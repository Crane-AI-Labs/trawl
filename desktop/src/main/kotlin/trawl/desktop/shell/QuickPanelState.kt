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
enum class QuickKey { UP, DOWN, FIRST, LAST, OPEN, SHOW_IN_FOLDER, CLOSE }

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

  /** Bumped each time the panel is shown, so the "recent files" list is read afresh. */
  private val refresh = MutableStateFlow(0)

  init {
    scope.launch { run() }
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
      }
    }
  }

  fun setQuery(q: String) { _query.value = q }

  /** The panel was shown again: start from an empty query and the recent files. */
  fun reset() {
    _query.value = ""
    _selected.value = 0
    refresh.value += 1
  }

  val current: QuickHit? get() = _hits.value.getOrNull(_selected.value)

  fun select(i: Int) { if (_hits.value.isNotEmpty()) _selected.value = i.coerceIn(0, _hits.value.lastIndex) }

  fun openAt(i: Int) { select(i); open() }

  fun open() {
    if (api.locked.value) return
    val h = current ?: return
    api.open(h.file)
    onClose()
  }

  fun showInFolder() {
    if (api.locked.value) return
    val h = current ?: return
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
      QuickKey.CLOSE -> onClose()
    }
    return true
  }
}
