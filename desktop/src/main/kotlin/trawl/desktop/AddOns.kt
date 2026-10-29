package trawl.desktop

import com.craneai.findr.core.PackIds
import com.craneai.findr.core.Refusal
import com.craneai.findr.engine.AsrModelFile
import com.craneai.findr.engine.AsrModelInstaller
import com.craneai.findr.engine.AsrModelSource
import com.craneai.findr.engine.BundleClass
import com.craneai.findr.engine.BundleManifest
import com.craneai.findr.engine.BundleResult
import com.craneai.findr.engine.BundleRole
import com.craneai.findr.engine.FolderModelSource
import com.craneai.findr.engine.InstalledAddons
import com.craneai.findr.engine.PrivatePack
import com.craneai.findr.engine.SignatureVerifier
import com.craneai.findr.engine.SignedBundleInstaller
import com.craneai.findr.engine.ZipModelSource
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import trawl.desktop.privacy.PackChoices

/**
 * Add-ons: optional model packs Trawl works better with, each a one-time file the person adds because Trawl never goes online. The
 * desktop's list is the phone's list ([PackIds]) cut down to what applies to a computer:
 *  - Privacy pack: yes (the same model, run with ONNX Runtime on the JVM).
 *  - Speech: runs today, but it arrives inside the Trawl models file (the `asr` folder), not as an add-on of its own, so its card only
 *    says whether it is there ([AddOnDef.bundled]); it used to say "Coming soon" while it was already reading videos.
 *  - Quick look: left out. It exists so a phone's slow CPU can read a photo in a fraction of EmbeddingGemma's time; a computer does not need it.
 */
data class AddOnDef(
  val id: String,
  /** `strings.properties` keys for the title, what it does and what it costs. */
  val nameKey: String,
  val benefitKey: String,
  val costKey: String,
  val sizeBytes: Long,
  /** Where its files live and how its download is recognised; null while the add-on's files do not exist for desktop yet ("coming soon"). */
  val files: AddOnFiles?,
  /** False where the add-on's engine cannot run (the Privacy pack on an Intel Mac): shown as "Not available on this computer", never as installed. */
  val supported: () -> Boolean = { true },
  /** Comes with the models file instead of being added on its own (Speech): the card says whether it is there, and offers nothing to add. */
  val bundled: Boolean = false,
) {
  val available: Boolean get() = files != null
  val sizeMb: Long get() = sizeBytes / 1_000_000

  /**
   * The one size this add-on is shown at: what is on disk once installed, else what its files add up to. The card's "MB on this computer" line,
   * the Remove button and the Remove dialog all print this, so they can never disagree (they used to round two different measurements: 22 MB beside "frees 23 MB").
   */
  fun shownBytes(state: AddOnState): Long = (state as? AddOnState.Installed)?.bytes?.takeIf { it > 0 } ?: sizeBytes
  fun shownSize(state: AddOnState): String = com.craneai.findr.ui.common.formatBytes(shownBytes(state))
}

/**
 * @property dir folder under `models/` the add-on installs into
 * @property folderName a folder with this name in Downloads is also accepted (someone unzipped the pack)
 * @property archive Downloads file names that count as its zip (a browser may add " (1)")
 * @property manifest every file with its size and SHA-256 (compiled into the app: a file that does not match is refused)
 */
class AddOnFiles(val dir: String, val manifest: List<AsrModelFile>, val folderName: String, val archive: Regex)

object AddOnCatalog {
  val privacyFiles = AddOnFiles(
    dir = PrivatePack.DIR,
    manifest = PrivatePack.files,
    folderName = PrivatePack.FOLDER_NAME,
    archive = Regex("""(?:findr|trawl)-private-models.*\.zip""", RegexOption.IGNORE_CASE),
  )

  val privacy = AddOnDef(
    id = PackIds.PRIVACY,
    nameKey = "addon_privacy_name",
    benefitKey = "addon_privacy_benefit",
    costKey = "addon_privacy_cost",
    sizeBytes = PrivatePack.files.sumOf { it.size },
    files = privacyFiles,
    supported = trawl.desktop.privacy.PrivacyRuntime::usable,
  )

