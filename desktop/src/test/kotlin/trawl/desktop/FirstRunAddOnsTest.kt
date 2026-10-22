package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.settings.FirstRunAddOns

/** F4: the first-run page offers the add-ons that can be added on this computer now, once, and nothing when there is nothing to offer. */
@OptIn(ExperimentalTestApi::class)
class FirstRunAddOnsTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private fun def(id: String, files: AddOnFiles? = AddOnCatalog.privacyFiles, supported: () -> Boolean = { true }) =
    AddOnDef(id, "addon_privacy_name", "addon_privacy_benefit", "addon_privacy_cost", 1_000, files, supported)

  @Test fun offersWhatCanBeAddedAndIsNotAddedYet() {
    val defs = listOf(def("a"), def("b", files = null), def("c", supported = { false }), def("d"), def("e"))
    val states = mapOf<String, AddOnState>("d" to AddOnState.Installed(5), "e" to AddOnState.Failed(AddOnProblem.NOT_FOUND))
    assertEquals(listOf("a", "e"), AddOnOffer.idsAtFirstRun(defs, states), "not the coming-soon one, not the unsupported one, not the one already added; a failed try is offered again")
  }

  @Test fun nothingToOfferMeansNoSection() {
    assertTrue(AddOnOffer.idsAtFirstRun(listOf(def("b", files = null)), emptyMap()).isEmpty())
    assertTrue(AddOnOffer.idsAtFirstRun(AddOnCatalog.all, AddOnCatalog.all.associate { it.id to AddOnState.Installed(1) }).isEmpty())
  }

  @Test fun theSpeechPackIsNotOfferedUntilItHasABundle() {
    assertTrue(AddOnCatalog.speech.id !in AddOnOffer.idsAtFirstRun(AddOnCatalog.all, emptyMap()))
  }

  @Test fun settingsSpeechCardTellsTheTruthAndNeverSaysComingSoon() = runComposeUiTest {
    val installed = androidx.compose.runtime.mutableStateOf(true)
    setContent { SunlightTheme { trawl.desktop.ui.settings.BundledCard(AddOnCatalog.speech, installed.value) } }
    onNodeWithText("Speech").assertExists()
    onNodeWithText("Included").assertExists()
    onNodeWithText(t("addons_bundled_on")).assertExists()
    onAllNodesWithText("Coming soon").assertCountEquals(0)
    installed.value = false
    waitForIdle()
    onNodeWithText("Not added").assertExists()
    onNodeWithText(t("addons_bundled_off")).assertExists()
    onAllNodesWithText("Coming soon").assertCountEquals(0)
  }

  @Test fun theFirstRunPageShowsTheExtrasWithTheSameCardsAsSettings() = runComposeUiTest {
    val dir = Files.createTempDirectory("trawl-f4").toFile()
    val c = AppController(dir, Platform.UserFolders(File(dir, "h"), File(dir, "d"), File(dir, "dl")), logSink = {}, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
    try {
      setContent { SunlightTheme { FirstRunAddOns(c.addOns, c.privacy) } }
      if (trawl.desktop.privacy.PrivacyRuntime.usable()) {
        waitUntil(timeoutMillis = 5000) { onAllNodesWithTextCount("Optional extras") > 0 }
        onNodeWithText("Skip these for now. You can add any of them later in Settings.").assertExists()
        onNodeWithText(t("addon_privacy_name")).assertExists()
      } else {
        waitForIdle(); Thread.sleep(300); waitForIdle()
        onNodeWithText("Optional extras").assertDoesNotExist()
      }
    } finally { c.close() }
  }

  private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String) =
    onAllNodesWithText(text).fetchSemanticsNodes().size
}
