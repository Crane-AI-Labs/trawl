package com.craneai.findr.ui.delivery

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.R
import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.Refusal
import com.craneai.findr.core.RemovableFile
import com.craneai.findr.ui.atlas.AtlasEngine
import com.craneai.findr.ui.atlas.AtlasTestBase
import com.craneai.findr.ui.atlas.Corpus
import com.craneai.findr.ui.atlas.FULL_ACCESS
import com.craneai.findr.ui.theme.FindrTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

class HonestModelsCardTest : AtlasTestBase() {
  @get:Rule val rule = createComposeRule()
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val compact = mutableStateOf(false)
  private val folded = mutableStateOf(false)
  private val allPacks = listOf(PackCatalog.QUICK_LOOK, PackCatalog.PRIVACY, PackCatalog.SPEECH)
  private val installed = DeliveryState(
    phase = DeliveryPhase.REFUSED, refusal = Refusal.DAMAGED, sizeMb = 670,
    searchModelInstalled = true, missingPacks = allPacks,
  )

  private fun draw(state: DeliveryState): AtlasEngine {
    val engine = AtlasEngine(delivery = state, corpus = Corpus.EMPTY)
    val viewModel = DeliveryViewModel(engine)
    rule.setContent {
      FindrTheme { GetModelsCard(viewModel, FULL_ACCESS, compact = compact.value, folded = folded.value) }
    }
    return engine
  }

