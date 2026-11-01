package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.craneai.findr.R
import com.craneai.findr.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

/** UI audit item 5: the model finishes installing on the models page and the phone is rotated; setup stays on the same page and does not crash. */
class OnboardingRecreateTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun step(a: Atlas): String =
    rule.onAllNodes(hasContentDescription("Step", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty() }.first { it.startsWith("Step") }

  @Test fun recreateAfterTheModelArrives() {
    val a = Atlas(rule, "t-onb-recreate")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f, false, Dev.SMALL)), "recreate") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.indexingNoModel, corpus = Corpus.MEDIA_ONLY))
      val content: @androidx.compose.runtime.Composable () -> Unit = { OnboardingScreen(v.addOns, v.delivery, v.index, NO_FILES_ACCESS, {}, {}) }
      a.launch(c, content = content)
      a.settle(700)
      var guard = 0
      while (true) {
        val s = step(a)
        val (n, total) = Regex("(\\d+) of (\\d+)").find(s)!!.destructured
        if (n == total || guard++ > 8) break
        rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].performTouchInputSwipe()
        a.settle(700)
      }
      val before = step(a)
      // The models page watches the flow while it is on screen (it is only live while watched); do the same here.
      val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { v.delivery.noModel.collect { } }
      v.engine.statusFlow.value = Status.upToDate        // the model is on the phone now
      a.settle(300)
      check(!v.delivery.noModel.value) { "setup: noModel should be false now" }
      a.reconfigure(Cfg(c.scale, false, if (c.dev == Dev.PHONE) Dev.LAND else c.dev, c.lang), content)
      a.settle(700)
      watcher.cancel()
      check(step(a) == before) { "${c.tag}: setup moved from '$before' to '${step(a)}' after recreate" }
    }
    a.assertNoFailures()
  }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTouchInputSwipe() =
  performTouchInput { swipeLeft() }
