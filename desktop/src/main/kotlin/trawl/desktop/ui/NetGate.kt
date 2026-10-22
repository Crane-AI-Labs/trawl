package trawl.desktop.ui

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.craneai.findr.core.KindFilter
import org.json.JSONArray
import org.json.JSONObject
import trawl.desktop.AppController
import trawl.desktop.Phase
import com.craneai.findr.core.PackIds
import trawl.desktop.shell.ControllerQuickSearch
import trawl.desktop.shell.Shell

/**
 * The scripted session behind the zero-network release gate (`desktop/scripts/net-gate.py`), switched on only by `TRAWL_NETGATE_DIR`.
 * It drives the controller the way the buttons do (it never clicks, never moves the pointer) through everything that loads a native library or
 * could plausibly reach for the network: first run, the models and the Privacy pack installed from signed files, indexing a made-up folder
 * (photos, screenshots, documents, video, audio), searches of every kind, the privacy-blur scoring pass, the quick panel, Settings, Licences
 * and the diagnostics report. It then keeps searching and rescanning until `TRAWL_NETGATE_MINUTES` (default 10) have passed since the app
 * started, so the watcher outside the process has a long window, and writes `netgate-result.json` in the data folder before it quits.
 *
 * Environment: `TRAWL_NETGATE_DIR` the made-up folder to index; `TRAWL_NETGATE_MODELS` the signed model bundle (.zip);
 * `TRAWL_NETGATE_PRIVACY` the signed Privacy pack (.zip); `TRAWL_NETGATE_QUERIES` queries separated by `|`.
 * Nothing here opens a connection, and a person's run never sets the variable.
 */
class NetGate(private val c: AppController, private val shell: Shell, private val dir: File, private val dataDir: File, private val log: (String) -> Unit = { System.err.println("[netgate] $it") }) {
  private val started = System.currentTimeMillis()
  private val minutes = System.getenv("TRAWL_NETGATE_MINUTES")?.toDoubleOrNull() ?: 10.0
  private val queries = (System.getenv("TRAWL_NETGATE_QUERIES")
    ?: "tamarind|wheelbarrow|a dog on a bench|receipt total|feed the chickens|school fees|sunset over water|invoice last week|nursery seedlings")
    .split("|").map { it.trim() }.filter { it.isNotEmpty() }
  private val steps = JSONArray()
  private val facts = JSONObject()

  private fun secs() = (System.currentTimeMillis() - started) / 1000.0

  private fun step(name: String, ok: Boolean = true, detail: String = "") {
    steps.put(JSONObject().put("step", name).put("ok", ok).put("at_s", secs()).put("detail", detail))
    log("step $name ok=$ok at=%.1fs $detail".format(secs()))
  }

  private suspend fun <T> within(minutes: Long, block: suspend () -> T): T? = withTimeoutOrNull(minutes * 60_000L) { block() }

  /** Searches [q] through the window's own path and notes how many files came back. */
  private suspend fun search(q: String, filter: KindFilter = KindFilter.ALL): Int {
    c.setFilter(filter)
    c.setQuery("")
    delay(150)
    c.setQuery(q)
    val r = within(1) { c.results.first { it.query == q && !it.browse } }
    return r?.rows?.size ?: -1
  }

