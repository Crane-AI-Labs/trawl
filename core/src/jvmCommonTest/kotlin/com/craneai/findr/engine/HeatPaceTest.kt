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

  @Test fun coolDownTriggersOnHeadroomSevereStatusOrHotBattery() {
    assertEquals(HeatPace.Action.COOL_DOWN, HeatPace.decide(headroom = 1.0f, thermalStatus = 0, batteryC = 35.0, charging = false).action)
    assertEquals(HeatPace.Action.COOL_DOWN, HeatPace.decide(headroom = 1.15f, thermalStatus = 0, batteryC = 35.0, charging = true).action)
    assertEquals(HeatPace.Action.COOL_DOWN, HeatPace.decide(headroom = Float.NaN, thermalStatus = 3, batteryC = 35.0, charging = false).action)
    assertEquals(HeatPace.Action.COOL_DOWN, HeatPace.decide(headroom = 0.4f, thermalStatus = 0, batteryC = 44.0, charging = true, hotStopC = 44.0).action)
    assertEquals(0.0, HeatPace.decide(headroom = 1.0f, thermalStatus = 0, batteryC = 35.0, charging = false).duty, 0.0)
  }

  @Test fun headroomPacesProportionallyAndEarlierWhileCharging() {
    // On battery: threshold is 0.85 -> 0.80 is full speed, 0.85 starts at 0.60, midpoint 0.925 is 0.425, approaching 1.0 nears 0.25.
    assertEquals(HeatPace.Action.FULL_SPEED, HeatPace.decide(headroom = 0.80f, thermalStatus = 0, batteryC = 35.0, charging = false).action)
    val startBatt = HeatPace.decide(headroom = 0.85f, thermalStatus = 0, batteryC = 35.0, charging = false)
    assertEquals(HeatPace.Action.PACE, startBatt.action)
    assertEquals(0.60, startBatt.duty, 0.001)

    val midBatt = HeatPace.decide(headroom = 0.925f, thermalStatus = 0, batteryC = 35.0, charging = false)
    assertEquals(HeatPace.Action.PACE, midBatt.action)
    assertEquals(0.425, midBatt.duty, 0.001)

    val highBatt = HeatPace.decide(headroom = 0.99f, thermalStatus = 0, batteryC = 35.0, charging = false)
    assertEquals(HeatPace.Action.PACE, highBatt.action)
    assertTrue("near 1.0 duty approaches 0.25: ${highBatt.duty}", highBatt.duty in 0.25..0.28)

    // Charging: threshold drops to 0.78 -> 0.80 is already pacing while 0.77 is still full speed.
    assertEquals(HeatPace.Action.FULL_SPEED, HeatPace.decide(headroom = 0.77f, thermalStatus = 0, batteryC = 35.0, charging = true).action)
    val startChg = HeatPace.decide(headroom = 0.78f, thermalStatus = 0, batteryC = 35.0, charging = true)
    assertEquals(HeatPace.Action.PACE, startChg.action)
    assertEquals(0.60, startChg.duty, 0.001)

    val chgAt80 = HeatPace.decide(headroom = 0.80f, thermalStatus = 0, batteryC = 35.0, charging = true)
    assertEquals(HeatPace.Action.PACE, chgAt80.action)
    assertTrue(chgAt80.duty < 0.60 && chgAt80.duty > 0.25)
  }

  @Test fun moderateThermalStatusCapsDutyAtFortyPercent() {
    val coolHeadroom = HeatPace.decide(headroom = 0.50f, thermalStatus = 2, batteryC = 35.0, charging = false)
    assertEquals(HeatPace.Action.PACE, coolHeadroom.action)
    assertEquals(0.40, coolHeadroom.duty, 0.001)

    val mildHeadroom = HeatPace.decide(headroom = 0.85f, thermalStatus = 2, batteryC = 35.0, charging = false)
    assertEquals(HeatPace.Action.PACE, mildHeadroom.action)
    assertEquals(0.40, mildHeadroom.duty, 0.001)

    val hotHeadroom = HeatPace.decide(headroom = 0.98f, thermalStatus = 2, batteryC = 35.0, charging = false)
    assertEquals(HeatPace.Action.PACE, hotHeadroom.action)
    assertTrue("tighter headroom duty wins over moderate cap: ${hotHeadroom.duty}", hotHeadroom.duty < 0.40)
  }

  @Test fun nanOrNegativeHeadroomFallsBackToBatteryTempAndClockCap() {
    for (bad in listOf(Float.NaN, -1.0f, Float.POSITIVE_INFINITY)) {
      assertEquals(HeatPace.Action.FULL_SPEED, HeatPace.decide(headroom = bad, thermalStatus = 0, batteryC = 36.0, charging = true).action)

      val warm = HeatPace.decide(headroom = bad, thermalStatus = 0, batteryC = 41.0, charging = true, warmC = 41.0, hotStopC = 44.0)
      assertEquals(HeatPace.Action.PACE, warm.action)
      assertEquals(0.60, warm.duty, 0.001)

      val midWarm = HeatPace.decide(headroom = bad, thermalStatus = 0, batteryC = 42.5, charging = true, warmC = 41.0, hotStopC = 44.0)
      assertEquals(HeatPace.Action.PACE, midWarm.action)
      assertEquals(0.425, midWarm.duty, 0.001)

      val capped = HeatPace.decide(headroom = bad, thermalStatus = 0, batteryC = 35.0, charging = true, capKhz = 1_400_000, paceCapBelowKhz = 2_000_000)
      assertEquals(HeatPace.Action.PACE, capped.action)
      assertEquals(0.60, capped.duty, 0.001)

      val unreadCap = HeatPace.decide(headroom = bad, thermalStatus = 0, batteryC = 35.0, charging = true, capKhz = null, paceCapBelowKhz = 2_000_000)
      assertEquals(HeatPace.Action.FULL_SPEED, unreadCap.action)
    }
  }

  @Test fun headroomCacheEnforcesMinimumInterval() {
    val cache = HeatPace.HeadroomCache(minIntervalMs = 10_000L)
    var calls = 0
    var next = 0.50f
    val fetch = { calls++; next }

    assertEquals(0.50f, cache.read(0L, fetch), 0.001f)
    assertEquals(1, calls)

    next = 0.90f
    assertEquals("within 10 s returns cached value without polling OS", 0.50f, cache.read(9_999L, fetch), 0.001f)
    assertEquals(1, calls)

    assertEquals("at 10 s polls OS again", 0.90f, cache.read(10_000L, fetch), 0.001f)
    assertEquals(2, calls)

    next = -1.0f
    assertTrue("negative headroom is normalized to NaN", cache.read(20_000L, fetch).isNaN())
    assertEquals(3, calls)
  }

  @Test fun headroomCacheRetriesImmediatelyAfterFetchThrows() {
    val cache = HeatPace.HeadroomCache(minIntervalMs = 10_000L)
    var calls = 0

    // Initial poll throws: must not suppress retry for minIntervalMs.
    val firstErr = runCatching {
      cache.read(1_000L) { calls++; throw IllegalStateException("transient binder failure") }
    }
    assertTrue(firstErr.isFailure)
    assertEquals(1, calls)

    // Retry 1 ms later succeeds and populates cache.
    assertEquals(0.45f, cache.read(1_001L) { calls++; 0.45f }, 0.001f)
    assertEquals(2, calls)

    // Within 10 s returns cached value without polling.
    assertEquals(0.45f, cache.read(5_000L) { calls++; 0.99f }, 0.001f)
    assertEquals(2, calls)

    // After 10 s, a transient throw must still allow immediate retry rather than locking stale 0.45f for another 10 s.
    val secondErr = runCatching {
      cache.read(11_001L) { calls++; throw RuntimeException("service busy") }
    }
    assertTrue(secondErr.isFailure)
    assertEquals(3, calls)

    assertEquals(0.88f, cache.read(11_002L) { calls++; 0.88f }, 0.001f)
    assertEquals(4, calls)
  }
}
