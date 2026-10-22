package com.craneai.findr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Sunlight (the design notes): warm paper, one ink, and one colour, marker yellow, used only as a fill under ink.
 * Eleven tokens per theme plus the two "on" partners; every Material role below is one of them, so nothing in the app can
 * pick up a stray Material purple or a wallpaper colour. Contrast is checked in SunlightThemeTest against the page's grades.
 */
@Immutable
data class SunlightTokens(
  /** The ground. Warm, not white: less glare in sun, less halo at night. */
  val paper: Color,
  /** Fields, cards, the meter. One step off the ground; no shadows. */
  val paper2: Color,
  /** All text and icons. */
  val ink: Color,
  /** Secondary text: paths, dates, hints. Still AAA on paper. */
  val ink2: Color,
  /** 2 dp borders on fields, tiles, chips and switches. Same as ink in light, softened in dark. */
  val line: Color,
  /** Dividers between rows (non-text, 3:1 is the bar). */
  val line2: Color,
  /** The one colour: selected, on, the meter fill. Always a fill under ink, never an edge on paper and never text. */
  val marker: Color,
  val onMarker: Color,
  /** Primary button fill: ink in light, paper-coloured ink in dark. */
  val action: Color,
  val onAction: Color,
  /** Delete and errors, always with an icon and a word. */
  val danger: Color,
  /** Added, done, allowed, always with an icon and a word. */
  val good: Color,
  /** Keyboard and TalkBack focus ring, 3 dp. */
  val focus: Color,
)

val SunlightLightTokens = SunlightTokens(
  paper = Color(0xFFFFFBF2), paper2 = Color(0xFFF6F0E2), ink = Color(0xFF111111), ink2 = Color(0xFF3D3A33),
  line = Color(0xFF111111), line2 = Color(0xFF8C867A), marker = Color(0xFFFFD400), onMarker = Color(0xFF111111),
  action = Color(0xFF111111), onAction = Color(0xFFFFFBF2), danger = Color(0xFF9B1C14), good = Color(0xFF146B37),
  focus = Color(0xFF111111),
)

val SunlightDarkTokens = SunlightTokens(
  paper = Color(0xFF15130E), paper2 = Color(0xFF211E17), ink = Color(0xFFF4EFE4), ink2 = Color(0xFFC8C1B2),
  line = Color(0xFFC8C1B2), line2 = Color(0xFF6E685C), marker = Color(0xFFFFD400), onMarker = Color(0xFF111111),
  action = Color(0xFFF4EFE4), onAction = Color(0xFF15130E), danger = Color(0xFFFFB4AB), good = Color(0xFF7FD69F),
  focus = Color(0xFFFFD400),
)

/** The tokens behind the current scheme, for what Material has no role for (focus, good, the marker under ink). */
val LocalSunlight = staticCompositionLocalOf { SunlightLightTokens }

/** Every Material 3 role mapped to a Sunlight token (the page's "How to implement in Compose" table). */
fun sunlightScheme(t: SunlightTokens, dark: Boolean): ColorScheme {
  val base = if (dark) darkColorScheme() else lightColorScheme()
  return base.copy(
    primary = t.action, onPrimary = t.onAction,
    primaryContainer = t.marker, onPrimaryContainer = t.onMarker, inversePrimary = t.marker,
    secondary = t.ink, onSecondary = t.paper,
    secondaryContainer = t.paper2, onSecondaryContainer = t.ink,
    tertiary = t.good, onTertiary = t.paper, tertiaryContainer = t.paper2, onTertiaryContainer = t.ink,
    background = t.paper, onBackground = t.ink, surface = t.paper, onSurface = t.ink,
    surfaceVariant = t.paper2, onSurfaceVariant = t.ink2,
    surfaceTint = Color.Transparent,   // no tonal elevation anywhere: borders, not shadows
    inverseSurface = t.ink, inverseOnSurface = t.paper,
    error = t.danger, onError = t.paper, errorContainer = t.paper2, onErrorContainer = t.danger,
    outline = t.line, outlineVariant = t.line2, scrim = Color.Black,
    surfaceBright = t.paper, surfaceDim = t.paper2,
    surfaceContainerLowest = t.paper, surfaceContainerLow = t.paper,
    surfaceContainer = t.paper2, surfaceContainerHigh = t.paper2, surfaceContainerHighest = t.paper2,
    primaryFixed = t.marker, primaryFixedDim = t.marker, onPrimaryFixed = t.onMarker, onPrimaryFixedVariant = t.onMarker,
    secondaryFixed = t.paper2, secondaryFixedDim = t.paper2, onSecondaryFixed = t.ink, onSecondaryFixedVariant = t.ink2,
    tertiaryFixed = t.paper2, tertiaryFixedDim = t.paper2, onTertiaryFixed = t.ink, onTertiaryFixedVariant = t.ink2,
  )
}

internal val LightScheme: ColorScheme = sunlightScheme(SunlightLightTokens, dark = false)
internal val DarkScheme: ColorScheme = sunlightScheme(SunlightDarkTokens, dark = true)

/** Behind the quick panel and under blurred previews: the scheme's scrim at 55%. */
const val SCRIM_ALPHA = 0.55f

val ColorScheme.isDark: Boolean get() = background.luminance() < 0.5f
