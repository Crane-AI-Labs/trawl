package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.ui.WindowSizing

class WindowSizingTest {
  @Test fun aRoomyScreenKeepsTheDefaultWindow() {
    // 1920 x 1080 with a taskbar, a 27-inch iMac at 2560 x 1440 points less its bars, and a 4K screen at 150%.
    for ((w, h) in listOf(1920 to 1040, 2560 to 1400, 2560 to 1392, 1706 to 960)) {
      val p = WindowSizing.plan(w, h)
      assertEquals(1120 to 800, p.width to p.height, "screen $w x $h")
      assertEquals(720 to 560, p.minWidth to p.minHeight)
      assertFalse(p.clamped, "screen $w x $h")
    }
  }

  /** A 14-inch MacBook Pro: 3024 x 1964 pixels, 1512 x 982 points. AWT, and so the window, counts points. */
  @Test fun aRetinaLaptopGetsAWindowThatDoesNotLookFullscreen() {
    // Usable under the notch menu bar (38) and a shown Dock (70): 1512 x 874; with the Dock hidden: 1512 x 944.
    for (usableH in listOf(874, 944)) {
      val p = WindowSizing.plan(1512, usableH)
      assertEquals(1120, p.width, "width is the default: it is 74% of 1512")
      assertTrue(p.height <= usableH * 85 / 100, "height ${p.height} of $usableH leaves room to see the desktop")
      assertTrue(p.height >= WindowSizing.MIN_H && p.height <= WindowSizing.DEFAULT_H, "$p")
      assertTrue(p.width <= 1512 * 85 / 100)
    }
    assertEquals(742, WindowSizing.plan(1512, 874).height)
    assertEquals(800, WindowSizing.plan(1512, 944).height)
  }

  /** The panel's physical pixels are not what the window is sized in; the maths takes points and never rescales them. */
  @Test fun theWindowIsNeverLargerThanTheUsableAreaInTheSameUnits() {
    for ((w, h) in listOf(1512 to 874, 1512 to 982, 1024 to 768, 1024 to 728, 2560 to 1400, 1280 to 720, 640 to 480)) {
      val p = WindowSizing.plan(w, h)
      assertTrue(p.width <= w && p.height <= h, "screen $w x $h gave $p")
      assertTrue(p.minWidth <= p.width && p.minHeight <= p.height, "$p")
    }
  }

  @Test fun aSmallScreenGetsAWindowThatFits() {
    // 1024 x 768 with a 40 px taskbar: usable 1024 x 728. The old 1120 x 800 window did not fit.
    val p = WindowSizing.plan(1024, 728)
    assertTrue(p.clamped)
    assertTrue(p.width <= 1024 && p.height + WindowSizing.FRAME_ALLOWANCE <= 728, "$p")
    assertEquals(870 to 618, p.width to p.height)
    assertEquals(720 to 560, p.minWidth to p.minHeight, "720 x 560 still fits inside it")
    // The same screen with nothing taking space (a Mac in the same size, bars aside) behaves the same way.
    assertEquals(870 to 652, WindowSizing.plan(1024, 768).let { it.width to it.height })
  }

  @Test fun theMinimumNeverExceedsTheScreen() {
    val p = WindowSizing.plan(640, 480)
    assertTrue(p.width <= 640 && p.minWidth <= 640 && p.minHeight <= p.height, "$p")
  }

  @Test fun unknownScreenKeepsTheDefaults() {
    val p = WindowSizing.plan(0, 0)
    assertEquals(1120 to 800, p.width to p.height)
    assertFalse(p.clamped)
  }
}
