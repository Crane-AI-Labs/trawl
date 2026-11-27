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
import com.craneai.findr.ui.theme.LocalReduceMotion
import com.craneai.findr.ui.theme.Motion
import com.craneai.findr.ui.theme.motionTween
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.semantics.Role
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
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.common.FileThumbnail
import com.craneai.findr.ui.common.LocalPreviewBlur
import com.craneai.findr.ui.common.SharedIcons
import com.craneai.findr.ui.common.UiCounters
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.common.highlightedSnippet
import com.craneai.findr.ui.common.isVisual
import com.craneai.findr.ui.common.labelRes
import com.craneai.findr.ui.common.plainSnippet

/** "at 0:42" for a moment hit, "p. 3" for a page hit. Null when the hit points at the whole file. */
@Composable
private fun locatorLabel(locator: Locator?): String? = when (locator) {
  is Locator.Page -> stringResource(R.string.badge_page, locator.page)
  is Locator.Time -> stringResource(R.string.badge_at_time, formatDuration(locator.startMs))
  null -> null
}

/** Which timestamp a row's relative age displays: capture/created date only for Created sorts, modified date otherwise (Desktop parity). */
internal fun displayDateMillis(file: IndexedFile, sort: SortOrder): Long = when (sort) {
  SortOrder.CREATED_NEWEST, SortOrder.CREATED_OLDEST -> file.dateMillis
  else -> file.modifiedMillis.takeIf { it > 0L } ?: file.dateMillis
}

/** Bounded LRU caches so 120 Hz list flings reuse formatted dates across same-day/same-minute files instead of calling DateUtils per row. */
private object DateLabelCache {
  private const val MAX_ENTRIES = 512
  private val shortDates = object : LinkedHashMap<Long, String>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > MAX_ENTRIES
  }
  private val ages = object : LinkedHashMap<Long, String>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > MAX_ENTRIES
  }
  private var cachedLocaleKey: String = ""

  private fun ensureLocale(localeTag: String, tzId: String) {
    val key = "$localeTag|$tzId"
    if (key != cachedLocaleKey) {
      cachedLocaleKey = key
      shortDates.clear()
      ages.clear()
    }
  }

  @Synchronized
  fun shortDate(context: android.content.Context, millis: Long, localeTag: String): String {
    val tz = java.util.TimeZone.getDefault()
    ensureLocale(localeTag, tz.id)
    val dayBucket = Math.floorDiv(millis + tz.getOffset(millis), 86_400_000L)
    return shortDates.getOrPut(dayBucket) {
      DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)
    }
  }

  @Synchronized
  fun relativeAge(context: android.content.Context, millis: Long, nowMs: Long, localeTag: String): String {
    ensureLocale(localeTag, java.util.TimeZone.getDefault().id)
    val minBucket = millis / 60_000L
    val nowMin = nowMs / 60_000L
    val key = (minBucket shl 24) xor (nowMin and 0xFFFFFFL)
    return ages.getOrPut(key) { ageLabel(context, millis, nowMs) }
  }
}

@Composable
private fun relativeAge(file: IndexedFile, sort: SortOrder = SortOrder.NEWEST): String {
  val context = LocalContext.current
  val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
  val skew = LocalClockSkewMs.current
  val displayMs = displayDateMillis(file, sort)
  return remember(displayMs, localeTag, skew) {
    DateLabelCache.relativeAge(context, displayMs, System.currentTimeMillis() + skew, localeTag)
  }
}

