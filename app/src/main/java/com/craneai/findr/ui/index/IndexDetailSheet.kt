package com.craneai.findr.ui.index

import com.craneai.findr.engine.Edition
import com.craneai.findr.ui.common.appString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.craneai.findr.R
import com.craneai.findr.ui.background.BackgroundAccess
import com.craneai.findr.ui.keeplearning.KeepLearning
import com.craneai.findr.ui.keeplearning.KeepLearningActivity
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.common.fraction
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder
import kotlin.math.roundToInt

/** Everything the engine reports about indexing, in plain language, with the controls that fit the situation. */
class MeterActions(
  val onPause: () -> Unit,
  val onResume: () -> Unit,
  val onReadOnBattery: () -> Unit,
  val onAllowAccess: () -> Unit,
  val onFreeSpace: () -> Unit,
  val onRetry: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexDetailSheet(
  status: IndexStatus,
  settings: FindrSettings,
  actions: MeterActions,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
  ) {
    Column(
      Modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Sun.Gutter)
        .padding(bottom = Sun.S16)
        .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(Sun.S16),
    ) {
      SheetHeader(status)
      KeepLearningOffer(status, onStarted = onDismiss)   // high up: on a Transsion phone it is the way to get through the backlog, so it must not hide below the fold
      LevelsBlock(status)
      KindBlock(status)
      StatsBlock(status, settings)
      status.currentFile?.let { CurrentFile(it) }
      if (status.tooLargeFiles > 0) {
        Text(
          pluralStringResource(R.plurals.sheet_too_large, status.tooLargeFiles, status.tooLargeFiles),
          style = SunType.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      val otherUnreadable = status.filesUnreadable - status.tooLargeFiles   // the too-large ones have their own line above
      if (otherUnreadable > 0) {
        Text(
          pluralStringResource(R.plurals.sheet_unreadable, otherUnreadable, otherUnreadable),
          style = SunType.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      status.lastError?.let { ErrorNote(it) }
      Controls(status, actions)
    }
  }
}

@Composable
private fun SheetHeader(status: IndexStatus) {
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S4)) {
    Text(stringResource(R.string.sheet_title), style = SunType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(status.headline(), style = SunType.Title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
    val pauseReason = status.pauseReason   // a local copy: the property lives in :core now, so it cannot be smart-cast
    val detail = when {
      status.phase == IndexPhase.PAUSED && pauseReason != null -> appString(pauseReason.detailRes)
      status.phase == IndexPhase.UP_TO_DATE -> appString(R.string.sheet_detail_up_to_date)
      status.phase == IndexPhase.SCANNING -> appString(R.string.sheet_detail_scanning)
      status.waitingForModels -> stringResource(R.string.sheet_detail_needs_models)
      status.phase == IndexPhase.INDEXING && status.needsCharger ->
        stringResource(if (status.lightOnBattery) R.string.sheet_detail_light_on_battery else R.string.sheet_detail_needs_charger)
      status.phase == IndexPhase.INDEXING -> stringResource(R.string.sheet_detail_indexing)
      status.phase == IndexPhase.ERROR && status.openProblem != null -> stringResource(R.string.open_problem_detail)
      status.phase == IndexPhase.ERROR -> stringResource(R.string.sheet_detail_error)
      else -> appString(R.string.sheet_detail_not_started)
    }
    Text(detail, style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** The two levels of "ready", side by side, so nobody mistakes searchable-by-name for understood-by-meaning. */
@Composable
private fun LevelsBlock(status: IndexStatus) {
  SunCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S16)) {
      LevelRow(
        label = stringResource(R.string.level_searchable),
        hint = stringResource(R.string.level_searchable_hint),
        done = status.filesSearchable,
        total = status.filesKnown,
        fraction = status.searchableFraction,
        pale = true,
      )
      status.quickLook?.takeIf { status.showQuickLook }?.let { q ->
        LevelRow(
          label = stringResource(R.string.level_quicklook),
          hint = stringResource(R.string.level_quicklook_hint),
          done = q.ready,
          total = q.known,
          fraction = fraction(q.ready, q.known),
          pale = true,
        )
      }
      LevelRow(
        label = stringResource(R.string.level_understood),
        hint = stringResource(R.string.level_understood_hint),
        done = status.filesUnderstood,
        total = status.filesReadable,
        fraction = status.understoodFraction,
        pale = false,
      )
    }
  }
}

/** The meter's bar: the marker as a fill under a 2 dp edge on a paper track. Searchable is the marker at 45%, understood the full marker. */
@Composable
private fun MeterBar(searchable: Float, understood: Float, height: androidx.compose.ui.unit.Dp) {
  val t = LocalSunlight.current
  TwoLevelBar(
    searchable = searchable,
    understood = understood,
    modifier = Modifier.clip(CircleShape).sunBorder(CircleShape),
    height = height,
    searchableColor = t.marker.copy(alpha = 0.45f),
    understoodColor = t.marker,
    trackColor = t.paper,
  )
}

@Composable
private fun LevelRow(label: String, hint: String, done: Int, total: Int, fraction: Float, pale: Boolean) {
  val summary = stringResource(R.string.level_a11y, label, formatCount(done), formatCount(total))
  Column(Modifier.semantics(mergeDescendants = true) { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = SunType.Label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(end = Sun.S12))
      Text(
        stringResource(R.string.count_of, formatCount(done), formatCount(total)),
        style = SunType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    MeterBar(searchable = if (pale) fraction else 0f, understood = if (pale) 0f else fraction, height = 14.dp)
    Text(hint, style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun KindBlock(status: IndexStatus) {
  val rows = status.kindRows()
  if (rows.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    Text(stringResource(R.string.sheet_by_kind), style = SunType.Heading, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
    rows.forEach { row ->
      val label = stringResource(row.label)
      val desc = stringResource(R.string.level_a11y, label, formatCount(row.understood), formatCount(row.known))
      Column(Modifier.semantics(mergeDescendants = true) { contentDescription = desc }, verticalArrangement = Arrangement.spacedBy(Sun.S4)) {
        Row(Modifier.fillMaxWidth()) {
          Text(label, style = SunType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(end = Sun.S12))
          Text(
            stringResource(R.string.count_of, formatCount(row.understood), formatCount(row.known)),
            style = SunType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        MeterBar(searchable = 0f, understood = row.fraction, height = 12.dp)
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsBlock(status: IndexStatus, settings: FindrSettings) {
  val eta = etaLabel(status.etaSeconds)?.let {
    if (settings.onlyWhileCharging || !settings.fullOnBattery) stringResource(R.string.eta_while_charging, it) else it
  } ?: stringResource(if (status.needsCharger) R.string.stat_when_charging else R.string.stat_estimating)
  val rate = status.ratePerHour?.let { stringResource(R.string.stat_rate_value, formatCount(it.roundToInt())) }
    ?: stringResource(R.string.stat_measuring)
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(Sun.S12),
    verticalArrangement = Arrangement.spacedBy(Sun.S12),
    maxItemsInEachRow = 2,
  ) {
    StatTile(stringResource(R.string.stat_time_left), if (status.phase == IndexPhase.UP_TO_DATE) stringResource(R.string.stat_done) else eta, Modifier.weight(1f))
    StatTile(stringResource(R.string.stat_speed), rate, Modifier.weight(1f))
    StatTile(stringResource(R.string.stat_index_size), formatBytes(status.indexBytes), Modifier.weight(1f))
    if (Edition.MODELS) StatTile(stringResource(R.string.stat_model_size), formatBytes(status.modelBytes), Modifier.weight(1f))
  }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
  SunCard(modifier) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Sun.S12, vertical = Sun.S12).semantics(mergeDescendants = true) { }) {
      Text(label, style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S4))
      Text(value, style = SunType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
  }
}

@Composable
private fun CurrentFile(name: String) {
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S4)) {
    Text(stringResource(R.string.sheet_reading_now), style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A file name may be long: it ellipsizes in the middle so the end (the extension) stays.
    Text(name, style = SunType.Body, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
  }
}

/** An error is an icon, the word "Problem" and the message in the danger colour, on paper-2 inside an edge: never colour alone. */
@Composable
private fun ErrorNote(message: String) {
  val danger = MaterialTheme.colorScheme.error
  val shape = MaterialTheme.shapes.medium
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(LocalSunlight.current.paper2)
      .sunBorder(shape)
      .padding(Sun.S16),
    verticalAlignment = Alignment.Top,
  ) {
    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = danger, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S12))
    Column(Modifier.weight(1f)) {
      Text(stringResource(R.string.sheet_error_word), style = SunType.Label, color = danger)
      Text(message, style = SunType.Body, color = danger)
    }
  }
}

/**
 * "Keep learning now" (ui/keeplearning): on TECNO, Infinix and itel phones, which pause apps with the screen off, and on any phone
 * with a large backlog. The primary action while indexing; beside "Read on battery anyway" it is the quieter one.
 */
@Composable
private fun KeepLearningOffer(status: IndexStatus, onStarted: () -> Unit) {
  val context = LocalContext.current
  val transsion = remember { BackgroundAccess.appliesHere() }
  if (!KeepLearning.offered(status, transsion)) return
  // The sheet closes behind it, so coming back lands on search, not on the sheet.
  val open = { context.startActivity(Intent(context, KeepLearningActivity::class.java)); onStarted() }
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    if (status.phase == IndexPhase.PAUSED) {
      SunOutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_action), style = SunType.Label) }
    } else {
      SunButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_action), style = SunType.Label) }
    }
    Text(appString(R.string.keep_action_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** The buttons that fit the situation: pause while working, the right way out of each wait, retry after an error. */
@Composable
private fun Controls(status: IndexStatus, actions: MeterActions) {
  val reason = status.pauseReason
  val full = Modifier.fillMaxWidth()
  when (status.phase) {
    IndexPhase.SCANNING, IndexPhase.INDEXING -> SunOutlinedButton(onClick = actions.onPause, modifier = full) {
      Icon(Icons.Rounded.Pause, contentDescription = null)
      Spacer(Modifier.width(Sun.S8))
      Text(stringResource(R.string.action_pause), style = SunType.Label)
    }
    IndexPhase.PAUSED -> when (reason?.nextStep() ?: NextStep.RESUME) {
      NextStep.RESUME -> SunButton(onClick = actions.onResume, modifier = full) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(Sun.S8))
        Text(stringResource(R.string.action_resume), style = SunType.Label)
      }
      NextStep.READ_ON_BATTERY -> SunButton(onClick = actions.onReadOnBattery, modifier = full) {
        Text(stringResource(R.string.action_read_on_battery), style = SunType.Label)
      }
      NextStep.ALLOW_ACCESS -> SunButton(onClick = actions.onAllowAccess, modifier = full) {
        Text(stringResource(R.string.action_allow_access), style = SunType.Label)
      }
      NextStep.FREE_SPACE -> SunButton(onClick = actions.onFreeSpace, modifier = full) {
        Text(stringResource(R.string.action_free_space), style = SunType.Label)
      }
      NextStep.NONE -> Text(
        stringResource(R.string.sheet_resumes_by_itself),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IndexPhase.ERROR -> SunButton(onClick = actions.onRetry, modifier = full) {
      Text(stringResource(R.string.action_retry), style = SunType.Label)
    }
    IndexPhase.UP_TO_DATE, IndexPhase.NOT_STARTED -> Unit
  }
}
