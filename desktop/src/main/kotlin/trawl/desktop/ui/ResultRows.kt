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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.ResultRow
import trawl.desktop.privacy.LocalPreviewBlur
import trawl.desktop.ui.privacy.rememberLeave
import trawl.desktop.t
import trawl.desktop.ui.search.Age
import trawl.desktop.ui.search.ListColumn
import trawl.desktop.ui.search.ListColumnSort
import trawl.desktop.ui.search.ListColumnVisibility
import trawl.desktop.ui.search.LocalRowContext
import trawl.desktop.ui.search.MoreButton
import trawl.desktop.ui.search.PickMark
import trawl.desktop.ui.search.RowText
import trawl.desktop.ui.search.Spot
import trawl.desktop.ui.search.spot

private val ROW_SHAPE = RoundedCornerShape(10.dp)
// Mathematically concentric radii for photo tiles: 12 dp outer card - 5 dp inner padding = 7 dp inner thumbnail.
private val TILE_CARD_SHAPE = RoundedCornerShape(12.dp)
private val THUMB_SHAPE = RoundedCornerShape(7.dp)
private val LIST_THUMB_SHAPE = RoundedCornerShape(6.dp)
private val HEADER_CELL_SHAPE = RoundedCornerShape(6.dp)
private val MORE_SHAPE = RoundedCornerShape(8.dp)
private val WEAK_SHAPE = RoundedCornerShape(8.dp)
private val LIST_THUMB_SIZE = 32.dp
private val COL_SIZE_W = 68.dp
private val COL_MODIFIED_W = 104.dp
private val COL_CREATED_W = 104.dp
private val COL_GAP = Sun.S8

/** The look of a result under the pointer, selected, or selected with the list holding keyboard focus (a 3 dp ring, ink in light and marker in dark). */
private fun Modifier.resultSurface(
  selected: Boolean, listFocused: Boolean, shape: androidx.compose.ui.graphics.Shape, source: MutableInteractionSource, tile: Boolean = false,
): Modifier = composed {
  val tk = LocalSunlight.current
  val hovered by source.collectIsHoveredAsState()
  // Give selected/hovered photo tiles a subtle paper2 backing too so the focus ring frames a crisp card instead of floating over bare canvas.
  val fill = when {
    selected -> tk.paper2
    hovered -> tk.paper2.copy(alpha = if (tile) 0.72f else 1f)
    else -> androidx.compose.ui.graphics.Color.Transparent
  }
  val edge = when {
    selected && listFocused -> Modifier.border(2.5.dp, tk.focus, shape)
    selected -> Modifier.border(Sun.Border, tk.line, shape)
    hovered && tile -> Modifier.border(Sun.Hairline, tk.line2, shape)
    else -> Modifier
  }
  // Draw background & border cleanly on the shape (never clip BEFORE border, which slices Skia anti-aliased strokes on Retina displays).
  Modifier.hoverable(source).background(fill, shape).then(edge).clip(shape)
}

/**
 * What a mouse does on a result: a click selects, a second click soon after opens, a right-click asks for the menu at that point. Handled on the
 * press itself (not after a double-click timeout), so selecting feels instant; nothing is consumed, so the list still scrolls and drags.
 */
@Composable
private fun Modifier.resultPointer(
  id: Long,
  preserveMultiOnPress: Boolean,
  onSelect: (shift: Boolean, toggle: Boolean) -> Unit,
  onOpen: () -> Unit,
  onMenu: (Offset) -> Unit,
): Modifier {
  val keepMulti by rememberUpdatedState(preserveMultiOnPress)
  val select by rememberUpdatedState(onSelect)
  val open by rememberUpdatedState(onOpen)
  val menu by rememberUpdatedState(onMenu)
  return pointerInput(id) {
    awaitPointerEventScope {
      var last = 0L
      var deferredCollapseAt: Offset? = null
      while (true) {
        val ev = awaitPointerEvent()
        val change = ev.changes.firstOrNull() ?: continue
        when (ev.type) {
          PointerEventType.Press -> when {
            ev.buttons.isSecondaryPressed -> { deferredCollapseAt = null; menu(change.position) }
            ev.buttons.isPrimaryPressed -> {
              // ---- SEARCH PACKAGE (A): Shift-click chooses a range, Cmd/Ctrl-click adds or takes away one; neither opens on a quick second click ----
              val shift = ev.keyboardModifiers.isShiftPressed
              val toggle = if (MainUi.isMac) ev.keyboardModifiers.isMetaPressed else ev.keyboardModifiers.isCtrlPressed
              val now = change.uptimeMillis
              if (shift || toggle) { last = 0L; deferredCollapseAt = null; select(shift, toggle) }
              else if (last != 0L && now - last < 450) { last = 0L; deferredCollapseAt = null; open() }
              else if (keepMulti) {
                // Plain-pressing an item already in a multi-selection keeps all picked items intact so the person can drag them together;
                // a plain click without dragging collapses to this row on Release (matching Finder).
                last = now
                deferredCollapseAt = change.position
              } else {
                last = now; deferredCollapseAt = null; select(false, false)
              }
            }
          }
          PointerEventType.Release -> {
            val downPos = deferredCollapseAt
            deferredCollapseAt = null
            if (downPos != null && (change.position - downPos).getDistance() < 6f) {
              select(false, false)
            }
          }
          else -> {}
        }
      }
    }
  }
}

