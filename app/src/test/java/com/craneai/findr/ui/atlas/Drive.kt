package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.lifecycle.viewmodel.initializer
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.R
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.search.SearchScreen
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The class-level annotations every atlas test shares. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
abstract class AtlasTestBase

/** Sets the little preferences the home cards read, so each cell starts from a known first-run state. */
fun prefs(firstSearchDone: Boolean = false, notificationsAsked: Boolean = false) {
  val p = UiPrefs(ApplicationProvider.getApplicationContext())
  p.firstSearchDone = firstSearchDone
  p.notificationsAsked = notificationsAsked
  p.pauseCardDismissed = true      // those two cards only appear on a Transsion / Samsung phone; the atlas phone is neither
  p.samsungCardDismissed = true
}

val FULL_ACCESS = AccessState(allFiles = true, notifications = true)
val NO_FILES_ACCESS = AccessState(allFiles = false, notifications = true)
val NO_NOTIF = AccessState(allFiles = true, notifications = false)

/** The real search screen over [v]. */
fun Atlas.launchSearch(c: Cfg, v: Vms, access: AccessState = FULL_ACCESS, bars: Bars = Bars(), blur: Boolean = false) {
  launch(c, bars, blur) { SearchScreen(v.search, v.index, v.delivery, access, {}, {}, {}) }
  settle(700)
}

fun Atlas.field() = rule.onNode(hasSetTextAction())

/** Taps the search box and brings the keyboard up. */
fun Atlas.focusField(imeDp: Int = 300) {
  field().performClick()
  applyBars(bars.copy(imeDp = imeDp))
  settle(300)
}

fun Atlas.type(text: String) {
  field().performTextInput(text)
  settle(900)
}

/** The keyboard goes down (the person pressed Back once or tapped a result) while the field keeps its text. */
fun Atlas.keyboardDown() { applyBars(bars.copy(imeDp = 0)); settle(300) }

/**
 * Settings: scrolls the Storage used section into view by its last row that this edition has (the AI model row in the full edition;
 * Trawl Go has no model, so its memory row or the index hint), so the rows above it are whole on screen.
 */
fun Atlas.scrollToStorage() {
  val ids = listOf(R.string.settings_storage_model, R.string.settings_storage_memory, R.string.settings_storage_index_hint)
  for (id in ids) {
    val n = rule.onAllNodes(androidx.compose.ui.test.hasText(str(id), substring = id == R.string.settings_storage_memory), useUnmergedTree = true)
    if (n.fetchSemanticsNodes().isNotEmpty()) { n[0].performScrollTo(); settle(300); return }
  }
  error("no storage row in Settings")
}

fun Atlas.pressClear() {
  rule.onNodeWithContentDescription(str(R.string.search_clear)).performClick()
  settle(900)
}

/** Puts a plan and five plain pictures in the video strip cache for every moment in [hits], so strips draw as they do when back on screen. */
fun seedStrips(hits: List<com.craneai.findr.core.SearchHit>, hidden: Boolean = false) {
  for (h in hits) {
    val ms = (h.locator as? com.craneai.findr.core.Locator.Time)?.startMs ?: continue
    val dur = h.file.durationMs ?: continue
    val plan = com.craneai.findr.core.VideoStrip.plan(ms, dur, com.craneai.findr.core.VideoStrip.KeyframeList((0L..dur step 1_000).toList()))
    val frames = plan.slots.indices.map { i ->
      val b = android.graphics.Bitmap.createBitmap(256, 144, android.graphics.Bitmap.Config.ARGB_8888)
      val c = android.graphics.Canvas(b)
      c.drawColor(android.graphics.Color.rgb(60 + 30 * (i % 5), 110, 190 - 25 * (i % 5)))
      c.drawCircle(256 * (0.2f + 0.15f * (i % 5)), 72f, 30f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
      b
    }
    com.craneai.findr.ui.common.VideoStripLoader.seed(h.file, ms, hidden, plan, frames)
  }
}

fun Vms.hitsFor(q: String, filter: com.craneai.findr.core.KindFilter = com.craneai.findr.core.KindFilter.ALL) =
  kotlinx.coroutines.runBlocking { engine.search(q, filter, 50, com.craneai.findr.core.ResultView.DEFAULT) }.hits

/** The app's root view model and a factory that serves the atlas view models, for [com.craneai.findr.ui.FindrRoot]. */
fun rootFor(engine: AtlasEngine): Pair<com.craneai.findr.ui.app.AppViewModel, androidx.lifecycle.ViewModelProvider.Factory> {
  val app = ApplicationProvider.getApplicationContext<android.app.Application>()
  val appVm = com.craneai.findr.ui.app.AppViewModel(app, engine)
  val f = androidx.lifecycle.viewmodel.viewModelFactory {
    initializer { appVm }
    initializer { com.craneai.findr.ui.search.SearchViewModel(engine, clock = { ATLAS_NOW }, weekStart = { java.time.DayOfWeek.MONDAY }, feedDispatcher = kotlinx.coroutines.Dispatchers.Unconfined) }
    initializer { com.craneai.findr.ui.index.IndexViewModel(engine) }
    initializer { com.craneai.findr.ui.addons.AddOnsViewModel(engine) }
    initializer { com.craneai.findr.ui.delivery.DeliveryViewModel(engine) }
  }
  return appVm to f
}
