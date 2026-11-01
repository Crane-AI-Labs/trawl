package com.craneai.findr.diag

import android.app.ApplicationExitInfo
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** :core writes WorkManager's stop-reason numbers and the platform's exit-reason numbers out; these are the libraries' own. */
class StopReasonNamesTest {
  @Test fun stopReasonNumbersMatchWorkManager() {
    assertEquals(WorkInfo.STOP_REASON_NOT_STOPPED, StopReasons.NOT_STOPPED)
    assertEquals(WorkInfo.STOP_REASON_UNKNOWN, StopReasons.UNKNOWN)
    assertEquals(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT, StopReasons.FOREGROUND_SERVICE_TIMEOUT)
    assertEquals(WorkInfo.STOP_REASON_CANCELLED_BY_APP, StopReasons.CANCELLED_BY_APP)
    assertEquals(WorkInfo.STOP_REASON_PREEMPT, StopReasons.PREEMPT)
    assertEquals(WorkInfo.STOP_REASON_TIMEOUT, StopReasons.TIMEOUT)
    assertEquals(WorkInfo.STOP_REASON_DEVICE_STATE, StopReasons.DEVICE_STATE)
    assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW, StopReasons.CONSTRAINT_BATTERY_NOT_LOW)
    assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING, StopReasons.CONSTRAINT_CHARGING)
    assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, StopReasons.CONSTRAINT_CONNECTIVITY)
    assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE, StopReasons.CONSTRAINT_DEVICE_IDLE)
    assertEquals(WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW, StopReasons.CONSTRAINT_STORAGE_NOT_LOW)
    assertEquals(WorkInfo.STOP_REASON_QUOTA, StopReasons.QUOTA)
    assertEquals(WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION, StopReasons.BACKGROUND_RESTRICTION)
    assertEquals(WorkInfo.STOP_REASON_APP_STANDBY, StopReasons.APP_STANDBY)
    assertEquals(WorkInfo.STOP_REASON_USER, StopReasons.USER)
    assertEquals(WorkInfo.STOP_REASON_SYSTEM_PROCESSING, StopReasons.SYSTEM_PROCESSING)
    assertEquals(WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED, StopReasons.ESTIMATED_APP_LAUNCH_TIME_CHANGED)
  }

  @Test fun everyNamedStopReasonHasAReadableName() {
    for (c in listOf(-256, -512, -128) + (1..15)) assertTrue("code $c", !StopReasons.name(c).startsWith("CODE_"))
  }

  @Test fun exitReasonNamesFollowThePlatformNumbers() {
    assertEquals("crash", ExitNames.name(ApplicationExitInfo.REASON_CRASH))
    assertEquals("native crash", ExitNames.name(ApplicationExitInfo.REASON_CRASH_NATIVE))
    assertEquals("ANR", ExitNames.name(ApplicationExitInfo.REASON_ANR))
    assertEquals("low memory", ExitNames.name(ApplicationExitInfo.REASON_LOW_MEMORY))
    assertEquals("signaled (killed)", ExitNames.name(ApplicationExitInfo.REASON_SIGNALED))
    assertEquals("user requested", ExitNames.name(ApplicationExitInfo.REASON_USER_REQUESTED))
    assertEquals("user stopped (force stop)", ExitNames.name(ApplicationExitInfo.REASON_USER_STOPPED))
    assertEquals("excessive resource use", ExitNames.name(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
    assertEquals("frozen", ExitNames.name(ApplicationExitInfo.REASON_FREEZER))
    assertEquals("other", ExitNames.name(ApplicationExitInfo.REASON_OTHER))
    assertEquals("exit by itself", ExitNames.name(ApplicationExitInfo.REASON_EXIT_SELF))
    assertEquals("dependency died", ExitNames.name(ApplicationExitInfo.REASON_DEPENDENCY_DIED))
    assertEquals("permission change", ExitNames.name(ApplicationExitInfo.REASON_PERMISSION_CHANGE))
    assertEquals("start-up failure", ExitNames.name(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
  }
}
