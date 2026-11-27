package trawl.desktop.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** The keys the quick panel understands, already separated from any UI toolkit so the rules can be tested without a window. */
enum class QuickKey {
  UP, DOWN, FIRST, LAST, OPEN,
  /** Shift+Enter: jump straight to the highlighted/top file even when the query has not been arrowed down. */
  OPEN_TOP,
  SHOW_IN_FOLDER,
  /** Esc: closes the system preview if it is up, else the in-panel preview if it is open, else the panel. */
  CLOSE,
  /** Space on an empty box or highlighted row, or Ctrl/Cmd+Y: show or hide the large preview of the selected result. */
  TOGGLE_PREVIEW,
  /** Ctrl/Cmd+Shift+Y: the system's own preview (macOS Quick Look) of the selected result. */
  FULL_PREVIEW,
  /** Ctrl/Cmd+C on a highlighted row (or Recent): copy the selected file to the clipboard. */
  COPY,
}

/**
 * The quick panel's whole behaviour: the query, the results, which row is selected, and what each key does. The panel window only draws
 * it and forwards keys. Search is [QuickSearchApi]'s; Stage-1 word matches answer after a short 40 ms pause (0 ms when blank), while
 * Stage-2 meaning embeds are held back by [ControllerQuickSearch] so rapid typing stays snappy and cool.
 */