private fun Modifier.resultSemantics(isSelected: Boolean, openLabel: String, onOpen: () -> Unit): Modifier =
  semantics(mergeDescendants = true) { selected = isSelected; onClick(label = openLabel) { onOpen(); true } }

/**
 * Clickable column header bar shown above [ResultsList] in List view (`Name`, `Folder`, `Size`, `Date modified`, `Date created`).
 * Clicking a sortable column toggles its sort order cleanly and mirrors the exact column widths used by [DocRow].
 */
@Composable
fun ListColumnHeader(sort: SortOrder, widthDp: Int, multi: Boolean, onSort: (SortOrder) -> Unit) {
  val tk = LocalSunlight.current
  val vis = remember(widthDp, sort) { ListColumnVisibility.forWidth(widthDp, sort) }
  Column(Modifier.fillMaxWidth().background(tk.paper)) {
    Row(
      Modifier.fillMaxWidth()
        .padding(start = Sun.S8 + Sun.S12, end = 12.dp + Sun.S8 + Sun.S4 + Sun.S12 + Sun.Target, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(COL_GAP),
    ) {
      if (multi) Spacer(Modifier.width(22.dp))
      ListHeaderCell(t("col_name"), ListColumn.NAME, sort, onSort, Modifier.weight(1.9f))
      if (vis.showFolder) ListHeaderCell(t("col_folder"), ListColumn.FOLDER, sort, onSort, Modifier.weight(1.1f))
      ListHeaderCell(t("col_size"), ListColumn.SIZE, sort, onSort, Modifier.width(COL_SIZE_W), alignEnd = true)
      if (vis.showModified) ListHeaderCell(t("col_modified"), ListColumn.MODIFIED, sort, onSort, Modifier.width(COL_MODIFIED_W), alignEnd = true)
      if (vis.showCreated) ListHeaderCell(t("col_created"), ListColumn.CREATED, sort, onSort, Modifier.width(COL_CREATED_W), alignEnd = true)
    }
    SunDivider()
  }
}

@Composable
private fun ListHeaderCell(
  label: String,
  column: ListColumn,
  sort: SortOrder,
  onSort: (SortOrder) -> Unit,
  modifier: Modifier,
  alignEnd: Boolean = false,
) {
  val tk = LocalSunlight.current
  val next = ListColumnSort.nextSort(column, sort)
  val arrow = ListColumnSort.indicator(column, sort)
  val text = if (arrow != null) "$label $arrow" else label
  val clickMod = if (next != null) {
    Modifier.sunFocus(HEADER_CELL_SHAPE).sunHover(HEADER_CELL_SHAPE).clip(HEADER_CELL_SHAPE)
      .pointerHoverIcon(PointerIcon.Hand)
      .clickable(role = Role.Button) { onSort(next) }
      .semantics { contentDescription = "${t("sort_group")}: $label" }
  } else {
    Modifier
  }
  Row(
    modifier.then(clickMod).heightIn(min = 28.dp).padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
  ) {
    Text(
      text,
      style = SunType.CaptionBold,
      color = if (arrow != null) tk.ink else tk.ink2,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
    )
  }
}

/**
 * One document, PDF or other non-picture result (or any file, including photos, when in List view): in Grid view a 40 dp icon tile,
 * name, badges, age/folder and snippet; in List view a compact aligned table row with thumbnail, file type badge, folder, exact size,
 * and exact Date Modified / Date Created columns.
 *
 * A hidden row (a blurred picture) shows no excerpt and says "hidden" in place of its name, which is also what a screen reader reads.
 * Sensitive files carry a badge ([RowBadges]); the right-click menu (ResultMenu.kt) marks and unmarks them.
 */
@Composable
fun DocRow(row: ResultRow, c: AppController, ui: MainUi, selected: Boolean, listFocused: Boolean) {
  val tk = LocalSunlight.current
  val f = row.file
  val leave = rememberLeave()
  val folder = remember(f.id) { PathText.tilde(c.fileOf(f).parent.orEmpty()) }
  // A hidden row (a blurred picture) shows no excerpt and says "hidden" in place of its name.
  val hidden = LocalPreviewBlur.current.hides(f)
  val snippet = remember(row, hidden) { if (hidden) null else cleanSnippet(row) }
  val name = if (hidden) t("hidden_label") else f.name
  // ---- SEARCH PACKAGE (A): age and folder (size only when sorting by Largest), several-chosen marks, the "..." button, stacking for large text ----
  val rc = LocalRowContext.current
  val displayDateMs = if (rc.sort == SortOrder.CREATED_NEWEST || rc.sort == SortOrder.CREATED_OLDEST) f.dateMillis else f.modifiedMillis.takeIf { it > 0L } ?: f.dateMillis
  val sub = remember(displayDateMs, f.sizeBytes, folder, rc.sort, rc.nowMs) { RowText.subtitle(Age.label(displayDateMs, rc.nowMs), folder, formatBytes(f.sizeBytes), rc.sort) }
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val ring = listFocused && (!ui.multi || ui.isCursor(f.id))
  val box = remember { Spot() }
  val spoken = remember(row, name, sub, snippet, hidden) { ResultSpeech.doc(row, name, sub, snippet, hidden) }
  Box(Modifier.fillMaxWidth().padding(horizontal = Sun.S8, vertical = 1.dp).spot(box)) {
    Row(
      Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Default).resultSurface(selected, ring, ROW_SHAPE, source)
        .dragFiles(f.id) {
          if (hidden || f.sensitive) emptyList()
          else if (ui.multi && f.id in ui.picked) c.results.value.rows.filter { it.file.id in ui.picked && !it.file.sensitive }.map { c.fileOf(it.file) }
          else listOf(c.fileOf(f))
        }
        .resultPointer(f.id, ui.multi && f.id in ui.picked, { shift, toggle -> ui.press(f.id, shift, toggle) }, { leave(f.id) { c.open(f) } }, { ui.pressSecondary(f.id); ui.menu = MenuRequest(f.id, it.x.toInt(), it.y.toInt()) })
        .resultSemantics(selected, t("action_open")) { leave(f.id) { c.open(f) } }
        .padding(start = Sun.S12, end = Sun.S4, top = if (rc.listMode) 7.dp else 10.dp, bottom = if (rc.listMode) 7.dp else 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12),
    ) {
      if (rc.listMode) {
        val vis = remember(rc.listWidthDp, rc.sort) { ListColumnVisibility.forWidth(rc.listWidthDp, rc.sort) }
        val modText = remember(f.modifiedMillis) { if (f.modifiedMillis > 0L) PreviewFacts.date(f.modifiedMillis) else "\u2014" }
        val createdText = remember(f.dateMillis) { if (f.dateMillis > 0L) PreviewFacts.date(f.dateMillis) else "\u2014" }
        Row(
          Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(COL_GAP),
        ) {
          if (ui.multi) PickMark(selected)
          Row(Modifier.weight(1.9f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
            when {
              f.kind == FileKind.IMAGE || f.kind == FileKind.SCREENSHOT ->
                Thumb(c.fileOf(f), Modifier.size(LIST_THUMB_SIZE).clip(LIST_THUMB_SHAPE).border(Sun.Hairline, tk.line2, LIST_THUMB_SHAPE), side = 120, iconSize = 16.dp, row = f)
              f.kind == FileKind.VIDEO -> VideoFrameTile(row, c.fileOf(f), c.frames, hidden, size = LIST_THUMB_SIZE)
              else -> KindTile(f.kind, size = LIST_THUMB_SIZE, fileName = if (hidden) null else f.name)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = SunType.Label, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val visual = if (hidden) null else FileTypeVisual.of(f.name, f.kind)
                if (visual != null) TypeBadge(visual)
                else if (f.kind == FileKind.PDF || f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) Badge(kindLabel(f.kind))
                if (f.sensitive) Badge(t("sensitive_badge"))
              }
              if (snippet != null) SnippetText(snippet, lines = 1, style = SunType.Caption)
              else if (!vis.showFolder && folder.isNotBlank()) Text(folder, style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          if (vis.showFolder) {
            Text(folder.ifBlank { "\u2014" }, style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.1f))
          }
          Text(
            formatBytes(f.sizeBytes),
            style = SunType.Caption,
            color = if (rc.sort == SortOrder.LARGEST || rc.sort == SortOrder.SMALLEST) tk.ink else tk.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.width(COL_SIZE_W),
          )
          if (vis.showModified) {
            Text(
              modText,
              style = SunType.Caption,
              color = if (rc.sort == SortOrder.NEWEST || rc.sort == SortOrder.OLDEST) tk.ink else tk.ink2,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.End,
              modifier = Modifier.width(COL_MODIFIED_W),
            )
          }
          if (vis.showCreated) {
            Text(
              createdText,
              style = SunType.Caption,
              color = if (rc.sort == SortOrder.CREATED_NEWEST || rc.sort == SortOrder.CREATED_OLDEST) tk.ink else tk.ink2,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.End,
              modifier = Modifier.width(COL_CREATED_W),
            )
          }
        }
      } else {
        // The picture of the row (mark, icon, name, badges, match) is read as one sentence (ResultSpeech); the "..." button stays its own control.
        Row(
          Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12),
        ) {
          if (ui.multi) PickMark(selected)
          if (f.kind == FileKind.VIDEO) VideoFrameTile(row, c.fileOf(f), c.frames, hidden) else KindTile(f.kind, fileName = f.name)
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
  val f = row.file
  val leave = rememberLeave()
  val hidden = LocalPreviewBlur.current.hides(f)
  // ---- SEARCH PACKAGE (A): several-chosen marks and the "..." button on the picture ----
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val ring = listFocused && (!ui.multi || ui.isCursor(f.id))
  val box = remember { Spot() }
  val title = if (hidden) t("hidden_tile") else f.name
  val spoken = remember(row, title, hidden) { ResultSpeech.tile(row, title, hidden) }
  Box(modifier.spot(box)) {
    Column(
      Modifier.fillMaxWidth().resultSurface(selected, ring, TILE_CARD_SHAPE, source, tile = true)
        .dragFiles(f.id) {
          if (hidden || f.sensitive) emptyList()
          else if (ui.multi && f.id in ui.picked) c.results.value.rows.filter { it.file.id in ui.picked && !it.file.sensitive }.map { c.fileOf(it.file) }
          else listOf(c.fileOf(f))
        }
        .resultPointer(f.id, ui.multi && f.id in ui.picked, { shift, toggle -> ui.press(f.id, shift, toggle) }, { leave(f.id) { c.open(f) } }, { ui.pressSecondary(f.id); ui.menu = MenuRequest(f.id, it.x.toInt(), it.y.toInt()) })
        .resultSemantics(selected, t("action_open")) { leave(f.id) { c.open(f) } }
        .padding(5.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box {
        // Omit the competing inner hairline when selected so there is no double-bezel halo inside the outer selection border.
        val thumbMod = Modifier.fillMaxWidth().aspectRatio(1f).clip(THUMB_SHAPE)
          .then(if (!selected) Modifier.border(Sun.Hairline, tk.line2.copy(alpha = 0.7f), THUMB_SHAPE) else Modifier)
        Thumb(c.fileOf(f), thumbMod, side = 360, row = f)
        if (ui.multi) PickMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        if (selected || hovered) {
          Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(MORE_SHAPE).background(tk.paper.copy(alpha = 0.92f))) { MoreButton(ui, f.id, box) }
        }
      }
      Row(
        Modifier.padding(horizontal = 2.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(title, style = SunType.Caption, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
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

/** The heading over each result section: Photos, Documents and PDFs, Videos, or Audio. */
fun groupTitle(group: Group, mix: Mix = Mix.DOCUMENTS): String = when (group) {
  Group.PHOTOS -> t("kind_plural_photos")
  Group.VIDEOS -> t("kind_plural_videos")
  Group.AUDIO -> t("kind_plural_audio")
  Group.DOCUMENTS -> when (mix) {
    Mix.MEDIA -> t("group_media")
    Mix.BOTH -> t("group_documents_media")
    Mix.DOCUMENTS -> t("kind_plural_documents")
  }
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
  Row(
    Modifier.padding(horizontal = Sun.S8, vertical = Sun.S8).sunFocus(WEAK_SHAPE).sunHover(WEAK_SHAPE).clip(WEAK_SHAPE).pointerHoverIcon(PointerIcon.Hand)
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
