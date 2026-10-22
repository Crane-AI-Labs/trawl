package com.craneai.findr.engine

import com.craneai.findr.BuildConfig
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.LearningSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What holds in both editions: the switches all follow the one build flag, and nothing else decides an edition. */
class EditionTest {
  @Test fun everyModelFeatureFollowsTheBuildFlag() {
    assertEquals(BuildConfig.EDITION_GO, Edition.GO)
    for (feature in listOf(Edition.MEANING, Edition.MODELS, Edition.FIND_SIMILAR, Edition.SPEECH, Edition.QUICK_LOOK, Edition.BLUR_PREVIEWS)) {
      assertEquals(!Edition.GO, feature)
    }
  }

  @Test fun theFlavorNamesTheEditionInItsPackageAndVersion() {
    assertEquals(Edition.GO, BuildConfig.APPLICATION_ID.contains(".go"))
    assertEquals(Edition.GO, BuildConfig.VERSION_NAME.endsWith("-go"))
    assertEquals(Edition.GO, BuildConfig.FLAVOR == "go")
  }

  @Test fun anEditionOnlyEverTurnsFeaturesOff() {
    val all = FindrSettings(speechToText = true, quickLook = true, blurPrivatePreviews = true, privateScoring = true, indexAudio = true)
    val s = all.forEdition()
    assertTrue(!s.speechToText || Edition.SPEECH)
    assertTrue(!s.quickLook || Edition.QUICK_LOOK)
    assertTrue(!s.blurPrivatePreviews || Edition.BLUR_PREVIEWS)
    assertTrue(!s.privateScoring || Edition.BLUR_PREVIEWS)
    // What the person chose about the files themselves is never an edition's business.
    val chosen = FindrSettings(indexPhotos = false, learningSpeed = LearningSpeed.GENTLE, excludedFolders = setOf("DCIM/Private")).forEdition()
    assertFalse(chosen.indexPhotos)
    assertTrue(chosen.onlyWhileCharging)
    assertEquals(setOf("DCIM/Private"), chosen.excludedFolders)
  }

  @Test fun theReadingSizeNeverGrowsBeyondTheFullEditions() {
    assertTrue(Edition.ocrMaxSide() <= FileProcessor.OCR_MAX_SIDE)
    assertEquals(if (Edition.GO) Edition.GO_OCR_MAX_SIDE else FileProcessor.OCR_MAX_SIDE, Edition.ocrMaxSide())
  }

  @Test fun theEmbedderFactoryHasNoModelFileInGoAndFullKeepsItsConstants() {
    assertEquals(listOf("embeddinggemma-2-740m.litertlm", "embeddinggemma-2-text-vision-440m.litertlm"), Embedder.STOCK_NAMES)
    assertFalse(Embedder.modelNames(MemoryProfile.LITE).isEmpty())
  }
}
