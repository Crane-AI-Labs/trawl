package com.craneai.findr.engine

import com.craneai.findr.core.PauseReason
import com.craneai.findr.engine.IndexPolicy.Decision
import com.craneai.findr.engine.IndexPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterRulesTest {
  private val onCharger = Decision(Tier.BULK, null)
  private val lightOnBattery = Decision(Tier.LIGHT, PauseReason.NOT_CHARGING)
  private val strictOnBattery = Decision(Tier.WORDS_ONLY, PauseReason.NOT_CHARGING)
  private val lowBattery = Decision(Tier.WORDS_ONLY, PauseReason.BATTERY_LOW)
  private val hot = Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)

  private fun pause(
    access: Boolean = true, user: Boolean = false, worker: PauseReason? = null, running: Boolean = false,
    waiting: Int = 0, decision: Decision = onCharger,
  ) = MeterRules.pause(access, user, worker, running, waiting, decision)

  @Test fun needsChargerOnlyWhenFilesWaitAndTheChargerIsWhatIsMissing() {
    assertTrue(MeterRules.needsCharger(5, lightOnBattery))
    assertTrue(MeterRules.needsCharger(5, strictOnBattery))
    assertFalse(MeterRules.needsCharger(0, lightOnBattery))
    assertFalse(MeterRules.needsCharger(5, onCharger))
    assertFalse(MeterRules.needsCharger(5, Decision(Tier.BULK, null)))   // full indexing on battery switched on
    assertFalse(MeterRules.needsCharger(5, lowBattery))                  // low battery is its own message
    assertFalse(MeterRules.needsCharger(5, hot))
  }

  @Test fun noFinishTimeWhileMeaningWaitsForTheCharger() {
    assertNull(MeterRules.eta(46 * 60L, needsCharger = true))
    assertEquals(46 * 60L, MeterRules.eta(46 * 60L, needsCharger = false))
    assertNull(MeterRules.eta(null, needsCharger = false))
  }

  @Test fun workerStillRunningOnBatteryIsNotPaused() {
    assertNull(pause(running = true, waiting = 5, decision = lightOnBattery))
  }

  @Test fun onceTheWorkerIsDoneOnBatteryTheMeterSaysWaitingForTheCharger() {
    assertEquals(PauseReason.NOT_CHARGING, pause(running = false, waiting = 5, decision = lightOnBattery))
    assertEquals(PauseReason.NOT_CHARGING, pause(worker = PauseReason.NOT_CHARGING, waiting = 5, decision = strictOnBattery))
  }

  @Test fun plugInDropsTheWorkersStaleNotChargingAtOnce() {
    assertNull(pause(worker = PauseReason.NOT_CHARGING, waiting = 5, decision = onCharger))
  }

  @Test fun nothingWaitingMeansNoPause() {
    assertNull(pause(waiting = 0, decision = lightOnBattery))
    assertNull(pause(waiting = 0, decision = lowBattery))
  }

  @Test fun lowBatteryAndHeatAreShownTheMomentTheyHoldWorkBack() {
    assertEquals(PauseReason.BATTERY_LOW, pause(running = true, waiting = 5, decision = lowBattery))
    assertEquals(PauseReason.TOO_HOT, pause(running = true, waiting = 5, decision = hot))
  }

  @Test fun otherWorkerPausesSurviveAPlugIn() {
    assertEquals(PauseReason.TOO_HOT, pause(worker = PauseReason.TOO_HOT, waiting = 5, decision = onCharger))
    assertEquals(PauseReason.BATTERY_LOW, pause(worker = PauseReason.BATTERY_LOW, waiting = 5, decision = onCharger))
  }

  @Test fun permissionAndUserPauseWinOverEverything() {
    assertEquals(PauseReason.NO_PERMISSION, pause(access = false, waiting = 5, decision = lowBattery))
    assertEquals(PauseReason.USER, pause(user = true, waiting = 5, decision = lightOnBattery))
  }
}
