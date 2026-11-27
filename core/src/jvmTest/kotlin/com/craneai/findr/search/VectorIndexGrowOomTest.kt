package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The desktop crash of 12 Nov ("arraycopy: last destination index 204073728 out of bounds for short[204072960]", 265,720 rows): a
 * heap index that runs out of memory while growing must stay whole. The desktop's worker catches every Throwable per file, so an
 * index left half-grown by an OutOfMemoryError is used again, and the next add wrote past the end of the vectors.
 *
 * The failure is real, not injected: a child JVM with a 64 MB heap holds 20,000 rows (31 MB of fp16) at full capacity, so growing
 * them by half (another 46 MB beside the old 31 MB) cannot fit.
 */
class VectorIndexGrowOomTest {
  @Test fun anIndexThatCannotGrowStaysWholeAndKeepsWorking() {
    val out = runChild()
    val lines = out.lines().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
    assertEquals(out, "OutOfMemoryError", lines["firstOverflow"])
    // The second add must fail the same honest way (no room), never by writing past the end of the vectors.
    assertEquals(out, "OutOfMemoryError", lines["secondOverflow"])
    assertEquals(out, "$ROWS", lines["size"])
    assertEquals(out, "true", lines["rowsIntact"])
    assertEquals(out, "true", lines["addAfterRemove"])
  }

  private fun runChild(): String {
    val javaBin = File(System.getProperty("java.home"), "bin/java").path
    val cp = System.getProperty("java.class.path")
    val p = ProcessBuilder(javaBin, "-Xmx64m", "-XX:+UseSerialGC", "-cp", cp, Child::class.java.name).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    assertTrue("child did not finish", p.waitFor(120, TimeUnit.SECONDS))
    return text
  }

  object Child {
    @JvmStatic fun main(args: Array<String>) {
      val random = Random(7)
      val index = VectorIndex(initialCapacity = ROWS, parallelism = 1)
      val probes = HashMap<Long, FloatArray>()
      for (s in 1L..ROWS) {
        val v = unit(random)
        if (s % 1000 == 0L) probes[s] = v
        index.add(s, s / 4, FileKind.PDF, Modality.TEXT, false, v)
      }
      // The desktop worker's per-file catch: the error is logged and the next file goes on.
      println("firstOverflow=" + outcome { index.add(ROWS + 1L, 99_999, FileKind.PDF, Modality.TEXT, false, unit(random)) })
      println("secondOverflow=" + outcome { index.add(ROWS + 2L, 99_999, FileKind.PDF, Modality.TEXT, false, unit(random)) })
      println("size=${index.size}")
      // Every probe still scores ~1 against itself, from the row that holds its segment.
      val intact = probes.all { (segment, v) ->
        val scores = index.scores(v)
        index.read { (0 until index.size).any { r -> index.segmentId(r) == segment && scores[r] > 0.99f } }
      }
      println("rowsIntact=$intact")
      index.removeFile(1)   // frees rows: an add then needs no growth and must work
      println("addAfterRemove=" + (outcome { index.add(ROWS + 3L, 99_999, FileKind.PDF, Modality.TEXT, false, unit(random)) } == "ok"))
    }

    private fun outcome(block: () -> Unit): String = try { block(); "ok" } catch (t: Throwable) { t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "") }
      .let { if (it.startsWith("OutOfMemoryError")) "OutOfMemoryError" else it }

    private fun unit(random: Random): FloatArray {
      val v = FloatArray(768) { (random.nextDouble() * 2 - 1).toFloat() }
      val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      return FloatArray(v.size) { v[it] / n }
    }
  }

  companion object {
    const val ROWS = 20_000
  }
}
