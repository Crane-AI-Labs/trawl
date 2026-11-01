package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.search.shownFiles
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

/** Search results in every kind, sort and date state, and the empty and in-between states. */
class ResultsAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun results(
    id: String, title: String, query: String, cells: List<Cfg>, corpus: Corpus = Corpus.FULL, status: IndexStatus = Status.upToDate,
    hidden: Int = 0, kbUp: Boolean = false, blur: Boolean = false, repairing: Boolean = false, meaning: Boolean = true,
    setup: (Vms) -> Unit = {}, after: (Atlas, Vms) -> Unit = { _, _ -> },
  ) {
    val a = Atlas(rule, "results")
    a.forEach(cells, id) { c ->
      prefs()
      val v = Vms(AtlasEngine(status = status, corpus = corpus, hidden = hidden, meaning = meaning))
      if (repairing) v.engine.repairingFlow.value = true
      seedStrips(v.hitsFor(query))
      setup(v)
      a.launchSearch(c, v, blur = blur)
      a.focusField()
      a.type(query)
      if (!kbUp) a.keyboardDown()
      after(a, v)
      a.shot(id, title)
    }
    a.assertNoFailures()
  }

  // ---- The main queries, full matrix on the two that matter most ----
  @Test fun wedding() = results("res-wedding", "Results for \"wedding\" (photos, video moments, a programme PDF)", "wedding", Cfg.full)
  @Test fun weddingKeyboardUp() = results("res-wedding-kbup", "Results for \"wedding\", keyboard still up (compact rows)", "wedding", Cfg.mini, kbUp = true)
  @Test fun weddingPhotosOnly() = results("res-wedding-photos-phone", "Results for \"wedding\" on a photos-only phone", "wedding", Cfg.full, Corpus.MEDIA_ONLY)
  @Test fun school() = results("res-school", "Results for \"school\" (receipts with snippets, a video moment, a voice note)", "school", Cfg.full)
  @Test fun queries() {
    for (q in listOf("boda", "receipt", "momo", "sermon", "sunset", "cake", "graduation", "clinic letter", "umeme", "kampala"))
      results("res-q-${q.replace(' ', '-')}", "Results for \"$q\"", q, Cfg.mini)
  }
  @Test fun luganda() = results("res-q-luganda", "Results for a Luganda query", "embaga", Cfg.mini)

  // ---- Kind filters with a query ----
  @Test fun filters() {
    for (f in KindFilter.entries) results("res-filter-${f.name.lowercase()}", "Results for \"wedding\", filter ${f.name}", "wedding", Cfg.mini, setup = { it.search.setFilter(f) })
  }
  @Test fun filtersNothing() {
    for (f in listOf(KindFilter.AUDIO, KindFilter.SCREENSHOTS, KindFilter.DOCUMENTS)) results("res-filter-empty-${f.name.lowercase()}", "\"wedding\" in ${f.name}: nothing of that kind", "wedding", Cfg.mini, setup = { it.search.setFilter(f) })
  }

  // ---- Sort and date ----
  @Test fun sorts() {
    for (s in SortOrder.entries) results("res-sort-${s.name.lowercase()}", "Results for \"school\", sort ${s.name}", "school", Cfg.mini, setup = { it.search.setSort(s) })
  }
  @Test fun dates() {
    for (d in DatePreset.entries) results("res-date-${d.name.lowercase()}", "Results for \"school\", date ${d.name}", "school", Cfg.mini, setup = { it.search.setDatePreset(d) })
  }
  @Test fun dateRange() = results("res-date-range", "Results for \"wedding\", custom date range", "wedding", Cfg.mini,
    setup = { it.search.setDateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 12)) })
  @Test fun datePhrase() {
    for (q in listOf("receipts last week", "wedding october", "photos this month", "school fees 2026"))
      results("res-datephrase-${q.replace(' ', '-')}", "Date read from the query: \"$q\"", q, Cfg.mini)
  }
  @Test fun dateNothing() = results("res-date-nothing", "\"wedding\" with date TODAY: nothing in that period", "wedding", Cfg.mini, setup = { it.search.setDatePreset(DatePreset.TODAY) })
  @Test fun sortAndDateAndFilter() = results("res-sort-date-filter", "\"wedding\": Photos, Oldest, This year", "wedding", Cfg.mini,
    setup = { it.search.setFilter(KindFilter.PHOTOS); it.search.setSort(SortOrder.OLDEST); it.search.setDatePreset(DatePreset.THIS_YEAR) })

  // ---- Nothing found, and the index not being done ----
  @Test fun nothingFound() = results("res-nothing", "Nothing found for a query", "zzqx", Cfg.full)
  @Test fun nothingFoundIncomplete() = results("res-nothing-indexing", "Nothing found while the index is still reading", "zzqx", Cfg.mini, status = Status.indexing)
  @Test fun nothingFoundPhotosOnly() = results("res-nothing-photos-phone", "Nothing found on a photos-only phone", "zzqx", Cfg.mini, Corpus.MEDIA_ONLY)
  @Test fun nothingFoundEmptyPhone() = results("res-nothing-empty-phone", "Nothing found on a phone with no files", "wedding", Cfg.mini, Corpus.EMPTY, Status.notStarted)
  @Test fun nothingFoundKeyboard() = results("res-nothing-kbup", "Nothing found, keyboard up", "zzqx", Cfg.mini, kbUp = true)
  @Test fun nothingFoundFilter() = results("res-nothing-filter", "Nothing found with a filter", "zzqx", Cfg.mini, setup = { it.search.setFilter(KindFilter.PHOTOS) })
  @Test fun resultsWhileIndexing() = results("res-while-indexing", "Results while the index is still reading (note under the box)", "wedding", Cfg.mini, status = Status.indexing)
  @Test fun resultsWaitingCharger() = results("res-waiting-charger", "Results, reading paused until charging", "wedding", Cfg.mini, status = Status.paused(com.craneai.findr.core.PauseReason.NOT_CHARGING))
  @Test fun resultsNoModel() = results("res-no-model", "Results by words only (no model yet)", "wedding", Cfg.mini, status = Status.indexingNoModel, meaning = false)
  @Test fun resultsRepairing() = results("res-repairing", "Results while the engine repairs itself", "wedding", Cfg.mini, repairing = true, meaning = false)
  @Test fun resultsHidden() = results("res-hidden", "Results with sensitive files hidden (chip)", "wedding", Cfg.mini, hidden = 2)
  @Test fun resultsBlur() = results("res-blur-on", "Results with private previews blurring switched on", "wedding", Cfg.mini, blur = true)

  // ---- Weak matches ----
  @Test fun weakCollapsed() = results("res-weak-collapsed", "Strong matches, then a collapsed \"weaker matches\" toggle", "family", Cfg.mini)
  @Test fun weakOpen() = results("res-weak-open", "Weaker matches opened", "family", Cfg.mini, after = { a, v -> v.search.toggleWeak(); a.settle(500) })

  // ---- Selection and find similar ----
  @Test fun select() {
    for (n in listOf(1, 3)) results("res-select-$n", "Selection mode, $n ticked", "wedding", Cfg.mini, after = { a, v ->
      v.search.state.value.feed.shownFiles().take(n).forEach { v.search.toggleSelected(it) }; a.settle(500)
    })
  }
  @Test fun selectAll() = results("res-select-all", "Selection mode, all ticked", "wedding", Cfg.mini, after = { a, v -> v.search.toggleSelected(v.search.state.value.feed.shownFiles().first()); v.search.selectAll(); a.settle(500) })
  @Test fun selectHome() {
    val a = Atlas(rule, "results")
    a.forEach(Cfg.mini, "res-select-home") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      v.search.state.value.feed.shownFiles().take(2).forEach { v.search.toggleSelected(it) }; a.settle(500)
      a.shot("res-select-home", "Selection mode on the home grid, 2 ticked")
    }
    a.assertNoFailures()
  }
  @Test fun findSimilar() {
    val a = Atlas(rule, "results")
    a.forEach(Cfg.mini, "res-similar") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      val f = v.engine.files.first { it.kind == FileKind.IMAGE && it.name.startsWith("IMG_20261003_121502") }
      v.search.findSimilar(f); a.settle(800)
      a.shot("res-similar", "Find similar: files like a photo (chip with its name)")
      v.search.clearSimilar(); a.settle(500)
      a.shot("res-similar-cleared", "Find similar taken away (back to home)")
    }
    a.assertNoFailures()
  }

  // ---- In-between: typed but the search has not come back yet ----
  @Test fun typingNoSettle() {
    val a = Atlas(rule, "results")
    a.forEach(Cfg.mini, "res-typing") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      a.focusField()
      a.field().performTextInputNoWait("wed")
      a.shot("res-typing-1-just-typed", "Just typed \"wed\" (before the 250 ms pause: home list still showing)")
      a.settle(900)
      a.shot("res-typing-2-settled", "Typed \"wed\", search came back")
    }
    a.assertNoFailures()
  }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTextInputNoWait(t: String) = performTextInput(t)
