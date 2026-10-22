package com.craneai.findr.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDefaultTest {
  @Test fun englishKeepsSpeechOn() {
    assertTrue(SpeechDefault.atFirstRun("en", current = true))
    assertTrue(SpeechDefault.atFirstRun("en-UG", current = true))
  }

  @Test fun everyOtherLanguageStartsWithSpeechOff() {
    for (tag in listOf("lg", "sw", "rw", "fr", "pt", "ar", "am", "ha", "yo", "so", "zu", "ach", "nyn")) {
      assertFalse(tag, SpeechDefault.atFirstRun(tag, current = true))
    }
  }

  @Test fun itNeverTurnsSpeechOnThatWasOff() {
    assertFalse(SpeechDefault.atFirstRun("en", current = false))   // the lite profile keeps it off
  }
}