  private fun visible(text: String) {
    assertTrue(text, rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
  }

  private fun noDamage() {
    rule.onAllNodes(hasText("damaged", substring = true), useUnmergedTree = true).assertCountEquals(0)
  }

  private fun wordsAreWhole(text: String) {
    val nodes = rule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
    assertTrue(text, nodes.isNotEmpty())
    for (node in nodes) {
      val layouts = mutableListOf<TextLayoutResult>()
      node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
      assertFalse("Recovery text is clipped: $text", layouts.any { it.hasVisualOverflow })
    }
  }

  private fun packName(id: String): String = context.getString(when (id) {
    PackCatalog.QUICK_LOOK -> R.string.pack_quicklook_name
    PackCatalog.PRIVACY -> R.string.pack_privacy_name
    else -> R.string.pack_speech_name
  })

  @Test fun installedModelNamesExactlyTheMissingPacksInCardsAndStrips() {
    val engine = draw(installed)
    val missingSets = allPacks.map { listOf(it) } + listOf(allPacks.take(2), allPacks.drop(1), allPacks)
    for (isCompact in listOf(false, true)) {
      for (missing in missingSets) {
        rule.runOnIdle { compact.value = isCompact; engine.deliveryFlow.value = installed.copy(missingPacks = missing) }
        visible(context.getString(R.string.models_missing_packs, missing.joinToString(", ", transform = ::packName)))
        visible(context.getString(R.string.models_missing_full_bundle))
        wordsAreWhole(context.getString(R.string.models_missing_full_bundle))
        visible(context.getString(R.string.models_get_full_bundle, installed.sizeMb))
        noDamage()
      }
    }
  }

  @Test fun installedModelWithEveryPackKeepsTheDownloadedFileFailureSeparate() {
    draw(installed.copy(missingPacks = emptyList()))
    visible(context.getString(R.string.models_refused_installed_safe))
    noDamage()
    rule.onAllNodes(hasText("Missing packs:", substring = true), useUnmergedTree = true).assertCountEquals(0)
  }

  @Test fun everyRefusalKeepsTheInstalledSearchModelAndMissingPackExplanation() {
    val engine = draw(installed)
    for (reason in Refusal.entries) {
      rule.runOnIdle { engine.deliveryFlow.value = installed.copy(refusal = reason) }
      visible(context.getString(R.string.models_missing_packs, allPacks.joinToString(", ", transform = ::packName)))
      visible(context.getString(R.string.models_get_full_bundle, installed.sizeMb))
      noDamage()
    }
  }

  @Test fun truncatedDownloadSaysBrowserCanContinueNotDamageOrStartAgain() {
    val incomplete = DeliveryState(
      phase = DeliveryPhase.REFUSED, refusal = Refusal.INCOMPLETE, sizeMb = 670,
      doneBytes = 300_000_000, totalBytes = 670_000_000,
    )
    val engine = draw(incomplete)
    for (hasModel in listOf(false, true)) {
      for (isCompact in listOf(false, true)) {
        rule.runOnIdle {
          compact.value = isCompact
          engine.deliveryFlow.value = incomplete.copy(searchModelInstalled = hasModel, missingPacks = if (hasModel) allPacks else emptyList())
        }
        visible("300 of 670 MB arrived.")
        visible("The download did not finish.")
        visible("let your browser continue it")
        wordsAreWhole("let your browser continue it")
        noDamage()
        rule.onAllNodes(hasText("start it again", substring = true), useUnmergedTree = true).assertCountEquals(0)
        if (hasModel) visible(context.getString(R.string.models_missing_packs, allPacks.joinToString(", ", transform = ::packName)))
      }
    }
  }

  @Test fun unknownDownloadSizeDoesNotInventProgress() {
    draw(DeliveryState(phase = DeliveryPhase.REFUSED, refusal = Refusal.INCOMPLETE))
    visible(context.getString(R.string.models_refused_incomplete))
    rule.onAllNodes(hasText("MB arrived.", substring = true), useUnmergedTree = true).assertCountEquals(0)
    noDamage()
  }

  @Test fun idleDownloadingInstallingDoneAndRefusedKeepTheirOwnMessages() {
    val engine = draw(DeliveryState(sizeMb = 670))
    visible(context.getString(R.string.models_title))
    visible(context.getString(R.string.models_get, 670))
    rule.runOnIdle { engine.deliveryFlow.value = DeliveryState(phase = DeliveryPhase.DOWNLOADING, doneBytes = 10_000_000) }
    visible("Downloading")
    rule.runOnIdle { engine.deliveryFlow.value = DeliveryState(phase = DeliveryPhase.INSTALLING, doneBytes = 50, totalBytes = 100) }
    visible(context.getString(R.string.models_installing_pct, 50))
    rule.runOnIdle {
      engine.deliveryFlow.value = DeliveryState(phase = DeliveryPhase.DONE, removable = RemovableFile("trawl-models.zip", 10_000_000))
    }
    visible(context.getString(R.string.models_done))
    visible(context.getString(R.string.models_free_yes))
    rule.runOnIdle { engine.deliveryFlow.value = DeliveryState(phase = DeliveryPhase.REFUSED, refusal = Refusal.DAMAGED) }
    visible("The downloaded file is damaged")
  }

  @Test fun installedIdleAndFoldedRefusalsDoNotOfferAReplacementSearchModel() {
    val engine = draw(installed.copy(phase = DeliveryPhase.IDLE, choiceOffered = true))
    visible(context.getString(R.string.models_missing_packs, allPacks.joinToString(", ", transform = ::packName)))
    visible(context.getString(R.string.models_get_full_bundle, 670))
    rule.onAllNodes(hasText(context.getString(R.string.models_choice_title)), useUnmergedTree = true).assertCountEquals(0)
    rule.runOnIdle { compact.value = true; folded.value = true; engine.deliveryFlow.value = installed }
    visible(context.getString(R.string.models_missing_packs, allPacks.joinToString(", ", transform = ::packName)))
    noDamage()
  }

  private fun translatedRecovery() {
    draw(installed.copy(refusal = Refusal.INCOMPLETE))
    visible(context.getString(R.string.models_refused_incomplete))
    visible(context.getString(R.string.models_missing_packs, allPacks.joinToString(", ", transform = ::packName)))
    visible(context.getString(R.string.models_missing_full_bundle))
    visible(context.getString(R.string.models_get_full_bundle, installed.sizeMb))
    assertFalse(context.getString(R.string.models_refused_incomplete).startsWith("The download"))
    assertFalse(context.getString(R.string.models_missing_packs, "packs").startsWith("The search model"))
  }

  @Test @Config(qualifiers = "lg") fun lugandaRecoveryUsesTheTranslatedStrings() = translatedRecovery()
  @Test @Config(qualifiers = "sw") fun swahiliRecoveryUsesTheTranslatedStrings() = translatedRecovery()

  @Test @Config(qualifiers = "w720dp-h360dp") fun shortScreenKeepsMissingPacksAndTheFullBundleFallbackReadable() {
    compact.value = true
    draw(installed)
    visible(context.getString(R.string.models_missing_packs, allPacks.joinToString(", ", transform = ::packName)))
    wordsAreWhole(context.getString(R.string.models_missing_full_bundle))
    noDamage()
  }
}
