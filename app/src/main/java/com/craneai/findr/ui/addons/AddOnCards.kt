package com.craneai.findr.ui.addons

import com.craneai.findr.ui.common.appString
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackRow
import com.craneai.findr.core.PackStatus
import com.craneai.findr.ui.settings.SettingsDialog
import com.craneai.findr.ui.settings.SunErrorText
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunDialog
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunSwitch
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder
import com.craneai.findr.ui.theme.sunFocusRing

/** The icon for a pack. A new pack falls back to a generic one, so adding it to the catalog is enough. */
fun packIcon(id: String): ImageVector = when (id) {
  PackCatalog.SPEECH -> Icons.Rounded.Mic
  PackCatalog.QUICK_LOOK -> Icons.Rounded.Bolt
  PackCatalog.PRIVACY -> Icons.Rounded.VisibilityOff
  else -> Icons.Rounded.Extension
}

/** Settings → Add-ons: one card per pack, with its switch, Add and Remove. */
@Composable
fun AddOnCard(
  row: PackRow,
  onAdd: () -> Unit,
  onRemove: () -> Unit,
  onSwitch: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  SunCard(modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      PackHeader(row)
      PackDescription(row)
      Spacer(Modifier.height(Sun.S12))
      when (row.status) {
        PackStatus.COMING_SOON -> Text(
          stringResource(R.string.addons_soon_hint),
          style = SunType.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PackStatus.NOT_INSTALLED -> {
          PackProblemText(row)
          SunOutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.addons_add_with_size, row.def.sizeLabel()), style = SunType.Label)
          }
        }
        PackStatus.INSTALLING -> InstallProgress(row)
        PackStatus.INSTALLED -> {
          val shape = MaterialTheme.shapes.small
          val packTitle = row.def.title()
          Row(
            Modifier
              .fillMaxWidth()
              .sunFocusRing(shape)
              .clip(shape)
              .toggleable(value = row.on, role = Role.Switch, onValueChange = onSwitch)
              .heightIn(min = Sun.SettingsRow)
              .semantics { stateDescription = packTitle },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              stringResource(if (row.on) R.string.addons_on else R.string.addons_off),
              style = SunType.BodyLarge,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )
            SunSwitch(checked = row.on)
          }
          SunOutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().padding(top = Sun.S8)) {
            Text(stringResource(R.string.addons_remove, row.def.sizeLabel()), style = SunType.Label)
          }
        }
      }
    }
  }
}

/** Icon, name and a small status tag. */
@Composable
fun PackHeader(row: PackRow) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(packIcon(row.def.id), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S12))
    Text(row.def.title(), style = SunType.Heading, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(Sun.S8))
    StatusTag(row.status)
  }
  Spacer(Modifier.height(Sun.S8))
}

/** Added is the marker under ink with a tick; the other states are a plain edge. Always a word as well. */
@Composable
private fun StatusTag(status: PackStatus) {
  val t = LocalSunlight.current
  val label = when (status) {
    PackStatus.INSTALLED -> R.string.addons_status_added
    PackStatus.INSTALLING -> R.string.addons_status_adding
    PackStatus.NOT_INSTALLED -> R.string.addons_status_not_added
    PackStatus.COMING_SOON -> R.string.addons_status_soon
  }
  val added = status == PackStatus.INSTALLED
  val fg = if (added) t.onMarker else t.ink
  Row(
    Modifier
      .clip(CircleShape)
      .background(if (added) t.marker else t.paper)
      .sunBorder(CircleShape, color = if (added) t.onMarker else t.line)
      .padding(horizontal = Sun.S12, vertical = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (added) {
      Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(Sun.S4))
    }
    Text(stringResource(label), style = SunType.CaptionBold, color = fg)
  }
}

/** The benefit, the cost and the size: the same words in Settings and in onboarding. */
@Composable
fun PackDescription(row: PackRow) {
  // "Recommended if ..." is for a pack that is not in yet: once added, the advice has done its job.
  if (row.def.recommendedText().isNotEmpty() && row.status != PackStatus.INSTALLED) {
    Text(row.def.recommendedText(), style = SunType.Label, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(Sun.S8))
  }
  Text(row.def.benefitText(), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
  Spacer(Modifier.height(Sun.S8))
  Text(row.def.costText(), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Sun.S8))
  Text(stringResource(R.string.addons_size, row.def.sizeLabel()), style = SunType.CaptionBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A progress bar and the words for it: the fill is the marker under a 2 dp edge. */
@Composable
fun InstallProgress(row: PackRow) {
  val t = LocalSunlight.current
  val fraction = row.progress
  val label = if (fraction != null) stringResource(R.string.addons_adding_pct, (fraction * 100).toInt()) else stringResource(R.string.addons_adding)
  Text(label, style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
  Spacer(Modifier.height(Sun.S8))
  val bar = Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).sunBorder(CircleShape)
  if (fraction != null) {
    LinearProgressIndicator(progress = { fraction }, modifier = bar, color = t.marker, trackColor = t.paper, gapSize = 0.dp, drawStopIndicator = {})
  } else {
    LinearProgressIndicator(modifier = bar, color = t.marker, trackColor = t.paper, gapSize = 0.dp)
  }
}

/** Why the last try failed, in plain words, when it did: an icon and the words in the danger colour. */
@Composable
fun PackProblemText(row: PackRow) {
  val problem = row.problem ?: return
  val text = when (problem) {
    PackProblem.NOT_FOUND -> appString(R.string.addons_problem_not_found, row.def.archiveName)
    PackProblem.NEEDS_FILE_ACCESS -> appString(R.string.addons_problem_access)
    PackProblem.BAD_FILES -> appString(R.string.addons_problem_bad)
    PackProblem.UNAVAILABLE -> stringResource(R.string.addons_problem_unavailable)
  }
  SunErrorText(text, Modifier.padding(bottom = Sun.S12))
}

/** "Add": says where the file has to be, then installs it. [howTo] comes from the pack source, so Play can word it differently. */
@Composable
fun AddPackDialog(row: PackRow, howTo: PackCopy?, onInstall: () -> Unit, onDismiss: () -> Unit) {
  SettingsDialog(
    title = stringResource(R.string.addons_add_title, row.def.title()),
    confirmLabel = stringResource(R.string.addons_add_install),
    dismissLabel = stringResource(R.string.settings_cancel),
    onConfirm = onInstall,
    onDismiss = onDismiss,
  ) {
    Text(howTo.text(), style = SunType.Body)
    Text(stringResource(R.string.addons_size, row.def.sizeLabel()), style = SunType.CaptionBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
fun RemovePackDialog(row: PackRow, onRemove: () -> Unit, onDismiss: () -> Unit) {
  SunDialog(
    title = stringResource(R.string.addons_remove_title, row.def.title()),
    body = appString(R.string.addons_remove_body, row.def.sizeLabel()),
    confirmLabel = stringResource(R.string.addons_remove_confirm),
    dismissLabel = stringResource(R.string.settings_cancel),
    onConfirm = onRemove,
    onDismiss = onDismiss,
    danger = true,
  )
}

/** The tick shown once a pack is in: the "good" colour, with an icon and the word. */
@Composable
fun PackAdded() {
  val good = LocalSunlight.current.good
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = Sun.Target)) {
    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = good, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S8))
    Text(stringResource(R.string.onb4_added), style = SunType.Label, color = good)
  }
}
