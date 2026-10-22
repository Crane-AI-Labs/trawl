package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The learning-speed mapping, the fair turns between photos and videos/recordings, and the quick-look interleave. */
class LearnControlTest {
  private val base = Tuning.DEFAULT

  // ---- the mapping ----------------------------------------------------------------------------------------------------

  @Test fun balancedIsExactlyThePhonesOwnTuning() {
    assertEquals(base, LearningProfile.tuning(base, LearningSpeed.BALANCED, roomy = true))
    assertEquals(base, LearningProfile.tuning(base, LearningSpeed.BALANCED, roomy = false))
  }

  @Test fun gentleUsesFewerThreadsLowerDutyAndRestsCooler() {
    val t = LearningProfile.tuning(base, LearningSpeed.GENTLE, roomy = true)
    assertEquals(1, t.asrThreads); assertEquals(1, t.clipImageThreads); assertEquals(1, t.privacyThreads)
    assertTrue(t.textThreadsOff <= 2)
    assertTrue(t.quickDuty < base.quickDuty); assertTrue(t.privacyDuty <= base.privacyDuty)   // privacy is already at 0.2
    assertTrue(t.warmC < base.warmC)
    assertTrue("Gentle never works harder than Balanced anywhere", t.hotStopC <= base.hotStopC && t.newFilesPerRun <= base.newFilesPerRun)
    assertTrue("polite stays as it is: Gentle never steps closer to the person", t.politeOnLockScreen)
    // A phone whose own profile is already lighter is not made heavier by Gentle.
    val light = base.copy(quickDuty = 0.2, textThreadsOff = 1, warmC = 38.0)
    val g = LearningProfile.tuning(light, LearningSpeed.GENTLE, roomy = true)
    assertEquals(0.2, g.quickDuty, 0.0); assertEquals(1, g.textThreadsOff); assertEquals(38.0, g.warmC, 0.0)
  }

  @Test fun fastStepsAsideOnlyForAnUnlockedPhoneAndNeverRaisesTheHeatLimits() {
    val t = LearningProfile.tuning(base, LearningSpeed.FAST, roomy = true)
    assertFalse(t.politeOnLockScreen)
    assertEquals(base.warmC, t.warmC, 0.0); assertEquals(base.hotStopC, t.hotStopC, 0.0)
    assertEquals(base.coolDownMs, t.coolDownMs)
    assertTrue(t.asrThreads > base.asrThreads && t.quickDuty > base.quickDuty)
  }

  @Test fun fastKeepsATightOrSmallPhonesThreadsButStillRelaxesPolite() {
    val t = LearningProfile.tuning(base, LearningSpeed.FAST, roomy = false)
    assertEquals(base.asrThreads, t.asrThreads); assertEquals(base.clipImageThreads, t.clipImageThreads); assertEquals(base.quickDuty, t.quickDuty, 0.0)
    assertFalse(t.politeOnLockScreen)
    assertFalse(LearningProfile.roomy(cores = 8, tight = true))
    assertFalse(LearningProfile.roomy(cores = 6, tight = false))
    assertTrue(LearningProfile.roomy(cores = 8, tight = false))
  }

  @Test fun theSpeedChoosesThePowerPolicyThroughTheDerivedFlags() {
    fun tier(speed: LearningSpeed, charging: Boolean): IndexPolicy.Tier {
      val s = com.craneai.findr.core.FindrSettings(learningSpeed = speed)
      return IndexPolicy.decide(charging, 80, 0, s.onlyWhileCharging, s.fullOnBattery).tier
    }
    assertEquals(IndexPolicy.Tier.WORDS_ONLY, tier(LearningSpeed.GENTLE, charging = false))
    assertEquals(IndexPolicy.Tier.LIGHT, tier(LearningSpeed.BALANCED, charging = false))
    assertEquals(IndexPolicy.Tier.BULK, tier(LearningSpeed.FAST, charging = false))
    for (s in LearningSpeed.entries) assertEquals("on the charger every speed runs everything", IndexPolicy.Tier.BULK, tier(s, charging = true))
  }

