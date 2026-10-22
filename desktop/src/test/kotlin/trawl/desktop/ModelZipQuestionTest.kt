package trawl.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import trawl.desktop.ui.ModelsPanel
import trawl.desktop.ui.SunlightTheme

/** F3: after a good model install the panel asks "delete the zip now?", and the zip is only ever deleted on a yes. */
@OptIn(ExperimentalTestApi::class)
class ModelZipQuestionTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private fun tmp() = Files.createTempDirectory("trawl-zipq").toFile().canonicalFile
  private fun zip(dir: File) = File(dir, "trawl-models.zip").also { it.writeBytes(ByteArray(3_000)) }
  private fun controller(data: File, downloads: File) =
    AppController(data, Platform.UserFolders(tmp(), tmp(), downloads), logSink = {}, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))

  private fun waitFor(what: String, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  @Test fun onlyAZipThatExistsAndIsNotTrawlsOwnIsOffered() {
    val data = tmp(); val dl = tmp(); val models = File(data, "models").also { it.mkdirs() }
    assertEquals(zip(dl).canonicalFile, ModelZipOffer.forZip(zip(dl), models)?.canonicalFile)
    assertNull(ModelZipOffer.forZip(File(dl, "gone.zip"), models), "already gone: nothing to ask")
    assertNull(ModelZipOffer.forZip(File(models, "kept.zip").also { it.writeText("x") }, models), "a file Trawl keeps itself is not the person's to delete")
  }

  @Test fun deletingSaysWhatWasFreedAndAFailureSaysSo() {
    val z = zip(tmp())
    val (msg, ok) = ModelZipOffer.delete(z)
    assertTrue(ok); assertFalse(z.exists()); assertTrue(msg.startsWith("Deleted trawl-models.zip and freed "), msg)
    val (bad, ok2) = ModelZipOffer.delete(File(tmp(), "dir").also { it.mkdirs(); File(it, "inner").writeText("x") })   // a non-empty folder cannot be deleted as a file
    assertFalse(ok2); assertTrue("could not delete" in bad, bad)
  }

  @Test fun aGoodInstallOffersTheZipAndKeepLeavesItAlone() {
    val dl = tmp(); val z = zip(dl)
    controller(tmp(), dl).use { c ->
      c.markInstalled(z)
      assertEquals(z.canonicalFile, c.model.value.zipOffer?.canonicalFile)
      c.keepModelZip()
      assertNull(c.model.value.zipOffer); assertTrue(z.exists(), "Keep it keeps it")
    }
  }

  @Test fun deleteItRemovesTheZipAndTheLineSaysSo() {
    val dl = tmp(); val z = zip(dl)
    controller(tmp(), dl).use { c ->
      c.markInstalled(z)
      c.deleteModelZip()
      waitFor("the zip to be deleted") { !z.exists() }
      waitFor("the answer line") { c.model.value.message?.startsWith("Deleted trawl-models.zip") == true }
      assertNull(c.model.value.zipOffer); assertFalse(c.model.value.failed)
    }
  }

  @Test fun theQuestionShowsInThePanelWithBothAnswers() = runComposeUiTest {
    val dl = tmp(); val z = zip(dl)
    val c = controller(tmp(), dl)
    try {
      c.markInstalled(z)
      setContent { SunlightTheme { val m by c.model.collectAsState(); ModelsPanel(c, m) } }
      onNodeWithText("Delete it").assertExists(); onNodeWithText("Keep it").assertExists()
      onNodeWithText("Keep it").performClick()
      waitForIdle()
      onNodeWithText("Delete it").assertDoesNotExist()
      assertTrue(z.exists())
    } finally { c.close() }
  }
}
