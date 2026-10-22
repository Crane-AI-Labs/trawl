package com.craneai.findr.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.craneai.findr.BuildConfig
import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.DeliveryService
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.ModelUpdate
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.Refusal
import com.craneai.findr.core.RemovableFile
import com.craneai.findr.core.UpdateKind
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val TAG = "FindrDelivery"

/**
 * The newest model bundle this app release knows about, per phone class (`assets/models-known.json`; scripts/pack-bundle.sh reads
 * its version from the same file, so a bundle and the notice cannot drift). Pure JVM.
 */
data class KnownBundle(val version: Int, val sizeMb: Int, val kind: UpdateKind)

object ModelsKnown {
  fun parse(json: String): Map<BundleClass, KnownBundle> = try {
    val classes = JSONObject(json).getJSONObject("classes")
    BundleClass.entries.mapNotNull { c ->
      classes.optJSONObject(c.id)?.let { o -> c to KnownBundle(o.getInt("version"), o.optInt("size_mb", 0), UpdateKind.parse(o.optString("update_kind"))) }
    }.toMap()
  } catch (_: Exception) {
    emptyMap()
  }

  /**
   * The gentle "a better model is available" notice, or null. Shown only when a signed bundle of this phone's class is installed
   * and is older than the newest known one, and the person has not dismissed that version. A phone with models from loose
   * files (no record) or none at all gets the first-run card or nothing, never this.
   */
  fun update(installed: InstalledBundle?, phoneClass: BundleClass, known: Map<BundleClass, KnownBundle>, dismissedVersion: Int): ModelUpdate? {
    val latest = known[phoneClass] ?: return null
    if (installed == null || installed.bundleClass != phoneClass) return null
    if (installed.version >= latest.version || dismissedVersion >= latest.version) return null
    return ModelUpdate(latest.version, installed.version, latest.sizeMb, latest.kind)
  }
}

/** One entry of Downloads, as the scan sees it. */
data class DownloadEntry(val name: String, val length: Long, val modified: Long)

/** What the scan found: bundles that look finished, and a download still under way. Pure JVM. */
data class DownloadScan(val ready: List<DownloadEntry>, val partial: DownloadEntry?)

object DownloadsWatch {
  /** A finished bundle: `trawl-models.zip`, `trawl-models-lite.zip`, or either with a browser's " (1)". */
  val BUNDLE = Regex("""trawl-models.*\.zip""", RegexOption.IGNORE_CASE)

  /**
   * A download in progress. Browsers write it under another name until it is done: Chrome `Unconfirmed 123.crdownload`, Firefox
   * `name.part`, others `.download` or `.tmp`, and Android 11+ a hidden `.pending-<time>-name.zip` that MediaStore renames at the end.
   */
  val PARTIAL = Regex("""(?i)(.*\.(crdownload|part|download|tmp))|(\.pending-\d+-.*trawl-models.*\.zip)""")

  /**
   * [previous] is the last scan's (length, modified) by name. A file is ready only when it is unchanged since then: a browser
   * may give the final name before the last bytes are written, and a bundle read half-written would be refused as damaged.
   * [settledMs] counts a file whose modified time is already older than that as stable on a first look (one that was there
   * before the app opened), [now] being the clock.
   */
  fun scan(listing: List<DownloadEntry>, previous: Map<String, Pair<Long, Long>>, now: Long, settledMs: Long = 10_000): DownloadScan {
    val partial = listing.filter { PARTIAL.matches(it.name) && (it.name.contains("trawl-models", true) || it.name.endsWith(".crdownload", true)) }
      .maxByOrNull { it.modified }
    val ready = listing.filter { BUNDLE.matches(it.name) && !PARTIAL.matches(it.name) && it.length > 0 }.filter {
      val before = previous[it.name]
      if (before != null) before == (it.length to it.modified) else now - it.modified > settledMs
    }.sortedByDescending { it.modified }
    return DownloadScan(ready, partial)
  }
}

/**
 * Gets the models onto the phone (core/Delivery.kt). While a card that wants them is on screen it watches Downloads for the bundle
 * a browser saved, installs it through [SignedBundleInstaller] with progress, and offers once to delete the verified file. A file
 * the person picks (ACTION_OPEN_DOCUMENT) goes through the same installer. Findr has no INTERNET permission, so this never
 * fetches anything: the browser does.
 */
