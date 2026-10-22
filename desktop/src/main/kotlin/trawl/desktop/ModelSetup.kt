package trawl.desktop

import com.craneai.findr.core.Refusal
import com.craneai.findr.engine.AsrModelPack
import com.craneai.findr.engine.BundleClass
import com.craneai.findr.engine.BundleResult
import com.craneai.findr.engine.Embedder
import com.craneai.findr.engine.InstalledBundle
import com.craneai.findr.engine.MemoryProfile
import com.craneai.findr.engine.SignedBundleInstaller
import java.io.File

/**
 * Getting the models onto this computer, the way the phone does it and with the phone's own code: the signed `trawl-models.zip` is
 * checked by [SignedBundleInstaller] (ECDSA P-256 signature over the manifest, then every file's SHA-256, before anything is
 * installed) and the model file lands in `models/`. Trawl never downloads it: the person's browser does, or someone hands them the file.
 */
class ModelSetup(private val dataDir: File, totalRamMb: Long = Platform.totalRamMb()) {
  val modelsDir = File(dataDir, "models")

  /** Chosen the way the phone chooses: by memory. A computer with 3 GB or less asks for the lite bundle. */
  val profile: MemoryProfile = MemoryProfile.choose(totalRamMb, false, 0)
  private val names = Embedder.modelNames(profile)

  private fun installer(skip: Set<String> = emptySet()) =
    SignedBundleInstaller(modelsDir, BundleClass.of(profile), names, packDirs = mapOf(AsrModelPack.FOLDER_NAME to ASR_DIR), skipFolders = skip)

  /** The model file to load, or null when none is installed. */
  fun installedModel(): File? = names.map { File(modelsDir, it) }.firstOrNull { it.isFile }

  /** What the installed models take on disk. */
  fun bytesOnDisk(): Long = modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  fun installedLabel(): String? {
    val rec = InstalledBundle.read(modelsDir) ?: return installedModel()?.name
    return "${rec.bundle} v${rec.version}"
  }

  /** A models bundle waiting in one of [folders]: the newest `trawl-models*.zip`, never a download still in progress. */
  fun findBundle(folders: List<File>): File? = folders.asSequence()
    .flatMap { dir -> dir.listFiles { f -> f.isFile && BUNDLE_NAME.matches(f.name) }?.asSequence() ?: emptySequence() }
    .maxByOrNull { it.lastModified() }

  /** Where the speech pack (Parakeet, the voice detector, the sound labels) lands. */
  val asrDir = File(modelsDir, ASR_DIR)

  /** Verifies and installs [zip]. The result is the phone's own [BundleResult]: installed, or refused with a [Refusal]. */
  fun install(zip: File, progress: (done: Long, total: Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): BundleResult {
    modelsDir.mkdirs()
    val first = zip.inputStream().buffered(1 shl 20).use { installer().install(it, progress, cancelled) }
    // Models installed before computers read speech have this bundle's version and no speech pack. The same version is refused as
    // "already installed", so the record is lifted once and the bundle is checked and installed again (every file is still verified).
    val carriesSpeech = (first as? BundleResult.Refused)?.manifest?.files?.any { it.path.startsWith(AsrModelPack.FOLDER_NAME + "/") } == true
    if (first is BundleResult.Refused && first.reason == Refusal.ALREADY_INSTALLED && carriesSpeech && !DesktopAsr(asrDir).installed()) {
      val record = File(modelsDir, InstalledBundle.FILE)
      val saved = record.takeIf { it.isFile }?.readBytes()
      record.delete()
      val again = zip.inputStream().buffered(1 shl 20).use { installer().install(it, progress, cancelled) }
      if (again is BundleResult.Refused && saved != null && !record.isFile) record.writeBytes(saved)   // refused again: the models stay as they were
      return again
    }
    return first
  }

  companion object {
    /** The folder under `models/` the speech pack installs into (the phone's `files/models/asr/`). */
    const val ASR_DIR = "asr"
    val BUNDLE_NAME = Regex("trawl-models.*\\.zip", RegexOption.IGNORE_CASE)

    /** The strings.properties key that says a [Refusal] in plain words. */
    fun refusalKey(r: Refusal, profile: MemoryProfile = MemoryProfile.STANDARD): String =
      // The signed code only says "wrong class". A computer takes the standard bundle (the 440m model), the phone-sized lite bundle is
      // refused on purpose, and the person should be told which file to fetch, not that it is "a different kind of device".
      if (r == Refusal.WRONG_CLASS && profile == MemoryProfile.LITE) "refusal_wrong_class_lite"
      else "refusal_" + r.name.lowercase()
  }
}

/** F3: the "delete the zip now?" question after a model install. The zip is the person's file, so it is only ever deleted when they say yes. */
object ModelZipOffer {
  /** The zip to ask about, or null when there is nothing to ask: it is gone already, or it is a file Trawl itself keeps (under [modelsDir]). */
  fun forZip(zip: File, modelsDir: File): File? {
    if (!zip.isFile) return null
    val inside = runCatching { zip.canonicalFile.toPath().startsWith(modelsDir.canonicalFile.toPath()) }.getOrDefault(false)
    return zip.takeIf { !inside }
  }

  /** Deletes [zip]. Returns the line to show (already in the person's words) and whether it worked. */
  fun delete(zip: File): Pair<String, Boolean> {
    val size = zip.length()
    val ok = runCatching { zip.delete() && !zip.exists() }.getOrDefault(false)
    return if (ok) t("first_zip_deleted", zip.name, com.craneai.findr.ui.common.formatBytes(size)) to true
    else t("first_zip_delete_failed", zip.name) to false
  }
}
