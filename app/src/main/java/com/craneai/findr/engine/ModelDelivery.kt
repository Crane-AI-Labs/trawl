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
import com.craneai.findr.core.ChoiceKeyValue
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.core.ModelChoiceStore
import com.craneai.findr.core.ModelPlan
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

/** What the last look saw of one file: its size and modified time, and since when (clock ms) it has been exactly that. */
data class Seen(val length: Long, val modified: Long, val since: Long)

/**
 * What the scan found: bundles that look finished ([ready]: unchanged long enough), a download under way in a browser's temporary
 * name ([partial]), and a bundle under its final name that is still changing or has only just stopped ([settling]). Pure JVM.
 */
data class DownloadScan(val ready: List<DownloadEntry>, val partial: DownloadEntry?, val settling: DownloadEntry? = null)

/** What to do about Downloads after one look. */
sealed interface DownloadStep {
  /** Nothing of ours there. */
  data object Nothing : DownloadStep

  /** A bundle that is still, and whole at its end: check and install it. */
  data class Install(val entry: DownloadEntry) : DownloadStep

  /** A download is still coming (or has gone quiet for a moment): show the bytes so far, keep watching. Never an error. */
  data class Downloading(val bytes: Long) : DownloadStep

  /** A bundle with no end that has not changed for [DownloadsWatch.STALLED_MS]: the download stopped. Say so once. */
  data class Stopped(val entry: DownloadEntry) : DownloadStep
}

object DownloadsWatch {
  /** A finished bundle: `trawl-models.zip`, `trawl-models-lite.zip`, or either with a browser's " (1)". */
  val BUNDLE = Regex("""trawl-models.*\.zip""", RegexOption.IGNORE_CASE)

  /**
   * A download in progress. Browsers write it under another name until it is done: Chrome `Unconfirmed 123.crdownload`, Firefox
   * `name.part`, Opera `.opdownload`, others `.download` or `.tmp`, and Android 10+ a hidden `.pending-<time>-name.zip` that
   * MediaStore renames at the end. Not every browser does this (Samsung Internet and Phoenix document nothing), so a name never
   * decides alone that a file is finished: [ZipTail] and the file holding still do (internal/research/model-download-incomplete.md).
   */
  val PARTIAL = Regex("""(?i)(.*\.(crdownload|opdownload|part|download|tmp))|(\.pending-\d+-.*trawl-models.*\.zip)""")

  /**
   * A file under its final name counts as still only after it has held the same size and modified time for this long. 15 s: a
   * mobile connection stalls for several seconds on a tower hand-over or a weak spot, and the old rule (two looks 1.5 s apart)
   * took such a pause for the end. A browser that renames at the end never feels the wait; for the others the card says
   * "Downloading" meanwhile. The zip's end record ([ZipTail]) is the real proof for a file written in order; this window covers
   * a file written out of order.
   */
  const val STABLE_MS = 15_000L

  /** A file already there, untouched this long, is still on the very first look (one that was there before the app opened). */
  const val SETTLED_MS = 10_000L

  /** No end record and not a byte of change for this long: the download has stopped (hung or abandoned), not paused. */
  const val STALLED_MS = 120_000L

  /**
   * [previous] is the last look's [Seen] by name (see [look]). A file is ready only when it has held the same length and modified
   * time for [stableMs], or, on a first look, was last modified more than [settledMs] ago; [now] is the clock. A file under its
   * final name that is not ready yet comes back as [DownloadScan.settling].
   */
  fun scan(
    listing: List<DownloadEntry>,
    previous: Map<String, Seen>,
    now: Long,
    stableMs: Long = STABLE_MS,
    settledMs: Long = SETTLED_MS,
  ): DownloadScan {
    val partial = listing.filter { PARTIAL.matches(it.name) && (it.name.contains("trawl-models", true) || it.name.endsWith(".crdownload", true) || it.name.endsWith(".opdownload", true)) }
      .maxByOrNull { it.modified }
    val finals = listing.filter { BUNDLE.matches(it.name) && !PARTIAL.matches(it.name) && it.length > 0 }
    val (ready, waiting) = finals.partition {
      val before = previous[it.name]
      if (before != null) before.length == it.length && before.modified == it.modified && now - before.since >= stableMs else now - it.modified > settledMs
    }
    return DownloadScan(ready.sortedByDescending { it.modified }, partial, waiting.maxByOrNull { it.modified })
  }

  /** The next look's [previous]: each file with the time it has held its present length and modified time. */
  fun look(listing: List<DownloadEntry>, previous: Map<String, Seen>, now: Long): Map<String, Seen> =
    listing.associate { e ->
      val before = previous[e.name]
      e.name to if (before != null && before.length == e.length && before.modified == e.modified) before else Seen(e.length, e.modified, now)
    }

  fun key(e: DownloadEntry) = "${e.name}|${e.length}|${e.modified}"

