package com.craneai.findr.ui.search

import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craneai.findr.core.ClockSense
import com.craneai.findr.core.DateFilter
import com.craneai.findr.core.DateParser
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SortOrder
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale

/** What the home list is showing right now. */
enum class SearchMode { RECENT, RESULTS }

data class SearchUiState(
  val query: String = "",
  val filter: KindFilter = KindFilter.ALL,
  val mode: SearchMode = SearchMode.RECENT,
  val feed: List<FeedItem> = emptyList(),
  /** A search is in flight (the previous list stays on screen, so typing never flashes empty). */
  val searching: Boolean = false,
  /** The query ran and nothing matched at all. */
  val nothingFound: Boolean = false,
  /** Results may grow as Findr finishes reading files. */
  val indexIncomplete: Boolean = false,
  /** Results are for `query`; false while the box has moved on and the debounce has not fired yet. */
  val resultsQuery: String = "",
  /** The first list (recent files, for a fresh start) has been built. Drives reportFullyDrawn. */
  val loaded: Boolean = false,
  /** Sensitive files kept out of this list: only counted ("3 hidden"), never named. */
  val hiddenCount: Int = 0,
  /** Sensitive files are in this list (the person unlocked them, or turned the setting on). The window goes FLAG_SECURE. */
  val showsSensitive: Boolean = false,
  /** They are shown only for now (unlocked on the chip), so the chip offers to hide them again. */
  val revealedForNow: Boolean = false,
  /** The embedding engine failed its self-test: results are from words alone, and the screen says Findr is repairing itself. */
  val repairing: Boolean = false,
  /** The order of the list now on screen. Best match only exists with a query; browsing is Newest unless changed. */
  val sort: SortOrder = SortOrder.NEWEST,
  /** The date filter in force (read from the query, or chosen on the chip), or null for any time. */
  val date: ActiveDate? = null,
  /** The files ticked in selection mode (index ids). Selection mode is on exactly while this is not empty. */
  val selected: Set<Long> = emptySet(),
  /** "Find similar" is on: the list is the files like this one (the query is empty). Shown as one chip the person can take away. */
  val similar: SimilarTo? = null,
  /** How far the phone's clock is behind its own files, in ms ([com.craneai.findr.core.ClockSense]); 0 for a right clock. Ages on the list count from clock + this. */
  val clockSkewMs: Long = 0L,
)

/** The file "Find similar" started from. */
data class SimilarTo(val id: Long, val name: String)

/**
 * The date the list is limited to. [phrase] is the text of the query it was read from ("last week"); null when the person chose it
 * from the date chip. Either way it is one chip the person can take away.
 */
data class ActiveDate(val filter: DateFilter, val phrase: String? = null)

/** What, in the indexing status, makes the visible list stale. Not the counts: they change on every file, the list must not reload that often. */
internal fun refreshKey(status: IndexStatus): Triple<IndexPhase, PauseReason?, Boolean> =
  Triple(status.phase, status.pauseReason, status.filesKnown == 0)

/** The date chip's choice when it was made by hand: a quick preset (worked out again at each search, so "Today" stays today) or a picked range. */
private sealed interface ManualDate {
  data class Preset(val preset: DatePreset) : ManualDate
  data class Picked(val filter: DateFilter) : ManualDate
}

