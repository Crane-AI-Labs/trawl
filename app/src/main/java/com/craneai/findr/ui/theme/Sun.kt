package com.craneai.findr.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.R

/**
 * Sunlight's spacing and sizes (the design notes, "Spacing and shape"). A 4 dp grid; 56 dp for every tappable thing
 * (48 is the floor, not the target); no fixed heights for anything holding text, only minimums, so 200% font size never clips.
 */
object Sun {
  val Target = 56.dp
  val Border = 2.dp
  val FocusRing = 3.dp
  val Gutter = 16.dp
  val S4 = 4.dp
  val S8 = 8.dp
  val S12 = 12.dp
  val S16 = 16.dp
  val S24 = 24.dp
  val S32 = 32.dp
  val Row = 72.dp
  val SettingsRow = 64.dp
  val Field = 60.dp
  val Button = 60.dp
  val FilterTile = 64.dp
  val Chip = 44.dp
  val Thumb = 56.dp
  val Icon = 24.dp
}

/** Borders, not elevation: a thing is separate because it has a 2 dp edge, not because it floats. */
fun Modifier.sunBorder(shape: Shape, color: Color? = null, width: Dp = Sun.Border): Modifier = composed {
  border(width, color ?: MaterialTheme.colorScheme.outline, shape)
}

/** The 3 dp focus ring (ink in light, marker in dark) for our own clickables; Material's controls draw their own. */
fun Modifier.sunFocusRing(shape: Shape): Modifier = composed {
  var focused by remember { mutableStateOf(false) }
  val color = LocalSunlight.current.focus
  onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(Sun.FocusRing, color, shape) else Modifier)
}

/** The one primary action of a screen: ink (light) or paper-ink (dark) fill, 60 dp, 12 dp corners. */
@Composable
fun SunButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable RowScope.() -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = Sun.Button),
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    elevation = null,
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content = content,
  )
}

/** Secondary: an outline in ink (or the danger colour), 60 dp like the primary, so the two line up side by side. */
@Composable
fun SunOutlinedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  danger: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val edge = if (danger) scheme.error else scheme.outline
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = Sun.Button),
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    border = BorderStroke(Sun.Border, if (enabled) edge else edge.copy(alpha = 0.38f)),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) scheme.error else scheme.onSurface),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content = content,
  )
}

/** Back, Not now, Cancel: words only, ink, 56 dp. */
@Composable
fun SunTextButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  danger: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  TextButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = Sun.Target),
    enabled = enabled,
    shape = MaterialTheme.shapes.small,
    colors = ButtonDefaults.textButtonColors(contentColor = if (danger) scheme.error else scheme.onSurface),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content = content,
  )
}

/** A card: paper-2 with a 2 dp edge, 12 dp corners, never a shadow. */
@Composable
fun SunCard(
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.surfaceVariant,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    color = color,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border = BorderStroke(Sun.Border, MaterialTheme.colorScheme.outline),
    content = content,
  )
}

/**
 * A switch that says On or Off inside its track, so colour is never the only signal. On is the marker under an ink thumb, inside
 * an ink edge. Drawn only: the row it sits in carries the toggle and its semantics (Role.Switch), unless [onCheckedChange] is set.
 */
@Composable
fun SunSwitch(
  checked: Boolean,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onCheckedChange: ((Boolean) -> Unit)? = null,
) {
  val t = LocalSunlight.current
  val word = stringResource(if (checked) R.string.switch_on else R.string.switch_off)
  val alpha = if (enabled) 1f else 0.45f
  val track = if (checked) t.marker else t.paper
  val edge = if (checked) t.onMarker else t.line
  val thumb = if (checked) t.onMarker else t.line
  val textColor = if (checked) t.onMarker else t.ink
  val interactive = if (onCheckedChange != null) {
    Modifier.sizeIn(minWidth = Sun.Target, minHeight = Sun.Target)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
  } else Modifier.clearAndSetSemantics { }
  Box(modifier.then(interactive), contentAlignment = Alignment.Center) {
    Row(
      Modifier
        .heightIn(min = 32.dp)
        .widthIn(min = 68.dp)
        .clip(CircleShape)
        .background(track.copy(alpha = if (enabled) 1f else 0.6f))
        .border(Sun.Border, edge.copy(alpha = alpha), CircleShape)
        .padding(horizontal = 4.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      if (!checked) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(thumb.copy(alpha = alpha)))
        Spacer(Modifier.width(6.dp))
      } else Spacer(Modifier.width(6.dp))
      Text(word, style = SunType.CaptionBold, color = textColor.copy(alpha = alpha), maxLines = 1)
      if (checked) {
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(22.dp).clip(CircleShape).background(thumb.copy(alpha = alpha)))
      } else Spacer(Modifier.width(6.dp))
    }
  }
}

