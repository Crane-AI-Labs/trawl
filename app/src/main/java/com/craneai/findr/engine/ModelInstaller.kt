package com.craneai.findr.engine

import android.content.Context
import android.os.Environment
import com.craneai.findr.BuildConfig
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "FindrModel"

/**
 * Puts the embedding model into app-private storage. Findr has no internet permission, so it can't download it:
 * a sideloaded tester copies the .litertlm file into Downloads (or it is pushed with adb during development), or the
 * one-file bundle `trawl-models.zip` that also carries the quick-look and speech packs ([ModelBundle]), and this moves it in. Copy to a temp name, then rename, so a half-copied file is never used.
 * (Shipping it inside the APK or a Play asset pack is the later, store-ready path: the build notes.)
 */
class ModelInstaller(
  private val context: Context,
  /** A pack's file was in Downloads but could not be used (unreadable, damaged, wrong files): the Add-ons card shows why. */
  private val report: (String, PackState.Failed) -> Unit = { _, _ -> },
  /** Which embedding models this phone may use (the lite profile prefers the text-only bundle). */
  profile: MemoryProfile = MemoryProfile.STANDARD,
) {
  private val modelsDir = File(context.filesDir, "models")
  /** The embedding models this phone may use, best first (the lite profile prefers the text-only bundle). */
  private val names = Embedder.modelNames(profile)

  /**
   * What a new phone needs, found in Downloads and installed without a question: the embedding model, then the quick-look,
   * speech and blur (private-looking previews) packs, each from the one-file bundle ([ModelBundle]) or from its separate file. A pack the user said "Not now" to
   * or removed is left alone ([mayAutoInstall]); a pack that is simply not there costs nothing and says nothing. The packs'
   * switches are not touched: they already default to on (a 2 GB phone's speech switch to off, SettingsCodec), and a switch
   * the user turned off stays off. Returns whether the model is in place.
   */
  suspend fun installDefaultsFromDownloads(): Boolean {
    val model = installFromDownloads()
    installQuickLookFromDownloads()   // first: it is what makes a fresh gallery searchable by meaning in minutes
    installAsrFromDownloads()
    installPrivateFromDownloads()   // 22 MB: the blur of private-looking previews is on by default too (its threshold is PrivateLook.MAIN_APP_THRESHOLD)
    return model
  }

  suspend fun installFromDownloads(): Boolean = withContext(Dispatchers.IO) {
    if (wantedModels().isEmpty()) return@withContext true
    if (!Environment.isExternalStorageManager()) return@withContext installedModel() != null
    // The engine's start-up, the index worker and the access grant all come here; two copies of one file would race on its .part.
    PackLocks.of(MODEL_LOCK).withLock {
      val wanted = wantedModels()   // again, under the lock: another caller may just have finished
      if (wanted.isEmpty()) return@withLock true
      if (!BuildConfig.ALLOW_UNSIGNED_MODELS) {
        // A user's build takes the model only from a signed bundle (ModelDelivery); a loose or unsigned file is ignored, in the log and nowhere else.
        Log.i(TAG, "model: unsigned model files are not accepted in this build; waiting for a signed bundle")
        return@withLock false
      }
      val listing = downloadsListing()
      val loose = wanted.map { File(downloads(), it) }.firstOrNull { it.isFile && it.length() > ModelBundle.MIN_MODEL_BYTES }
      modelsDir.mkdirs()
      try {
        if (loose != null) {
          val temp = File(modelsDir, loose.name + ".part")
          try {
            loose.inputStream().use { input -> temp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            if (temp.length() != loose.length()) error("copied ${temp.length()} of ${loose.length()} bytes")
            temp.renameTo(File(modelsDir, loose.name))
          } catch (t: Throwable) {
            temp.delete()
            throw t
          }
          Log.i(TAG, "installed ${loose.name} (${loose.length() / 1_000_000} MB) from Downloads")   // log-safe: our model file
        } else {
          val name = ModelBundle.installModel(listing, wanted, modelsDir) { f, t ->
            Log.w(TAG, "model: can't use ${redact(f.name)} in Downloads", t)
            com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.MODEL_INSTALL, t)
          } ?: return@withLock installedModel() != null
          Log.i(TAG, "installed $name (${File(modelsDir, name).length() / 1_000_000} MB) from the models bundle in Downloads")   // log-safe: our model file
        }
        true
      } catch (t: Throwable) {
        Log.w(TAG, "model install failed", t)
        com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.MODEL_INSTALL, t)
        false
      }
    }
  }

  private fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

  private fun installedModel(): String? = names.firstOrNull { File(modelsDir, it).isFile }

  /**
   * Which model files a Downloads copy may still install. Any model will do, except that with the fine-tuned flag on a
   * preferred (fine-tuned) bundle replaces a stock one already installed.
   */
  private fun wantedModels(): List<String> {
    val installed = installedModel()
    return when {
      installed == null -> names
      Embedder.USE_FINETUNED -> names.takeWhile { it != installed }
      else -> emptyList()
    }
  }

  /**
   * The speech models (D3: on in release builds). A tester copies either the folder `findr-speech-models/` or the archive
   * `trawl-speech-models.zip` (scripts/pack-asr-models.sh makes both) into Downloads; this verifies each file by size and
   * SHA-256 and moves it into `files/models/asr/`, the layout [com.craneai.findr.extract.SherpaAsrEngine] reads. A later
   * Play Asset Delivery pack is one more [AsrModelSource] fed to the same [AsrModelInstaller].
   * Returns true when every required speech file is in place.
   */
  suspend fun installAsrFromDownloads(): Boolean = withContext(Dispatchers.IO) {
    val installer = AsrModelInstaller(File(modelsDir, PackFilesRegistry.speech.dir), PackFilesRegistry.speech.manifest)
    if (installer.complete()) return@withContext true
    // "Not now" in onboarding, or Remove in Settings: a zip left in Downloads is not installed unasked. Add clears this.
    if (!mayAutoInstall(PackCatalog.SPEECH, "speech models")) return@withContext false
    // The engine's start-up, the index worker and the Add button all call this; two copies of one file would race on its .part (seen on the Infinix).
    PackLocks.of(PackCatalog.SPEECH).withLock {
      installLocked(installer, sources(PackFilesRegistry.speech, PackCatalog.SPEECH, "speech models"), PackCatalog.SPEECH, "speech models")
    }
  }

  /** Not declined, and Downloads is readable; says which, in the log, when not. */
  private fun mayAutoInstall(packId: String, what: String): Boolean {
    val block = autoInstallBlock(PackPrefs(context), packId, Environment.isExternalStorageManager())
    if (block != null) Log.i(TAG, "$what: not installed automatically: $block")
    return block == null
  }

  /** The pack's folder or archives in Downloads; an archive that can't be opened is logged and shown on the card. */
  private fun sources(files: PackFiles, packId: String, what: String): List<AsrModelSource> {
    val listing = downloadsListing()
    val found = findPackSources(listing, files) { f, t ->
      Log.w(TAG, "$what: can't open ${redact(f.name)} in Downloads", t)
      report(packId, PackState.Failed(PackProblem.BAD_FILES, "${f.name}: ${t.message ?: t}"))
    }
    if (found.isEmpty()) Log.i(TAG, "$what: none in Downloads (${listing.size} entries; looking for ${files.folderName}/ or ${files.archive.pattern})")   // log-safe: the pack's own folder name and file pattern
    return found
  }

  /**
   * The quick-look pack (D2): `trawl-quicklook-models.zip` (or the unzipped folder) in Downloads, made by
   * scripts/pack-quicklook-models.sh, checked file by file into `files/models/quicklook/`. Same rules as speech: a pack the
   * user said "Not now" to or removed is left alone until they tap Add.
   */
  suspend fun installQuickLookFromDownloads(): Boolean = withContext(Dispatchers.IO) {
    val files = PackFilesRegistry.quickLook
    val installer = AsrModelInstaller(File(modelsDir, files.dir), files.manifest)
    if (installer.complete()) return@withContext true
    if (!mayAutoInstall(PackCatalog.QUICK_LOOK, "quick-look models")) return@withContext false
    PackLocks.of(PackCatalog.QUICK_LOOK).withLock {
      val ok = installer.complete() ||
        installLocked(installer, sources(files, PackCatalog.QUICK_LOOK, "quick-look models"), PackCatalog.QUICK_LOOK, "quick-look models")
      if (ok) QuickLookPack.RETIRED.map { File(modelsDir, "${files.dir}/$it") }.filter { it.isFile }.forEach { f ->
        val mb = f.length() / 1_000_000
        if (f.delete()) Log.i(TAG, "removed ${f.name} ($mb MB), which the quick-look pack no longer uses")   // log-safe: our model file
        f.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()   // an earlier model's emptied folder
      }
      ok
    }
  }

  /**
   * The "Blur private-looking previews" pack: `trawl-private-models.zip` (or the unzipped folder) in Downloads, made by
   * scripts/pack-private-models.sh, checked file by file into `files/models/private/`. Same rules as the other packs.
   */
  suspend fun installPrivateFromDownloads(): Boolean = withContext(Dispatchers.IO) {
    val files = PackFilesRegistry.privacy
    val installer = AsrModelInstaller(File(modelsDir, files.dir), files.manifest)
    if (installer.complete()) return@withContext true
    if (!mayAutoInstall(PackCatalog.PRIVACY, "privacy model")) return@withContext false
    PackLocks.of(PackCatalog.PRIVACY).withLock {
      installer.complete() || installLocked(installer, sources(files, PackCatalog.PRIVACY, "privacy model"), PackCatalog.PRIVACY, "privacy model")
    }
  }

  private fun downloadsListing(): List<File> = downloads().listFiles()?.toList().orEmpty()

  private fun installLocked(installer: AsrModelInstaller, sources: List<AsrModelSource>, packId: String, what: String): Boolean {
    if (installer.complete()) return true
    val problems = mutableListOf<String>()
    for (source in sources) {
      try {
        when (val r = installer.install(source)) {
          is AsrModelInstaller.Outcome.Ready -> {
            Log.i(TAG, "$what ready: ${r.copied.size} files (${r.bytes / 1_000_000} MB) from ${redact(source.label)}")
            return true
          }
          is AsrModelInstaller.Outcome.Incomplete -> {
            Log.w(TAG, "$what from ${redact(source.label)} are incomplete: ${r.problems.size} problems (${redact(r.problems.joinToString("; "))})")
            problems += r.problems
          }
        }
      } catch (t: Throwable) {
        Log.w(TAG, "$what install from ${redact(source.label)} failed", t)
        com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.PACK_INSTALL, t)
        problems += "${source.label}: ${t.message ?: t}"
      } finally {
        source.close()
      }
    }
    if (problems.isNotEmpty()) report(packId, PackState.Failed(PackProblem.BAD_FILES, problems.first()))
    return false
  }

  companion object {
    private const val MODEL_LOCK = ModelDelivery.MODEL_LOCK

    /** The folder first, then archives newest first (a browser may have saved "trawl-speech-models (1).zip"). */
    internal fun findAsrSources(inDownloads: List<File>): List<AsrModelSource> = findPackSources(inDownloads, PackFilesRegistry.speech)
  }
}
