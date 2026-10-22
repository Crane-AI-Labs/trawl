package com.craneai.findr.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wallpaper colours keep Sunlight's floors whatever the wallpaper does: every text pair 4.5:1, borders, dividers and the focus
 * ring 3:1. A pair that passes is used as the wallpaper gives it; one that fails is corrected and named. The schemes below stand
 * in for Material You's (which need a phone): the phone's real ones are logged by FindrTheme and checked in the build notes.
 */
class WallpaperPaletteTest {
  /** A tidy Material-style light scheme from a dark-blue wallpaper: nothing should need correcting. */
  private val blueLight = lightColorScheme(
    primary = Color(0xFF3F5F90), onPrimary = Color.White, primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF001B3C),
    surface = Color(0xFFF9F9FF), onSurface = Color(0xFF191C20), onSurfaceVariant = Color(0xFF43474E), surfaceContainerHigh = Color(0xFFE7E8EE),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6CF), error = Color(0xFFBA1A1A),
  )

  /**
   * Adversarial: a bright-yellow wallpaper read badly. White on a yellow primary, a pale outline, a soft secondary text, dividers
   * that vanish, a marker whose text is mid-grey. Every one of these must be pulled up to the floor.
   */
  private val yellowBad = lightColorScheme(
    primary = Color(0xFFFFD60A), onPrimary = Color.White, primaryContainer = Color(0xFFFFE680), onPrimaryContainer = Color(0xFF9A8A40),
    surface = Color(0xFFFFFBEF), onSurface = Color(0xFF3A3A30), onSurfaceVariant = Color(0xFF9A9480), surfaceContainerHigh = Color(0xFFF2EDD8),
    outline = Color(0xFFD8D2B8), outlineVariant = Color(0xFFEDE8D0), error = Color(0xFFFF6B6B),
  )

  /** Adversarial in the dark: a green wallpaper with dim text and a dark primary. */
  private val greenDarkBad = darkColorScheme(
    primary = Color(0xFF2E5E32), onPrimary = Color(0xFF3A6A3A), primaryContainer = Color(0xFF1F4A24), onPrimaryContainer = Color(0xFF3F6F44),
    surface = Color(0xFF101410), onSurface = Color(0xFF7A8A7A), onSurfaceVariant = Color(0xFF4A564A), surfaceContainerHigh = Color(0xFF1E241E),
    outline = Color(0xFF2A322A), outlineVariant = Color(0xFF1E261E), error = Color(0xFF8A2A2A),
  )

  private fun assertFloors(name: String, w: WallpaperTokens) {
    val t = w.tokens
    fun at(pair: String, fg: Color, bg: Color, min: Double) {
      val r = contrast(fg, bg)
      assertTrue("$name: $pair is ${"%.2f".format(r)}:1, needs $min:1 (fallbacks ${w.fallbacks})", r >= min - 1e-9)
    }
    at("ink on paper", t.ink, t.paper, TEXT)
    at("ink on paper-2", t.ink, t.paper2, TEXT)
    at("ink-2 on paper", t.ink2, t.paper, TEXT)
    at("ink-2 on paper-2", t.ink2, t.paper2, TEXT)
    at("ink on marker", t.onMarker, t.marker, TEXT)
    at("text on action", t.onAction, t.action, TEXT)
    at("danger on paper", t.danger, t.paper, TEXT)
    at("good on paper", t.good, t.paper, TEXT)
    at("line on paper", t.line, t.paper, NON_TEXT)
    at("line on paper-2", t.line, t.paper2, NON_TEXT)
    at("line-2 on paper", t.line2, t.paper, NON_TEXT)
    at("focus on paper", t.focus, t.paper, NON_TEXT)
  }

  @Test fun aTidyWallpaperIsUsedAsItIs() {
    val w = wallpaperTokens(blueLight, dark = false)
    assertFloors("blue light", w)
    // Only the dividers (Material's outlineVariant is a hairline tone, under 3:1 by design) are lifted.
    assertTrue(w.fallbacks.toString(), w.fallbacks.all { it.startsWith("line-2") })
    assertEquals(blueLight.primaryContainer, w.tokens.marker)       // the selected tile is the wallpaper's own colour
    assertEquals(blueLight.onSurface, w.tokens.ink)
    assertEquals(blueLight.surface, w.tokens.paper)
  }

  @Test fun aBadBrightWallpaperIsPulledUpToTheFloors() {
    val w = wallpaperTokens(yellowBad, dark = false)
    assertFloors("yellow (bad)", w)
    for (pair in listOf("text on action", "ink-2 on paper", "line on paper", "ink on marker", "danger on paper")) {
      assertTrue("$pair should have been corrected: ${w.fallbacks}", w.fallbacks.any { it.startsWith(pair) })
    }
    assertEquals("the fill itself is kept: the marker stays the wallpaper's yellow", yellowBad.primaryContainer, w.tokens.marker)
  }

  @Test fun aBadDarkWallpaperIsPulledUpToTheFloors() {
    val w = wallpaperTokens(greenDarkBad, dark = true)
    assertFloors("green dark (bad)", w)
    assertTrue(w.fallbacks.isNotEmpty())
  }

  @Test fun sunlightItselfNeedsNoCorrectionUnderTheSameRules() {
    // Run Sunlight's own schemes through the wallpaper mapping: its text pairs already clear the floors with room to spare.
    for ((name, scheme, dark) in listOf(Triple("light", LightScheme, false), Triple("dark", DarkScheme, true))) {
      val w = wallpaperTokens(scheme, dark)
      assertFloors("sunlight $name", w)
      assertTrue("$name: ${w.fallbacks}", w.fallbacks.none { it.startsWith("ink") || it.startsWith("text") })
    }
  }

  @Test fun belowAndroid12OnlySunlightIsOffered() {
    assertEquals(false, wallpaperColoursAvailable(30))
    assertEquals(true, wallpaperColoursAvailable(31))
  }

  /** The contrast function is the page's (sunlight.py) WCAG formula. */
  @Test fun contrastMatchesTheKnownValues() {
    assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
    assertEquals(1.0, contrast(Color(0xFF777777), Color(0xFF777777)), 0.0001)
  }

  @Suppress("unused") private fun ColorScheme.debug() = "$primary $surface"
}
