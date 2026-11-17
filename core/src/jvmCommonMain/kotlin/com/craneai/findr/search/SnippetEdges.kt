package com.craneai.findr.search

/**
 * Tidies the two ends of a snippet. A snippet is a piece cut out of a longer text, and the cut can land inside a word:
 * a chunk starts where a character count put it ("h's notice in writing" for "one month's notice in
 * writing"), and a word-index excerpt can start on the "s" of "month's". The half word at an end is dropped and an
 * ellipsis says the text goes on there.
 *
 * It works on the text as shown, with the matched words still wrapped in « », and only ever removes whole characters
 * of plain text, so the highlighting stays aligned with the words it marked. A half word that holds a marked character
 * is kept, so a match is never cut away.
 *
 * Whether an end is inside a word depends on the text around the excerpt, which the caller knows and this does not:
 * pass [tidy] the characters just before and just after (empty when the excerpt reaches the start or the end of the
 * text). Languages written without spaces (Chinese, Japanese, Thai, ...) have no word to cut at, so nothing is trimmed there.
 * [window] takes offsets in the marked input and expands them to complete words, emoji sequences and highlights. Its character
 * budget is soft: a word may run [WORD_OVERRUN] characters past it, and a longer run (a base64 line, a minified blob) is cut there,
 * at a clean code point; unspaced scripts cut at Unicode code points rather than dictionary word boundaries. Pass `highlights = false`
 * for plain text whose « » are the author's quotation marks, not highlights.
 */
object SnippetEdges {
  const val ELLIPSIS = "…"
  private const val OPEN = '«'
  private const val CLOSE = '»'

  /** How far past the requested edge a window may stretch to finish a word, so a preview stays about one row long. A highlight is never cut. */
  const val WORD_OVERRUN = 200

  fun window(snippet: String, from: Int, to: Int, highlights: Boolean = true): String {
    require(from in 0..snippet.length && to in from..snippet.length)
    val plain = StringBuilder(snippet.length)
    val marked = ArrayList<Boolean>(snippet.length)
    val offsets = IntArray(snippet.length + 1)
    var marking = false
    for ((index, character) in snippet.withIndex()) {
      offsets[index] = plain.length
      when (character) {
        OPEN -> if (highlights) marking = true else { plain.append(character); marked += false }
        CLOSE -> if (highlights) marking = false else { plain.append(character); marked += false }
        else -> { plain.append(character); marked += marking }
      }
    }
    offsets[snippet.length] = plain.length
    var start = offsets[from]
    var end = offsets[to]
    val wantedStart = start
    val wantedEnd = end
    while (start > 0 && start < plain.length && (marked[start - 1] && marked[start] || insideCluster(plain, start) || wantedStart - start < WORD_OVERRUN && insideWordRun(plain, start))) start--
    while (end > 0 && end < plain.length && (marked[end - 1] && marked[end] || insideCluster(plain, end) || end - wantedEnd < WORD_OVERRUN && insideWordRun(plain, end))) end++
    val lead = start > 0
    val trail = end < plain.length
    while (start < end && plain[start].isWhitespace() && !marked[start]) start++
    while (end > start && plain[end - 1].isWhitespace() && !marked[end - 1]) end--
    if (start == end) return ""
    return buildString {
      if (lead && plain[start] != ELLIPSIS[0]) append(ELLIPSIS)
      var open = false
      for (index in start until end) {
        if (marked[index] != open) { append(if (marked[index]) OPEN else CLOSE); open = marked[index] }
        append(plain[index])
      }
      if (open) append(CLOSE)
      if (trail && plain[end - 1] != ELLIPSIS[0]) append(ELLIPSIS)
    }
  }

  private const val ZWNJ = 0x200C
  private const val ZWJ = 0x200D

  /** A cut between these two characters would split one emoji (a ZWJ family, a skin tone, a flag, a tag flag) or a word joined by a zero-width (non-)joiner, as Persian writes it. */
  private fun joinsCluster(text: CharSequence, cut: Int, left: Int, right: Int): Boolean {
    if (right == ZWJ || right == ZWNJ) return !Character.isWhitespace(left)
    if (left == ZWJ || left == ZWNJ) return !Character.isWhitespace(right)
    if (right in 0x1F3FB..0x1F3FF || right in 0xE0020..0xE007F) return !Character.isWhitespace(left)
    if (isRegionalIndicator(left) && isRegionalIndicator(right)) {
      // flags are pairs: the cut is inside one when an odd number of indicators come before it
      var count = 0
      var at = cut
      while (at > 0 && isRegionalIndicator(Character.codePointBefore(text, at))) { count++; at -= 2 }
      return count % 2 == 1
    }
    return false
  }

  private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

