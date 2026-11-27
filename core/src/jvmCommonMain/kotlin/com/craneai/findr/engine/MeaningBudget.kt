package com.craneai.findr.engine

import com.craneai.findr.core.StorageEstimate

/**
 * Which of a document's chunks get a meaning vector. Every chunk keeps its words (the keyword index reads the whole text); only the
 * embedding is budgeted, because it is where the time and the heat go. Machine text ([TextClass]) gets none; a person's document gets
 * all of them up to [CHUNKS], and past that a chosen [CHUNKS]:
 *  - the opening ([OPENING] chunks: the title, the summary, what the document is about),
 *  - the chunks where its headings fall (up to [MAX_HEADINGS]: each section is reachable by what it is about),
 *  - and the rest spread evenly over what is left, so no long stretch of the document is without a nearby vector.
 *
 * Without a budget one long file cost as much as hundreds of photos: on a large personal corpus (17 Nov audit) 140 files held 56 % of 729,752
 * vectors, one of them 7,246, nearly all machine output in a code project.
 */
object MeaningBudget {
  /**
   * Vectors for one long document: a safety ceiling, not a target (D47.1). Picks below every chunk lost meaning queries on the
   * long-document set (12 books of 353 to 357 chunks, 36 planted facts; meaning top 3 against every chunk's 26/36: 120 -> 18, 200 -> 18,
   * 300 -> 23, paired -3 [-19.4, 0.0] pp), so no smaller value passed the no-loss gate; 500 keeps every chunk of such a book, and the
   * classification ([TextClass]) is what saves the vectors. The phone's size estimate keeps its own figure ([StorageEstimate.DOC_MAX_CHUNKS]).
   */
  const val CHUNKS = 500

  /** Chunks from the start that are always embedded. */
  const val OPENING = 12

  /** At most this many of the budget go to the chunks that hold headings. */
  const val MAX_HEADINGS = 24

  /**
   * True at index i: embed chunk i. All of them when they fit in [budget]; otherwise the opening, the chunks where [headings] (from
   * [headings]) first appear, in reading order, and an even spread of the rest. Exactly `min(chunks.size, budget)` trues.
   */
  fun chosen(chunks: List<String>, headings: List<String> = emptyList(), budget: Int = CHUNKS): BooleanArray {
    val total = chunks.size
    val out = BooleanArray(total)
    if (total <= budget) { out.fill(true); return out }
    var marked = 0
    for (i in 0 until minOf(OPENING, budget)) { out[i] = true; marked++ }
    var cursor = 0
    var headed = 0
    for (h in headings) {
      if (headed >= MAX_HEADINGS || marked >= budget) break
      var i = cursor
      while (i < total && !chunks[i].contains(h)) i++
      if (i >= total) continue   // not found from here on (cut across two chunks): the next heading looks from the same place
      cursor = i
      if (!out[i]) { out[i] = true; marked++; headed++ }
    }
    val free = (0 until total).filter { !out[it] }
    val spread = budget - marked
    for (k in 0 until spread) out[free[(free.size.toLong() * k / spread).toInt()]] = true
    return out
  }

  /**
   * The heading lines of a document's raw pages (line breaks intact), whitespace collapsed as [Chunker] does, so each can be found in
   * the chunks: Markdown headings, numbered sections ("2.3 Results", "IV. Budget"), and short title-like lines (up to 10 words, no
   * closing punctuation, mostly capitalised or all capitals). In reading order, without repeats.
   */
  fun headings(pages: List<String>): List<String> {
    val out = LinkedHashSet<String>()
    for (page in pages) for (raw in page.lineSequence()) {
      val line = raw.trim()
      if (line.length < 3 || line.length > 100) continue
      val md = MARKDOWN.matchEntire(line)
      val text = when {
        md != null -> md.groupValues[1]
        NUMBERED.matches(line) && !line.endsWith('.') -> line
        titleLike(line) -> line
        else -> null
      } ?: continue
      val norm = text.replace(SPACES, " ").trim()
      if (norm.length >= 3) out += norm
    }
    return out.toList()
  }

  private fun titleLike(line: String): Boolean {
    if (line.last() in ".,;:!?" || !line.first().isUpperCase()) return false
    val words = line.split(SPACES).filter { w -> w.any { it.isLetter() } }
    if (words.isEmpty() || words.size > 10) return false
    val capital = words.count { it.first().isUpperCase() || it.all { c -> !c.isLetter() || c.isUpperCase() } }
    return capital * 2 >= words.size
  }

  private val MARKDOWN = Regex("^#{1,6}\\s+(.+?)\\s*#*$")
  private val NUMBERED = Regex("^(\\d+(\\.\\d+)*\\.?|[IVXLC]+\\.)\\s+\\p{Lu}.{1,78}$")
  private val SPACES = Regex("\\s+")
}
