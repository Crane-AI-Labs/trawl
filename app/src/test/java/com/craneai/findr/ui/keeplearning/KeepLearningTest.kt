package com.craneai.findr.ui.keeplearning

import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason
import com.craneai.findr.ui.keeplearning.KeepLearning.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepLearningTest {
  /** Indexing on the charger, cool, plenty of battery: the normal night. */
  private val ok = KeepLearning.Inputs(
    phase = IndexPhase.INDEXING, pauseReason = null, needsCharger = false, sameStateForMs = 600_000, noProgressForMs = 5_000,
    plugged = true, batteryPct = 80, batteryTempC = 36.0, thermalStatus = 0, startedOnCharger = true, unpluggedForMs = 0,
  )

  @Test fun aNormalNightKeepsGoing() = assertNull(KeepLearning.stopReason(ok))

  @Test fun doneOnlyOnceUpToDateHolds() {
    assertNull("the phase flickers between worker runs", KeepLearning.stopReason(ok.copy(phase = IndexPhase.UP_TO_DATE, sameStateForMs = 5_000)))
    assertEquals(Stop.DONE, KeepLearning.stopReason(ok.copy(phase = IndexPhase.UP_TO_DATE, sameStateForMs = 20_000)))
  }

  @Test fun heatStopsBeforeTheWorkersOwnCoolDown() {
    assertEquals(Stop.HOT, KeepLearning.stopReason(ok.copy(batteryTempC = 42.0)))
    assertNull(KeepLearning.stopReason(ok.copy(batteryTempC = 41.9)))
    assertEquals("PowerManager.THERMAL_STATUS_SEVERE", Stop.HOT, KeepLearning.stopReason(ok.copy(thermalStatus = 3)))
    assertNull("moderate is the worker's business", KeepLearning.stopReason(ok.copy(thermalStatus = 2)))
    assertEquals("heat wins even when done", Stop.HOT, KeepLearning.stopReason(ok.copy(phase = IndexPhase.UP_TO_DATE, sameStateForMs = 60_000, batteryTempC = 43.0)))
  }

  @Test fun batteryUnderTwentyOffTheCharger() {
    val battery = ok.copy(plugged = false, startedOnCharger = false)
    assertEquals(Stop.LOW_BATTERY, KeepLearning.stopReason(battery.copy(batteryPct = 19)))
    assertNull(KeepLearning.stopReason(battery.copy(batteryPct = 20)))
    assertNull("on the charger a low battery is charging", KeepLearning.stopReason(ok.copy(batteryPct = 5)))
    assertNull("an unknown level is not low", KeepLearning.stopReason(battery.copy(batteryPct = -1)))
  }

  @Test fun unpluggedStopsOnlyWhenStartedOnTheChargerAndNotForAWobble() {
    val off = ok.copy(plugged = false)
    assertNull(KeepLearning.stopReason(off.copy(unpluggedForMs = 3_000)))
    assertEquals(Stop.UNPLUGGED, KeepLearning.stopReason(off.copy(unpluggedForMs = 5_000)))
    assertNull("started on battery: no charger to lose", KeepLearning.stopReason(off.copy(startedOnCharger = false, unpluggedForMs = 600_000)))
  }

  @Test fun aPauseItCannotLeaveStopsIt() {
    for (r in listOf(PauseReason.USER, PauseReason.NO_PERMISSION, PauseReason.STORAGE_LOW)) {
      assertNull(KeepLearning.stopReason(ok.copy(phase = IndexPhase.PAUSED, pauseReason = r, sameStateForMs = 10_000)))
      assertEquals(Stop.CANT_CONTINUE, KeepLearning.stopReason(ok.copy(phase = IndexPhase.PAUSED, pauseReason = r, sameStateForMs = 30_000)))
    }
    assertNull("the worker's warm rest says TOO_HOT for a while", KeepLearning.stopReason(ok.copy(phase = IndexPhase.PAUSED, pauseReason = PauseReason.TOO_HOT, sameStateForMs = 600_000)))
    assertEquals(Stop.CANT_CONTINUE, KeepLearning.stopReason(ok.copy(phase = IndexPhase.ERROR, sameStateForMs = 30_000)))
  }

  @Test fun onBatteryWithNothingMovingItWaitsForTheCharger() {
    val battery = ok.copy(plugged = false, startedOnCharger = false, needsCharger = true)
    assertNull(KeepLearning.stopReason(battery.copy(noProgressForMs = 60_000)))
    assertEquals(Stop.NEEDS_CHARGER, KeepLearning.stopReason(battery.copy(noProgressForMs = 120_000)))
    assertNull("light work still moving", KeepLearning.stopReason(battery.copy(noProgressForMs = 10_000)))
    assertNull("on the charger it is never 'waiting for the charger'", KeepLearning.stopReason(ok.copy(needsCharger = true, noProgressForMs = 600_000)))
  }

  @Test fun offeredOnTranssionWhileWorkIsLeftAndAnywhereForALargeBacklog() {
    val small = IndexStatus(phase = IndexPhase.INDEXING, filesKnown = 300, filesUnderstood = 100)
    assertTrue(KeepLearning.offered(small, transsion = true))
    assertFalse(KeepLearning.offered(small, transsion = false))
    val large = small.copy(filesKnown = 1_200, filesUnderstood = 100)
    assertTrue(KeepLearning.offered(large, transsion = false))
    assertFalse("nothing left", KeepLearning.offered(small.copy(filesUnderstood = 300), transsion = true))
    assertFalse("up to date", KeepLearning.offered(small.copy(phase = IndexPhase.UP_TO_DATE), transsion = true))
    assertFalse("paused by the person", KeepLearning.offered(small.copy(phase = IndexPhase.PAUSED, pauseReason = PauseReason.USER), transsion = true))
    assertTrue("waiting for the charger: the screen can wait with it", KeepLearning.offered(small.copy(phase = IndexPhase.PAUSED, pauseReason = PauseReason.NOT_CHARGING), transsion = true))
  }

  @Test fun unreadableFilesAreNotWorkLeft() {
    val s = IndexStatus(phase = IndexPhase.INDEXING, filesKnown = 300, filesUnderstood = 290, filesUnreadable = 10)
    assertEquals(0, KeepLearning.remaining(s))
    assertFalse(KeepLearning.offered(s, transsion = true))
  }
}
