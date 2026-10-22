package com.craneai.findr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/** Which colours the app wears: Sunlight's own (the default), or the phone's wallpaper colours (Android 12+). */
enum class Palette { SUNLIGHT, WALLPAPER }

/** Wallpaper colours exist from Android 12 (API 31); below that only Sunlight is offered. */
fun wallpaperColoursAvailable(sdk: Int): Boolean = sdk >= 31

/** WCAG 2 contrast ratio of two opaque colours. */
fun contrast(a: Color, b: Color): Double {
  fun lum(c: Color): Double {
    val argb = c.toArgb()
    fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
  }
  val (x, y) = lum(a) to lum(b)
  return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

/** The tokens built from a wallpaper scheme, and which pairs had to be corrected to keep their contrast. */
class WallpaperTokens(val tokens: SunlightTokens, val fallbacks: List<String>, val ratios: Map<String, Double>)

/**
 * Sunlight's roles on the wallpaper's colours: paper is the scheme's surface, paper-2 its raised container, ink its on-surface,
 * the marker its primary container (so the selected filter tile and the meter fill follow the wallpaper), the action its primary.
 * Borders stay borders (the outline), never shadows. The meaning colours stay Sunlight's own, so "Added" is green whatever the wallpaper.
 *
 * Every pair the screens use is then held to Sunlight's floor, whatever the wallpaper does: text 4.5:1, borders, dividers and the
 * focus ring 3:1. A pair below it has its foreground moved toward black (light) or white (dark), a step at a time, until it
 * passes; the name of the pair goes into [WallpaperTokens.fallbacks] (and the log) so it can be seen.
 */
fun wallpaperTokens(scheme: ColorScheme, dark: Boolean): WallpaperTokens {
  val sun = if (dark) SunlightDarkTokens else SunlightLightTokens
  val fixes = ArrayList<String>()
  val ratios = LinkedHashMap<String, Double>()
  val paper = scheme.surface
  val paper2 = scheme.surfaceContainerHigh

  fun guard(name: String, fg: Color, bgs: List<Color>, min: Double): Color {
    // Toward whichever of black and white the backgrounds leave more room for (the text's own side of the scale).
    val toward = if (dark) Color.White else Color.Black
    var c = fg
    var step = 0
    while (bgs.any { contrast(c, it) < min } && step < 20) {
      step++
      c = lerp(fg, toward, step / 20f)
    }
    if (step > 0) fixes += "$name ${"%.2f".format(bgs.minOf { contrast(fg, it) })} -> ${"%.2f".format(bgs.minOf { contrast(c, it) })}"
    ratios[name] = bgs.minOf { contrast(c, it) }
    return c
  }

  fun guardOn(name: String, fg: Color, bg: Color, min: Double): Color {
    // Text on a fill (the marker, the action): move the text toward black or white, whichever the fill contrasts with more.
    val toward = if (contrast(Color.Black, bg) >= contrast(Color.White, bg)) Color.Black else Color.White
    var c = fg
    var step = 0
    while (contrast(c, bg) < min && step < 20) {
      step++
      c = lerp(fg, toward, step / 20f)
    }
    if (step > 0) fixes += "$name ${"%.2f".format(contrast(fg, bg))} -> ${"%.2f".format(contrast(c, bg))}"
    ratios[name] = contrast(c, bg)
    return c
  }

  val ink = guard("ink on paper", scheme.onSurface, listOf(paper, paper2), min = TEXT)
  val ink2 = guard("ink-2 on paper", scheme.onSurfaceVariant, listOf(paper, paper2), min = TEXT)
  val line = guard("line on paper", scheme.outline, listOf(paper, paper2), min = NON_TEXT)
  val line2 = guard("line-2 on paper", scheme.outlineVariant, listOf(paper), min = NON_TEXT)
  val marker = scheme.primaryContainer
  val onMarker = guardOn("ink on marker", scheme.onPrimaryContainer, marker, min = TEXT)
  val action = scheme.primary
  val onAction = guardOn("text on action", scheme.onPrimary, action, min = TEXT)
  val danger = guard("danger on paper", scheme.error, listOf(paper, paper2), min = TEXT)
  val good = guard("good on paper", sun.good, listOf(paper, paper2), min = TEXT)
  val focus = guard("focus on paper", scheme.primary, listOf(paper), min = NON_TEXT)
  val tokens = SunlightTokens(
    paper = paper, paper2 = paper2, ink = ink, ink2 = ink2, line = line, line2 = line2, marker = marker, onMarker = onMarker,
    action = action, onAction = onAction, danger = danger, good = good, focus = focus,
  )
  return WallpaperTokens(tokens, fixes, ratios)
}

/** Sunlight's floors: body text 4.5:1 (WCAG AA), non-text (borders, dividers, the focus ring) 3:1. */
const val TEXT = 4.5
const val NON_TEXT = 3.0
