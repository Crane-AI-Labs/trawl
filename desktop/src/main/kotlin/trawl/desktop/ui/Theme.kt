package trawl.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.SunlightDarkTokens
import com.craneai.findr.ui.theme.SunlightLightTokens
import com.craneai.findr.ui.theme.SunlightTokens
import com.craneai.findr.ui.theme.sunlightScheme
import trawl.desktop.ContrastChoice
import trawl.desktop.OsContrast
import trawl.desktop.Settings
import trawl.desktop.TextSize
import trawl.desktop.ThemeChoice

private fun resource(name: String): ByteArray =
  Sun::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: error("font $name is missing from the app")

/**
 * Atkinson Hyperlegible (Braille Institute, SIL OFL 1.1), the phone's own two files, bundled and never downloaded. It was drawn so that
 * b d p q, I l 1 and 0 O cannot be confused, which is what a file name needs.
 */
val Atkinson: FontFamily by lazy {
  FontFamily(
    Font("atkinson-regular", resource("atkinson_hyperlegible_regular.ttf"), FontWeight.Normal),
    Font("atkinson-bold", resource("atkinson_hyperlegible_bold.ttf"), FontWeight.Bold),
  )
}

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
  TextStyle(fontFamily = Atkinson, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

/**
 * Sunlight's styles at laptop scale: the phone's roles, drawn smaller because a screen at arm's length and a mouse need less than a thumb on a
 * pavement. Body is 14, Caption is 12 and nothing is smaller. Atkinson Hyperlegible stays: its open shapes are what keep 12 readable.
 */
object SunType {
  val Display get() = style(28, 34, FontWeight.Bold)
  val Title get() = style(22, 28, FontWeight.Bold)
  val Heading get() = style(17, 22, FontWeight.Bold)
  val BodyLarge get() = style(15, 22)
  val Body get() = style(14, 20)
  val Label get() = style(14, 18, FontWeight.Bold)
  val Caption get() = style(12, 16)
  val CaptionBold get() = style(12, 16, FontWeight.Bold)
}

/** Spacing and sizes: the phone's 4 dp grid, with desktop targets (a pointer is exact, so 36 dp is comfortable and 32 dp is a chip). */
object Sun {
  val Target = 36.dp
  val Border = 2.dp
  val Hairline = 1.dp
  val S2 = 2.dp
  val S4 = 4.dp
  val S6 = 6.dp
  val S8 = 8.dp
  val S12 = 12.dp
  val S16 = 16.dp
  val S20 = 20.dp
  val S24 = 24.dp
  val S32 = 32.dp
  val Field = 44.dp
  val Button = 36.dp
  val Chip = 32.dp
  val Thumb = 40.dp
  val Icon = 20.dp
  val IconSmall = 16.dp
}

/**
 * Tokens for a floating, borderless-window surface: the quick-search panel (ui/quick/QuickPanel.kt and ShellWindows.kt) takes its look from them.
 * They are the one place a panel gets its look, so it matches the main window; any later floating surface (a lock screen card, a menu) should use them too.
 * Use: window transparent, a [Corner]-rounded `paper` surface inset by [ShadowInset] so the [Elevation] shadow is not clipped, a
 * [Edge] hairline in `line2`, a search field of [Field] height, rows of [Row], text from [SunType] (title [SunType.Heading], subtitle
 * [SunType.Caption] in `ink2`, no "Documents" label), the marker only for the match, and the same [sunFocus] ring on every control.
 */
object SunPanel {
  val Corner = 14.dp
  val Edge = Sun.Hairline
  val Elevation = 20.dp
  /** The shadow's two lights, strong enough to read over a light desktop (the stock 12% / 25% black was faint at 24 dp). */
  val ShadowAmbient = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)
  val ShadowSpot = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.70f)
  /** The margin around the panel is painted this nearly-invisible black, so a click there lands on the window (a fully clear pixel lets the click through on Windows and the Mac) and closes it. */
  val MarginScrim = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.012f)
  val ShadowInset = 28.dp
  val Width = 680.dp
  val MaxHeight = 460.dp
  val Pad = Sun.S12
  val Row = 52.dp
}

private val SunlightShapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(10.dp),
  large = RoundedCornerShape(12.dp),
  extraLarge = RoundedCornerShape(16.dp),
)

