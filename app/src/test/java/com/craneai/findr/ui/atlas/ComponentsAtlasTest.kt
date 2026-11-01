package com.craneai.findr.ui.atlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.Locator
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.SearchHit
import com.craneai.findr.quick.QuickMode
import com.craneai.findr.quick.QuickUiState
import com.craneai.findr.ui.background.BackgroundPauseCard
import com.craneai.findr.ui.background.SamsungSleepCard
import com.craneai.findr.ui.index.IndexDetailSheet
import com.craneai.findr.ui.index.MeterActions
import com.craneai.findr.ui.privacy.LockOfferDialog
import com.craneai.findr.ui.privacy.LockScreen
import com.craneai.findr.ui.privacy.SensitiveLeaveDialog
import com.craneai.findr.ui.report.ReportDialog
import com.craneai.findr.ui.search.AccessBanner
import com.craneai.findr.ui.search.DateRangeDialog
import com.craneai.findr.ui.search.EmptyFilter
import com.craneai.findr.ui.search.FileActionSheet
import com.craneai.findr.ui.search.FreshPhone
import com.craneai.findr.ui.search.IncompleteNote
import com.craneai.findr.ui.search.MomentPeekSheet
import com.craneai.findr.ui.search.NothingFound
import com.craneai.findr.ui.search.RepairingNote
import com.craneai.findr.ui.search.SelectionActionBar
import com.craneai.findr.ui.search.SelectionTopBar
import com.craneai.findr.ui.search.SimilarChip
import org.junit.Rule
import org.junit.Test

