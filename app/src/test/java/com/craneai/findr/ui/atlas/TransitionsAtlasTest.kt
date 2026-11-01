package com.craneai.findr.ui.atlas

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.ui.FindrRoot
import com.craneai.findr.ui.addons.AddOnsViewModel
import com.craneai.findr.ui.app.AppViewModel
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.search.SearchScreen
import com.craneai.findr.ui.search.SearchViewModel
import com.craneai.findr.ui.search.shownFiles
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Transitions: a "before" frame, an "after" frame and every intermediate state a test can construct.
 * What Robolectric cannot do (a real keyboard, the system's Recents, process kill by the OS, an external viewer) is stood in for as the
 * notes say: window insets for the keyboard, lifecycle moves for Recents/Home, activity re-creation with new view models for process death.
 */
class TransitionsAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun screen(v: Vms, access: () -> com.craneai.findr.ui.app.AccessState = { FULL_ACCESS }): @androidx.compose.runtime.Composable () -> Unit =
    { SearchScreen(v.search, v.index, v.delivery, access(), {}, {}, {}) }

  private fun topFile(v: Vms, q: String) = runBlocking { v.engine.search(q, KindFilter.ALL, 20, ResultView.DEFAULT) }.hits.first().file

  // =========================== search -> clear (X) ===========================
  private fun clearWithKeyboardUp(group: String, corpus: Corpus, cells: List<Cfg>, query: String) {
    val a = Atlas(rule, "t-clear")
    a.forEach(cells, group) { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = corpus))
      a.launchSearch(c, v)
      a.shot("$group-1-home", "Search then clear (keyboard up): 1 home")
      a.focusField()
      a.shot("$group-2-focused", "Search then clear (keyboard up): 2 box focused, keyboard up, nothing typed")
      a.type(query)
      a.shot("$group-3-typed", "Search then clear (keyboard up): 3 typed \"$query\", results, keyboard up")
      a.pressClear()
      a.shot("$group-4-after-x", "Search then clear (keyboard up): 4 right after the X, keyboard still up")
      a.keyboardDown()
      a.shot("$group-5-kbd-down", "Search then clear (keyboard up): 5 keyboard dismissed, box now empty")
    }
    a.assertNoFailures()
  }

  @Test fun clearPhotosPhoneFull() = clearWithKeyboardUp("clear-kbup-photos", Corpus.MEDIA_ONLY, Cfg.full, "wedding")
  @Test fun clearMixedMini() = clearWithKeyboardUp("clear-kbup-mixed", Corpus.FULL, Cfg.mini, "school")
  @Test fun clearPhotosSecondQuery() = clearWithKeyboardUp("clear-kbup-photos-boda", Corpus.MEDIA_ONLY, Cfg.mini, "boda")

  /** The keyboard was dismissed first (so the list is browsable), then the X. The box is not focused when the X is tapped. */
  @Test fun clearAfterKeyboardDown() {
    val a = Atlas(rule, "t-clear")
    a.forEach(Cfg.full, "clear-kbdown") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      a.focusField(); a.type("wedding"); a.keyboardDown()
      a.shot("clear-kbdown-1-results", "Search then clear (keyboard down): 1 results, keyboard dismissed")
      a.pressClear()
      a.shot("clear-kbdown-2-after-x", "Search then clear (keyboard down): 2 right after the X")
    }
    a.assertNoFailures()
  }

  /** Clearing while a filter, a sort and a date are set: the chips under the box must follow. */
  @Test fun clearWithFilterSortDate() {
    val a = Atlas(rule, "t-clear")
    a.forEach(Cfg.mini, "clear-filtered") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      v.search.setFilter(KindFilter.PHOTOS); v.search.setSort(com.craneai.findr.core.SortOrder.OLDEST)
      a.launchSearch(c, v)
      a.focusField(); a.type("wedding")
      a.shot("clear-filtered-1-typed", "Clear with Photos filter + Oldest sort: 1 typed, results")
      a.pressClear()
      a.shot("clear-filtered-2-after-x", "Clear with Photos filter + Oldest sort: 2 after the X (kbd up)")
      a.keyboardDown()
      a.shot("clear-filtered-3-kbd-down", "Clear with Photos filter + Oldest sort: 3 keyboard down")
    }
    a.assertNoFailures()
  }

  /** Delete the text with the keyboard (backspace) instead of the X. */
  @Test fun clearByDeleting() {
    val a = Atlas(rule, "t-clear")
    a.forEach(Cfg.mini, "clear-backspace") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      a.focusField(); a.type("wedding")
      a.field().performTextClearance()
      a.settle(900)
      a.shot("clear-backspace-1-erased", "Erase all text with backspace: keyboard still up")
    }
    a.assertNoFailures()
  }

  // =========================== search -> back, selection -> back ===========================
  @Test fun selectionAndBack() {
    val a = Atlas(rule, "t-back")
    a.forEach(Cfg.mini, "back") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.focusField(); a.type("school"); a.keyboardDown()
      a.shot("back-1-results", "Back: 1 results showing")
      val files = v.search.state.value.feed.shownFiles()
      v.search.toggleSelected(files[0]); v.search.toggleSelected(files[1]); a.settle(400)
      a.shot("back-2-selecting", "Back: 2 two files ticked (selection mode)")
      a.back()
      a.shot("back-3-after-back", "Back: 3 after Back (selection cleared, results stay)")
    }
    a.assertNoFailures()
  }

  // =========================== results -> open file -> return ===========================
  @Test fun openAndReturn() {
    val a = Atlas(rule, "t-open")
    a.forEach(Cfg.mini, "open") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.focusField(); a.type("school"); a.keyboardDown()
      a.shot("open-1-results", "Open a result: 1 results, keyboard down")
      val name = topFile(v, "school").name
      a.rule.onAllNodes(hasClickAction() and hasContentDescription(name, substring = true)).onFirst().performClick()
      a.settle(600)
      a.shot("open-2-tapped", "Open a result: 2 right after the tap (no viewer app in the test: the \"no app\" note)")
      a.moveTo(Lifecycle.State.CREATED)
      a.moveTo(Lifecycle.State.RESUMED)
      a.shot("open-3-returned", "Open a result: 3 back from the viewer (stopped then resumed), results as left")
    }
    a.assertNoFailures()
  }

  // =========================== Recents / Home: out and in ===========================
  /** [away] STARTED is the Recents overview (paused, still visible); CREATED is Home (stopped). */
  private fun outIn(group: String, title: String, away: Lifecycle.State, query: String?, kbUp: Boolean, jolt: Boolean, cells: List<Cfg>) {
    val a = Atlas(rule, "t-recents")
    a.forEach(cells, group) { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      if (query != null || kbUp) a.focusField()
      if (query != null) a.type(query)
      if (query != null && !kbUp) a.keyboardDown()
      a.shot("$group-1-before", "$title: 1 before leaving")
      // The system takes the keyboard away while the app is not in front.
      if (kbUp) a.applyBars(a.bars.copy(imeDp = 0))
      a.moveTo(away)
      a.shotOrNote("$group-2-away", "$title: 2 while away (window kept, keyboard taken by the system)",
        "while stopped the window is detached, so there is no frame to draw; Android shows the system snapshot of frame 1 in Recents")
      a.moveTo(Lifecycle.State.RESUMED)
      a.shot("$group-3-back", "$title: 3 just back (resumed), keyboard ${if (kbUp && jolt) "not yet up" else "as before"}")
      if (kbUp && jolt) {
        a.applyBars(a.bars.copy(imeDp = 300)); a.settle(500)
        a.shot("$group-4-kbd-returns", "$title: 4 keyboard comes back up")
      }
    }
    a.assertNoFailures()
  }

  @Test fun recentsResultsKbDown() = outIn("recents-results-kbdown", "Recents out/in, results showing, keyboard down", Lifecycle.State.STARTED, "wedding", false, false, Cfg.full)
  @Test fun recentsResultsKbUp() = outIn("recents-results-kbup", "Recents out/in, results showing, keyboard up", Lifecycle.State.STARTED, "wedding", true, true, Cfg.full)
  @Test fun recentsHomeKbUp() = outIn("recents-home-kbup", "Recents out/in, home, box focused, keyboard up", Lifecycle.State.STARTED, null, true, true, Cfg.mini)
  @Test fun recentsHomeKbDown() = outIn("recents-home-kbdown", "Recents out/in, home, keyboard down", Lifecycle.State.STARTED, null, false, false, Cfg.mini)
  @Test fun homeResultsKbDown() = outIn("home-results-kbdown", "Home out/in, results showing, keyboard down", Lifecycle.State.CREATED, "wedding", false, false, Cfg.mini)
  @Test fun homeResultsKbUp() = outIn("home-results-kbup", "Home out/in, results showing, keyboard up", Lifecycle.State.CREATED, "wedding", true, true, Cfg.mini)
  @Test fun homeHomeKbUp() = outIn("home-home-kbup", "Home out/in, home, box focused, keyboard up", Lifecycle.State.CREATED, null, true, true, Cfg.mini)
  @Test fun recentsResultsKbUpNoJolt() = outIn("recents-results-kbup-nojolt", "Recents out/in, results, keyboard up, keyboard already back at resume", Lifecycle.State.STARTED, "wedding", true, false, Cfg.mini)

  // =========================== rotation / tablet / landscape / multi-window ===========================
  private fun rotate(group: String, title: String, from: Cfg, to: Cfg, query: String?, kbUp: Boolean, corpus: Corpus = Corpus.MEDIA_ONLY) {
    val a = Atlas(rule, "t-rotate")
    a.forEach(listOf(from), group) { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = corpus))
      a.launchSearch(c, v)
      if (query != null || kbUp) a.focusField()
      if (query != null) a.type(query)
      if (query != null && !kbUp) a.keyboardDown()
      a.shot("$group-1-before", "$title: 1 before")
      a.reconfigure(to)
      a.shot("$group-2-after", "$title: 2 after the change (same view models, saved state restored)")
      a.reconfigure(from)
      a.shot("$group-3-back", "$title: 3 changed back")
    }
    a.assertNoFailures()
  }

  @Test fun rotateHome10() = rotate("rotate-home-s10", "Rotate phone, home", Cfg(1.0f), Cfg(1.0f, dev = Dev.LAND), null, false)
  @Test fun rotateHome20() = rotate("rotate-home-s20", "Rotate phone, home", Cfg(2.0f), Cfg(2.0f, dev = Dev.LAND), null, false)
  @Test fun rotateResults10() = rotate("rotate-results-s10", "Rotate phone, results", Cfg(1.0f), Cfg(1.0f, dev = Dev.LAND), "wedding", false)
  @Test fun rotateResults20() = rotate("rotate-results-s20", "Rotate phone, results", Cfg(2.0f), Cfg(2.0f, dev = Dev.LAND), "wedding", false)
  @Test fun rotateTyping10() = rotate("rotate-typing-s10", "Rotate phone while typing (keyboard up)", Cfg(1.0f), Cfg(1.0f, dev = Dev.LAND), "wedding", true)
  @Test fun rotateTyping20() = rotate("rotate-typing-s20", "Rotate phone while typing (keyboard up)", Cfg(2.0f), Cfg(2.0f, dev = Dev.LAND), "wedding", true)
  @Test fun rotateTablet() = rotate("rotate-tablet-results", "Rotate tablet, results", Cfg(1.0f, dev = Dev.TABLET), Cfg(1.0f, dev = Dev.TABLET_LAND), "wedding", false)
  @Test fun rotateTabletHome() = rotate("rotate-tablet-home", "Rotate tablet, home", Cfg(1.0f, dev = Dev.TABLET), Cfg(1.0f, dev = Dev.TABLET_LAND), null, false)
  @Test fun splitScreen() = rotate("multiwindow-results", "Multi-window: full screen to half height", Cfg(1.0f), Cfg(1.0f, dev = Dev.SPLIT), "wedding", false)
  @Test fun splitScreenHome() = rotate("multiwindow-home", "Multi-window: full screen to half height, home", Cfg(2.0f), Cfg(2.0f, dev = Dev.SPLIT), null, false)
  @Test fun splitScreenTyping() = rotate("multiwindow-typing", "Multi-window: half height, typing", Cfg(1.0f, dev = Dev.SPLIT), Cfg(1.0f), "wedding", true)
  @Test fun smallPhone() = rotate("smallphone-results", "Small phone 360x640 vs normal", Cfg(2.0f, dev = Dev.SMALL), Cfg(2.0f), "wedding", false)

  /** Every device shape, every screen, at two sizes. */
  @Test fun deviceShapes() {
    val a = Atlas(rule, "t-devices")
    val cells = buildList { for (d in listOf(Dev.LAND, Dev.TABLET_LAND, Dev.SMALL, Dev.SPLIT)) for (s in listOf(1.0f, 2.0f)) add(Cfg(s, false, d)); add(Cfg(1.0f, true, Dev.TABLET_LAND)) }
    a.forEach(cells, "devices") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      a.shot("device-home", "Home on this device shape")
      a.focusField()
      a.shot("device-home-kbd", "Home, keyboard up, on this device shape")
      a.type("wedding")
      a.shot("device-typed", "Typed, results, keyboard up, on this device shape")
      a.keyboardDown()
      a.shot("device-results", "Results, keyboard down, on this device shape")
      a.pressClear()
      a.shot("device-cleared", "After the X on this device shape")
    }
    a.assertNoFailures()
  }

  // =========================== font / dark / language change while away or in use ===========================
  private fun configChange(group: String, title: String, from: Cfg, to: Cfg, query: String?) {
    val a = Atlas(rule, "t-config")
    a.forEach(listOf(from), group) { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      if (query != null) { a.focusField(); a.type(query); a.keyboardDown() }
      a.shot("$group-1-before", "$title: 1 before")
      // The change is made in Settings while the app is stopped; on return the system destroys and re-creates the activity with it.
      // (ActivityScenario cannot recreate a stopped activity, so the re-create is done in front and a stop/start follows.)
      a.reconfigure(to)
      a.moveTo(Lifecycle.State.CREATED)
      a.moveTo(Lifecycle.State.RESUMED)
      a.shot("$group-2-after", "$title: 2 coming back after the change")
    }
    a.assertNoFailures()
  }

  @Test fun fontChangeHome() = configChange("config-font-home", "Font 1.0 to 2.0 while away, home", Cfg(1.0f), Cfg(2.0f), null)
  @Test fun fontChangeResults() = configChange("config-font-results", "Font 1.0 to 2.0 while away, results", Cfg(1.0f), Cfg(2.0f), "wedding")
  @Test fun fontShrinkResults() = configChange("config-font-shrink-results", "Font 2.0 to 1.0 while away, results", Cfg(2.0f), Cfg(1.0f), "wedding")
  @Test fun darkChangeHome() = configChange("config-dark-home", "Light to dark while away, home", Cfg(1.3f), Cfg(1.3f, true), null)
  @Test fun darkChangeResults() = configChange("config-dark-results", "Light to dark while away, results", Cfg(1.3f), Cfg(1.3f, true), "wedding")
  @Test fun langChangeHome() = configChange("config-lang-home", "English to Luganda while away, home", Cfg(1.3f), Cfg(1.3f, false, Dev.PHONE, "lg"), null)
  @Test fun langChangeResults() = configChange("config-lang-results", "English to Luganda while away, results", Cfg(1.3f), Cfg(1.3f, false, Dev.PHONE, "lg"), "wedding")

  // =========================== process death and restore ===========================
  @Test fun processDeath() {
    val a = Atlas(rule, "t-death")
    a.forEach(Cfg.mini, "death") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.focusField(); a.type("school"); a.keyboardDown()
      val files = v.search.state.value.feed.shownFiles()
      v.search.toggleSelected(files[0]); a.settle(300)
      a.shot("death-1-before", "Process death: 1 before (results, one file ticked)")
      // The OS kills the process: the activity is re-created from its saved state with NEW view models (they live in memory only).
      val v2 = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.reconfigure(c, screen(v2))
      a.shot("death-2-restored", "Process death: 2 restored (saved UI state back, query/selection/filters gone)")
    }
    a.assertNoFailures()
  }

  @Test fun processDeathWithFilter() {
    val a = Atlas(rule, "t-death")
    a.forEach(Cfg.mini, "death-filter") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      v.search.setFilter(KindFilter.VIDEOS); v.search.setSort(com.craneai.findr.core.SortOrder.LARGEST)
      a.launchSearch(c, v)
      a.shot("death-filter-1-before", "Process death with Videos filter + Largest sort: 1 before")
      a.reconfigure(c, screen(Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))))
      a.shot("death-filter-2-restored", "Process death with Videos filter + Largest sort: 2 restored (filter and sort reset)")
    }
    a.assertNoFailures()
  }

  // =========================== permission screen return ===========================
  @Test fun permissionReturn() {
    val a = Atlas(rule, "t-permission")
    a.forEach(Cfg.mini, "perm") { c ->
      prefs(firstSearchDone = true)
      var access by mutableStateOf(NO_FILES_ACCESS)
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY, status = Status.notStarted))
      a.launch(c) { SearchScreen(v.search, v.index, v.delivery, access, {}, {}, {}) }
      a.settle(600)
      a.shot("perm-1-before", "Permission: 1 home with the files-access banner (before opening Settings)")
      a.moveTo(Lifecycle.State.CREATED)
      access = FULL_ACCESS.copy(notifications = false)
      v.engine.statusFlow.value = Status.scanning
      a.moveTo(Lifecycle.State.RESUMED); a.settle(600)
      a.shot("perm-2-after-grant", "Permission: 2 back from Settings after allowing files (banner gone; notification ask may show)")
      access = NO_FILES_ACCESS
      a.settle(400)
      a.shot("perm-3-refused", "Permission: 3 back from Settings without allowing (banner stays)")
    }
    a.assertNoFailures()
  }

  // =========================== app navigation: home -> settings -> licences -> back ===========================
  private fun root(engine: AtlasEngine) = rootFor(engine)

  /**
   * home -> results -> Settings -> Back through the real FindrRoot. NOT drivable in Robolectric: the real AppViewModel's constructor calls
   * `Environment.isExternalStorageManager()`, which throws ArrayIndexOutOfBounds inside Robolectric's android-all (tried registering the
   * package for the test uid and the calling uid; same throw). The states on both sides are drawn elsewhere: results (res-*), Settings (set-*),
   * and the Settings-back-to-results behaviour of SearchScreen itself (t-back selection/back frames). Recorded in SKIPPED.txt.
   */
  @Test fun navigation() {
    java.io.File(Atlas.indexDir, "SKIPPED.txt").appendText("nav-1..4\tNavigation home -> results -> Settings -> back (FindrRoot)\tAppViewModel calls Environment.isExternalStorageManager(), which throws inside Robolectric; the two sides are drawn separately (res-*, set-*)\n")
  }
}
