package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings under the lite memory profile: speech to text starts off there; standard phones keep D3's default and correction. */
class LiteSettingsTest {
  @Test fun aFreshLiteInstallStartsWithSpeechOff() {
    assertFalse(SettingsCodec.decode(null, lite = true).speechToText)
    assertTrue(SettingsCodec.decode(null).speechToText)   // standard: unchanged
  }

  @Test fun aLitePhoneStillStartsWithQuickLookOn() {
    // The default packs install without a question on every phone; only speech's switch is held back on a 2 GB phone.
    val lite = SettingsCodec.decode(null, lite = true)
    assertTrue(lite.quickLook)
    assertFalse(lite.speechToText)
  }

  @Test fun aLitePhoneKeepsTheUsersChoice() {
    val on = SettingsCodec.encode(FindrSettings(speechToText = true))
    val off = SettingsCodec.encode(FindrSettings(speechToText = false))
    assertTrue(SettingsCodec.decode(on, lite = true).speechToText)
    assertFalse(SettingsCodec.decode(off, lite = true).speechToText)
  }

  @Test fun theVersion2CorrectionStaysStandardOnly() {
    val v1Off = """{"speechToText": false, "version": 1}"""
    assertTrue(SettingsCodec.decode(v1Off).speechToText)                 // D3: an old saved default is corrected to on
    assertFalse(SettingsCodec.decode(v1Off, lite = true).speechToText)   // lite: nothing to correct
  }

  @Test fun everythingElseDecodesTheSameUnderLite() {
    val raw = SettingsCodec.encode(FindrSettings(learningSpeed = LearningSpeed.BALANCED, indexAudio = true, quickLook = false, excludedFolders = setOf("DCIM/Private")))
    assertEquals(SettingsCodec.decode(raw).copy(speechToText = false), SettingsCodec.decode(raw, lite = true).copy(speechToText = false))
  }
}
