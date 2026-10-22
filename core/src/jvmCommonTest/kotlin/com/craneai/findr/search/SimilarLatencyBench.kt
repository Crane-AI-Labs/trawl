package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/** Seed-to-results latency of Searcher.similar on this JVM (a Mac, not a phone). Runs only with FINDR_BENCH=1; prints, asserts nothing. */
class SimilarLatencyBench {
  @Test fun latency() {
    assumeTrue(System.getenv("FINDR_BENCH") == "1")
    for (n in listOf(3_000, 30_000)) {
      val dir = Files.createTempDirectory("findr-sim-bench").toFile()
      val store = IndexStore.open(dir, log = {})
      val vectors = VectorIndex(parallelism = 1)
      val random = Random(5)
      fun unit(): FloatArray { val v = FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() }; val s = sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(v.size) { v[it] / s } }
      store.upsertFromCrawl((1..n).map { CrawlRecord("external_primary", it.toLong(), "content://m/$it", "DCIM/", "IMG_$it.jpg", FileKind.IMAGE, null, 1000, it.toLong(), dateMillis = it.toLong()) })
      val ids = store.knownMediaStoreIds("external_primary")
      var photoSeed = 0L; var videoSeed = 0L
      // n photos, plus one video of 8 frames as the last file
      for (i in 1..n) {
        val f = ids.getValue(i.toLong()); val v = unit()
        val seg = store.saveSegments(f, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = v)), FileState.UNDERSTOOD)
        vectors.add(seg[0], f, FileKind.IMAGE, Modality.IMAGE, false, v)
        if (i == n / 2) photoSeed = f
      }
      store.upsertFromCrawl(listOf(CrawlRecord("external_primary", n + 1L, "content://m/v", "DCIM/", "V.mp4", FileKind.VIDEO, null, 1000, 1, dateMillis = 1)))
      videoSeed = store.knownMediaStoreIds("external_primary").getValue(n + 1L)
      val frames = (1..8).map { unit() }
      val segs = store.saveSegments(videoSeed, frames.mapIndexed { i, v -> Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = i * 1000L, locEnd = i * 1000L + 999, vector = v) }, FileState.UNDERSTOOD)
      frames.forEachIndexed { i, v -> vectors.add(segs[i], videoSeed, FileKind.VIDEO, Modality.IMAGE, false, v) }
      val searcher = Searcher(store, vectors)
      for ((label, seed) in listOf("photo (1 seed vector)" to photoSeed, "video (8 seed vectors)" to videoSeed)) {
        repeat(5) { searcher.similar(seed, KindFilter.ALL, 60) }
        val ms = (1..30).map { val t = System.nanoTime(); searcher.similar(seed, KindFilter.ALL, 60); (System.nanoTime() - t) / 1e6 }.sorted()
        println("SIMILAR-BENCH n=$n twoStage=${vectors.twoStageApplies()} $label: median %.1f ms, p95 %.1f ms, max %.1f ms".format(ms[15], ms[28], ms[29]))
      }
      vectors.close(); store.close(); dir.deleteRecursively()
    }
  }
}
