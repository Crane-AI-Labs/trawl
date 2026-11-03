package com.craneai.findr.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.craneai.findr.R
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunType

/**
 * The short form of a Home card, for landscape, short screens and large text: one title line (or two), an action or two, and a way
 * out. Home shows one card at a time, and on a short or wide screen it must be a strip, not a page: the results under it keep
 * their room (RC QA on the tablet in landscape: the full "Remove the downloaded file?" card pushed the list off the screen).
 *
 * Wide (600 dp and up): title, actions and the dismiss control sit in one row. Narrower: the title and the dismiss control, then
 * the actions underneath. [body] is the one explaining sentence; it appears only when the screen is tall enough to spare the room.
 * [below] is for a progress bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeBanner(
  title: String,
  modifier: Modifier = Modifier,
  body: String? = null,
  dismissLabel: String? = null,
  onDismiss: (() -> Unit)? = null,
  color: Color = MaterialTheme.colorScheme.surfaceVariant,
  below: (@Composable () -> Unit)? = null,
  actions: @Composable () -> Unit = {},
) {
  val config = LocalConfiguration.current
  // At large text the words wrap in full: a clipped sentence lost the number a person needs ("Free up about 1.2 GB") and its second half.
  val large = config.fontScale >= 1.29f
  // One row only on a wide window at normal text: at large text the buttons took the whole row and left the title a letter a line
  // ("G / e / t" on a phone on its side at 2.0, 1.0.6 polish), so the actions go under the title there too.
  val wide = config.screenWidthDp >= 600 && !large
  val showBody = body != null && config.screenHeightDp >= 500
  val titleLines = if (large) Int.MAX_VALUE else 3
  val bodyLines = Int.MAX_VALUE   // the body only shows on a window 500 dp tall, which has the room; Luganda runs longer than English
  val text: @Composable (Modifier) -> Unit = { m ->
    Column(m) {
      Text(
        title,
        style = SunType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = titleLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() },
      )
      if (showBody) Text(body.orEmpty(), style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = bodyLines, overflow = TextOverflow.Ellipsis)
    }
  }
  val dismiss: @Composable () -> Unit = {
    if (onDismiss != null) {
      IconButton(onClick = onDismiss, modifier = Modifier.size(Sun.Target)) {
        Icon(Icons.Rounded.Close, contentDescription = dismissLabel, tint = MaterialTheme.colorScheme.onSurface)
      }
    }
  }
  SunCard(modifier.fillMaxWidth(), color = color) {
    Column(Modifier.padding(start = Sun.S16, end = if (onDismiss != null) Sun.S4 else Sun.S16, top = Sun.S8, bottom = Sun.S8)) {
      if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          text(Modifier.weight(1f).padding(vertical = Sun.S4))
          actions()
          dismiss()
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          text(Modifier.weight(1f).padding(vertical = Sun.S4))
          dismiss()
        }
        FlowRow(
          modifier = Modifier.padding(end = if (onDismiss != null) Sun.S12 else 0.dp),
          horizontalArrangement = Arrangement.spacedBy(Sun.S8),
          verticalArrangement = Arrangement.spacedBy(Sun.S4),
        ) { actions() }
      }
      below?.let { Column(Modifier.padding(top = Sun.S4, end = if (onDismiss != null) Sun.S12 else 0.dp)) { it() } }
    }
  }
}

/**
 * A Home strip folded to its title while the person is searching: one line (two at large text) and a chevron; a tap opens the strip
 * where it stands. At large text the full strip (title, sentence, two buttons) above the meter, the note and the chips pushed every
 * answer below the fold (UI audit P3, D16).
 */
@Composable
fun HomeBannerLine(title: String, onOpen: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
  val large = LocalConfiguration.current.fontScale >= 1.29f
  SunCard(modifier.fillMaxWidth(), color = color) {
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = stringResource(R.string.action_open), role = Role.Button, onClick = onOpen)
        .defaultMinSize(minHeight = Sun.Target)
        .padding(start = Sun.S16, end = Sun.S8, top = Sun.S4, bottom = Sun.S4),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        title,
        style = SunType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (large) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).semantics { heading() },
      )
      Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = Sun.S8).size(Sun.Icon))
    }
  }
}
