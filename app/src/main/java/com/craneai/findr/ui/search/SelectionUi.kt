package com.craneai.findr.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.SelectAll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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
  val countText = pluralStringResource(R.plurals.select_count, count, count)
  val toggle = stringResource(if (allSelected) R.string.select_none else R.string.select_all)
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  BoxWithConstraints(modifier.fillMaxWidth().statusBarsPadding().padding(start = Sun.S4, end = Sun.S8, top = Sun.S4)) {
    // The word goes when it would not fit beside the whole count: in Luganda at normal text "Ggyawo byonna ebirondeddwa" (Select none)
    // cut the count to "Fa…" (1.0.6 polish). Measured, so a short translation keeps its word.
    val wordFits = with(density) {
      val countPx = measurer.measure(countText, SunType.Heading, maxLines = 1).size.width
      val wordPx = measurer.measure(toggle, SunType.Label, maxLines = 1).size.width + TEXT_BUTTON_PADDING.roundToPx()
      countPx + wordPx <= (maxWidth - Sun.Target - Sun.S8).roundToPx()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // One row at any font size (UI audit item 2): the cross is the icon alone (its description names it), the count takes the room
      // that is left on one line, and Select all becomes an icon at large text instead of a word that squeezes the count.
      SunIconAction(Icons.Rounded.Close, exit, exit, onExit, showWord = false)
      Text(
        countText,
        style = SunType.Heading,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(start = Sun.S8).semantics { heading(); liveRegion = LiveRegionMode.Polite },
      )
      if (density.fontScale >= 1.3f || !wordFits) {
        SunIconAction(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, toggle, toggle, onSelectAll, showWord = false)
      } else {
        SunTextButton(onClick = onSelectAll) { Text(toggle, style = SunType.Label, maxLines = 1) }
      }
    }
  }
}

/** SunTextButton's own side padding (16 dp each side), plus a little air so the count is never cut by a pixel. */
private val TEXT_BUTTON_PADDING = 40.dp

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
    // Equal shares of the width (UI audit item 10): SpaceEvenly let the last action, More, run off the edge at large text.
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val share = stringResource(R.string.action_share)
    val copy = stringResource(R.string.action_copy)
    SunIconAction(Icons.Outlined.Share, share, share, onShare, Modifier.weight(1f), wordLines = 2)
    SunIconAction(Icons.Outlined.ContentCopy, copy, copy, onCopy, Modifier.weight(1f), wordLines = 2)
    if (onMark != null) stringResource(R.string.action_mark_sensitive).let { SunIconAction(Icons.Outlined.VisibilityOff, it, it, onMark, Modifier.weight(1f), wordLines = 2) }
    if (onUnmark != null) stringResource(R.string.action_unmark_sensitive).let { SunIconAction(Icons.Outlined.Visibility, it, it, onUnmark, Modifier.weight(1f), wordLines = 2) }
    if (onMore != null) stringResource(R.string.action_more).let { SunIconAction(Icons.Outlined.MoreHoriz, it, it, onMore, Modifier.weight(1f), wordLines = 2) }
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
