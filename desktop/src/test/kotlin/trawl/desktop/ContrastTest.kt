package trawl.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.craneai.findr.ui.theme.SunlightTokens
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.ui.sunlightTokens

/**
 * Audit row H5: every pair of Sunlight tokens that the app puts text, an icon, an edge or a focus ring on, against WCAG 2.2 AA (4.5:1 for
 * text, 3:1 for the lines and shapes a person needs to see to use a control), in light and dark; and the high-contrast variant against AAA
 * (7:1 for text, 4.5:1 for edges). The ratio is computed here from the tokens, not copied from a table.
 *
 * Not tested, on purpose: the marker yellow against the paper (it is always a fill under ink with an ink edge, never the only thing that
 * separates two parts, and the selected state also carries the ink edge, a check or a word), hairline dividers (decoration), and disabled
 * controls (exempt in WCAG 1.4.3 and 1.4.11).
 */
class ContrastTest {
  /** WCAG relative luminance of an sRGB colour. */
  private fun lum(c: Color): Double {
    fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
  }

  private fun ratio(a: Color, b: Color): Double {
    val (hi, lo) = lum(a).let { x -> lum(b).let { y -> if (x >= y) x to y else y to x } }
    return (hi + 0.05) / (lo + 0.05)
  }

  private class Pair(val name: String, val fg: (SunlightTokens) -> Color, val bg: (SunlightTokens) -> Color, val kind: Kind)
  private enum class Kind { TEXT, NON_TEXT }

  private val pairs = listOf(
    Pair("ink on paper", { it.ink }, { it.paper }, Kind.TEXT),
    Pair("ink on paper2", { it.ink }, { it.paper2 }, Kind.TEXT),
    Pair("ink2 on paper", { it.ink2 }, { it.paper }, Kind.TEXT),
    Pair("ink2 on paper2", { it.ink2 }, { it.paper2 }, Kind.TEXT),
    Pair("onMarker on marker", { it.onMarker }, { it.marker }, Kind.TEXT),
    Pair("onAction on action", { it.onAction }, { it.action }, Kind.TEXT),
    Pair("danger on paper", { it.danger }, { it.paper }, Kind.TEXT),
    Pair("danger on paper2", { it.danger }, { it.paper2 }, Kind.TEXT),
    Pair("good on paper", { it.good }, { it.paper }, Kind.TEXT),
    Pair("good on paper2", { it.good }, { it.paper2 }, Kind.TEXT),
    Pair("paper on danger (error button)", { it.paper }, { it.danger }, Kind.TEXT),
    Pair("line on paper", { it.line }, { it.paper }, Kind.NON_TEXT),
    Pair("line on paper2", { it.line }, { it.paper2 }, Kind.NON_TEXT),
    Pair("line2 on paper", { it.line2 }, { it.paper }, Kind.NON_TEXT),
    Pair("line2 on paper2", { it.line2 }, { it.paper2 }, Kind.NON_TEXT),
    Pair("focus ring on paper", { it.focus }, { it.paper }, Kind.NON_TEXT),
    Pair("focus ring on paper2", { it.focus }, { it.paper2 }, Kind.NON_TEXT),
    Pair("ink edge on marker (selected chip)", { it.onMarker }, { it.marker }, Kind.NON_TEXT),
  )

  private fun check(dark: Boolean, high: Boolean, text: Double, nonText: Double) {
    val t = sunlightTokens(dark, high)
    val failures = pairs.filter { p ->
      // The error button is paper on danger; in dark the danger is the light salmon, so the pair still reads.
      val need = if (p.kind == Kind.TEXT) text else nonText
      ratio(p.fg(t), p.bg(t)) < need
    }.map { "${it.name} = ${"%.2f".format(ratio(it.fg(t), it.bg(t)))}" }
    assertTrue(failures.isEmpty(), "dark=$dark high=$high below ${text}:1 text / ${nonText}:1 non-text: $failures")
  }

