package com.craneai.findr.diag

import android.app.ApplicationExitInfo
import android.app.ActivityManager.RunningAppProcessInfo as Proc
import org.junit.Assert.assertEquals
import org.junit.Test

/** :core writes the platform's exit-reason and importance numbers out so it needs no Android; these are the platform's own. */
class ExitConstantsTest {
  @Test fun reasonNumbersMatchThePlatform() {
    assertEquals(ApplicationExitInfo.REASON_CRASH, ExitRecord.REASON_CRASH)
    assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, ExitRecord.REASON_CRASH_NATIVE)
    assertEquals(ApplicationExitInfo.REASON_ANR, ExitRecord.REASON_ANR)
    assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, ExitRecord.REASON_LOW_MEMORY)
    assertEquals(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ExitRecord.REASON_INITIALIZATION_FAILURE)
    assertEquals(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ExitRecord.REASON_EXCESSIVE_RESOURCE_USAGE)
  }

  @Test fun importanceNumbersMatchThePlatform() {
    assertEquals(Proc.IMPORTANCE_FOREGROUND, ExitEvents.IMPORTANCE_FOREGROUND)
    assertEquals(Proc.IMPORTANCE_FOREGROUND_SERVICE, ExitEvents.IMPORTANCE_FOREGROUND_SERVICE)
    assertEquals(Proc.IMPORTANCE_VISIBLE, ExitEvents.IMPORTANCE_VISIBLE)
    assertEquals(Proc.IMPORTANCE_SERVICE, ExitEvents.IMPORTANCE_SERVICE)
    assertEquals(Proc.IMPORTANCE_CACHED, ExitEvents.IMPORTANCE_CACHED)
  }
}
