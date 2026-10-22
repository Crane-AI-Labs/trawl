package com.craneai.findr.engine

/**
 * Text cut into ~400-char pieces at word boundaries, overlapping a little so a sentence split in two is still found.
 * 400 chars plus the "title: none | text: " prefix stays inside the 128-token bucket, the cheapest per token on the
 * phone (the build notes, M1). Same rule as scripts/eval_quality.py, so the eval numbers carry over.
 */
object Chunker {
  const val CHUNK_CHARS = 400
  const val OVERLAP_CHARS = 60

  fun chunks(raw: String): List<String> {
    val text = raw.replace(WHITESPACE, " ").trim()
    val out = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      var end = minOf(text.length, start + CHUNK_CHARS)
      if (end < text.length) {
        val space = text.lastIndexOf(' ', end).takeIf { it > start + CHUNK_CHARS / 2 }
        if (space != null) end = space
      }
      out += text.substring(start, end)
      if (end >= text.length) break
      start = maxOf(end - OVERLAP_CHARS, start + 1)
    }
    return out
  }

  private val WHITESPACE = Regex("\\s+")
}
