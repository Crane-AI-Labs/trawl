package trawl.desktop.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunIconButton
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.a11yLive
import trawl.desktop.ui.kindIcon

/**
 * Choosing several results (A22): Shift-click for a range, Cmd/Ctrl-click to add or take away one, Shift+arrows to extend, Cmd/Ctrl+A for all.
 * Pure, so every rule is tested without a window. [State.selected] is the cursor (the row that has the focus ring and the preview); [State.picked]
 * is the chosen set (more than one means "several"); [State.anchor] is where a range starts.
 */
object Picking {
  data class State(val selected: Long?, val picked: Set<Long> = emptySet(), val anchor: Long? = null) {
    /** More than one result is chosen. */
    val several: Boolean get() = picked.size > 1
  }

  /** The ids from [from] to [to], both included, in the list's order (either way round). Empty when either is not in the list. */
  fun range(ids: List<Long>, from: Long, to: Long): List<Long> {
    val a = ids.indexOf(from)
    val b = ids.indexOf(to)
    if (a < 0 || b < 0) return emptyList()
    return ids.subList(minOf(a, b), maxOf(a, b) + 1)
  }

  /** A click on [id]: Shift takes the range from the anchor, Cmd/Ctrl toggles that one result, a plain click chooses it alone. Shift wins over Cmd/Ctrl. */
  fun click(s: State, ids: List<Long>, id: Long, shift: Boolean, toggle: Boolean): State = when {
    shift -> {
      val from = s.anchor?.takeIf { it in ids } ?: s.selected?.takeIf { it in ids } ?: id
      State(selected = id, picked = range(ids, from, id).toCollection(LinkedHashSet()), anchor = from)
    }
    toggle -> {
      val base: Set<Long> = if (s.picked.isEmpty() && s.selected != null) linkedSetOf(s.selected) else s.picked
      val next = LinkedHashSet(base).also { if (!it.add(id)) it.remove(id) }
      State(selected = id, picked = next, anchor = id)
    }
    else -> State(selected = id, picked = emptySet(), anchor = id)
  }

  /** Shift+arrow: the cursor moved to [to]; everything from the anchor to it is chosen. */
  fun extend(s: State, ids: List<Long>, to: Long?): State {
    if (to == null) return s
    val from = s.anchor?.takeIf { it in ids } ?: s.selected?.takeIf { it in ids } ?: to
    return State(selected = to, picked = range(ids, from, to).toCollection(LinkedHashSet()), anchor = from)
  }

  /** Cmd/Ctrl+A: every result in the list. The cursor stays where it was. */
  fun all(s: State, ids: List<Long>): State =
    if (ids.isEmpty()) s else State(selected = s.selected?.takeIf { it in ids } ?: ids.first(), picked = ids.toCollection(LinkedHashSet()), anchor = ids.first())

  /** The list changed: only ids still in it stay chosen. */
  fun prune(s: State, ids: List<Long>): State {
    if (s.picked.isEmpty()) return s
    val keep = s.picked.filterTo(LinkedHashSet()) { it in ids }
    return s.copy(picked = keep, anchor = s.anchor?.takeIf { it in ids })
  }

  /** The chosen ids in the list's own order (what Copy puts on the clipboard, first to last as they read). */
  fun inOrder(ids: List<Long>, picked: Set<Long>): List<Long> = ids.filter { it in picked }
}

/** The little box on a chosen result while several are chosen: a ticked square, the same shape as every other checkbox in Trawl. */
@Composable
fun PickMark(on: Boolean, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Box(
    modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(if (on) tk.marker else tk.paper)
      .border(Sun.Border, if (on) tk.onMarker else tk.line, RoundedCornerShape(5.dp)),
    contentAlignment = Alignment.Center,
  ) { if (on) Icon(Icons.Check, contentDescription = null, tint = tk.onMarker, modifier = Modifier.size(16.dp)) }
}

/** What the bar over the list does to the chosen results. */
class PickActions(
  val canShare: Boolean,
  val onCopy: () -> Unit,
  val onShow: () -> Unit,
  val onShare: () -> Unit,
  val onAll: () -> Unit,
  val onClose: () -> Unit,
)

/** The bar over the list while several results are chosen: how many, then Copy, Show in folders, Share (where the computer has it), Select all, and a way out. */
@Composable
fun SelectionBar(count: Int, actions: PickActions, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Row(
    modifier.fillMaxWidth().background(tk.paper2).padding(horizontal = Sun.S16, vertical = Sun.S4)
      .semantics { contentDescription = t("select_bar_label") },
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S4),
  ) {
    Text(tn("select_count", count, formatCount(count)), style = SunType.Label, color = tk.ink, modifier = Modifier.weight(1f).a11yLive())
    SunIconButton(Icons.Copy, tn("action_copy_n", count, formatCount(count)), actions.onCopy)
    SunIconButton(Icons.FolderOpen, t("action_show_folders"), actions.onShow)
    if (actions.canShare) SunIconButton(Icons.Share, tn("action_share_n", count, formatCount(count)), actions.onShare)
    SunTextButton(onClick = actions.onAll) { Text(t("select_all"), style = SunType.Label) }
    SunIconButton(Icons.Close, t("select_exit"), actions.onClose)
  }
}

/** The right-hand pane while several results are chosen: how many, the first few names, and the same actions as large buttons. */
@Composable
fun MultiPane(files: List<IndexedFile>, actions: PickActions, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val n = files.size
  val blur = trawl.desktop.privacy.LocalPreviewBlur.current
  Column(modifier.padding(Sun.S24), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    Text(tn("select_count", n, formatCount(n)), style = SunType.Heading, color = tk.ink, modifier = Modifier.a11yLive())
    Text(t("select_pane_body"), style = SunType.Body, color = tk.ink2)
    Column(verticalArrangement = Arrangement.spacedBy(Sun.S4)) {
      files.take(NAMES_SHOWN).forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          Icon(kindIcon(f.kind), contentDescription = null, tint = tk.ink2, modifier = Modifier.size(Sun.IconSmall))
          Text(if (blur.hides(f)) t("hidden_tile") else f.name, style = SunType.Body, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      if (n > NAMES_SHOWN) Text(t("select_more_names", formatCount(n - NAMES_SHOWN)), style = SunType.Caption, color = tk.ink2)
    }
    Spacer(Modifier.height(Sun.S4))
    SunButton(onClick = actions.onCopy) {
      Icon(Icons.Copy, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
      Spacer(Modifier.width(Sun.S8))
      Text(tn("action_copy_n", n, formatCount(n)), style = SunType.Label)
    }
    SunOutlinedButton(onClick = actions.onShow) {
      Icon(Icons.FolderOpen, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
      Spacer(Modifier.width(Sun.S8))
      Text(t("action_show_folders"), style = SunType.Label)
    }
    if (actions.canShare) SunOutlinedButton(onClick = actions.onShare) {
      Icon(Icons.Share, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
      Spacer(Modifier.width(Sun.S8))
      Text(tn("action_share_n", n, formatCount(n)), style = SunType.Label)
    }
  }
}

private const val NAMES_SHOWN = 8