/**
 * One filter: its icon and its word. Selected is the marker fill under ink with the ink edge kept, so selection survives greyscale
 * and sunlight; TalkBack hears "selected" (Role.RadioButton). [compact] is the one-line form used while results are showing.
 */
@Composable
fun FilterTile(
  label: String,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  val t = LocalSunlight.current
  val shape = if (compact) CircleShape else MaterialTheme.shapes.extraSmall
  val fg = if (selected) t.onMarker else t.ink
  // The compact chip is 44 dp to see and 56 dp to touch; a tile is already 64 dp.
  val base = modifier
    .then(if (compact) Modifier.minimumInteractiveComponentSize() else Modifier)
    .sunFocusRing(shape)
    .clip(shape)
    .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    .background(if (selected) t.marker else t.paper)
    .border(Sun.Border, if (selected) t.onMarker else t.line, shape)
  if (compact) {
    Row(
      base.heightIn(min = Sun.Chip).padding(horizontal = 14.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(6.dp))
      Text(label, style = SunType.Label, color = fg, maxLines = 1)
    }
  } else {
    Column(
      base.heightIn(min = Sun.FilterTile).padding(horizontal = 6.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(Sun.Icon))
      Spacer(Modifier.size(2.dp))
      Text(label, style = SunType.Label, color = fg, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** An icon button with its word under it ("Share", "Copy"): 56 dp, and TalkBack hears [description] (which names the file). */
@Composable
fun SunIconAction(
  icon: ImageVector,
  word: String,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  tint: Color = MaterialTheme.colorScheme.onSurface,
) {
  Column(
    modifier
      .sizeIn(minWidth = Sun.Target, minHeight = Sun.Target)
      .clip(MaterialTheme.shapes.small)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { contentDescription = description }
      .padding(horizontal = 4.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sun.Icon))
    // The word is for the eye; TalkBack already hears [description], which names the file too.
    Text(word, style = SunType.CaptionBold, color = tint, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
  }
}

/** Dialogs are rare and always a question with two answers: the safe one is the text button, the action is the primary. */
@Composable
fun SunDialog(
  title: String,
  body: String,
  confirmLabel: String,
  dismissLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  danger: Boolean = false,
) {
  val scheme = MaterialTheme.colorScheme
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.sunBorder(MaterialTheme.shapes.medium),
    shape = MaterialTheme.shapes.medium,
    containerColor = scheme.background,
    titleContentColor = scheme.onBackground,
    textContentColor = scheme.onBackground,
    tonalElevation = 0.dp,
    title = { Text(title, style = SunType.Heading) },
    text = { Text(body, style = SunType.Body) },
    confirmButton = {
      if (danger) {
        Button(
          onClick = onConfirm,
          modifier = Modifier.heightIn(min = Sun.Target),
          shape = MaterialTheme.shapes.small,
          colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
        ) { Text(confirmLabel) }
      } else {
        SunButton(onClick = onConfirm) { Text(confirmLabel) }
      }
    },
    dismissButton = { SunTextButton(onClick = onDismiss) { Text(dismissLabel) } },
  )
}

/** A section heading (Settings, the meter sheet): Heading style, marked as a heading for TalkBack. */
@Composable
fun SunSectionHeading(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = SunType.Heading,
    color = MaterialTheme.colorScheme.onBackground,
    modifier = modifier.fillMaxWidth().padding(top = Sun.S24, bottom = Sun.S8).semantics { heading() },
  )
}
