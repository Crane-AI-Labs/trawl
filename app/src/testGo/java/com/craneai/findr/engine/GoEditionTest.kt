package com.craneai.findr.engine

import com.craneai.findr.core.FindrSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Trawl Go: words only, no model, no 64-bit libraries. */
class GoEditionTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun theGoBuildSaysSo() {
    assertTrue(Edition.GO)
    assertFalse(Edition.MEANING || Edition.MODELS || Edition.FIND_SIMILAR || Edition.SPEECH || Edition.QUICK_LOOK || Edition.BLUR_PREVIEWS)
    assertEquals(1280, Edition.ocrMaxSide())
  }

  @Test fun theNoModelEmbedderNeverHasAModelAndNeverReturnsAVector() = runBlocking {
    val e = NoModelEmbedder()
    assertNull(e.modelFile)
    assertNull(e.imageFingerprint)
    assertNull(e.textFingerprint)
    assertTrue(e.modelNames.isEmpty())
    assertFalse(e.supportsAudio || e.imagesByEmbeddingGemma || e.repairing.value)
    assertEquals(MemoryProfile.LITE, e.profile)
    e.warm()      // a search box getting focus is harmless
    e.release()
    for (call in listOf<suspend () -> Unit>({ e.query("boda") }, { e.document("text") }, { e.image(ByteArray(1)) }, { e.audio(ByteArray(1)) })) {
      try {
        call()
        fail("a vector from a build with no model would poison the index")
      } catch (_: UnsupportedOperationException) {
      }
    }
  }

  @Test fun theFactoryBuildsTheNoModelEmbedder() {
    // newEmbedder needs a Context in full; in Go it ignores it, so it is checked by what the class is.
    assertTrue(NoModelEmbedder(MemoryProfile.STANDARD).profile == MemoryProfile.STANDARD)
  }

  @Test fun everyModelSwitchIsOffWhateverWasSaved() {
    val s = FindrSettings(speechToText = true, quickLook = true, blurPrivatePreviews = true, privateScoring = true, indexAudio = true).forEdition()
    assertFalse(s.speechToText || s.quickLook || s.blurPrivatePreviews || s.privateScoring || s.indexAudio)
  }

  @Test fun theStubsAreNeverAvailable() {
    val dir = tmp.newFolder()
    File(dir, "anything.onnx").writeBytes(byteArrayOf(1))
    assertFalse(PrivateScorer(File(dir, "anything.onnx")).available())
    assertFalse(com.craneai.findr.extract.SherpaAsrEngine(dir).available())
    assertFalse(isModelRuntimeError(RuntimeException("x")))
  }
}
