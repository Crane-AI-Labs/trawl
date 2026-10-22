package trawl.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import trawl.desktop.t
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import com.craneai.findr.ui.theme.LocalSunlight

/** A keyboard focus ring: 3 dp, ink in light and marker in dark, on every clickable we draw, buttons included (Material only tints a focused button). */
fun Modifier.sunFocus(shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
  var focused by remember { mutableStateOf(false) }
  val color = LocalSunlight.current.focus
  // Like :focus-visible: a click that lands focus on a button does not draw the ring; Tab and the arrow keys do.
  Modifier.onFocusChanged { focused = it.isFocused && InputModality.keyboard }.then(if (focused) Modifier.border(3.dp, color, shape) else Modifier)
}

/**
 * A hover tint under the pointer: paper-2 over paper, a step darker where the thing is already paper-2. Applied before the clip/click so the
 * whole shape lights up. [selected] keeps the stronger fill of a chosen row from being washed out.
 */
fun Modifier.sunHover(shape: androidx.compose.ui.graphics.Shape, base: Color? = null): Modifier = composed {
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val tk = LocalSunlight.current
  val fill = if (hovered) (base ?: tk.paper2) else Color.Transparent
  Modifier.hoverable(source).background(fill, shape)
}

/** The one primary action of a screen: ink fill, 36 dp, 8 dp corners. */
@Composable
fun SunButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
  Button(
    onClick = onClick, modifier = modifier.heightIn(min = Sun.Button).sunFocus(MaterialTheme.shapes.small).pointerHoverIcon(PointerIcon.Hand), enabled = enabled,
    shape = MaterialTheme.shapes.small, elevation = null, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), content = content,
  )
}

/** Secondary: an outline in ink (or the danger colour). */
@Composable
fun SunOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, content: @Composable RowScope.() -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val edge = if (danger) scheme.error else scheme.outline
  OutlinedButton(
    onClick = onClick, modifier = modifier.heightIn(min = Sun.Button).sunFocus(MaterialTheme.shapes.small).pointerHoverIcon(PointerIcon.Hand), enabled = enabled,
    shape = MaterialTheme.shapes.small, border = BorderStroke(Sun.Border, if (enabled) edge else edge.copy(alpha = 0.38f)),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) scheme.error else scheme.onSurface),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), content = content,
  )
}

/** Back, Not now, Cancel: words only. */
@Composable
fun SunTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
  TextButton(
    onClick = onClick, modifier = modifier.heightIn(min = Sun.Target).sunFocus(MaterialTheme.shapes.small).pointerHoverIcon(PointerIcon.Hand), enabled = enabled, shape = MaterialTheme.shapes.small,
    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), content = content,
  )
}

/** A card: paper-2 with a 2 dp edge, 12 dp corners, never a shadow. */
@Composable
fun SunCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable () -> Unit) {
  Surface(
    modifier = modifier, shape = MaterialTheme.shapes.medium, color = color, contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 0.dp, shadowElevation = 0.dp, border = BorderStroke(Sun.Border, MaterialTheme.colorScheme.outline), content = content,
  )
}

/** A small label: "PDF", "p. 2", "Words". Outlined, never colour alone. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, marker: Boolean = false) {
  val tk = LocalSunlight.current
  Text(
    text, style = SunType.CaptionBold, color = if (marker) tk.onMarker else tk.ink, maxLines = 1,
    modifier = modifier.clip(CircleShape).background(if (marker) tk.marker else tk.paper)
      .border(1.dp, if (marker) tk.onMarker else tk.line2, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
  )
}

/** One filter chip: icon and word; selected is the marker under ink with the ink edge kept, so it survives greyscale. */
@Composable
fun FilterChip(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val fg = if (selected) tk.onMarker else tk.ink
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  Row(
    modifier.sunFocus(CircleShape).clip(CircleShape).pointerHoverIcon(PointerIcon.Hand).hoverable(source)
      .selectable(selected = selected, role = Role.RadioButton, interactionSource = source, indication = null, onClick = onClick)
      .background(if (selected) tk.marker else if (hovered) tk.paper2 else tk.paper).border(Sun.Border, if (selected) tk.onMarker else tk.line, CircleShape)
      .heightIn(min = Sun.Chip).padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(Sun.IconSmall))
      Spacer(Modifier.width(6.dp))
    }
    Text(label, style = SunType.Label, color = fg, maxLines = 1)
  }
}

