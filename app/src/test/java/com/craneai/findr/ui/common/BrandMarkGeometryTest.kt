package com.craneai.findr.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The mark (res/drawable/ic_brand_mark.xml) is the one file to swap when the logo changes, so these checks guard any new logo:
 * the ink is centred on its 64-unit grid, and on the launcher (ic_launcher_foreground.xml) it stays inside the 66 dp safe circle
 * that every mask shape keeps, while using most of it. The splash shows the adaptive icon itself, so it cannot drift from the
 * launcher. Only the path commands the marks use are read (M, L, h, v, c, z; strokes with round caps, and fills).
 */
class BrandMarkGeometryTest {
  private val res = File("src/main/res")
  private val mark = File(res, "drawable/ic_brand_mark.xml").readText()
  private val foreground = File(res, "drawable/ic_launcher_foreground.xml").readText()

  /** Ink as sample points with a radius (half the stroke width; 0 for a fill outline). */
  private data class Ink(val x: Double, val y: Double, val r: Double)

  private fun ink(): List<Ink> {
    val out = ArrayList<Ink>()
    val pathTag = Regex("""<path\b[^>]*/>""", RegexOption.DOT_MATCHES_ALL)
    for (tag in pathTag.findAll(mark).map { it.value }) {
      val data = Regex("""android:pathData="([^"]+)"""").find(tag)!!.groupValues[1]
      val stroke = Regex("""android:strokeWidth="([\d.]+)"""").find(tag)?.groupValues?.get(1)?.toDouble()
      val r = (stroke ?: 0.0) / 2
      val tokens = Regex("""[MmLlHhVvCcZz]|-?\d*\.?\d+""").findAll(data).map { it.value }.toList()
      var i = 0
      var x = 0.0
      var y = 0.0
      var cmd = 'M'
      fun num() = tokens[i++].toDouble()
      while (i < tokens.size) {
        if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
        when (cmd) {
          'M' -> { x = num(); y = num(); out += Ink(x, y, r); cmd = 'L' }
          'L' -> { x = num(); y = num(); out += Ink(x, y, r) }
          'h' -> { val x0 = x; x += num(); for (k in 0..20) out += Ink(x0 + (x - x0) * k / 20, y, r) }
          'v' -> { val y0 = y; y += num(); for (k in 0..20) out += Ink(x, y0 + (y - y0) * k / 20, r) }
          'c' -> {
            val c1x = x + num(); val c1y = y + num(); val c2x = x + num(); val c2y = y + num(); val ex = x + num(); val ey = y + num()
            for (k in 0..40) {
              val t = k / 40.0; val u = 1 - t
              out += Ink(
                u * u * u * x + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * ex,
                u * u * u * y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ey, r,
              )
            }
            x = ex; y = ey
          }
          'z', 'Z' -> {}
          else -> error("ic_brand_mark uses path command '$cmd'; teach BrandMarkGeometryTest to read it")
        }
      }
    }
    return out
  }

  /** The foreground's inset on each side, as a share of the 108 dp canvas. A fraction: launchers draw the layer at their own size. */
  private fun inset(): Double {
    val v = Regex("""android:inset="([\d.]+)%"""").find(foreground)
    assertTrue("ic_launcher_foreground.xml needs one android:inset as a percentage (a dp inset does not scale with the layer)", v != null)
    return v!!.groupValues[1].toDouble() / 100
  }

  @Test fun theInkIsCentredOnItsGrid() {
    val pts = ink()
    val left = pts.minOf { it.x - it.r }; val right = pts.maxOf { it.x + it.r }
    val top = pts.minOf { it.y - it.r }; val bottom = pts.maxOf { it.y + it.r }
    assertEquals("horizontal centre (ink x $left..$right)", 32.0, (left + right) / 2, 0.5)
    // Within a unit of the middle: the net's beam makes it top-heavy, so its box may sit up to a unit low.
    assertEquals("vertical centre (ink y $top..$bottom)", 32.0, (top + bottom) / 2, 1.0)
  }

  @Test fun onTheLauncherTheInkStaysInsideTheSafeCircleAndUsesIt() {
    val dpPerUnit = 108 * (1 - 2 * inset()) / 64
    val far = ink().maxOf { hypot(it.x - 32, it.y - 32) + it.r } * dpPerUnit
    assertTrue("farthest ink is $far dp from the centre; the safe circle is 33 dp (keep a little room)", far <= 31.0)
    assertTrue("farthest ink is $far dp from the centre: the mark is small for the icon (use the safe circle)", far >= 28.0)
    // The mark's own size over what the insets leave is the 108 dp canvas (so the drawable's natural size is right too).
    val size = Regex("""android:width="([\d.]+)dp"""").find(mark)!!.groupValues[1].toDouble()
    assertEquals(108.0, size / (1 - 2 * inset()), 0.5)
  }

  @Test fun theSplashShowsTheLauncherIcon() {
    val themes = File(res, "values/themes.xml").readText()
    val splash = Regex("""<style name="Theme.Findr.Splash"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(themes)!!.groupValues[1]
    assertTrue(splash, splash.contains("""<item name="windowSplashScreenAnimatedIcon">@mipmap/ic_launcher</item>"""))
    assertTrue(File(res, "mipmap-anydpi/ic_launcher.xml").readText().contains("<adaptive-icon"))
  }
}
