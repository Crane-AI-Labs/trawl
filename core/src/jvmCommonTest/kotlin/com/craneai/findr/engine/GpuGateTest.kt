package com.craneai.findr.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuGateTest {
  private fun wait(sinceTouchMs: Long?, uiVisible: Boolean = true, lists: Int = 1) =
    GpuGate.shouldWait(100_000, sinceTouchMs?.let { 100_000 - it }, uiVisible, lists)

  @Test fun aFingerOnTheListOrFocusAlwaysHolds() = assertTrue(wait(null))

  @Test fun browsingAListHoldsForHalfAMinute() {
    assertTrue(wait(10_000))          // scroll 5 s, read 10 s: never an encode mid-browse
    assertTrue(wait(29_000))
    assertFalse(wait(31_000))         // left alone on the grid (watching the meter): encodes resume
  }

  @Test fun withoutAVisibleListOnlyTheQuietWindow() {
    assertTrue(wait(5_000, lists = 0))
    assertFalse(wait(9_000, lists = 0))                      // meter sheet, onboarding
    assertFalse(wait(9_000, uiVisible = false, lists = 1))  // screen off or app in the background, grid still composed
  }
}
