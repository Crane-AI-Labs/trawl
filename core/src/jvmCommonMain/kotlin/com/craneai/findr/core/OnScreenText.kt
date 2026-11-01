package com.craneai.findr.core

/**
 * Which of the text a recogniser read off a picture is kept for the keyword index.
 *
 * The long rule is the one the app always had: the picture's whole reading is kept when it holds at least [LONG_LETTERS] letters,
 * whatever they say (logos and stray glyphs have few letters, a page or a caption has many). It is unchanged, byte for byte.
 *
 * The short rule is new. A one-word title card ("Harbour"), a sign ("EXIT") or a meme word has fewer letters than that and was lost,
 * in videos and in photos alike. A short reading is kept only when every line kept is plainly a word: the recogniser is sure of it,
 * it stands level and big enough to be a sign rather than a texture, it is made of letters, and it is spelt like a word (lower case,
 * UPPER CASE or Capitalised, with more than one distinct letter). A recogniser that reports no confidence (see [OcrLine.UNKNOWN])
 * gives no evidence, so its short readings are dropped, as before.
 */
object OnScreenText {
  /** The letters a picture's whole reading needs to be kept whatever it says (the long rule). */
  const val LONG_LETTERS = 10

  /** The fewest letters in a word of the short rule. */
  const val MIN_WORD_LETTERS = 3

  /**
   * The least confidence of a line, and of its least sure word, for the short rule (ML Kit gives 0..1).
   * Calibrated on 1255 pictures read on three phones (A06, TECNO KN4, itel, at 768 / 1280 / 2048 px): at 0.75 the rule keeps
   * 14 to 15 real short lines and no junk line, at 0.80 only 8. The first junk reading (a text-free photo) appears at 0.71, so
   * the margin is thin: do not go lower without re-running the probe.
   */
  const val MIN_LINE_CONFIDENCE = 0.75f
  const val MIN_WORD_CONFIDENCE = 0.70f

  /** The most a line may be turned from level, in degrees, either way. */
  const val MAX_ANGLE = 15f

  /** The least height of a line as a share of the picture's height: a sign or a caption, not a texture. */
  const val MIN_HEIGHT_FRACTION = 0.015f

  /**
   * @param text every line kept, one per line, for the keyword index
   * @param long true when the long rule kept it: the reading may then also be embedded as text. A short reading is words only
   *   (one word makes a poor text vector, and a vector is what could move a ranking).
   */
  class Kept(val text: String, val long: Boolean) {
    val lines: List<String> get() = text.lines()
  }

  /** The long rule alone: what the app kept before the short rule existed. */
  fun legacy(text: String): String? = text.trim().takeIf { t -> t.count { it.isLetter() } >= LONG_LETTERS }

  /** The long rule, then the short one. Null when nothing is kept. */
  fun keep(lines: List<OcrLine>): Kept? {
    legacy(lines.joinToString("\n") { it.text })?.let { return Kept(it, true) }
    val words = lines.filter(::isPlainWord).map { it.text.trim() }
    return if (words.isEmpty()) null else Kept(words.joinToString("\n"), false)
  }

  /** True when [line] is, by itself, plainly a word or a few words that someone put on the picture. */
  fun isPlainWord(line: OcrLine): Boolean {
    val text = line.text.trim()
    if (line.confidence == OcrLine.UNKNOWN || line.confidence < MIN_LINE_CONFIDENCE) return false
    if (line.minWordConfidence != OcrLine.UNKNOWN && line.minWordConfidence < MIN_WORD_CONFIDENCE) return false
    if (kotlin.math.abs(line.angle) > MAX_ANGLE) return false
    if (line.heightFraction < MIN_HEIGHT_FRACTION) return false
    val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return false
    // every token is a word: letters (and the apostrophe or hyphen inside one), spelt like a word
    if (!tokens.all(::isSpeltLikeAWord)) return false
    return tokens.any { t -> t.count { it.isLetter() } >= MIN_WORD_LETTERS }
  }

  private fun isSpeltLikeAWord(token: String): Boolean {
    val core = token.trim { !it.isLetter() }
    if (core.isEmpty() || core.any { !it.isLetter() && it != '\'' && it != '’' && it != '-' }) return false
    return core.split('\'', '’', '-').filter { it.isNotEmpty() }.all(::isSpeltLikeASegment)
  }

  /** One run of letters: not one letter repeated, and lower case, UPPER CASE or Capitalised ("tFo" and "OoOo" are texture, not words). */
  private fun isSpeltLikeASegment(letters: String): Boolean {
    if (letters.length < 2) return true                    // "a", "I" next to a real word; the line needs one long word anyway
    if (letters.toSet().size < 2) return false             // "oooo", "llll"
    val caps = letters.count { it.isUpperCase() }
    return caps == 0 || caps == letters.length || (letters.first().isUpperCase() && caps == 1)
  }
}
