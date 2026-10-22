package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.ResultRow
import trawl.desktop.privacy.LocalPreviewBlur
import trawl.desktop.ui.privacy.rememberLeave
import trawl.desktop.t
import trawl.desktop.ui.search.Age
import trawl.desktop.ui.search.LocalRowContext
import trawl.desktop.ui.search.MoreButton
import trawl.desktop.ui.search.PickMark
import trawl.desktop.ui.search.RowText
import trawl.desktop.ui.search.Spot
import trawl.desktop.ui.search.spot

/** The look of a result under the pointer, selected, or selected with the list holding keyboard focus (a 3 dp ring, ink in light and marker in dark). */
private fun Modifier.resultSurface(
  selected: Boolean, listFocused: Boolean, shape: androidx.compose.ui.graphics.Shape, source: MutableInteractionSource, tile: Boolean = false,
): Modifier = composed {
  val tk = LocalSunlight.current
  val hovered by source.collectIsHoveredAsState()
  val fill = when {
    selected && !tile -> tk.paper2
    hovered && !tile -> tk.paper2
    else -> androidx.compose.ui.graphics.Color.Transparent
  }
  val edge = when {
    selected && listFocused -> Modifier.border(3.dp, tk.focus, shape)
    selected -> Modifier.border(Sun.Border, tk.line, shape)
    hovered && tile -> Modifier.border(Sun.Border, tk.line2, shape)
    else -> Modifier
  }
  Modifier.hoverable(source).background(fill, shape).then(edge)
}

/**
 * What a mouse does on a result: a click selects, a second click soon after opens, a right-click asks for the menu at that point. Handled on the
 * press itself (not after a double-click timeout), so selecting feels instant; nothing is consumed, so the list still scrolls and drags.
 */
@Composable
private fun Modifier.resultPointer(id: Long, onSelect: (shift: Boolean, toggle: Boolean) -> Unit, onOpen: () -> Unit, onMenu: (Offset) -> Unit): Modifier {
  val select by rememberUpdatedState(onSelect)
  val open by rememberUpdatedState(onOpen)
  val menu by rememberUpdatedState(onMenu)
  return pointerInput(id) {
    awaitPointerEventScope {
      var last = 0L
      while (true) {
        val ev = awaitPointerEvent()
        if (ev.type != PointerEventType.Press) continue
        val change = ev.changes.first()
        when {
          ev.buttons.isSecondaryPressed -> { menu(change.position) }
          ev.buttons.isPrimaryPressed -> {
            // ---- SEARCH PACKAGE (A): Shift-click chooses a range, Cmd/Ctrl-click adds or takes away one; neither opens on a quick second click ----
            val shift = ev.keyboardModifiers.isShiftPressed
            val toggle = if (MainUi.isMac) ev.keyboardModifiers.isMetaPressed else ev.keyboardModifiers.isCtrlPressed
            val now = change.uptimeMillis
            if (shift || toggle) { last = 0L; select(shift, toggle) }
            else if (last != 0L && now - last < 450) { last = 0L; open() } else { last = now; select(false, false) }
          }
        }
      }
    }
  }
}

private fun Modifier.resultSemantics(isSelected: Boolean, openLabel: String, onOpen: () -> Unit): Modifier =
  semantics(mergeDescendants = true) { selected = isSelected; onClick(label = openLabel) { onOpen(); true } }

/**
 * One document, PDF or other non-picture result: a 40 dp icon tile, the name and badges, where it lives, and one line of what matched.
 *
 * A hidden row (a blurred picture) shows no excerpt and says "hidden" in place of its name, which is also what a screen reader reads.
 * Sensitive files carry a badge ([RowBadges]); the right-click menu (ResultMenu.kt) marks and unmarks them.
 */
@Composable
fun DocRow(row: ResultRow, c: AppController, ui: MainUi, selected: Boolean, listFocused: Boolean) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(10.dp)
  val f = row.file
  val leave = rememberLeave()
  val folder = remember(f.id) { PathText.tilde(c.fileOf(f).parent.orEmpty()) }
  // A hidden row (a blurred picture) shows no excerpt and says "hidden" in place of its name.
  val hidden = LocalPreviewBlur.current.hides(f)
  val snippet = remember(row, hidden) { if (hidden) null else cleanSnippet(row) }
  val name = if (hidden) t("hidden_label") else f.name
  // ---- SEARCH PACKAGE (A): age and folder (size only when sorting by Largest), several-chosen marks, the "..." button, stacking for large text ----
  val rc = LocalRowContext.current
  val sub = remember(f.dateMillis, f.sizeBytes, folder, rc) { RowText.subtitle(Age.label(f.dateMillis, rc.nowMs), folder, formatBytes(f.sizeBytes), rc.sort) }
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val ring = listFocused && (!ui.multi || ui.isCursor(f.id))
  val box = remember { Spot() }
  val spoken = remember(row, name, sub, snippet, hidden) { ResultSpeech.doc(row, name, sub, snippet, hidden) }
  Box(Modifier.fillMaxWidth().padding(horizontal = Sun.S8, vertical = 1.dp).spot(box)) {
    Row(
      Modifier.fillMaxWidth().clip(shape).pointerHoverIcon(PointerIcon.Default).resultSurface(selected, ring, shape, source)
        .resultPointer(f.id, { shift, toggle -> ui.press(f.id, shift, toggle) }, { leave(f.id) { c.open(f) } }, { ui.pressSecondary(f.id); ui.menu = MenuRequest(f.id, it.x.toInt(), it.y.toInt()) })
        .resultSemantics(selected, t("action_open")) { leave(f.id) { c.open(f) } }
        .padding(start = Sun.S12, end = Sun.S4, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12),
    ) {
      // The picture of the row (mark, icon, name, badges, match) is read as one sentence (ResultSpeech); the "..." button stays its own control.
      Row(
        Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12),
      ) {
        if (ui.multi) PickMark(selected)
        if (f.kind == com.craneai.findr.core.FileKind.VIDEO) VideoFrameTile(row, c.fileOf(f), c.frames, hidden) else KindTile(f.kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          if (rc.stack) {
            Text(name, style = SunType.Label, color = tk.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RowBadges(row)
          } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
              Text(name, style = SunType.Label, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
              RowBadges(row)
            }
          }
          Text(sub, style = SunType.Caption, color = tk.ink2, maxLines = if (rc.stack) 2 else 1, overflow = TextOverflow.Ellipsis)
          if (snippet != null) SnippetText(snippet, lines = if (rc.stack) 2 else 1, modifier = Modifier.padding(top = 2.dp))
        }
      }
      if (selected || hovered) MoreButton(ui, f.id, box) else Spacer(Modifier.size(Sun.Target))
    }
    ResultMenu(ui, c, row)
  }
}

