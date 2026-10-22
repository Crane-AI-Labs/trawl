package trawl.desktop

import com.craneai.findr.core.Refusal
import com.craneai.findr.engine.MemoryProfile
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Settings models panel follows the Downloads folder while it is open (a bundle added or taken away shows without leaving and
 * re-opening Settings, and a refusal about a file that is gone goes with it), and a refusal says which bundle to fetch.
 */
class ModelPanelTest {
  private fun tmp() = Files.createTempDirectory("trawl-panel").toFile().canonicalFile

  private fun waitFor(what: String, ms: Long = 15_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(100) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun unsignedBundle(dir: File): File = File(dir, "trawl-models.zip").also { f ->
    ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("model.litertlm")); z.write("not a model".toByteArray()); z.closeEntry() }
  }

  @Test fun theSmallPhoneBundleIsRefusedOnAComputerInPlainWordsAndTheStandardOneOnASmallComputer() {
    Strings.use(java.util.Locale.ENGLISH)
    val onComputer = Strings.get(ModelSetup.refusalKey(Refusal.WRONG_CLASS, MemoryProfile.STANDARD))
    assertTrue("small phone bundle" in onComputer && "trawl-models.zip" in onComputer, onComputer)
    assertFalse("different kind of device" in onComputer, "the old wording is gone")
    val onSmall = Strings.get(ModelSetup.refusalKey(Refusal.WRONG_CLASS, MemoryProfile.LITE))
    assertNotEquals(onComputer, onSmall, "a small computer is told the opposite")
    // Nothing else changes with the profile, and every refusal still has words.
    for (r in Refusal.entries) for (p in MemoryProfile.entries) assertTrue(Strings.has(ModelSetup.refusalKey(r, p)), "$r on $p")
    assertEquals("refusal_not_signed", ModelSetup.refusalKey(Refusal.NOT_SIGNED, MemoryProfile.LITE))
  }

  @Test fun aBundleAddedOrRemovedWhileSettingsIsOpenShowsAndAStaleRefusalClears() {
    val data = tmp(); val downloads = tmp()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    AppController(data, Platform.UserFolders(tmp(), tmp(), downloads), logSink = {}, scope = scope).use { c ->
      c.openSettings()
      assertNull(c.model.value.found, "nothing in Downloads yet")

      val zip = unsignedBundle(downloads)
      waitFor("the new bundle to be noticed with Settings still open") { c.model.value.found?.name == "trawl-models.zip" }

      c.installModels(zip)
      waitFor("the refusal") { c.model.value.failed && !c.model.value.installing }
      assertNotNull(c.model.value.message)

      // The refused file is taken away: its refusal is stale, and the panel clears it by itself.
      assertTrue(zip.delete())
      waitFor("the old refusal to clear") { c.model.value.message == null && !c.model.value.failed }
      assertNull(c.model.value.found)
    }
    scope.cancel()
    data.deleteRecursively(); downloads.deleteRecursively()
  }
}
