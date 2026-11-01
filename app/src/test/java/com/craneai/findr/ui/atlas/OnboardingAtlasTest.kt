package com.craneai.findr.ui.atlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.isRoot
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.R
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.ui.FindrRoot
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.onboarding.OnboardingScreen
import org.junit.Rule
import org.junit.Test

/** First run: every page of setup, with and without files access, with and without a model on the phone. */
class OnboardingAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun Atlas.next() {
    rule.onAllNodes(isRoot())[0].performTouchInput { swipeLeft() }
    settle(700)
  }

  /** Draws page after page by swiping; [pages] is how many frames to take (the pager stops at its last page). */
  private fun pages(id: String, title: String, cells: List<Cfg>, pages: Int, status: IndexStatus, access: AccessState, corpus: Corpus = Corpus.MEDIA_ONLY, imeDp: Int = 0) {
    val a = Atlas(rule, "onboarding")
    a.forEach(cells, id) { c ->
      prefs()
      val v = Vms(AtlasEngine(status = status, corpus = corpus))
      a.launch(c, Bars(imeDp = imeDp)) { OnboardingScreen(v.addOns, v.delivery, v.index, access, {}, {}) }
      a.settle(700)
      for (p in 1..pages) {
        a.shot("$id-p$p", "$title: page $p")
        if (p < pages) a.next()
      }
    }
    a.assertNoFailures()
  }

  // Models already on the phone: language, welcome, files (+ background page on a phone that is still optimised).
  @Test fun noAccessModelsPresent() = pages("onb-noaccess", "Setup, no files access, model on the phone", Cfg.full, 4, Status.upToDate, NO_FILES_ACCESS)
  @Test fun withAccessModelsPresent() = pages("onb-access", "Setup, files access already given, model on the phone", Cfg.mini, 4, Status.upToDate, FULL_ACCESS)
  // No model yet (a fresh install of the lean build): an extra "Get the models" page.
  @Test fun noAccessNoModel() = pages("onb-nomodel", "Setup, no files access, no model yet", Cfg.mini, 5, Status.indexingNoModel, NO_FILES_ACCESS)
  @Test fun withAccessNoModel() = pages("onb-nomodel-access", "Setup, access given, no model yet", Cfg.mini, 5, Status.indexingNoModel, FULL_ACCESS)
  @Test fun smallPhone() = pages("onb-small", "Setup on a small phone (360x640dp)", listOf(Cfg(1.0f, false, Dev.SMALL), Cfg(2.0f, false, Dev.SMALL)), 5, Status.indexingNoModel, NO_FILES_ACCESS)
  @Test fun landscape() = pages("onb-landscape", "Setup in landscape", listOf(Cfg(1.0f, false, Dev.LAND), Cfg(1.3f, false, Dev.TABLET_LAND), Cfg(2.0f, false, Dev.LAND)), 5, Status.indexingNoModel, NO_FILES_ACCESS)

  @Test fun chooseFolders() {
    val a = Atlas(rule, "onboarding")
    a.forEach(Cfg.mini, "onb-folders") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.upToDate))
      a.launch(c) { OnboardingScreen(v.addOns, v.delivery, v.index, NO_FILES_ACCESS, {}, {}) }
      a.settle(700)
      a.next(); a.next()
      a.rule.onNodeWithText(a.str(R.string.onb_choose_folders)).performClick()
      a.settle(900)
      a.shot("onb-folders", "Setup, Choose folders (reached from the files page)")
    }
    a.assertNoFailures()
  }

  /** Access arrives while the files page is showing: the pager moves on by itself. */
  @Test fun accessGrantedOnFilesPage() {
    val a = Atlas(rule, "onboarding")
    a.forEach(Cfg.mini, "onb-grant") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.upToDate))
      var access by mutableStateOf(NO_FILES_ACCESS)
      a.launch(c) { OnboardingScreen(v.addOns, v.delivery, v.index, access, {}, {}) }
      a.settle(700)
      a.next(); a.next()
      a.shot("onb-grant-1-files", "Setup: files page, waiting for access")
      access = FULL_ACCESS
      a.settle(300)
      a.shot("onb-grant-2-just-granted", "Setup: access just granted (page starting to move on)")
      a.settle(900)
      a.shot("onb-grant-3-moved-on", "Setup: access granted, moved to the next page")
    }
    a.assertNoFailures()
  }

  /**
   * The whole app from a first launch: onboarding hands over to home. NOT drivable here: the real AppViewModel asks the framework
   * `Environment.isExternalStorageManager()`, which throws ArrayIndexOutOfBounds inside Robolectric's android-all (same as navigation in
   * TransitionsAtlasTest). The onboarding pages and the home they hand over to are drawn separately (onb-* and home-*); only the hand-over
   * frame is missing. Recorded in SKIPPED.txt.
   */
  @Test fun firstLaunchThroughRoot() {
    java.io.File(Atlas.indexDir, "SKIPPED.txt").appendText("onb-root-1-first-launch\tFirst launch through FindrRoot (setup, then home)\tAppViewModel calls Environment.isExternalStorageManager(), which throws inside Robolectric; onboarding pages and home are drawn separately\n")
  }
}