/** Sheets, dialogs, cards and the quick-search panel, drawn on their own (they need a tap or a system event to appear in the real app). */
class ComponentsAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private val noop = MeterActions({}, {}, {}, {}, {}, {})

  private fun files() = AtlasEngine().files

  /** A column of components on the screen background, below the status bar. */
  private fun column(id: String, title: String, cells: List<Cfg> = Cfg.mini, overlay: Overlay = Overlay.NONE, scroll: Boolean = true, content: @Composable () -> Unit) {
    val a = Atlas(rule, "components")
    a.forEach(cells, id) { c ->
      prefs()
      a.launch(c) {
        Column(
          Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
      }
      a.settle(900)
      a.shot(id, title, overlay)
    }
    a.assertNoFailures()
  }

  /** A sheet or dialog on an empty screen. */
  private fun window(id: String, title: String, overlay: Overlay, cells: List<Cfg> = Cfg.mini, content: @Composable () -> Unit) {
    val a = Atlas(rule, "components")
    a.forEach(cells, id) { c ->
      prefs()
      a.launch(c) { Box(Modifier.fillMaxSize()) { content() } }
      a.settle(1000)
      a.shot(id, title, overlay)
    }
    a.assertNoFailures()
  }

  // ---- the index detail sheet (tap the meter card) ----
  private val sheetStates: List<Pair<String, IndexStatus>> = listOf(
    "uptodate" to Status.upToDate, "indexing" to Status.indexing, "scanning" to Status.scanning, "notstarted" to Status.notStarted,
    "nomodel" to Status.indexingNoModel, "waitmodels" to Status.waitingForModels, "reembed" to Status.reembedding,
    "error-storage" to Status.error, "error-busy" to Status.errorBusy,
  ) + PauseReason.entries.map { "paused-${it.name.lowercase()}" to Status.paused(it) }

  @Test fun indexSheets() {
    for ((n, st) in sheetStates) window("sheet-index-$n", "Index details sheet: $n", Overlay.BOTTOM) { IndexDetailSheet(st, FindrSettings(), noop, {}) }
  }

  // ---- file action sheet ----
  @Test fun fileSheets() {
    val all = files()
    val picks = listOf("photo" to all.first { it.kind == FileKind.IMAGE }, "screenshot" to all.first { it.kind == FileKind.SCREENSHOT },
      "video" to all.first { it.kind == FileKind.VIDEO }, "pdf" to all.first { it.kind == FileKind.PDF }, "audio" to all.first { it.kind == FileKind.AUDIO })
    for ((n, f) in picks) window("sheet-file-$n", "File actions sheet: $n", Overlay.BOTTOM) { FileActionSheet(f, {}, {}, {}, {}, {}, {}, onFindSimilar = if (f.kind in setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)) ({}) else null) }
    window("sheet-file-sensitive", "File actions sheet: a file marked sensitive", Overlay.BOTTOM) { FileActionSheet(all.first { it.kind == FileKind.IMAGE }.copy(sensitiveFlags = 1), {}, {}, {}, {}, {}, {}, {}) }
  }

  // ---- video moment peek ----
  private fun momentHit(): SearchHit = Vms().hitsFor("wedding").first { it.locator is Locator.Time }
  @Test fun peekSheet() {
    val h = momentHit()
    seedStrips(listOf(h))
    val ms = (h.locator as Locator.Time).startMs
    window("sheet-peek", "Video moment peek sheet", Overlay.BOTTOM) { MomentPeekSheet(h.file, ms, false, {}, {}, {}) }
  }
  @Test fun peekSheetHidden() {
    val h = momentHit()
    seedStrips(listOf(h), hidden = true)
    val ms = (h.locator as Locator.Time).startMs
    window("sheet-peek-hidden", "Video moment peek sheet, frames hidden (private)", Overlay.BOTTOM) { MomentPeekSheet(h.file, ms, true, {}, {}, {}) }
  }
  @Test fun peekSheetNoFrames() {
    val h = momentHit()
    val ms = (h.locator as Locator.Time).startMs
    window("sheet-peek-noframes", "Video moment peek sheet, frames not read yet", Overlay.BOTTOM) { MomentPeekSheet(h.file, ms, false, {}, {}, {}) }
  }

  // ---- dialogs ----
  @Test fun dateRange() = window("dialog-date-range", "Date range dialog", Overlay.CENTER) { DateRangeDialog({ _, _ -> }, {}) }
  @Test fun leaveDialogs() {
    for (copy in listOf(false, true)) for (many in listOf(false, true))
      window("dialog-sensitive-${if (copy) "copy" else "share"}${if (many) "-many" else ""}", "Sensitive ${if (copy) "copy" else "share"} confirmation${if (many) " (several files)" else ""}", Overlay.CENTER) { SensitiveLeaveDialog(copy, {}, {}, many) }
  }
  @Test fun lockOffer() = window("dialog-lock-offer", "Offer to lock the app (first time a file is marked sensitive)", Overlay.CENTER) { LockOfferDialog({}) }
  @Test fun reportDialog() {
    window("dialog-report", "Report a problem dialog", Overlay.CENTER) { ReportDialog(null, {}, {}) }
    window("dialog-report-crash", "Report a problem dialog, after a crash", Overlay.CENTER) { ReportDialog("The app stopped the last time it ran", {}, {}) }
  }

  // ---- lock screen ----
  @Test fun lockScreen() {
    val a = Atlas(rule, "components")
    a.forEach(Cfg.mini, "lock-screen") { c ->
      prefs()
      val pc = com.craneai.findr.privacy.PrivacyController(ApplicationProvider.getApplicationContext(), AtlasEngine())
      a.launch(c) { LockScreen(pc) }
      a.settle(800)
      a.shot("lock-screen", "App lock screen (nothing of the user's is shown)")
    }
    a.assertNoFailures()
  }

  // ---- notes, banners, empty states ----
  /** Components that scroll themselves cannot sit in a scrolling column: each gets a fixed-height cell. */
  @Composable private fun Cell(h: Int = 300, c: @Composable () -> Unit) = Box(Modifier.fillMaxWidth().height(h.dp)) { c() }
  @Test fun emptyStates() {
    column("empty-states-1", "Empty states: fresh phone, empty filter", scroll = false) { Cell { FreshPhone() }; Cell { EmptyFilter() } }
    column("empty-states-2", "Notes and banners: index still reading (running, waiting for charger), repairing, access banner (full and compact), Find-similar chip") {
      IncompleteNote(waitingForCharger = false); IncompleteNote(waitingForCharger = true); RepairingNote()
      AccessBanner(onAllow = {}); AccessBanner(onAllow = {}, compact = true, onHide = {})
      SimilarChip("IMG_20261003_121502.jpg", {})
    }
  }
  @Test fun nothingFoundVariants() {
    column("nothing-variants-1", "Nothing found: plain, index still reading", Cfg.mini, scroll = false) { Cell { NothingFound("zzqx", indexIncomplete = false) }; Cell { NothingFound("zzqx", indexIncomplete = true) } }
    column("nothing-variants-2", "Nothing found: Find similar found nothing", Cfg.mini, scroll = false) { NothingFound("", indexIncomplete = false, similarTo = "IMG_20261003_121502.jpg") }
    column("nothing-variants-3", "Nothing found: a very long query", Cfg.mini, scroll = false) { NothingFound("a very long query that the person typed because they really do remember the whole sentence of the receipt", indexIncomplete = false) }
  }
  @Test fun selectionBars() = column("selection-bars", "Selection bars: top (1 ticked, all ticked) and action bars (one file, several, sensitive)") {
    SelectionTopBar(1, false, {}, {}); SelectionTopBar(12, true, {}, {})
    SelectionActionBar({}, {}, {}, null, {}); SelectionActionBar({}, {}, null, {}, null)
  }

  // ---- home cards that only appear on some phones ----
  @Test fun phoneSpecificCards() = column("phone-cards", "Cards that show only on some phones: Transsion background pause (full, compact), Samsung sleeping apps (full, compact)") {
    BackgroundPauseCard({}, {}); BackgroundPauseCard({}, {}, compact = true)
    SamsungSleepCard({}, {}); SamsungSleepCard({}, {}, compact = true)
  }

  // ---- quick-search panel ----
  private fun quick(id: String, title: String, state: QuickUiState, mode: QuickMode = QuickMode.SEARCH, offerTile: Boolean = false, typed: String = state.query, cells: List<Cfg> = Cfg.mini) {
    val a = Atlas(rule, "quick")
    a.forEach(cells, id) { c ->
      prefs()
      a.launch(c) {
        com.craneai.findr.quick.QuickSearchPanel(
          state = state, mode = mode, text = TextFieldState(typed), reopen = 0, offerTile = offerTile,
          onQuery = {}, onFocused = {}, onTyping = {}, onHitsDrawn = {}, onResultsShown = {}, onClose = {}, onShare = {}, onCopy = {}, onOpen = {},
          onShowFolder = {}, onMarkSensitive = { _, _ -> }, onUnlock = {}, onOpenFindr = {}, onAddTile = {}, onDeclineTile = {},
        )
      }
      a.settle(900)
      a.shot(id, title)
    }
    a.assertNoFailures()
  }
  private fun hits(q: String) = Vms().hitsFor(q)

  @Test fun quickEmpty() = quick("quick-empty", "Quick search: opened, nothing typed", QuickUiState())
  @Test fun quickResults() = quick("quick-results", "Quick search: results for \"wedding\"", QuickUiState(query = "wedding", resultsQuery = "wedding", hits = hits("wedding")))
  @Test fun quickResultsMore() = quick("quick-results-school", "Quick search: results for \"school\"", QuickUiState(query = "school", resultsQuery = "school", hits = hits("school")))
  @Test fun quickSearching() = quick("quick-searching", "Quick search: words answered, meaning still coming", QuickUiState(query = "wedding", resultsQuery = "wedding", hits = hits("wedding").take(2), searching = true))
  @Test fun quickNothing() = quick("quick-nothing", "Quick search: nothing found", QuickUiState(query = "zzqx", resultsQuery = "zzqx", nothingFound = true))
  @Test fun quickNothingIncomplete() = quick("quick-nothing-indexing", "Quick search: nothing found, files still being read", QuickUiState(query = "zzqx", resultsQuery = "zzqx", nothingFound = true, indexIncomplete = true))
  @Test fun quickNotSetUp() = quick("quick-notsetup", "Quick search: Findr not set up yet", QuickUiState(query = "wedding", resultsQuery = "wedding", nothingFound = true, notSetUp = true))
  @Test fun quickHidden() = quick("quick-hidden", "Quick search: results with sensitive files hidden (chip)", QuickUiState(query = "wedding", resultsQuery = "wedding", hits = hits("wedding"), hiddenCount = 3))
  @Test fun quickRevealed() = quick("quick-revealed", "Quick search: sensitive files shown for now", QuickUiState(query = "wedding", resultsQuery = "wedding", hits = hits("wedding"), hiddenCount = 0, showsSensitive = true, revealedForNow = true))
  @Test fun quickTile() = quick("quick-offer-tile", "Quick search: offer to add the Quick Settings tile", QuickUiState(query = "wedding", resultsQuery = "wedding", hits = hits("wedding")), offerTile = true)
  @Test fun quickDeviceLocked() = quick("quick-device-locked", "Quick search: phone locked", QuickUiState(), QuickMode.DEVICE_LOCKED)
  @Test fun quickAppLocked() = quick("quick-app-locked", "Quick search: Findr locked", QuickUiState(), QuickMode.APP_LOCKED)
  @Test fun quickLandscape() = quick("quick-landscape", "Quick search: landscape", QuickUiState(query = "wedding", resultsQuery = "wedding", hits = hits("wedding")), cells = listOf(Cfg(1.0f, false, Dev.LAND), Cfg(2.0f, false, Dev.LAND), Cfg(1.0f, false, Dev.TABLET_LAND)))

  // ---- keep-learning (the black "leave it charging overnight" screen): its two screens are private to the activity, so they are called by reflection ----
  @Composable private fun keepScreen(name: String, vararg args: Any?) {
    val composer = currentComposer
    val m = Class.forName("com.craneai.findr.ui.keeplearning.KeepLearningActivityKt").declaredMethods.first { it.name == name }
    m.isAccessible = true
    m.invoke(null, *args, composer, 0)
  }
  private fun keepBattery(plugged: Boolean, pct: Int): Any = Class.forName("com.craneai.findr.ui.keeplearning.BatteryState")
    .declaredConstructors.first { it.parameterCount == 3 }.newInstance(plugged, pct, 31.0)

  @Test fun keepLearningRunning() {
    for ((n, st, plugged) in listOf(Triple("charging", Status.indexing, true), Triple("battery", Status.indexing, false), Triple("done", Status.upToDate, true)))
      window("keep-running-$n", "Keep learning screen: running, $n", Overlay.NONE, listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.0f, false, Dev.LAND))) { keepScreen("Running", st, keepBattery(plugged, 64), {}) }
  }
  @Test fun keepLearningStopped() {
    for (r in com.craneai.findr.ui.keeplearning.KeepLearning.Stop.entries)
      window("keep-stopped-${r.name.lowercase()}", "Keep learning screen: stopped, ${r.name}", Overlay.NONE, listOf(Cfg(1.0f), Cfg(2.0f))) { keepScreen("Stopped", r, {}) }
  }
}