class QuickPanelState(
  private val api: QuickSearchApi,
  scope: CoroutineScope,
  private val onClose: () -> Unit,
  private val debounceMs: Long = 40,
  /**
   * Optional Spotlight-style hand-off to the full Trawl search window: when the person types a query and presses Enter without explicitly
   * selecting a row, this opens the main window with that query (and never opens a stale recent file mid-debounce).
   */
  private val onOpenFullSearch: ((String) -> Unit)? = null,
) {
  /** Guards coupled transitions across query, hits, selected, answered, userSelectedRow, and previewing. */
  private val stateLock = Any()

  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()

  private val _hits = MutableStateFlow<List<QuickHit>>(emptyList())
  val hits: StateFlow<List<QuickHit>> = _hits.asStateFlow()

  private val _selected = MutableStateFlow(0)
  val selected: StateFlow<Int> = _selected.asStateFlow()

  /** True once the person explicitly moved the selection (arrows/Home/End/click) for the current query; reset on query change and reset. */
  private val _userSelectedRow = MutableStateFlow(false)
  val userSelectedRow: StateFlow<Boolean> = _userSelectedRow.asStateFlow()

  /** True when the panel supports Spotlight-style Enter hand-off to the full Trawl window (rows stay unhighlighted until navigated). */
  val hasFullSearchHandoff: Boolean get() = onOpenFullSearch != null

  /** The query the current [hits] answer, or null before the first answer. Lets the panel tell "nothing matches" from "still looking". */
  private val _answered = MutableStateFlow<String?>(null)
  val answered: StateFlow<String?> = _answered.asStateFlow()

  /** The app lock is on: the panel shows only a locked state, and re-asks the moment it is unlocked. */
  val locked: StateFlow<Boolean> get() = api.locked

  /** The large preview of the selected result is open: the list narrows to a column and the preview takes the rest. Follows the selection. */
  private val _previewing = MutableStateFlow(false)
  val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

  /** Monotonic tick bumped when a file is copied from Quick Search so the status line can flash a brief confirmation. */
  private val _copiedTick = MutableStateFlow(0)
  val copiedTick: StateFlow<Int> = _copiedTick.asStateFlow()

  /** What the preview draws with (pictures, text, sound) and the system preview, if any. */
  val preview: QuickPreviewApi get() = api.preview

  /** Bumped each time the panel is shown, so the "recent files" list is read afresh. */
  private val refresh = MutableStateFlow(0)

  init {
    scope.launch { run() }
    // Locking closes the preview at once: what it shows must not stay on screen behind the lock.
    scope.launch {
      api.locked.collect {
        if (it) {
          synchronized(stateLock) { _previewing.value = false }
          stopPlayback()
          runCatching { api.preview.full.close() }
        }
      }
    }
  }

  private fun stopPlayback() {
    runCatching { api.preview.player?.stop() }
  }

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  private suspend fun run() {
    // A blank query (the panel just opened) is answered at once; typing waits for a pause.
    combine(_query.debounce { if (it.isBlank()) 0L else debounceMs }, refresh, api.locked) { q, _, _ -> q }.collectLatest { q ->
      // The answer may come in two steps (words, then words and meaning). The row the person is on stays under the cursor by file id
      // while the same question is refined; a new question starts at the top.
      api.search(q).collect { rows ->
        synchronized(stateLock) {
          if (_query.value != q) return@synchronized
          val prevId = currentLocked()?.file?.id
          val keep = if (_answered.value == q) prevId else null
          val at = keep?.let { id -> rows.indexOfFirst { it.file.id == id } } ?: -1
          _hits.value = rows
          _selected.value = if (at >= 0) at else 0
          _answered.value = q
          if (rows.isEmpty()) _previewing.value = false
          val nextId = currentLocked()?.file?.id
          if (rows.isEmpty() || nextId != prevId) stopPlayback()
        }
      }
    }
  }

  fun setQuery(q: String) {
    val changed = synchronized(stateLock) {
      val diff = _query.value != q
      _userSelectedRow.value = false
      _query.value = q
      diff
    }
    if (changed) stopPlayback()
  }

  /** The panel was shown again: start from an empty query and the recent files. */
  fun reset() {
    stopPlayback()
    synchronized(stateLock) {
      _answered.value = null
      _hits.value = emptyList()
      _selected.value = 0
      _userSelectedRow.value = false
      _query.value = ""
      _previewing.value = false
    }
    refresh.value += 1
  }

  /** Opens the large preview of the selected result, or closes it. Nothing opens while locked or with no result. */
  fun togglePreview() {
    val closed = synchronized(stateLock) {
      if (_previewing.value) { _previewing.value = false; return@synchronized true }
      if (api.locked.value || currentLocked() == null) return@synchronized false
      _userSelectedRow.value = true
      _previewing.value = true
      false
    }
    if (closed) stopPlayback()
  }

  fun closePreview() {
    synchronized(stateLock) { _previewing.value = false }
    stopPlayback()
  }

  /**
   * The system's own preview (macOS Quick Look) of the selected result, stepping through the other results with the arrow keys. [hides] says
   * which files the privacy blur is covering: those are never handed to the system preview (it would show them sharp), and when the selected
   * one is hidden nothing opens. Returns whether something was shown.
   */
  fun fullPreview(hides: (com.craneai.findr.core.IndexedFile) -> Boolean = { false }): Boolean {
    if (api.locked.value) return false
    val full = api.preview.full
    if (!full.available) return false
    val snapshot = synchronized(stateLock) {
      val now = currentLocked() ?: return false
      now to _hits.value
    }
    val (now, allHits) = snapshot
    if (hides(now.file)) return false
    stopPlayback()
    val shown = allHits.filter { !hides(it.file) }
    val at = shown.indexOfFirst { it.file.id == now.file.id }
    return full.show(shown.map { api.preview.fileOf(it.file) }, at.coerceAtLeast(0))
  }

  /** The panel is going away: whatever preview or media playback is active stops immediately. */
  fun dismissPreviews() {
    synchronized(stateLock) { _previewing.value = false }
    stopPlayback()
    runCatching { api.preview.full.close() }
  }

  private fun currentLocked(): QuickHit? = _hits.value.getOrNull(_selected.value)

  val current: QuickHit? get() = synchronized(stateLock) { currentLocked() }

  /** True when the rows currently on screen answer the text currently in the box (prevents opening a stale row mid-debounce). */
  private fun hitsMatchQueryLocked(): Boolean {
    val q = _query.value.trim()
    val ans = _answered.value?.trim() ?: return false
    return ans == q
  }

  fun select(i: Int) {
    val moved = synchronized(stateLock) {
      if (_hits.value.isNotEmpty()) {
        val next = i.coerceIn(0, _hits.value.lastIndex)
        val diff = next != _selected.value
        _selected.value = next
        _userSelectedRow.value = true
        diff
      } else false
    }
    if (moved) stopPlayback()
  }

  fun openAt(i: Int) {
    if (api.locked.value) return
    val h = synchronized(stateLock) {
      if (_hits.value.isNotEmpty()) {
        _selected.value = i.coerceIn(0, _hits.value.lastIndex)
        _userSelectedRow.value = true
      }
      if (!hitsMatchQueryLocked()) return
      val hit = currentLocked() ?: return
      _previewing.value = false
      hit
    }
    stopPlayback()
    api.open(h.file)
    onClose()
  }

  /**
   * Enter: when a query is typed and the person has not arrowed to a specific row, hand off to the full Trawl window with that query
   * (if [onOpenFullSearch] is wired) so every result is shown and a fast Enter never opens a stale recent file during debounce.
   * When a row was explicitly chosen, or on the blank Recent list, open the selected file (still guarding against debounce races).
   */
  fun open() {
    if (api.locked.value) return
    var fullQuery: String? = null
    var hit: QuickHit? = null
    synchronized(stateLock) {
      val q = _query.value.trim()
      if (q.isNotEmpty() && !_userSelectedRow.value && onOpenFullSearch != null) {
        _previewing.value = false
        fullQuery = q
      } else if (hitsMatchQueryLocked()) {
        val h = currentLocked()
        if (h != null) {
          _previewing.value = false
          hit = h
        }
      }
    }
    fullQuery?.let { q ->
      stopPlayback()
      runCatching { api.preview.full.close() }
      onOpenFullSearch?.invoke(q)
      return
    }
    hit?.let { h ->
      stopPlayback()
      api.open(h.file)
      onClose()
    }
  }

  /** Shift+Enter (or row click): open the highlighted/top file directly, still guarding against opening stale rows mid-debounce. */
  fun openTop() = openSelected()

  private fun openSelected() {
    if (api.locked.value) return
    val h = synchronized(stateLock) {
      if (!hitsMatchQueryLocked()) return
      val hit = currentLocked() ?: return
      _previewing.value = false
      hit
    }
    stopPlayback()
    api.open(h.file)
    onClose()
  }

  fun showInFolder() {
    if (api.locked.value) return
    val h = synchronized(stateLock) {
      if (!hitsMatchQueryLocked()) return
      val hit = currentLocked() ?: return
      _previewing.value = false
      hit
    }
    stopPlayback()
    api.showInFolder(h.file)
    onClose()
  }

  /** Copies the currently selected file to the system clipboard without closing Quick Search. */
  fun copy() {
    if (api.locked.value) return
    val h = synchronized(stateLock) {
      if (!hitsMatchQueryLocked()) return
      currentLocked() ?: return
    }
    api.copy(h.file)
    _copiedTick.value += 1
  }

  /** Returns true when the key was used (so the text field must not also act on it). */
  fun onKey(k: QuickKey): Boolean {
    when (k) {
      QuickKey.DOWN -> {
        val moved = synchronized(stateLock) {
          val n = _hits.value.size
          if (n > 0) {
            val before = _selected.value
            if (onOpenFullSearch != null && !_userSelectedRow.value) {
              _selected.value = 0
              _userSelectedRow.value = true
            } else {
              _selected.value = (_selected.value + 1).coerceAtMost(n - 1)
              _userSelectedRow.value = true
            }
            _selected.value != before
          } else false
        }
        if (moved) stopPlayback()
      }
      QuickKey.UP -> {
        val moved = synchronized(stateLock) {
          val n = _hits.value.size
          if (n > 0) {
            val before = _selected.value
            if (onOpenFullSearch != null && _userSelectedRow.value && _selected.value == 0 && !_previewing.value) {
              _userSelectedRow.value = false
            } else {
              _selected.value = (_selected.value - 1).coerceAtLeast(0)
              _userSelectedRow.value = true
            }
            _selected.value != before
          } else false
        }
        if (moved) stopPlayback()
      }
      QuickKey.FIRST -> {
        val moved = synchronized(stateLock) {
          if (_hits.value.isNotEmpty()) { val diff = _selected.value != 0; _selected.value = 0; _userSelectedRow.value = true; diff } else false
        }
        if (moved) stopPlayback()
      }
      QuickKey.LAST -> {
        val moved = synchronized(stateLock) {
          val n = _hits.value.size
          if (n > 0) { val diff = _selected.value != n - 1; _selected.value = n - 1; _userSelectedRow.value = true; diff } else false
        }
        if (moved) stopPlayback()
      }
      QuickKey.OPEN -> open()
      QuickKey.OPEN_TOP -> openTop()
      QuickKey.SHOW_IN_FOLDER -> showInFolder()
      QuickKey.CLOSE -> {
        stopPlayback()
        when {
          api.preview.full.open.value -> api.preview.full.close()
          synchronized(stateLock) { if (_previewing.value) { _previewing.value = false; true } else false } -> {}
          else -> onClose()
        }
      }
      QuickKey.TOGGLE_PREVIEW -> togglePreview()
      QuickKey.FULL_PREVIEW -> fullPreview()
      QuickKey.COPY -> copy()
    }
    return true
  }
}
