package com.craneai.findr.engine

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunHandoffTest {
  @Test fun aBlipDoesNotEndTheRunButALongPoliteWaitDoes() {
    assertFalse(RunHandoff.handOffDue(0))
    assertFalse("a notification waking the screen is not enough", RunHandoff.handOffDue(RunHandoff.HAND_OFF_AFTER_MS - 1))
    assertTrue(RunHandoff.handOffDue(RunHandoff.HAND_OFF_AFTER_MS))
    assertTrue(RunHandoff.handOffDue(6 * 60 * 60_000L))
  }

  @Test fun theModelsGoBeforeTheRunDoes() {
    assertEquals("models go after 30 s", 30_000L, PolitePolicy.RELEASE_AFTER_MS)
    assertEquals("the run leaves after 5 min", 5 * 60_000L, RunHandoff.HAND_OFF_AFTER_MS)
    assertTrue("a hand-off never leaves models loaded", PolitePolicy.RELEASE_AFTER_MS < RunHandoff.HAND_OFF_AFTER_MS)
  }

  @Test fun theTwoTimersAreIndependent() {
    // 2 min polite on a tight phone: models released, run still here (a glance that ends at screen-off resumes inside the same run)
    val twoMin = 2 * 60_000L
    assertTrue(PolitePolicy.releaseDue(twoMin, released = false, memoryTight = true))
    assertFalse(RunHandoff.handOffDue(twoMin))
    assertFalse(PolitePolicy.releaseDue(twoMin, released = true, memoryTight = true))
    // ...and on a phone with room the models stay for the whole wait; the hand-off (the run ending) is what gives them back
    assertFalse(PolitePolicy.releaseDue(twoMin, released = false, memoryTight = false))
    // 5 min polite: the run leaves
    assertTrue(RunHandoff.handOffDue(5 * 60_000L))
  }

  @Test fun aRunThatStartsPoliteTakesNoForegroundService() {
    assertTrue(RunHandoff.startsWithoutService(polite = true))
    assertFalse(RunHandoff.startsWithoutService(polite = false))
  }

  @Test fun onlyTheForegroundServiceLimitAsksForAPlainRerun() {
    assertTrue(RunHandoff.resumeAfterStop(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
    // The system's own restarts (constraints, quota, preemption) come back through WorkManager by themselves.
    assertFalse(RunHandoff.resumeAfterStop(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING))
    assertFalse(RunHandoff.resumeAfterStop(WorkInfo.STOP_REASON_QUOTA))
    assertFalse(RunHandoff.resumeAfterStop(WorkInfo.STOP_REASON_NOT_STOPPED))
  }

  @Test fun theResumeSafetyNetIsShortButNotBusy() {
    assertTrue(RunHandoff.RESUME_DELAY_MIN in 5L..30L)
  }

  @Test fun screenOffResumesOnlyAHandedOffRun() {
    assertTrue(RunHandoff.resumeOnScreenOff(handOffPending = true))
    assertFalse("every screen-off must not start a worker", RunHandoff.resumeOnScreenOff(handOffPending = false))
  }

  @Test fun aRunThatEndsOnBatteryWithWorkLeftPutsTheChargerNetBack() {
    assertTrue(RunHandoff.chargerNetWanted(charging = false, paused = false, workLeft = true, alreadyWaiting = false))
  }

  @Test fun theChargerNetIsNeverAskedForWhenItCouldOnlyBeWasteful() {
    assertFalse("on the charger a CHARGING job would start at once and a polite phone would hand it off in a loop",
      RunHandoff.chargerNetWanted(charging = true, paused = false, workLeft = true, alreadyWaiting = false))
    assertFalse("paused by the person", RunHandoff.chargerNetWanted(charging = false, paused = true, workLeft = true, alreadyWaiting = false))
    assertFalse("nothing is left to do", RunHandoff.chargerNetWanted(charging = false, paused = false, workLeft = false, alreadyWaiting = false))
    assertFalse("one already waits", RunHandoff.chargerNetWanted(charging = false, paused = false, workLeft = true, alreadyWaiting = true))
  }

  @Test fun aCachedProcessWithNoServiceMayNotDoHeavyWork() {
    assertEquals(android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED, RunHandoff.IMPORTANCE_CACHED)
    for (cached in listOf(400, 500, 900, 1000)) assertTrue("importance $cached", RunHandoff.unprotected(cached, holdsForegroundService = false))
  }

  @Test fun aServiceAJobOrAnOpenScreenProtectsTheProcess() {
    // in front 100, foreground service 125, visible 200, perceptible 230, serving a job (a bound service) 300
    for (live in listOf(0, 100, 125, 200, 230, 300)) assertFalse("importance $live", RunHandoff.unprotected(live, holdsForegroundService = false))
    assertFalse("a held foreground service protects whatever the number says", RunHandoff.unprotected(900, holdsForegroundService = true))
  }

  @Test fun aJobThatLostTheLockWaitsOnlyWhereItIsWhatProtectsTheProcess() {
    for (job in listOf(200, 230, 270, 300, 399)) assertTrue("importance $job", RunHandoff.lendsProtection(job, holdsForegroundService = false))
    for (other in listOf(0, 100, 125)) assertFalse("a screen on show or a foreground service already protects: $other", RunHandoff.lendsProtection(other, holdsForegroundService = false))
    for (cached in listOf(400, 500, 1000)) assertFalse("cached: nothing protects the wait: $cached", RunHandoff.lendsProtection(cached, holdsForegroundService = false))
    assertFalse("holding a foreground service", RunHandoff.lendsProtection(300, holdsForegroundService = true))
  }

  @Test fun theWaitEndsBeforeTheJobLimit() {
    assertTrue(RunHandoff.LEND_WAIT_MS < 10 * 60_000L)
    // the waiting job protects exactly the importances the gate calls protected
    for (i in 0..1000 step 5) if (RunHandoff.lendsProtection(i, false)) assertFalse("importance $i", RunHandoff.unprotected(i, false))
  }

  @Test fun theGateAsksTheSystemAtMostOnceEveryTwoSeconds() {
    var asked = 0
    var importance = 100
    var now = 10_000L
    val gate = ProtectionGate(importance = { asked++; importance }, now = { now })
    assertFalse(gate.lost(holdsForegroundService = false))
    importance = 900
    now += 1_999
    assertFalse("the answer is kept for two seconds", gate.lost(holdsForegroundService = false))
    assertEquals(1, asked)
    now += 1
    assertTrue("a fresh look sees the process cached", gate.lost(holdsForegroundService = false))
    assertEquals(2, asked)
    assertTrue(gate.lost(holdsForegroundService = false))
    assertEquals(2, asked)
  }

  @Test fun aRunHoldingAServiceNeverAsksTheSystem() {
    var asked = 0
    val gate = ProtectionGate(importance = { asked++; 900 }, now = { 0L })
    assertFalse(gate.lost(holdsForegroundService = true))
    assertEquals(0, asked)
    assertTrue("the service ended: the first look finds a cached process", gate.lost(holdsForegroundService = false))
  }

  @Test fun theChargerNetCannotLoopHotAndStillBeingArmedLongBeforeAPlugIn() {
    assertTrue("long enough to break a start-end-rearm loop", RunHandoff.NET_ARM_DELAY_S >= 10)
    assertTrue("short enough that a net armed minutes ago has long passed it", RunHandoff.NET_ARM_DELAY_S <= 60)
    val engine = java.io.File("src/main/java/com/craneai/findr/engine/FindrEngine.kt").readText()
    val net = engine.substringAfter("suspend fun keepChargerNet()").substringBefore("A run handed itself off")
    assertTrue("the net's request carries the delay", net.contains("setInitialDelay(RunHandoff.NET_ARM_DELAY_S"))
  }

  @Test fun quotaStandbyAndOtherSystemStopsAskForADelayedRerun() {
    for (r in listOf(
      WorkInfo.STOP_REASON_QUOTA, WorkInfo.STOP_REASON_APP_STANDBY, WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
      WorkInfo.STOP_REASON_PREEMPT, WorkInfo.STOP_REASON_DEVICE_STATE, WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED, WorkInfo.STOP_REASON_UNKNOWN,
    )) assertTrue("stop reason $r", RunHandoff.followUpAfterStop(r))
  }

  @Test fun stopsThatWereAskedForOrAreRefusedAreNotFollowedUp() {
    for (r in listOf(
      WorkInfo.STOP_REASON_CANCELLED_BY_APP, WorkInfo.STOP_REASON_USER, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
      WorkInfo.STOP_REASON_CONSTRAINT_CHARGING, WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW, WorkInfo.STOP_REASON_NOT_STOPPED,
    )) assertFalse("stop reason $r", RunHandoff.followUpAfterStop(r))
  }
}