class SearchViewModel(
  private val engine: SearchEngine,
  private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
  private val weekStart: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
  /** Where the browse feed is built (thousands of items, off the UI thread). Tests pass their own dispatcher so they can wait for it. */
  private val feedDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
  private data class Request(val query: String, val filter: KindFilter, val immediate: Boolean, val nonce: Int = 0)

  /** What a raw query, the chips and the sorts add up to: the words to search, the date, the order, and whether it is a search at all. */
  private class Derived(val text: String, val date: ActiveDate?, val sort: SortOrder, val similar: Boolean = false) {
    /** Browsing: no words and no "Find similar" (that is a result list of its own, with Best match). */
    val browse get() = text.isEmpty() && !similar
    val view get() = ResultView(sort, date?.filter?.range, date?.phrase)
  }

  // The sort is remembered for the session, one per kind of list: browsing and results. Not saved to disk: a new launch starts at
  // Newest / Best match, the order people expect first.
  private var browseSort = SortOrder.NEWEST
  private var resultsSort = SortOrder.BEST
  private var manualDate: ManualDate? = null
  /** Date phrases the person took off with the chip's cross: read as ordinary words until they leave the query. */
  private val ignored = HashSet<String>()

  /**
   * The dates of the newest files, as of the last load: what [ClockSense] weighs against the phone's clock. A phone whose clock is
   * behind its own files (a TECNO that read 31 August with files dated October) would otherwise resolve "in October" to a month
   * that has not happened. Empty until the first load, and for an engine without an index: the clock then stands.
   */
  @Volatile private var clockEvidence: List<Long> = emptyList()

  /** The clock, unless the files prove it behind ([ClockSense]); to the millisecond the clock itself when it is right. */
  private fun now(): ZonedDateTime = ClockSense.now(clock(), clockEvidence)

  /** Reads the newest files' dates again (an index scan) and tells the screen how far behind the clock is, if it is. */
  private suspend fun senseClock() {
    val clockMs = clock().toInstant().toEpochMilli()
    clockEvidence = engine.newestDates(ClockSense.EVIDENCE_FILES, ClockSense.ceiling(clockMs))
    val skew = ClockSense.skewMs(clockMs, clockEvidence)
    if (_state.value.clockSkewMs != skew) _state.update { it.copy(clockSkewMs = skew) }
  }

  private fun derive(query: String): Derived {
    val parsed = DateParser.parse(query, now(), weekStart(), ignored)
    val date = parsed.date?.let { ActiveDate(it.filter, it.phrase) } ?: when (val m = manualDate) {
      is ManualDate.Preset -> ActiveDate(DateParser.preset(m.preset, now(), weekStart()))
      is ManualDate.Picked -> ActiveDate(m.filter)
      null -> null
    }
    val text = parsed.text.trim()
    val similar = similarTo != null && text.isEmpty()
    return Derived(text, date, if (text.isEmpty() && !similar) browseSort else resultsSort, similar)
  }

  /** Brings the chips in the state up to date at once (the list itself follows when its search lands). */
  private fun showDerived(query: String) {
    val d = derive(query)
    _state.update { it.copy(sort = d.sort, date = d.date) }
  }

  private val request = MutableStateFlow(Request("", KindFilter.ALL, immediate = true))
  private val _state = MutableStateFlow(SearchUiState())
  val state: StateFlow<SearchUiState> = _state

  private var lastResults: SearchResults? = null
  private var weakExpanded = false
  /** The file "Find similar" was started on (whole file: the engine needs its name too). Typing a query leaves it. */
  private var similarTo: IndexedFile? = null

  // FindrPerf (J2): what the person feels. Time from the last keystroke to the words-first answer being on screen, to the
  // meaning answer, and whether the top result moved between the two. One logcat line each (`adb logcat -s FindrPerf`).
  private var typedAtNs = 0L
  private var wordsTopId: Long? = null
  private var pendingMark: String? = null

  init {
    viewModelScope.launch {
      request.collectLatest { r ->
        // Type-to-filter: the words answer comes on every keystroke (one FTS query, ~60 ms on the Infinix). Only the meaning
        // step waits for a ~250 ms pause in typing (ProgressiveSearch), so the next keystroke cancels it before an embed starts.
        // Chips, clearing and refreshes do not wait at all.
        try {
          load(r)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          _state.update { it.copy(searching = false) }
        }
      }
    }
    // Results fill in as indexing advances; re-run the current search whenever the phase changes. "Delete everything" while already
    // paused keeps the phase (it only changes why, and empties the index), so the reason and "nothing known" are part of the key:
    // the recent list, the hidden chip and any results must not keep file names the person just deleted.
    viewModelScope.launch {
      engine.status.map { refreshKey(it) }.distinctUntilChanged().drop(1).collect {
        request.update { r -> r.copy(immediate = true, nonce = r.nonce + 1) }
      }
    }
    // The engine's self-test failing (or passing again) changes what a search can do: show the note, and run the search again.
    viewModelScope.launch {
      engine.searchRepairing.collect { r ->
        val changed = _state.value.repairing != r
        _state.update { it.copy(repairing = r) }
        if (changed) request.update { q -> q.copy(immediate = true, nonce = q.nonce + 1) }
      }
    }
    // Unlocking or hiding sensitive results, or flipping the setting, changes what the list holds: run it again at once.
    viewModelScope.launch {
      combine(engine.sensitiveRevealed, engine.settings.map { it.showSensitive }.distinctUntilChanged()) { a, b -> a to b }
        .distinctUntilChanged().drop(1).collect {
          request.update { r -> r.copy(immediate = true, nonce = r.nonce + 1) }
        }
    }
  }

  private val _markedSensitive = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  /** Fires after a file is marked sensitive (not unmarked). Home uses it to offer the app lock the first time (onboarding v2). */
  val markedSensitive: SharedFlow<Unit> = _markedSensitive

  /** "Mark as sensitive" / "Not sensitive" on one file. The list reloads, so a newly marked file leaves it at once. */
  fun markSensitive(file: IndexedFile, sensitive: Boolean) {
    viewModelScope.launch {
      engine.setSensitive(file, sensitive)
      if (sensitive) _markedSensitive.tryEmit(Unit)
      request.update { r -> r.copy(immediate = true, nonce = r.nonce + 1) }
    }
  }

  /** "Mark as sensitive" / "Not sensitive" on every file in [files] that the change applies to (marking skips the already sensitive, unmarking skips those only sensitive by their folder). */
  fun markSensitive(files: List<IndexedFile>, sensitive: Boolean) {
    val todo = files.filter { if (sensitive) !it.sensitive else it.markedSensitive }
    if (todo.isEmpty()) return
    viewModelScope.launch {
      todo.forEach { engine.setSensitive(it, sensitive) }
      if (sensitive) _markedSensitive.tryEmit(Unit)
      clearSelection()
      request.update { r -> r.copy(immediate = true, nonce = r.nonce + 1) }
    }
  }

  fun setQuery(text: String) {
    if (text == request.value.query) return
    if (text.isNotBlank() && similarTo != null) { similarTo = null; _state.update { it.copy(similar = null) } }
    typedAtNs = System.nanoTime()
    wordsTopId = null
    weakExpanded = false
    // A dismissed date phrase stays dismissed only while it is still in the box.
    val lower = text.lowercase()
    ignored.retainAll { lower.contains(it) }
    _state.update { it.copy(query = text, searching = text.isNotBlank()) }
    showDerived(text)
    request.update { it.copy(query = text, immediate = false) }
  }

  fun setFilter(filter: KindFilter) {
    if (filter == request.value.filter) return
    _state.update { it.copy(filter = filter) }
    request.update { it.copy(filter = filter, immediate = true) }
  }

  // ---- find similar --------------------------------------------------------------------------------------------------

  /**
   * "Find similar" on [file]: the result list becomes the files whose picture vectors are closest to its own. The query box is
   * emptied (the words and the file cannot both be the question); the kind chips, the date and the sort keep working.
   */
  fun findSimilar(file: IndexedFile) {
    similarTo = file
    weakExpanded = false
    typedAtNs = 0L
    ignored.clear()
    _state.update { it.copy(query = "", similar = SimilarTo(file.id, file.name), searching = true, selected = emptySet()) }
    showDerived("")
    request.update { it.copy(query = "", immediate = true, nonce = it.nonce + 1) }
  }

  /** The chip's cross: back to the home list. */
  fun clearSimilar() {
    if (similarTo == null) return
    similarTo = null
    _state.update { it.copy(similar = null) }
    rerun()
  }

  // ---- sort and date -------------------------------------------------------------------------------------------------

  /** The order of whatever list is on screen: results (Best match by default) or browsing (Newest by default). Remembered for the session. */
  fun setSort(order: SortOrder) {
    val browse = derive(request.value.query).browse
    if (browse) browseSort = if (order == SortOrder.BEST) SortOrder.NEWEST else order else resultsSort = order
    rerun()
  }

  fun setDatePreset(preset: DatePreset) { manualDate = ManualDate.Preset(preset); rerun() }

  fun setDateRange(from: LocalDate, to: LocalDate) {
    manualDate = ManualDate.Picked(DateParser.custom(from, to, clock().zone))
    rerun()
  }

  /**
   * The chip's cross. A date read from the words is dismissed (those words are searched as ordinary words from now on, and the box is
   * not edited); a date chosen on the chip is simply cleared. Anything the person chose by hand stays under a dismissed phrase.
   */
  fun clearDate() {
    val phrase = _state.value.date?.phrase
    if (phrase != null) ignored += phrase else manualDate = null
    rerun()
  }

  private fun rerun() {
    showDerived(request.value.query)
    weakExpanded = false
    request.update { it.copy(immediate = true, nonce = it.nonce + 1) }
  }

  // ---- selection -----------------------------------------------------------------------------------------------------

  /** Long-press starts selection with this file; a tap while selecting ticks or unticks it. Unticking the last one ends selection. */
  fun toggleSelected(file: IndexedFile) = _state.update {
    it.copy(selected = if (file.id in it.selected) it.selected - file.id else it.selected + file.id)
  }

  /** Select all ticks everything the list shows; when everything is ticked already it clears instead. */
  fun selectAll() = _state.update {
    val all = it.feed.shownFiles().map { f -> f.id }.toSet()
    it.copy(selected = if (it.selected.containsAll(all)) emptySet() else all)
  }

  fun clearSelection() = _state.update { if (it.selected.isEmpty()) it else it.copy(selected = emptySet()) }

  /** The ticked files, in list order. */
  fun selectedFiles(): List<IndexedFile> = _state.value.let { s -> s.feed.shownFiles().filter { it.id in s.selected } }

  /** The search box gained focus: warm the model while the keyboard comes up (SearchEngine.prewarm is cheap if already warm). */
  fun prewarm() = engine.prewarm()

  /** Scrolling or typing: the index worker waits between files so the grid gets the CPU (SearchEngine.setUserInteracting). */
  fun setInteracting(active: Boolean) = engine.setUserInteracting(active || focusHeld)

  /** The results grid is on screen (and not covered by the meter sheet): GPU encodes hold back (SearchEngine.setListVisible). */
  fun setListVisible(visible: Boolean) = engine.setListVisible(visible)

  private var focusHeld = false
  private var focusRelease: kotlinx.coroutines.Job? = null

  /**
   * The search box gained or lost focus. While it has focus the worker starts no new file, so the in-flight encode drains while
   * the person types and the first query does not queue behind it (the engine runs one model call at a time; that wait was
   * about 2 s of J2 during indexing on the A06). Released after 30 s at most, so a box left focused never stalls indexing.
   */
  fun setSearchFocus(focused: Boolean) {
    focusRelease?.cancel()
    focusHeld = focused
    engine.setUserInteracting(focused)
    if (focused) focusRelease = viewModelScope.launch {
      delay(FOCUS_HOLD_MS)
      focusHeld = false
      engine.setUserInteracting(false)
    }
  }

  fun toggleWeak() {
    weakExpanded = !weakExpanded
    val results = lastResults ?: return
    val sorted = derive(request.value.query).sort != SortOrder.BEST
    _state.update { it.copy(feed = buildResultFeed(results.hits, results.strongCount, weakExpanded, sorted)) }
  }

  /** The UI calls this one frame after a new feed was composed: that is when its pixels are on screen. */
  fun feedDrawn() {
    val mark = pendingMark ?: return
    pendingMark = null
    Log.i(PERF, mark.replace("@draw", "${(System.nanoTime() - typedAtNs) / 1_000_000}"))
  }

  private fun markResults(results: SearchResults) {
    if (typedAtNs == 0L) return
    val ids = results.hits.map { it.file.id }
    if (!results.complete) {
      wordsTopId = ids.firstOrNull()
      pendingMark = "words_ms=@draw q=\"${redact(results.query)}\" hits=${ids.size}"
    } else {
      val oldTop = wordsTopId
      val moved = if (oldTop == null) "n/a" else if (oldTop == ids.firstOrNull()) "same" else "moved_to_rank=${ids.indexOf(oldTop) + 1}"
      pendingMark = "meaning_ms=@draw q=\"${redact(results.query)}\" hits=${ids.size} top=$moved"
    }
  }

  private suspend fun load(r: Request) {
    senseClock()
    val d = derive(r.query)
    val q = d.text
    val seed = similarTo.takeIf { q.isEmpty() }
    if (seed != null) {
      _state.update { it.copy(searching = true, sort = d.sort, date = d.date) }
      val results = engine.similar(seed, r.filter, SIMILAR_LIMIT, d.view)
      if (similarTo?.id != seed.id) return   // left "Find similar" while it ran: the list that follows is not this one
      lastResults = results
      val feed = buildResultFeed(results.hits, results.strongCount, weakExpanded, sorted = d.sort != SortOrder.BEST)
      _state.update {
        it.copy(
          mode = SearchMode.RESULTS,
          feed = feed,
          selected = it.selected.keepIn(feed),
          searching = false,
          nothingFound = results.hits.isEmpty(),
          // "Still learning" only when the list is genuinely thin: a full list of similar pictures needs no excuse.
          indexIncomplete = results.indexIncomplete && results.hits.size < SIMILAR_THIN,
          resultsQuery = "",
          loaded = true,
          hiddenCount = results.hiddenCount,
          showsSensitive = results.hits.any { h -> h.file.sensitive },
          revealedForNow = engine.sensitiveRevealed.value && !engine.settings.value.showSensitive,
        )
      }
      return
    }
    if (q.isEmpty()) {
      lastResults = null
      val recent = engine.recent(r.filter, RECENT_LIMIT, d.view)
      val hidden = engine.recentHiddenCount(r.filter, d.view.dates)
      // The grouped "recent documents, then photos" list is the start screen; a chosen sort or date, or a date typed alone, is one list.
      val grouped = d.sort == SortOrder.NEWEST && d.date == null
      val feed = withContext(feedDispatcher) { if (grouped) buildRecentFeed(recent) else buildSortedFeed(recent) } // thousands of items: not on the UI thread
      _state.update {
        it.copy(
          sort = d.sort,
          date = d.date,
          selected = it.selected.keepIn(feed),
          mode = SearchMode.RECENT,
          feed = feed,
          loaded = true,
          searching = false,
          nothingFound = false,
          indexIncomplete = false,
          resultsQuery = "",
          hiddenCount = hidden,
          showsSensitive = recent.any { f -> f.sensitive },
          revealedForNow = engine.sensitiveRevealed.value && !engine.settings.value.showSensitive,
        )
      }
      return
    }
    _state.update { it.copy(searching = true, sort = d.sort, date = d.date) }
    // Word matches arrive at once, meaning matches when the model is free (it may be busy indexing): show each step.
    var phraseWasContent = false
    engine.searchProgressive(q, r.filter, meaningDelayMs = if (r.immediate) 0L else DEBOUNCE_MS, view = d.view).collect { results ->
      // A date read from the words that leaves nothing: unless the phrase is part of what the person is looking for (it is written in
      // a file that also matches the rest of the query), in which case it is searched as ordinary words instead.
      val phrase = d.date?.phrase
      if (results.complete && results.hits.isEmpty() && phrase != null && !phraseWasContent && phraseIsContent(r, phrase)) {
        phraseWasContent = true
        return@collect
      }
      // A half-typed word that matches nothing keeps the last list for a moment instead of flashing "nothing found" between
      // keystrokes; the next keystroke cancels this wait. A finished search shows its answer exactly as before.
      if (!r.immediate && results.complete && results.hits.isEmpty()) delay(DEBOUNCE_MS)
      lastResults = results
      val feed = buildResultFeed(results.hits, results.strongCount, weakExpanded, sorted = d.sort != SortOrder.BEST)
      // An answer whose list equals the one already drawn composes nothing new, so the screen's draw hook (keyed on the feed) never
      // fires for it and its mark would wait for the next, different list. The pixels are right already: close the mark now.
      // (With a draw still pending for that same list, pendingMark is not null and that draw closes this mark instead.)
      val alreadyDrawn = pendingMark == null && feed.isNotEmpty() && _state.value.feed == feed
      markResults(results)
      _state.update {
        it.copy(
          mode = SearchMode.RESULTS,
          feed = feed,
          selected = it.selected.keepIn(feed),
          searching = !results.complete,
          nothingFound = results.complete && results.hits.isEmpty(),
          indexIncomplete = results.indexIncomplete,
          resultsQuery = q,
          hiddenCount = results.hiddenCount,
          showsSensitive = results.hits.any { h -> h.file.sensitive },
          revealedForNow = engine.sensitiveRevealed.value && !engine.settings.value.showSensitive,
        )
      }
      if (alreadyDrawn) feedDrawn()
    }
    if (phraseWasContent) load(r) // the phrase is in `ignored` now: the same query, without the date chip
  }

  /** Asks the query again with [phrase] read as words. If that proves the phrase was content, it stays in [ignored] and this returns true. */
  private suspend fun phraseIsContent(r: Request, phrase: String): Boolean {
    // Dismissing "from september" lets the scan go on to the shorter "september" inside it: dismiss every reading of the same words.
    val added = ArrayList<String>()
    if (ignored.add(phrase)) added += phrase
    while (true) {
      val inner = DateParser.parse(r.query, now(), weekStart(), ignored).date?.phrase ?: break
      if (inner !in phrase) break
      ignored += inner; added += inner
    }
    var proven = false
    try {
      val d = derive(r.query)
      // Only files that matched on words can show the phrase is written in them; a meaning-only hit proves nothing.
      val matched = engine.search(d.text, r.filter, view = d.view).hits.filter { it.reason != MatchReason.MEANING }.map { it.file }
      proven = matched.isNotEmpty() && engine.anyMentions(phrase, matched)
    } finally {
      if (!proven) ignored.removeAll(added.toSet()) // also when a keystroke cancels the probe: the date applies until it is proven wrong
    }
    return proven
  }

  /** Ticks for files that are still in the list: a reload (indexing moved on, a file was marked) must not keep a tick on a file that left. */
  private fun Set<Long>.keepIn(feed: List<FeedItem>): Set<Long> =
    if (isEmpty()) this else feed.shownFiles().mapTo(HashSet()) { it.id }.let { shown -> filterTo(HashSet()) { it in shown } }

  private companion object {
    /** How long typing must pause before the meaning step (the query embed) starts. Words do not wait. */
    const val DEBOUNCE_MS = 250L
    const val FOCUS_HOLD_MS = 30_000L
    const val PERF = "FindrPerf"

    /** The home grid is the recent library, not one screenful of it: scrolling to the end must not hit a wall at 60. */
    const val RECENT_LIMIT = 5000

    /** How many files "Find similar" lists, at most (a search lists 60). */
    const val SIMILAR_LIMIT = 60
    /** Fewer similar pictures than this while the index is unfinished: the list is thin, and the "still learning" note is shown. */
    const val SIMILAR_THIN = 10
  }
}
