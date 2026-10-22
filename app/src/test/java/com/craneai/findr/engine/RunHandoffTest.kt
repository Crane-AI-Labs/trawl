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
}