  @Test fun theLowBatteryFloorAndHeatStopHoldWhateverTheSpeed() {
    val fast = com.craneai.findr.core.FindrSettings(learningSpeed = LearningSpeed.FAST)
    assertEquals(IndexPolicy.Tier.WORDS_ONLY, IndexPolicy.decide(false, 29, 0, fast.onlyWhileCharging, fast.fullOnBattery).tier)
    assertEquals(IndexPolicy.Tier.WORDS_ONLY, IndexPolicy.decide(false, 90, IndexPolicy.HOT_THERMAL_STATUS, fast.onlyWhileCharging, fast.fullOnBattery).tier)
  }

  @Test fun theSpeedIsLaidOverTheDevicesBaseAndCanBeChangedBack() {
    Tuning.useSpeed(LearningSpeed.GENTLE, roomy = true)
    assertEquals(1, Tuning.current.asrThreads)
    Tuning.useSpeed(LearningSpeed.BALANCED, roomy = true)
    assertEquals(Tuning.DEFAULT.asrThreads, Tuning.current.asrThreads)
  }

  @Test fun theNewKnobsParseAndAZeroRestoresTheOldBehaviour() {
    val t = Tuning.apply(base, Tuning.parse("mediaShare=0\nquickVideoShare=0\nquickVideoSeekMs=0\nnewMediaOnBattery=false"))
    assertEquals(0.0, t.mediaShare, 0.0); assertEquals(0.0, t.quickVideoShare, 0.0); assertEquals(0L, t.quickVideoSeekMs); assertFalse(t.newMediaOnBattery)
    assertEquals(Tuning.MEDIA_SHARE, base.mediaShare, 0.0)
    assertEquals(base, Tuning.apply(base, Tuning.parse("mediaShare=7")))   // out of range: ignored
  }

  // ---- fair turns -----------------------------------------------------------------------------------------------------

  @Test fun theMediaShareConvergesOnTheShareOfBusyTime() {
    val m = MediaShare(0.2)
    var ms = 0L
    repeat(400) {
      if (m.mediaTurn(mediaWaiting = true, otherWaiting = true)) { m.record(true, 20_000); } else { m.record(false, 4_000) }
      ms++
    }
    assertEquals(0.2, m.achieved, 0.03)
  }

  @Test fun photosGoFirstAndTheFirstVideoIsOwedOnlyAfterTheFirstPhoto() {
    val m = MediaShare(0.2)
    assertFalse(m.mediaTurn(true, true))   // nothing recorded yet: the share of zero time is zero
    m.record(false, 4_200)
    assertTrue(m.mediaTurn(true, true))
    m.record(true, 20_000)
    assertFalse(m.mediaTurn(true, true))
  }

  @Test fun aSideWithNothingWaitingNeverBlocksTheOther() {
    val m = MediaShare(0.2)
    assertTrue(m.mediaTurn(mediaWaiting = true, otherWaiting = false))   // only videos left: all the time is theirs
    assertFalse(m.mediaTurn(mediaWaiting = false, otherWaiting = true))
    assertFalse(m.mediaTurn(mediaWaiting = false, otherWaiting = false))
    m.record(true, 1_000_000)
    assertTrue(m.mediaTurn(mediaWaiting = true, otherWaiting = false))
  }

  @Test fun aShareOfZeroIsTheOldOrder() {
    val m = MediaShare(0.0)
    assertTrue(m.noShare)
    m.record(false, 1_000)
    assertFalse(m.mediaTurn(true, true))   // photos first for as long as any wait
    assertTrue(m.mediaTurn(true, false))   // then videos
  }

