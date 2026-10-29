package com.craneai.findr.core

/**
 * Where inside a transcript piece each spoken word or stretch sits, so a search hit can say WHEN the matched words were said instead of
 * when the piece began. A piece is up to ~400 characters of speech segments joined by single spaces; a short phone video is one piece
 * from 0:00, so the piece's own start made every match read "at 0:00".
 *
 * The marks are stored with the piece (`segments.marks`) as `offset:startMs:endMs` entries joined by commas: [Mark.offset] is where the
 * marked text begins in the piece's text, the times are in the whole file's time (the ASR window offset is already added). A recogniser
 * that times its words (Parakeet) gives one mark per WORD, so a hit lands on the word (~19 bytes a word, ~1.3 KB for a full piece); the
 * rest give one per speech segment, a stretch of up to 15 s that a hit is placed in by character share. Both read the same way; a hit
 * reads only the one mark it points at.
 *
 * Pieces from before the marks existed have none ([timeAt] with no marks): they are placed by character position between the
 * piece's start and end, which is within a few seconds for speech (a speaker's pace is steady over a ~400-character piece) and far
 * better than the piece start. Pieces whose words cannot be placed (the English runs the language gate kept out of a mixed-language
 * transcript, the "Sounds:" line) are stored with no time at all, and a hit on them shows none.
 */
object TranscriptMarks {
  class Mark(val offset: Int, val startMs: Long, val endMs: Long)

  fun encode(marks: List<Mark>): String? =
    if (marks.isEmpty()) null else marks.joinToString(",") { "${it.offset}:${it.startMs}:${it.endMs}" }

  /** The marks in [encoded], or an empty list when there are none or the text can't be read (a hit then falls back to interpolation). */
  fun decode(encoded: String?): List<Mark> {
    if (encoded.isNullOrEmpty()) return emptyList()
    return try {
      encoded.split(',').map { entry ->
        val f = entry.split(':')
        Mark(f[0].toInt(), f[1].toLong(), f[2].toLong())
      }
    } catch (_: RuntimeException) {
      emptyList()
    }
  }

  /**
   * The moment, in the file's time, at which the character [charPos] of a piece's text ([textLength] long) is said; null when the piece
   * has no time. With [marks]: inside the segment that holds the character, by its share of that segment's characters. Without: by its
   * share of the whole text between [pieceStartMs] and [pieceEndMs].
   */
  fun timeAt(marks: List<Mark>, textLength: Int, pieceStartMs: Long?, pieceEndMs: Long?, charPos: Int): Long? {
    if (marks.isNotEmpty()) {
      var i = marks.indexOfLast { it.offset <= charPos }
      if (i < 0) i = 0
      val m = marks[i]
      val segmentEnd = if (i + 1 < marks.size) marks[i + 1].offset - 1 else textLength   // the joining space is not part of the segment
      val length = (segmentEnd - m.offset).coerceAtLeast(1)
      val share = ((charPos - m.offset).toDouble() / length).coerceIn(0.0, 1.0)
      return m.startMs + ((m.endMs - m.startMs) * share).toLong()
    }
    if (pieceStartMs == null) return null
    if (pieceEndMs == null || pieceEndMs <= pieceStartMs || textLength <= 0) return pieceStartMs
    val share = (charPos.toDouble() / textLength).coerceIn(0.0, 1.0)
    return pieceStartMs + ((pieceEndMs - pieceStartMs) * share).toLong()
  }

  /**
   * Where in [body] the matched words are: the first « » match inside [snippet] (the keyword index's quote of the best-matching
   * stretch of this same body) when that stretch can be found in the body, else the first word of the body that begins like one of the
   * query [terms] (the index stems word endings, so "meetings" answers "meeting"). Null when nothing in the body matches (a hit found
   * by meaning alone): the caller then keeps the piece's own start.
   */
  fun matchOffset(body: String, snippet: String?, terms: List<String>): Int? {
    if (snippet != null) {
      val first = snippet.indexOf('«')
      if (first >= 0) {
        val plain = snippet.replace("«", "").replace("»", "")
        val lead = if (plain.startsWith("…")) 1 else 0
        val cut = plain.substring(lead).removeSuffix("…")
        val before = snippet.substring(0, first).replace("«", "").replace("»", "").length - lead
        val at = if (cut.isEmpty()) -1 else body.indexOf(cut)
        if (at >= 0) return (at + before.coerceAtLeast(0)).coerceAtMost(body.length - 1)
      }
    }
    if (terms.isEmpty()) return null
    var i = 0
    while (i < body.length) {
      if (!body[i].isLetterOrDigit()) { i++; continue }
      var j = i
      while (j < body.length && body[j].isLetterOrDigit()) j++
      val word = body.substring(i, j).lowercase()
      if (terms.any { sameStem(word, it) }) return i
      i = j
    }
    return null
  }

  /** Porter strips endings only, so two words are one when they share all but a short ending (at least 3 letters in common). */
  private fun sameStem(word: String, term: String): Boolean {
    if (word == term) return true
    var n = 0
    while (n < word.length && n < term.length && word[n] == term[n]) n++
    return n >= 3 && n >= minOf(word.length, term.length) - 2
  }
}