private fun sunlightTypography() = Typography(
  displayLarge = SunType.Display, displayMedium = SunType.Display, displaySmall = SunType.Display,
  headlineLarge = SunType.Title, headlineMedium = SunType.Title, headlineSmall = SunType.Title,
  titleLarge = SunType.Heading, titleMedium = SunType.Heading, titleSmall = SunType.Label,
  bodyLarge = SunType.BodyLarge, bodyMedium = SunType.Body, bodySmall = SunType.Caption,
  labelLarge = SunType.Label, labelMedium = SunType.Label, labelSmall = SunType.CaptionBold,
)

/**
 * The high-contrast Sunlight: the same eleven tokens with the greys taken out. Text is pure black on white (or white on black), the quiet
 * secondary ink is nearly as strong, edges are the full ink and even the softest edge clears 4.5:1. Desktop only (the phone's tokens stay
 * as they are); it is used when the person picks High contrast or the computer asks for it (see OsContrast). Checked in ContrastTest.
 */
val SunlightHighContrastLight = SunlightTokens(
  paper = Color(0xFFFFFFFF), paper2 = Color(0xFFF2F2F2), ink = Color(0xFF000000), ink2 = Color(0xFF262626),
  line = Color(0xFF000000), line2 = Color(0xFF595959), marker = Color(0xFFFFD400), onMarker = Color(0xFF000000),
  action = Color(0xFF000000), onAction = Color(0xFFFFFFFF), danger = Color(0xFF8A0000), good = Color(0xFF0B5A24),
  focus = Color(0xFF000000),
)

val SunlightHighContrastDark = SunlightTokens(
  paper = Color(0xFF000000), paper2 = Color(0xFF141414), ink = Color(0xFFFFFFFF), ink2 = Color(0xFFF2F2F2),
  line = Color(0xFFFFFFFF), line2 = Color(0xFFA6A6A6), marker = Color(0xFFFFD400), onMarker = Color(0xFF000000),
  action = Color(0xFFFFFFFF), onAction = Color(0xFF000000), danger = Color(0xFFFFC4BD), good = Color(0xFF8FF0AE),
  focus = Color(0xFFFFD400),
)

/** True while the high-contrast colours are in use, for the few things (a hairline's softness) that are not a token. */
val LocalHighContrast = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The tokens for a theme and contrast, so a test and the window read the same table. */
fun sunlightTokens(dark: Boolean, highContrast: Boolean): SunlightTokens = when {
  highContrast && dark -> SunlightHighContrastDark
  highContrast -> SunlightHighContrastLight
  dark -> SunlightDarkTokens
  else -> SunlightLightTokens
}

/**
 * The window's theme: the phone's Sunlight tokens mapped onto Material 3, nothing else (no dynamic colour, no tonal elevation).
 *
 * [textSize] multiplies the font scale for everything inside (text only, never boxes), so one setting resizes the main window, Settings,
 * first run and every dialog. [contrast] picks the high-contrast tokens; [systemHighContrast] is what the computer said (OsContrast).
 */
@Composable
fun SunlightTheme(
  choice: ThemeChoice = ThemeChoice.SYSTEM,
  textSize: TextSize = TextSize.DEFAULT,
  contrast: ContrastChoice = ContrastChoice.STANDARD,
  systemHighContrast: Boolean = false,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val dark = when (choice) { ThemeChoice.SYSTEM -> systemDark; ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true }
  val high = when (contrast) { ContrastChoice.HIGH -> true; ContrastChoice.STANDARD -> false; ContrastChoice.SYSTEM -> systemHighContrast }
  val tokens: SunlightTokens = sunlightTokens(dark, high)
  val scheme = remember(dark, high) { sunlightScheme(tokens, dark) }
  val typography = remember { sunlightTypography() }
  val density = LocalDensity.current
  val scaled = remember(density.density, density.fontScale, textSize) { Density(density.density, density.fontScale * textSize.scale) }
  CompositionLocalProvider(LocalSunlight provides tokens, LocalHighContrast provides high, LocalDensity provides scaled) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = SunlightShapes, content = content)
  }
}

/** The theme as the person set it in Settings, with the computer's contrast answer read once per run. */
@Composable
fun SunlightTheme(settings: Settings, content: @Composable () -> Unit) {
  // Asked once per run, off the window's thread (on a Mac and on Linux it is a short command).
  val os by androidx.compose.runtime.produceState(false) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { OsContrast.active() } }
  SunlightTheme(settings.theme, settings.textSize, settings.contrast, os, content)
}

/** Borders, not elevation: a thing is separate because it has a 2 dp edge, not because it floats. */
fun Modifier.sunBorder(shape: Shape, color: Color, width: Dp = Sun.Border): Modifier = border(width, color, shape)
