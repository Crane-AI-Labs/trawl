package com.craneai.findr.quick

import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuickUiState(
  val query: String = "",
  val hits: List<SearchHit> = emptyList(),
  /** Meaning matches are still coming (the words-only answer is on screen). */
  val searching: Boolean = false,
  val nothingFound: Boolean = false,
  /** Files are still being read, so the list may grow. */
  val indexIncomplete: Boolean = false,
  /** The query these hits answer; the box may already have moved on. */
  val resultsQuery: String = "",
  /** Findr has indexed nothing yet, so an empty list means "not set up", not "no match". */
  val notSetUp: Boolean = false,
  /** Matches left out because they are marked sensitive; the "N hidden" chip counts them. */
  val hiddenCount: Int = 0,
  /** Some hit on screen is sensitive: the window turns FLAG_SECURE. */
  val showsSensitive: Boolean = false,
  /** Sensitive results are on screen because the person unlocked them for now (not because the setting is on). */
  val revealedForNow: Boolean = false,
)

/**
 * The panel's search. It adds no search logic: it feeds `SearchEngine.searchProgressive` and shows each step, exactly as the
 * main screen does, with a shorter pause before searching because the person is waiting on one answer, not browsing.
 */
class QuickSearchViewModel(private val engine: SearchEngine) : ViewModel() {
  private val query = MutableStateFlow("")
  /** Bumped to run the same query again (sensitive results shown or hidden, a file marked). */
  private val rerun = MutableStateFlow(0)
  @Volatile private var blocked = false
  private val _state = MutableStateFlow(QuickUiState())
  val state: StateFlow<QuickUiState> = _state

  // FindrPerf: keystroke to words on screen, then to meaning on screen (`adb logcat -s FindrPerf`). Same shape as the main screen's.
  private var typedAtNs = 0L
  private var wordsTopId: Long? = null
  private var pendingMark: String? = null

  init {
    viewModelScope.launch {
      // Type-to-filter: no wait before the words answer (one FTS query, tens of ms); only the meaning step waits DEBOUNCE_MS
      // for a pause in typing (ProgressiveSearch), so the next keystroke cancels it before any embed starts.
      combine(query, rerun) { q, _ -> q }.collectLatest { q ->
        try {
          load(q.trim())
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          _state.update { it.copy(searching = false) }
        }
      }
    }
  }

  init {
    // Unlocking or hiding sensitive results, or flipping the setting, changes what the list holds: run it again.
    viewModelScope.launch {
      combine(engine.sensitiveRevealed, engine.settings.map { it.showSensitive }.distinctUntilChanged()) { a, b -> a to b }
        .distinctUntilChanged().collect { rerun.update { n -> n + 1 } }
    }
  }

  /**
   * The panel must show nothing while the phone is locked or Findr's own lock is closed. Blocking clears the query and the
   * hits, and makes every later query a no-op, so no search runs and no result can arrive behind the lock screen.
   */
  fun setBlocked(value: Boolean) {
    if (blocked == value) return
    blocked = value
    if (value) {
      query.value = ""
      _state.value = QuickUiState()
    }
  }

  /** "Mark as sensitive" / "Not sensitive" on one file; the list runs again so a newly marked file leaves it at once. */
  fun markSensitive(file: IndexedFile, sensitive: Boolean) {
    viewModelScope.launch {
      engine.setSensitive(file, sensitive)
      rerun.update { it + 1 }
    }
  }

  fun setQuery(text: String) {
    if (blocked || text == query.value) return
    typedAtNs = System.nanoTime()
    wordsTopId = null
    _state.update { it.copy(query = text, searching = text.isNotBlank()) }
    query.value = text
  }

  /** The search box gained focus: load the text model while the keyboard comes up (cheap if already warm). */
  fun prewarm() = engine.prewarm()

  fun setInteracting(active: Boolean) = engine.setUserInteracting(active)

  /** The UI calls this one frame after new hits were composed: that is when their pixels are on screen. */
  fun hitsDrawn() {
    val mark = pendingMark ?: return
    pendingMark = null
    Log.i(PERF, mark.replace("@draw", "${(System.nanoTime() - typedAtNs) / 1_000_000}"))
  }

  private suspend fun load(q: String) {
    if (q.isEmpty() || blocked) {
      _state.update { it.copy(hits = emptyList(), searching = false, nothingFound = false, indexIncomplete = false, resultsQuery = "", hiddenCount = 0, showsSensitive = false) }
      return
    }
    engine.searchProgressive(q, KindFilter.ALL, LIMIT, meaningDelayMs = DEBOUNCE_MS).collect { results ->
      if (blocked) return@collect
      val nothing = results.complete && results.hits.isEmpty()
      // A half-typed word that matches nothing keeps the last list for a moment instead of flashing "nothing found" between
      // keystrokes; the next keystroke cancels this wait.
      if (nothing) delay(DEBOUNCE_MS)
      mark(results.hits.map { it.file.id }, results.complete, q)
      _state.update {
        it.copy(
          hits = results.hits,
          searching = !results.complete,
          nothingFound = nothing,
          indexIncomplete = results.indexIncomplete,
          resultsQuery = q,
          notSetUp = nothing && engine.status.value.filesSearchable == 0,
          hiddenCount = results.hiddenCount,
          showsSensitive = results.hits.any { h -> h.file.sensitive },
          revealedForNow = engine.sensitiveRevealed.value && !engine.settings.value.showSensitive,
        )
      }
    }
  }

  private fun mark(ids: List<Long>, complete: Boolean, q: String) {
    if (typedAtNs == 0L) return
    if (!complete) {
      wordsTopId = ids.firstOrNull()
      pendingMark = "quick words_ms=@draw q=\"${redact(q)}\" hits=${ids.size}"
    } else {
      val oldTop = wordsTopId
      val moved = if (oldTop == null) "n/a" else if (oldTop == ids.firstOrNull()) "same" else "moved_to_rank=${ids.indexOf(oldTop) + 1}"
      pendingMark = "quick meaning_ms=@draw q=\"${redact(q)}\" hits=${ids.size} top=$moved"
    }
  }

  companion object {
    /** How long typing must pause before the meaning step starts (words come at once). Shorter than the main screen's 250 ms: the panel exists to be quick. */
    const val DEBOUNCE_MS = 120L

    /** A panel shows a handful, not a library. */
    const val LIMIT = 30
    private const val PERF = "FindrPerf"

    fun factory(engine: SearchEngine): ViewModelProvider.Factory = viewModelFactory {
      initializer { QuickSearchViewModel(engine) }
    }
  }
}
