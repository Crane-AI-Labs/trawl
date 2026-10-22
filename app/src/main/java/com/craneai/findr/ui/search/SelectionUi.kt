package com.craneai.findr.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunIconAction
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType

/** Most files handed to another app in one Share. Each is a content URI in one binder transaction (about 1 MB for all of them). */
const val MAX_SHARE = 200

/** The top of the screen while files are ticked: the cross, how many, and Select all. Replaces the wordmark line. */
@Composable
fun SelectionTopBar(count: Int, allSelected: Boolean, onSelectAll: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
  val exit = stringResource(R.string.select_exit)
  Row(
    modifier.fillMaxWidth().statusBarsPadding().padding(start = Sun.S4, end = Sun.S8, top = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SunIconAction(Icons.Rounded.Close, exit, exit, onExit)
    Text(
      pluralStringResource(R.plurals.select_count, count, count),
      style = SunType.Heading,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.weight(1f).padding(start = Sun.S8).semantics { heading(); liveRegion = LiveRegionMode.Polite },
    )
    SunTextButton(onClick = onSelectAll) { Text(stringResource(if (allSelected) R.string.select_none else R.string.select_all), style = SunType.Label) }
  }
}

/**
 * The bottom of the screen while files are ticked, where the search box usually is: Share, Copy, and (when they apply) Mark sensitive,
 * Not sensitive and, with one file ticked, More (the single-file sheet: Open, Show in folder). There is no delete: Trawl only reads.
 */
@Composable
fun SelectionActionBar(
  onShare: () -> Unit,
  onCopy: () -> Unit,
  onMark: (() -> Unit)?,
  onUnmark: (() -> Unit)?,
  onMore: (() -> Unit)?,
  modifier: Modifier = Modifier,
) {
  val line = LocalSunlight.current.line
  Row(
    modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background)
      .drawBehind { drawLine(line, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
      .navigationBarsPadding()
      .padding(horizontal = Sun.S8, vertical = Sun.S4),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val share = stringResource(R.string.action_share)
    val copy = stringResource(R.string.action_copy)
    SunIconAction(Icons.Outlined.Share, share, share, onShare)
    SunIconAction(Icons.Outlined.ContentCopy, copy, copy, onCopy)
    if (onMark != null) stringResource(R.string.action_mark_sensitive).let { SunIconAction(Icons.Outlined.VisibilityOff, it, it, onMark) }
    if (onUnmark != null) stringResource(R.string.action_unmark_sensitive).let { SunIconAction(Icons.Outlined.Visibility, it, it, onUnmark) }
    if (onMore != null) stringResource(R.string.action_more).let { SunIconAction(Icons.Outlined.MoreHoriz, it, it, onMore) }
  }
}

/** The tick on a thumbnail while selecting: an empty ring when not chosen, the marker with a check when chosen. */
@Composable
fun SelectMark(selected: Boolean, modifier: Modifier = Modifier, size: Int = 24) {
  val t = LocalSunlight.current
  Box(
    modifier
      .size(size.dp)
      .background(if (selected) t.marker else t.paper.copy(alpha = 0.85f), CircleShape)
      .border(2.dp, if (selected) t.onMarker else t.ink, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = t.onMarker, modifier = Modifier.size((size - 6).dp))
  }
}
