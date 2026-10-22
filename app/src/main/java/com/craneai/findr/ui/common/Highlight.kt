package com.craneai.findr.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** One run of a snippet; `matched` runs were wrapped in « » by the engine. */
data class SnippetRun(val text: String, val matched: Boolean)

/** Splits "pay «school» fees" into runs. Tolerates unbalanced markers: stray ones are dropped, text is kept. */
fun parseSnippet(snippet: String): List<SnippetRun> {
  val runs = ArrayList<SnippetRun>()
  val buf = StringBuilder()
  var matched = false
  fun flush() {
    if (buf.isNotEmpty()) runs += SnippetRun(buf.toString(), matched)
    buf.clear()
  }
  for (c in snippet) {
    when (c) {
      '«' -> { flush(); matched = true }
      '»' -> { flush(); matched = false }
      else -> buf.append(c)
    }
  }
  flush()
  return runs
}

fun highlightedSnippet(snippet: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
  for (run in parseSnippet(snippet)) {
    if (run.matched) withStyle(style) { append(run.text) } else append(run.text)
  }
}

/** The plain words of a snippet with the markers removed, for TalkBack. */
fun plainSnippet(snippet: String): String = parseSnippet(snippet).joinToString("") { it.text }
