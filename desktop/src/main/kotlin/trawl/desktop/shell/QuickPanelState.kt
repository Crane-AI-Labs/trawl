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
  UP, DOWN, FIRST, LAST, OPEN, SHOW_IN_FOLDER,
  /** Esc: closes the system preview if it is up, else the in-panel preview if it is open, else the panel. */
  CLOSE,
  /** Space on an empty box, or Ctrl/Cmd+Y: show or hide the large preview of the selected result. */
  TOGGLE_PREVIEW,
  /** Ctrl/Cmd+Shift+Y: the system's own preview (macOS Quick Look) of the selected result. */
  FULL_PREVIEW,
}

/**
 * The quick panel's whole behaviour: the query, the results, which row is selected, and what each key does. The panel window only draws
 * it and forwards keys. Search is [QuickSearchApi]'s; typing is answered after the same 120 ms pause the main window uses.
 */
class QuickPanelState(
  private val api: QuickSearchApi,
  scope: CoroutineScope,
  private val onClose: () -> Unit,
  private val debounceMs: Long = 120,
) {
  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()

  private val _hits = MutableStateFlow<List<QuickHit>>(emptyList())
  val hits: StateFlow<List<QuickHit>> = _hits.asStateFlow()

  private val _selected = MutableStateFlow(0)
  val selected: StateFlow<Int> = _selected.asStateFlow()

  /** The query the current [hits] answer, or null before the first answer. Lets the panel tell "nothing matches" from "still looking". */
  private val _answered = MutableStateFlow<String?>(null)
  val answered: StateFlow<String?> = _answered.asStateFlow()

  /** The app lock is on: the panel shows only a locked state, and re-asks the moment it is unlocked. */
  val locked: StateFlow<Boolean> get() = api.locked

  /** The large preview of the selected result is open: the list narrows to a column and the preview takes the rest. Follows the selection. */
  private val _previewing = MutableStateFlow(false)
  val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

  /** What the preview draws with (pictures, text, sound) and the system preview, if any. */
  val preview: QuickPreviewApi get() = api.preview

  /** Bumped each time the panel is shown, so the "recent files" list is read afresh. */
  private val refresh = MutableStateFlow(0)

  init {
    scope.launch { run() }
    // Locking closes the preview at once: what it shows must not stay on screen behind the lock.
    scope.launch { api.locked.collect { if (it) { _previewing.value = false; runCatching { api.preview.full.close() } } } }
  }

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  private suspend fun run() {
    // A blank query (the panel just opened) is answered at once; typing waits for a pause.
    combine(_query.debounce { if (it.isBlank()) 0L else debounceMs }, refresh, api.locked) { q, _, _ -> q }.collectLatest { q ->
      // The answer may come in two steps (words, then words and meaning). The row the person is on stays under the cursor by file id
      // while the same question is refined; a new question starts at the top.
      api.search(q).collect { rows ->
        val keep = if (_answered.value == q) current?.file?.id else null
        val at = keep?.let { id -> rows.indexOfFirst { it.file.id == id } } ?: -1
        _hits.value = rows
        _selected.value = if (at >= 0) at else 0
        _answered.value = q
        if (rows.isEmpty()) _previewing.value = false
      }
    }
  }

  fun setQuery(q: String) { _query.value = q }

  /** The panel was shown again: start from an empty query and the recent files. */
  fun reset() {
    _query.value = ""
    _selected.value = 0
    _previewing.value = false
    refresh.value += 1
  }

  /** Opens the large preview of the selected result, or closes it. Nothing opens while locked or with no result. */
  fun togglePreview() {
    if (_previewing.value) { _previewing.value = false; return }
    if (api.locked.value || current == null) return
    _previewing.value = true
  }

  fun closePreview() { _previewing.value = false }

  /**
   * The system's own preview (macOS Quick Look) of the selected result, stepping through the other results with the arrow keys. [hides] says
   * which files the privacy blur is covering: those are never handed to the system preview (it would show them sharp), and when the selected
   * one is hidden nothing opens. Returns whether something was shown.
   */
  fun fullPreview(hides: (com.craneai.findr.core.IndexedFile) -> Boolean = { false }): Boolean {
    if (api.locked.value) return false
    val full = api.preview.full
    val now = current ?: return false
    if (!full.available || hides(now.file)) return false
    val shown = _hits.value.filter { !hides(it.file) }
    val at = shown.indexOfFirst { it.file.id == now.file.id }
    return full.show(shown.map { api.preview.fileOf(it.file) }, at.coerceAtLeast(0))
  }

  /** The panel is going away: whatever preview is up goes with it. */
  fun dismissPreviews() {
    _previewing.value = false
    runCatching { api.preview.full.close() }
  }

  val current: QuickHit? get() = _hits.value.getOrNull(_selected.value)

  fun select(i: Int) { if (_hits.value.isNotEmpty()) _selected.value = i.coerceIn(0, _hits.value.lastIndex) }

  fun openAt(i: Int) { select(i); open() }

  fun open() {
    if (api.locked.value) return
    val h = current ?: return
    _previewing.value = false
    api.open(h.file)
    onClose()
  }

  fun showInFolder() {
    if (api.locked.value) return
    val h = current ?: return
    _previewing.value = false
    api.showInFolder(h.file)
    onClose()
  }

  /** Returns true when the key was used (so the text field must not also act on it). */
  fun onKey(k: QuickKey): Boolean {
    val n = _hits.value.size
    when (k) {
      QuickKey.DOWN -> if (n > 0) _selected.value = (_selected.value + 1).coerceAtMost(n - 1)
      QuickKey.UP -> if (n > 0) _selected.value = (_selected.value - 1).coerceAtLeast(0)
      QuickKey.FIRST -> if (n > 0) _selected.value = 0
      QuickKey.LAST -> if (n > 0) _selected.value = n - 1
      QuickKey.OPEN -> open()
      QuickKey.SHOW_IN_FOLDER -> showInFolder()
      QuickKey.CLOSE -> when {
        api.preview.full.open.value -> api.preview.full.close()
        _previewing.value -> _previewing.value = false
        else -> onClose()
      }
      QuickKey.TOGGLE_PREVIEW -> togglePreview()
      QuickKey.FULL_PREVIEW -> fullPreview()
    }
    return true
  }
}
