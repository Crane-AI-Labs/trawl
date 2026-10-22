package com.craneai.findr.ui.search

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.DateRangePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.craneai.findr.R
import com.craneai.findr.core.DateLabel
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.DateUnit
import com.craneai.findr.core.DayPart
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ---- words ------------------------------------------------------------------------------------------------------------

@androidx.annotation.StringRes
fun SortOrder.labelRes(): Int = when (this) {
  SortOrder.BEST -> R.string.sort_best
  SortOrder.NEWEST -> R.string.sort_newest
  SortOrder.OLDEST -> R.string.sort_oldest
  SortOrder.LARGEST -> R.string.sort_largest
  SortOrder.NAME -> R.string.sort_name
}

/** The sorts the menu offers: Best match only where there is a query to be best for. */
fun sortChoices(searching: Boolean): List<SortOrder> =
  if (searching) listOf(SortOrder.BEST, SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.LARGEST, SortOrder.NAME)
  else listOf(SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.LARGEST, SortOrder.NAME)

/** A date filter's name in the person's language: strings from resources, month and day names from the locale. */
fun DateLabel.text(context: Context, locale: Locale): String {
  val r = context.resources
  fun plural(past: Boolean, n: Int, unit: DateUnit): String {
    val id = when (unit) {
      DateUnit.DAY -> if (past) R.plurals.date_past_days else R.plurals.date_ago_days
      DateUnit.WEEK -> if (past) R.plurals.date_past_weeks else R.plurals.date_ago_weeks
      DateUnit.MONTH -> if (past) R.plurals.date_past_months else R.plurals.date_ago_months
      DateUnit.YEAR -> if (past) R.plurals.date_past_years else R.plurals.date_ago_years
    }
    return r.getQuantityString(id, n, n)
  }
  fun day(d: LocalDate) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(d)
  return when (this) {
    DateLabel.Today -> r.getString(R.string.date_today)
    DateLabel.Yesterday -> r.getString(R.string.date_yesterday)
    DateLabel.ThisWeek -> r.getString(R.string.date_this_week)
    DateLabel.LastWeek -> r.getString(R.string.date_last_week)
    DateLabel.ThisMonth -> r.getString(R.string.date_this_month)
    DateLabel.LastMonth -> r.getString(R.string.date_last_month)
    DateLabel.ThisYear -> r.getString(R.string.date_this_year)
    DateLabel.LastYear -> r.getString(R.string.date_last_year)
    DateLabel.Weekend -> r.getString(R.string.date_weekend)
    DateLabel.LastNight -> r.getString(R.string.date_last_night)
    is DateLabel.Part -> r.getString(
      when (part) {
        DayPart.MORNING -> if (yesterday) R.string.date_yesterday_morning else R.string.date_this_morning
        DayPart.AFTERNOON -> if (yesterday) R.string.date_yesterday_afternoon else R.string.date_this_afternoon
        DayPart.EVENING -> if (yesterday) R.string.date_yesterday_evening else R.string.date_this_evening
      },
    )
    is DateLabel.Past -> plural(true, n, unit)
    is DateLabel.Ago -> plural(false, n, unit)
    is DateLabel.Day -> day(date)
    is DateLabel.Month -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)
    is DateLabel.Year -> String.format(locale, "%d", year)
    is DateLabel.Custom -> if (from == to) day(from) else r.getString(R.string.date_range, day(from), day(to))
  }
}

@Composable
fun dateLabelText(label: DateLabel): String {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  return remember(label, locale) { label.text(context, locale) }
}

@Composable
private fun presetLabel(p: DatePreset): String = stringResource(
  when (p) {
    DatePreset.TODAY -> R.string.date_today
    DatePreset.THIS_WEEK -> R.string.date_this_week
    DatePreset.THIS_MONTH -> R.string.date_this_month
    DatePreset.THIS_YEAR -> R.string.date_this_year
  },
)

// ---- chips --------------------------------------------------------------------------------------------------------------

/**
 * The shape both chips share: a pill with an icon, a word and (for a menu) a drop arrow, 36 dp to see and 48 to touch like the
 * filter chip beside it. [active] puts it on the marker, under ink, as a chosen filter is everywhere in Sunlight. [onClear] adds
 * the cross, a second target of its own.
 */
