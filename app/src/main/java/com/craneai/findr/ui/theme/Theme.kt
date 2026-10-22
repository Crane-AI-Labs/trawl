package com.craneai.findr.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.craneai.findr.core.FindrLog as Log

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Sunlight's corners: 8 dp tiles, 12 dp fields, cards, buttons and dialogs, 22 dp for the quick panel. Nothing else. */
internal val SunlightShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(12.dp),
  extraLarge = RoundedCornerShape(22.dp),
)

/** Light or dark for a mode, given what the system says. The in-app choice (Settings, Appearance) wins over the system. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
  ThemeMode.SYSTEM -> systemDark
  ThemeMode.LIGHT -> false
  ThemeMode.DARK -> true
}

/** The tokens for a palette and brightness: Sunlight's own, or the wallpaper's (Android 12+) held to Sunlight's contrast floors. */
@Composable
fun rememberTokens(palette: Palette, dark: Boolean): SunlightTokens {
  val context = LocalContext.current
  // A new wallpaper reaches the app as a configuration change, so the configuration keys the wallpaper scheme.
  val configuration = LocalConfiguration.current
  if (palette != Palette.WALLPAPER || !wallpaperColoursAvailable(Build.VERSION.SDK_INT)) {
    return if (dark) SunlightDarkTokens else SunlightLightTokens
  }
  val built = remember(dark, configuration) {
    wallpaperTokens(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context), dark)
  }
  LaunchedEffect(built) {
    // Colours and ratios only (nothing about the person's files): which pairs the wallpaper needed corrected, for the record.
    Log.i(
      "FindrTheme",
      "wallpaper palette (${if (dark) "dark" else "light"}): paper ${built.tokens.paper.hex()} marker ${built.tokens.marker.hex()} " +
        "action ${built.tokens.action.hex()}; ratios " + built.ratios.entries.joinToString { "${it.key} ${"%.2f".format(it.value)}" } +
        "; fallbacks: " + (built.fallbacks.joinToString("; ").ifEmpty { "none" }),
    )
  }
  return built.tokens
}

private fun Color.hex() = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * Sunlight, in warm paper or dark, or (Settings, Appearance) in the phone's wallpaper colours: the same roles, the same borders
 * and 56 dp targets, the same contrast floors. Light or dark follows [mode] in both. Every tappable thing gets at least 56 dp
 * (LocalMinimumInteractiveComponentSize), not Material's 48.
 */
@Composable
fun FindrTheme(
  mode: ThemeMode = ThemeMode.SYSTEM,
  palette: Palette = Palette.SUNLIGHT,
  content: @Composable () -> Unit,
) {
  val dark = mode.isDark(isSystemInDarkTheme())
  val tokens = rememberTokens(palette, dark)
  val scheme = remember(tokens, dark) {
    when (tokens) {
      SunlightLightTokens -> LightScheme
      SunlightDarkTokens -> DarkScheme
      else -> sunlightScheme(tokens, dark)
    }
  }
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window ?: return@SideEffect
      val controller = WindowCompat.getInsetsController(window, view)
      controller.isAppearanceLightStatusBars = !dark
      controller.isAppearanceLightNavigationBars = !dark
    }
  }
  // Selected text: the marker under ink, the handles in ink (the same rule as everywhere: yellow is a fill, never an edge).
  val selection = TextSelectionColors(handleColor = tokens.ink, backgroundColor = tokens.marker.copy(alpha = 0.55f))
  // 56 dp targets for real: Material sizes its controls to LocalMinimumInteractiveComponentSize, but a touch only reaches a
  // control's own (smaller) clickable inside that space up to the view configuration's minimum touch target, 48 dp by default.
  // Raising that to 56 dp makes every small clickable (icon buttons, chips) answer across the whole 56 dp.
  val baseConfig = LocalViewConfiguration.current
  val viewConfig = remember(baseConfig) { SunlightViewConfiguration(baseConfig) }
  CompositionLocalProvider(
    LocalReduceMotion provides rememberReduceMotion(),
    LocalSunlight provides tokens,
    LocalMinimumInteractiveComponentSize provides Sun.Target,
    LocalViewConfiguration provides viewConfig,
  ) {
    MaterialTheme(colorScheme = scheme, typography = FindrTypography, shapes = SunlightShapes) {
      CompositionLocalProvider(LocalTextSelectionColors provides selection, content = content)
    }
  }
}

/** The platform's view configuration with Sunlight's 56 dp minimum touch target. */
private class SunlightViewConfiguration(base: ViewConfiguration) : ViewConfiguration by base {
  override val minimumTouchTargetSize: DpSize get() = DpSize(Sun.Target, Sun.Target)
}
