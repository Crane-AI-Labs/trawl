package trawl.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import trawl.desktop.ui.Brand

class BrandTest {
  @Test fun theMarkIsTheSiteFavicon() {
    val svg = File("../site/favicon.svg").readText()
    val d = Regex(" d=\"([^\"]*)\"").find(svg)!!.groupValues[1]
    assertEquals(d, Brand.PATH, "the desktop copy of the mark must match site/favicon.svg (and the phone's ic_brand_mark.xml)")
    val phone = File("../app/src/main/res/drawable/ic_brand_mark.xml").readText()
    assertEquals(d, Regex("pathData=\"([^\"]*)\"").find(phone)!!.groupValues[1].trim())
  }

  @Test fun theOutlineParsesIntoClosedPolygonsOnTheGrid() {
    val polys = Brand.polygons()
    assertEquals(9, polys.size)   // the X with its handle, four diamonds and their four holes
    assertTrue(polys.all { it.size >= 4 })
    assertTrue(polys.flatten().all { (x, y) -> x in 0f..64f && y in 0f..64f })
  }

  @Test fun theWindowIconHasInkOnYellowAndTransparentCorners() {
    val img = Brand.icon(64)
    assertEquals(64, img.width)
    assertEquals(0, img.getRGB(0, 0) ushr 24, "the tile's corner is transparent")
    assertEquals(0xFFFFD400.toInt(), img.getRGB(4, 32), "tile colour is the marker yellow")
    assertEquals(0xFF111111.toInt(), img.getRGB(32, 32),"the X's crossing is ink")
  }
}