  val speech = AddOnDef(
    id = PackIds.SPEECH,
    nameKey = "addon_speech_name",
    benefitKey = "addon_speech_benefit",
    costKey = "addon_speech_cost",
    sizeBytes = 142_772_368L,
    files = null,
    bundled = true,
  )

  val all: List<AddOnDef> = listOf(privacy, speech)
  fun byId(id: String): AddOnDef? = all.firstOrNull { it.id == id }
}

/**
 * F4: which add-ons the first-run page offers. Only what can be added on this computer today (Speech has no signed bundle yet, the
 * Privacy pack needs an engine that runs here) and is not already added. Taken once when the page opens, so a card the person just acted
 * on stays to show "Added" instead of vanishing under their finger. Empty means no extras section at all.
 */
object AddOnOffer {
  fun idsAtFirstRun(defs: List<AddOnDef>, states: Map<String, AddOnState>): List<String> =
    defs.filter { it.available && it.supported() && states[it.id] !is AddOnState.Installed }.map { it.id }
}

/** Why an add-on could not be added: the words come from `addon_problem_<name>`. */
enum class AddOnProblem { NOT_FOUND, BAD_FILES, NOT_SIGNED, BAD_SIGNATURE, DAMAGED, NOT_THIS_ADDON, OLDER_VERSION, NO_SPACE, CANCELLED, UNAVAILABLE }

sealed interface AddOnState {
  data object NotInstalled : AddOnState
  data class Installing(val doneBytes: Long, val totalBytes: Long) : AddOnState {
    val fraction: Float? get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
  }
  data class Installed(val bytes: Long) : AddOnState
  /** Not installed, and the last try failed. [detail] is for the log, never a path. */
  data class Failed(val problem: AddOnProblem, val detail: String? = null) : AddOnState
}

/** What the manager needs from the app around it. */
class AddOnHost(
  /** Called before an add-on's files are deleted: stop whatever has them open, forget what it produced (the scores). */
  val releaseForRemoval: (String) -> Unit = {},
  /** Called after any change: the app picks a new add-on up (starts the scoring pass) or lets go of a removed one. */
  val changed: () -> Unit = {},
)

/** What is at the top of a zip, if it is a signed bundle: its manifest and whether a trusted key signed it. Nothing is installed by reading it. */
class SignedPeek(val manifest: BundleManifest?, val signatureValid: Boolean) {
  /** A signed add-on bundle (role `addon`) whose manifest is understood. */
  val isAddon: Boolean get() = signatureValid && manifest?.role == BundleRole.ADDON

  /** The pack folders (top level) the manifest carries. */
  val folders: Set<String> get() = manifest?.files?.map { it.path.substringBefore('/') }?.toSet().orEmpty()

  companion object {
    private const val MAX_SMALL = 512 * 1024

    /** Null when [zip] is not a signed bundle at all (no manifest first). */
    fun of(zip: File, verifier: SignatureVerifier = SignatureVerifier()): SignedPeek? = try {
      ZipInputStream(zip.inputStream().buffered(1 shl 14)).use { zin ->
        val m = small(zin, BundleManifest.ENTRY) ?: return null
        val s = small(zin, BundleManifest.SIGNATURE_ENTRY)?.let { SignatureVerifier.decodeSig(it) }
        SignedPeek(BundleManifest.parse(m), s != null && verifier.verifies(m, s))
      }
    } catch (_: Exception) {
      null
    }

    private fun small(zin: ZipInputStream, name: String): ByteArray? {
      val e = zin.nextEntry ?: return null
      if (e.name != name || e.isDirectory) return null
      val out = java.io.ByteArrayOutputStream()
      val b = ByteArray(8192)
      while (true) {
        val r = zin.read(b)
        if (r < 0) break
        out.write(b, 0, r)
        if (out.size() > MAX_SMALL) return null
      }
      return out.toByteArray()
    }
  }
}

