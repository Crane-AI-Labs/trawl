package com.craneai.findr.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The full edition's scorer (ONNX Runtime). Trawl Go has a stub that is never available (see GoEditionTest), so this runs for full only. */
class BlurScorerAvailabilityTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun theBlurScorerIsAvailableExactlyWhileItsModelFileIsThere() {
    val model = File(tmp.newFolder("scorer"), "nsfw-384.onnx")
    val scorer = PrivateScorer(model)
    assertFalse(scorer.available())
    model.writeBytes(byteArrayOf(1))
    assertTrue(scorer.available())
    model.delete()
    assertFalse("removing the pack takes the scorer away at once", scorer.available())
  }
}
