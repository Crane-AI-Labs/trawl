package com.craneai.findr.ui.search

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.Locator
import com.craneai.findr.core.SearchHit
import com.craneai.findr.ui.common.FileThumbnail
import com.craneai.findr.ui.common.LocalPreviewBlur
import com.craneai.findr.ui.common.SharedIcons
import com.craneai.findr.ui.common.UiCounters
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.common.highlightedSnippet
import com.craneai.findr.ui.common.labelRes
import com.craneai.findr.ui.common.plainSnippet

/** "at 0:42" for a moment hit, "p. 3" for a page hit. Null when the hit points at the whole file. */
@Composable
private fun locatorLabel(locator: Locator?): String? = when (locator) {
  is Locator.Page -> stringResource(R.string.badge_page, locator.page)
  is Locator.Time -> stringResource(R.string.badge_at_time, formatDuration(locator.startMs))
  null -> null
}

@Composable
private fun relativeAge(file: IndexedFile): String {
  val context = LocalContext.current
  val locale = LocalConfiguration.current.locales[0]
  // Recomputed rarely; "2 hours ago" needs no live ticking on a results list.
  // The file's date: the day a photo was taken when the phone knows it, else when the file was last changed.
  val skew = LocalClockSkewMs.current
  return remember(file.dateMillis, locale, skew) { ageLabel(context, file.dateMillis, System.currentTimeMillis() + skew) }
}

/** How far the phone's clock is behind its own files ([com.craneai.findr.core.ClockSense]), so a file from last week does not read "In 7 weeks" on a phone whose clock is 7 weeks slow. 0 when the clock is right. */
internal val LocalClockSkewMs = staticCompositionLocalOf { 0L }

/**
 * "2 hours ago" where the language has relative-time words (ICU data). Where it has none, Android prints a bare signed
 * offset ("-8 h", seen in Luganda), so the date and time are shown instead, which every language can format.
 */
internal fun ageLabel(context: android.content.Context, millis: Long, now: Long = System.currentTimeMillis()): String {
  val relative = DateUtils.getRelativeTimeSpanString(ageBasis(millis, now), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
  return if (looksUnlocalised(relative)) {
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
  } else relative
}

/**
 * The moment an age is measured from: never later than [now]. On a phone whose clock is behind its files ([LocalClockSkewMs]
 * makes "now" the 3rd newest file's date) the one or two files newer than that "now" would read "In 0 min."; clamped, they read
 * as the same instant as now ("0 min. ago"). An age label never points at the future.
 */
internal fun ageBasis(millis: Long, now: Long): Long = if (millis > now) now else millis

/** A relative time with no words around the number: "-8 h", "+3 d" (what ICU falls back to without the language's data). */
internal fun looksUnlocalised(relative: String): Boolean = Regex("""^\s*[-+\u2212]\s*\d""").containsMatchIn(relative)

/** A square thumbnail in the grid: photo, screenshot or video. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(
  hit: SearchHit,
  onOpen: () -> Unit,
  onShare: () -> Unit,
  /** Long-press: start choosing files with this one. While [selecting], a tap and a long-press both tick or untick it. */
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  icons: SharedIcons? = null,
  selecting: Boolean = false,
  selected: Boolean = false,
) {
  SideEffect { UiCounters.cellComposes.incrementAndGet() }
  val file = hit.file
  val haptics = LocalHapticFeedback.current
  val moment = (hit.locator as? Locator.Time)?.startMs
  val atLabel = locatorLabel(hit.locator)
  val kindLabel = stringResource(file.kind.labelRes())
  val description = buildString {
    append(file.name).append(", ").append(kindLabel)
    file.durationMs?.let { append(", ").append(formatDuration(it)) }
    atLabel?.let { append(", ").append(it) }
  }
  val blur = LocalPreviewBlur.current
  val hidden = blur.hides(file)
  val openLabel = stringResource(if (hidden) R.string.action_show_picture else R.string.action_open)
  val selectLabel = stringResource(R.string.action_select)
  val hiddenNote = stringResource(R.string.preview_hidden)
  val cellDescription = if (hidden) stringResource(R.string.a11y_description_note, description, hiddenNote) else description

  Box(
    modifier
      .aspectRatio(1f)
      .graphicsLayer()   // its own RenderNode: a scroll moves the cell instead of re-recording everything in it
      .clip(MaterialTheme.shapes.extraSmall)   // Sunlight: 8 dp tiles
      .combinedClickable(
        onClickLabel = if (selecting) selectLabel else openLabel,
        // A blurred cell's tap shows the picture; the next tap opens it. Choosing files ignores the blur: a tap ticks it.
        onClick = if (selecting) onToggle else if (hidden) { { blur.reveal(file); Unit } } else onOpen,
        onLongClickLabel = selectLabel,
        onLongClick = {
          haptics.performHapticFeedback(HapticFeedbackType.LongPress)
          onToggle()
        },
      )
      .semantics { contentDescription = cellDescription; if (selecting) this.selected = selected },
  ) {
    FileThumbnail(file, Modifier.fillMaxSize(), frameMs = moment, iconSize = 30.dp, iconPainter = icons?.kind(file.kind), hiddenWords = hidden)
    if (selecting) {
      if (selected) Box(Modifier.fillMaxSize().background(LocalSunlight.current.marker.copy(alpha = 0.28f)).border(3.dp, LocalSunlight.current.focus, MaterialTheme.shapes.extraSmall))
      SelectMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
    } else {
      ShareBadge(onShare, Modifier.align(Alignment.BottomEnd), painter = icons?.share)
    }
    if (file.kind == FileKind.VIDEO) {
      val dur = file.durationMs
      if (atLabel != null) {
        Pill(atLabel, strong = true, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
        if (dur != null) Pill(formatDuration(dur), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp), scrim = true)
      } else if (dur != null) {
        Pill(formatDuration(dur), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp), scrim = true, leading = true)
      }
    }
  }
}

