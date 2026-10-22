package trawl.desktop.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.theme.LocalSunlight
import java.time.Instant
import java.time.ZoneOffset
import trawl.desktop.AppController
import trawl.desktop.ViewUi
import trawl.desktop.t
import trawl.desktop.ui.Icons
import trawl.desktop.ui.MainUi
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.menuActiveRing
import trawl.desktop.ui.sunFocus

// ---- SEARCH PACKAGE (A): the date and sort controls and "Find similar" in the filter bar (A5-A9) ----

/** One line of a small choice menu. */
class Choice(val label: String, val selected: Boolean, val run: () -> Unit)

/**
 * A chip that opens a menu (the date and the sort). Quiet when it is at its default, the marker fill when it changes what the list shows, so a
 * changed view is visible without reading it. [onClear] draws a cross as its own button (a separate target, with its own spoken label).
 */
@Composable
private fun ControlChip(
  label: String, icon: ImageVector, active: Boolean, spoken: String, onClick: () -> Unit, modifier: Modifier = Modifier,
  clearLabel: String? = null, onClear: (() -> Unit)? = null, trailing: Boolean = true, focus: FocusRequester? = null,
) {
  val tk = LocalSunlight.current
  val fg = if (active) tk.onMarker else tk.ink
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  Row(
    modifier.heightIn(min = Sun.Chip).clip(CircleShape).background(if (active) tk.marker else if (hovered) tk.paper2 else tk.paper)
      .border(Sun.Border, if (active) tk.onMarker else tk.line, CircleShape),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      (if (focus != null) Modifier.focusRequester(focus) else Modifier).sunFocus(CircleShape).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).hoverable(source)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = spoken }
        .heightIn(min = Sun.Chip - 4.dp).padding(start = 12.dp, end = if (onClear != null) 4.dp else 8.dp, top = 2.dp, bottom = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(Sun.IconSmall))
      Spacer(Modifier.width(6.dp))
      Text(label, style = SunType.Label, color = fg, maxLines = 1)
      if (trailing && onClear == null) {
        Spacer(Modifier.width(2.dp))
        Icon(Icons.ExpandMore, contentDescription = null, tint = fg, modifier = Modifier.size(Sun.IconSmall))
      }
    }
    if (onClear != null && clearLabel != null) {
      Box(
        Modifier.padding(end = 4.dp).size(26.dp).sunFocus(CircleShape).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand)
          .clickable(role = Role.Button, onClick = onClear).semantics { contentDescription = clearLabel },
        contentAlignment = Alignment.Center,
      ) { Icon(Icons.Close, contentDescription = null, tint = fg, modifier = Modifier.size(Sun.IconSmall)) }
    }
  }
}

/** A small menu under its chip: Up and Down move, Enter chooses, Esc closes. Our own popup like the result menu, so it looks the same everywhere. */
@Composable
private fun ChoiceMenu(choices: List<Choice>, onDismiss: () -> Unit) {
  val tk = LocalSunlight.current
  var active by remember { mutableIntStateOf(choices.indexOfFirst { it.selected }.coerceAtLeast(0)) }
  val provider = remember {
    object : PopupPositionProvider {
      override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.bottom + 4).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
      }
    }
  }
  val shape = RoundedCornerShape(10.dp)
  Popup(
    popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true),
    onPreviewKeyEvent = { e ->
      if (e.type != KeyEventType.KeyDown) false
      else when (e.key) {
        Key.Escape -> { onDismiss(); true }
        Key.DirectionDown -> { active = (active + 1) % choices.size; true }
        Key.DirectionUp -> { active = (active + choices.size - 1) % choices.size; true }
        Key.Enter, Key.NumPadEnter -> { choices[active].run(); true }
        else -> false
      }
    },
  ) {
    Column(Modifier.widthIn(min = 200.dp).width(IntrinsicSize.Max).clip(shape).background(tk.paper).border(Sun.Border, tk.line, shape).padding(Sun.S4).selectableGroup()) {
      choices.forEachIndexed { i, ch -> ChoiceRow(ch, active == i) }
    }
  }
}

@Composable
private fun ChoiceRow(ch: Choice, keyboardActive: Boolean) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(7.dp)
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  Row(
    Modifier.fillMaxWidth().clip(shape).pointerHoverIcon(PointerIcon.Hand).hoverable(source)
      .background(if (hovered || keyboardActive) tk.paper2 else androidx.compose.ui.graphics.Color.Transparent, shape)
      .menuActiveRing(keyboardActive, shape)
      // selectable, not just clickable: a screen reader says "selected" for the choice that is on now, as the tick says it to the eye
      .selectable(selected = ch.selected, role = Role.RadioButton, onClick = ch.run).semantics { contentDescription = ch.label }
      .heightIn(min = 34.dp).padding(horizontal = Sun.S12, vertical = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(Sun.IconSmall)) { if (ch.selected) Icon(Icons.Check, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.IconSmall)) }
    Spacer(Modifier.width(Sun.S8))
    Text(ch.label, style = SunType.Body, color = tk.ink, maxLines = 1)
  }
}

