package trawl.desktop

import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.PackIds
import com.craneai.findr.engine.SignatureVerifier
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import trawl.desktop.privacy.PackChoices
import trawl.desktop.privacy.PrivacyRuntime
import trawl.desktop.shell.ControllerQuickSearch
import trawl.desktop.shell.QuickSearchApi

/**
 * Package B wired into the app: where the scorer cannot load the Privacy pack is never "added", and the lock and the sensitive marks
 * decide what the main list and the quick panel may show. Made-up files only.
 */
class PrivacyWiringTest {
  private fun tmp() = Files.createTempDirectory("trawl-privwire").toFile().canonicalFile

  private fun waitFor(what: String, ms: Long = 30_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  @AfterTest fun resetOverride() { PrivacyRuntime.override = null }

  // ---- the runtime check ------------------------------------------------------------------------------------------------

  @Test fun anIntelMacIsAPlainNoAndNothingElseIs() {
    assertTrue(PrivacyRuntime.isIntelMac("Mac OS X", "x86_64"))
    assertTrue(PrivacyRuntime.isIntelMac("Darwin", "amd64"))
    assertFalse(PrivacyRuntime.isIntelMac("Mac OS X", "aarch64"), "Apple silicon is supported")
    assertFalse(PrivacyRuntime.isIntelMac("Windows 11", "amd64"))
    assertFalse(PrivacyRuntime.isIntelMac("Linux", "x86_64"))
    // The real probe says no for an Intel Mac without trying to load anything, whatever this computer is.
    assertFalse(PrivacyRuntime.probe("Mac OS X", "x86_64"))
  }

  @Test fun theRealProbeLoadsTheScorerLibraryOnThisComputer() {
    // This build machine runs the test suite, so the slimmed library for its own system must load.
    assertTrue(PrivacyRuntime.probe(), "ONNX Runtime did not load: the slimmed jar lost this computer's native library")
  }

  @Test fun theOnnxRuntimeJarOnTheClasspathCarriesOnlyThisSystemsNatives() {
    val jar = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).first { it.name.startsWith("onnxruntime-1") }
    val natives = java.util.zip.ZipFile(jar).use { z -> z.entries().asSequence().map { it.name }.filter { it.startsWith("ai/onnxruntime/native/") && !it.endsWith("/") }.toList() }
    val folders = natives.map { it.removePrefix("ai/onnxruntime/native/").substringBefore('/') }.toSet()
    assertEquals(1, folders.size, "one system's natives, not every system's: $folders")
    assertTrue(natives.none { ".dSYM" in it }, "no macOS debug-symbol folders")
    assertTrue(jar.length() < 20_000_000, "the slimmed jar is about 6 to 13 MB, not the published 51 MB")
  }

  @Test fun theOverrideWinsOverTheProbe() {
    PrivacyRuntime.override = false
    assertFalse(PrivacyRuntime.usable())
    PrivacyRuntime.override = true
    assertTrue(PrivacyRuntime.usable())
  }

  // ---- an add-on that cannot run here --------------------------------------------------------------------------------

  private class Choices : PackChoices {
    val declined = HashSet<String>()
    override fun declined(id: String) = id in declined
    override fun setDeclined(id: String, declined: Boolean) { if (declined) this.declined += id else this.declined -= id }
  }

  @Test fun anUnsupportedPrivacyPackIsNeverInstalledNotEvenWithItsFilesInPlace() {
    val models = tmp(); val dl = tmp()
    val def = AddOnCatalog.privacy.copy(supported = { false })
    val files = def.files!!
    // Put the whole pack in place by hand: it must still not read as installed.
    val dir = File(models, files.dir)
    for (f in files.manifest) File(dir, f.path).apply { parentFile.mkdirs(); writeBytes(ByteArray(f.size.toInt())) }
    val mgr = AddOnManager(models, { listOf(dl) }, Choices(), AddOnHost(), executor = { it.run() }, catalog = listOf(def), verifier = SignatureVerifier(emptyList()))
    assertEquals(AddOnState.NotInstalled, mgr.states.value[PackIds.PRIVACY])
    mgr.install(PackIds.PRIVACY)
    val st = mgr.states.value[PackIds.PRIVACY]
    assertIs<AddOnState.Failed>(st)
    assertEquals(AddOnProblem.UNAVAILABLE, st.problem)
    mgr.refresh()
    assertFalse(mgr.states.value[PackIds.PRIVACY] is AddOnState.Installed)
    models.deleteRecursively(); dl.deleteRecursively()
  }

