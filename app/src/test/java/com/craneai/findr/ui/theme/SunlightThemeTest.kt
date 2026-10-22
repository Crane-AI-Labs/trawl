package com.craneai.findr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Sunlight's tokens reach every Material role as the design page maps them (the design notes, "How to implement in
 * Compose"), and the pairs the page grades keep their WCAG contrast. Computed here from the same hex values, so a token change
 * that breaks a grade fails the build instead of shipping.
 */
class SunlightThemeTest {
  private val themes = listOf(
    Triple("light", SunlightLightTokens, LightScheme),
    Triple("dark", SunlightDarkTokens, DarkScheme),
  )

  // WCAG 2 relative luminance and contrast ratio, exactly as the page computes them.
  private fun lum(c: Color): Double {
    val argb = c.toArgb()
    fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
  }

  private fun ratio(a: Color, b: Color): Double {
    val (hi, lo) = lum(a).let { la -> lum(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
    return (hi + 0.05) / (lo + 0.05)
  }

  @Test fun everyMaterialRoleIsASunlightToken() {
    for ((name, t, s) in themes) {
      fun eq(role: String, expected: Color, actual: Color) = assertEquals("$name $role", expected, actual)
      eq("primary (action)", t.action, s.primary)
      eq("onPrimary", t.onAction, s.onPrimary)
      eq("primaryContainer (marker)", t.marker, s.primaryContainer)
      eq("onPrimaryContainer", t.onMarker, s.onPrimaryContainer)
      eq("secondary (ink)", t.ink, s.secondary)
      eq("onSecondary", t.paper, s.onSecondary)
      eq("secondaryContainer (paper-2)", t.paper2, s.secondaryContainer)
      eq("onSecondaryContainer", t.ink, s.onSecondaryContainer)
      eq("tertiary (good)", t.good, s.tertiary)
      eq("error (danger)", t.danger, s.error)
      eq("onError", t.paper, s.onError)
      eq("background (paper)", t.paper, s.background)
      eq("onBackground (ink)", t.ink, s.onBackground)
      eq("surface (paper)", t.paper, s.surface)
      eq("onSurface (ink)", t.ink, s.onSurface)
      eq("surfaceVariant (paper-2)", t.paper2, s.surfaceVariant)
      eq("onSurfaceVariant (ink-2)", t.ink2, s.onSurfaceVariant)
      eq("outline (line)", t.line, s.outline)
      eq("outlineVariant (line-2)", t.line2, s.outlineVariant)
      eq("surfaceTint: no tonal elevation", Color.Transparent, s.surfaceTint)
      eq("inverseSurface", t.ink, s.inverseSurface)
      eq("inverseOnSurface", t.paper, s.inverseOnSurface)
    }
  }

  /** No Material default (purple, teal, grey) is left anywhere in the scheme: every colour is one of the theme's tokens. */
  @Test fun noRoleKeepsAMaterialDefault() {
    for ((name, t, s) in themes) {
      val allowed = setOf(t.paper, t.paper2, t.ink, t.ink2, t.line, t.line2, t.marker, t.onMarker, t.action, t.onAction, t.danger, t.good,
        Color.Transparent, Color.Black)
      for ((role, c) in roles(s)) assertTrue("$name $role = $c is not a Sunlight token", c in allowed)
    }
  }

  private fun roles(s: ColorScheme): List<Pair<String, Color>> = listOf(
    "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
    "inversePrimary" to s.inversePrimary, "secondary" to s.secondary, "onSecondary" to s.onSecondary,
    "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer, "tertiary" to s.tertiary,
    "onTertiary" to s.onTertiary, "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
    "background" to s.background, "onBackground" to s.onBackground, "surface" to s.surface, "onSurface" to s.onSurface,
    "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant, "surfaceTint" to s.surfaceTint,
    "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface, "error" to s.error, "onError" to s.onError,
    "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer, "outline" to s.outline,
    "outlineVariant" to s.outlineVariant, "scrim" to s.scrim, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
    "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
    "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceContainerLow" to s.surfaceContainerLow,
    "surfaceContainerLowest" to s.surfaceContainerLowest, "primaryFixed" to s.primaryFixed, "primaryFixedDim" to s.primaryFixedDim,
    "onPrimaryFixed" to s.onPrimaryFixed, "onPrimaryFixedVariant" to s.onPrimaryFixedVariant, "secondaryFixed" to s.secondaryFixed,
    "secondaryFixedDim" to s.secondaryFixedDim, "onSecondaryFixed" to s.onSecondaryFixed,
    "onSecondaryFixedVariant" to s.onSecondaryFixedVariant, "tertiaryFixed" to s.tertiaryFixed, "tertiaryFixedDim" to s.tertiaryFixedDim,
    "onTertiaryFixed" to s.onTertiaryFixed, "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
  )

  /** The page's token table: text pairs at AAA (7:1) or AA (4.5:1) as graded there, non-text pairs at 3:1. */
  @Test fun contrastMeetsThePageGrades() {
    for ((name, t, _) in themes) {
      fun atLeast(pair: String, a: Color, b: Color, min: Double) {
        val r = ratio(a, b)
        assertTrue("$name $pair is ${"%.2f".format(r)}:1, needs $min:1", r >= min)
      }
      atLeast("ink on paper", t.ink, t.paper, 7.0)
      atLeast("ink on paper-2", t.ink, t.paper2, 7.0)
      atLeast("ink-2 on paper", t.ink2, t.paper, 7.0)
      atLeast("ink-2 on paper-2 (hints in cards)", t.ink2, t.paper2, 7.0)
      atLeast("ink on marker", t.onMarker, t.marker, 7.0)
      atLeast("button text on action", t.onAction, t.action, 7.0)
      atLeast("danger on paper", t.danger, t.paper, 4.5)
      atLeast("danger on paper-2 (error notes in cards)", t.danger, t.paper2, 4.5)
      atLeast("good on paper", t.good, t.paper, 4.5)
      atLeast("line on paper (2 dp borders)", t.line, t.paper, 3.0)
      atLeast("line on paper-2 (borders of cards)", t.line, t.paper2, 3.0)
      atLeast("line-2 on paper (dividers)", t.line2, t.paper, 3.0)
      atLeast("focus ring on paper", t.focus, t.paper, 3.0)
    }
  }

  /** Why the marker is a fill and never an edge or text on paper: it fails 3:1 against the ground. */
  @Test fun markerOnPaperFailsSoItIsOnlyEverAFill() {
    assertTrue(ratio(SunlightLightTokens.marker, SunlightLightTokens.paper) < 3.0)
    // In light the ink edge carries the shape (ink on paper is far above 3:1); in dark the marker against the ground is high
    // enough to be the focus ring (the page: 13:1).
    assertTrue(ratio(SunlightDarkTokens.marker, SunlightDarkTokens.paper) > 7.0)
  }

  @Test fun typeIsAtkinsonInTwoWeightsAndNeverBelow14sp() {
    val styles: List<Pair<String, TextStyle>> = with(FindrTypography) {
      listOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
      )
    }
    for ((role, style) in styles) {
      assertEquals("$role font", Atkinson, style.fontFamily)
      assertTrue("$role is ${style.fontSize}", style.fontSize.isSp && style.fontSize.value >= 14f)
      assertTrue("$role line height is sp", style.lineHeight.isSp)
      assertTrue("$role weight ${style.fontWeight}", style.fontWeight == FontWeight.Normal || style.fontWeight == FontWeight.Bold)
    }
    // The page's seven sizes.
    assertEquals(listOf(32f, 24f, 20f, 18f, 16f, 16f, 14f),
      listOf(SunType.Display, SunType.Title, SunType.Heading, SunType.BodyLarge, SunType.Body, SunType.Label, SunType.Caption).map { it.fontSize.value })
  }

  @Test fun targetsAndCornersFollowThePage() {
    assertEquals(56f, Sun.Target.value)
    assertEquals(2f, Sun.Border.value)
    assertTrue(Sun.Field.value >= Sun.Target.value && Sun.Button.value >= Sun.Target.value)
    assertTrue(Sun.Chip.value >= 44f && Sun.FilterTile.value >= Sun.Target.value)
  }

  @Test fun theInAppChoiceWinsOverTheSystem() {
    assertEquals(true, ThemeMode.SYSTEM.isDark(systemDark = true))
    assertEquals(false, ThemeMode.SYSTEM.isDark(systemDark = false))
    assertEquals(false, ThemeMode.LIGHT.isDark(systemDark = true))
    assertEquals(true, ThemeMode.DARK.isDark(systemDark = false))
  }
}
