package trawl.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import trawl.desktop.Os
import trawl.desktop.Platform

/** Where a right-click happened: the result it was on and the point inside that result's row. */
data class MenuRequest(val id: Long, val x: Int, val y: Int)

/**
 * Everything the main window remembers about what the person is doing that is not in the controller: which result is selected, whether the
 * large preview is open, the search field's caret, where focus is. One object so the keyboard handler, the list, the preview and the
 * screenshot driver (which sets these directly) all see the same thing.
 */
class MainUi {
  var tfv by mutableStateOf(TextFieldValue(""))
  var selected by mutableStateOf<Long?>(null)
  var quickLook by mutableStateOf(false)
  /** The index details panel (Package C's), opened from the status bar. */
  var detailOpen by mutableStateOf(false)
  var showWeak by mutableStateOf(false)
  var menu by mutableStateOf<MenuRequest?>(null)
  // ---- SEARCH PACKAGE (A): several results chosen at once (Shift/Cmd/Ctrl-click, Shift+arrows, select all) ----
  /** The results chosen together, in the order they were chosen; empty or one id means nothing is "multi-selected" ([selected] alone is the choice). */
  var picked by mutableStateOf<Set<Long>>(emptySet())
  /** Where a Shift-click or Shift+arrow range starts. */
  var anchor: Long? = null
  /** The Recent list shows every document, not the first few (the "Show N more documents" row). */
  var recentAll by mutableStateOf(false)
  /** The sort menu, the date menu and the date range dialog. */
  var sortMenu by mutableStateOf(false)
  var dateMenu by mutableStateOf(false)
  var dateDialog by mutableStateOf(false)

  /** Every selectable id in reading order, set from the built list; the range and "select all" rules read it. Not state: only read on a click or key. */
  var order: List<Long> = emptyList()

  /** More than one result is chosen. */
  val multi: Boolean get() = picked.size > 1
  /** Drawn as chosen: the picked ones while several are chosen, otherwise the one selected result. */
  fun isPicked(id: Long): Boolean = if (picked.size > 1) id in picked else selected == id
  /** The row with the cursor (the focus ring) while several are chosen. */
  fun isCursor(id: Long): Boolean = selected == id
  fun clearPicked() { if (picked.isNotEmpty()) picked = emptySet(); anchor = selected }

  fun pickState() = trawl.desktop.ui.search.Picking.State(selected, picked, anchor)
  fun setPick(s: trawl.desktop.ui.search.Picking.State) { selected = s.selected; picked = s.picked; anchor = s.anchor }

  /** A press on result [id]: a plain click chooses it alone, Shift takes the range, Cmd/Ctrl adds or removes it. */
  fun press(id: Long, shift: Boolean, toggle: Boolean) {
    setPick(trawl.desktop.ui.search.Picking.click(pickState(), order, id, shift, toggle))
    menu = null
    focusList()
  }

  /** A right-click on [id]: on one of several chosen results it keeps the choice (the menu acts on all of them); anywhere else it chooses that result alone. */
  fun pressSecondary(id: Long) {
    if (picked.size > 1 && id in picked) { selected = id; menu = null; focusList() } else press(id, shift = false, toggle = false)
  }

  var searchFocused by mutableStateOf(false)
  var listFocused by mutableStateOf(false)

  /** Bumped by Cmd/Ctrl K: the search field selects all of its text, so typing replaces the old query. */
  var selectAllSignal by mutableIntStateOf(0)

  /** The query the selection was last reconciled for (not state: only read when results change). */
  var lastQuery: String = ""

  /** How many rows a Page key moves; set from the list's height. */
  var pageRows: Int = 8

  val searchFocus = FocusRequester()
  val listFocus = FocusRequester()

  val zone: FocusZone get() = if (searchFocused) FocusZone.SEARCH else if (listFocused) FocusZone.LIST else FocusZone.OTHER

  /** The search field's value for the controller's [query]: our own (with its caret) while they agree, a fresh one with the caret at the end otherwise. */
  fun valueFor(query: String): TextFieldValue = if (tfv.text == query) tfv else TextFieldValue(query, TextRange(query.length))

  /** The date and sort chips, so a menu or dialog opened from one hands focus back to it (H4). */
  val dateChipFocus = FocusRequester()
  val sortChipFocus = FocusRequester()

  fun focusSearch() { runCatching { searchFocus.requestFocus() } }
  fun focusList() { runCatching { listFocus.requestFocus() } }

  /**
   * Where focus goes when a menu or dialog that a chip opened closes. Someone using the keyboard tabbed to that chip and pressed Enter, so they
   * land back on it and carry on tabbing from there; someone using the mouse goes to the search box, ready to type, as before.
   */
  fun focusAfterMenu(chip: FocusRequester) { if (InputModality.keyboard) runCatching { chip.requestFocus() }.onFailure { focusSearch() } else focusSearch() }

  companion object {
    /** The most recent main window's state, so the screenshot driver can select a result or open the large preview without a keyboard. */
    @Volatile var current: MainUi? = null

    val isMac: Boolean get() = Platform.os == Os.MAC

    /** The command key as it is written on this computer's key caps: the Mac's own symbol on a Mac, "Ctrl" elsewhere (see [Keys]). */
    val modName: String get() = if (isMac) "\u2318" else "Ctrl"
  }
}