  suspend fun run() {
    // 0. Made-up files only: the JVM's user.home must be the scratch folder, or the first-run defaults would index the real person's folders.
    val want = System.getenv("TRAWL_NETGATE_HOME")?.let { File(it).canonicalPath }
    val have = File(System.getProperty("user.home") ?: "").canonicalPath
    val isolated = want != null && want == have
    facts.put("home_isolated", isolated)
    step("home-isolated", isolated, if (isolated) "user.home is the scratch folder" else "user.home is not the scratch folder")
    if (!isolated) {
      File(dataDir, "netgate-result.json").writeText(JSONObject().put("facts", facts).put("steps", steps).put("complete", false).toString(2))
      return
    }

    // 1. First run: the language page, then the folder choice. Nothing is chosen for the person; the script answers it.
    delay(3_000)
    LanguagePage.pending = false
    delay(2_000)
    step("first-run-shown", detail = "screen=${c.screen.value}")

    // 2. The models, from the signed bundle the same way the "Install the models" button does it.
    val models = System.getenv("TRAWL_NETGATE_MODELS")?.let { File(it) }?.takeIf { it.isFile }
    if (models != null) {
      c.installModels(models)
      val ok = within(15) { c.model.first { !it.installing && it.installed != null } } != null
      step("models-installed", ok, "installed=${c.model.value.installed}")
    } else step("models-installed", false, "TRAWL_NETGATE_MODELS missing")

    // 3. The Privacy pack (the ONNX Runtime scorer), from its signed zip.
    val pack = System.getenv("TRAWL_NETGATE_PRIVACY")?.let { File(it) }?.takeIf { it.isFile }
    if (pack != null) {
      c.addOns.install(PackIds.PRIVACY, pack)
      // Ends at once on a refusal too (it used to wait the full 10 minutes when the pack could not be added).
      val end = within(10) { c.addOns.states.first { it[PackIds.PRIVACY].let { s -> s is trawl.desktop.AddOnState.Installed || s is trawl.desktop.AddOnState.Failed } } }?.get(PackIds.PRIVACY)
      val ok = end is trawl.desktop.AddOnState.Installed
      step("privacy-pack-installed", ok, "ready=${c.privacyPackReady()} state=${end?.javaClass?.simpleName ?: "timeout"}${(end as? trawl.desktop.AddOnState.Failed)?.let { " problem=${it.problem}" } ?: ""} vcruntime=${trawl.desktop.privacy.WindowsVcRuntime.lastResult}")
    } else step("privacy-pack-installed", false, "TRAWL_NETGATE_PRIVACY missing")

    // 4. Folder choice and the start of indexing. Every kind is switched on so every reader (and its native library) runs.
    c.addChoice(dir)
    c.setIndexPhotos(true); c.setIndexScreenshots(true); c.setIndexDocuments(true); c.setIndexVideos(true); c.setIndexAudio(true)
    c.start()
    step("indexing-started", detail = "folder=${dir.name}")

    // 5. Indexing to the end (words and meaning), then the privacy pass has pictures to score.
    val done = within(25) { c.index.first { it.modelReady && it.phase == Phase.UP_TO_DATE && it.total > 0 && it.understood >= it.total } } != null
    val u = c.index.value
    facts.put("files", u.total).put("understood", u.understood).put("unreadable", u.unreadable)
    step("indexing-done", done, "files=${u.total} understood=${u.understood} unreadable=${u.unreadable}")

    // 6. The blur's scoring pass: wait for the app's own log line (it runs on its own thread once the pack and the pictures are there).
    val scored = within(8) {
      var n: Int? = null
      while (n == null) { n = scoredPictures(); if (n == null) delay(2_000) }
      n
    }
    facts.put("privacy_pass_scored", scored ?: -1)
    step("privacy-pass", scored != null && scored > 0, "scored=${scored ?: "none"}")

    // 7. Searches: words, meaning, a filter each, the quick panel's own search and the panel window itself.
    var asked = 0
    var answered = 0
    suspend fun round() {
      for (q in queries) { asked++; if (search(q) >= 0) answered++ }
      for (f in listOf(KindFilter.PHOTOS, KindFilter.DOCUMENTS, KindFilter.VIDEOS, KindFilter.AUDIO, KindFilter.SCREENSHOTS)) { asked++; if (search(queries.first(), f) >= 0) answered++ }
      c.setFilter(KindFilter.ALL); c.setQuery("")
      val quick = ControllerQuickSearch(c)
      for (q in listOf("", "tamarind", "dog bench", "feed the chickens")) { asked++; if (within(1) { quick.search(q).first() } != null) answered++ }
    }
    round()
    facts.put("searches_asked", asked).put("searches_answered", answered)
    step("searches", answered == asked, "$answered of $asked answered")

    shell.showPanel(); delay(2_500); shell.hidePanel(); delay(500)
    step("quick-panel", detail = "shown and hidden")

    // The share helper is a child process of its own (macOS and Windows). Its no-window self-check loads the same machinery the sheet uses;
    // the sheet itself needs a person, so the gate never opens it.
    val shareLine = shareSelfCheck()
    facts.put("share_selfcheck", shareLine ?: "n/a")
    step("share-helper-selfcheck", shareLine == null || shareLine.startsWith("ok"), shareLine ?: "no share helper on this system")

    // The Quick Look helper (macOS) likewise: its self-check loads the QuickLookUI framework and shows nothing.
    val qlLine = quickLookSelfCheck()
    facts.put("quicklook_selfcheck", qlLine ?: "n/a")
    step("quicklook-helper-selfcheck", qlLine == null || qlLine.startsWith("ok"), qlLine ?: "no Quick Look helper on this system")

    c.openSettings(); delay(1_500)
    c.openLicences(); delay(1_000)
    c.closeLicences(); c.closeSettings(); delay(500)
    val report = c.diagnostics()
    facts.put("diagnostics_has_runtime", report.lines().any { it.startsWith("Java runtime: ") })
    facts.put("java_runtime", System.getProperty("java.runtime.version") ?: "")
    facts.put("java_home", System.getProperty("java.home") ?: "")
    step("settings-licences-diagnostics")

    // 8. Keep working until the window the watcher needs has passed: rescans, searches, the panel again.
    var cycles = 0
    while (secs() < minutes * 60) {
      c.rescan()
      round()
      shell.showPanel(); delay(1_500); shell.hidePanel()
      cycles++
      delay(20_000)
    }
    facts.put("cycles", cycles).put("ran_s", secs())
    step("held-open", detail = "cycles=$cycles for %.0fs".format(secs()))

    File(dataDir, "netgate-result.json").writeText(JSONObject().put("facts", facts).put("steps", steps).put("complete", true).toString(2))
    log("result written")
  }