  @Test fun lightMeetsAA() = check(dark = false, high = false, text = 4.5, nonText = 3.0)
  @Test fun darkMeetsAA() = check(dark = true, high = false, text = 4.5, nonText = 3.0)
  @Test fun highContrastLightMeetsAAA() = check(dark = false, high = true, text = 7.0, nonText = 4.5)
  @Test fun highContrastDarkMeetsAAA() = check(dark = true, high = true, text = 7.0, nonText = 4.5)

  @Test fun theRatioIsTheWcagOne() {
    assertEquals(21.0, ratio(Color.Black, Color.White), 0.001)
    assertEquals(1.0, ratio(Color.White, Color.White), 0.001)
    assertTrue(Color.White.luminance() > Color.Black.luminance())
  }

  @Test fun highContrastIsABetterVersionOfStandardNotADifferentDesign() {
    for (dark in listOf(false, true)) {
      val std = sunlightTokens(dark, false)
      val high = sunlightTokens(dark, true)
      assertEquals(std.marker, high.marker, "the marker stays the one colour")
      for (p in pairs.filter { it.kind == Kind.TEXT }) {
        assertTrue(ratio(p.fg(high), p.bg(high)) >= ratio(p.fg(std), p.bg(std)) - 0.001, "${p.name} must not get weaker in high contrast (dark=$dark)")
      }
    }
  }

  // ---- reading the computer's own setting (OsContrast) ----

  @Test fun macIncreaseContrastIsOneOrZero() {
    assertTrue(OsContrast.parseMac("1\n"))
    assertFalse(OsContrast.parseMac("0\n"))
    assertFalse(OsContrast.parseMac(null), "a key that was never set errors out: that is no")
  }

  @Test fun gnomeHighContrastIsTrueOrFalse() {
    assertTrue(OsContrast.parseGsettings("true\n"))
    assertFalse(OsContrast.parseGsettings("false\n"))
    assertFalse(OsContrast.parseGsettings(null))
    assertTrue(OsContrast.isHighContrastTheme("'HighContrastInverse'"))
    assertFalse(OsContrast.isHighContrastTheme("'Adwaita'"))
  }

  @Test fun eachSystemIsAskedItsOwnWayAndAFailureIsNo() {
    val none: (String) -> String? = { null }
    val said: (List<String>) -> String? = { cmd -> if (cmd.any { "universalaccess" in it }) "1" else if ("high-contrast" in cmd) "true" else null }
    assertTrue(OsContrast.active(Os.MAC, none, { false }, said))
    assertTrue(OsContrast.active(Os.LINUX, none, { false }, said))
    assertTrue(OsContrast.active(Os.WINDOWS, none, { true }, { null }))
    assertFalse(OsContrast.active(Os.WINDOWS, none, { false }, said))
    assertFalse(OsContrast.active(Os.LINUX, none, { false }, { null }), "no gsettings (KDE, a minimal desktop) reads as no")
    assertFalse(OsContrast.active(Os.WINDOWS, none, { error("no toolkit") }, { null }), "an unreadable answer never throws")
    assertTrue(OsContrast.active(Os.LINUX, { if (it == "GTK_THEME") "HighContrast" else null }, { false }, { null }))
  }

  @Test fun theEnvironmentCanForceEitherAnswer() {
    assertTrue(OsContrast.active(Os.LINUX, { if (it == "TRAWL_CONTRAST") "high" else null }, { false }, { null }))
    assertFalse(OsContrast.active(Os.MAC, { if (it == "TRAWL_CONTRAST") "standard" else null }, { false }, { "1" }))
  }

  @Test fun theChoicesRoundTripAndUnknownMeansTheDefault() {
    for (c in ContrastChoice.entries) assertEquals(c, ContrastChoice.of(c.id))
    assertEquals(ContrastChoice.SYSTEM, ContrastChoice.of("nonsense"))
    for (s in TextSize.entries) assertEquals(s, TextSize.of(s.id))
    assertEquals(TextSize.DEFAULT, TextSize.of(null))
  }
}
