package com.craneai.findr.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
  val wide = config.screenWidthDp >= 600
  val showBody = body != null && config.screenHeightDp >= 500
  val text: @Composable (Modifier) -> Unit = { m ->
    Column(m) {
      Text(
        title,
        style = SunType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { heading() },
      )
      if (showBody) Text(body.orEmpty(), style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
