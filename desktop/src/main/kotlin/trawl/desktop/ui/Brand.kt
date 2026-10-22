package trawl.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.SunlightLightTokens
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import trawl.desktop.t

/**
 * The Trawl mark: Crane AI Labs' X with four hollow diamonds, the lower-right arm running on into a magnifier's handle. This is the
 * same outline as the phone's launcher drawable (app/src/main/res/drawable/ic_brand_mark.xml) and site/favicon.svg, on the same
 * 64-unit grid; BrandTest fails if this copy and the favicon ever differ. It is drawn as one even-odd shape, so the diamonds are
 * real holes. The path uses only M, implicit line-to and Z, so a few lines parse it and no vector library is needed.
 */
object Brand {
  const val PATH =
    "M31.36 11.6 30.95 11.82 25.72 17.05 25.55 17.31 25.49 17.62 25.55 17.93 25.72 18.19 30.95 23.41 31.21 23.59 31.52 23.65 31.83 23.59 32.09 23.41 37.32 18.19 37.49 17.93 37.55 17.62 37.49 17.31 37.32 17.05 32.09 11.82 31.83 11.65ZM33.69 17.78 31.68 19.79 31.52 19.86 31.36 19.79 29.35 17.78 29.28 17.62 29.35 17.46 31.36 15.44 31.52 15.38 31.68 15.44 33.69 17.46 33.76 17.62ZM16.72 26.07 11.37 31.4 11.2 31.66 11.14 31.97 11.2 32.28 11.37 32.54 16.6 37.76 16.86 37.94 17.17 38 17.48 37.94 17.74 37.76 22.97 32.54 23.14 32.28 23.2 31.97 23.14 31.66 22.97 31.4 17.74 26.17 17.48 26 17.17 25.93ZM17.25 34.19 17.01 34.14 15 32.13 14.93 31.97 15 31.81 17.01 29.79 17.17 29.73 17.33 29.79 19.34 31.81 19.41 31.97 19.34 32.13ZM22.05 20.82 21.62 20.88 21.21 21.05 20.86 21.31 20.6 21.66 20.43 22.07 20.37 22.5 20.43 22.94 20.6 23.34 20.86 23.69 29.14 31.97 20.72 40.41 20.43 41 20.39 41.65 20.5 42.08 20.86 42.62 21.41 42.99 21.83 43.1 22.49 43.06 23.08 42.77 31.46 34.4 47.95 52.58 48.43 52.92 48.95 53.16 49.52 53.29 50.1 53.31 50.67 53.21 51.21 53.01 52 52.45 52.37 52 52.64 51.49 52.81 50.93 52.86 50.35 52.81 49.78 52.64 49.22 52.13 48.4 33.96 31.91 42.32 23.53 42.61 22.94 42.65 22.28 42.54 21.86 42.17 21.31 41.63 20.95 40.98 20.82 40.55 20.88 39.96 21.17 31.52 29.59 23.24 21.31 22.7 20.95ZM45.56 26 45.3 26.17 40.07 31.4 39.9 31.66 39.84 31.97 39.9 32.28 40.07 32.54 45.3 37.76 45.56 37.94 45.87 38 46.18 37.94 46.44 37.76 51.67 32.54 51.84 32.28 51.9 31.97 51.84 31.66 51.67 31.4 46.44 26.17 46.03 25.95ZM46.03 34.14 45.87 34.21 45.71 34.14 43.7 32.13 43.63 31.97 43.7 31.81 45.71 29.79 45.87 29.73 46.03 29.79 48.04 31.81 48.11 31.97 48.04 32.13ZM31.07 40.42 25.72 45.75 25.55 46.01 25.49 46.32 25.55 46.63 25.72 46.89 30.95 52.11 31.21 52.29 31.52 52.35 31.83 52.29 32.09 52.11 37.32 46.89 37.49 46.63 37.55 46.32 37.49 46.01 37.32 45.75 32.09 40.52 31.83 40.35 31.52 40.28ZM31.6 48.54 31.36 48.49 29.35 46.48 29.28 46.32 29.35 46.16 31.36 44.14 31.52 44.08 31.68 44.14 33.69 46.16 33.76 46.32 33.69 46.48Z"

  const val GRID = 64f

  /** The outline as closed polygons of (x, y) points on the 64-unit grid. */
  fun polygons(path: String = PATH): List<List<Pair<Float, Float>>> =
    path.split('M').filter { it.isNotBlank() }.map { sub ->
      val nums = sub.substringBefore('Z').trim().split(Regex("\\s+")).map { it.toFloat() }
      nums.chunked(2).map { it[0] to it[1] }
    }

  /** The window and taskbar icon: the mark in ink on the marker-yellow tile, drawn at [px] by [px] with Java2D. */
  fun icon(px: Int): BufferedImage {
    val img = BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.color = Color(0xFF, 0xD4, 0x00)
    g.fill(RoundRectangle2D.Float(0f, 0f, px.toFloat(), px.toFloat(), px * 0.25f, px * 0.25f))
    val k = px / GRID
    val outline = Path2D.Float(Path2D.WIND_EVEN_ODD)
    for (poly in polygons()) {
      poly.forEachIndexed { i, (x, y) -> if (i == 0) outline.moveTo(x * k, y * k) else outline.lineTo(x * k, y * k) }
      outline.closePath()
    }
    g.color = Color(0x11, 0x11, 0x11)
    g.fill(outline)
    g.dispose()
    return img
  }

  /** Every size a window manager, taskbar or alt-tab list may ask for. */
  fun icons(): List<BufferedImage> = listOf(16, 24, 32, 48, 64, 128, 256).map(::icon)
}

/** The mark on its marker tile, as on the phone's home screen: ink on yellow, whatever the palette. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
  val brand = SunlightLightTokens
  val polys = androidx.compose.runtime.remember { Brand.polygons() }
  Box(modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(brand.marker), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(size)) {
      val k = this.size.width / Brand.GRID
      val p = Path().apply {
        fillType = PathFillType.EvenOdd
        for (poly in polys) {
          poly.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * k, y * k) else lineTo(x * k, y * k) }
          close()
        }
      }
      drawPath(p, brand.onMarker)
    }
  }
}

/** The mark and the app's name, the header of the main screen and of first run. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, markSize: Dp = 36.dp) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    BrandMark(markSize)
    Spacer(Modifier.width(10.dp))
    Text(t("app_name"), style = SunType.Title, color = MaterialTheme.colorScheme.onBackground)
  }
}
