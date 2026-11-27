package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningTest {
  private val phone = DeviceProfile(
    model = "x", socManufacturer = "MediaTek", socModel = "MT6769", ramMb = 3_700, bigCores = 2, bigMaxMhz = 2_000,
    littleCores = 6, littleMaxMhz = 1_800, hasOpenCl = true, npuModelSuffix = null,
  )

  @Test fun moderateChargerCapsOnlySpeechThreadsWithoutChangingOtherTuning() {
    val base = Tuning.DEFAULT.copy(asrThreads = 4, textThreadsOn = 6, textThreadsOff = 8, audioThreads = 8)
    assertEquals(base.copy(asrThreads = 2), base.withThermalLimit(true))
    assertEquals(base, base.withThermalLimit(false))
  }

  @Test fun thermalThreadCapNeverRaisesALowerThreadSetting() {
    val base = Tuning.DEFAULT.copy(textThreadsOn = 1, textThreadsOff = 1, audioThreads = 1, asrThreads = 1)
    assertEquals(base, base.withThermalLimit(true))
  }

  @Test fun workerThermalLimitSurvivesSpeedChangesAndCoolingRestoresTheProfile() {
    val overrides = mapOf("textThreadsOn" to "6", "textThreadsOff" to "8", "asrThreads" to "4")
    try {
      Tuning.install(phone, overrides)
      val base = Tuning.current
      Tuning.useThermal(true)
      assertEquals(base.withThermalLimit(true), Tuning.current)
      Tuning.useSpeed(com.craneai.findr.core.LearningSpeed.FAST, roomy = true)
      val fast = LearningProfile.tuning(base, com.craneai.findr.core.LearningSpeed.FAST, roomy = true)
      assertEquals(fast.withThermalLimit(true), Tuning.current)
      Tuning.useThermal(false)
      assertEquals(fast, Tuning.current)
    } finally {
      Tuning.useThermal(false)
      Tuning.install(phone)
    }
  }

  @Test fun defaultIsWhatEveryPhoneRanBeforeProfiles() {
    val t = Tuning.DEFAULT
    assertNull("LiteRT-LM's default text threads while the screen is on", t.textThreadsOn)
    assertEquals(Embedder.SCREEN_OFF_TEXT_THREADS, t.textThreadsOff)
    assertEquals(0.5, t.quickDuty, 0.0)
    assertEquals(0.2, t.privacyDuty, 0.0)
    assertEquals(41.0, t.warmC, 0.0)
    assertEquals(44.0, t.hotStopC, 0.0)
    assertEquals(20_000L, t.warmRestMs)
    assertEquals(120_000L, t.coolDownMs)
    assertEquals(20, t.newFilesPerRun)
    assertEquals(2 * 60_000L, t.newEncodeMsPerRun)
    assertEquals(2, t.clipImageThreads); assertEquals(2, t.privacyThreads); assertEquals(2, t.asrThreads)
    assertEquals(Tuning.DEFAULT_AUDIO_THREADS, t.audioThreads)
    assertEquals(true, t.visionGpu)
  }

  @Test fun theBatteryPolicyKeepsItsOwnConstants() {
    // Profiles tune effort, not the D12 rules: the 30% floor and the charger-first tiers are shared by every phone.
    assertEquals(30, IndexPolicy.MIN_BATTERY_PCT)
    assertEquals(IndexPolicy.Tier.WORDS_ONLY, IndexPolicy.decide(false, 29, 0, false, false).tier)
  }

  @Test fun forDeviceMatchesThreadsToPerformanceCores() {
    // 2-perf-core Helio G85/G99 stays at 2 threads for screen-on text and audio.
    assertEquals(Tuning.DEFAULT.copy(textThreadsOn = 2, audioThreads = 2), Tuning.forDevice(phone))

    // 6-perf-core Snapdragon 8 Gen 3 (1+3+2+2) caps at 4 threads.
    val s24 = DeviceProfile(
      model = "S24U", socManufacturer = "Qualcomm", socModel = "SM8650", ramMb = 12_000,
      bigCores = 6, bigMaxMhz = 3_400, littleCores = 2, littleMaxMhz = 2_270,
      hasOpenCl = true, npuModelSuffix = "Qualcomm_SM8650", perfCores = 6,
    )
    assertEquals(Tuning.DEFAULT.copy(textThreadsOn = 4, audioThreads = 4), Tuning.forDevice(s24))

    // Uniform quad-core (e.g. itel A669W 4x A7): leaves half the cores free for UI/system.
    val quad = phone.copy(bigCores = 4, littleCores = 0, perfCores = 4, bigMaxMhz = 1_300, littleMaxMhz = 0)
    assertEquals(Tuning.DEFAULT.copy(textThreadsOn = 2, audioThreads = 2), Tuning.forDevice(quad))

    // Unknown topology (0 perf cores) keeps DEFAULT unchanged.
    val unknown = phone.copy(bigCores = 0, littleCores = 0, perfCores = 0)
    assertEquals(Tuning.DEFAULT, Tuning.forDevice(unknown))

    // Pure helpers across core counts.
    assertEquals(2, Tuning.recommendedTextThreads(1))
    assertEquals(2, Tuning.recommendedTextThreads(2))
    assertEquals(3, Tuning.recommendedTextThreads(3))
    assertEquals(4, Tuning.recommendedTextThreads(4))
    assertEquals(4, Tuning.recommendedTextThreads(6))
    assertEquals(4, Tuning.recommendedTextThreads(0))
    assertEquals(2, Tuning.recommendedAudioThreads(2))
    assertEquals(4, Tuning.recommendedAudioThreads(6))
    assertEquals(4, Tuning.recommendedAudioThreads(0))
  }

  @Test fun audioThreadsKnobAppliesAndRejectsOutOfRange() {
    assertEquals(6, Tuning.apply(Tuning.DEFAULT, mapOf("audioThreads" to "6")).audioThreads)
    assertEquals(Tuning.DEFAULT.audioThreads, Tuning.apply(Tuning.DEFAULT, mapOf("audioThreads" to "0")).audioThreads)
    assertEquals(Tuning.DEFAULT.audioThreads, Tuning.apply(Tuning.DEFAULT, mapOf("audioThreads" to "99")).audioThreads)
    assertEquals(Tuning.DEFAULT.audioThreads, Tuning.apply(Tuning.DEFAULT, mapOf("audioThreads" to "abc")).audioThreads)
  }

  @Test fun overridesReplaceOnlyWhatTheyName() {
    val t = Tuning.apply(Tuning.DEFAULT, mapOf("textThreadsOn" to "6", "quickDuty" to "0.8", "visionGpu" to "false"))
    assertEquals(6, t.textThreadsOn); assertEquals(0.8, t.quickDuty, 0.0); assertEquals(false, t.visionGpu)
    assertEquals(Tuning.DEFAULT.copy(textThreadsOn = 6, quickDuty = 0.8, visionGpu = false), t)
  }

  @Test fun badOrOutOfRangeValuesChangeNothing() {
    val bad = mapOf("textThreadsOn" to "99", "quickDuty" to "0", "warmC" to "hot", "newFilesPerRun" to "-3", "nonsense" to "1")
    assertEquals(Tuning.DEFAULT, Tuning.apply(Tuning.DEFAULT, bad))
  }

  @Test fun defaultKeywordGoesBackToLiteRtsThreads() {
    assertNull(Tuning.apply(Tuning.DEFAULT.copy(textThreadsOn = 6), mapOf("textThreadsOn" to "default")).textThreadsOn)
  }

  @Test fun visionCpuThreadsIsDefaultUntilNamed() {
    assertNull(Tuning.DEFAULT.visionCpuThreads)
    assertEquals(6, Tuning.apply(Tuning.DEFAULT, mapOf("visionCpuThreads" to "6")).visionCpuThreads)
    assertNull(Tuning.apply(Tuning.DEFAULT.copy(visionCpuThreads = 6), mapOf("visionCpuThreads" to "default")).visionCpuThreads)
    assertNull(Tuning.apply(Tuning.DEFAULT, mapOf("visionCpuThreads" to "99")).visionCpuThreads)
  }

  @Test fun modelsAreGivenBackWhenPoliteUnlessAProfileKeepsThem() {
    assertEquals(false, Tuning.DEFAULT.keepModelsWhenPolite)
    assertEquals(true, Tuning.apply(Tuning.DEFAULT, mapOf("keepModelsWhenPolite" to "true")).keepModelsWhenPolite)
    assertEquals(false, Tuning.apply(Tuning.DEFAULT.copy(keepModelsWhenPolite = true), mapOf("keepModelsWhenPolite" to "false")).keepModelsWhenPolite)
  }

  @Test fun ortMemoryOptionsKeepOrtsDefaultsUntilSwitched() {
    val d = Tuning.DEFAULT
    assertEquals(true, d.ortArena); assertEquals(true, d.ortPattern); assertEquals(false, d.ortInitDevice)
    val t = Tuning.apply(d, mapOf("ortArena" to "false", "ortPattern" to "false", "ortInitDevice" to "true"))
    assertEquals(false, t.ortArena); assertEquals(false, t.ortPattern); assertEquals(true, t.ortInitDevice)
  }

  @Test fun framesPastHalfAnHourAreOnePerMinuteUnlessTheKnobSaysOtherwiseAndNonsenseIsIgnored() {
    assertEquals(1, Tuning.DEFAULT.deepFramesPerMin)
    assertEquals(0, Tuning.apply(Tuning.DEFAULT, Tuning.parse("deepFramesPerMin=0")).deepFramesPerMin)   // the old rule stays one line away
    assertEquals(2, Tuning.apply(Tuning.DEFAULT, Tuning.parse("deepFramesPerMin=2")).deepFramesPerMin)
    assertEquals(1, Tuning.apply(Tuning.DEFAULT, mapOf("deepFramesPerMin" to "7")).deepFramesPerMin)
    assertEquals(1, Tuning.apply(Tuning.DEFAULT, mapOf("deepFramesPerMin" to "-1")).deepFramesPerMin)
    assertEquals(1, Tuning.apply(Tuning.DEFAULT, mapOf("deepFramesPerMin" to "x")).deepFramesPerMin)
  }

  @Test fun parakeetSegmentCapStaysAt15sUntilSwitchedAndIgnoresNonsense() {
    assertEquals(15, Tuning.DEFAULT.asrMaxSpeechS)
    assertEquals(10, Tuning.apply(Tuning.DEFAULT, mapOf("asrMaxSpeechS" to "10")).asrMaxSpeechS)
    assertEquals(15, Tuning.apply(Tuning.DEFAULT, mapOf("asrMaxSpeechS" to "2")).asrMaxSpeechS)
    assertEquals(15, Tuning.apply(Tuning.DEFAULT, mapOf("asrMaxSpeechS" to "x")).asrMaxSpeechS)
  }

  @Test fun everyClassIsOnTheDefaultUntilAMeasurementMovesIt() {
    for (ram in RamClass.values()) for (soc in SocTier.values()) for (oem in Oem.values())
      assertEquals(Tuning.DEFAULT, Tuning.forClass(DeviceClass(ram, soc, oem)))
  }

  @Test fun heatPacingIsOffUnlessAKnobFileTurnsItOn() {
    val d = Tuning.DEFAULT
    assertEquals(false, d.paces)
    assertEquals(Tuning.PACE_OFF_C, d.paceFromC, 0.0); assertEquals(0L, d.paceCapBelowKhz)
    assertEquals(1.0, d.paceWordsDuty, 0.0); assertEquals(1.0, d.paceQuickDuty, 0.0); assertEquals(1.0, d.pacePrivacyDuty, 0.0)
    // Every speed setting keeps it off: GENTLE and FAST change threads and duty, never heat pacing.
    for (s in com.craneai.findr.core.LearningSpeed.entries) for (roomy in listOf(false, true))
      assertEquals(false, LearningProfile.tuning(d, s, roomy).paces)
    val t = Tuning.apply(d, Tuning.parse("paceFromC=38\npaceWordsDuty=0.6\npaceQuickDuty=0.8"))
    assertEquals(true, t.paces); assertEquals(38.0, t.paceFromC, 0.0); assertEquals(0.6, t.paceWordsDuty, 0.0); assertEquals(0.8, t.paceQuickDuty, 0.0)
    assertEquals(true, Tuning.apply(d, mapOf("paceCapBelowKhz" to "2000000", "pacePrivacyDuty" to "0.5")).paces)
    assertEquals("a threshold with no duty below 1 paces nothing", false, Tuning.apply(d, mapOf("paceFromC" to "38")).paces)
    assertEquals("a duty with no threshold paces nothing", false, Tuning.apply(d, mapOf("paceWordsDuty" to "0.5")).paces)
    assertEquals(d, Tuning.apply(d, mapOf("paceFromC" to "10", "paceWordsDuty" to "0", "paceCapBelowKhz" to "-1", "paceQuickDuty" to "x")))
  }

  @Test fun knobFileParses() {
    val m = Tuning.parse("# a comment\n\ntextThreadsOff = 8\nwarmC=42.5\nnot a setting\n")
    assertEquals(mapOf("textThreadsOff" to "8", "warmC" to "42.5"), m)
  }

  @Test fun clipEncoderFollowsLiveLearningSpeedAndHonorsExplicitOverride() {
    val s24 = DeviceProfile(
      model = "S24U", socManufacturer = "Qualcomm", socModel = "SM8650", ramMb = 12_000,
      bigCores = 6, bigMaxMhz = 3_400, littleCores = 2, littleMaxMhz = 2_270,
      hasOpenCl = true, npuModelSuffix = "Qualcomm_SM8650", perfCores = 6,
    )
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    try {
      Tuning.install(s24)
      // Constructed before LearningSpeed is applied (mirrors FindrEngine initialization order).
      val clip = ClipEncoder(java.io.File("."), lazyOf(null), scope)
      assertEquals(2, clip.resolvedImageThreads())
      assertEquals(4, clip.textThreads())

      Tuning.useSpeed(com.craneai.findr.core.LearningSpeed.GENTLE, roomy = true)
      assertEquals(1, clip.resolvedImageThreads())

      Tuning.useSpeed(com.craneai.findr.core.LearningSpeed.FAST, roomy = true)
      assertEquals(4, clip.resolvedImageThreads())

      // Mid-range 2-big-core Helio stays at 2 threads even in FAST mode.
      Tuning.install(phone)
      Tuning.useSpeed(com.craneai.findr.core.LearningSpeed.FAST, roomy = false)
      assertEquals(2, clip.resolvedImageThreads())
      assertEquals(2, clip.textThreads())

      // Explicit bench override stays fixed regardless of Tuning changes.
      val fixed = ClipEncoder(java.io.File("."), lazyOf(null), scope, imageThreads = 3)
      assertEquals(3, fixed.resolvedImageThreads())
    } finally {
      Tuning.useThermal(false)
      Tuning.install(phone)
    }
  }
}
