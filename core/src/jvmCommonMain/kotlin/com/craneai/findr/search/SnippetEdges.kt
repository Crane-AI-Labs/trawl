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
 */
object SnippetEdges {
  const val ELLIPSIS = "…"
  private const val OPEN = '«'
  private const val CLOSE = '»'

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
  private fun isWord(c: Char): Boolean {
    if (!(Character.isLetterOrDigit(c) || Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() })) return false
    return when (Character.UnicodeScript.of(c.code)) {
      Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.THAI,
      Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
      -> false
      else -> true
    }
  }

  private fun isApostrophe(c: Char) = c == '\'' || c == '’' || c == 'ʼ' || c == '‘'
}
