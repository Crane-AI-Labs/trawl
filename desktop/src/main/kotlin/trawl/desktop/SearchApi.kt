package trawl.desktop

import com.craneai.findr.core.ClockSense
import com.craneai.findr.core.DateFilter
import com.craneai.findr.core.DateParser
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.SortOrder
import com.craneai.findr.engine.ProgressiveSearch
import com.craneai.findr.engine.QueryVectors
import com.craneai.findr.index.IndexStore
import com.craneai.findr.search.Searcher
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What the search side needs from the index service, as plain functions so the search logic (this file) can be tested without a whole
 * service and the service does not grow a second copy of the phone's rules. [IndexService.searchHooks] builds the real one.
 */
class SearchHooks(
  val store: () -> IndexStore?,
  val searcher: () -> Searcher?,
  /** A text model is installed, so a query can be given a meaning vector. */
  val modelReady: () -> Boolean,
  /** How many vectors the matrix holds (0: nothing to search by meaning yet). */
  val vectorCount: () -> Int,
  /** The query's meaning vector; blocks (runs on the default dispatcher). Null when there is no model. */
  val embed: (String) -> FloatArray?,
  val wordsWeight: (String) -> Double,
  val meaningWeight: (String, FloatArray?) -> Double,
  /** The kinds the person has switched on in Settings. */
  val kinds: () -> Set<FileKind>,
  /** A new question was asked: reading steps aside for a moment. */
  val touch: (String, KindFilter) -> Unit = { _, _ -> },
  /** Whether sensitive files may be listed; fails closed. */
  val includeSensitive: () -> Boolean = { false },
)

/** The file "Find similar" started from. */
data class SimilarTo(val id: Long, val name: String)

/**
 * The date the list is limited to. [phrase] is the text of the query it was read from ("last week"); null when the person chose it from
 * the date chip. Either way it is one chip they can take away. Same meaning as the phone's own.
 */
data class ActiveDate(val filter: DateFilter, val phrase: String? = null)

/** What the Recent list asks the index for, and what came back. */
class RecentList(val files: List<IndexedFile>, val hidden: Int)

/**
 * The search the window and the quick panel both call. It owns nothing but the questions: the ranking, the sorts, the date ranges and the
 * hiding of sensitive files are the core's ([Searcher], [IndexStore]), the same code the phone runs. Nothing here ranks.
 */
class SearchApi(private val h: SearchHooks) {
  /**
   * Words first (at once), then the fused answer (words and meaning together), as the phone's [ProgressiveSearch] does it. The first
   * emission has `complete = false` when a meaning answer is coming. [meaningDelayMs] holds the embed back while a word is still being
   * typed (the next keystroke cancels this flow first).
   */
  fun search(query: String, filter: KindFilter, view: ResultView = ResultView.DEFAULT, limit: Int = LIMIT, meaningDelayMs: Long = 0, withSensitive: Boolean? = null): Flow<SearchResults> = flow {
    h.touch(query.trim(), filter)
    val st = h.searcher()
    if (st == null) { emit(SearchResults(query, emptyList(), 0, 0L, indexIncomplete = true)); return@flow }   // the index is still opening
    val on = h.kinds()
    // A kind that is switched off is out of results even though its files stay in the index (switching it on again is instant). Ask for
    // more than [limit] when something is off, so the hits that are dropped do not leave the list short.
    val ask = if (on.size < Crawler.KINDS.size) limit * 3 else limit
    val progressive = ProgressiveSearch(
      searcher = { h.searcher() ?: st },
      meaningPossible = h.modelReady,
      storedVectors = { h.vectorCount() > 0 },
      ensureVectors = {},   // the desktop loads the matrix when the index opens
      wantsMeaning = { q -> q.length >= ProgressiveSearch.MIN_MEANING_CHARS && h.modelReady() && h.vectorCount() > 0 },
      embed = { q -> QueryVectors(withContext(Dispatchers.Default) { h.embed(q) }, null) },
      includeSensitive = { withSensitive ?: h.includeSensitive() },
      wordsWeight = { q -> h.wordsWeight(q) },
      meaningWeight = { q, v -> h.meaningWeight(q, v) },
    )
    emitAll(progressive.run(query, filter, ask, meaningDelayMs, view).map { onlyKinds(it, on, limit) })
  }.flowOn(Dispatchers.Default)

