package trawl.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import kotlinx.coroutines.launch
import trawl.desktop.AppController
import trawl.desktop.t
import trawl.desktop.ui.search.MoreRow
import trawl.desktop.ui.search.PeekHeading

/**
 * The results, one lazy column of compact rows and photo-grid rows. The column is a single keyboard stop (Tab reaches it once; the arrow keys
 * move a selection inside it), so a long list never traps Tab. The selection scrolls into view as it moves.
 */
@Composable
fun ResultsList(c: AppController, built: BuiltResults, ui: MainUi, state: LazyListState, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  LaunchedEffect(ui.selected, built) {
    val id = ui.selected ?: return@LaunchedEffect
    val index = built.entryIndexOf(id)
    if (index < 0) return@LaunchedEffect
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val top = info.viewportStartOffset
    val bottom = info.viewportEndOffset
    when {
      item == null -> state.scrollToItem(index, scrollOffset = -((bottom - top) / 3))
      item.offset < top + 4 -> state.scrollBy((item.offset - top - 8).toFloat())
      item.offset + item.size > bottom - 4 -> state.scrollBy((item.offset + item.size - bottom + 8).toFloat())
    }
  }
  Box(modifier) {
    Box(
      Modifier.fillMaxSize().focusRequester(ui.listFocus).onFocusChanged { ui.listFocused = it.isFocused }.focusable()
        .semantics { contentDescription = t("results_list_label") },
    ) {
      LazyColumn(state = state, contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp, end = 12.dp), modifier = Modifier.fillMaxSize()) {
        items(built.entries, key = { it.key }) { entry ->
          when (entry) {
            is Entry.Header -> GroupHeader(entry.group, entry.count)
            is Entry.Doc -> DocRow(entry.row, c, ui, ui.isPicked(entry.row.file.id), ui.listFocused)
            is Entry.Tiles -> TileRow(entry.rows, built.cols, c, ui, ui.listFocused)
            is Entry.Weak -> WeakToggle(entry.count, entry.expanded) { ui.showWeak = !ui.showWeak }
            // ---- SEARCH PACKAGE (A) ----
            is Entry.Peek -> PeekHeading(entry.count, entry.more) {
              val at = built.entries.indexOfFirst { it is Entry.Header && it.group == Group.PHOTOS }
              if (at >= 0) scope.launch { state.animateScrollToItem(at) }
            }
            is Entry.More -> MoreRow(entry.group, entry.hidden) { ui.recentAll = true }
          }
        }
      }
    }
    VerticalScrollbar(
      rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
      style = defaultScrollbarStyle().copy(unhoverColor = tk.line2.copy(alpha = 0.45f), hoverColor = tk.ink2),
    )
  }
}
