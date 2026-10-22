package com.craneai.findr.engine

import com.craneai.findr.core.FindrSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trawl (the full edition) must keep doing everything it did before Go existed. */
class FullEditionTest {
  @Test fun theFullBuildKeepsEveryFeature() {
    assertFalse(Edition.GO)
    assertTrue(Edition.MEANING && Edition.MODELS && Edition.FIND_SIMILAR && Edition.SPEECH && Edition.QUICK_LOOK && Edition.BLUR_PREVIEWS)
    assertEquals(FileProcessor.OCR_MAX_SIDE, Edition.ocrMaxSide())
    assertEquals(2048, FileProcessor.OCR_MAX_SIDE)
  }

  @Test fun theFullEditionsSettingsComeBackUnchanged() {
    val s = FindrSettings(speechToText = true, quickLook = true, blurPrivatePreviews = true, privateScoring = true, indexAudio = true)
    assertEquals(s, s.forEdition())
    assertEquals(FindrSettings(), FindrSettings().forEdition())
  }

  @Test fun theModelErrorCheckStillSeesOnnxRuntimeErrors() {
    assertTrue(isModelRuntimeError(ai.onnxruntime.OrtException("x")))
    assertFalse(isModelRuntimeError(RuntimeException("x")))
  }
}
