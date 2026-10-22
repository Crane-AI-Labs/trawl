package com.craneai.findr.core

import com.craneai.findr.index.FolderGroups
import com.craneai.findr.index.folderScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings stored by older builds must load as "everything on, as before"; new fields must survive a round trip. */
class SettingsCodecTest {

  // Written verbatim by build 2 (before folder choices existed). Do not regenerate this from the encoder.
  private val v2 = """{"onlyWhileCharging":false,"indexPhotos":true,"indexScreenshots":true,"indexVideos":false,
    |"indexDocuments":true,"indexAudio":false,"speechToText":true,"quickLook":true,"version":2,
    |"excludedFolders":["DCIM/Private"]}""".trimMargin()

  @Test fun anInstallFromBeforeFolderChoicesStartsWithEverythingOnAsToday() {
    val s = SettingsCodec.decode(v2)
    assertTrue(s.groupOverrides.isEmpty())
    assertTrue(s.includedFolders.isEmpty())
    val scope = s.folderScope()
    for (g in FolderGroups.ALL) assertEquals(g.id, g.defaultOn, scope.isOn(g))
    // Status stays off because it always was; every other group is on.
    assertEquals(listOf(FolderGroups.WHATSAPP_STATUS), FolderGroups.ALL.filter { !scope.isOn(it) }.map { it.id })
  }

  @Test fun blurringPrivatePreviewsIsOnByDefaultAndASavedOffSurvivesARoundTrip() {
    assertTrue(SettingsCodec.decode(v2).blurPrivatePreviews)   // no saved choice: the default
    val off = SettingsCodec.decode(SettingsCodec.encode(FindrSettings(blurPrivatePreviews = false)))
    assertFalse("a saved off stays off", off.blurPrivatePreviews)
    assertTrue(SettingsCodec.decode(SettingsCodec.encode(off.copy(blurPrivatePreviews = true))).blurPrivatePreviews)
  }

  @Test fun theBlurPackSwitchIsOnByDefaultAndSurvivesARoundTripApartFromTheBlurSetting() {
    assertTrue(SettingsCodec.decode(v2).privateScoring)
    val off = SettingsCodec.decode(SettingsCodec.encode(FindrSettings(privateScoring = false)))
    assertFalse(off.privateScoring)
    assertTrue("the pack's switch is not the blur setting", off.blurPrivatePreviews)
    assertTrue(SettingsCodec.decode(SettingsCodec.encode(off.copy(privateScoring = true))).privateScoring)
  }

  @Test fun privacyMarksSurviveARoundTripAndOldSavesHideByDefault() {
    val marked = FindrSettings(showSensitive = true, privateFolders = setOf("DCIM/Private"), markedFiles = setOf("v:1", "path:v:dcim/camera/a.jpg"))
    val back = SettingsCodec.decode(SettingsCodec.encode(marked))
    assertEquals(marked.privateFolders, back.privateFolders)
    assertEquals(marked.markedFiles, back.markedFiles)
    assertTrue(back.showSensitive)
    val old = SettingsCodec.decode(v2)
    assertFalse(old.showSensitive)
    assertTrue(old.markedFiles.isEmpty())
  }

  @Test fun theirOtherChoicesAreKept() {
    val s = SettingsCodec.decode(v2)
    assertFalse(s.onlyWhileCharging)
    assertFalse(s.indexVideos)
    assertEquals(setOf("DCIM/Private"), s.excludedFolders)
  }

  @Test fun versionOneStillGetsSpeechToTextOn() {
    val s = SettingsCodec.decode("""{"speechToText":false,"onlyWhileCharging":true}""")   // no "version" field: build 1
    assertTrue(s.speechToText)
    // From version 2 on, the user's own choice is respected.
    assertFalse(SettingsCodec.decode("""{"speechToText":false,"version":2}""").speechToText)
  }

  @Test fun roundTripKeepsEveryField() {
    val s = FindrSettings(
      learningSpeed = LearningSpeed.FAST, indexAudio = true, speechToText = false, quickLook = false,
      excludedFolders = setOf("Pictures/Trip", "DCIM/Camera/Private"),
      groupOverrides = mapOf(FolderGroups.CAMERA to false, FolderGroups.WHATSAPP_STATUS to true),
      includedFolders = setOf("Download/Receipts"),
    )
    val back = SettingsCodec.decode(SettingsCodec.encode(s))
    assertEquals(s, back)
  }