  @Test fun videosAndRecordingsTakeTurnsInsideTheShare() {
    val m = MediaShare(0.2)
    val both = MediaShare.MEDIA_KINDS
    assertEquals(listOf(FileKind.VIDEO, FileKind.AUDIO), m.mediaOrder(both))
    m.record(true, 1_000, FileKind.VIDEO)
    assertEquals(listOf(FileKind.AUDIO, FileKind.VIDEO), m.mediaOrder(both))   // the recording is next, not video number two
    m.record(true, 1_000, FileKind.AUDIO)
    assertEquals(listOf(FileKind.VIDEO, FileKind.AUDIO), m.mediaOrder(both))
    assertEquals(listOf(FileKind.AUDIO), m.mediaOrder(setOf(FileKind.AUDIO)))   // a kind that is switched off is not offered
  }

  @Test fun mediaKindsAreVideoAndAudioOnly() {
    assertEquals(setOf(FileKind.VIDEO, FileKind.AUDIO), MediaShare.MEDIA_KINDS)
  }

  // ---- quick look interleave ------------------------------------------------------------------------------------------

  @Test fun aBatchCarriesAboutTheShareOfVideos() {
    val photos = (1..30).map { "p$it" }
    val videos = (1..10).map { "v$it" }
    val b = QuickMix.pick(photos, videos, 16, 0.2)
    assertEquals(16, b.size)
    assertEquals(3, b.count { it.startsWith("v") })
    assertEquals(listOf("v1", "v2", "v3"), b.filter { it.startsWith("v") })   // newest first within each kind
    assertEquals((1..13).map { "p$it" }, b.filter { it.startsWith("p") })
  }

  @Test fun aShareOfZeroPutsVideosAfterEveryPhotoAsBefore() {
    val photos = (1..30).map { "p$it" }
    assertEquals(photos.take(16), QuickMix.pick(photos, listOf("v1"), 16, 0.0))
    // ...and when the photos run out, the videos follow in the same batch.
    assertEquals(listOf("p1", "p2", "v1", "v2"), QuickMix.pick(listOf("p1", "p2"), listOf("v1", "v2", "v3"), 4, 0.0))
  }

  @Test fun aSideThatRunsShortIsToppedUpFromTheOther() {
    assertEquals(listOf("p1", "p2", "p3", "p4"), QuickMix.pick(listOf("p1", "p2", "p3", "p4"), emptyList(), 4, 0.5))
    assertEquals(listOf("v1", "v2", "v3", "v4"), QuickMix.pick(emptyList(), listOf("v1", "v2", "v3", "v4", "v5"), 4, 0.2))
    assertEquals(listOf("p1", "v1", "v2"), QuickMix.pick(listOf("p1"), listOf("v1", "v2", "v3"), 3, 0.34))
    assertEquals(emptyList<String>(), QuickMix.pick(listOf("p"), listOf("v"), 0, 0.5))
  }

  @Test fun theFirstVideoRidesInTheVeryFirstBatchWhateverTheBatchSize() {
    val b = QuickMix.pick((1..30).map { "p$it" }, listOf("v1"), 16, 0.05)
    assertTrue("v1" in b)
  }

  // ---- the quick-look frame, and the new-file kinds ----------------------------------------------------------------

  @Test fun theQuickLookFrameIsTakenLaterButNeverBeyondAQuarterIn() {
    assertEquals(1_500L, QuickLookPass.seekMsFor(1_500, 60_000))
    assertEquals(2_000L, QuickLookPass.seekMsFor(5_000, 8_000))   // an 8 s clip: a quarter in
    assertEquals(0L, QuickLookPass.seekMsFor(0, 60_000))          // the old behaviour
    assertEquals(0L, QuickLookPass.seekMsFor(1_500, 0))           // length unknown: the start
  }

  @Test fun onBatteryShortNewMediaJoinsTheNewFileKindsOnlyWhenTheKnobIsOn() {
    val on = IndexPolicy.newFileKinds(base.newMediaOnBattery)
    assertTrue(FileKind.VIDEO in on && FileKind.AUDIO in on)
    assertTrue(IndexPolicy.NEW_FILE_KINDS.all { it in on })
    assertEquals(IndexPolicy.NEW_FILE_KINDS, IndexPolicy.newFileKinds(false))
  }
}
