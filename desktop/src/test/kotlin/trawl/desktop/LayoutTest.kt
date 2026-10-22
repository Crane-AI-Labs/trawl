package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.ui.Layout

class LayoutTest {
  @Test fun theMinimumWindowIsASingleColumn() {
    assertEquals(Layout.Mode.SINGLE, Layout.mode(720))
    assertEquals(0, Layout.previewWidth(720))
    assertEquals(720, Layout.listWidth(720))
    assertFalse(Layout.showRail(720))
    assertFalse(Layout.twoColumnFirstRun(720))
    assertFalse(Layout.showKeyHints(720))
  }

  @Test fun theSplitStartsAtNineHundredExactly() {
    assertEquals(Layout.Mode.SINGLE, Layout.mode(899))
    assertEquals(Layout.Mode.SPLIT, Layout.mode(900))
    assertTrue(Layout.twoColumnFirstRun(900))
    assertFalse(Layout.twoColumnFirstRun(899))
  }

  @Test fun theSettingsListAppearsFromEightHundredAndEighty() {
    assertFalse(Layout.showRail(879))
    assertTrue(Layout.showRail(880))
  }

  @Test fun keyHintsNeedRoomForThem() {
    assertFalse(Layout.showKeyHints(1099))
    assertTrue(Layout.showKeyHints(1100))
  }

  @Test fun thePreviewIsFortyPercentWithinItsBounds() {
    assertEquals(360, Layout.previewWidth(900))
    assertEquals(604, Layout.previewWidth(1512))
    assertEquals(448, Layout.previewWidth(1120))
    assertEquals(Layout.PREVIEW_MAX, Layout.previewWidth(3000), "a huge window must not grow a billboard")
    for (w in 900..2600 step 50) {
      val p = Layout.previewWidth(w)
      assertTrue(p in Layout.PREVIEW_MIN..Layout.PREVIEW_MAX, "width $w gave $p")
      assertEquals(w, p + Layout.listWidth(w))
    }
  }

  @Test fun photoColumnsFollowTheListWidthAndNeverDropBelowTwo() {
    assertEquals(2, Layout.gridColumns(100))
    assertEquals(2, Layout.gridColumns(0))
    assertEquals(3, Layout.gridColumns(3 * Layout.TILE_MIN + 2 * Layout.TILE_GAP))
    assertEquals(2, Layout.gridColumns(3 * Layout.TILE_MIN + 2 * Layout.TILE_GAP - 1))
    assertEquals(6, Layout.gridColumns(900))
    var last = 0
    for (w in 200..2000 step 10) {
      val c = Layout.gridColumns(w)
      assertTrue(c >= last, "columns never go down as the list widens")
      last = c
    }
  }

  // --- the Settings scroll-spy ---

  private val tops = listOf(0, 600, 1100, 1700, 2200, 2700)

  @Test fun theFirstSectionIsActiveAtTheTop() {
    assertEquals(0, Layout.activeSection(tops, 0, 3000))
  }

  @Test fun aSectionLightsUpJustBeforeItReachesTheTop() {
    assertEquals(0, Layout.activeSection(tops, 600 - Layout.SPY_SLACK - 1, 3000))
    assertEquals(1, Layout.activeSection(tops, 600 - Layout.SPY_SLACK, 3000))
    assertEquals(2, Layout.activeSection(tops, 1100, 3000))
    assertEquals(3, Layout.activeSection(tops, 1700 + 200, 3000))
  }

  @Test fun theLastSectionWinsAtTheVeryBottomEvenIfItNeverReachesTheTop() {
    assertEquals(4, Layout.activeSection(tops, 2200, 3000))
    assertEquals(5, Layout.activeSection(tops, 3000, 3000))
    assertEquals(5, Layout.activeSection(tops, 2999, 3000), "within two pixels of the end counts")
  }

  @Test fun noScrollRoomOrNoSectionsIsHandled() {
    assertEquals(0, Layout.activeSection(tops, 0, 0), "a page that does not scroll shows the first section")
    assertEquals(-1, Layout.activeSection(emptyList(), 0, 100))
  }

  @Test fun sectionsNotMeasuredYetDoNotLightUp() {
    val unmeasured = listOf(0, Int.MAX_VALUE, Int.MAX_VALUE)
    assertEquals(0, Layout.activeSection(unmeasured, 500, 2000))
  }
}