  /**
   * What to do after one look. [skip] holds the keys (name|length|modified) already tried or already reported: a changed file has
   * a new key and is looked at again. [hasEnd] is the zip end-record check ([ZipTail.hasEnd]): it is asked only of a file that has
   * held still, and only a file that has one is installed.
   */
  fun step(listing: List<DownloadEntry>, previous: Map<String, Seen>, now: Long, skip: Set<String>, hasEnd: (DownloadEntry) -> Boolean): DownloadStep {
    val scan = scan(listing, previous, now)
    val candidates = scan.ready.filter { key(it) !in skip }
    candidates.firstOrNull(hasEnd)?.let { return DownloadStep.Install(it) }
    // Still, but no end record: a stalled connection, until it has been silent so long that the download has stopped.
    candidates.firstOrNull()?.let { c ->
      val quietSince = previous[c.name]?.takeIf { it.length == c.length && it.modified == c.modified }?.since ?: c.modified
      return if (now - quietSince >= STALLED_MS) DownloadStep.Stopped(c) else DownloadStep.Downloading(c.length)
    }
    scan.settling?.takeIf { key(it) !in skip }?.let { return DownloadStep.Downloading(it.length) }
    scan.partial?.let { return DownloadStep.Downloading(it.length) }
    return DownloadStep.Nothing
  }

  /**
   * A file that failed a size or SHA check may still have been written to: it is not the same file now as when the install began.
   * Then the failure is the download not being done, not damage. A file that is gone is not still being written.
   */
  fun changedSince(startedLength: Long, startedModified: Long, nowLength: Long, nowModified: Long, exists: Boolean = true): Boolean =
    exists && (nowLength != startedLength || nowModified != startedModified)
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
  private val acceptClasses: Set<BundleClass> = ModelPlan.acceptClasses(phoneClass)
  private val prefs = context.getSharedPreferences("delivery", Context.MODE_PRIVATE)
  private val choiceStore = ModelChoiceStore(object : ChoiceKeyValue {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
  }, phoneClass)
  private val known: Map<BundleClass, KnownBundle> = runCatching { ModelsKnown.parse(context.assets.open("models-known.json").bufferedReader().readText()) }.getOrDefault(emptyMap())

  private val flow = MutableStateFlow(choiceState(DeliveryState()))
  override val state: StateFlow<DeliveryState> = flow

  private val lock = Any()
  private var watchers = 0
  private var poller: Job? = null
  private var job: Job? = null
  @Volatile private var cancelled = false
  private var previous = emptyMap<String, Seen>()
  /** name|length|modified of a file already tried: a refused file is not read again until it changes. */
  private val tried = HashSet<String>()
  /**
   * name|length|modified of a download already reported as stopped. Kept when the person taps Get the models again (unlike [tried]):
   * the stopped file is still there, and it must not be reported again over the new download. A changed file has a new key.
   */
  private val stopped = HashSet<String>()
  private var pending: Pending? = null

  private class Pending(val file: File?, val uri: Uri?, val bytes: Long, val modified: Long, val key: String, val name: String)

  init { refreshUpdate() }

  /**
   * The pack in force: a 2 GB phone is always Save data; otherwise the person's answer, then what is installed (a small pack
   * from the website counts as Save data), then Standard. The lean-first policy (off: [LEAN_FIRST]) would make the small
   * core bundle the default for a phone with no model and no answer yet; its vision model comes later as [ModelBundle.VISION_ARCHIVE_NAME].
   */
  private fun currentChoice(): ModelChoice {
    val installed = InstalledBundle.read(modelsDir)
    if (LEAN_FIRST && choiceStore.stored() == null && installed == null) return ModelPlan.effective(phoneClass, ModelChoice.SAVE_DATA, null)
    return choiceStore.effective(installed?.bundleClass)
  }

  private fun sizeOf(choice: ModelChoice): Int = known[ModelPlan.bundleClass(phoneClass, choice)]?.sizeMb ?: 0

  /** [s] with the pack-choice fields brought up to date (the sizes come from the same file as the update notice). */
  private fun choiceState(s: DeliveryState): DeliveryState {
    val choice = currentChoice()
    return s.copy(
      choice = choice,
      choiceOffered = ModelPlan.offered(phoneClass),
      standardMb = known[BundleClass.STANDARD]?.sizeMb ?: 0,
      saveDataMb = known[BundleClass.LITE]?.sizeMb ?: 0,
      installedChoice = ModelPlan.choiceOf(InstalledBundle.read(modelsDir)?.bundleClass),
      sizeMb = sizeOf(choice),
    )
  }

  override fun downloadUrl(): String = BuildConfig.MODELS_BASE_URL + ModelPlan.archiveName(phoneClass, currentChoice())