/** A checkbox row that says Yes or No in words as well as colour. */
@Composable
fun CheckRow(label: String, detail: String?, checked: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(8.dp)
  Row(
    modifier.fillMaxWidth().sunFocus(shape).sunHover(shape).clip(shape).pointerHoverIcon(PointerIcon.Hand)
      .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
      .heightIn(min = Sun.Target).padding(horizontal = 4.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(if (checked) tk.marker else tk.paper)
        .border(Sun.Border, if (enabled) (if (checked) tk.onMarker else tk.line) else tk.line2, RoundedCornerShape(5.dp)),
      contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Icons.Check, contentDescription = null, tint = tk.onMarker, modifier = Modifier.size(16.dp)) }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(label, style = SunType.Body, color = if (enabled) tk.ink else tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (detail != null) Text(detail, style = SunType.Caption, color = tk.ink2)
    }
  }
}

/** The meter's bar: marker fill under an ink edge, so "how far" is a length and not only a colour. [fraction] null shows a quarter-bar sliver (unknown). */
@Composable
fun SunProgress(fraction: Float?, modifier: Modifier = Modifier, thickness: androidx.compose.ui.unit.Dp = 12.dp) {
  val tk = LocalSunlight.current
  // A screen reader says "45 percent", or "in progress" when the length is not known; the bar is not only a picture.
  val range = if (fraction == null) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
  Box(modifier.fillMaxWidth().height(thickness).semantics { progressBarRangeInfo = range }.clip(CircleShape).background(tk.paper).border(Sun.Border, tk.line, CircleShape)) {
    val f = (fraction ?: 0.04f).coerceIn(0.04f, 1f)
    Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(tk.marker))
  }
}

/** A dialog is a question with two answers: the safe one is the text button. */
@Composable
fun SunDialog(title: String, body: String, confirmLabel: String, dismissLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
  val scheme = MaterialTheme.colorScheme
  AlertDialog(
    onDismissRequest = onDismiss, modifier = Modifier.sunBorder(MaterialTheme.shapes.medium, scheme.outline).widthIn(max = 520.dp).semantics { paneTitle = title }, shape = MaterialTheme.shapes.medium,
    containerColor = scheme.background, titleContentColor = scheme.onBackground, textContentColor = scheme.onBackground, tonalElevation = 0.dp,
    title = { Text(title, style = SunType.Heading, modifier = Modifier.a11yHeading()) },
    text = { Text(body, style = SunType.Body) },
    confirmButton = {
      if (danger) Button(onClick = onConfirm, modifier = Modifier.heightIn(min = Sun.Target), shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)) { Text(confirmLabel) }
      else SunButton(onClick = onConfirm) { Text(confirmLabel) }
    },
    dismissButton = { SunTextButton(onClick = onDismiss) { Text(dismissLabel) } },
  )
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
  Text(text, style = SunType.Heading, color = MaterialTheme.colorScheme.onBackground, modifier = modifier.fillMaxWidth().padding(top = Sun.S20, bottom = Sun.S8).a11yHeading())
}

/** A line that carries a warning or an error: the icon and the words, never colour alone. */
@Composable
fun Notice(text: String, bad: Boolean, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  // One spoken line, read out when it appears (a live region), and "Problem" first when it is a warning: the icon is the only other thing that says so.
  Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
    Icon(if (bad) Icons.Warning else Icons.Check, contentDescription = if (bad) t("speak_problem") else null, tint = if (bad) tk.danger else tk.good, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = SunType.Body, color = if (bad) tk.danger else tk.good)
  }
}

/**
 * A small icon-only button: a 36 dp target, a hover tint, a focus ring and a spoken [label] (an icon alone says nothing to a screen reader,
 * so the label is required, not optional).
 */
@Composable
fun SunIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(8.dp)
  Box(
    modifier.size(Sun.Target).sunFocus(shape).sunHover(shape).clip(shape).pointerHoverIcon(PointerIcon.Hand)
      .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) { Icon(icon, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon)) }
}

/** A keyboard shortcut drawn as a key cap ("Ctrl K"): quiet, outlined, never the loudest thing on screen. */
@Composable
fun KeyCap(text: String, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(4.dp)
  // On a Mac the words become the symbols on its keyboard (see Keys); a screen reader always hears the words.
  Text(
    Keys.cap(text, MainUi.isMac), style = SunType.CaptionBold, color = tk.ink2, maxLines = 1,
    modifier = modifier.clip(shape).background(tk.paper).border(Sun.Hairline, tk.line2, shape).padding(horizontal = 5.dp, vertical = 1.dp)
      .semantics { contentDescription = Keys.spoken(text) },
  )
}

/** A hairline between rows and sections. */
@Composable
fun SunDivider(modifier: Modifier = Modifier) {
  // A hairline is decoration, so it is soft; in high contrast it is the full edge colour.
  val soft = if (LocalHighContrast.current) 1f else 0.55f
  androidx.compose.material3.HorizontalDivider(modifier, thickness = Sun.Hairline, color = LocalSunlight.current.line2.copy(alpha = soft))
}
