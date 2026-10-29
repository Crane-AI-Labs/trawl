package com.craneai.findr.core

/**
 * Word times from what a speech model reports per token, and the one rule for cleaning a stretch of speech that the text and the
 * words both go through, so the words kept are the words of the text kept.
 *
 * Parakeet (sherpa-onnx, CTC head) returns the tokens it wrote and, for each, the second of the audio it was emitted at, on a grid of
 * its frame length (80 ms). A token that begins a word carries a leading space ("▁" in the model's file). The first token of a word
 * gives the word's start; the last one's frame, plus one frame, its end. Both are moved [LATE_BIAS_MS] earlier, as the model is late.
 */
object WordTimes {
  /** Parakeet's frame: 8x subsampling of 10 ms features. A word lasts at least this long after its last token. */
  const val FRAME_MS = 80L

  /**
   * Parakeet's CTC head emits a token a little after the sound begins, so its times run late. Measured on Crane (2026-10-24) against
   * Kokoro TTS word times (the model's own durations), shipped int8 model through Silero VAD, over five clips of different pace and
   * pause (internal/research/video-times.md): the median signed error of a hit placed by its word group was +0.176, +0.110, +0.131,
   * +0.123 and +0.161 s. 130 ms, their median, leaves -0.017 to +0.044 s. Subtracted from every token time; a re-measure on a
   * different model or voice would move it.
   */
  const val LATE_BIAS_MS = 130L

  /**
   * The words in [tokens], with their times in the file ([baseMs] is where the audio handed to the model began). [seconds] is that
   * audio's length. Empty when the model gave no times (tokens and timestamps differ in number), so the caller keeps the whole
   * stretch's one time.
   */
  fun fromTokens(tokens: List<String>, timestamps: FloatArray, seconds: Double, baseMs: Long): List<SpokenWord> {
    if (tokens.isEmpty() || tokens.size != timestamps.size) return emptyList()
    val texts = ArrayList<String>()
    val starts = ArrayList<Long>()
    val lasts = ArrayList<Long>()
    val sb = StringBuilder()
    for (i in tokens.indices) {
      val piece = tokens[i].replace('▁', ' ')
      if (piece.isBlank()) continue
      val ms = ((timestamps[i] * 1000f).toLong() - LATE_BIAS_MS).coerceAtLeast(0)
      if (sb.isEmpty() || piece[0].isWhitespace()) {
        if (sb.isNotEmpty()) texts += sb.toString()
        sb.setLength(0)
        starts += ms
        lasts += ms
      } else lasts[lasts.size - 1] = ms
      sb.append(piece.trim())
    }
    if (sb.isNotEmpty()) texts += sb.toString()
    val limit = (seconds * 1000).toLong()
    return texts.indices.map { i ->
      val next = if (i + 1 < texts.size) starts[i + 1] else limit
      val end = minOf(lasts[i] + FRAME_MS, maxOf(next, starts[i]))
      SpokenWord(texts[i], baseMs + starts[i], baseMs + end)
    }
  }

  /**
   * Small decoders sometimes loop on noise ("the the the the…"). Drops everything when the words come faster than anyone speaks
   * (more than 7 a second) and cuts a word repeated more than 4 times in a row to 4. [textOf] reads a word's text.
   */
  fun <T> clean(words: List<T>, seconds: Double, textOf: (T) -> String): List<T> {
    if (words.isEmpty() || words.size / maxOf(seconds, 0.5) > 7.0) return emptyList()
    val out = ArrayList<T>(words.size)
    var run = 0
    for (w in words) {
      run = if (out.isNotEmpty() && textOf(out.last()).equals(textOf(w), ignoreCase = true)) run + 1 else 1
      if (run <= 4) out += w
    }
    return out
  }

  /** The text form of [clean]: [raw] split on white space, cleaned, joined with single spaces. */
  fun clean(raw: String, seconds: Double): String =
    clean(raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }, seconds) { it }.joinToString(" ")

  /**
   * The words of a cleaned stretch of speech: [tokens] and [timestamps] as the model gave them, cleaned by the same rule as the text.
   * Empty unless they spell exactly [text] when joined by spaces, so a mark's offset is always a place in the stored text.
   */
  fun forText(text: String, tokens: List<String>, timestamps: FloatArray, seconds: Double, baseMs: Long): List<SpokenWord> {
    if (text.isEmpty()) return emptyList()
    val words = clean(fromTokens(tokens, timestamps, seconds, baseMs), seconds) { it.text }
    return if (words.joinToString(" ") { it.text } == text) words else emptyList()
  }
}