  /** The newest files (or the sort/date the view asks for), for the window before anything is typed. */
  fun recent(filter: KindFilter, view: ResultView = ResultView.DEFAULT, limit: Int = RECENT_LIMIT, withSensitive: Boolean? = null): RecentList {
    val st = h.store() ?: return RecentList(emptyList(), 0)
    val kinds = filter.kinds.intersect(h.kinds())
    val sensitive = withSensitive ?: h.includeSensitive()
    val files = st.recent(kinds, limit, includeSensitive = sensitive, order = view.sort, dates = view.dates)
    val hidden = if (sensitive) 0 else st.recentHiddenCount(kinds, view.dates)
    return RecentList(files, hidden)
  }

  /** The files whose pictures are closest to [file]'s own (the phone's "Find similar"). Empty for a file with no picture vector. */
  suspend fun similar(file: IndexedFile, filter: KindFilter, view: ResultView = ResultView.DEFAULT, limit: Int = SIMILAR_LIMIT): SearchResults = withContext(Dispatchers.Default) {
    h.touch(file.name, filter)
    val st = h.searcher() ?: return@withContext SearchResults(file.name, emptyList(), 0, 0L, indexIncomplete = true)
    val on = h.kinds()
    val ask = if (on.size < Crawler.KINDS.size) limit * 3 else limit
    onlyKinds(st.similar(file.id, filter, ask, includeSensitive = h.includeSensitive(), view = view, label = file.name), on, limit)
  }

  /** The dates of the newest files: what [ClockSense] weighs against the computer's clock. */
  fun newestDates(clockMs: Long): List<Long> = h.store()?.newestDates(ClockSense.EVIDENCE_FILES, ClockSense.ceiling(clockMs)) ?: emptyList()

  /** True when any of [files] holds [phrase] as words (its name or its text), which makes a date phrase part of what is looked for. */
  fun anyMentions(phrase: String, files: List<IndexedFile>): Boolean {
    val st = h.searcher() ?: return false
    return st.mentioning(phrase, files.map { it.id }).isNotEmpty()
  }

  fun fileById(id: Long): IndexedFile? = h.store()?.fileById(id)

  /**
   * The opening text of a document, PDF, recording or video as it was indexed: what the preview shows for a file that came up with no match
   * to quote (the Recent list, where nothing was typed). The first stored chunks joined up to about [maxChars], cut at a word; null when the
   * file has no text (a picture, an empty file, one that is not indexed yet). Reads the index only, so it is quick; call it off the UI thread.
   */
  fun leadText(fileId: Long, maxChars: Int = LEAD_CHARS): String? {
    val st = h.store() ?: return null
    return joinLead(st.storedChunks(fileId, 0, LEAD_CHUNKS).map { it.second }, maxChars)
  }

  companion object {
    const val LIMIT = 80
    /** The Recent list is the library's newest files, not one screenful of them. */
    const val RECENT_LIMIT = 600
    const val SIMILAR_LIMIT = 60
    /** The preview's lead text: at most this many characters, from at most this many of the file's first chunks. */
    const val LEAD_CHARS = 1200
    const val LEAD_CHUNKS = 6

    /** [chunks] as one run of text of about [maxChars] (whitespace collapsed, cut at a word with an ellipsis); null when there is none. Pure. */
    fun joinLead(chunks: List<String>, maxChars: Int): String? {
      val sb = StringBuilder()
      for (c in chunks) {
        val one = c.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty()) continue
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(one)
        if (sb.length > maxChars) break
      }
      if (sb.isEmpty()) return null
      if (sb.length <= maxChars) return sb.toString()
      val cut = sb.lastIndexOf(" ", maxChars).takeIf { it > maxChars / 2 } ?: maxChars
      return sb.substring(0, cut).trimEnd() + "\u2026"
    }
    /** Fewer similar pictures than this while the index is unfinished: the list is thin, and the "still learning" note is shown. */
    const val SIMILAR_THIN = 10
    /** What can be a seed for "Find similar": pictures and videos, as on the phone (documents have no picture vector to start from). */
    val SIMILAR_KINDS = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)

    /** [r] without the hits of kinds that are switched off, cut to [limit], the strong count kept in step. Pure. */
    fun onlyKinds(r: SearchResults, on: Set<FileKind>, limit: Int): SearchResults {
      if (on.size >= Crawler.KINDS.size) return if (r.hits.size <= limit) r else r.copy(hits = r.hits.take(limit), strongCount = r.strongCount.coerceAtMost(limit))
      val kept = r.hits.withIndex().filter { it.value.file.kind in on }
      val strong = kept.count { it.index < r.strongCount }
      return r.copy(hits = kept.map { it.value }.take(limit), strongCount = strong.coerceAtMost(limit))
    }
  }
}

