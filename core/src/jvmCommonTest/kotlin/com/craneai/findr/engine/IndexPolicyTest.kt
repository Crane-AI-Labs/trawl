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
    charging: Boolean = false, pct: Int = 80, thermal: Int = 0, strict: Boolean = false, full: Boolean = false, saver: Boolean = false,
  ) = IndexPolicy.decide(charging, pct, thermal, strict, full, saver)

  // ---- Android's Battery Saver (A2): Gentle already holds on battery, Balanced keeps the light share, Fast ignores it ----

  @Test fun batterySaverNarrowsBalancedToTheLightShareOnBattery() {
    val d = decide(saver = true)
    assertEquals(Tier.LIGHT, d.tier)
    assertTrue("quick look still runs", d.lightAllowed)
    assertFalse("the GPU work of new files waits", d.newFilesFullQuality)
    assertFalse(d.bulkAllowed)
    assertEquals(PauseReason.NOT_CHARGING, d.heldBy)   // plugging in lifts it, so the meter may say so
  }

  @Test fun batterySaverChangesNothingForGentleWhichIsAlreadyHeldToWordsOnBattery() {
    assertEquals(decide(strict = true), decide(strict = true, saver = true))
    assertEquals(Tier.WORDS_ONLY, decide(strict = true, saver = true).tier)
  }

  @Test fun fastIgnoresBatterySaver() {
    assertEquals(decide(full = true), decide(full = true, saver = true))
    assertEquals(Tier.BULK, decide(full = true, saver = true).tier)
    // its own limits still apply
    assertEquals(PauseReason.BATTERY_LOW, decide(full = true, saver = true, pct = 25).heldBy)
    assertEquals(PauseReason.TOO_HOT, decide(full = true, saver = true, thermal = 3).heldBy)
  }

  @Test fun theChargerWinsOverBatterySaverForEveryPreset() {
    for ((strict, full) in listOf(false to false, true to false, false to true)) {
      val d = decide(charging = true, strict = strict, full = full, saver = true)
      assertEquals(Tier.BULK, d.tier)
      assertNull(d.heldBy)
    }
  }

  @Test fun batterySaverIsTheSameLightShareAsAWarmPhone() {
    assertEquals(decide(thermal = IndexPolicy.WARM_THERMAL_STATUS), decide(thermal = IndexPolicy.WARM_THERMAL_STATUS, saver = true))
    assertEquals(decide(thermal = IndexPolicy.WARM_THERMAL_STATUS), decide(saver = true))
  }

  @Test fun lowBatteryAndHeatStillReportFirstUnderBatterySaver() {
    assertEquals(PauseReason.BATTERY_LOW, decide(saver = true, pct = 29).heldBy)
    assertEquals(PauseReason.TOO_HOT, decide(saver = true, thermal = 3).heldBy)
  }

  @Test fun chargerThermalTableAppliesAtEveryBatteryLevelAndPreset() {
    val bulk = IndexPolicy.Decision(Tier.BULK, null)
    val warm = bulk.copy(reducedThreads = true)
    val hot = IndexPolicy.Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)
    val expected = listOf(bulk, bulk, warm, hot, hot, hot, hot)
    for (pct in listOf(-1, 1, 29, 30, 100)) for (thermal in 0..6)
      for (strict in listOf(false, true)) for (full in listOf(false, true)) for (saver in listOf(false, true)) {
        assertEquals("charging pct=$pct thermal=$thermal strict=$strict full=$full saver=$saver", expected[thermal],
          decide(charging = true, pct = pct, thermal = thermal, strict = strict, full = full, saver = saver))
      }
  }

  // ---- a corroborated heat signal (D48, D51): SEVERE counts only when the battery (>= 40 C) or the headroom (>= 1.0) agrees; a stale
  // status counts as none, and the charger cuts threads only when the phone measures warm (battery >= 38 C or headroom >= 0.85) ----

  @Test fun corroborateTable() {
    val batteries = listOf(Double.NaN, 25.0, 29.0, 37.0, 38.0, 39.9, 40.0, 45.0)
    val headrooms = listOf(Float.NaN, 0.0f, 0.5f, 0.85f, 0.99f, 1.0f, 1.4f)
    for (status in 0..6) for (b in batteries) for (h in headrooms) {
      val agrees = b.isNaN() || b >= IndexPolicy.HOT_BATTERY_C || (!h.isNaN() && h >= IndexPolicy.HOT_HEADROOM)
      val expected = if (status < IndexPolicy.HOT_THERMAL_STATUS || agrees) status else 0
      assertEquals("status=$status battery=$b headroom=$h", expected, IndexPolicy.corroborate(status, b, h))
    }
  }

  @Test fun measuredWarmthIsTheBatteryAt38OrTheHeadroomAt085() {
    assertFalse(IndexPolicy.measuredWarm(Double.NaN, Float.NaN))
    assertFalse(IndexPolicy.measuredWarm(37.9, 0.84f))
    assertTrue(IndexPolicy.measuredWarm(38.0, Float.NaN))
    assertTrue(IndexPolicy.measuredWarm(Double.NaN, 0.85f))
    assertTrue(IndexPolicy.measuredWarm(30.0, 0.9f))
  }

  private fun decideHeat(charging: Boolean, thermal: Int, batteryC: Double, headroom: Float = Float.NaN, pct: Int = 80, strict: Boolean = false, full: Boolean = false, saver: Boolean = false) =
    IndexPolicy.decide(charging, pct, thermal, strict, full, saver, batteryC = batteryC, headroom = headroom)

  /** The KN4 and the A06 (MT6769): status 3 for days, battery 29-37 C, headroom low or not given. Decided as a cool phone (D51). */
  @Test fun stuckCoolASevereStatusOnACoolPhoneCountsAsNone() {
    for (b in listOf(29.0, 33.5, 36.0, 37.9)) for (h in listOf(Float.NaN, 0.3f, 0.84f)) {
      val charger = decideHeat(charging = true, thermal = 3, batteryC = b, headroom = h)
      assertEquals("charger battery=$b headroom=$h", IndexPolicy.Decision(Tier.BULK, null, reducedThreads = false, thermalStale = true), charger)
      val battery = decideHeat(charging = false, thermal = 3, batteryC = b, headroom = h)
      assertEquals("battery battery=$b headroom=$h", IndexPolicy.Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, newFilesFullQuality = true, thermalStale = true), battery)
      assertEquals(IndexPolicy.FollowUp.NEXT_BATCH, IndexPolicy.followUp(battery, paused = false, newFilesWaiting = true))   // not "retry later": nothing is too hot
    }
    // Every status from SEVERE up is the same when nothing agrees, and the presets, Battery Saver and the battery floor keep their limits.
    for (thermal in 3..6) assertEquals(IndexPolicy.Decision(Tier.BULK, null, thermalStale = true), decideHeat(charging = true, thermal = thermal, batteryC = 34.0))
    assertEquals(IndexPolicy.Decision(Tier.WORDS_ONLY, PauseReason.NOT_CHARGING, thermalStale = true), decideHeat(false, 3, 34.0, strict = true))
    assertEquals(IndexPolicy.Decision(Tier.BULK, null, thermalStale = true), decideHeat(false, 3, 34.0, full = true))
    assertEquals(IndexPolicy.Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, newFilesFullQuality = false, thermalStale = true), decideHeat(false, 3, 34.0, saver = true))
    assertEquals(PauseReason.BATTERY_LOW, decideHeat(false, 3, 34.0, pct = 20).heldBy)
  }

  @Test fun stuckWarmByHeadroomCutsThreadsOnTheCharger() {
    for (h in listOf(0.85f, 0.9f, 0.99f)) for (thermal in 3..6) {
      assertEquals("headroom=$h thermal=$thermal", IndexPolicy.Decision(Tier.BULK, null, reducedThreads = true, thermalStale = true), decideHeat(true, thermal, batteryC = 35.0, headroom = h))
      // On battery the tier is the normal one (the warm share is for a MODERATE status Android reports).
      assertEquals(IndexPolicy.Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, thermalStale = true), decideHeat(false, thermal, batteryC = 35.0, headroom = h))
    }
  }

  @Test fun stuckWarmByBatteryCutsThreadsOnTheCharger() {
    for (b in listOf(38.0, 39.0, 39.9)) for (h in listOf(Float.NaN, 0.2f)) for (thermal in 3..6) {
      assertEquals("battery=$b headroom=$h thermal=$thermal", IndexPolicy.Decision(Tier.BULK, null, reducedThreads = true, thermalStale = true), decideHeat(true, thermal, batteryC = b, headroom = h))
      assertEquals(IndexPolicy.Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, thermalStale = true), decideHeat(false, thermal, batteryC = b, headroom = h))
    }
  }

  @Test fun aGenuinelyHotPhoneStillStopsAtWordsOnTheChargerAndOnBattery() {
    val hot = IndexPolicy.Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)
    for (charging in listOf(true, false)) for (thermal in 3..6) {
      assertEquals("battery hot charging=$charging thermal=$thermal", hot, decideHeat(charging, thermal, batteryC = 40.0))
      assertEquals("battery hot charging=$charging thermal=$thermal", hot, decideHeat(charging, thermal, batteryC = 46.0, headroom = 0.2f))
      assertEquals("headroom agrees charging=$charging thermal=$thermal", hot, decideHeat(charging, thermal, batteryC = 35.0, headroom = 1.0f))
      assertEquals("headroom agrees charging=$charging thermal=$thermal", hot, decideHeat(charging, thermal, batteryC = 35.0, headroom = 1.6f))
      assertEquals("no battery temperature: nothing disagrees charging=$charging thermal=$thermal", hot, decideHeat(charging, thermal, batteryC = Double.NaN))
    }
    assertEquals(IndexPolicy.FollowUp.RETRY_LATER, IndexPolicy.followUp(hot, paused = false, newFilesWaiting = true))
  }

  @Test fun belowSevereTheStatusIsUsedAsItIsAndMeasuredWarmthDecidesTheChargerThreads() {
    // A hot battery with a calm status is the worker's own battery rest (41 / 44 C), not the policy's: the tiers are unchanged.
    for (charging in listOf(true, false)) for (thermal in 0..2) for (b in listOf(Double.NaN, 30.0, 38.0, 45.0)) for (h in listOf(Float.NaN, 0.2f, 0.85f, 1.5f)) {
      val plain = decide(charging = charging, thermal = thermal)
      // MODERATE on the charger cuts threads when the phone measures warm, or measures nothing at all (Android's word is all there is).
      val reduced = charging && thermal == IndexPolicy.WARM_THERMAL_STATUS && (IndexPolicy.measuredWarm(b, h) || (b.isNaN() && h.isNaN()))
      assertEquals("charging=$charging thermal=$thermal battery=$b headroom=$h", plain.copy(reducedThreads = reduced), decideHeat(charging, thermal, b, h))
    }
  }

  @Test fun batteryThermalTableKeepsTheExistingLevelPresetAndSaverLimits() {
    val words = IndexPolicy.Decision(Tier.WORDS_ONLY, PauseReason.NOT_CHARGING)
    val light = IndexPolicy.Decision(Tier.LIGHT, PauseReason.NOT_CHARGING)
    val warm = light.copy(newFilesFullQuality = false)
    val hot = IndexPolicy.Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)
    val low = IndexPolicy.Decision(Tier.WORDS_ONLY, PauseReason.BATTERY_LOW)
    val bulk = IndexPolicy.Decision(Tier.BULK, null)
    for (strict in listOf(false, true)) for (full in listOf(false, true)) for (saver in listOf(false, true)) {
      val cool = when {
        strict -> words
        full -> bulk
        saver -> warm
        else -> light
      }
      val expected = listOf(cool, cool, if (strict) words else warm, hot, hot, hot, hot)
      for (pct in listOf(-1, 1, 29, 30, 100)) for (thermal in 0..6) {
        assertEquals("battery pct=$pct thermal=$thermal strict=$strict full=$full saver=$saver",
          if (pct in 0..29) low else expected[thermal],
          decide(pct = pct, thermal = thermal, strict = strict, full = full, saver = saver))
      }
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

  @Test fun moderateHeatOnTheChargerKeepsBulkWithReducedThreads() {
    val warm = decide(charging = true, thermal = 2)
    assertEquals(Tier.BULK, warm.tier)
    assertTrue(warm.bulkAllowed)
    assertTrue(warm.newFilesFullQuality)
    assertTrue(warm.reducedThreads)
    assertNull(warm.heldBy)
    assertFalse(decide(charging = true, thermal = 1).reducedThreads)
    assertFalse(decide(thermal = 2).reducedThreads)
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

  @Test fun severeHeatOnTheChargerRetriesLaterJustAsOnBattery() {
    val hot = decide(charging = true, thermal = IndexPolicy.HOT_THERMAL_STATUS)
    assertEquals(decide(thermal = IndexPolicy.HOT_THERMAL_STATUS), hot)
    assertEquals(IndexPolicy.FollowUp.RETRY_LATER, follow(hot))
    assertEquals(IndexPolicy.FollowUp.NONE, follow(hot, paused = true))
    assertEquals(IndexPolicy.FollowUp.NONE, follow(hot, waiting = false))
    assertEquals(IndexPolicy.FollowUp.NONE, follow(hot, paused = true, waiting = false))
    assertEquals(IndexPolicy.FollowUp.NONE, follow(decide(charging = true, thermal = IndexPolicy.WARM_THERMAL_STATUS)))
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