  @Test fun aSaveFromBeforeTheBatteryPolicyReadsOnlyWhileChargingAsOffButKeepsFolderChoices() {
    // Version 3 (folder choices) wrote the old default "only while charging: on", which was never a choice.
    val v3 = """{"onlyWhileCharging":true,"indexPhotos":true,"version":3,"excludedFolders":["DCIM/Private"],
      |"includedFolders":["Download/Receipts"],"groups":{"camera":false}}""".trimMargin()
    val s = SettingsCodec.decode(v3)
    assertFalse(s.onlyWhileCharging)
    assertFalse(s.fullOnBattery)
    assertEquals(mapOf("camera" to false), s.groupOverrides)
    assertEquals(setOf("Download/Receipts"), s.includedFolders)
    assertEquals(setOf("DCIM/Private"), s.excludedFolders)
    assertFalse(SettingsCodec.decode("""{"onlyWhileCharging":true,"version":2}""").onlyWhileCharging)
    assertFalse(SettingsCodec.decode("""{"onlyWhileCharging":true}""").onlyWhileCharging)
  }

  @Test fun fromVersionFourTheChargingChoicesBecomeALearningSpeed() {
    // The two old switches are one choice now: charging-only is GENTLE, full-on-battery is FAST, neither is BALANCED; the strict one wins.
    assertEquals(LearningSpeed.GENTLE, SettingsCodec.decode("""{"onlyWhileCharging":true,"version":4}""").learningSpeed)
    assertEquals(LearningSpeed.FAST, SettingsCodec.decode("""{"fullOnBattery":true,"version":4}""").learningSpeed)
    assertEquals(LearningSpeed.BALANCED, SettingsCodec.decode("""{"version":4}""").learningSpeed)
    assertEquals(LearningSpeed.GENTLE, SettingsCodec.decode("""{"onlyWhileCharging":true,"fullOnBattery":true,"version":4}""").learningSpeed)
    // An install with no saved choice, or a build-1 save, is BALANCED: today's behaviour.
    assertEquals(LearningSpeed.BALANCED, SettingsCodec.decode(null).learningSpeed)
    assertEquals(LearningSpeed.BALANCED, SettingsCodec.decode("""{"speechToText":false}""").learningSpeed)
  }

  @Test fun theLearningSpeedRoundTripsAndKeepsTheOldFlagsForADowngrade() {
    for (speed in LearningSpeed.entries) {
      val raw = SettingsCodec.encode(FindrSettings(learningSpeed = speed))
      assertEquals(speed, SettingsCodec.decode(raw).learningSpeed)
      val o = org.json.JSONObject(raw)
      assertEquals(speed == LearningSpeed.GENTLE, o.getBoolean("onlyWhileCharging"))
      assertEquals(speed == LearningSpeed.FAST, o.getBoolean("fullOnBattery"))
    }
    // A version-4 build reading a version-5 save sees the same two flags (the new key is ignored by it).
    val v4Reads = SettingsCodec.decode(SettingsCodec.encode(FindrSettings(learningSpeed = LearningSpeed.FAST)).replace("\"learningSpeed\":\"fast\",", ""))
    assertEquals(LearningSpeed.FAST, v4Reads.learningSpeed)
  }

  @Test fun theDerivedFlagsAreWhatThePowerPolicyReads() {
    val g = FindrSettings(learningSpeed = LearningSpeed.GENTLE)
    val b = FindrSettings()
    val f = FindrSettings(learningSpeed = LearningSpeed.FAST)
    assertTrue(g.onlyWhileCharging); assertFalse(g.fullOnBattery)
    assertFalse(b.onlyWhileCharging); assertFalse(b.fullOnBattery)
    assertFalse(f.onlyWhileCharging); assertTrue(f.fullOnBattery)
  }

  @Test fun recordingsAreReadByDefaultAndTheDeeperSwitchKeepsItsOldMeaning() {
    // Build 2 stored only indexAudio (the slow audio vector). It stays "Deeper sound search"; reading recordings is on.
    val s = SettingsCodec.decode(v2)
    assertTrue(s.indexRecordings)
    assertFalse(s.indexAudio)
    val old = SettingsCodec.decode("""{"version":3,"indexAudio":true}""")
    assertTrue(old.indexRecordings)
    assertTrue(old.indexAudio)
    // A person who turns recordings off keeps it off.
    val off = SettingsCodec.decode(SettingsCodec.encode(FindrSettings(indexRecordings = false)))
    assertFalse(off.indexRecordings)
    assertFalse(FindrSettings().indexAudio)
  }

  @Test fun theCurrentVersionIsWritten() {
    assertTrue(SettingsCodec.encode(FindrSettings()).contains("\"version\":${SettingsCodec.VERSION}"))
  }

  @Test fun nothingOrGarbageGivesTheDefaults() {
    assertEquals(FindrSettings(), SettingsCodec.decode(null))
    assertEquals(FindrSettings(), SettingsCodec.decode("not json {"))
    assertEquals(FindrSettings(), SettingsCodec.decode(""))
  }

  @Test fun aGroupsFieldOfTheWrongShapeDoesNotLoseTheRest() {
    val s = SettingsCodec.decode("""{"version":3,"indexAudio":true,"groups":{"camera":false,"downloads":true}}""")
    assertTrue(s.indexAudio)
    assertEquals(mapOf("camera" to false, "downloads" to true), s.groupOverrides)
  }
}