/**
 * Adds and removes add-ons and keeps their state, with the phone's own checks:
 *  - a SIGNED add-on bundle (`trawl-bundle.json` + signature first, role `addon`) goes through [SignedBundleInstaller]: the ECDSA
 *    P-256 signature over the manifest, then every file's size and SHA-256, all before anything is placed; any failure leaves the
 *    models folder as it was;
 *  - otherwise the pack's own folder or zip, or its folder inside a models bundle, is read through [AsrModelInstaller]: every file must
 *    match the size and SHA-256 compiled into this app, so a changed file is refused.
 *
 * State is always reconciled with the files on disk ([refresh]): an add-on someone copied in shows as installed, and a failed try stays
 * visible until the next one. Nothing is installed unasked: the person presses Add (from a file they picked, or from Downloads).
 */
class AddOnManager(
  private val modelsDir: File,
  private val downloads: () -> List<File>,
  private val choices: PackChoices,
  private val host: AddOnHost = AddOnHost(),
  private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "trawl-addons").apply { isDaemon = true } },
  private val log: (String) -> Unit = {},
  private val catalog: List<AddOnDef> = AddOnCatalog.all,
  private val verifier: SignatureVerifier = SignatureVerifier(),
  private val freeSpace: (File) -> Long = { it.usableSpace },
) {
  private val locks = ConcurrentHashMap<String, Any>()
  private val _states = MutableStateFlow(catalog.associate { it.id to disk(it) })
  val states: StateFlow<Map<String, AddOnState>> get() = _states

  /** Re-reads the disk (a few `stat`s). An install in progress is left alone; a failure stays until something changes. */
  fun refresh() {
    _states.update { cur ->
      catalog.associate { def ->
        val now = cur[def.id]
        def.id to when {
          now is AddOnState.Installing -> now
          else -> disk(def).let { d -> if (d is AddOnState.NotInstalled && now is AddOnState.Failed) now else d }
        }
      }
    }
  }

  /** Add-on files found in the usual folders for [id]: folders, zips and signed add-on bundles, newest first. */
  fun findCandidates(id: String): List<File> {
    val def = catalog.firstOrNull { it.id == id } ?: return emptyList()
    val files = def.files ?: return emptyList()
    return candidates(files, downloads())
  }

  /**
   * Adds [id] from [from] (a file or folder the person picked), or, when null, from what [findCandidates] finds. Runs in the
   * background; progress and the result show in [states].
   */
  fun install(id: String, from: File? = null) {
    val def = catalog.firstOrNull { it.id == id } ?: return
    if (_states.value[id] is AddOnState.Installing) return
    val files = def.files
    if (files == null || !def.supported()) {
      set(id, AddOnState.Failed(AddOnProblem.UNAVAILABLE))
      return
    }
    choices.setDeclined(id, false)
    set(id, AddOnState.Installing(0, def.sizeBytes))
    executor.execute {
      synchronized(locks.getOrPut(id) { Any() }) {
        val result = try {
          run(def, files, from)
        } catch (t: Throwable) {
          log("${def.id}: install failed: ${t.javaClass.simpleName}")
          AddOnState.Failed(AddOnProblem.BAD_FILES, t.javaClass.simpleName)
        }
        set(id, result)
        if (result is AddOnState.Installed) log("${def.id}: installed, ${result.bytes / 1_000_000} MB")
        host.changed()
      }
    }
  }

  private fun run(def: AddOnDef, files: AddOnFiles, from: File?): AddOnState {
    val installer = AsrModelInstaller(File(modelsDir, files.dir), files.manifest)
    val picked = from != null
    if (!picked && installer.complete()) return AddOnState.Installed(installer.installedBytes())
    val found = if (from != null) listOf(from) else candidates(files, downloads())
    if (found.isEmpty()) return AddOnState.Failed(AddOnProblem.NOT_FOUND)
    var worst: AddOnState.Failed? = null
    for (candidate in found) {
      when (val r = tryCandidate(def, files, installer, candidate)) {
        is AddOnState.Installed -> return r
        is AddOnState.Failed -> if (worst == null || rank(r.problem) > rank(worst.problem)) worst = r
        else -> Unit
      }
    }
    log("${def.id}: not installed: ${worst?.problem}")
    return worst ?: AddOnState.Failed(AddOnProblem.NOT_FOUND)
  }

  /** The most telling failure wins over "not found" or "not this add-on" when several files were tried. */
  private fun rank(p: AddOnProblem) = when (p) {
    AddOnProblem.BAD_SIGNATURE -> 5
    AddOnProblem.DAMAGED, AddOnProblem.BAD_FILES -> 4
    AddOnProblem.NO_SPACE, AddOnProblem.OLDER_VERSION -> 3
    AddOnProblem.NOT_SIGNED -> 2
    else -> 1
  }

  private fun tryCandidate(def: AddOnDef, files: AddOnFiles, installer: AsrModelInstaller, candidate: File): AddOnState {
    val progress = { done: Long, total: Long -> set(def.id, AddOnState.Installing(done, total)) }
    // 1. A signed add-on bundle.
    if (candidate.isFile) {
      val peek = SignedPeek.of(candidate, verifier)
      if (peek != null) {
        if (peek.manifest != null && !peek.signatureValid) return AddOnState.Failed(AddOnProblem.BAD_SIGNATURE, "signature does not verify against a trusted key")
        if (peek.isAddon) {
          if (files.folderName !in peek.folders) return AddOnState.Failed(AddOnProblem.NOT_THIS_ADDON)
          return signed(def, files, candidate, progress)
        }
        // A signed models bundle (role full) is not an add-on bundle: its pack folders are read by hash below.
      }
    }
    // 2. The pack's folder or zip, or its folder inside a models bundle: every file checked against the hashes in this app.
    val sources = sourcesOf(files, candidate)
    if (sources.isEmpty()) return AddOnState.Failed(AddOnProblem.NOT_THIS_ADDON)
    val problems = mutableListOf<String>()
    try {
      for (s in sources) {
        val r = try {
          installer.install(s, progress)
        } catch (t: Throwable) {
          problems += "${t.javaClass.simpleName}"
          continue
        }
        when (r) {
          is AsrModelInstaller.Outcome.Ready -> return AddOnState.Installed(installer.installedBytes())
          is AsrModelInstaller.Outcome.Incomplete -> problems += r.problems
        }
      }
    } finally {
      sources.forEach { runCatching { it.close() } }
    }
    return AddOnState.Failed(AddOnProblem.BAD_FILES, problems.firstOrNull())
  }

  private fun signed(def: AddOnDef, files: AddOnFiles, zip: File, progress: (Long, Long) -> Unit): AddOnState {
    val installer = SignedBundleInstaller(
      modelsDir, BundleClass.STANDARD, modelNames = emptyList(),
      packDirs = catalog.mapNotNull { d -> d.files?.let { it.folderName to it.dir } }.toMap(),
      verifier = verifier, freeSpace = freeSpace,
    )
    val result = zip.inputStream().buffered(1 shl 20).use { installer.install(it, progress) }
    return when (result) {
      is BundleResult.Installed -> AddOnState.Installed(disk(def).let { (it as? AddOnState.Installed)?.bytes ?: result.installedBytes })
      is BundleResult.Refused -> when (result.reason) {
        // The same version is already in place and whole: that is a success, not a failure.
        Refusal.ALREADY_INSTALLED -> disk(def).takeIf { it is AddOnState.Installed } ?: AddOnState.Installed(0)
        Refusal.NOT_SIGNED -> AddOnState.Failed(AddOnProblem.NOT_SIGNED, result.detail)
        Refusal.BAD_SIGNATURE -> AddOnState.Failed(AddOnProblem.BAD_SIGNATURE, result.detail)
        Refusal.OLDER_VERSION -> AddOnState.Failed(AddOnProblem.OLDER_VERSION, result.detail)
        Refusal.NO_SPACE -> AddOnState.Failed(AddOnProblem.NO_SPACE, result.detail)
        Refusal.CANCELLED -> AddOnState.Failed(AddOnProblem.CANCELLED)
        Refusal.BAD_MANIFEST, Refusal.WRONG_CLASS -> AddOnState.Failed(AddOnProblem.NOT_THIS_ADDON, result.detail)
        Refusal.DAMAGED, Refusal.INCOMPLETE -> AddOnState.Failed(AddOnProblem.DAMAGED, result.detail)   // a copy that stopped short reads the same: copy it again
        Refusal.UNREADABLE -> AddOnState.Failed(AddOnProblem.BAD_FILES, result.detail)
      }
    }
  }

  /** Deletes the add-on's files and does not install it again behind the person's back. */
  fun remove(id: String) {
    val def = catalog.firstOrNull { it.id == id } ?: return
    val files = def.files ?: return
    if (_states.value[id] is AddOnState.Installing) return
    choices.setDeclined(id, true)
    executor.execute {
      synchronized(locks.getOrPut(id) { Any() }) {
        host.releaseForRemoval(id)
        val dir = File(modelsDir, files.dir)
        val freed = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dir.deleteRecursively()
        set(id, disk(def))
        log("${def.id}: removed, freed ${freed / 1_000_000} MB")
        host.changed()
      }
    }
  }

  /** "Not now": remember it, so nothing that turns up in Downloads is added unasked. */
  fun decline(id: String) = choices.setDeclined(id, true)

  /** The version recorded for a signed add-on bundle, or null (installed by hash, or not installed). */
  fun recordedVersion(bundle: String): Int? = InstalledAddons.read(modelsDir)[bundle]

  private fun set(id: String, state: AddOnState) = _states.update { it + (id to state) }

  private fun disk(def: AddOnDef): AddOnState {
    val files = def.files ?: return AddOnState.NotInstalled
    if (!def.supported()) return AddOnState.NotInstalled   // files copied in by hand still never read as "installed" where the engine cannot run
    val installer = AsrModelInstaller(File(modelsDir, files.dir), files.manifest)
    return if (installer.complete()) AddOnState.Installed(installer.installedBytes()) else AddOnState.NotInstalled
  }

  companion object {
    /** Folders, then zips, newest first, that could hold [files]: its own folder, its own zips, a models bundle's folder and zips, and signed bundles. */
    fun candidates(files: AddOnFiles, inFolders: List<File>): List<File> {
      val found = LinkedHashSet<File>()
      for (dir in inFolders) {
        val entries = dir.listFiles()?.toList().orEmpty()
        entries.filter { it.isDirectory && it.name == files.folderName }.forEach { found += it }
        entries.filter { it.isFile && files.archive.matches(it.name) }.sortedByDescending { it.lastModified() }.forEach { found += it }
        entries.filter { it.isDirectory && it.name == "trawl-models" && File(it, files.folderName).isDirectory }.forEach { found += it }
        entries.filter { it.isFile && ModelsBundleName.matches(it.name) }.sortedByDescending { it.lastModified() }.forEach { found += it }
      }
      return found.toList()
    }

    private val ModelsBundleName = Regex("""trawl-(models|addon)[^/]*\.zip""", RegexOption.IGNORE_CASE)

    /** The hash-checked sources a candidate offers: the folder itself, the zip (at its root or under one wrapping folder), or the pack inside a bundle. */
    fun sourcesOf(files: AddOnFiles, candidate: File): List<AsrModelSource> = buildList {
      if (candidate.isDirectory) {
        if (File(candidate, files.manifest.first().path).isFile) add(FolderModelSource(candidate))
        File(candidate, files.folderName).takeIf { it.isDirectory }?.let { add(FolderModelSource(it)) }
      } else if (candidate.isFile) {
        runCatching { ZipModelSource(candidate) }.getOrNull()?.let { z ->
          if (z.entry(files.manifest.first().path) != null) add(z) else z.close()
        }
        runCatching { ZipModelSource(candidate, prefix = files.folderName + "/") }.getOrNull()?.let { z ->
          if (z.carriesPrefix()) add(z) else z.close()
        }
      }
    }
  }
}
