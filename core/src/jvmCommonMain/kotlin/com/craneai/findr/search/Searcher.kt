package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.DateParser
import com.craneai.findr.core.DateRange
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.core.TranscriptMarks
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SortOrder
import com.craneai.findr.core.sortRanked
import com.craneai.findr.core.SearchHit
import com.craneai.findr.core.SearchResults
import com.craneai.findr.engine.Chunker
import com.craneai.findr.index.Db
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.index.longOrNull

/**
 * Hybrid search over the index: meaning (EmbeddingGemma vectors, scored in memory) fused with words (FTS5), by
 * DigUp's method. See [Fusion] for the arithmetic; this class gathers its inputs.
 *
 *  - Meaning: every vector is scored against the query, put on one per-query scale, and each file keeps its best
 *    segment. The scale is the vectors' own distribution, so "nothing found" needs no tuned threshold.
 *  - Words: FTS5 with Porter stemming over file names, folders, document text and OCR; a word found in no file is
 *    retried as a prefix ("grok" finds "Grok4"), and the share of the query's words each file holds is its coverage.
 *  - Search works on a partial index: with no [queryVector], or before vectors exist, names and words still answer.
 *
 * Safe to call from any thread; each call reads a consistent set of vector rows ([VectorIndex.read]) and uses the
 * store's reader connections, so indexing can run at the same time.
 *
 * @param indexedKinds the kinds the engine currently indexes (settings), so `indexIncomplete` is not stuck true
 *   forever because, say, audio indexing is off
 */
