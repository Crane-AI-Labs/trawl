package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningTest {
  private val phone = DeviceProfile(
    model = "x", socManufacturer = "MediaTek", socModel = "MT6769", ramMb = 3_700, bigCores = 2, bigMaxMhz = 2_000,
    littleCores = 6, littleMaxMhz = 1_800, hasOpenCl = true, npuModelSuffix = null,
  )

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
    assertEquals(true, t.visionGpu)
  }

  @Test fun theBatteryPolicyKeepsItsOwnConstants() {
    // Profiles tune effort, not the D12 rules: the 30% floor and the charger-first tiers are shared by every phone.
    assertEquals(30, IndexPolicy.MIN_BATTERY_PCT)
    assertEquals(IndexPolicy.Tier.WORDS_ONLY, IndexPolicy.decide(false, 29, 0, false, false).tier)
  }

  @Test fun noProfileYetMeansNoChange() {
    assertEquals(Tuning.DEFAULT, Tuning.forDevice(phone))
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

  @Test fun knobFileParses() {
    val m = Tuning.parse("# a comment\n\ntextThreadsOff = 8\nwarmC=42.5\nnot a setting\n")
    assertEquals(mapOf("textThreadsOff" to "8", "warmC" to "42.5"), m)
  }
}
