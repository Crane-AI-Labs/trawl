package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.craneai.findr.R
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.ModelPresence
import com.craneai.findr.engine.Edition
import com.craneai.findr.ui.delivery.modelsCardWanted
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.shadows.ShadowLooper

/**
 * The cold-start flash (1.0.8): Home drew "Get the search models" for a moment on every cold start of a phone that HAS the models,
 * because the status before the index opened said "0 bytes of model", which read as "no model". Whether the model is there is now
 * three answers ([ModelPresence]); the card needs MISSING. Frame by frame with the clock stopped, as a phone would draw it.
 */
class ColdStartModelsCardTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun cardDrawn(a: Atlas): Boolean =
    rule.onAllNodes(hasText(a.str(R.string.models_title)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  /** Steps [n] 16 ms frames and returns the frames on which the card was drawn. */
  private fun framesWithCard(a: Atlas, n: Int, from: Int = 0): List<Int> = (from until from + n).filter {
    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
    rule.mainClock.advanceTimeBy(16)
    cardDrawn(a)
  }

  @Test fun modelsInstalledCardNeverDrawnBeforeOrAfterTheCheck() {
    assumeTrue(Edition.MODELS)
    val a = Atlas(rule, "t-coldstart")
    a.forEach(listOf(Cfg(1.0f), Cfg(1.0f, false, Dev.LAND)), "installed") { c ->
      prefs()
      // A status built before anyone looked at the model (IndexStatus(): 0 bytes, presence UNKNOWN), as on a cold start before the index opens.
      val v = Vms(AtlasEngine(status = IndexStatus(), corpus = Corpus.MEDIA_ONLY))
      a.launch(c) { com.craneai.findr.ui.search.SearchScreen(v.search, v.index, v.delivery, FULL_ACCESS, {}, {}, {}) }
      rule.mainClock.autoAdvance = false
      val early = (if (cardDrawn(a)) listOf(-1) else emptyList()) + framesWithCard(a, 40)
      // The check answers: the model is on the phone (the engine's status once it has looked).
      v.engine.statusFlow.value = Status.upToDate
      val late = framesWithCard(a, 40, from = 40)
      rule.mainClock.autoAdvance = true
      check(early.isEmpty()) { "${c.tag}: the models card was drawn before the check answered, frames $early" }
      check(late.isEmpty()) { "${c.tag}: the models card was drawn after the check said installed, frames $late" }
      check(!v.delivery.noModel.value) { "${c.tag}: noModel true with the model installed" }
    }
    a.assertNoFailures()
  }

  @Test fun modelsMissingCardDrawnFromTheFirstFrame() {
    assumeTrue(Edition.MODELS)
    val a = Atlas(rule, "t-coldstart")
    a.forEach(listOf(Cfg(1.0f), Cfg(1.0f, false, Dev.LAND)), "missing") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = IndexStatus(models = ModelPresence.MISSING), corpus = Corpus.MEDIA_ONLY))
      a.launch(c) { com.craneai.findr.ui.search.SearchScreen(v.search, v.index, v.delivery, FULL_ACCESS, {}, {}, {}) }
      rule.mainClock.autoAdvance = false
      val first = cardDrawn(a)
      val frames = framesWithCard(a, 20)
      rule.mainClock.autoAdvance = true
      check(first) { "${c.tag}: no models card on the first frame of a phone known to have no model" }
      check(frames.size == 20) { "${c.tag}: the card left while the model is still missing (drawn on ${frames.size} of 20 frames)" }
    }
    a.assertNoFailures()
  }

  @Test fun noModelIsTrueOnlyWhenTheModelIsKnownMissing() {
    assumeTrue(Edition.MODELS)
    val v = Vms(AtlasEngine(status = IndexStatus(), corpus = Corpus.MEDIA_ONLY))
    val expected = mapOf(ModelPresence.UNKNOWN to false, ModelPresence.INSTALLED to false, ModelPresence.MISSING to true)
    for ((presence, want) in expected) {
      v.engine.statusFlow.value = IndexStatus(models = presence)
      val got = Vms(v.engine).delivery.noModel.value   // a new view model, as after a process death: its first value is the one drawn
      assertEquals("noModel for $presence", want, got)
    }
  }

  @Test fun cardRule() {
    assertFalse(modelsCardWanted(noModel = false, busy = false, finished = false))   // installed or not known yet: no card
    assertTrue(modelsCardWanted(noModel = true, busy = false, finished = false))     // known missing: the card
    assertTrue(modelsCardWanted(noModel = false, busy = true, finished = false))     // a delivery in progress or a question to ask
    assertFalse(modelsCardWanted(noModel = true, busy = true, finished = true))      // a finished install with nothing to ask
  }
}
