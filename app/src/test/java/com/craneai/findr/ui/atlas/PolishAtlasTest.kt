package com.craneai.findr.ui.atlas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit
import com.craneai.findr.quick.QuickMode
import com.craneai.findr.quick.QuickUiState
import com.craneai.findr.ui.search.FileRow
import com.craneai.findr.ui.search.NothingFound
import com.craneai.findr.ui.search.SelectionActionBar
import com.craneai.findr.ui.search.SelectionTopBar
import com.craneai.findr.ui.search.SimilarChip
import com.craneai.findr.ui.settings.SettingsScreen
import com.craneai.findr.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

/**
 * The 1.0.6 polish items (UI audit P1-P9), one group of cells per item, drawn the same way on the 1.0.5 code and on the polish branch
 * so the two sets of pictures compare cell for cell. Group "polish"; ids start with the item ("p1-meter-…").
 */
class PolishAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private val large = listOf(Cfg(1.0f), Cfg(2.0f), Cfg(2.0f, true), Cfg(1.3f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.PHONE, "lg"))

  private fun home(id: String, title: String, cells: List<Cfg>, corpus: Corpus = Corpus.MEDIA_ONLY, status: IndexStatus = Status.upToDate,
                   delivery: com.craneai.findr.core.DeliveryState = Deliveries.idle) {
    val a = Atlas(rule, "polish")
    a.forEach(cells, id) { c ->
      prefs()
      val v = Vms(AtlasEngine(status = status, delivery = delivery, corpus = corpus))
      a.launchSearch(c, v)
      a.settle(300)
      a.shot(id, title)
    }
    a.assertNoFailures()
  }

  private fun results(id: String, title: String, query: String, cells: List<Cfg>, corpus: Corpus = Corpus.FULL, status: IndexStatus = Status.upToDate, meaning: Boolean = true) {
    val a = Atlas(rule, "polish")
    a.forEach(cells, id) { c ->
      prefs()
      val v = Vms(AtlasEngine(status = status, corpus = corpus, meaning = meaning))
      seedStrips(v.hitsFor(query))
      a.launchSearch(c, v)
      a.focusField(if (c.dev == Dev.LAND || c.dev == Dev.SPLIT) 160 else 300)
      a.type(query)
      a.keyboardDown()
      a.shot(id, title)
    }
    a.assertNoFailures()
  }

  private fun column(id: String, title: String, cells: List<Cfg>, content: @Composable () -> Unit) {
    val a = Atlas(rule, "polish")
    a.forEach(cells, id) { c ->
      prefs()
      a.launch(c) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
      }
      a.settle(900)
      a.shot(id, title)
    }
    a.assertNoFailures()
  }

  // ---- P1: the meter's title at large text ----
  @Test fun p1Meter() {
    home("p1-meter-indexing", "Meter while reading (\"Learning your files\")", large, status = Status.indexing)
    home("p1-meter-scanning", "Meter while looking through the phone", large, status = Status.scanning)
    home("p1-meter-models", "Meter waiting for the models (download running)", large, status = Status.indexingNoModel, delivery = Deliveries.downloading)
  }

  // ---- P2: Settings, the storage rows ----
  @Test fun p2Storage() {
    val a = Atlas(rule, "polish")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.3f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.PHONE, "lg")), "p2-storage") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.indexing))
      a.launch(c) { SettingsScreen(v.index, v.addOns, v.delivery, ThemeMode.LIGHT, {}, {}, onBack = {}) }
      a.settle(900)
      a.scrollToStorage()
      a.settle(300)
      a.shot("p2-storage", "Settings, the storage rows (value beside or under its label)")
    }
    a.assertNoFailures()
  }

  // ---- P3: results with no model yet, at large text ----
  @Test fun p3NoModel() {
    results("p3-no-model", "Results by words only, no model yet (models card above)", "wedding",
      listOf(Cfg(1.0f), Cfg(1.3f), Cfg(2.0f), Cfg(1.0f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.PHONE, "lg"),
        Cfg(1.0f, false, Dev.TAB_A9), Cfg(1.0f, false, Dev.TAB_A9_LAND), Cfg(1.0f, false, Dev.TABLET), Cfg(2.0f, false, Dev.SMALL)), status = Status.indexingNoModel, meaning = false)
    // The Recents agent's report on the Galaxy Tab: no models, font 1.0, "market" typed and the keyboard closed.
    results("p3-no-model-market", "No model yet, \"market\" typed, keyboard closed (Galaxy Tab report)", "market",
      listOf(Cfg(1.0f, false, Dev.TAB_A9), Cfg(1.0f, false, Dev.TAB_A9_LAND), Cfg(1.0f), Cfg(2.0f, false, Dev.SMALL)), status = Status.indexingNoModel, meaning = false)
  }

  // ---- P4: the bottom of the list against the search box ----
  @Test fun p4Dock() = home("p4-dock", "Home, the last visible row against the search box", listOf(Cfg(1.0f), Cfg(1.3f), Cfg(2.0f), Cfg(1.0f, false, Dev.LAND), Cfg(1.0f, false, Dev.TABLET_LAND)))

  // ---- P5: file names in the Find-similar chip and its empty state ----
  @Test fun p5Similar() {
    val a = Atlas(rule, "polish")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f), Cfg(2.0f, true), Cfg(1.0f, false, Dev.PHONE, "lg")), "p5-similar") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      val f = v.engine.files.first { it.kind == FileKind.IMAGE && it.name.startsWith("IMG_20261003_121502") }
      v.search.findSimilar(f); a.settle(800)
      a.shot("p5-similar", "Find similar: the chip with the photo's name")
    }
    a.assertNoFailures()
    column("p5-similar-parts", "Find similar: the chip and the nothing-found words with a long name",
      listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.0f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.PHONE, "lg"))) {
      SimilarChip("IMG_20261003_121502.jpg", {})
      SimilarChip("Screenshot_20261012-184455_WhatsApp.png", {})
      Box(Modifier.fillMaxWidth().height(360.dp)) { NothingFound("", indexIncomplete = false, similarTo = "IMG_20261003_121502.jpg") }
    }
  }

  // ---- P6: the empty states in a short window ----
  @Test fun p6ShortEmpty() {
    val cells = listOf(Cfg(1.0f, false, Dev.LAND), Cfg(2.0f, false, Dev.LAND), Cfg(2.0f, false, Dev.SPLIT), Cfg(2.0f, false, Dev.LAND, "lg"))
    results("p6-nothing-short", "Nothing found in a short window (phone on its side, split screen)", "zzqx", cells)
    home("p6-fresh-short", "Fresh phone (no files yet) in a short window", cells, Corpus.EMPTY, Status.notStarted)
  }

  // ---- P7: a video row's strip, first frame and after the plan comes back ----
  @Test fun p7Strip() {
    val a = Atlas(rule, "polish")
    for ((label, durationMs) in listOf("short" to 400L, "long" to 60_000L)) {
      a.forEach(listOf(Cfg(1.0f), Cfg(2.0f)), "p7-strip-$label") { c ->
        prefs()
        val v = Vms(AtlasEngine(corpus = Corpus.FULL))
        val src = v.engine.files.first { it.kind == FileKind.VIDEO }
        val file = src.copy(id = src.id + 900_000 + durationMs, uri = "fake://polish/strip/$label/${c.tag}", durationMs = durationMs)
        val moment = durationMs / 2
        val hit = SearchHit(file, 1.0, MatchReason.MEANING, locator = Locator.Time(moment))
        a.launch(c) { Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp)) { FileRow(hit, {}, {}, {}, {}, onOpenAt = {}) } }
        a.shot("p7-strip-$label-1-first", "Video row ($label clip), first frame: nothing known about the strip yet")
        // The plan arrives (from the cache here; on a phone, from the file): a short clip has one picture, which is not a strip.
        seedStrips(listOf(hit))
        a.settle(1500)
        a.shot("p7-strip-$label-2-planned", "Video row ($label clip), after the strip's plan came back")
      }
    }
    a.assertNoFailures()
  }

  // ---- P8: Share and Copy, phone, tablet and quick panel ----
  @Test fun p8Actions() {
    results("p8-rows", "Document rows with Share and Copy", "school", listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.0f, false, Dev.TABLET), Cfg(1.0f, false, Dev.PHONE, "lg")))
    val a = Atlas(rule, "polish")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.0f, false, Dev.PHONE, "lg")), "p8-quick") { c ->
      prefs()
      val hits = Vms().hitsFor("school")
      a.launch(c) {
        com.craneai.findr.quick.QuickSearchPanel(
          state = QuickUiState(query = "school", resultsQuery = "school", hits = hits), mode = QuickMode.SEARCH, text = TextFieldState("school"), reopen = 0, offerTile = false,
          onQuery = {}, onFocused = {}, onTyping = {}, onHitsDrawn = {}, onResultsShown = {}, onClose = {}, onShare = {}, onCopy = {}, onOpen = {},
          onShowFolder = {}, onMarkSensitive = { _, _ -> }, onUnlock = {}, onOpenFindr = {}, onAddTile = {}, onDeclineTile = {},
        )
      }
      a.settle(900)
      a.shot("p8-quick", "Quick search panel: rows with Share and Copy")
    }
    a.assertNoFailures()
  }

  // ---- P9: keep learning's stopped screens, Luganda copy ----
  @Composable private fun keepStopped(reason: com.craneai.findr.ui.keeplearning.KeepLearning.Stop) {
    val composer = currentComposer
    val m = Class.forName("com.craneai.findr.ui.keeplearning.KeepLearningActivityKt").declaredMethods.first { it.name == "Stopped" }
    m.isAccessible = true
    m.invoke(null, reason, {}, composer, 0)
  }

  @Test fun p9Keep() {
    for (r in listOf(com.craneai.findr.ui.keeplearning.KeepLearning.Stop.HOT, com.craneai.findr.ui.keeplearning.KeepLearning.Stop.UNPLUGGED)) {
      val a = Atlas(rule, "polish")
      a.forEach(listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.0f, false, Dev.PHONE, "lg")), "p9-keep-${r.name.lowercase()}") { c ->
        prefs()
        a.launch(c) { Box(Modifier.fillMaxSize()) { keepStopped(r) } }
        a.settle(900)
        a.shot("p9-keep-${r.name.lowercase()}", "Keep learning stopped (${r.name}): the Close button on black")
      }
      a.assertNoFailures()
    }
    val a = Atlas(rule, "polish")
    a.forEach(listOf(Cfg(1.0f, false, Dev.PHONE, "lg"), Cfg(1.0f)), "p9-quick-locked") { c ->
      prefs()
      a.launch(c) {
        com.craneai.findr.quick.QuickSearchPanel(
          state = QuickUiState(), mode = QuickMode.DEVICE_LOCKED, text = TextFieldState(""), reopen = 0, offerTile = false,
          onQuery = {}, onFocused = {}, onTyping = {}, onHitsDrawn = {}, onResultsShown = {}, onClose = {}, onShare = {}, onCopy = {}, onOpen = {},
          onShowFolder = {}, onMarkSensitive = { _, _ -> }, onUnlock = {}, onOpenFindr = {}, onAddTile = {}, onDeclineTile = {},
        )
      }
      a.settle(900)
      a.shot("p9-quick-locked", "Quick search while the phone is locked")
    }
    a.assertNoFailures()
    column("p9-selection", "Selection bars in Luganda", listOf(Cfg(1.0f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.PHONE, "lg"))) {
      SelectionTopBar(1, false, {}, {}); SelectionTopBar(12, true, {}, {})
      SelectionActionBar({}, {}, {}, null, {})
    }
  }
}

