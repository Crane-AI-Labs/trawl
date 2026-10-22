package com.craneai.findr.ui.index

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SdCardAlert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason
import com.craneai.findr.ui.theme.LocalReduceMotion
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Motion
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.motionTween
import com.craneai.findr.ui.theme.sunFocusRing

/**
 * The meter on Home: one sentence that says what works now, a bar, and one line of numbers; tap for details. The sentence
 * changes with the state, its place never does. Paper-2 with a 2 dp edge, no shadow. While the person searches it shrinks to
 * one line, so results keep the room.
 */
@Composable
fun IndexMeterCard(
  status: IndexStatus,
  compact: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val reduce = LocalReduceMotion.current
  val t = LocalSunlight.current
  val title = status.headline()
  val sub = status.subline()
  val percent = status.percentUnderstood()
  val done = status.phase == IndexPhase.UP_TO_DATE
  val showBar = status.phase != IndexPhase.NOT_STARTED && !done
  val quick = status.quickLookLine()
  val description = stringResource(R.string.meter_a11y, title, sub, percent).let { base -> quick?.let { stringResource(R.string.meter_a11y_extra, base, it) } ?: base }
  val openLabel = stringResource(R.string.meter_open_details)
  val shape = MaterialTheme.shapes.medium

  Column(
    modifier
      .fillMaxWidth()
      .sunFocusRing(shape)
      .clip(shape)
      .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
      .background(t.paper2)
      .border(Sun.Border, t.line, shape)
      .semantics(mergeDescendants = true) { contentDescription = description }
      .animateContentSize(motionTween(reduce, Motion.Standard))
      .defaultMinSize(minHeight = Sun.Target)
      .padding(horizontal = Sun.S16, vertical = if (compact) Sun.S8 else Sun.S12),
    verticalArrangement = Arrangement.Center,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(status.meterIcon(), contentDescription = null, tint = status.meterTint(), modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(Sun.S8))
      Text(
        title,
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = t.ink,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (showBar && status.filesKnown > 0) {
        Text("$percent%", style = SunType.Label, color = t.ink, modifier = Modifier.padding(start = Sun.S8))
      }
      Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = t.ink,
        modifier = Modifier.padding(start = 2.dp).size(Sun.Icon),
      )
    }
    if (showBar) {
      Spacer(Modifier.height(if (compact) Sun.S4 else Sun.S8))
      TwoLevelBar(
        searchable = status.searchableFraction,
        understood = status.understoodFraction,
        height = if (compact) 6.dp else 12.dp,
      )
    }
    if (!compact) {
      Spacer(Modifier.height(Sun.S4))
      Text(sub, style = SunType.Body, color = t.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
      if (quick != null) Text(quick, style = SunType.Caption, color = t.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** One icon per state, beside the sentence: never colour alone. */
private fun IndexStatus.meterIcon(): ImageVector = when (phase) {
  IndexPhase.UP_TO_DATE -> Icons.Rounded.CheckCircle
  IndexPhase.ERROR -> if (openProblem != null) Icons.Rounded.HourglassEmpty else Icons.Rounded.ErrorOutline   // an index that will open by itself is a wait, not a fault
  IndexPhase.SCANNING -> Icons.Rounded.Search
  IndexPhase.NOT_STARTED -> Icons.Rounded.HourglassEmpty
  IndexPhase.PAUSED -> when (pauseReason) {
    PauseReason.NOT_CHARGING -> Icons.Rounded.BatteryChargingFull
    PauseReason.BATTERY_LOW -> Icons.Rounded.BatteryAlert
    PauseReason.TOO_HOT -> Icons.Rounded.Thermostat
    PauseReason.NO_PERMISSION -> Icons.Rounded.FolderOff
    PauseReason.STORAGE_LOW -> Icons.Rounded.SdCardAlert
    PauseReason.USER, null -> Icons.Rounded.Pause
  }
  IndexPhase.INDEXING -> if (needsCharger && !lightOnBattery) Icons.Rounded.BatteryChargingFull else Icons.Rounded.Bolt
}

@Composable
private fun IndexStatus.meterTint(): Color {
  val t = LocalSunlight.current
  return when (phase) {
    IndexPhase.UP_TO_DATE -> t.good
    IndexPhase.ERROR -> if (openProblem != null) t.ink else t.danger
    else -> t.ink
  }
}