/** One photo tile: a square thumbnail with its name below. */
@Composable
fun PhotoTile(row: ResultRow, c: AppController, ui: MainUi, selected: Boolean, listFocused: Boolean, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(10.dp)
  val f = row.file
  val leave = rememberLeave()
  val hidden = LocalPreviewBlur.current.hides(f)
  // ---- SEARCH PACKAGE (A): several-chosen marks and the "..." button on the picture ----
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val ring = listFocused && (!ui.multi || ui.isCursor(f.id))
  val box = remember { Spot() }
  val spoken = ResultSpeech.tile(row, if (hidden) t("hidden_tile") else f.name, hidden)
  Box(modifier.spot(box)) {
    Column(
      Modifier.fillMaxWidth().clip(shape).resultSurface(selected, ring, shape, source, tile = true)
        .resultPointer(f.id, { shift, toggle -> ui.press(f.id, shift, toggle) }, { leave(f.id) { c.open(f) } }, { ui.pressSecondary(f.id); ui.menu = MenuRequest(f.id, it.x.toInt(), it.y.toInt()) })
        .resultSemantics(selected, t("action_open")) { leave(f.id) { c.open(f) } }
        .padding(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Box {
        Thumb(c.fileOf(f), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(7.dp)).border(Sun.Hairline, tk.line2, RoundedCornerShape(7.dp)), side = 360, row = f)
        if (ui.multi) PickMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        if (selected || hovered) {
          Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(8.dp)).background(tk.paper.copy(alpha = 0.92f))) { MoreButton(ui, f.id, box) }
        }
      }
      Row(
        Modifier.padding(horizontal = 2.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(if (hidden) t("hidden_tile") else f.name, style = SunType.Caption, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (f.sensitive) Badge(t("sensitive_badge"))
      }
    }
    ResultMenu(ui, c, row)
  }
}

/** A row of the photo grid: [cols] equal columns, the last row padded so tiles keep one size. */
@Composable
fun TileRow(rows: List<ResultRow>, cols: Int, c: AppController, ui: MainUi, listFocused: Boolean) {
  Row(Modifier.fillMaxWidth().padding(horizontal = Sun.S8 - 4.dp), horizontalArrangement = Arrangement.spacedBy(Layout.TILE_GAP.dp - 4.dp)) {
    rows.forEach { PhotoTile(it, c, ui, ui.isPicked(it.file.id), listFocused, Modifier.weight(1f)) }
    repeat((cols - rows.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
  }
}

/** The words over a group: Documents and PDFs only when that is all the group holds. */
fun groupTitle(group: Group, mix: Mix): String = when {
  group == Group.PHOTOS -> t("kind_plural_photos")
  mix == Mix.MEDIA -> t("group_media")
  mix == Mix.BOTH -> t("group_documents_media")
  else -> t("kind_plural_documents")
}

/** A group label above documents or photos: quiet small caps-style text and a count. */
@Composable
fun GroupHeader(group: Group, count: Int, mix: Mix = Mix.DOCUMENTS) {
  val tk = LocalSunlight.current
  Row(Modifier.fillMaxWidth().padding(start = Sun.S16, end = Sun.S16, top = Sun.S12, bottom = Sun.S4), verticalAlignment = Alignment.CenterVertically) {
    Text(
      groupTitle(group, mix),
      style = SunType.CaptionBold, color = tk.ink2, modifier = Modifier.weight(1f),
    )
    Text(count.toString(), style = SunType.Caption, color = tk.ink2)
  }
}

/** The "Show weaker matches (12)" row and its collapse. */
@Composable
fun WeakToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(8.dp)
  Row(
    Modifier.padding(horizontal = Sun.S8, vertical = Sun.S8).sunFocus(shape).sunHover(shape).clip(shape).pointerHoverIcon(PointerIcon.Hand)
      .clickable(role = Role.Button, onClick = onToggle).heightIn(min = Sun.Target).padding(horizontal = Sun.S12),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(if (expanded) Icons.ExpandLess else Icons.ExpandMore, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S8))
    Text(
      t("weak_toggle_count", t(if (expanded) "search_weak_collapse" else "search_weak_expand"), count),
      style = SunType.Label, color = tk.ink,
    )
  }
}
