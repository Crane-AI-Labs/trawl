package com.craneai.findr.ui.atlas

import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.Refusal
import com.craneai.findr.core.SortOrder
import com.craneai.findr.engine.Edition
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.junit4.createEmptyComposeRule

/** Home: the screen that opens with no query, in every state the index, the models, the permissions and the filters can put it in. */
class HomeAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun home(id: String, title: String, cells: List<Cfg>, corpus: Corpus = Corpus.FULL, access: AccessState0 = AccessState0.FULL,
                   status: com.craneai.findr.core.IndexStatus = Status.upToDate, delivery: com.craneai.findr.core.DeliveryState = Deliveries.idle,
                   hidden: Int = 0, firstSearchDone: Boolean = false, imeDp: Int = 0, setup: (Vms) -> Unit = {}, repairing: Boolean = false) {
    val atlas = Atlas(rule, "home")
    atlas.forEach(cells, id) { c ->
      prefs(firstSearchDone = firstSearchDone)
      val v = Vms(AtlasEngine(status = status, delivery = delivery, corpus = corpus, hidden = hidden))
      if (repairing) v.engine.repairingFlow.value = true
      setup(v)
      atlas.launchSearch(c, v, access.state)
      if (imeDp > 0) atlas.focusField(imeDp)
      atlas.settle(300)
      atlas.shot(id, title)
    }
    atlas.assertNoFailures()
  }

  // ---- The home grid itself: the two corpora, the full matrix ----
  @Test fun homePhotosOnly() = home("home-photos-phone-like-S24", "Home, photos/screenshots/videos only (recent photos grid)", Cfg.full, Corpus.MEDIA_ONLY)
  @Test fun homeMixed() = home("home-mixed", "Home, documents then photos", Cfg.full)
  @Test fun homeDocsOnly() = home("home-docs-only", "Home, documents only", Cfg.mini, Corpus.DOCS_ONLY)
  @Test fun homeFreshPhone() = home("home-fresh-phone", "Home, no files at all (fresh phone)", Cfg.mini, Corpus.EMPTY, status = Status.notStarted)
  @Test fun homeEmptyUpToDate() = home("home-empty-indexed", "Home, no files, index up to date", Cfg.mini, Corpus.EMPTY)
  @Test fun homeKeyboardUp() = home("home-keyboard-up", "Home, search box focused, keyboard up (compact rows)", Cfg.full, Corpus.MEDIA_ONLY, imeDp = 300)
  @Test fun homeKeyboardUpMixed() = home("home-keyboard-up-mixed", "Home, mixed corpus, keyboard up", Cfg.mini, imeDp = 300)
  @Test fun homeKeyboardTall() = home("home-keyboard-tall", "Home, tall keyboard (360 dp)", Cfg.mini, Corpus.MEDIA_ONLY, imeDp = 360)

  // ---- Index meter in each phase ----
  @Test fun meterScanning() = home("home-index-scanning", "Home, index scanning", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.scanning)
  @Test fun meterIndexing() = home("home-index-indexing", "Home, index reading by meaning", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.indexing)
  @Test fun meterIndexingMixed() = home("home-index-indexing-mixed", "Home, mixed corpus, index reading", Cfg.mini, status = Status.indexing)
  @Test fun meterNoModel() = home("home-index-no-model", "Home, index running with no model yet", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.indexingNoModel)
  @Test fun meterWaiting() = home("home-index-waiting-models", "Home, words searchable, waiting for models", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.waitingForModels)
  @Test fun meterNotStarted() = home("home-index-not-started", "Home, index not started", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.notStarted)
  @Test fun meterError() = home("home-index-error-storage-full", "Home, index error: storage full", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.error)
  @Test fun meterErrorBusy() = home("home-index-error-busy", "Home, index busy (will open by itself)", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.errorBusy)
  @Test fun meterReembed() = home("home-index-reembedding", "Home, re-reading after a model update", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.reembedding)
  @Test fun meterPaused() { for (r in PauseReason.entries) home("home-index-paused-${r.name.lowercase()}", "Home, index paused: $r", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.paused(r)) }

  // ---- Home cards ----
  @Test fun cardNoFilesAccess() = home("home-card-files-access", "Home, All files access not granted", Cfg.full, Corpus.EMPTY, AccessState0.NO_FILES, Status.notStarted)
  @Test fun cardNoFilesAccessKeyboard() = home("home-card-files-access-keyboard", "Home, files access card with keyboard up (strip)", Cfg.mini, Corpus.EMPTY, AccessState0.NO_FILES, Status.notStarted, imeDp = 300)
  @Test fun cardNotifications() = home("home-card-notifications", "Home, notification ask after the first search", Cfg.mini, Corpus.MEDIA_ONLY, AccessState0.NO_NOTIF, firstSearchDone = true)
  @Test fun cardUpdate() { assumeTrue(Edition.MODELS); home("home-card-model-update", "Home, model update notice", Cfg.mini, Corpus.MEDIA_ONLY, delivery = Deliveries.update) }
  @Test fun cardModels() {
    assumeTrue(Edition.MODELS)
    val noModel = Status.indexingNoModel
    val states = mapOf(
      "idle" to Deliveries.idle, "idle-choice" to Deliveries.idleWithChoice, "downloading" to Deliveries.downloading, "installing" to Deliveries.installing,
      "installing-unknown" to Deliveries.installingUnknown, "done-removable" to Deliveries.doneRemovable,
      "refused-not-signed" to Deliveries.refused(Refusal.NOT_SIGNED), "refused-bad-signature" to Deliveries.refused(Refusal.BAD_SIGNATURE),
      "refused-wrong-class" to Deliveries.refused(Refusal.WRONG_CLASS), "refused-incomplete" to Deliveries.refused(Refusal.INCOMPLETE, 300_000_000, 670_000_000),
      "refused-bad-manifest" to Deliveries.refused(Refusal.BAD_MANIFEST), "refused-damaged" to Deliveries.refused(Refusal.DAMAGED),
      "refused-already" to Deliveries.refused(Refusal.ALREADY_INSTALLED), "refused-older" to Deliveries.refused(Refusal.OLDER_VERSION),
      "refused-no-space" to Deliveries.refused(Refusal.NO_SPACE), "refused-cancelled" to Deliveries.refused(Refusal.CANCELLED),
      "refused-unreadable" to Deliveries.refused(Refusal.UNREADABLE),
    )
    for ((k, d) in states) home("home-card-models-$k", "Home, get-models card: $k", if (k == "idle" || k == "downloading") Cfg.full else Cfg.mini, Corpus.MEDIA_ONLY, status = noModel, delivery = d)
  }
  @Test fun cardModelsKeyboard() { assumeTrue(Edition.MODELS); home("home-card-models-keyboard", "Home, models card with keyboard up (strip)", Cfg.mini, Corpus.MEDIA_ONLY, status = Status.indexingNoModel, delivery = Deliveries.downloading, imeDp = 300) }

  // ---- Notes under the search box ----
  @Test fun hiddenChip() = home("home-hidden-chip", "Home, sensitive files hidden chip", Cfg.mini, Corpus.MEDIA_ONLY, hidden = 3)
  @Test fun repairing() = home("home-repairing", "Home, repairing note (engine self-test failed)", Cfg.mini, Corpus.MEDIA_ONLY, repairing = true)

  // ---- Kind filters, sort and date on home ----
  @Test fun kindFilters() {
    for (f in KindFilter.entries) home("home-filter-${f.name.lowercase()}", "Home, kind filter ${f.name}", Cfg.mini, setup = { it.search.setFilter(f) })
  }
  @Test fun kindFiltersMedia() {
    for (f in listOf(KindFilter.DOCUMENTS, KindFilter.AUDIO)) home("home-media-filter-${f.name.lowercase()}", "Home, photos-only phone, filter ${f.name} (empty filter)", Cfg.mini, Corpus.MEDIA_ONLY, setup = { it.search.setFilter(f) })
  }
  @Test fun sorts() {
    for (s in SortOrder.entries.filter { it != SortOrder.BEST }) home("home-sort-${s.name.lowercase()}", "Home, sort ${s.name}", Cfg.mini, setup = { it.search.setSort(s) })
  }
  @Test fun dates() {
    for (d in DatePreset.entries) home("home-date-${d.name.lowercase()}", "Home, date ${d.name}", Cfg.mini, Corpus.MEDIA_ONLY, setup = { it.search.setDatePreset(d) })
  }
  @Test fun dateNothing() = home("home-date-today-empty", "Home, date TODAY with filter that leaves nothing", Cfg.mini, Corpus.DOCS_ONLY, setup = { it.search.setDatePreset(DatePreset.TODAY) })

  enum class AccessState0(val state: com.craneai.findr.ui.app.AccessState) {
    FULL(FULL_ACCESS), NO_FILES(NO_FILES_ACCESS), NO_NOTIF(com.craneai.findr.ui.atlas.NO_NOTIF)
  }
}