  /** Runs the share helper's own self-check (no window), or returns null where the system has no share helper. */
  private fun shareSelfCheck(): String? {
    val helpers = File(dataDir, "helpers")
    val sample = dir.walkTopDown().firstOrNull { it.isFile } ?: return "error:no sample file"
    return runCatching {
      when (trawl.desktop.Platform.os) {
        trawl.desktop.Os.MAC -> trawl.desktop.privacy.HelperRunner.Process.run(
          listOf(trawl.desktop.Helpers.extract(trawl.desktop.Share.MAC_HELPER, helpers).also { it.setExecutable(true) }.absolutePath, "--self-check"), 60_000L)
        trawl.desktop.Os.WINDOWS -> trawl.desktop.privacy.HelperRunner.Process.run(
          listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            trawl.desktop.Helpers.extract(trawl.desktop.Share.WINDOWS_HELPER, helpers).absolutePath, "selfcheck", sample.absolutePath), 120_000L)
        trawl.desktop.Os.LINUX -> null
      }
    }.getOrElse { "error:${it.message}" }
  }

  /** Runs the Quick Look helper's own self-check (no window), or returns null where the system has none (anything but a Mac build that includes it). */
  private fun quickLookSelfCheck(): String? {
    if (trawl.desktop.Platform.os != trawl.desktop.Os.MAC) return null
    if (trawl.desktop.shell.QuickLookMac::class.java.classLoader.getResource(trawl.desktop.shell.QuickLookMac.HELPER) == null) return null
    return runCatching {
      trawl.desktop.privacy.HelperRunner.Process.run(
        listOf(trawl.desktop.Helpers.extract(trawl.desktop.shell.QuickLookMac.HELPER, File(dataDir, "helpers")).also { it.setExecutable(true) }.absolutePath, "--self-check"), 60_000L)
    }.getOrElse { "error:${it.message}" }
  }

  /** The number from the app's "privacy pass: scored N pictures" line in its own log, or null while it has not run. */
  private fun scoredPictures(): Int? {
    val f = File(dataDir, "logs/trawl.log")
    if (!f.isFile) return null
    return runCatching { f.readLines().lastOrNull { "privacy pass: scored" in it }?.substringAfter("scored ")?.substringBefore(" ")?.toIntOrNull() }.getOrNull()
  }
}
