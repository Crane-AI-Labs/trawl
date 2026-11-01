package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FgsBudgetTest {
  private val h = 60 * 60_000L
  private val min = 60_000L
  private fun budget() = FgsBudget()

  // ---- the split --------------------------------------------------------------------------------------------------------

  @Test fun picturesVideosAndRecordingsAreMediaProcessingOnAndroid15() {
    for (k in listOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO, FileKind.AUDIO)) assertEquals("$k", FgsType.MEDIA_PROCESSING, FgsPolicy.preferred(k, 35))
    for (k in listOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.OTHER)) assertEquals("$k", FgsType.DATA_SYNC, FgsPolicy.preferred(k, 36))
  }

  @Test fun beforeAndroid15EverythingIsDataSync() {
    for (k in FileKind.entries) assertEquals(FgsType.DATA_SYNC, FgsPolicy.preferred(k, 34))
    assertEquals(setOf(FgsType.DATA_SYNC), FgsPolicy.available(34))
    assertEquals(setOf(FgsType.DATA_SYNC, FgsType.MEDIA_PROCESSING), FgsPolicy.available(35))
  }

  @Test fun aBuildWithoutTheMediaProcessingTypeRunsEverythingUnderDataSync() {
    for (k in FileKind.entries) for (sdk in listOf(34, 35, 36)) assertEquals("$k $sdk", FgsType.DATA_SYNC, FgsPolicy.preferred(k, sdk, mediaProcessing = false))
    assertEquals(setOf(FgsType.DATA_SYNC), FgsPolicy.available(36, mediaProcessing = false))
    val b = FgsBudget.forSdk(36, mediaProcessing = false)
    assertEquals(FgsType.DATA_SYNC, b.choose(FgsType.MEDIA_PROCESSING, 0))   // asked for the type the build does not declare: data sync
  }

  // ---- counting ---------------------------------------------------------------------------------------------------------

  @Test fun eachTypeCountsItsOwnTime() {
    val b = budget()
    b.enter(FgsType.DATA_SYNC, 0)
    b.enter(FgsType.MEDIA_PROCESSING, 1 * h)   // switched: 1 h of data sync banked
    b.leave(3 * h)                             // 2 h of media processing
    assertEquals(5 * h, b.remainingMs(FgsType.DATA_SYNC, 3 * h))
    assertEquals(4 * h, b.remainingMs(FgsType.MEDIA_PROCESSING, 3 * h))
  }

  @Test fun theOpenStretchCountsWithoutLeaving() {
    val b = budget()
    b.enter(FgsType.DATA_SYNC, 0)
    assertEquals(4 * h, b.remainingMs(FgsType.DATA_SYNC, 2 * h))
    assertEquals(6 * h, b.remainingMs(FgsType.MEDIA_PROCESSING, 2 * h))
  }

  @Test fun anOldWindowIsForgottenAfter24Hours() {
    val b = budget()
    b.enter(FgsType.DATA_SYNC, 0)
    b.leave(5 * h)
    assertEquals(1 * h, b.remainingMs(FgsType.DATA_SYNC, 6 * h))
    assertEquals(6 * h, b.remainingMs(FgsType.DATA_SYNC, 24 * h))
  }

  @Test fun openingTheAppRestartsBothMeters() {
    val b = budget()
    b.enter(FgsType.DATA_SYNC, 0)
    b.leave(6 * h)
    b.markExhausted(FgsType.MEDIA_PROCESSING, 6 * h)
    assertNull(b.choose(FgsType.DATA_SYNC, 7 * h))
    b.uiForeground(7 * h)
    assertEquals(6 * h, b.remainingMs(FgsType.DATA_SYNC, 7 * h))
    assertEquals(FgsType.MEDIA_PROCESSING, b.choose(FgsType.MEDIA_PROCESSING, 7 * h))
  }

  @Test fun aServiceRunningWhenTheAppOpensStartsCountingAgain() {
    val b = budget()
    b.enter(FgsType.DATA_SYNC, 0)
    b.uiForeground(5 * h)
    assertEquals(6 * h - 1 * h, b.remainingMs(FgsType.DATA_SYNC, 6 * h))
  }

  // ---- choosing ---------------------------------------------------------------------------------------------------------

  @Test fun thePreferredTypeIsUsedWhileItHasRoom() {
    assertEquals(FgsType.MEDIA_PROCESSING, budget().choose(FgsType.MEDIA_PROCESSING, 0))
    assertEquals(FgsType.DATA_SYNC, budget().choose(FgsType.DATA_SYNC, 0))
  }

  @Test fun aSpentTypeFallsBackToTheOtherOne() {
    val b = budget()
    b.enter(FgsType.MEDIA_PROCESSING, 0)
    b.leave(6 * h - 5 * min)   // 5 minutes left: inside the margin
    assertEquals(FgsType.DATA_SYNC, b.choose(FgsType.MEDIA_PROCESSING, 6 * h))
  }

  @Test fun theMarginIsLongerThanTheLongestFile() {
    assertTrue(FgsBudget.MARGIN_MS > IndexWorkerTimeouts.LONGEST_FILE_MS)
    val b = budget()
    b.enter(FgsType.DATA_SYNC, 0)
    b.leave(6 * h - FgsBudget.MARGIN_MS)
    assertEquals("exactly the margin left is not enough for another file", FgsType.MEDIA_PROCESSING, b.choose(FgsType.DATA_SYNC, 6 * h))
  }

  @Test fun bothTypesSpentMeansNoServiceAtAll() {
    val b = budget()
    b.markExhausted(FgsType.DATA_SYNC, 0)
    b.markExhausted(FgsType.MEDIA_PROCESSING, 0)
    assertNull(b.choose(FgsType.DATA_SYNC, 1 * h))
    assertNull(b.choose(FgsType.MEDIA_PROCESSING, 1 * h))
  }

  @Test fun beforeAndroid15NothingEverRunsOut() {
    val b = FgsBudget.forSdk(34)
    b.enter(FgsType.DATA_SYNC, 0)
    assertEquals(FgsType.DATA_SYNC, b.choose(FgsType.MEDIA_PROCESSING, 100 * h))   // media processing does not exist there
    assertEquals(FgsType.DATA_SYNC, b.choose(FgsType.DATA_SYNC, 100 * h))
  }

  @Test fun onAndroid15BothTypesGiveTwelveHoursInAll() {
    val b = FgsBudget.forSdk(36)
    var now = 0L
    var served = 0L
    // Ten-minute files, preferring media processing: the day ends when both types are down to the margin.
    while (true) {
      val t = b.choose(FgsType.MEDIA_PROCESSING, now) ?: break
      b.enter(t, now)
      now += 10 * min
      served += 10 * min
    }
    b.leave(now)
    assertTrue("served $served", served >= 12 * h - 2 * FgsBudget.MARGIN_MS)
    assertTrue(served <= 12 * h)
  }

  // ---- surviving a process death ------------------------------------------------------------------------------------------

  @Test fun theLedgerSurvivesBeingSavedAndLoaded() {
    val a = budget()
    a.enter(FgsType.DATA_SYNC, 0)
    a.enter(FgsType.MEDIA_PROCESSING, 2 * h)
    val text = a.encode(3 * h)             // the last heartbeat: media processing has run 1 h
    val b = budget()
    b.restore(text, 4 * h)                  // the process died after the heartbeat and came back an hour later
    assertEquals(4 * h, b.remainingMs(FgsType.DATA_SYNC, 4 * h))
    assertEquals(5 * h, b.remainingMs(FgsType.MEDIA_PROCESSING, 4 * h))
    assertNull("no stretch is open after a restore", b.current)
  }

  @Test fun aRestoredWindowStillExpires() {
    val a = budget()
    a.enter(FgsType.DATA_SYNC, 0)
    val text = a.encode(5 * h)
    val b = budget()
    b.restore(text, 25 * h)
    assertEquals(6 * h, b.remainingMs(FgsType.DATA_SYNC, 25 * h))
  }

  @Test fun garbageLeavesAnEmptyLedger() {
    val b = budget()
    b.restore("nonsense;;=1,x", 0)
    assertEquals(6 * h, b.remainingMs(FgsType.DATA_SYNC, 0))
    b.restore(null, 0)
    assertFalse(b.describe(0).isEmpty())
  }
}

/** The longest time one file may take in the worker (IndexWorker.timeoutFor: videos and recordings, 10 minutes). */
private object IndexWorkerTimeouts { const val LONGEST_FILE_MS = 10 * 60_000L }