@Composable
private fun ChipFace(
  icon: ImageVector,
  text: String?,
  active: Boolean,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onClear: (() -> Unit)? = null,
  clearLabel: String = "",
  maxTextWidth: Int = 120,
  arrow: Boolean = true,
) {
  val t = LocalSunlight.current
  val fg = if (active) t.onMarker else t.ink
  Row(
    modifier
      .minimumInteractiveComponentSize()
      .clip(CircleShape)
      .background(if (active) t.marker else t.paper)
      .border(Sun.Border, if (active) t.onMarker else t.line, CircleShape)
      .heightIn(min = 36.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      Modifier
        .heightIn(min = 36.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }
        .padding(start = 10.dp, end = if (onClear != null) 4.dp else if (text == null) 8.dp else 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
      if (text != null) {
        Spacer(Modifier.width(Sun.S4))
        Text(text, style = SunType.CaptionBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = maxTextWidth.dp))
      }
      if (arrow) Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
    }
    if (onClear != null) {
      Box(
        Modifier
          .size(36.dp)
          .clip(CircleShape)
          .clickable(role = Role.Button, onClick = onClear)
          .semantics { contentDescription = clearLabel },
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Composable
private fun menuColors() = LocalSunlight.current

/** One entry in a chip's menu: marker under ink when it is the current choice, a tick, and the radio role for TalkBack. */
@Composable
private fun ChoiceItem(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
  val t = LocalSunlight.current
  DropdownMenuItem(
    text = { Text(text, style = SunType.BodyLarge, color = if (selected) t.onMarker else t.ink) },
    leadingIcon = icon?.let { i -> { Icon(i, contentDescription = null, tint = if (selected) t.onMarker else t.ink) } },
    trailingIcon = if (selected) ({ Icon(Icons.Rounded.Check, contentDescription = null, tint = t.onMarker) }) else null,
    onClick = onClick,
    modifier = Modifier
      .heightIn(min = Sun.Target)
      .then(if (selected) Modifier.background(t.marker) else Modifier)
      .semantics { this.selected = selected; role = Role.RadioButton },
  )
}

@Composable
private fun ChipMenu(open: Boolean, onOpen: (Boolean) -> Unit, focusable: Boolean, content: @Composable () -> Unit) {
  val t = LocalSunlight.current
  DropdownMenu(
    expanded = open,
    onDismissRequest = { onOpen(false) },
    // In the search box the menu must not take focus: the box keeps its caret and the keyboard stays up.
    properties = PopupProperties(focusable = focusable),
    shape = MaterialTheme.shapes.medium,
    containerColor = t.paper,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(Sun.Border, t.line),
    modifier = Modifier.selectableGroup(),
  ) { content() }
}

/** "Sort: Newest" as one chip with a menu. Browsing has no Best match (nothing to be best for). */
@Composable
fun SortChip(sort: SortOrder, searching: Boolean, onSort: (SortOrder) -> Unit, modifier: Modifier = Modifier) {
  var open by remember { mutableStateOf(false) }
  val word = stringResource(sort.labelRes())
  val group = stringResource(R.string.sort_group)
  // Anything but the default order is a choice the person made, so the chip says so with the marker.
  val chosen = sort != (if (searching) SortOrder.BEST else SortOrder.NEWEST)
  Box(modifier) {
    ChipFace(Icons.Rounded.SwapVert, word, chosen, stringResource(R.string.a11y_description_note, group, word), { open = true })
    ChipMenu(open, { open = it }, focusable = true) {
      sortChoices(searching).forEach { s -> ChoiceItem(stringResource(s.labelRes()), s == sort, { open = false; onSort(s) }) }
    }
  }
}

/**
 * The date chip: "Any time" until a date is chosen, then the date with a cross. The menu holds the quick choices and the calendar.
 * [compact] is the form inside the search box: just the calendar until a date is set, so the typed words keep their room.
 * A date read out of the typed words ([fromWords]) looks and works the same; the screen reader is told where it came from.
 */
@Composable
fun DateChip(
  date: ActiveDate?,
  onPreset: (DatePreset) -> Unit,
  onPickRange: () -> Unit,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  focusable: Boolean = true,
) {
  var open by remember { mutableStateOf(false) }
  val group = stringResource(R.string.date_group)
  val any = stringResource(R.string.date_any_time)
  val label = date?.let { dateLabelText(it.filter.label) }
  val description = when {
    label == null -> stringResource(R.string.a11y_description_note, group, any)
    date.phrase != null -> stringResource(R.string.a11y_description_note, group, stringResource(R.string.date_from_words, label))
    else -> stringResource(R.string.a11y_description_note, group, label)
  }
  Box(modifier) {
    ChipFace(
      icon = Icons.Rounded.CalendarMonth,
      text = label ?: if (compact) null else any,
      active = date != null,
      description = description,
      onClick = { open = true },
      onClear = if (date != null) onClear else null,
      clearLabel = stringResource(R.string.date_remove),
      maxTextWidth = if (compact) 88 else 160,
      arrow = date == null,
    )
    ChipMenu(open, { open = it }, focusable) {
      DatePreset.entries.forEach { p ->
        val chosen = date != null && date.phrase == null && presetMatches(p, date)
        ChoiceItem(presetLabel(p), chosen, { open = false; onPreset(p) })
      }
      ChoiceItem(stringResource(R.string.date_pick_range), date?.filter?.label is DateLabel.Custom, { open = false; onPickRange() })
    }
  }
}

private fun presetMatches(p: DatePreset, date: ActiveDate): Boolean = when (p) {
  DatePreset.TODAY -> date.filter.label == DateLabel.Today
  DatePreset.THIS_WEEK -> date.filter.label == DateLabel.ThisWeek
  DatePreset.THIS_MONTH -> date.filter.label == DateLabel.ThisMonth
  DatePreset.THIS_YEAR -> date.filter.label == DateLabel.ThisYear
}

/** "Similar to IMG_0412.jpg" with a cross: the one visible sign that the list is "Find similar", and the way out of it. */
@Composable
fun SimilarChip(name: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
  val text = stringResource(R.string.similar_chip, name)
  Box(modifier) {
    ChipFace(
      icon = Icons.Rounded.ImageSearch,
      text = text,
      active = true,
      description = text,
      onClick = {},
      onClear = onClear,
      clearLabel = stringResource(R.string.similar_remove),
      maxTextWidth = 220,
      arrow = false,
    )
  }
}

/** The line of sort and date chips above the list (inside the grid, so it scrolls away with it). */
@Composable
fun SortDateRow(
  sort: SortOrder,
  searching: Boolean,
  date: ActiveDate?,
  onSort: (SortOrder) -> Unit,
  onPreset: (DatePreset) -> Unit,
  onPickRange: () -> Unit,
  onClearDate: () -> Unit,
  modifier: Modifier = Modifier,
  /** "Find similar" is on: the name of the file it started from. It leads the row as a chip with a cross. */
  similar: String? = null,
  onClearSimilar: () -> Unit = {},
) {
  Row(
    modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (similar != null) SimilarChip(similar, onClearSimilar)
    SortChip(sort, searching, onSort)
    DateChip(date, onPreset, onPickRange, onClearDate)
  }
}

/**
 * Short windows (a tablet or phone on its side): the kind, sort and date chips on one scrolling line, so the list gets the row
 * the second line took. The kind chips stay one selectable group of their own. Same chips, same actions as [FilterRow] over
 * [SortDateRow].
 */
@Composable
fun FilterSortRow(
  filter: com.craneai.findr.core.KindFilter,
  onFilter: (com.craneai.findr.core.KindFilter) -> Unit,
  sort: SortOrder,
  searching: Boolean,
  date: ActiveDate?,
  onSort: (SortOrder) -> Unit,
  onPreset: (DatePreset) -> Unit,
  onPickRange: () -> Unit,
  onClearDate: () -> Unit,
  modifier: Modifier = Modifier,
  similar: String? = null,
  onClearSimilar: () -> Unit = {},
) {
  val group = stringResource(R.string.filter_group)
  Row(
    modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (similar != null) SimilarChip(similar, onClearSimilar)
    Row(
      Modifier.semantics { contentDescription = group }.selectableGroup(),
      horizontalArrangement = Arrangement.spacedBy(Sun.S8),
      verticalAlignment = Alignment.CenterVertically,
    ) { KindChips(filter, onFilter) }
    SortChip(sort, searching, onSort)
    DateChip(date, onPreset, onPickRange, onClearDate)
  }
}

// ---- calendar -----------------------------------------------------------------------------------------------------------

/** First and last day from the system calendar picker. Days come back as UTC midnights, so they are read as UTC dates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(onApply: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
  val state = rememberDateRangePickerState()
  fun day(ms: Long?): LocalDate? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
  val from = day(state.selectedStartDateMillis)
  val to = day(state.selectedEndDateMillis) ?: from
  // Not DatePickerDialog: its window is a fixed 360 dp, and the range picker lays its seven columns out wider than that on
  // a 411 dp phone (TECNO Spark 50, 2026-10-16: the Saturday column was cut off, so no Saturday could be picked). Our own
  // dialog gives the picker the full width of the screen.
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 640.dp),
      shape = DatePickerDefaults.shape,
      color = DatePickerDefaults.colors().containerColor,
    ) {
      Column {
        DateRangePicker(
          state,
          title = { Text(stringResource(R.string.date_picker_title), style = SunType.Label, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
          modifier = Modifier.weight(1f, fill = false),
        )
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          com.craneai.findr.ui.theme.SunTextButton(onClick = onDismiss) { Text(stringResource(R.string.sensitive_cancel)) }
          Spacer(Modifier.width(8.dp))
          com.craneai.findr.ui.theme.SunButton(
            onClick = { if (from != null && to != null) onApply(from, to) },
            enabled = from != null,
          ) { Text(stringResource(R.string.date_picker_ok)) }
        }
      }
    }
  }
}
