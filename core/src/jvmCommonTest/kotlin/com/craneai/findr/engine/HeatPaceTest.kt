package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatPaceTest {
  @Test fun coolOrFullDutyNeverRests() {
    assertEquals(0L, HeatPace.restMs(800, hot = false, duty = 0.5))
    assertEquals(0L, HeatPace.restMs(800, hot = true, duty = 1.0))
    assertEquals(0L, HeatPace.restMs(0, hot = true, duty = 0.5))
    assertEquals("a nonsense duty is off, not a stall", 0L, HeatPace.restMs(800, hot = true, duty = 0.0))
  }

  @Test fun hotRestsInProportionToTheWork() {
    assertEquals(800L, HeatPace.restMs(800, hot = true, duty = 0.5))
    assertEquals(200L, HeatPace.restMs(800, hot = true, duty = 0.8))
    assertEquals("one long file never parks the lane for a minute", HeatPace.MAX_REST_MS, HeatPace.restMs(60_000, hot = true, duty = 0.5))
  }

  @Test fun hotIsTheBatteryOrTheClockCap() {
    assertFalse(HeatPace.hot(36.0, fromC = 38.0, capKhz = 2_000_000, capBelowKhz = 0))
    assertTrue(HeatPace.hot(38.0, fromC = 38.0, capKhz = 2_000_000, capBelowKhz = 0))
    assertTrue(HeatPace.hot(34.0, fromC = 99.0, capKhz = 1_400_000, capBelowKhz = 2_000_000))
    assertFalse(HeatPace.hot(34.0, fromC = 99.0, capKhz = 2_000_000, capBelowKhz = 2_000_000))
    assertFalse("an unreadable cap is not a hot phone", HeatPace.hot(34.0, fromC = 99.0, capKhz = null, capBelowKhz = 2_000_000))
  }
}