/** The date chip's choice when it was made by hand: a quick preset (worked out again at each search, so "Today" stays today) or a picked range. */
sealed interface ManualDate {
  data class Preset(val preset: DatePreset) : ManualDate
  data class Picked(val filter: DateFilter) : ManualDate
}

/** What a raw query, the chips and the sorts add up to: the words to search, the date, the order, and whether it is a search at all. */
class Derived(val text: String, val date: ActiveDate?, val sort: SortOrder, val similar: Boolean = false) {
  /** Browsing: no words and no "Find similar" (that is a result list of its own, with Best match). */
  val browse: Boolean get() = text.isEmpty() && !similar
  val view: ResultView get() = ResultView(sort, date?.filter?.range, date?.phrase)
}

/** One answer for the window: what [SearchSession.load] reports as it goes. */
data class SessionAnswer(
  val derived: Derived,
  val query: String,
  val hits: SearchResults?,
  val recent: RecentList?,
  val similar: SimilarTo?,
  val skewMs: Long,
)

/**
 * The phone's view-model rules for sort and date, ported with the same names so the two stay readable side by side:
 *  - the sort is remembered per kind of list (browsing starts at Newest, results at Best match), for the session only;
 *  - a date typed in the words ("last week") is read by the core's [DateParser]; a date chosen on the chip is a preset (worked out again
 *    each time) or a picked range; the chip's cross dismisses a typed phrase (it is then ordinary words) or clears a chosen date;
 *  - a typed date phrase that finds nothing is searched again as words, and kept as words if a file proves the phrase is content;
 *  - "now" is the computer's clock unless the files prove it behind ([ClockSense]).
 * Not thread safe by itself: the controller calls it from one collector at a time and sets the chips from the UI thread (the fields are
 * volatile or copied under [lock]).
 */
