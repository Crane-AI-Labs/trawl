package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Modality
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The pure parts of DigUp's hybrid ranking, kept apart from SQLite and the vector matrix so they can be unit-tested
 * with plain numbers. The method is DigUp's `Searcher.swift` as validated by `scripts/eval_quality.py` (which beat
 * reciprocal-rank fusion on both of DigUp's evals and on ours):
 *
 *   score = meaningWeight * max(z, 0) + wordsWeight * coverage^1.5 + 1 (if the query names the file's kind)
 *           + 2 (if all of a 2+ word query's words stand side by side, in order, in one passage: the phrase bonus)
 *
 * where z is the best segment's cosine on a per-query scale ([meaning]) and coverage is the share of the query's
 * words found anywhere in the file. A file competes only if it stands out in meaning (z >= 2) or holds at least half
 * the query's words, which is also what makes "nothing found" an honest answer.
 */
object Fusion {
  const val Z_GATE = 2.0
  const val WORD_WEIGHT = 2.0
  /**
   * Added to a file's score when all of the query's words (two or more) occur contiguously, in order, in one chunk or OCR
   * text of it. Fixed in advance at the word weight and not tuned: the fine-tune lead's offline numbers (eval-en documents
   * 0.946 to 0.962, screenshots 0.902 to 0.918, nothing worse; exact-phrase lookups about 1.0 in every language).
   */
  const val PHRASE_BONUS = WORD_WEIGHT
  /**
   * Added when the file's NAME holds all of a 2+ word query's words: the name is the one place a person puts exactly what the file is,
   * so an all-words name match is the strongest words signal there is (a phrase in the text is +2 too, and the two add). Without it a
   * document whose meaning was not learned yet scored words only and lost to every picture the quick look liked a little
   * ("minutes of pta meeting 12 sept" -> "Minutes of PTA meeting 12 Sept.docx" 16th of 54). Fixed at the phrase bonus, not tuned.
   */
  const val NAME_BONUS = PHRASE_BONUS
  /** A file whose words cover at least this share of the query's words qualifies even with no meaning match. */
  const val MIN_COVERAGE = 0.5
  const val STRONG_RATIO = 0.5

  /** Fewer vectors than this in a modality and its own average is too noisy: the overall average stands in. */
  private const val MIN_GROUP = 20
  private const val MIN_DEVIATION = 0.02

  /** Words that describe what to look for, not what is in it. Kind words ("photo", "pdf") are here and act as [kindHints]. */
  private val STOPWORDS = setOf(
    "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "and", "or", "my", "me", "that", "this", "these",
    "those", "from", "about", "where", "what", "which", "who", "when", "is", "was", "are", "were", "be", "it", "its",
    "some", "any", "find", "show", "file", "files", "photo", "photos", "picture", "pictures", "image", "images",
    "screenshot", "screenshots", "video", "videos", "pdf", "document", "documents", "recording",
  )

  /**
   * The query's content words: lower-cased, split on anything that is not a letter or digit, words of two letters
   * or more, no stopwords, no repeats, in order. (Unlike Python's `\w`, an underscore splits: file names such as
   * `IMG_2026_receipt` are indexed that way by FTS5's unicode61 tokenizer, so queries must split the same way.)
   */
  fun terms(query: String): List<String> {
    val seen = HashSet<String>()
    return words(query).filter { it.length >= 2 && it !in STOPWORDS && seen.add(it) }
  }

  private fun words(text: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    for (ch in text.lowercase()) {
      if (Character.isLetterOrDigit(ch)) sb.append(ch)
      else if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) }
    }
    if (sb.isNotEmpty()) out += sb.toString()
    return out
  }

  /**
   * The FTS5 phrase for the query's [terms], or null when there are fewer than two (one word is no phrase). The terms are
   * letters and digits only, so quoting is safe. With [prefixLast] the last word is unfinished, as in the word patterns:
   * FTS5 spells that `"one two thr" *`.
   */
  fun phraseQuery(terms: List<String>, prefixLast: Boolean): String? {
    if (terms.size < 2) return null
    return terms.joinToString(" ", prefix = "\"", postfix = "\"") + if (prefixLast) " *" else ""
  }

  /**
   * Codes, IDs and long numbers ("K7Q2LM", "4815162342") mean nothing to an embedding and everything to keyword
   * search: a query made only of them weighs words double and meaning half.
   */
  fun isLookup(terms: List<String>): Boolean =
    terms.isNotEmpty() && terms.all { t -> t.any(Char::isDigit) && (t.any(Char::isLetter) || t.length >= 4) }

  private val KIND_WORDS: Map<String, Set<FileKind>> = buildMap {
    for (w in listOf("screenshot", "screenshots", "message", "sms", "chat")) put(w, setOf(FileKind.SCREENSHOT))
    // memes are pictures people saved: images here (the eval's own hint table does the same)
    for (w in listOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "meme", "memes")) put(w, setOf(FileKind.IMAGE))
    for (w in listOf("pdf", "pdfs", "paper", "papers")) put(w, setOf(FileKind.PDF))
    // People say "document" for a PDF as readily as for a Word file.
    for (w in listOf("document", "documents")) put(w, setOf(FileKind.DOCUMENT, FileKind.PDF))
    for (w in listOf("note", "notes")) put(w, setOf(FileKind.DOCUMENT))
    for (w in listOf("video", "videos", "clip", "clips", "movie", "movies")) put(w, setOf(FileKind.VIDEO))
    for (w in listOf("audio", "song", "songs", "podcast", "voice", "sound", "recording", "recordings")) put(w, setOf(FileKind.AUDIO))
  }

  /** Kinds the query asks for ("screenshot of…", "…in a video"): a soft +1, not a filter. */
  fun kindHints(query: String): Set<FileKind> = words(query).flatMapTo(HashSet()) { KIND_WORDS[it].orEmpty() }

  /** [setsScale] with the stale rows of any modality that has fewer than [MIN_GROUP] current scale rows added. */
  private fun withStaleWhereNothingElse(modality: IntArray, setsScale: BooleanArray, stale: BooleanArray, groups: Int): BooleanArray {
    val current = IntArray(groups)
    for (row in setsScale.indices) if (setsScale[row] && !stale[row]) current[modality[row]]++
    return BooleanArray(setsScale.size) { row -> setsScale[row] && (!stale[row] || current[modality[row]] < MIN_GROUP) }
  }

  /**
   * Puts one query's cosines on a single scale. Each vector's cosine minus the average of its own modality (text,
   * image and audio vectors sit at different levels for the same query), in standard deviations of all of them
   * together. Scaling each modality by its own deviation instead inflated the narrow ones and buried PDF pages under
   * pictures (DigUp, 94 vs 91 of 105). The scale comes only from rows where [setsScale] is true: the later pages of
   * a long file are scored on the scale but do not move it, or a book's hundreds of pages would drag the text
   * average down for every query.
   *
   * Rows made by the previous model ([stale], kept searchable while their files are read again after a model upgrade) are scored
   * on the scale too and do not move it either, with one exception: a modality that has fewer than [MIN_GROUP] current rows
   * (all of the photos, right after the upgrade) takes its stale rows into the scale, since it has nothing else to be centred
   * on. As photos are replaced the scale moves over to the new vectors by itself.
   *
   * @param modality ordinal of [Modality] per row
   */
  fun meaning(scores: FloatArray, modality: IntArray, setsScale: BooleanArray, stale: BooleanArray? = null): DoubleArray {
    val groups = Modality.entries.size
    val setsScale = if (stale == null) setsScale else withStaleWhereNothingElse(modality, setsScale, stale, groups)
    val sum = DoubleArray(groups)
    val count = IntArray(groups)
    for (row in scores.indices) if (setsScale[row]) {
      val m = modality[row]
      sum[m] += scores[row].toDouble()
      count[m]++
    }
    val total = count.sum()
    val all = if (total == 0) 0.0 else sum.sum() / total
    val mean = DoubleArray(groups) { if (count[it] >= MIN_GROUP) sum[it] / count[it] else all }

    val centred = DoubleArray(scores.size) { scores[it] - mean[modality[it]] }
    var n = 0.0
    var total2 = 0.0
    for (row in centred.indices) if (setsScale[row]) { n++; total2 += centred[row] }
    val centre = total2 / maxOf(n, 1.0)
    var squares = 0.0
    for (row in centred.indices) if (setsScale[row]) squares += (centred[row] - centre) * (centred[row] - centre)
    val deviation = maxOf(sqrt(squares / maxOf(n, 1.0)), MIN_DEVIATION)
    for (row in centred.indices) centred[row] = (centred[row] - centre) / deviation
    return centred
  }

  /** The rows that set the scale in [meaning], per the same rule (a later step does not, a stale row only where a modality has nothing else). */
  fun scaleRows(modality: IntArray, setsScale: BooleanArray, stale: BooleanArray): BooleanArray =
    withStaleWhereNothingElse(modality, setsScale, stale, Modality.entries.size)

  /** The mean of each modality, the centre and the deviation [meaning] puts a query's cosines on; [z] applies them to one cosine. */
  class Scale(val mean: DoubleArray, val centre: Double, val deviation: Double) {
    fun z(score: Float, modality: Int): Double = (score - mean[modality] - centre) / deviation
  }

  /**
   * [meaning]'s scale, estimated from a sample instead of every row: [sampleScores] and [sampleModality] are exactly scored
   * scale rows (see [scaleRows]) drawn evenly from each modality, and [groupRows] is how many scale rows each modality really has
   * (the sample of a modality stands for all of them, so a modality's weight, and whether it has the [MIN_GROUP] rows to be
   * centred on its own mean, are the real counts). With every row in the sample this is [meaning]'s scale. The two-stage scan uses it;
   * the exact scan does not, it calls [meaning].
   */
  fun estimateScale(sampleScores: FloatArray, sampleModality: IntArray, groupRows: IntArray): Scale {
    val groups = Modality.entries.size
    val sum = DoubleArray(groups)
    val sampled = IntArray(groups)
    for (i in sampleScores.indices) { sum[sampleModality[i]] += sampleScores[i].toDouble(); sampled[sampleModality[i]]++ }
    val total = groupRows.sum()
    var weighted = 0.0
    for (g in 0 until groups) if (sampled[g] > 0) weighted += sum[g] / sampled[g] * groupRows[g]
    val all = if (total == 0) 0.0 else weighted / total
    val mean = DoubleArray(groups) { if (groupRows[it] >= MIN_GROUP && sampled[it] > 0) sum[it] / sampled[it] else all }
    val rowsOf = DoubleArray(groups) { if (sampled[it] > 0) groupRows[it].toDouble() / sampled[it] else 0.0 }
    var n = 0.0
    var total2 = 0.0
    for (i in sampleScores.indices) { val w = rowsOf[sampleModality[i]]; n += w; total2 += w * (sampleScores[i] - mean[sampleModality[i]]) }
    val centre = total2 / maxOf(n, 1.0)
    var squares = 0.0
    for (i in sampleScores.indices) {
      val c = sampleScores[i] - mean[sampleModality[i]] - centre
      squares += rowsOf[sampleModality[i]] * c * c
    }
    return Scale(mean, centre, maxOf(sqrt(squares / maxOf(n, 1.0)), MIN_DEVIATION))
  }

  /**
   * One file's fused score. [z] is its best meaning z (null when it has no vectors or the query had none),
   * [coverage] its share of the query's words, [hinted] whether the query names its kind, [phrase] whether the query's words
   * stand side by side in one passage of it.
   *
   * [pending]: the file is still mid-indexing (words read, meaning not yet) rather than one that will never have a
   * meaning vector. While it waits, its word term is doubled, so a file with a strong word match is not routinely
   * outranked by a weaker meaning-only match from a file that happened to be understood first (DigUp's own worker
   * order reads pictures and screenshots before documents, so a document's meaning can lag by minutes; the research notes).
   * Only takes effect when [z] is null: the moment the file's own vectors land, this term is zero on its own, so a
   * fully indexed corpus is never moved by it (measured unchanged on eval-en and D17; see AppPathHarness).
   */
  fun score(
    z: Double?, coverage: Double, lookup: Boolean, hinted: Boolean, wordWeight: Double = WORD_WEIGHT, phrase: Boolean = false, pending: Boolean = false,
    named: Boolean = false,
  ): Double {
    val (meaningWeight, wordsWeight) = if (lookup) 0.5 to wordWeight * 2 else 1.0 to wordWeight
    val wordsTerm = wordsWeight * coverage.pow(1.5)
    val pendingBonus = if (pending && z == null) wordsTerm else 0.0
    return meaningWeight * maxOf(z ?: 0.0, 0.0) + wordsTerm + pendingBonus + (if (hinted) 1.0 else 0.0) + (if (phrase) PHRASE_BONUS else 0.0) + (if (named) NAME_BONUS else 0.0)
  }

  /** How many of [scores] (best first) are strong: at least [ratio] times the top one. The rest go under "weaker matches". */
  fun strongCount(scores: List<Double>, ratio: Double = STRONG_RATIO): Int {
    val top = scores.firstOrNull() ?: return 0
    if (top <= 0) return scores.size
    return scores.takeWhile { it >= top * ratio }.size
  }
}
