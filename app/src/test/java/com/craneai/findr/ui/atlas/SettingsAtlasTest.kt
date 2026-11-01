package com.craneai.findr.ui.atlas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackIds
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackState
import com.craneai.findr.core.packRows
import com.craneai.findr.engine.Edition
import com.craneai.findr.ui.addons.AddOnCard
import com.craneai.findr.ui.addons.AddPackDialog
import com.craneai.findr.ui.addons.RemovePackDialog
import com.craneai.findr.ui.language.LanguageDialog
import com.craneai.findr.ui.settings.LicencesScreen
import com.craneai.findr.ui.settings.SettingsScreen
import com.craneai.findr.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Settings (drawn whole, top to bottom, in a very tall window), its dialogs, the add-on cards in every state, and Licences. */
class SettingsAtlasTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun settingsPage(id: String, title: String, cells: List<Cfg>, status: IndexStatus = Status.upToDate, delivery: com.craneai.findr.core.DeliveryState = com.craneai.findr.core.DeliveryState(sizeMb = 670, installedChoice = com.craneai.findr.core.ModelChoice.STANDARD),
                           packs: Map<String, PackState> = PackIds.ALL.associateWith { PackState.NotInstalled }, settings: FindrSettings = FindrSettings(), mode: ThemeMode = ThemeMode.SYSTEM) {
    val a = Atlas(rule, "settings")
    a.forEach(cells, id) { c ->
      prefs()
      val v = Vms(AtlasEngine(status = status, settings = settings, delivery = delivery, packs = packs))
      a.launch(c) { SettingsScreen(v.index, v.addOns, v.delivery, if (c.dark) ThemeMode.DARK else mode, {}, {}, onBack = {}) }
      a.settle(900)
      a.shot(id, title)
    }
    a.assertNoFailures()
  }

  @Test fun page() = settingsPage("set-page", "Settings, whole page (models installed, no add-ons)", Cfg.tall)
  @Test fun pageIndexing() = settingsPage("set-page-indexing", "Settings while reading (storage shows the estimate)", Cfg.tall.take(3), Status.indexing)
  @Test fun pageNoModel() = settingsPage("set-page-nomodel", "Settings with no model on the phone (models card)", Cfg.tall.take(2), Status.indexingNoModel, Deliveries.idle)
  @Test fun pageDownloading() = settingsPage("set-page-downloading", "Settings while the models download", Cfg.tall.take(2), Status.indexingNoModel, Deliveries.downloading)
  @Test fun pageUpdate() = settingsPage("set-page-update", "Settings with a model update on offer", Cfg.tall.take(2), Status.upToDate, Deliveries.update)
  @Test fun pageUpdateReread() = settingsPage("set-page-update-reread", "Settings with a model update that re-reads every file", Cfg.tall.take(2), Status.upToDate, Deliveries.updateReread)
  @Test fun pageSaveData() = settingsPage("set-page-savedata", "Settings with the small (save data) model installed", Cfg.tall.take(2), Status.upToDate, Deliveries.saveDataInstalled)
  @Test fun pagePacksInstalled() = settingsPage(
    "set-page-packs", "Settings with all three add-ons installed", Cfg.tall.take(3), packs = PackIds.ALL.associateWith { PackState.Installed(120_000_000) },
  )
  @Test fun pagePacksInstalling() = settingsPage(
    "set-page-packs-installing", "Settings with add-ons being added", Cfg.tall.take(2),
    packs = PackIds.ALL.mapIndexed { i, id -> id to (if (i == 0) PackState.Installing(60_000_000, 142_000_000) else if (i == 1) PackState.Installing(0, 0) else PackState.NotInstalled) }.toMap(),
  )
  @Test fun pagePacksFailed() = settingsPage(
    "set-page-packs-failed", "Settings with add-ons that failed", Cfg.tall.take(2),
    packs = PackIds.ALL.mapIndexed { i, id -> id to PackState.Failed(PackProblem.entries[i % PackProblem.entries.size]) }.toMap(),
  )
  @Test fun pageNoKinds() = settingsPage(
    "set-page-changed", "Settings with kinds, privacy and speed set away from the defaults", Cfg.tall.take(2),
    settings = FindrSettings(learningSpeed = com.craneai.findr.core.LearningSpeed.GENTLE, indexPhotos = false, indexVideos = false, indexDocuments = false, indexRecordings = false, indexAudio = true, blurPrivatePreviews = false, showSensitive = true, excludedFolders = setOf("DCIM/Private"), privateFolders = setOf("Download/Bank")),
  )

  // ---- dialogs reached by tapping in Settings ----
  private fun settingsDialog(id: String, title: String, open: (Atlas) -> Unit) {
    val a = Atlas(rule, "settings")
    a.forEach(Cfg.mini, id) { c ->
      prefs()
      val v = Vms(AtlasEngine())
      a.launch(c) { SettingsScreen(v.index, v.addOns, v.delivery, if (c.dark) ThemeMode.DARK else ThemeMode.SYSTEM, {}, {}, onBack = {}) }
      a.settle(700)
      open(a)
      a.settle(700)
      a.shot(id, title, Overlay.CENTER)
    }
    a.assertNoFailures()
  }
  @Test fun deleteDialog() = settingsDialog("set-dialog-delete", "Settings: delete the index, confirmation") {
    it.rule.onNodeWithText(it.str(R.string.settings_delete, it.str(R.string.app_name))).performScrollTo().performClick()
  }
  @Test fun languageDialog() = settingsDialog("set-dialog-language", "Settings: language list") {
    it.rule.onNodeWithText(it.str(R.string.settings_language)).performScrollTo().performClick()
  }

  // ---- add-on cards in every state, and the add / remove dialogs ----
  private fun addOnCards(id: String, title: String, settings: FindrSettings = FindrSettings(), state: (String, Int) -> PackState) {
    assumeTrue(Edition.MODELS)
    val a = Atlas(rule, "settings")
    a.forEach(Cfg.mini, id) { c ->
      prefs()
      val states = PackCatalog.all.mapIndexed { i, d -> d.id to state(d.id, i) }.toMap()
      val rows = packRows(PackCatalog.all, states, settings)
      a.launch(c) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
          rows.forEach { AddOnCard(it, {}, {}, {}) }
        }
      }
      a.settle(500)
      a.shot(id, title)
    }
    a.assertNoFailures()
  }
  @Test fun cardsNotAdded() = addOnCards("addon-not-added", "Add-on cards: not added") { _, _ -> PackState.NotInstalled }
  @Test fun cardsAdding() = addOnCards("addon-adding", "Add-on cards: adding (progress, then size unknown)") { _, i -> if (i % 2 == 0) PackState.Installing(50_000_000, 140_000_000) else PackState.Installing(0, 0) }
  @Test fun cardsAddedOn() = addOnCards("addon-added-on", "Add-on cards: added, switched on") { _, _ -> PackState.Installed(117_000_000) }
  @Test fun cardsAddedOff() = addOnCards("addon-added-off", "Add-on cards: added, switched off", FindrSettings(quickLook = false, speechToText = false, privateScoring = false)) { _, _ -> PackState.Installed(117_000_000) }
  @Test fun cardsFailed() {
    for (p in PackProblem.entries) addOnCards("addon-failed-${p.name.lowercase()}", "Add-on cards: failed, ${p.name}") { _, _ -> PackState.Failed(p, "detail") }
  }

  // The dialogs are Compose dialogs, so they need a composition: draw them through a host activity content.
  private fun dialogHost(id: String, title: String, cells: List<Cfg> = Cfg.mini, content: @androidx.compose.runtime.Composable (PackRowSet) -> Unit) {
    assumeTrue(Edition.MODELS)
    val a = Atlas(rule, "settings")
    a.forEach(cells, id) { c ->
      prefs()
      val rows = PackRowSet(
        notAdded = packRows(PackCatalog.all, PackCatalog.all.associate { it.id to PackState.NotInstalled }, FindrSettings()),
        added = packRows(PackCatalog.all, PackCatalog.all.associate { it.id to PackState.Installed(117_000_000) }, FindrSettings()),
      )
      a.launch(c) { content(rows) }
      a.settle(800)
      a.shot(id, title, Overlay.CENTER)
    }
    a.assertNoFailures()
  }
  class PackRowSet(val notAdded: List<com.craneai.findr.core.PackRow>, val added: List<com.craneai.findr.core.PackRow>)

  @Test fun addDialogs() {
    for (i in 0..2) dialogHost("addon-dialog-add-$i", "Add-on dialog: add pack ${i + 1}") { r -> AddPackDialog(r.notAdded[i], null, {}, {}) }
  }
  @Test fun removeDialogs() {
    for (i in 0..2) dialogHost("addon-dialog-remove-$i", "Add-on dialog: remove pack ${i + 1}") { r -> RemovePackDialog(r.added[i], {}, {}) }
  }
  @Test fun languageDirect() = dialogHost("lang-dialog", "Language list dialog", Cfg.lean) { LanguageDialog(onDismiss = {}) }

  // ---- Licences ----
  @Test fun licences() {
    val a = Atlas(rule, "settings")
    a.forEach(listOf(Cfg(1.0f, false, Dev.TALL), Cfg(1.0f, true, Dev.TALL), Cfg(2.0f, false, Dev.PHONE), Cfg(1.0f, false, Dev.TABLET)), "licences") { c ->
      prefs()
      a.launch(c) { LicencesScreen(onBack = {}) }
      a.settle(700)
      a.shot("licences", "Licences page")
    }
    a.assertNoFailures()
  }
}