/**
 * A full-width row (72 dp at least): a 56 dp thumbnail, the name, one line that says what and where it is, the matched words
 * with the hits on the marker, and the labelled Share and Copy buttons. A hairline (line-2) separates rows.
 *
 * [compact]: while the person types (the box has focus and the keyboard is up) the row shrinks to one line each for the name
 * (cut in the middle, so the extension stays), what and where, and the matched words, so several results fit above the
 * keyboard. Same file, same actions, same 56 dp targets; only the lines change.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
  hit: SearchHit,
  onOpen: () -> Unit,
  onShare: () -> Unit,
  onCopy: () -> Unit,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  selecting: Boolean = false,
  selected: Boolean = false,
) {
  val file = hit.file
  val haptics = LocalHapticFeedback.current
  val t = LocalSunlight.current
  val badge = locatorLabel(hit.locator)
  val folder = file.relativePath.substringBeforeLast('/', "")
  val age = relativeAge(file)
  val pages = file.pageCount?.takeIf { it > 1 && file.kind == FileKind.PDF }
  val pagesText = pages?.let { pluralStringResource(R.plurals.pages_count, it, it) }
  val kindLabel = stringResource(file.kind.labelRes())
  val subtitle = buildString {
    append(kindLabel)
    if (folder.isNotEmpty()) append("  ·  ").append(folder)
    append("  ·  ").append(age)
    val durationMs = file.durationMs   // a local copy: the property lives in :core now, so it cannot be smart-cast
    if (file.kind == FileKind.AUDIO && durationMs != null) append("  ·  ").append(formatDuration(durationMs))
    if (pagesText != null) append("  ·  ").append(pagesText)
  }
  val blur = LocalPreviewBlur.current
  val hidden = blur.hides(file)
  val showLabel = stringResource(R.string.action_show_picture)
  val hiddenNote = stringResource(R.string.preview_hidden)
  val description = buildString {
    append(file.name).append(", ").append(kindLabel)
    badge?.let { append(", ").append(it) }
    if (hidden) append(", ").append(hiddenNote)
    hit.snippet?.let { append(". ").append(plainSnippet(it)) }
  }
  // The matched words: bold, on the marker (a fill under ink, never a coloured word).
  val highlight = SpanStyle(fontWeight = FontWeight.Bold, background = t.marker, color = t.onMarker)
  val divider = t.line2

  // Share and Copy sit beside the clickable part, not inside it: the merged "open this file" node must not swallow them.
  // At a large font size (150% and up) they move under it, so the name keeps the width it needs instead of wrapping per word.
  val stacked = LocalDensity.current.fontScale >= 1.5f
  val openPart: @Composable (Modifier) -> Unit = { partModifier ->
    Row(
      partModifier
        .clip(MaterialTheme.shapes.small)
        .combinedClickable(
          onClickLabel = stringResource(if (selecting) R.string.action_select else R.string.action_open),
          onClick = if (selecting) onToggle else onOpen,
          onLongClickLabel = stringResource(R.string.action_select),
          onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggle()
          },
        )
        .semantics(mergeDescendants = true) {
          contentDescription = description
          if (selecting) this.selected = selected
          // The thumbnail has no node of its own (it is merged into this one), so TalkBack reaches "show" as an action here.
          if (hidden) customActions = listOf(CustomAccessibilityAction(showLabel) { blur.reveal(file); true })
        }
        .padding(start = 8.dp, top = if (compact) 3.dp else 8.dp, bottom = if (compact) 3.dp else 8.dp, end = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(Sun.S12),
      verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    ) {
      if (selecting) SelectMark(selected, Modifier.align(Alignment.CenterVertically), size = 28)
      FileThumbnail(
        file,
        Modifier
          .size(Sun.Thumb)
          .clip(MaterialTheme.shapes.extraSmall)
          .then(if (hidden) Modifier.clickable { blur.reveal(file) } else Modifier)
          .clearAndSetSemantics { },
        frameMs = (hit.locator as? Locator.Time)?.startMs,
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
          Text(
            file.name,
            style = SunType.BodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact) 1 else 2,
            overflow = if (compact) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (badge != null) {
            Pill(badge, strong = true, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
          }
        }
        Text(
          if (hidden) stringResource(R.string.preview_hidden_words) else subtitle,
          style = if (compact) SunType.Caption else SunType.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = if (compact) 1 else 2,
          overflow = TextOverflow.Ellipsis,
        )
        hit.snippet?.let { snippet ->
          Text(
            highlightedSnippet(snippet, highlight),
            style = SunType.Body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact) 1 else 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
  val rowModifier = modifier
    .fillMaxWidth()
    .heightIn(min = Sun.Row)
    .then(if (selecting && selected) Modifier.background(t.marker.copy(alpha = 0.22f)) else Modifier)
    .drawBehind { drawLine(divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1.dp.toPx()) }
  // Choosing files: the row's own Share and Copy step aside (the bar at the bottom acts on everything ticked).
  if (selecting) {
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) { openPart(Modifier.weight(1f)) }
  } else if (stacked) {
    Column(rowModifier) {
      openPart(Modifier.fillMaxWidth())
      RowActions(name = file.name, onShare = onShare, onCopy = onCopy, modifier = Modifier.align(Alignment.End))
    }
  } else {
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
      openPart(Modifier.weight(1f))
      RowActions(name = file.name, onShare = onShare, onCopy = onCopy)
    }
  }
}

/** A small label on top of a thumbnail or beside a title. `strong` is the match itself ("p. 3", "at 0:42"): marker under ink. */
@Composable
fun Pill(
  text: String,
  modifier: Modifier = Modifier,
  strong: Boolean = false,
  scrim: Boolean = false,
  leading: Boolean = false,
) {
  val t = LocalSunlight.current
  val bg = when {
    strong -> t.marker
    scrim -> Color(0xB3000000)
    else -> t.paper2
  }
  val fg = when {
    strong -> t.onMarker
    scrim -> Color.White
    else -> t.ink
  }
  Row(
    modifier
      .clip(CircleShape)
      .background(bg)
      .then(if (strong) Modifier.border(1.dp, t.onMarker, CircleShape) else Modifier)
      .padding(horizontal = 8.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (leading) {
      Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
    }
    Text(text, style = SunType.CaptionBold, color = fg, maxLines = 1, modifier = Modifier.widthIn(max = 120.dp))
  }
}