class SearchSession(
  private val api: SearchApi,
  private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
  private val weekStart: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
) {
  private val lock = Any()
  private var browseSort = SortOrder.NEWEST
  private var resultsSort = SortOrder.BEST
  private var manualDate: ManualDate? = null
  private val ignored = HashSet<String>()
  @Volatile private var similarTo: IndexedFile? = null
  @Volatile private var clockEvidence: List<Long> = emptyList()

  val similar: SimilarTo? get() = similarTo?.let { SimilarTo(it.id, it.name) }

  /** The clock, unless the files prove it behind ([ClockSense]); to the millisecond the clock itself when it is right. */
  fun now(): ZonedDateTime = ClockSense.now(clock(), clockEvidence)

  /** How far the computer's clock is behind its own files, in ms; 0 for a right clock. Ages count from clock + this. */
  fun skewMs(): Long = ClockSense.skewMs(clock().toInstant().toEpochMilli(), clockEvidence)

  private fun senseClock() {
    clockEvidence = api.newestDates(clock().toInstant().toEpochMilli())
  }

  fun derive(query: String): Derived = synchronized(lock) {
    val parsed = DateParser.parse(query, now(), weekStart(), ignored)
    val date = parsed.date?.let { ActiveDate(it.filter, it.phrase) } ?: when (val m = manualDate) {
      is ManualDate.Preset -> ActiveDate(DateParser.preset(m.preset, now(), weekStart()))
      is ManualDate.Picked -> ActiveDate(m.filter)
      null -> null
    }
    val text = parsed.text.trim()
    val similar = similarTo != null && text.isEmpty()
    Derived(text, date, if (text.isEmpty() && !similar) browseSort else resultsSort, similar)
  }

  /** A dismissed date phrase stays dismissed only while it is still in the box. Call on each new query text. */
  fun queryChanged(text: String) {
    synchronized(lock) {
      if (text.isNotBlank() && similarTo != null) similarTo = null
      val lower = text.lowercase()
      ignored.retainAll { lower.contains(it) }
    }
  }

  fun setSort(order: SortOrder, query: String) {
    val browse = derive(query).browse
    synchronized(lock) { if (browse) browseSort = if (order == SortOrder.BEST) SortOrder.NEWEST else order else resultsSort = order }
  }

  fun setDatePreset(preset: DatePreset) = synchronized(lock) { manualDate = ManualDate.Preset(preset) }

  fun setDateRange(from: LocalDate, to: LocalDate) = synchronized(lock) { manualDate = ManualDate.Picked(DateParser.custom(from, to, clock().zone)) }

  /**
   * The chip's cross. A date read from the words is dismissed (those words are searched as ordinary words from now on, and the box is not
   * edited); a date chosen on the chip is simply cleared.
   */
  fun clearDate(query: String) {
    val phrase = derive(query).date?.phrase
    synchronized(lock) { if (phrase != null) ignored += phrase else manualDate = null }
  }

  fun findSimilar(file: IndexedFile) = synchronized(lock) { similarTo = file; ignored.clear() }
  fun clearSimilar() = synchronized(lock) { similarTo = null }

  /**
   * Answers [query] for [filter], reporting each step to [report]: words first, then the fused answer; the Recent list when the words are
   * empty; the similar list when "Find similar" is on. [meaningDelayMs] holds the embed back while a word is being typed. Cancel it with the
   * coroutine. With [quiet] a words-only interim answer is not reported (a refresh of a list the person is already reading must not flash).
   */
  suspend fun load(query: String, filter: KindFilter, meaningDelayMs: Long, quiet: Boolean, report: (SessionAnswer) -> Unit) {
    withContext(Dispatchers.Default) { senseClock() }
    val d = derive(query)
    val skew = skewMs()
    val seed = similarTo.takeIf { d.text.isEmpty() }
    if (seed != null) {
      val r = api.similar(seed, filter, d.view)
      if (similarTo?.id != seed.id) return   // left "Find similar" while it ran: the list that follows is not this one
      report(SessionAnswer(d, "", r, null, similar, skew))
      return
    }
    if (d.text.isEmpty()) {
      val recent = withContext(Dispatchers.Default) { api.recent(filter, d.view) }
      report(SessionAnswer(d, "", null, recent, null, skew))
      return
    }
    var phraseWasContent = false
    api.search(d.text, filter, d.view, meaningDelayMs = meaningDelayMs).collect { results ->
      val phrase = d.date?.phrase
      if (results.complete && results.hits.isEmpty() && phrase != null && !phraseWasContent && phraseIsContent(query, filter, phrase)) {
        phraseWasContent = true
        return@collect
      }
      if (quiet && !results.complete) return@collect
      report(SessionAnswer(d, d.text, results, null, null, skew))
    }
    if (phraseWasContent) load(query, filter, 0L, quiet, report)   // the phrase is in `ignored` now: the same query, without the date chip
  }

  /** Asks the query again with [phrase] read as words. If that proves the phrase was content, it stays in [ignored] and this returns true. */
  private suspend fun phraseIsContent(query: String, filter: KindFilter, phrase: String): Boolean {
    val added = ArrayList<String>()
    synchronized(lock) {
      if (ignored.add(phrase)) added += phrase
      // Dismissing "from september" lets the scan go on to the shorter "september" inside it: dismiss every reading of the same words.
      while (true) {
        val inner = DateParser.parse(query, now(), weekStart(), ignored).date?.phrase ?: break
        if (inner !in phrase) break
        ignored += inner; added += inner
      }
    }
    var proven = false
    try {
      val d = derive(query)
      var last: SearchResults? = null
      api.search(d.text, filter, d.view).collect { last = it }
      // Only files that matched on words can show the phrase is written in them; a meaning-only hit proves nothing.
      val matched = last?.hits.orEmpty().filter { it.reason != MatchReason.MEANING }.map { it.file }
      proven = matched.isNotEmpty() && withContext(Dispatchers.Default) { api.anyMentions(phrase, matched) }
    } finally {
      if (!proven) synchronized(lock) { ignored.removeAll(added.toSet()) }   // also when a keystroke cancels the probe: the date applies until it is proven wrong
    }
    return proven
  }
}

/** The date a file's age is counted from: never later than [now] (a file cannot be from the future, and a wrong date must not say "in 3 min."). */
fun ageBasis(millis: Long, now: Long): Long = if (millis > now) now else millis