@Composable
private fun shortDateLabel(millis: Long): String {
  val context = LocalContext.current
  val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
  return remember(millis, localeTag) { DateLabelCache.shortDate(context, millis, localeTag) }
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

private val UNLOCALISED_RELATIVE = Regex("""^\s*[-+\u2212]\s*\d""")

/** A relative time with no words around the number: "-8 h", "+3 d" (what ICU falls back to without the language's data). */
internal fun looksUnlocalised(relative: String): Boolean = UNLOCALISED_RELATIVE.containsMatchIn(relative)

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
  /** A video found at a moment: its time label opens the sheet with the pictures around it (MomentPeekSheet). Null leaves the label a plain label. */
  onPeek: (() -> Unit)? = null,
) {
  SideEffect { UiCounters.cellComposes.incrementAndGet() }
  val file = hit.file
  val haptics = LocalHapticFeedback.current
  val moment = (hit.locator as? Locator.Time)?.startMs
  val atLabel = locatorLabel(hit.locator)
  val kindLabel = stringResource(file.kind.labelRes())
  val description = remember(file.name, kindLabel, file.durationMs, atLabel) {
    buildString {
      append(file.name).append(", ").append(kindLabel)
      file.durationMs?.let { append(", ").append(formatDuration(it)) }
      atLabel?.let { append(", ").append(it) }
    }
  }
  val blur = LocalPreviewBlur.current
  val hidden = blur.hides(file)
  val openLabel = stringResource(if (hidden) R.string.action_show_picture else R.string.action_open)
  val selectLabel = stringResource(R.string.action_select)
  val cellDescription = if (hidden) {
    val hiddenNote = stringResource(if (blur.checking(file)) R.string.preview_checking else R.string.preview_hidden)
    stringResource(R.string.a11y_description_note, description, hiddenNote)
  } else description

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
    if (file.kind == FileKind.VIDEO) {
      val dur = file.durationMs
      if (atLabel != null) {
        // The matched moment sits top left, clear of the share badge (bottom right): on a 104 dp tile the ~70 dp label and the badge
        // overlapped at the bottom. A tile has room for one time, and the moment is the one a search is about; the length stays in the
        // tile's description. While choosing files the tick mark takes this corner, so the label steps aside.
        if (!selecting) {
          if (onPeek != null) {
            // A 48 dp touch area around the label opens the pictures around the moment.
            val peekLabel = stringResource(R.string.strip_peek_label)
            Box(
              Modifier.align(Alignment.TopStart).heightIn(min = 48.dp).clickable(onClickLabel = peekLabel, role = Role.Button, onClick = onPeek),
              contentAlignment = Alignment.TopStart,
            ) { Pill(atLabel, strong = true, modifier = Modifier.padding(6.dp)) }
          } else {
            Pill(atLabel, strong = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
          }
        }
      } else if (dur != null) {
        // Top end: the share badge owns the bottom end and the tick of selecting the top start, so the length never meets either (UI audit item 14).
        Pill(formatDuration(dur), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).testTag("video-pill"), scrim = true, leading = true)
      }
    }
    if (selecting) {
      if (selected) Box(Modifier.fillMaxSize().background(LocalSunlight.current.marker.copy(alpha = 0.28f)).border(3.dp, LocalSunlight.current.focus, MaterialTheme.shapes.extraSmall))
      SelectMark(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
    } else {
      ShareBadge(onShare, Modifier.align(Alignment.BottomEnd).testTag("share-badge"), painter = icons?.share, fileName = file.name)
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
  /**
   * Opens the video at a time (ms). With it, a video row that matched at a moment shows the strip of pictures around that moment
   * under its text (MomentStrip). Null, [compact] and [selecting] leave the strip out.
   */
  onOpenAt: ((Long) -> Unit)? = null,
  /** Current sort order, so the age / date metadata reflects Date modified vs Date created accurately. */
  sort: SortOrder = SortOrder.NEWEST,
  /** Dense metadata list row (List view mode): shows thumbnail/badge, folder, exact size, and Date Modified/Created. */
  listMode: Boolean = false,
) {
  val file = hit.file
  val haptics = LocalHapticFeedback.current
  val t = LocalSunlight.current
  val badge = locatorLabel(hit.locator)
  val folder = remember(file.relativePath) { file.relativePath.substringBeforeLast('/', "") }
  val age = relativeAge(file, sort)
  val pages = file.pageCount?.takeIf { it > 1 && file.kind == FileKind.PDF }
  val pagesText = pages?.let { pluralStringResource(R.plurals.pages_count, it, it) }
  val kindLabel = stringResource(file.kind.labelRes())
  val sizeText = remember(file.sizeBytes) { formatBytes(file.sizeBytes) }
  val modDateText = if (listMode && file.modifiedMillis > 0L) stringResource(R.string.label_modified_short, shortDateLabel(file.modifiedMillis)) else null
  val createdDateText = if (listMode && file.dateMillis > 0L) stringResource(R.string.label_created_short, shortDateLabel(file.dateMillis)) else null
  val subtitle = remember(listMode, folder, sizeText, sort, modDateText, createdDateText, file.modifiedMillis, file.dateMillis, file.kind, file.durationMs, kindLabel, age, pagesText) {
    if (listMode) {
      buildString {
        if (folder.isNotEmpty()) append(folder).append("  ·  ")
        append(sizeText)
        val createdPrimary = sort == SortOrder.CREATED_NEWEST || sort == SortOrder.CREATED_OLDEST
        if (createdPrimary) {
          if (createdDateText != null) append("  ·  ").append(createdDateText)
          if (modDateText != null && file.modifiedMillis != file.dateMillis) append("  ·  ").append(modDateText)
        } else {
          if (modDateText != null) append("  ·  ").append(modDateText)
          else if (createdDateText != null) append("  ·  ").append(createdDateText)
          if (createdDateText != null && modDateText != null && file.dateMillis != file.modifiedMillis) append("  ·  ").append(createdDateText)
        }
        val durationMs = file.durationMs
        if ((file.kind == FileKind.AUDIO || file.kind == FileKind.VIDEO) && durationMs != null) append("  ·  ").append(formatDuration(durationMs))
      }
    } else {
      buildString {
        append(kindLabel)
        if (folder.isNotEmpty()) append("  ·  ").append(folder)
        if (sort == SortOrder.LARGEST || sort == SortOrder.SMALLEST) append("  ·  ").append(sizeText)
        append("  ·  ").append(age)
        val durationMs = file.durationMs   // a local copy: the property lives in :core now, so it cannot be smart-cast
        if (file.kind == FileKind.AUDIO && durationMs != null) append("  ·  ").append(formatDuration(durationMs))
        if (pagesText != null) append("  ·  ").append(pagesText)
      }
    }
  }
  val blur = LocalPreviewBlur.current
  val hidden = blur.hides(file)
  val typeVisual = remember(file.name, file.kind, hidden) { if (hidden) null else FileTypeVisual.of(file.name, file.kind) }
  val showLabel = if (hidden) stringResource(R.string.action_show_picture) else ""
  val hiddenNote = if (hidden) stringResource(if (blur.checking(file)) R.string.preview_checking else R.string.preview_hidden) else ""
  val description = remember(file.name, kindLabel, listMode, sizeText, age, badge, hidden, hiddenNote, hit.snippet) {
    buildString {
      append(file.name).append(", ").append(kindLabel).append(", ").append(if (listMode) "$sizeText, $age" else age)   // the date, as the subtitle shows it
      badge?.let { append(", ").append(it) }
      if (hidden) append(", ").append(hiddenNote)
      hit.snippet?.let { append(". ").append(plainSnippet(it)) }
    }
  }
  // The matched words: bold, on the marker (a fill under ink, never a coloured word).
  val highlight = remember(t.marker, t.onMarker) { SpanStyle(fontWeight = FontWeight.Bold, background = t.marker, color = t.onMarker) }
  val highlightedText = remember(hit.snippet, highlight) { hit.snippet?.let { highlightedSnippet(it, highlight) } }
  val divider = t.line2

  // Share and Copy sit beside the clickable part, not inside it: the merged "open this file" node must not swallow them.
  // At a large font size (130% and up, UI audit item 12) they move under it, so the name keeps the width it needs instead of wrapping per word.
  val stacked = LocalDensity.current.fontScale >= 1.3f
  val thumbSize = if (listMode) 44.dp else Sun.Thumb
  val momentMs = (hit.locator as? Locator.Time)?.startMs
  val badgeModifier = if (momentMs != null && onOpenAt != null && !selecting && !hidden) {
    Modifier.clickable(
      role = Role.Button,
      onClickLabel = stringResource(R.string.strip_open_at_match, formatDuration(momentMs)),
    ) { onOpenAt(momentMs) }
  } else Modifier
  val openPart: @Composable (Modifier) -> Unit = { partModifier ->
    Row(
      partModifier
        .clip(MaterialTheme.shapes.small)
        .combinedClickable(
          onClickLabel = stringResource(if (selecting) R.string.action_select else if (hidden) R.string.action_show_picture else R.string.action_open),
          onClick = if (selecting) onToggle else if (hidden) { { blur.reveal(file); Unit } } else onOpen,
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
        .padding(start = 8.dp, top = if (compact || listMode) 4.dp else 8.dp, bottom = if (compact || listMode) 4.dp else 8.dp, end = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(Sun.S12),
      verticalAlignment = if (compact || listMode) Alignment.CenterVertically else Alignment.Top,
    ) {
      if (selecting) SelectMark(selected, Modifier.align(Alignment.CenterVertically), size = 28)
      if (file.kind.isVisual()) {
        FileThumbnail(
          file,
          Modifier
            .size(thumbSize)
            .clip(MaterialTheme.shapes.extraSmall)
            .then(if (hidden && !selecting) Modifier.clickable { blur.reveal(file) } else Modifier)
            .clearAndSetSemantics { },
          frameMs = (hit.locator as? Locator.Time)?.startMs,
          iconSize = if (listMode) 22.dp else 28.dp,
        )
      } else {
        ColorKindTile(
          fileName = file.name,
          kind = file.kind,
          modifier = Modifier.size(thumbSize).clearAndSetSemantics { },
          iconSize = if (listMode) 22.dp else 28.dp,
        )
      }
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact || listMode) 1.dp else 2.dp)) {
        if (stacked) {
          // At large accessibility text (>= 130%, UI audit item 12), keep badges below the file name so the name keeps the full column width.
          Text(
            file.name,
            style = if (listMode) SunType.Label else SunType.BodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact || listMode) 1 else 2,
            overflow = if (compact || listMode) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
          )
          if (typeVisual != null || badge != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              if (typeVisual != null) TypeBadge(typeVisual)
              if (badge != null) Pill(badge, modifier = badgeModifier, strong = true)
            }
          }
        } else {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              file.name,
              style = if (listMode) SunType.Label else SunType.BodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = if (compact || listMode) 1 else 2,
              overflow = if (compact || listMode) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (typeVisual != null) {
              TypeBadge(typeVisual)
            }
            if (badge != null) {
              Pill(badge, modifier = badgeModifier, strong = true)
            }
          }
        }
        Text(
          if (hidden) stringResource(if (blur.checking(file)) R.string.preview_checking_words else R.string.preview_hidden_words) else subtitle,
          style = if (compact || listMode) SunType.Caption else SunType.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = if (compact || listMode) 1 else 2,
          overflow = TextOverflow.Ellipsis,
        )
        highlightedText?.let { snippetText ->
          Text(
            snippetText,
            style = if (listMode) SunType.Caption else SunType.Body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (compact || listMode) 1 else 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
  // The strip of pictures around a video's matched moment sits under the whole row (it has taps of its own, so it is outside the
  // row's merged "open this file" node), aligned with the thumbnail's left edge.
  val stripOn = onOpenAt != null && momentMs != null && file.kind == FileKind.VIDEO && !compact && !selecting && stripMayShow(momentMs, file.durationMs)
  val stripFrames = rememberStripFrames(file, momentMs ?: 0L, hidden, stripOn)
  val strip: @Composable () -> Unit = {
    if (stripOn && stripFrames.takesRoom()) {
      MomentStrip(stripFrames, momentMs, hidden, onOpenAt, { blur.reveal(file) }, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
    }
  }
  // A plan that still finds a single picture (a video with very few key frames) takes the strip away: the row eases closed then,
  // instead of the rows below jumping (UI audit P7). Only rows that may have a strip carry the animation.
  val reduce = LocalReduceMotion.current
  val rowModifier = modifier
    .fillMaxWidth()
    .graphicsLayer()   // its own RenderNode: 120 Hz flings translate cached rows on GPU instead of re-recording every frame
    .then(if (stripOn) Modifier.animateContentSize(motionTween(reduce, Motion.Standard)) else Modifier)
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
      strip()
    }
  } else {
    Column(rowModifier) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        openPart(Modifier.weight(1f))
        RowActions(name = file.name, onShare = onShare, onCopy = onCopy)
      }
      strip()
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
    Text(text, style = SunType.CaptionBold, color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
  }
}
