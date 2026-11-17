package com.craneai.findr.search

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Random marked texts and windows: whatever the cut, the result is balanced, whole code points, a stretch of the input, and keeps the window's own text. */
class SnippetWindowPropertyTest {
  private val pieces = listOf("a", "b", "é", "ng", "'", "’", " ", "  ", "«", "»", "😀", "👍🏽", "👨\u200D👩\u200D👧", "🇺🇬", "🇰🇪", "中", "文", "𠀀", "\u0301", "\u200C", "\u200D", "\uFE0F", "1", ".", "-", "مرحبا", "привет", "«x»", "ng'ombe")

  @Test fun anyWindowOfAnyMixedTextStaysWellFormed() {
    val rnd = Random(42)
    val problems = linkedMapOf<String, String>()
    repeat(50_000) {
      val text = (0 until rnd.nextInt(0, 30)).joinToString("") { pieces[rnd.nextInt(pieces.size)] }
      val from = rnd.nextInt(0, text.length + 1)
      val to = rnd.nextInt(from, text.length + 1)
      fun note(kind: String) { problems.getOrPut(kind) { "text=${text.map { "\\u%04x".format(it.code) }.joinToString("")} (${text}) from=$from to=$to" } }
      val out = try { SnippetEdges.window(text, from, to) } catch (e: Throwable) { note("throws ${e.javaClass.simpleName}"); return@repeat }
      var depth = 0
      for (c in out) { if (c == '«') depth++ else if (c == '»') depth--; if (depth !in 0..1) { note("unbalanced"); break } }
      if (depth != 0) note("unclosed")
      if (out.contains("«»")) note("empty highlight")
      for ((i, c) in out.withIndex()) {
        if (c.isHighSurrogate() && (i + 1 >= out.length || !out[i + 1].isLowSurrogate())) note("lone high surrogate")
        if (c.isLowSurrogate() && (i == 0 || !out[i - 1].isHighSurrogate())) note("lone low surrogate")
      }
      val plainIn = text.replace("«", "").replace("»", "")
      val plainOut = out.removePrefix("…").removeSuffix("…").replace("«", "").replace("»", "")
      if (!plainIn.contains(plainOut)) note("output is not a stretch of the input")
      if (out.isNotEmpty() && (out.removePrefix("…").removeSuffix("…").let { it.isNotEmpty() && (it.first().isWhitespace() || it.last().isWhitespace()) })) note("edge whitespace")
      // the requested window's own text must survive
      val b = text.substring(0, from).replace("«", "").replace("»", "").length
      val e = b + text.substring(from, to).replace("«", "").replace("»", "").length
      val core = plainIn.substring(b, e).trim()
      if (core.isNotEmpty() && !plainOut.contains(core)) note("window text lost")
      if (core.isNotEmpty() && out.isEmpty()) note("empty for non-blank window")
    }
    if (problems.isNotEmpty()) fail(problems.entries.joinToString("\n") { "${it.key}: ${it.value}" })
  }

  /** Plain text (« » are the author's quotation marks): still whole code points, still a stretch of the input, still the asked-for text, and never longer than the budget plus the overrun at each end. */
  @Test fun anyWindowOfPlainTextStaysWithinTheOverrun() {
    val rnd = Random(7)
    val long = listOf("x".repeat(300), "\uD801\uDC00".repeat(150), "e\u0301".repeat(150))
    val problems = linkedMapOf<String, String>()
    repeat(20_000) {
      val text = (0 until rnd.nextInt(0, 30)).joinToString("") { if (rnd.nextInt(8) == 0) long[rnd.nextInt(long.size)] else pieces[rnd.nextInt(pieces.size)] }
      val from = rnd.nextInt(0, text.length + 1)
      val to = rnd.nextInt(from, text.length + 1)
      fun note(kind: String) { problems.getOrPut(kind) { "text=${text.map { "\\u%04x".format(it.code) }.joinToString("")} from=$from to=$to" } }
      val out = try { SnippetEdges.window(text, from, to, highlights = false) } catch (e: Throwable) { note("throws ${e.javaClass.simpleName}"); return@repeat }
      val body = out.removePrefix("…").removeSuffix("…")
      for ((i, c) in out.withIndex()) {
        if (c.isHighSurrogate() && (i + 1 >= out.length || !out[i + 1].isLowSurrogate())) note("lone high surrogate")
        if (c.isLowSurrogate() && (i == 0 || !out[i - 1].isHighSurrogate())) note("lone low surrogate")
      }
      if (!text.contains(body)) note("output is not a stretch of the input")
      val core = text.substring(from, to).trim()
      if (core.isNotEmpty() && !body.contains(core)) note("window text lost")
      // a cluster is never cut, so allow the few characters one emoji or accent sequence adds at each end
      if (body.length > (to - from) + 2 * (SnippetEdges.WORD_OVERRUN + 16)) note("longer than the budget plus the overrun")
    }
    if (problems.isNotEmpty()) fail(problems.entries.joinToString("\n") { "${it.key}: ${it.value}" })
  }
}