  override fun setChoice(choice: ModelChoice) {
    choiceStore.set(choice)
    flow.value = choiceState(flow.value)
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
    val now = System.currentTimeMillis()
    // Never install an unfinished file: one that has held still must also END like a zip (a browser that stalls on a file it already
    // named holds still without being done). A preallocated one that ends like a zip is caught by the installer's checks below (see finish).
    val step = DownloadsWatch.step(listing, previous, now, tried + stopped) { ZipTail.hasEnd(File(dir, it.name)) }
    previous = DownloadsWatch.look(listing, previous, now)
    val phase = flow.value.phase
    val waiting = phase == DeliveryPhase.IDLE || phase == DeliveryPhase.DOWNLOADING ||
      (phase == DeliveryPhase.REFUSED && flow.value.refusal == Refusal.INCOMPLETE)   // a stopped download that starts moving again
    when (step) {
      is DownloadStep.Install -> {
        tried += DownloadsWatch.key(step.entry)
        installFile(File(dir, step.entry.name))
      }
      is DownloadStep.Downloading -> if (waiting) flow.value = flow.value.copy(phase = DeliveryPhase.DOWNLOADING, doneBytes = step.bytes, totalBytes = flow.value.sizeMb * 1_000_000L, refusal = null)
      is DownloadStep.Stopped -> if (phase == DeliveryPhase.IDLE || phase == DeliveryPhase.DOWNLOADING) {
        stopped += DownloadsWatch.key(step.entry)
        Log.w(TAG, "download stopped at ${step.entry.length / 1_000_000} MB")   // log-safe: our own bundle
        flow.value = flow.value.copy(phase = DeliveryPhase.REFUSED, refusal = Refusal.INCOMPLETE, doneBytes = step.entry.length, totalBytes = flow.value.sizeMb * 1_000_000L)
      }
      DownloadStep.Nothing -> if (phase == DeliveryPhase.DOWNLOADING) flow.value = flow.value.copy(phase = DeliveryPhase.IDLE, doneBytes = 0)   // the download was cancelled
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
    start(rec) {
      // A chosen file that does not end like a zip is a download that stopped: say so, not "damaged". Only where the descriptor can seek;
      // a provider that streams cannot be asked, and the installer's own read will then hit the end too soon (the same refusal).
      val whole = runCatching {
        context.contentResolver.openFileDescriptor(u, "r")?.use { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { ZipTail.hasEnd(it.channel) } }
      }.getOrNull()
      if (whole == false) throw java.io.EOFException("the chosen file has no zip end")
      context.contentResolver.openInputStream(u) ?: throw java.io.FileNotFoundException("cannot open the chosen file")
    }
  }

  private fun start(rec: Pending, open: () -> InputStream) {
    synchronized(lock) {
      if (job?.isActive == true) return
      cancelled = false
      val before = InstalledBundle.read(modelsDir)?.bundleClass
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
            // A full disk, a file that ran out, a file that cannot be opened: each says what it is, not "damaged" for all of them.
            BundleResult.Refused(FailureKind.of(t), "${t.javaClass.simpleName}: ${t.message}")
          }
          finish(result, rec, before)
        }
      }
    }
  }

  private suspend fun finish(result: BundleResult, rec: Pending, before: BundleClass?) {
    when (result) {
      is BundleResult.Installed -> {
        Log.i(TAG, "installed ${result.manifest.bundle} v${result.manifest.version} (${result.installedBytes / 1_000_000} MB)")   // log-safe: our own bundle
        // An add-on is something the person asked for by downloading it: a pack they once declined is wanted now.
        if (result.manifest.role == BundleRole.ADDON) clearDeclined(result.manifest)
        pending = rec.takeIf { canRemove(it) }
        // What is installed is now what the person has: remember it as the answer, so the pack on the phone and the choice agree.
        val full = result.manifest.role == BundleRole.FULL
        val upgraded = full && ModelPlan.upgradesStack(before, result.manifest.bundleClass)
        if (full) choiceStore.recordInstalled(result.manifest.bundleClass)
        onInstalled()
        refreshUpdate()
        flow.value = choiceState(flow.value).copy(
          phase = DeliveryPhase.DONE, refusal = null, removable = offer(rec), doneBytes = 0, totalBytes = 0,
          upgradedToStandard = flow.value.upgradedToStandard || upgraded,
        )
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
          else -> {
            val file = rec.file
            val moved = file != null && DownloadsWatch.changedSince(rec.bytes, rec.modified, file.length(), file.lastModified(), file.exists())
            val total = flow.value.sizeMb * 1_000_000L
            flow.value = when {
              // It failed a size or SHA check, or ran out, but it is not the file the install began with: the download is still going.
              // Not an error. Its new length and modified time make a new key, so the scan installs it when it is done.
              moved && (result.reason == Refusal.DAMAGED || result.reason == Refusal.INCOMPLETE) ->
                flow.value.copy(phase = DeliveryPhase.DOWNLOADING, refusal = null, doneBytes = file.length(), totalBytes = total)
              // Ran out and still the same: the download stopped. Keep how far it got, for the message.
              result.reason == Refusal.INCOMPLETE -> flow.value.copy(phase = DeliveryPhase.REFUSED, refusal = result.reason, doneBytes = rec.bytes, totalBytes = total)
              else -> flow.value.copy(phase = DeliveryPhase.REFUSED, refusal = result.reason, doneBytes = 0, totalBytes = 0)
            }
          }
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
    // A small pack on a standard phone is compared with the small pack's newest version (the class it was installed as).
    val installed = InstalledBundle.read(modelsDir)
    val u = ModelsKnown.update(installed, installed?.bundleClass ?: phoneClass, known, prefs.getInt("update_dismissed", 0))
    flow.value = choiceState(flow.value).copy(update = u)
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
