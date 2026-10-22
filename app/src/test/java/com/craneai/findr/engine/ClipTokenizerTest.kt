package com.craneai.findr.engine

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer against open_clip's own output. The golden file is written by scripts/build_clip_tokenizer.py (JSON text, TAB, 77 ids);
 * gradle runs unit tests with the module directory as the working directory, so the real asset is read from src/main/assets.
 */
class ClipTokenizerTest {
  private val tok = File("src/main/assets/${ClipTokenizer.ASSET}").inputStream().use { ClipTokenizer.fromGzip(it) }

  private class Case(val text: String, val ids: LongArray)

  private val golden: List<Case> = File("src/test/resources/clip_tokenizer_golden.tsv").readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { line ->
    val tab = line.indexOf('\t')
    Case(jsonString(line.substring(0, tab)), line.substring(tab + 1).split(' ').map { it.toLong() }.toLongArray())
  }

  /**
   * Cases that cannot match, on purpose: ftfy repairs mojibake (text decoded with the wrong encoding, here UTF-8 read as Windows-1252) and
   * this port does not carry ftfy's encoding detector. The golden keeps them so the gap stays visible; the test checks they still differ,
   * so porting the repair later makes this fail and the entry go.
   */
  private val mojibake = setOf("cafÃ© au lait", "donâ€™t", "naÃ¯ve", "âœ” done", "Ã€")

  @Test fun everyGoldenCaseMatchesOpenClipExactly() {
    assertTrue("golden has ${golden.size} cases", golden.size > 500)
    val bad = golden.filter { it.text !in mojibake && !tok.encode(it.text).contentEquals(it.ids) }
    assertTrue(bad.joinToString("\n") { "${it.text.take(60).map { c -> if (c.code in 32..126) c.toString() else "\\u%04x".format(c.code) }.joinToString("")}\n  want ${it.ids.take(24)}\n  got  ${tok.encode(it.text).take(24)}" }, bad.isEmpty())
  }

  @Test fun mojibakeIsTheOnlyKnownDifference() {
    for (t in mojibake) {
      val c = golden.single { it.text == t }
      assertNotEquals("'$t' now matches, so the ftfy gap is closed: remove it from the expected-to-differ set", c.ids.toList(), tok.encode(t).toList())
    }
  }

  @Test fun shapeIsStartIdsEndThenZeros() {
    val ids = tok.encode("a photo of a dog")
    assertEquals(ClipTokenizer.CONTEXT, ids.size)
    assertEquals(ClipTokenizer.SOT, ids[0])
    assertEquals(ClipTokenizer.EOT, ids[6])
    assertTrue(ids.drop(7).all { it == 0L })
    assertArrayEquals(longArrayOf(ClipTokenizer.SOT, ClipTokenizer.EOT) + LongArray(75), tok.encode(""))
  }

  @Test fun tooLongIsCutAndForcedToEnd() {
    val ids = tok.encode("a ".repeat(200))
    assertEquals(ClipTokenizer.EOT, ids.last())
    assertEquals(ClipTokenizer.SOT, ids[0])
    assertTrue(ids.slice(1..75).all { it == 320L })   // 75 x "a</w>"
  }

  @Test fun heapIsSmall() {
    assertTrue("${tok.sizeBytes} bytes", tok.sizeBytes < 500_000)
  }

  @Test fun encodingAQueryIsCheap() {
    val queries = golden.map { it.text }.filter { it.length in 10..100 }
    val t0 = System.nanoTime()
    val fresh = File("src/main/assets/${ClipTokenizer.ASSET}").inputStream().use { ClipTokenizer.fromGzip(it) }
    val loadMs = (System.nanoTime() - t0) / 1e6
    val t1 = System.nanoTime()
    queries.forEach { fresh.encode(it) }   // first pass: empty word cache, cold JIT
    val coldMs = (System.nanoTime() - t1) / 1e6 / queries.size
    repeat(3) { queries.forEach { fresh.encode(it) } }
    val t2 = System.nanoTime()
    repeat(20) { queries.forEach { fresh.encode(it) } }
    val warmMs = (System.nanoTime() - t2) / 1e6 / (20 * queries.size)
    println("ClipTokenizer: load ${"%.0f".format(loadMs)} ms; per query over ${queries.size} queries: ${"%.3f".format(coldMs)} ms cold, ${"%.4f".format(warmMs)} ms warm (JVM)")
    assertTrue("$coldMs ms", coldMs < 2.0)
  }

  /** A JSON string literal with its quotes, as json.dumps(ensure_ascii=True) writes it. */
  private fun jsonString(lit: String): String {
    require(lit.length >= 2 && lit.first() == '"' && lit.last() == '"') { lit }
    val sb = StringBuilder()
    var i = 1
    while (i < lit.length - 1) {
      val c = lit[i++]
      if (c != '\\') { sb.append(c); continue }
      when (val e = lit[i++]) {
        'n' -> sb.append('\n')
        't' -> sb.append('\t')
        'r' -> sb.append('\r')
        'b' -> sb.append('\b')
        'f' -> sb.append('\u000c')
        '"', '\\', '/' -> sb.append(e)
        'u' -> { sb.append(lit.substring(i, i + 4).toInt(16).toChar()); i += 4 }
        else -> error("bad escape \\$e in $lit")
      }
    }
    return sb.toString()
  }
}