  @Test fun theCatalogsPrivacyPackAsksTheRuntime() {
    PrivacyRuntime.override = false
    assertFalse(AddOnCatalog.privacy.supported())
    PrivacyRuntime.override = true
    assertTrue(AddOnCatalog.privacy.supported())
  }

  // ---- the lock and the marks decide what is listed ---------------------------------------------------------------------

  @Test fun lockedShowsNothingMarkedFilesStayOutUntilShownAndTheQuickPanelNeverShowsThem() {
    Strings.use(Locale.ENGLISH)
    val root = tmp(); val data = tmp()
    File(root, "notes").mkdirs()
    File(root, "notes/recipe.txt").writeText("Slow roasted tomatoes with a spoonful of orange marmalade and thyme.")
    File(root, "notes/diary.txt").writeText("Private diary: the marmalade jar is where I hide the spare key.")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    AppController(data, Platform.UserFolders(tmp(), tmp(), tmp()), logSink = {}, scope = scope).use { c ->
      c.addFolder(root)
      waitFor("both files to be read") { c.service.search("marmalade", KindFilter.ALL).hits.size == 2 }
      val diary = c.service.search("marmalade", KindFilter.ALL).hits.first { it.file.name == "diary.txt" }.file

      assertEquals(1, c.markSensitive(listOf(diary.id), true))
      assertEquals(listOf("recipe.txt"), c.service.search("marmalade", KindFilter.ALL).hits.map { it.file.name }, "marked files stay out of search")
      assertEquals(listOf("recipe.txt"), c.service.recent(KindFilter.ALL).map { it.name }.filter { it.endsWith(".txt") }, "and out of the recent list")

      // Shown by the person (no lock, so no proof needed): the main list has it, flagged; the quick panel still does not.
      assertTrue(c.privacy.revealWithoutProof())
      val shown = c.service.search("marmalade", KindFilter.ALL).hits.map { it.file }
      assertEquals(setOf("recipe.txt", "diary.txt"), shown.map { it.name }.toSet())
      assertTrue(shown.first { it.name == "diary.txt" }.sensitive)
      val quick = ControllerQuickSearch(c)
      assertEquals(listOf("recipe.txt"), runBlocking { quick.search("marmalade").first() }.map { it.file.name }, "the panel has no way to show sensitive files")

      // Lock it: the panel and the main list answer with nothing, and the panel says it is locked.
      assertEquals(trawl.desktop.privacy.PrivacyController.ChangeResult.OK, c.privacy.enableLock("a long made-up passphrase".toCharArray(), useOsPrompt = false))
      c.privacy.onScreenOff()
      assertTrue(c.privacy.locked.value)
      assertTrue(quick.locked.value)
      assertEquals(emptyList(), runBlocking { quick.search("marmalade").first() })
      assertEquals(emptyList(), runBlocking { quick.search("").first() })
      c.setQuery("marmalade")
      waitFor("the main list to answer the locked query") { c.results.value.query == "marmalade" }
      assertTrue(c.results.value.rows.isEmpty(), "locked: nothing is looked up")
      // And the lock also puts the sensitive files away again.
      assertFalse(c.privacy.revealed.value)
    }
    scope.cancel()
    root.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aSearchWithNoPrivacyBehindItIsNeverLockedAndNeverBlurs() {
    val plain = object : QuickSearchApi {
      override fun search(query: String) = kotlinx.coroutines.flow.flowOf(emptyList<trawl.desktop.shell.QuickHit>())
      override fun open(file: com.craneai.findr.core.IndexedFile) {}
      override fun showInFolder(file: com.craneai.findr.core.IndexedFile) {}
    }
    assertFalse(plain.locked.value)
    assertFalse(plain.blurOn())
  }

  @Test fun everyNewPrivacyWordHasEnglish() {
    Strings.use(Locale.ENGLISH)
    for (k in listOf(
      "addons_status_unsupported", "addons_unsupported_hint", "privacy_blur_unsupported", "quick_locked", "quick_row_hidden",
      "settings_nav_privacy", "fact_privacy", "fact_privacy_marked", "fact_privacy_folder", "fact_privacy_looks",
      "settings_report_open", "settings_report_open_hint", "hidden_label", "hidden_tile", "sensitive_badge", "sensitive_mark", "sensitive_unmark",
    )) assertTrue(Strings.has(k), "missing string: $k")
  }
}
