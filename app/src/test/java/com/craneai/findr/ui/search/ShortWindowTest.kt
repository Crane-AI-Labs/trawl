package com.craneai.findr.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which windows get the one-list layout (tablet landscape, RC QA 2026-10-21: the results area showed about one row). */
class ShortWindowTest {
  @Test fun `the test tablet on its side is short`() {
    assertTrue(ShortWindow.isShort(widthDp = 1007, heightDp = 601))   // SM-X135G, 800 x 1340 px at 213 dpi, bars included
    assertTrue(ShortWindow.isShort(widthDp = 1007, heightDp = 552))   // the same window without the taskbar
    assertTrue(ShortWindow.isShort(widthDp = 893, heightDp = 533))    // a 240 dpi tablet of the same size
  }

  @Test fun `a phone on its side is short`() {
    assertTrue(ShortWindow.isShort(widthDp = 915, heightDp = 411))   // Galaxy A06
    assertTrue(ShortWindow.isShort(widthDp = 800, heightDp = 360))
  }

  @Test fun `a short split-screen window is short even when it is narrow`() {
    assertTrue(ShortWindow.isShort(widthDp = 411, heightDp = 400))
  }

  @Test fun `portrait phones and tablets keep the stacked layout`() {
    assertFalse(ShortWindow.isShort(widthDp = 411, heightDp = 914))   // Galaxy A06, upright
    assertFalse(ShortWindow.isShort(widthDp = 360, heightDp = 640))   // the smallest phone we care about, upright
    assertFalse(ShortWindow.isShort(widthDp = 601, heightDp = 1007))  // the test tablet, upright
    assertFalse(ShortWindow.isShort(widthDp = 601, heightDp = 954))   // the same without the taskbar
    assertFalse(ShortWindow.isShort(widthDp = 800, heightDp = 1280))  // a large tablet, upright
  }

  @Test fun `a large tablet on its side has the room for the stacked layout`() {
    assertFalse(ShortWindow.isShort(widthDp = 1280, heightDp = 800))
  }

  @Test fun `the header and the meter share a line only in a wide window`() {
    assertTrue(ShortWindow.mergedHeader(893))
    assertTrue(ShortWindow.mergedHeader(600))
    assertFalse(ShortWindow.mergedHeader(411))
  }
}