class Searcher(
  private val store: IndexStore,
  private val vectors: VectorIndex,
  private val indexedKinds: () -> Set<FileKind> = { FileKind.entries.toSet() - FileKind.OTHER },
  private val zGate: Double = Fusion.Z_GATE,
  private val wordWeight: Double = Fusion.WORD_WEIGHT,
  /** The quick-look vectors (D2: pictures EmbeddingGemma hasn't understood yet), scored against [search]'s `quickVector`. */
  private val quick: VectorIndex? = null,
) {

  private class Meaning(val z: Double, val segmentId: Long)
  private class WordMatch(val file: Long, val segment: Long, val snippet: String?)
  /**
   * [phrase]: the files with all of the query's words (two or more) side by side in one chunk or OCR text.
   * [prefixOnly]: the files where the last word, typed as a prefix ("toy*"), matched only as the start of a longer word (it reaches "toilet": stemming makes "toy" "toi"),
   * never as a whole word. Empty when the query's last word is not a prefix.
   */
  private class Words(
    val matches: List<WordMatch>, val coverage: Map<Long, Double>, val phrase: Set<Long>, val prefixOnly: Set<Long> = emptySet(),
    /** [named]: the files whose NAME holds all of the query's words (two or more): the best words signal there is. */
    val named: Set<Long> = emptySet(),
  ) {
    companion object { val NONE = Words(emptyList(), emptyMap(), emptySet()) }
  }

  /**
   * @param prefixLast treat the last word as unfinished ("recei" finds "receipt"), for search-as-you-type
   * @param quickVector the query in the quick-look model's space (null: that modality sits this query out, e.g. a
   *   non-English query, which CLIP's English-only text tower can't read)
   */
  fun search(
    query: String,
    queryVector: FloatArray?,
    filter: KindFilter,
    limit: Int,
    prefixLast: Boolean = false,
    quickVector: FloatArray? = null,
    includeSensitive: Boolean = false,
    /** This query's word weight; the engine passes the one for the query's language ([com.craneai.findr.engine.QueryLanguage]). */
    wordWeight: Double = this.wordWeight,
    /**
     * The sort and the date range. The date range is applied inside the word query and to the meaning rows, before any limit, and
     * never moves the relevance floor (the z-scale still comes from every row). A sort other than [SortOrder.BEST] orders the
     * strong matches among themselves and the weak ones among themselves, so it can never lift a weak match above a strong one.
     */
    view: ResultView = ResultView.DEFAULT,
  ): SearchResults {
    val started = System.nanoTime()
    var hidden = 0
    fun results(hits: List<SearchHit>, strong: Int) = SearchResults(
      query = query, hits = hits, strongCount = strong,
      tookMs = (System.nanoTime() - started) / 1_000_000,
      indexIncomplete = store.waiting(filter.kinds.intersect(indexedKinds())) > 0,
      hiddenCount = hidden,
    )
    if (limit <= 0) return results(emptyList(), 0)

    val terms = Fusion.terms(query)
    val dates = view.dates
    // A date read from the query is a filter, except for the files that carry those very words in their name or text: "Church choir
    // schedule Oct 2026.docx" (modified in September) is what "church choir schedule oct 2026" asks for, not something to hide.
    val carriers = if (dates != null && view.datePhrase != null && DateParser.namesCalendarDate(view.datePhrase)) carrying(view.datePhrase) else emptySet()
    val found = words(terms, filter, prefixLast, dates, carriers)
    val inRange = dates?.let { d -> store.idsInDateRange(d).let { if (carriers.isEmpty()) it else it + carriers } }
    val best = meaning(queryVector, filter, quickVector, found.coverage.filterValues { it >= Fusion.MIN_COVERAGE }.keys, inRange)
    val words = found.matches
    val coverage = found.coverage

    // A file qualifies by standing out in meaning, or by containing at least half the query's words.
    val lookup = Fusion.isLookup(terms)
    val hinted = Fusion.kindHints(query)
    val candidates = HashSet<Long>()
    for ((file, m) in best) if (m.z >= zGate) candidates += file
    for ((file, share) in coverage) if (share >= Fusion.MIN_COVERAGE) candidates += file
    if (candidates.isEmpty()) return results(emptyList(), 0)

    val kindOf = store.filesByIds(candidates)   // also drops anything skipped or deleted since the vectors loaded
    // The pending bonus only matters when meaning scoring is actually happening this query (best non-empty: a query
    // vector answered and at least one row got a z) -- that is the only way an already-understood file's weak meaning
    // match could outrank a pending file's words in the first place. A words-only query (no embedding yet, or none
    // asked for) has nothing for a pending file to lose to, so it is left exactly as before: no DB round trip, and
    // the word/phrase arithmetic below is unchanged (SearcherTest pins its exact values with no query vector).
    val pending = if (best.isEmpty()) emptySet() else store.pendingMeaning(candidates.filterTo(HashSet()) { best[it] == null })
    val scored = ArrayList<Pair<Long, Double>>(candidates.size)
    val literal = HashSet<Long>()   // every word of the query, written out in the file (see the promotion below)
    for (file in candidates) {
      val info = kindOf[file] ?: continue
      // Sensitive files (the person's own marks) are dropped here, before the limit, so a page of hits is never short
      // because hidden files took its places. They are counted, so the "N hidden" chip can offer them after unlock.
      if (info.sensitive && !includeSensitive) { hidden++; continue }
      val kind = info.kind
      if ((coverage[file] ?: 0.0) >= LITERAL_COVERAGE && file !in found.prefixOnly) literal += file
      scored += file to Fusion.score(
        best[file]?.z, coverage[file] ?: 0.0, lookup, kind in hinted, wordWeight, phrase = file in found.phrase, pending = file in pending, named = file in found.named,
      )
    }
    scored.sortWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
    var strongCount = Fusion.strongCount(scored.map { it.second })
    // A file that holds every word of the query, as written, is never filed under "weaker matches": meaning's z has no ceiling, a
    // literal hit's score has one (about the word weight), so one confident picture match pushed every literal hit below the fold
    // ("toy": toy photos first, the receipt that says "toy" under weaker). The literal hits in the tail move up to just behind the
    // strong ones, in their own order. The strong ones, and so the top of the list, are untouched.
    if (literal.isNotEmpty() && strongCount < scored.size) {
      val tail = scored.subList(strongCount, scored.size).toList()
      val up = tail.filter { it.first in literal }
      if (up.isNotEmpty()) {
        val head = scored.subList(0, strongCount).toList()
        scored.clear(); scored += head; scored += up; scored += tail.filter { it.first !in literal }
        strongCount += up.size
      }
    }
    val strongIds = HashSet<Long>(); for (i in 0 until strongCount) strongIds += scored[i].first
    val top = sortRanked(scored, strongCount, view.sort) { kindOf.getValue(it.first) }.take(limit)

    val keywordOf = HashMap<Long, WordMatch>()
    for (w in words) keywordOf.putIfAbsent(w.file, w)

    // Which segment each hit points at: the best meaning segment if it stands out, else the one the words matched.
    val pointedAt = LinkedHashMap<Long, Long>()
    for ((file, _) in top) {
      val semantic = best[file]
      val keyword = keywordOf[file]
      val useMeaning = semantic != null && (semantic.z >= zGate || keyword == null)
      val segment = if (useMeaning) semantic?.segmentId else keyword?.segment
      if (segment != null) pointedAt[file] = segment
    }
    val facts = segmentFacts(pointedAt.values + top.mapNotNull { keywordOf[it.first]?.segment })

    val hits = ArrayList<SearchHit>(top.size)
    for ((file, score) in top) {
      val segment = pointedAt[file] ?: continue
      val info = kindOf[file] ?: continue
      val here = facts[segment] ?: continue
      val keyword = keywordOf[file]
      val semantic = best[file]

      // Quote the matched words only when they come from the segment we point at, so a PDF hit on page 5 never
      // shows page 2's words. A picture's vector has no text of its own and may borrow its OCR words, which
      // describe the same picture; a file name's match is not worth quoting.
      var snippet = keyword?.takeIf { it.segment == segment }?.snippet?.takeIf { it.contains('«') }
      if (snippet == null && here.text.isNullOrBlank() && here.locStart == null && keyword?.snippet?.contains('«') == true &&
        facts[keyword.segment]?.kind != SegmentKind.NAME
      ) snippet = keyword.snippet
      // A passage found by meaning alone still shows what it says.
      if (snippet == null && here.kind in TEXT_SEGMENTS) snippet = here.text?.let { lead(it, charsBefore(here)) }
      else snippet = tidied(snippet, if (keyword != null && snippet === keyword.snippet) facts[keyword.segment] else here)

      val byMeaning = semantic != null && semantic.z >= zGate
      val byWords = (coverage[file] ?: 0.0) >= Fusion.MIN_COVERAGE
      val reason = when {
        byMeaning && byWords -> MatchReason.BOTH
        byWords -> MatchReason.WORDS
        else -> MatchReason.MEANING
      }
      hits += SearchHit(info, score, reason, snippet, locatorOf(here, keyword?.takeIf { it.segment == segment }?.snippet, terms))
    }
    // The strong ones are the first [strongCount] of the full ranking (within the ratio of the top score, plus the literal hits promoted
    // behind them), sorted among themselves by any other sort: that is exactly the hits whose files are in [strongIds].
    return results(hits, hits.count { it.file.id in strongIds })
  }

  // ---- find similar ----------------------------------------------------------------------------------------------

  /**
   * "More like this": the files whose picture vectors sit closest to [fileId]'s own, in one space (see [SimilarSpace]).
   *
   * No query is embedded and no words are used. The seed's stored vectors (a photo has one, a video up to [SIMILAR_SEED_VECTORS]
   * evenly spaced key frames) are each scored against every row of the same space and modality; a file's z is its best over
   * all seed vectors and all its rows (max, which the pre-registered eval kept: the research notes). The z is put
   * on the scale of that same space (a seed vector's cosines against the other pictures), so "nothing is really like this"
   * gives an empty list, with no tuned threshold, exactly as a search that finds nothing does.
   *
   * The seed itself is never offered. The kind filter, the date range, the sort, the hidden-file rule and `indexIncomplete`
   * mean what they mean in [search]. Documents are not offered a seed (a null [SimilarSeed]); nor is a picture that has no
   * picture vector yet.
   *
   * @return the results; `query` is [label] (the seed's name) for the screen to show
   */
  fun similar(
    fileId: Long,
    filter: KindFilter,
    limit: Int,
    includeSensitive: Boolean = false,
    view: ResultView = ResultView.DEFAULT,
    label: String = "",
    /** The seed's quick-look vector, made on demand (the image tower, ~0.1 s): called at most once, and only when [similarSeed] needs it. */
    quickSeed: (() -> FloatArray?)? = null,
  ): SearchResults {
    val started = System.nanoTime()
    var hidden = 0
    fun results(hits: List<SearchHit>, strong: Int) = SearchResults(
      query = label, hits = hits, strongCount = strong, tookMs = (System.nanoTime() - started) / 1_000_000,
      indexIncomplete = store.waiting(filter.kinds.intersect(indexedKinds())) > 0, hiddenCount = hidden,
    )
    if (limit <= 0) return results(emptyList(), 0)
    val seedFile = store.fileById(fileId) ?: return results(emptyList(), 0)
    // A hidden seed (the lock closed after its result was drawn) must not draw out its neighbours.
    if (seedFile.sensitive && !includeSensitive) return results(emptyList(), 0)
    val seed = similarSeed(fileId, quickSeed) ?: return results(emptyList(), 0)

    val index = seed.space.index(vectors, quick) ?: return results(emptyList(), 0)
    val allowed = BooleanArray(FileKind.entries.size).also { a -> filter.kinds.forEach { a[it.ordinal] = true } }
    val inRange = view.dates?.let { store.idsInDateRange(it) }
    val best = HashMap<Long, Meaning>()
    fun offer(file: Long, z: Double, segment: Long) {
      if (file == fileId) return
      if (inRange != null && file !in inRange) return
      val current = best[file]
      if (current == null || z > current.z) best[file] = Meaning(z, segment)
    }
    index.read {
      val only = seed.space.modality.ordinal
      for (vector in seed.vectors) {
        if (index === vectors && inRange == null && vectors.twoStageApplies()) {
          twoStageMeaning(vector, allowed, emptySet(), ::offer, only = only, skip = fileId)
        } else {
          exactSimilar(index, vector, allowed, only, fileId, ::offer)
        }
      }
    }

    val candidates = best.filterValues { it.z >= zGate }.keys
    if (candidates.isEmpty()) return results(emptyList(), 0)
    val kindOf = store.filesByIds(candidates)   // also drops anything skipped or deleted since the vectors loaded
    val scored = ArrayList<Pair<Long, Double>>(candidates.size)
    for (file in candidates) {
      val info = kindOf[file] ?: continue
      if (info.sensitive && !includeSensitive) { hidden++; continue }
      scored += file to best.getValue(file).z
    }
    scored.sortWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
    val strongCount = Fusion.strongCount(scored.map { it.second })
    val top = sortRanked(scored, strongCount, view.sort) { kindOf.getValue(it.first) }.take(limit)
    val facts = segmentFacts(top.mapNotNull { best[it.first]?.segmentId })
    val hits = top.mapNotNull { (file, score) ->
      val info = kindOf[file] ?: return@mapNotNull null
      SearchHit(info, score, MatchReason.MEANING, null, best[file]?.segmentId?.let { facts[it] }?.let { locatorOf(it, null, emptyList()) })
    }
    return results(hits, if (view.sort == SortOrder.BEST) Fusion.strongCount(hits.map { it.score }) else minOf(strongCount, hits.size))
  }

  /** The exact scan for [similar]: every row of [index] scored, the z-scale taken from the rows of [only] modality except the seed's. */
  private fun exactSimilar(
    index: VectorIndex, vector: FloatArray, allowed: BooleanArray, only: Int, skip: Long, offer: (Long, Double, Long) -> Unit,
  ) {
    val scores = index.scores(vector)
    val rows = (0 until index.size).filter { index.modalityOrdinal(it) == only && index.fileId(it) != skip }.toIntArray()
    if (rows.isEmpty()) return
    val sub = FloatArray(rows.size) { scores[rows[it]] }
    val z = Fusion.meaning(
      sub, IntArray(rows.size) { only }, BooleanArray(rows.size) { !index.isLate(rows[it]) }, BooleanArray(rows.size) { index.isStale(rows[it]) },
    )
    for (i in rows.indices) {
      val row = rows[i]
      if (allowed[index.kindOrdinal(row)]) offer(index.fileId(row), z[i], index.segmentId(row))
    }
  }

  /** Which vector space a seed is searched in. Never both: EmbeddingGemma's and the quick-look model's cosines are not comparable. */
  enum class SimilarSpace(val modality: Modality) {
    /** EmbeddingGemma picture vectors (a photo, a screenshot, a video's key frames in the full profile). */
    EMBEDDING_GEMMA(Modality.IMAGE),
    /** The quick-look (TinyCLIP) vectors: pictures, and video frames in the lite profile, EmbeddingGemma has not understood. */
    QUICK_LOOK(Modality.CLIP);

    fun index(eg: VectorIndex, quick: VectorIndex?): VectorIndex? = if (this == EMBEDDING_GEMMA) eg else quick
  }

  /** A seed's vectors, all of one [space], at most [SIMILAR_SEED_VECTORS] of them evenly spaced in segment order. */
  class SimilarSeed(val space: SimilarSpace, val vectors: List<FloatArray>, val embedded: Boolean = false)

  /**
   * Whether EmbeddingGemma has understood too few pictures for its space to answer "find similar": fewer picture rows than the
   * quick-look space holds, i.e. under half of the pictures understood. (EmbeddingGemma's row replaces a file's quick-look row,
   * so while the index is half built the two spaces split the pictures between them, and an understood seed finds almost
   * nothing in a nearly empty space: on the Infinix, 7 to 11 EmbeddingGemma rows against 3.4 thousand quick-look ones.)
   * A small library that is fully understood has no quick-look rows left and so stays in its own space, however few rows.
   */
  fun egSparse(): Boolean {
    val quickRows = quick?.size ?: return false
    if (quickRows == 0) return false
    val image = Modality.IMAGE.ordinal
    val egRows = vectors.read { (0 until vectors.size).count { vectors.modalityOrdinal(it) == image } }
    return egRows < quickRows
  }

  /**
   * Reads [fileId]'s stored vectors and picks its space: EmbeddingGemma picture vectors if it has any and that space is not
   * [egSparse]; otherwise the quick-look space, with the seed's stored quick-look vector, or one made by [quickSeed]
   * (once, on demand) if it has none. A seed is never searched in two spaces. Null when no usable vector exists.
   */
  fun similarSeed(fileId: Long, quickSeed: (() -> FloatArray?)? = null): SimilarSeed? {
    val egBlobs = ArrayList<ByteArray>()
    val quickBlobs = ArrayList<ByteArray>()
    store.forEachVectorOfFile(fileId) { _, _, _, modality, _, blob ->
      when {
        modality == Modality.IMAGE && blob.size == vectors.dim * 2 -> egBlobs += blob
        modality == Modality.CLIP && quick != null && blob.size == quick.dim * 2 -> quickBlobs += blob
      }
    }
    fun pick(blobs: List<ByteArray>): List<FloatArray> {
      val n = blobs.size
      val take = minOf(n, SIMILAR_SEED_VECTORS)
      // evenly spaced across the file, the first and the last included (a video's key frames come in time order)
      return (0 until take).map { i -> Fp16.blobToFloats(blobs[if (take == 1) 0 else (i.toLong() * (n - 1) / (take - 1)).toInt()]) }
    }
    val useQuick = quick != null && (egBlobs.isEmpty() || egSparse())
    if (!useQuick) return if (egBlobs.isNotEmpty()) SimilarSeed(SimilarSpace.EMBEDDING_GEMMA, pick(egBlobs)) else null
    if (quickBlobs.isNotEmpty()) return SimilarSeed(SimilarSpace.QUICK_LOOK, pick(quickBlobs))
    val made = quickSeed?.invoke()?.takeIf { it.size == quick!!.dim }
    return when {
      made != null -> SimilarSeed(SimilarSpace.QUICK_LOOK, listOf(made), embedded = true)
      egBlobs.isNotEmpty() -> SimilarSeed(SimilarSpace.EMBEDDING_GEMMA, pick(egBlobs))   // no quick vector to be had: the sparse space still answers
      else -> null
    }
  }

  // ---- meaning ---------------------------------------------------------------------------------------------------

  /**
   * Each allowed file's best segment, by z on this query's scale (empty when there is no usable query vector).
   *
   * EmbeddingGemma's rows are scored against [queryVector] and the quick-look rows against [quickVector], each in its own
   * space. EmbeddingGemma's rows get exactly the z they would get with no quick-look pass (their own [Fusion.meaning]), so
   * documents, videos and everything already understood score as before. The quick-look rows get their z from one
   * [Fusion.meaning] over all rows, where they are a modality of their own (centred on their own mean, one shared deviation).
   * This `split` mode beat the plain shared scale in the half-done states (eval-en + 1,000 COCO photos: overall r@1 0.81 vs
   * 0.78 at half done, photos 0.68 vs 0.57) and tied it elsewhere except CLIP-only photos (0.63 vs 0.69); the research notes.
   */
  private fun meaning(
    queryVector: FloatArray?, filter: KindFilter, quickVector: FloatArray?, wordFiles: Set<Long>,
    /** With a date range: the ids of the files inside it. Rows of other files are scored (the scale needs them) but not offered. */
    inRange: Set<Long>? = null,
  ): Map<Long, Meaning> {
    val eg = queryVector?.takeIf { it.size == vectors.dim }
    val qi = quick?.takeIf { quickVector != null && quickVector.size == it.dim }
    if (eg == null && qi == null) return emptyMap()
    val allowed = BooleanArray(FileKind.entries.size).also { a -> filter.kinds.forEach { a[it.ordinal] = true } }
    val best = HashMap<Long, Meaning>()
    fun offer(file: Long, z: Double, segment: Long) {
      if (inRange != null && file !in inRange) return
      val current = best[file]
      if (current == null || z > current.z) best[file] = Meaning(z, segment)
    }
    vectors.read {
      withQuick(qi) {
        val n = if (eg != null) vectors.size else 0
        // Quick rows of files EmbeddingGemma has picture vectors for are left out: its vectors supersede (normally the quick
        // row was already dropped when the file was understood; this keeps the end state EmbeddingGemma's even if one lingers).
        // Only picture vectors count: in the lite profile a picture's text (OCR) has EmbeddingGemma vectors beside its quick one.
        var quickRows = IntArray(0)
        var quickScores = FloatArray(0)
        if (qi != null && qi.size > 0) {
          val covered = HashSet<Long>(n * 2)
          val image = Modality.IMAGE.ordinal
          for (row in 0 until n) if (vectors.modalityOrdinal(row) == image) covered += vectors.fileId(row)
          quickScores = qi.scores(quickVector!!)
          quickRows = (0 until qi.size).filter { qi.fileId(it) !in covered }.toIntArray()
        }
        val m = quickRows.size
        if (n + m == 0) return@withQuick
        // A big index with nothing from the quick-look pass to add is scanned in two stages (see [twoStageMeaning]); every other
        // case, and every index below the size threshold, takes the exact path below, unchanged.
        // A date range takes the exact path too: the first pass keeps the best rows of the whole index, and the files in range may not be among them.
        if (m == 0 && inRange == null && vectors.twoStageApplies()) { twoStageMeaning(eg!!, allowed, wordFiles, ::offer); return@withQuick }
        val egScores = if (n > 0) vectors.scores(eg!!) else FloatArray(0)
        val scores = FloatArray(n + m)
        val modality = IntArray(n + m)
        val setsScale = BooleanArray(n + m)
        val stale = BooleanArray(n + m)
        for (row in 0 until n) {
          scores[row] = egScores[row]; modality[row] = vectors.modalityOrdinal(row)
          stale[row] = vectors.isStale(row); setsScale[row] = !vectors.isLate(row)
        }
        for (j in 0 until m) {
          scores[n + j] = quickScores[quickRows[j]]; modality[n + j] = qi!!.modalityOrdinal(quickRows[j]); setsScale[n + j] = true
        }
        // EmbeddingGemma's rows on their own scale (unchanged by the quick-look pass); quick rows on the shared one.
        val zEg = if (m == 0) Fusion.meaning(scores, modality, setsScale, stale)
          else Fusion.meaning(scores.copyOf(n), modality.copyOf(n), setsScale.copyOf(n), stale.copyOf(n))
        val zAll = if (m == 0) zEg else Fusion.meaning(scores, modality, setsScale, stale)
        for (row in 0 until n) if (allowed[vectors.kindOrdinal(row)]) offer(vectors.fileId(row), zEg[row], vectors.segmentId(row))
        for (j in 0 until m) {
          val row = quickRows[j]
          if (allowed[qi!!.kindOrdinal(row)]) offer(qi.fileId(row), zAll[n + j], qi.segmentId(row))
        }
      }
    }
    return best
  }

  /**
   * [meaning]'s EmbeddingGemma half without scoring every row at full width (the caller holds the vectors' read lock).
   *
   * The candidates are the best rows of a cheap first pass ([VectorIndex.stageCandidates]) plus the rows of the files the words
   * found (those files' meaning enters their score even below the gate); they are scored exactly. The z-scale, which the exact path
   * takes from all rows, is estimated from [TwoStage.scaleSample] exactly scored scale rows per modality, evenly spaced
   * ([Fusion.estimateScale]). A row outside the candidates is simply not offered: it would have ranked below them.
   */
  private fun twoStageMeaning(
    eg: FloatArray, allowed: BooleanArray, wordFiles: Set<Long>, offer: (Long, Double, Long) -> Unit,
    /** Find-similar: score only rows of this modality (ordinal) and leave out the seed's own rows, scale included. */
    only: Int = -1, skip: Long = -1L,
  ) {
    val n = vectors.size
    val groups = Modality.entries.size
    val modality = IntArray(n) { vectors.modalityOrdinal(it) }
    val onScale = Fusion.scaleRows(modality, BooleanArray(n) { !vectors.isLate(it) }, BooleanArray(n) { vectors.isStale(it) })
    if (only >= 0) for (row in 0 until n) if (modality[row] != only || vectors.fileId(row) == skip) onScale[row] = false
    val groupRows = IntArray(groups)
    for (row in 0 until n) if (onScale[row]) groupRows[modality[row]]++
    val seen = IntArray(groups)
    val sample = checkNotNull(vectors.twoStage).scaleSample
    val picked = IntArray(groupRows.sumOf { minOf(it, sample) })
    var taken = 0
    for (row in 0 until n) if (onScale[row]) {
      val g = modality[row]
      // systematic sample: scaleSample evenly spaced rows of a modality that has more (all of them otherwise)
      if ((seen[g] + 1).toLong() * sample / groupRows[g] > seen[g].toLong() * sample / groupRows[g]) picked[taken++] = row
      seen[g]++
    }
    val sampleScores = vectors.scoresAt(eg, picked)
    val scale = Fusion.estimateScale(sampleScores, IntArray(taken) { modality[picked[it]] }, groupRows)

    val staged = vectors.stageCandidates(eg, allowed)
    val ofWords = vectors.rowsOfFiles(wordFiles.toLongArray().also { it.sort() })
    val rows = mergeSorted(staged, ofWords)
    val scores = vectors.scoresAt(eg, rows)
    for (i in rows.indices) {
      val row = rows[i]
      if (only >= 0 && (modality[row] != only || vectors.fileId(row) == skip)) continue
      if (allowed[vectors.kindOrdinal(row)]) offer(vectors.fileId(row), scale.z(scores[i], modality[row]), vectors.segmentId(row))
    }
  }

  /** The union of two ascending row lists, ascending, each row once. */
  private fun mergeSorted(a: IntArray, b: IntArray): IntArray {
    val out = IntArray(a.size + b.size)
    var i = 0; var j = 0; var n = 0
    while (i < a.size || j < b.size) {
      val next = if (j >= b.size || (i < a.size && a[i] <= b[j])) a[i++] else b[j++]
      if (n == 0 || out[n - 1] != next) out[n++] = next
    }
    return out.copyOf(n)
  }

  /** Holds [index]'s read lock too (when there is one), so its rows can't move between scoring and looking them up. */
  private fun withQuick(index: VectorIndex?, block: () -> Unit) = if (index == null) block() else index.read(block)

  // ---- words -----------------------------------------------------------------------------------------------------

  /**
   * The matches (one per file, best first by BM25, name weighted 3x), each file's share of the query's words, and the files
   * where the words form a phrase ([Fusion.phraseQuery]) in one passage of text (not a file name or folder, which are
   * words of their own). Same index, same Porter stemming and the same unfinished last word as the word patterns.
   */
  private fun words(terms: List<String>, filter: KindFilter, prefixLast: Boolean, dates: DateRange? = null, carriers: Set<Long> = emptySet()): Words {
    if (terms.isEmpty() || filter.kinds.isEmpty()) return Words.NONE
    val kinds = filter.kinds.joinToString(",") { "'${it.name}'" }
    val inDates = dateClause("f", dates, carriers)
    return try {
      store.read { db ->
        val patterns = terms.mapIndexed { i, term -> "\"$term\"" + if (prefixLast && i == terms.lastIndex) "*" else "" }.toMutableList()
        // A word that is in no file as typed matches as the start of longer words (stemming only covers endings),
        // so a result the panel showed while typing does not vanish when typing stops. A word that is in the index
        // stays whole ("cat" must not bring in "category"), and two-letter words are too short to stand for one.
        for (i in terms.indices) {
          if (terms[i].length >= 3 && !patterns[i].endsWith("*") && !isIndexed(db, patterns[i])) patterns[i] += "*"
        }
        val words = ArrayList<WordMatch>()
        val seen = HashSet<Long>()
        db.query(
          """
          SELECT s.file, s.id, snippet(fts, 1, '«', '»', '…', 12) FROM fts
          JOIN segments s ON s.id = fts.rowid
          JOIN files f ON f.id = s.file
          WHERE fts MATCH ? AND f.state != 'skipped' AND f.kind IN ($kinds)$inDates
          ORDER BY bm25(fts, 3.0, 1.0) LIMIT 1000
          """,
          patterns.joinToString(" OR "),
        ) { row ->
          val file = row.getLong(0)
          if (words.size < MAX_WORD_FILES && seen.add(file)) words += WordMatch(file, row.getLong(1), if (row.isNull(2)) null else row.getText(2))
        }
        val coverage = HashMap<Long, Double>()
        val prefixOnly = HashSet<Long>()
        if (words.isNotEmpty()) {
          val files = words.joinToString(",") { it.file.toString() }
          for (pattern in patterns) {
            val matched = HashSet<Long>()
            db.query(
              "SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid WHERE fts MATCH ? AND s.file IN ($files)",
              pattern,
            ) { matched += it.getLong(0); coverage.merge(it.getLong(0), 1.0 / patterns.size, Double::plus) }
            if (pattern.endsWith("*")) {
              // One more query, only for a prefix: the files with the word whole ("toy", "toys") tell the others ("toilet") apart.
              val whole = HashSet<Long>()
              db.query(
                "SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid WHERE fts MATCH ? AND s.file IN ($files)",
                pattern.removeSuffix("*"),
              ) { whole += it.getLong(0) }
              prefixOnly += matched - whole
            }
          }
        }
        // The files whose name holds every word of a 2+ word query ("Minutes of PTA meeting 12 Sept.docx" for "minutes of pta meeting 12
        // sept"). Only among the files that already matched on words, so this is one small query; the folder is not part of the name.
        val named = HashSet<Long>()
        if (patterns.size >= 2 && words.isNotEmpty()) {
          val files = words.joinToString(",") { it.file.toString() }
          db.query(
            "SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid WHERE fts MATCH ? AND s.kind = 'name' AND s.file IN ($files)",
            "name : (" + patterns.joinToString(" AND ") + ")",
          ) { named += it.getLong(0) }
        }
        val phrase = HashSet<Long>()
        Fusion.phraseQuery(terms, prefixLast)?.let { phrasePattern ->
          db.query(
            """
            SELECT DISTINCT s.file FROM fts
            JOIN segments s ON s.id = fts.rowid
            JOIN files f ON f.id = s.file
            WHERE fts MATCH ? AND s.kind IN ($PHRASE_KINDS) AND f.state != 'skipped' AND f.kind IN ($kinds)$inDates LIMIT 1000
            """,
            phrasePattern,
          ) { phrase += it.getLong(0) }
        }
        Words(words, coverage, phrase, prefixOnly, named)
      }
    } catch (e: androidx.sqlite.SQLiteException) {
      Words.NONE   // a query FTS5 cannot parse is "no word matches", not a crash
    }
  }

  /** ` AND f.date_ms >= from AND f.date_ms < until` for the files table aliased [alias] (the numbers are Longs, so nothing to escape). */
  private fun dateClause(alias: String, dates: DateRange?, carriers: Set<Long> = emptySet()): String {
    if (dates == null) return ""
    val range = (dates.fromMs?.let { " AND $alias.date_ms >= $it" } ?: "") + (dates.untilMs?.let { " AND $alias.date_ms < $it" } ?: "")
    if (carriers.isEmpty() || range.isEmpty()) return range
    // the files that carry the date as words are let through whatever their modified date
    return " AND (${range.removePrefix(" AND ")} OR $alias.id IN (${carriers.joinToString(",")}))"
  }

  /**
   * The files that have [phrase] (the words a date was read from: "oct 2026") written side by side in their name, folder or text.
   * At most [MAX_CARRIERS], which is far more than a person has files named after a month.
   */
  private fun carrying(phrase: String): Set<Long> {
    val terms = Fusion.terms(phrase)
    val pattern = Fusion.phraseQuery(terms, prefixLast = false) ?: terms.singleOrNull()?.let { "\"$it\"" } ?: return emptySet()
    return try {
      store.read { db ->
        val out = HashSet<Long>()
        db.query(
          "SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid JOIN files f ON f.id = s.file WHERE fts MATCH ? AND f.state != 'skipped' LIMIT $MAX_CARRIERS",
          pattern,
        ) { out += it.getLong(0) }
        out
      }
    } catch (e: androidx.sqlite.SQLiteException) {
      emptySet()
    }
  }

  /**
   * The files among [files] that have every content word of [phrase] in their text or name (in any of their segments). This is how a
   * date typed in a query is told from a date that is part of what is looked for: "September" in "your PAYE return for September has
   * not been filed" is written in the screenshot, "September" in "invoices from September" is not written in anything.
   */
  fun mentioning(phrase: String, files: Collection<Long>): Set<Long> {
    val terms = Fusion.terms(phrase)
    if (terms.isEmpty() || files.isEmpty()) return emptySet()
    val list = files.distinct().joinToString(",")
    return try {
      store.read { db ->
        var all: Set<Long>? = null
        for (term in terms) {
          val has = HashSet<Long>()
          db.query("SELECT DISTINCT s.file FROM fts JOIN segments s ON s.id = fts.rowid WHERE fts MATCH ? AND s.file IN ($list)", "\"$term\"") { has += it.getLong(0) }
          all = all?.intersect(has) ?: has
          if (all.isEmpty()) break
        }
        all ?: emptySet()
      }
    } catch (e: androidx.sqlite.SQLiteException) {
      emptySet()
    }
  }

  private fun isIndexed(db: Db, pattern: String): Boolean {
    var found = false
    db.query("SELECT 1 FROM fts WHERE fts MATCH ? LIMIT 1", pattern) { found = true }
    return found
  }

  // ---- hit details ---------------------------------------------------------------------------------------------------

  private class SegmentFacts(val id: Long, val file: Long, val kind: SegmentKind, val locStart: Long?, val locEnd: Long?, val text: String?, val marks: String? = null)

  private fun segmentFacts(ids: Collection<Long>): Map<Long, SegmentFacts> {
    val out = HashMap<Long, SegmentFacts>()
    if (ids.isEmpty()) return out
    val list = ids.distinct().joinToString(",")
    store.read { db ->
      db.query("SELECT s.id, s.file, s.kind, s.loc_start, s.loc_end, fts.body, s.marks FROM segments s LEFT JOIN fts ON fts.rowid = s.id WHERE s.id IN ($list)") {
        out[it.getLong(0)] = SegmentFacts(
          it.getLong(0), it.getLong(1), SegmentKind.of(it.getText(2)), it.longOrNull(3), it.longOrNull(4), if (it.isNull(5)) null else it.getText(5),
          if (it.isNull(6)) null else it.getText(6),
        )
      }
    }
    return out
  }

  private fun locatorOf(s: SegmentFacts, snippet: String?, terms: List<String>): Locator? = when (s.kind) {
    SegmentKind.PAGE -> s.locStart?.let { Locator.Page(it.toInt()) }
    SegmentKind.FRAME, SegmentKind.AUDIO -> s.locStart?.let { Locator.Time(it, s.locEnd) }
    SegmentKind.TRANSCRIPT -> transcriptMoment(s, snippet, terms)
    // The "Sounds:" line lists each label once for the whole file; it stands for no moment.
    else -> null
  }

  /**
   * When the matched words of a transcript piece are said. A piece is up to ~400 characters of speech, and a short video is one piece
   * from 0:00, so its own start would read "at 0:00" for a phrase said at 0:40. The matched word's place in the piece (the quoted
   * stretch's first « », else the first query word in the text) is mapped through the piece's per-segment times
   * ([com.craneai.findr.core.TranscriptMarks]); a piece from before those existed is placed by character position between its start and
   * end. A piece with no time (the language gate's partial English runs) has none to show, and a hit by meaning alone, with no matched
   * word, points at the start of the piece it found.
   */
  private fun transcriptMoment(s: SegmentFacts, snippet: String?, terms: List<String>): Locator? {
    val start = s.locStart ?: return null
    val body = s.text
    val at = body?.let { TranscriptMarks.matchOffset(it, snippet, terms) }
      ?.let { TranscriptMarks.timeAt(TranscriptMarks.decode(s.marks), body.length, start, s.locEnd, it) }
    return Locator.Time(at ?: start, s.locEnd)
  }

  /**
   * The first stretch of a passage, cut at a word boundary. [before] is the source just before the passage (see [SnippetEdges]): a
   * chunk that begins inside a word loses the half word and starts with an ellipsis.
   */
  private fun lead(text: String, before: CharSequence = "", limit: Int = 200): String? {
    val t = SnippetEdges.tidy(text.trim().replace(Regex("\\s+"), " "), before)
    if (t.isEmpty()) return null
    if (t.length <= limit) return t
    val cut = t.lastIndexOf(' ', limit).let { if (it < limit / 2) limit else it }
    return t.substring(0, cut) + "…"
  }

  /**
   * [snippet] with the half words at its ends dropped. The source around it is the segment's own text: the characters beside the quoted
   * stretch, or, where it begins the chunk, the end of the chunk before it ([charsBefore]). Unchanged when the stretch cannot be placed.
   */
  private fun tidied(snippet: String?, from: SegmentFacts?): String? {
    val text = from?.text
    if (snippet == null || text == null) return snippet
    val plain = snippet.removePrefix("…").removeSuffix("…").replace("«", "").replace("»", "")
    if (plain.isEmpty()) return snippet
    val at = text.indexOf(plain)
    if (at < 0) return snippet
    val before = if (at > 0) text.substring(maxOf(0, at - 2), at) else charsBefore(from)
    val end = at + plain.length
    return SnippetEdges.tidy(snippet, before, text.substring(end, minOf(text.length, end + 2)))
  }

  /**
   * The (up to two) characters of the file's text just before this chunk begins; "" when it begins the text or the neighbour cannot be
   * placed. [Chunker] starts a chunk [Chunker.OVERLAP_CHARS] before the end of the one before it, so the chunk's first characters are that
   * chunk's last ones, and the character before them is read off the neighbour. If the two do not line up (a first chunk, another page,
   * text cut some other way) the start is taken to be clean. One small query, and only for a chunk that starts like a word.
   */
  private fun charsBefore(chunk: SegmentFacts): String {
    val text = chunk.text
    val k = Chunker.OVERLAP_CHARS
    if (chunk.kind != SegmentKind.CHUNK || text == null || text.length < k) return ""
    if (!text[0].isLetterOrDigit() && text[0] != '\'' && text[0] != '’') return ""
    var previous: String? = null
    try {
      store.read { db ->
        db.query(
          "SELECT fts.body FROM segments s JOIN fts ON fts.rowid = s.id WHERE s.file = ? AND s.kind = 'chunk' AND s.id < ? ORDER BY s.id DESC LIMIT 1",
          chunk.file, chunk.id,
        ) { previous = if (it.isNull(0)) null else it.getText(0) }
      }
    } catch (e: androidx.sqlite.SQLiteException) {
      return ""
    }
    val prev = previous ?: return ""
    if (prev.length <= k || !prev.regionMatches(prev.length - k, text, 0, k)) return ""
    return prev.substring(maxOf(0, prev.length - k - 2), prev.length - k)
  }

  private companion object {
    const val MAX_WORD_FILES = 100
    const val MAX_CARRIERS = 2000
    /** A file whose words cover this much of the query (every word, allowing for float sums) holds all of it. */
    const val LITERAL_COVERAGE = 0.999
    /** How many of a video's key frames stand for it as a find-similar seed. */
    const val SIMILAR_SEED_VECTORS = 8
    /** The segments whose text is the file's own words: chunks, pages, OCR and what was said or heard. Not names or folders. */
    val PHRASE_KINDS = listOf(SegmentKind.CHUNK, SegmentKind.PAGE, SegmentKind.OCR, SegmentKind.TRANSCRIPT, SegmentKind.SOUND)
      .joinToString(",") { "'${it.db}'" }
    val TEXT_SEGMENTS = setOf(SegmentKind.CHUNK, SegmentKind.PAGE, SegmentKind.OCR, SegmentKind.TRANSCRIPT, SegmentKind.SOUND)
  }
}
