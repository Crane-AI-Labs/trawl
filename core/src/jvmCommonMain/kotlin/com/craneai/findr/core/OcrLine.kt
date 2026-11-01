package com.craneai.findr.core

/**
 * One line of text a recogniser read from a picture, with what it said about it: how sure it was, how the line is turned, how big
 * it stands on the picture and in which language it thinks it is. A whole picture's words are judged by [OnScreenText] with these.
 *
 * @param confidence 0..1, or [UNKNOWN] when the recogniser gives none (then the line is judged by its text alone)
 * @param angle degrees the line is rotated from upright (0 for level text)
 * @param heightFraction the line's box height as a share of the picture's height (0 when unknown)
 * @param widthFraction the line's box width as a share of the picture's width (0 when unknown)
 * @param language the recogniser's guess ("en", "und" when it has none), or null when it gives none
 * @param minWordConfidence the least sure word of the line, [UNKNOWN] when not given
 * @param words how many words (elements) the recogniser split the line into, 0 when not given
 */
class OcrLine(
  val text: String,
  val confidence: Float,
  val angle: Float,
  val heightFraction: Float,
  val widthFraction: Float,
  val language: String?,
  val minWordConfidence: Float = UNKNOWN,
  val words: Int = 0,
) {
  companion object {
    const val UNKNOWN = -1f
  }
}
