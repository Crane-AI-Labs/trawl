package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.Modality
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repeatable microbenchmarks for the North Star core timings (NS-1, NS-2):
 *  - loadFrom (cold rebuild from SQLite rows into a mapped VectorFile)
 *  - removeFile (write-lock stall per updated/deleted file)
 *  - rowsOfFiles (hybrid search lookup of word-matched files)
 *  - exact 768-d scan vs TwoStage (256-d -> 768-d rescore) at 50k rows
 *
 * Prints structured `BENCH:` lines for before/after hill-climbing comparisons.
 */
class CoreTimingsBench {
  private fun unit(random: Random, dim: Int = Fp16.DIM): FloatArray {
    val v = FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() }
    val inv = (1.0 / sqrt(v.sumOf { (it * it).toDouble() })).toFloat()
    return FloatArray(dim) { v[it] * inv }
  }

  private fun toBlob(v: FloatArray): ByteArray {
    val out = ByteArray(v.size * 2)
    for (i in v.indices) {
      val h = Fp16.toHalf(v[i]).toInt()
      out[2 * i] = (h and 0xFF).toByte()
      out[2 * i + 1] = ((h ushr 8) and 0xFF).toByte()
    }
    return out
  }

  @Test fun measureVectorIndexCoreTimings() {
    val rows = 50_000
    val chunksPerFile = 5
    val random = Random(42)
    // Pre-generate 64 distinct blobs and cycle them so fixture setup stays fast and deterministic.
    val pool = Array(64) { toBlob(unit(random)) }
    val queries = Array(8) { unit(random) }
    val allKinds = BooleanArray(FileKind.entries.size) { true }

    val dir = Files.createTempDirectory("trawl-bench-vec").toFile()
    try {
      val backing = dir.resolve("vectors.bin")
      val index = VectorIndex(
        parallelism = 4,
        backing = backing,
        settleDelayMs = -1,
        twoStage = TwoStage.DEFAULT,
      )

      val tLoad0 = System.nanoTime()
      index.loadFrom({ 1L }) { sink ->
        for (i in 0 until rows) {
          val fileId = (i / chunksPerFile).toLong() + 1L
          sink(i.toLong() + 1L, fileId, FileKind.DOCUMENT, Modality.TEXT, 0, pool[i and 63])
        }
      }
      val loadMs = (System.nanoTime() - tLoad0) / 1e6

      // Warmup JIT
      repeat(2) {
        index.scores(queries[it])
        index.read {
          val c = index.stageCandidates(queries[it], allKinds)
          index.scoresAt(queries[it], c)
        }
      }

      // Exact scan latency
      val exactTimes = DoubleArray(queries.size) { q ->
        val t0 = System.nanoTime()
        index.scores(queries[q])
        (System.nanoTime() - t0) / 1e6
      }.sortedArray()

      // Two-stage scan latency
      val twoStageTimes = DoubleArray(queries.size) { q ->
        val t0 = System.nanoTime()
        index.read {
          val c = index.stageCandidates(queries[q], allKinds)
          index.scoresAt(queries[q], c)
        }
        (System.nanoTime() - t0) / 1e6
      }.sortedArray()

      // rowsOfFiles latency (200 calls, 50 sorted fileIds each)
      val fileSets = Array(200) { k ->
        LongArray(50) { j -> ((k * 37 + j * 13) % (rows / chunksPerFile)).toLong() + 1L }.also { it.sort() }
      }
      val tRows0 = System.nanoTime()
      var totalRowsFound = 0
      index.read {
        for (ids in fileSets) totalRowsFound += index.rowsOfFiles(ids).size
      }
      val rowsOfFilesMs = (System.nanoTime() - tRows0) / 1e6

      // removeFile latency (500 files = 2,500 rows removed under write lock)
      val tRem0 = System.nanoTime()
      var removed = 0
      for (f in 1L..500L) removed += index.removeFile(f * 17L)
      val removeFileMs = (System.nanoTime() - tRem0) / 1e6

      index.close()
      assertTrue(totalRowsFound > 0)
      assertTrue(removed == 500 * chunksPerFile)

      println("BENCH: loadFrom_50k_ms=${"%.2f".format(loadMs)}")
      println("BENCH: exactScan_50k_median_ms=${"%.2f".format(exactTimes[exactTimes.size / 2])}")
      println("BENCH: twoStageScan_50k_median_ms=${"%.2f".format(twoStageTimes[twoStageTimes.size / 2])}")
      println("BENCH: rowsOfFiles_200x50_ms=${"%.2f".format(rowsOfFilesMs)}")
      println("BENCH: removeFile_500_ms=${"%.2f".format(removeFileMs)} (per_call_ms=${"%.3f".format(removeFileMs / 500.0)})")
    } finally {
      dir.deleteRecursively()
    }
  }
}