/** The order the list is in at its default: newest first while browsing, best match for anything searched. */
fun defaultSort(browse: Boolean): SortOrder = if (browse) SortOrder.NEWEST else SortOrder.BEST

/**
 * The date and sort chips and, in "Find similar", the chip that says so. They sit in the filter bar after the kind chips; [FlowRow] wraps them
 * onto a second line in a narrow window so none is cut off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterBarRow(c: AppController, ui: MainUi, view: ViewUi, modifier: Modifier = Modifier, kindChips: @Composable () -> Unit) {
  FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8), itemVerticalAlignment = Alignment.CenterVertically) {
    kindChips()
    DateChip(c, ui, view)
    SortChip(c, ui, view)
    view.similar?.let { seed ->
      ControlChip(
        label = t("similar_chip", seed.name), icon = Icons.ImageSearch, active = true, spoken = t("similar_chip", seed.name), onClick = {},
        clearLabel = t("similar_remove"), onClear = { c.clearSimilar(); ui.focusSearch() }, modifier = Modifier.widthIn(max = 320.dp),
      )
    }
  }
}

@Composable
private fun DateChip(c: AppController, ui: MainUi, view: ViewUi) {
  val date = view.date
  val label = date?.filter?.label?.text() ?: t("date_any_time")
  val spoken = when {
    date == null -> t("date_group") + ": " + t("date_any_time")
    date.phrase != null -> t("date_from_words", label)
    else -> t("date_group") + ": " + label
  }
  Box {
    ControlChip(
      label = label, icon = Icons.Calendar, active = date != null, spoken = spoken, onClick = { ui.dateMenu = !ui.dateMenu },
      clearLabel = t("date_remove"), onClear = if (date != null) ({ c.clearDate(); ui.focusSearch() }) else null, focus = ui.dateChipFocus,
    )
    if (ui.dateMenu) {
      val close = { ui.dateMenu = false; ui.focusAfterMenu(ui.dateChipFocus) }
      val current = date?.filter?.label
      val choices = buildList {
        add(Choice(t("date_any_time"), date == null) { close(); if (date != null) c.clearDate() })
        DatePreset.entries.forEach { p -> add(Choice(p.menuText(), current == p.label()) { close(); c.setDatePreset(p) }) }
        add(Choice(t("date_pick_range"), false) { ui.dateMenu = false; ui.dateDialog = true })
      }
      ChoiceMenu(choices) { close() }
    }
  }
}

@Composable
private fun SortChip(c: AppController, ui: MainUi, view: ViewUi) {
  val searching = !view.browse
  val label = view.sort.text()
  Box {
    ControlChip(
      label = label, icon = Icons.SwapVert, active = view.sort != defaultSort(view.browse), spoken = t("sort_group") + ": " + label,
      onClick = { ui.sortMenu = !ui.sortMenu }, focus = ui.sortChipFocus,
    )
    if (ui.sortMenu) {
      val close = { ui.sortMenu = false; ui.focusAfterMenu(ui.sortChipFocus) }
      ChoiceMenu(sortChoices(searching).map { o -> Choice(o.text(), view.sort == o) { close(); c.setSort(o) } }) { close() }
    }
  }
}

/** The "pick dates" dialog: the first and last day in a calendar, or typed (the picker's own keyboard-friendly text mode). A single day is a range of one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(c: AppController, ui: MainUi) {
  val state = rememberDateRangePickerState()
  val close = { ui.dateDialog = false; ui.focusAfterMenu(ui.dateChipFocus) }
  fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
  DatePickerDialog(
    onDismissRequest = close,
    confirmButton = {
      SunButton(
        onClick = {
          val a = state.selectedStartDateMillis ?: return@SunButton
          val b = state.selectedEndDateMillis ?: a
          close()
          c.setDateRange(day(a), day(b))
        },
        enabled = state.selectedStartDateMillis != null,
      ) { Text(t("date_picker_ok"), style = SunType.Label) }
    },
    dismissButton = { SunTextButton(onClick = close) { Text(t("action_cancel"), style = SunType.Label) } },
  ) {
    DateRangePicker(
      state, modifier = Modifier.heightIn(max = 560.dp),
      title = { Text(t("date_picker_title"), style = SunType.Heading, modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
      showModeToggle = true,
    )
  }
}
