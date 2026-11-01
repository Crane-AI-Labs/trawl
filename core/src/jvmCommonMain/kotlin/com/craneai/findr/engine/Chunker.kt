package com.craneai.findr.engine

/**
 * Text cut into ~400-char pieces at word boundaries, overlapping a little so a sentence split in two is still found.
 * 400 chars plus the "title: none | text: " prefix stays inside the 128-token bucket, the cheapest per token on the
 * phone (the build notes, M1). Same rule as scripts/eval_quality.py, so the eval numbers carry over.
 */
object Chunker {
  const val CHUNK_CHARS = 400
  const val OVERLAP_CHARS = 60

  /**
   * [tick] runs once per chunk, so a caller can stop a very long text (cancellation, a deadline) between chunks.
   * The word boundary is looked for only inside the chunk's own window, so a text with no spaces at all (a minified blob,
   * a base64 line, a corrupt file read as text) costs one chunk's work per chunk, not a scan back to the start of the text.
   */
  fun chunks(raw: String, tick: () -> Unit = {}): List<String> {
    val text = raw.replace(WHITESPACE, " ").trim()
    val out = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      var end = minOf(text.length, start + CHUNK_CHARS)
      if (end < text.length) {
        val space = lastSpace(text, end, start + CHUNK_CHARS / 2)
        if (space != null) end = space
      }
      tick()
      out += text.substring(start, end)
      if (end >= text.length) break
      start = maxOf(end - OVERLAP_CHARS, start + 1)
    }
    return out
  }

  /** The last space at or before [from] and after [floor], or null; never looks further back than [floor]. */
  private fun lastSpace(text: String, from: Int, floor: Int): Int? {
    var i = from
    while (i > floor) {
      if (text[i] == ' ') return i
      i--
    }
    return null
  }

  private val WHITESPACE = Regex("\\s+")
}
