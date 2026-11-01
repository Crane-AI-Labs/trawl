package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which phones keep one heavy model loaded at a time, and when the speech model and audio encoder may leave. */
class MemoryBudgetTest {
  @Test fun theFourGigabytePhonesAreTight() {
    assertTrue(MemoryBudget.tight(3_633, 1_800))   // Galaxy A06, sold as 4 GB, about 1.8 GB free
    assertTrue(MemoryBudget.tight(3_700, 1_800))   // TECNO Spark 50
    assertTrue(MemoryBudget.tight(1_900, 900))     // a 2 GB phone (lite profile) is tight too
  }

  @Test fun roomyPhonesAreNot() {
    assertFalse(MemoryBudget.tight(7_700, 4_000))  // Infinix X6885, 8 GB
    assertFalse(MemoryBudget.tight(11_000, 6_000)) // a 12 GB flagship
    assertFalse(MemoryBudget.tight(5_600, 2_500))  // a 6 GB phone with room
  }

  @Test fun aSixGigabytePhoneWithLittleFreeIsTight() {
    assertTrue(MemoryBudget.tight(5_600, 1_200))
    assertFalse(MemoryBudget.tight(5_600, 1_500))
  }

  @Test fun unknownNumbersNeverMakeAPhoneTight() {
    assertFalse(MemoryBudget.tight(0, 0))
    assertFalse(MemoryBudget.tight(8_000, 0))
  }

  @Test fun theTightLineSitsBetweenFourAndSixGigabytes() {
    assertTrue(MemoryBudget.tight(MemoryBudget.TIGHT_TOTAL_MB, 4_000))
    assertFalse(MemoryBudget.tight(MemoryBudget.TIGHT_TOTAL_MB + 1, 4_000))
  }

  @Test fun soundModelsLeaveAfterTheLastRecordingOfAStretch() {
    assertTrue(IndexWorker.soundModelsCanLeave(FileKind.VIDEO, FileKind.IMAGE))
    assertTrue(IndexWorker.soundModelsCanLeave(FileKind.AUDIO, FileKind.DOCUMENT))
    assertTrue(IndexWorker.soundModelsCanLeave(FileKind.VIDEO, FileKind.SCREENSHOT))
  }

  @Test fun theyStayWhileTheNextFileNeedsThem() {
    assertFalse(IndexWorker.soundModelsCanLeave(FileKind.VIDEO, FileKind.VIDEO))
    assertFalse(IndexWorker.soundModelsCanLeave(FileKind.VIDEO, FileKind.AUDIO))
    assertFalse(IndexWorker.soundModelsCanLeave(FileKind.AUDIO, FileKind.VIDEO))
  }

  @Test fun theyStayWhenNothingIsKnownAboutTheNextFile() {
    assertFalse(IndexWorker.soundModelsCanLeave(FileKind.VIDEO, null))
  }

  @Test fun theyLeaveAtTheEndOfAMediaTurnEvenWhenTheNextFileIsNotKnown() {
    assertTrue(IndexWorker.soundModelsCanLeave(FileKind.VIDEO, null, endOfMediaTurn = true))
    assertTrue(IndexWorker.soundModelsCanLeave(FileKind.AUDIO, null, endOfMediaTurn = true))
    assertFalse(IndexWorker.soundModelsCanLeave(FileKind.AUDIO, FileKind.VIDEO, endOfMediaTurn = true))   // a known next recording still wins
    assertFalse(IndexWorker.soundModelsCanLeave(FileKind.IMAGE, null, endOfMediaTurn = true))
  }

  @Test fun picturesAndDocumentsNeverTriggerAnUnload() {
    assertEquals(false, IndexWorker.soundModelsCanLeave(FileKind.IMAGE, FileKind.IMAGE))
    assertEquals(false, IndexWorker.soundModelsCanLeave(FileKind.DOCUMENT, FileKind.IMAGE))
  }

  @Test fun aRoomyPhoneKeepsItsModelsWhenARunEnds() {
    assertTrue(MemoryBudget.keepResident(MemoryBudget.tight(7_900, 3_000)))    // Infinix, 8 GB
    assertTrue(MemoryBudget.keepResident(MemoryBudget.tight(11_500, 6_000)))   // S24, 12 GB
  }

  @Test fun aTightPhoneGivesThemBack() {
    assertFalse(MemoryBudget.keepResident(MemoryBudget.tight(3_633, 1_800)))   // A06 / TECNO KN4, 4 GB
    assertFalse(MemoryBudget.keepResident(MemoryBudget.tight(7_900, 900)))     // roomy in total, but nearly full right now
  }

  @Test fun anIdleAppReleasesOnlyWhenTheSystemReallyNeedsMemory() {
    assertTrue(MemoryBudget.releaseWhenIdle(10))   // RUNNING_LOW
    assertTrue(MemoryBudget.releaseWhenIdle(15))   // RUNNING_CRITICAL
    assertTrue(MemoryBudget.releaseWhenIdle(60))   // MODERATE (cached, being killed)
    assertTrue(MemoryBudget.releaseWhenIdle(80))   // COMPLETE
  }

  // ---- Android 14+ sends only trim levels 20 and 40: the phone's own numbers say whether it is short of memory (A6) ----

  @Test fun freeMemoryUnderOneAndAHalfTimesTheSystemsLineIsPressure() {
    assertTrue(MemoryBudget.pressure(availMb = 400, thresholdMb = 300))    // 400 < 450
    assertFalse(MemoryBudget.pressure(availMb = 450, thresholdMb = 300))   // the edge is not pressure
    assertFalse(MemoryBudget.pressure(availMb = 1_700, thresholdMb = 300)) // a 4 GB phone at rest
  }

  @Test fun theSystemsOwnLowMemoryFlagIsPressureWhateverTheNumbers() {
    assertTrue(MemoryBudget.pressure(availMb = 1_700, thresholdMb = 300, lowMemory = true))
  }

  @Test fun unknownNumbersNeverSayPressure() {
    assertFalse(MemoryBudget.pressure(availMb = 0, thresholdMb = 300))
    assertFalse(MemoryBudget.pressure(availMb = 200, thresholdMb = 0))
  }

  @Test fun theMultipleIsTheKnob() {
    assertFalse(MemoryBudget.pressure(availMb = 400, thresholdMb = 300, multiple = 1.2))
    assertTrue(MemoryBudget.pressure(availMb = 400, thresholdMb = 300, multiple = 2.0))
  }

  @Test fun aLevelThatStillArrivesCountsOnlyWhenTheNumbersAgree() {
    assertTrue(MemoryBudget.releaseWhenIdle(40, pressureNow = true))     // BACKGROUND on Android 14+, with the phone short of memory
    assertFalse(MemoryBudget.releaseWhenIdle(40, pressureNow = false))   // BACKGROUND alone is still just the cached list
    assertFalse(MemoryBudget.releaseWhenIdle(20, pressureNow = true))    // UI_HIDDEN fires whenever our last screen goes away
    assertTrue(MemoryBudget.releaseWhenIdle(15, pressureNow = false))    // the old levels keep working on Android 13 and below
    assertFalse(MemoryBudget.releaseWhenIdle(5, pressureNow = false))
  }

  @Test fun leavingTheAppOrSittingInTheCacheIsNotPressure() {
    assertFalse(MemoryBudget.releaseWhenIdle(5))    // RUNNING_MODERATE, an early hint
    assertFalse(MemoryBudget.releaseWhenIdle(20))   // UI_HIDDEN: fires every time the last screen goes away
    assertFalse(MemoryBudget.releaseWhenIdle(40))   // BACKGROUND: the process merely joined the cached list
  }
}
