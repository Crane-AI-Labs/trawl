package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.PauseReason
import com.craneai.findr.engine.IndexPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPolicyTest {
  private fun decide(
    charging: Boolean = false, pct: Int = 80, thermal: Int = 0, strict: Boolean = false, full: Boolean = false,
  ) = IndexPolicy.decide(charging, pct, thermal, strict, full)

  @Test fun onTheChargerEverythingRunsAtAnyLevelOrHeat() {
    for (pct in listOf(-1, 1, 29, 30, 100)) for (thermal in 0..6) {
      val d = decide(charging = true, pct = pct, thermal = thermal)
      assertEquals(Tier.BULK, d.tier)
      assertNull(d.heldBy)
    }
  }

  @Test fun onBatteryWithChargeAndCoolPhoneTheLightWorkRunsAndFullQualityWaitsForTheCharger() {
    val d = decide()
    assertEquals(Tier.LIGHT, d.tier)
    assertEquals(PauseReason.NOT_CHARGING, d.heldBy)
    assertTrue(d.lightAllowed)
    assertFalse(d.bulkAllowed)
  }

  @Test fun thirtyPercentIsEnoughTwentyNineIsNot() {
    assertEquals(Tier.LIGHT, decide(pct = 30).tier)
    assertEquals(Tier.LIGHT, decide(pct = IndexPolicy.MIN_BATTERY_PCT).tier)
    val low = decide(pct = 29)
    assertEquals(Tier.WORDS_ONLY, low.tier)
    assertEquals(PauseReason.BATTERY_LOW, low.heldBy)
    assertFalse(low.lightAllowed)
  }

  @Test fun anUnknownBatteryLevelDoesNotStopTheWork() {
    assertEquals(Tier.LIGHT, decide(pct = -1).tier)
  }

  @Test fun severeHeatStopsEverythingButWordsOnBattery() {
    assertEquals(Tier.LIGHT, decide(thermal = 1).tier)
    val hot = decide(thermal = IndexPolicy.HOT_THERMAL_STATUS)
    assertEquals(Tier.WORDS_ONLY, hot.tier)
    assertEquals(PauseReason.TOO_HOT, hot.heldBy)
    assertEquals(Tier.WORDS_ONLY, decide(thermal = 4).tier)
  }

  @Test fun moderateHeatKeepsQuickLookButHoldsFullQualityBack() {
    // A Galaxy S24 reported MODERATE after a warm download and sat at "Cooling down" with quick look 0 of 11,804.
    assertEquals(2, IndexPolicy.WARM_THERMAL_STATUS)
    val warm = decide(thermal = IndexPolicy.WARM_THERMAL_STATUS)
    assertEquals(Tier.LIGHT, warm.tier)
    assertTrue("quick look runs", warm.lightAllowed)
    assertFalse("the GPU full-quality work of new files waits", warm.newFilesFullQuality)
    assertFalse(warm.bulkAllowed)
    assertTrue("normal temperature still reads new files at full quality", decide(thermal = 1).newFilesFullQuality)
    assertFalse("words-only has none", decide(thermal = 3).newFilesFullQuality)
  }

  @Test fun theChargerIsNotSlowedByModerateHeat() {
    assertEquals(Tier.BULK, decide(charging = true, thermal = 2).tier)
  }

  @Test fun lowBatteryIsReportedBeforeHeat() {
    assertEquals(PauseReason.BATTERY_LOW, decide(pct = 10, thermal = 3).heldBy)
  }

  @Test fun theStrictSettingKeepsBatteryToWordsOnly() {
    val d = decide(strict = true)
    assertEquals(Tier.WORDS_ONLY, d.tier)
    assertEquals(PauseReason.NOT_CHARGING, d.heldBy)
    assertEquals(Tier.BULK, decide(charging = true, strict = true).tier)
  }

  @Test fun fullIndexingOnBatteryUnlocksTheBacklogButStillRespectsLevelAndHeat() {
    val d = decide(full = true)
    assertEquals(Tier.BULK, d.tier)
    assertNull(d.heldBy)
    assertEquals(Tier.WORDS_ONLY, decide(full = true, pct = 25).tier)
    assertEquals(PauseReason.TOO_HOT, decide(full = true, thermal = 3).heldBy)
    // warm (MODERATE): not the backlog on battery, but quick look goes on
    assertEquals(Tier.LIGHT, decide(full = true, thermal = 2).tier)
  }

  @Test fun strictWinsOverFullOnBattery() {
    val d = decide(strict = true, full = true)
    assertEquals(Tier.WORDS_ONLY, d.tier)
    assertEquals(PauseReason.NOT_CHARGING, d.heldBy)
  }

  @Test fun thePerRunCapsAreTheAgreedOnes() {
    assertEquals(20, IndexPolicy.NEW_FILES_PER_RUN)
    assertEquals(120_000L, IndexPolicy.NEW_ENCODE_MS_PER_RUN)
  }

  @Test fun newFilesOnBatteryAreSmallKindsOnly() {
    assertTrue(FileKind.IMAGE in IndexPolicy.NEW_FILE_KINDS)
    assertTrue(FileKind.SCREENSHOT in IndexPolicy.NEW_FILE_KINDS)
    assertFalse(FileKind.VIDEO in IndexPolicy.NEW_FILE_KINDS)
    assertFalse(FileKind.AUDIO in IndexPolicy.NEW_FILE_KINDS)
  }

  // ---- D12 follow-up: the next capped batch of new files (the 3 h A06 night left 80 new photos with nothing queued) ----

  private fun follow(d: IndexPolicy.Decision = decide(), paused: Boolean = false, waiting: Boolean = true) =
    IndexPolicy.followUp(d, paused, waiting)

  @Test fun onBatteryWithNewFilesWaitingTheNextBatchIsAskedFor() {
    assertEquals(IndexPolicy.FollowUp.NEXT_BATCH, follow(decide(pct = 80)))
    assertEquals(IndexPolicy.FollowUp.NEXT_BATCH, follow(decide(pct = 30)))   // the threshold itself still runs (D12: >= 30%)
  }

  @Test fun nothingWaitingMeansNothingIsQueued() {
    assertEquals(IndexPolicy.FollowUp.NONE, follow(waiting = false))
  }

  @Test fun pausedNeverQueuesAnything() {
    assertEquals(IndexPolicy.FollowUp.NONE, follow(paused = true))
    assertEquals(IndexPolicy.FollowUp.NONE, follow(decide(pct = 5), paused = true))
  }

  @Test fun lowBatteryOrHeatLooksAgainLaterInsteadOfRunning() {
    assertEquals(IndexPolicy.FollowUp.RETRY_LATER, follow(decide(pct = 29)))
    assertEquals(IndexPolicy.FollowUp.RETRY_LATER, follow(decide(thermal = IndexPolicy.HOT_THERMAL_STATUS)))
    assertEquals(IndexPolicy.FollowUp.RETRY_LATER, follow(decide(pct = 10, thermal = 3)))
  }

  @Test fun onTheChargerTheBulkPassTakesNewFilesSoNothingExtraIsQueued() {
    assertEquals(IndexPolicy.FollowUp.NONE, follow(decide(charging = true)))
    assertEquals(IndexPolicy.FollowUp.NONE, follow(decide(charging = true, pct = 5)))
  }

  @Test fun theStrictChargingOnlySettingStillKeepsEverythingHeavierOffBattery() {
    assertEquals(IndexPolicy.FollowUp.NONE, follow(decide(strict = true)))
  }

  @Test fun fullIndexingOnBatteryNeedsNoFollowUpEither() {
    assertEquals(IndexPolicy.FollowUp.NONE, follow(decide(full = true)))
  }

  @Test fun d12ThresholdsAreUnchanged() {
    assertEquals(30, IndexPolicy.MIN_BATTERY_PCT)
    assertEquals(20, IndexPolicy.NEW_FILES_PER_RUN)
    assertEquals(2 * 60_000L, IndexPolicy.NEW_ENCODE_MS_PER_RUN)
  }
}