class ModelDelivery(
  private val context: Context,
  private val scope: CoroutineScope,
  /** The profile this phone's HARDWARE gets (not the one running now: a standard phone holding the core model runs the lite stack). */
  private val hardware: MemoryProfile,
  /** The models changed on disk: give the loaded model back, refresh the packs and the meter, and let the indexer start. */
  private val onInstalled: suspend () -> Unit,
) : DeliveryService {
  private val modelsDir = File(context.filesDir, "models")
  private val phoneClass = BundleClass.of(hardware)
  /** A standard phone also takes the lighter core bundle (its text-only model); a lite phone takes only its own class. Add-ons fit every phone. */
  private val acceptClasses: Set<BundleClass> =
    if (phoneClass == BundleClass.STANDARD) setOf(BundleClass.STANDARD, BundleClass.LITE) else setOf(phoneClass)
  private val prefs = context.getSharedPreferences("delivery", Context.MODE_PRIVATE)
  private val known: Map<BundleClass, KnownBundle> = runCatching { ModelsKnown.parse(context.assets.open("models-known.json").bufferedReader().readText()) }.getOrDefault(emptyMap())

  private val flow = MutableStateFlow(DeliveryState(sizeMb = known[phoneClass]?.sizeMb ?: 0))
  override val state: StateFlow<DeliveryState> = flow

  private val lock = Any()
  private var watchers = 0
  private var poller: Job? = null
  private var job: Job? = null
  @Volatile private var cancelled = false
  private var previous = emptyMap<String, Pair<Long, Long>>()
  /** name|length|modified of a file already tried: a refused file is not read again until it changes. */
  private val tried = HashSet<String>()
  private var pending: Pending? = null

  private class Pending(val file: File?, val uri: Uri?, val bytes: Long, val modified: Long, val key: String, val name: String)

  init { refreshUpdate() }

  /**
   * The first bundle for this phone. The lean-first policy (off: [LEAN_FIRST]) sends a standard phone with no model yet to the core
   * bundle (the lite file, 304 MB) instead of the whole 670 MB; its vision model comes later as [ModelBundle.VISION_ARCHIVE_NAME].
   */
  override fun downloadUrl(): String = BuildConfig.MODELS_BASE_URL + when {
    phoneClass == BundleClass.LITE -> ModelBundle.LITE_ARCHIVE_NAME
    LEAN_FIRST && InstalledBundle.read(modelsDir) == null -> ModelBundle.LITE_ARCHIVE_NAME
    else -> ModelBundle.ARCHIVE_NAME
  }

  // ---- watching Downloads ----------------------------------------------------------------------------------------

  override fun watch(on: Boolean) {
    synchronized(lock) {
      watchers = maxOf(0, watchers + if (on) 1 else -1)
      if (watchers > 0 && poller?.isActive != true) poller = scope.launch(Dispatchers.IO) { pollLoop() }
      if (watchers == 0) { poller?.cancel(); poller = null }
    }
  }

  private suspend fun pollLoop() {
    while (scope.isActive) {
      runCatching { pollOnce() }.onFailure { Log.w(TAG, "downloads scan failed", it) }
      delay(POLL_MS)
    }
  }

  /** One look at Downloads. Public for the engine's start-up check, which has no card on screen yet. */
  suspend fun pollOnce() {
    if (!Environment.isExternalStorageManager()) return
    if (job?.isActive == true) return
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val listing = dir.listFiles().orEmpty().filter { it.isFile }.map { DownloadEntry(it.name, it.length(), it.lastModified()) }
    val scan = DownloadsWatch.scan(listing, previous, System.currentTimeMillis())
    previous = listing.associate { it.name to (it.length to it.modified) }
    val next = scan.ready.firstOrNull { "${it.name}|${it.length}|${it.modified}" !in tried }
    val phase = flow.value.phase
    when {
      next != null -> {
        tried += "${next.name}|${next.length}|${next.modified}"
        installFile(File(dir, next.name))
      }
      scan.partial != null && phase in setOf(DeliveryPhase.IDLE, DeliveryPhase.DOWNLOADING) ->
        flow.value = flow.value.copy(phase = DeliveryPhase.DOWNLOADING, doneBytes = scan.partial.length, totalBytes = (known[phoneClass]?.sizeMb ?: 0) * 1_000_000L, refusal = null)
      scan.partial == null && phase == DeliveryPhase.DOWNLOADING -> flow.value = flow.value.copy(phase = DeliveryPhase.IDLE, doneBytes = 0)   // the download was cancelled
    }
  }

  // ---- installing ------------------------------------------------------------------------------------------------

  private fun installFile(file: File) {
    val rec = Pending(file, null, file.length(), file.lastModified(), "${file.name}|${file.length()}|${file.lastModified()}", file.name)
    start(rec) { file.inputStream() }
  }

  override fun installFrom(uri: String) {
    val u = Uri.parse(uri)
    val name = runCatching {
      context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
      }
    }.getOrNull()
    val rec = Pending(null, u, name?.second ?: 0L, 0L, "uri|$uri", name?.first ?: "models")
    start(rec) { context.contentResolver.openInputStream(u) ?: throw java.io.IOException("cannot open the chosen file") }
  }

  private fun start(rec: Pending, open: () -> InputStream) {
    synchronized(lock) {
      if (job?.isActive == true) return
      cancelled = false
      flow.value = flow.value.copy(phase = DeliveryPhase.INSTALLING, doneBytes = 0, totalBytes = 0, refusal = null, removable = null)
      job = scope.launch(Dispatchers.IO) {
        PackLocks.of(MODEL_LOCK).withLock {
          val result = try {
            var lastPublished = 0L
            installer().install(
              open(),
              progress = { done, total ->
                val now = System.nanoTime()
                if (now - lastPublished > 200_000_000L || done == total) {
                  lastPublished = now
                  flow.value = flow.value.copy(doneBytes = done, totalBytes = total)
                }
              },
              cancelled = { cancelled },
            )
          } catch (t: Throwable) {
            Log.w(TAG, "bundle install failed", t)
            com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.MODEL_INSTALL, t)
            BundleResult.Refused(Refusal.DAMAGED, "${t.javaClass.simpleName}: ${t.message}")
          }
          finish(result, rec)
        }
      }
    }
  }

  private suspend fun finish(result: BundleResult, rec: Pending) {
    when (result) {
      is BundleResult.Installed -> {
        Log.i(TAG, "installed ${result.manifest.bundle} v${result.manifest.version} (${result.installedBytes / 1_000_000} MB)")   // log-safe: our own bundle
        // An add-on is something the person asked for by downloading it: a pack they once declined is wanted now.
        if (result.manifest.role == BundleRole.ADDON) clearDeclined(result.manifest)
        pending = rec.takeIf { canRemove(it) }
        onInstalled()
        refreshUpdate()
        flow.value = flow.value.copy(phase = DeliveryPhase.DONE, refusal = null, removable = offer(rec), doneBytes = 0, totalBytes = 0)
      }
      is BundleResult.Refused -> {
        Log.w(TAG, "bundle refused: ${result.reason} (${result.detail})")   // log-safe: a reason code and our own numbers
        when (result.reason) {
          Refusal.CANCELLED -> flow.value = flow.value.copy(phase = DeliveryPhase.IDLE, doneBytes = 0, totalBytes = 0)
          Refusal.ALREADY_INSTALLED -> {
            // The file is a good copy of what is already here: say so, and still offer to free its space.
            pending = rec.takeIf { canRemove(it) }
            flow.value = flow.value.copy(phase = DeliveryPhase.DONE, refusal = Refusal.ALREADY_INSTALLED, removable = offer(rec), doneBytes = 0, totalBytes = 0)
          }
          else -> flow.value = flow.value.copy(phase = DeliveryPhase.REFUSED, refusal = result.reason, doneBytes = 0, totalBytes = 0)
        }
      }
    }
  }

  private fun clearDeclined(m: BundleManifest) {
    val folders = m.files.mapNotNull { f -> f.path.substringBefore('/', "").takeIf { it.isNotEmpty() } }.toSet()
    val choices = PackPrefs(context)
    listOf(PackCatalog.SPEECH, PackCatalog.QUICK_LOOK, PackCatalog.PRIVACY).forEach { id ->
      if (PackFilesRegistry.of(id)?.folderName in folders) choices.setDeclined(id, false)
    }
  }

  private fun installer(): SignedBundleInstaller {
    val packs = listOf(PackCatalog.SPEECH, PackCatalog.QUICK_LOOK, PackCatalog.PRIVACY).mapNotNull { id -> PackFilesRegistry.of(id)?.let { id to it } }
    val choices = PackPrefs(context)
    return SignedBundleInstaller(
      modelsDir = modelsDir,
      phoneClass = phoneClass,
      // Every model file any class carries (the lite list is the superset): the bundle's class decides which one it brings.
      modelNames = Embedder.modelNames(MemoryProfile.LITE),
      packDirs = packs.associate { (_, f) -> f.folderName to f.dir },
      skipFolders = packs.filter { (id, _) -> choices.declined(id) }.map { (_, f) -> f.folderName }.toSet(),
      acceptClasses = acceptClasses,
    )
  }

  override fun cancel() { cancelled = true }

  override fun acknowledge() {
    if (flow.value.phase == DeliveryPhase.INSTALLING) return
    tried.clear()   // the person looked at the problem: a file that was refused may be tried again (they freed space, fixed the download)
    flow.value = flow.value.copy(phase = DeliveryPhase.IDLE, refusal = null, doneBytes = 0, totalBytes = 0, removable = null)
  }

  // ---- freeing the space -----------------------------------------------------------------------------------------

  /** Only a file we can delete exactly, and only once per file: a Downloads file by path, a chosen document if its provider allows. */
  private fun canRemove(p: Pending): Boolean = when {
    p.file != null -> isInDownloads(p.file)
    p.uri != null -> runCatching {
      context.contentResolver.query(p.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
        c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
      }
    }.getOrNull() == true
    else -> false
  }

  private fun isInDownloads(f: File): Boolean =
    runCatching { f.canonicalFile.parentFile == Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile }.getOrDefault(false)

  /** The question, unless this very file was already answered. */
  private fun offer(rec: Pending): RemovableFile? =
    pending?.takeIf { it === rec && !prefs.getBoolean("removed_${it.key}", false) }?.let { RemovableFile(it.name, it.bytes) }

  override fun removeDownloaded() {
    val p = pending ?: return
    val freed = flow.value.removable?.bytes ?: p.bytes
    val ok = runCatching {
      when {
        // The exact verified file: still there, unchanged since it was verified, and in Downloads.
        p.file != null -> p.file.isFile && p.file.length() == p.bytes && p.file.lastModified() == p.modified && isInDownloads(p.file) && p.file.delete()
        p.uri != null -> DocumentsContract.deleteDocument(context.contentResolver, p.uri)
        else -> false
      }
    }.getOrDefault(false)
    Log.i(TAG, "removed the downloaded bundle: $ok (${freed / 1_000_000} MB)")   // log-safe: our own bundle
    prefs.edit().putBoolean("removed_${p.key}", true).apply()
    pending = null
    flow.value = flow.value.copy(removable = null)
  }

  override fun keepDownloaded() {
    pending?.let { prefs.edit().putBoolean("removed_${it.key}", true).apply() }
    pending = null
    flow.value = flow.value.copy(removable = null)
  }

  // ---- the update notice -----------------------------------------------------------------------------------------

  private fun refreshUpdate() {
    val u = ModelsKnown.update(InstalledBundle.read(modelsDir), phoneClass, known, prefs.getInt("update_dismissed", 0))
    flow.value = flow.value.copy(update = u)
  }

  override fun dismissUpdate() {
    flow.value.update?.let { prefs.edit().putInt("update_dismissed", it.latestVersion).apply() }
    refreshUpdate()
  }

  companion object {
    private const val POLL_MS = 1_500L
    internal const val MODEL_LOCK = "model"

    /** The lean-first policy (internal/research/lean-download.md): off until the owner decides; the mechanics above work either way. */
    internal const val LEAN_FIRST = false
  }
}