  /** A cut here would split one character: a surrogate pair, a base letter from its mark, or an emoji sequence. Never cut, however long. */
  private fun insideCluster(text: CharSequence, cut: Int): Boolean {
    if (Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) return true
    val right = Character.codePointAt(text, cut)
    val type = Character.getType(right)
    if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) return true
    return joinsCluster(text, cut, Character.codePointBefore(text, cut), right)
  }

  /** A cut here would leave half a word (letters either side, an apostrophe between letters counting as one). */
  private fun insideWordRun(text: CharSequence, cut: Int): Boolean {
    val left = Character.codePointBefore(text, cut)
    val right = Character.codePointAt(text, cut)
    val before = cut - Character.charCount(left)
    val after = cut + Character.charCount(right)
    val leftIsWord = isWord(left) || isApostrophe(left) && before > 0 && isWord(Character.codePointBefore(text, before))
    val rightIsWord = isWord(right) || isApostrophe(right) && after < text.length && isWord(Character.codePointAt(text, after))
    return leftIsWord && rightIsWord && !(isApostrophe(left) && isApostrophe(right))
  }

  /**
   * @param snippet the excerpt, with « » around matched words and optionally an ellipsis at either end
   * @param before the (up to two) characters of the source just before the excerpt; empty when it starts the source
   * @param after the character(s) of the source just after it; empty when it ends the source
   */
  fun tidy(snippet: String, before: CharSequence = "", after: CharSequence = ""): String {
    var lead = snippet.startsWith(ELLIPSIS)
    var trail = snippet.length > 1 && snippet.endsWith(ELLIPSIS)
    val body = snippet.removePrefix(if (lead) ELLIPSIS else "").removeSuffix(if (trail) ELLIPSIS else "")

    // plain text and, per character, whether the engine marked it
    val plain = StringBuilder(body.length)
    val marked = ArrayList<Boolean>(body.length)
    var on = false
    for (c in body) {
      when (c) {
        OPEN -> on = true
        CLOSE -> on = false
        else -> { plain.append(c); marked += on }
      }
    }
    var from = 0
    var to = plain.length
    var changed = false

    if (before.isNotEmpty() && plain.isNotEmpty() && insideWord(before, plain[0], leftOf = true)) {
      var gap = 0
      while (gap < plain.length && !plain[gap].isWhitespace()) gap++
      var cut = gap
      while (cut < plain.length && plain[cut].isWhitespace()) cut++
      if (gap > 0 && cut < to && (0 until cut).none { marked[it] }) { from = cut; lead = true; changed = true }
    }
    if (after.isNotEmpty() && to > from && insideWord(after, plain[to - 1], leftOf = false)) {
      var gap = to - 1
      while (gap >= from && !plain[gap].isWhitespace()) gap--
      var keep = gap
      while (keep > from && plain[keep - 1].isWhitespace()) keep--
      if (gap > from && keep > from && (gap until to).none { marked[it] }) { to = keep; trail = true; changed = true }
    }
    if (!changed) return snippet

    val out = StringBuilder()
    if (lead) out.append(ELLIPSIS)
    var open = false
    for (i in from until to) {
      if (marked[i] != open) { out.append(if (marked[i]) OPEN else CLOSE); open = marked[i] }
      out.append(plain[i])
    }
    if (open) out.append(CLOSE)
    if (trail) out.append(ELLIPSIS)
    return out.toString()
  }

  /**
   * Is the cut between [outside] (the source beside the excerpt) and [edge] (the excerpt's own end character) inside a word?
   * [leftOf]: [outside] is the text before the excerpt (its last character touches [edge]); otherwise it is the text after
   * (its first character touches [edge]).
   * Letters and digits make a word, and so does an apostrophe between two of them ("month's", "ng'ombe"); a hyphen, a slash
   * or a full stop does not join, so a cut after "well-" is a clean one.
   */
  private fun insideWord(outside: CharSequence, edge: Char, leftOf: Boolean): Boolean {
    val near = if (leftOf) outside.last() else outside.first()
    val far = if (outside.length < 2) null else if (leftOf) outside[outside.length - 2] else outside[1]
    val nearIsWord = isWord(near) || (isApostrophe(near) && far != null && isWord(far))
    val edgeIsWord = isWord(edge) || isApostrophe(edge)
    if (!nearIsWord || !edgeIsWord) return false
    // two apostrophes, or an apostrophe on each side of nothing, are not a word
    return !(isApostrophe(near) && isApostrophe(edge))
  }

  /** A letter or digit of a script that puts spaces between words; the unspaced scripts have no word edge to cut at. */
  private fun isWord(c: Char) = isWord(c.code)

  private fun isWord(codePoint: Int): Boolean {
    if (!(Character.isLetterOrDigit(codePoint) || Character.getType(codePoint).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() })) return false
    return when (Character.UnicodeScript.of(codePoint)) {
      Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
      Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
      -> false
      else -> true
    }
  }

  private fun isApostrophe(character: Char) = isApostrophe(character.code)

  private fun isApostrophe(codePoint: Int) = codePoint == '\''.code || codePoint == '’'.code || codePoint == 'ʼ'.code || codePoint == '‘'.code
}
