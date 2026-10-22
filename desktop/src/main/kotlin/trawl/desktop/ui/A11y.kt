package trawl.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight

/**
 * Accessibility building blocks for the desktop shell (audit rows H2 and H4). One rule behind them: whatever can be clicked can be
 * reached with Tab, shows a visible focus ring, is activated by Enter or Space, and has a name a screen reader can say.
 *
 * What Compose Desktop gives for free: `clickable` is focusable and Enter/Space activate it; the semantics tree is exposed to the
 * platform accessibility bridge (Java Access Bridge on Windows for NVDA/JAWS, NSAccessibility on macOS for VoiceOver). What it does not
 * give: a name for an icon or an image, and a focus ring (the Sunlight ring is [sunFocus]). These helpers add those two.
 */

/** A name for a screen reader, on something that is only a picture. */
fun Modifier.a11yLabel(text: String): Modifier = semantics { contentDescription = text }

/** A clickable that is reachable by Tab, rings when focused, says [label] and activates on Enter and Space. */
fun Modifier.a11yClickable(label: String, shape: Shape = RoundedCornerShape(12.dp), onClick: () -> Unit): Modifier =
  pointerHoverIcon(PointerIcon.Hand)
    .sunFocus(shape)
    .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
    .semantics { contentDescription = label }

/**
 * The row of a menu that the arrow keys are on. A paper-2 tint alone is about 1.1:1 against the menu's paper, so a keyboard user could not see
 * where they were; this adds the same 3 dp focus ring every other control has. Shown only while the keyboard was the last thing used (a mouse
 * opening the menu has no "current row" to show).
 */
fun Modifier.menuActiveRing(active: Boolean, shape: Shape): Modifier = composed {
  val tk = LocalSunlight.current
  if (active && InputModality.keyboard) Modifier.border(3.dp, tk.focus, shape) else Modifier
}

/** A heading, so a screen reader can jump between sections. */
fun Modifier.a11yHeading(): Modifier = semantics { heading() }

/** Text that changes by itself (a result count) and should be read out when it does, without taking focus. */
fun Modifier.a11yLive(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** A 48 dp icon-only button: always named, always rings on focus. [label] is both the spoken name and the click description. */
@Composable
fun A11yIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Box(modifier.size(Sun.Target).a11yClickable(label, RoundedCornerShape(24.dp), onClick), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))   // the Box carries the name; the glyph is decoration
  }
}
