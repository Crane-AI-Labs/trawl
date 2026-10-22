package com.craneai.findr.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "looks private" policy: what blurs, and the guard that nothing else may read the score. */
class PrivateLookTest {
  @Test fun onlyAScoreAtOrAboveTheThresholdBlurs() {
    assertTrue(PrivateLook.blurs(0.9f))
    assertTrue(PrivateLook.blurs(0.958f))
    assertFalse(PrivateLook.blurs(0.8999f))
    assertFalse(PrivateLook.blurs(0f))
  }

  @Test fun anUnscoredOrUnreadablePictureNeverBlurs() {
    assertFalse(PrivateLook.blurs(null))
    assertFalse(PrivateLook.blurs(-1f))   // IndexStore.UNREADABLE_SCORE
  }

  @Test fun theThresholdIsReachableByTheLabelSmoothedModel() {
    // The model never says more than about 0.958, so a threshold near 1 would blur nothing at all.
    assertTrue(PrivateLook.THRESHOLD < 0.95f)
  }

  @Test fun theMainAppBlursLessThanThePanelButStillReachableByTheModel() {
    assertTrue(PrivateLook.MAIN_APP_THRESHOLD > PrivateLook.THRESHOLD)
    assertTrue("label smoothing caps the model near 0.958", PrivateLook.MAIN_APP_THRESHOLD < 0.958f)
    assertFalse(PrivateLook.blurs(0.93f, PrivateLook.MAIN_APP_THRESHOLD))
    assertTrue(PrivateLook.blurs(0.93f))   // the panel's bar
    assertTrue(PrivateLook.blurs(0.945f, PrivateLook.MAIN_APP_THRESHOLD))
  }

  @Test fun probabilityIsASoftmaxOfTheTwoLogits() {
    assertEquals(0.5f, PrivateLook.probability(1f, 1f), 1e-6f)
    assertEquals(0.7310586f, PrivateLook.probability(1f, 0f), 1e-6f)
    assertEquals(0.2689414f, PrivateLook.probability(0f, 1f), 1e-6f)
    // Big logits must not overflow.
    assertEquals(1f, PrivateLook.probability(1000f, -1000f), 1e-6f)
    assertEquals(0f, PrivateLook.probability(-1000f, 1000f), 1e-6f)
  }

  @Test fun aVideoTakesItsMostPrivateLookingFrame() {
    assertEquals(0.93f, PrivateLook.ofFrames(listOf(0.1f, 0.93f, 0.4f))!!, 0f)
    assertNull(PrivateLook.ofFrames(emptyList()))   // nothing readable: stored as "unreadable"
  }

  /**
   * The score may blur a thumbnail and do nothing else. Where it is read is a short list: the store that holds it, the model
   * and passes that write it, the settings and the thumbnail code that blur. Search, fusion, ranking and the result lists
   * must not mention it, so a future change that starts to rank or hide by it fails here and has to be argued for.
   */
  @Test fun rankingNeverSeesTheScore() {
    val allowed = setOf(
      "core/Model.kt", "core/PrivateLook.kt", "index/IndexStore.kt", "index/Schema.kt", "ui/common/PreviewBlur.kt",
    )
    val root = File("src/main/java/com/craneai/findr")
    // The shared engine moved to :core: the same package tree under its own source roots.
    val roots = listOf(root) + File("../core/src").listFiles { f -> f.name.endsWith("Main") }.orEmpty().map { File(it, "kotlin/com/craneai/findr") }
    val offenders = roots.flatMap { r ->
      r.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { f -> val text = f.readText(); "looksPrivate" in text || "looks_private" in text }
        .map { it.relativeTo(r).path }
        .toList()
    }
      .filter { it !in allowed }
      // The writing side: the passes and scorer that produce the score and the engine that runs them.
      .filter { it !in setOf("engine/PrivacyPass.kt", "engine/PrivateScorer.kt", "engine/FindrEngine.kt", "engine/IndexWorker.kt") }
    assertEquals("the score is read outside the blur code: $offenders", emptyList<String>(), offenders)
    val searchSide = roots.flatMap { r -> File(r, "search").walkTopDown().filter { it.isFile }.filter { "looksPrivate" in it.readText() }.toList() }
    assertEquals(emptyList<File>(), searchSide)
  }
}
