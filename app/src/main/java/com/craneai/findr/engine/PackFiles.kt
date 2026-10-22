package com.craneai.findr.engine

import com.craneai.findr.core.PackCatalog
import java.io.File
import kotlinx.coroutines.sync.Mutex

/**
 * Where a pack's files live and how to recognise its download: the storage half of a [com.craneai.findr.core.PackDef].
 * Pure JVM, so the lookups are unit-tested. A new pack is one more entry in [PackFilesRegistry.of].
 *
 * @property dir folder under `files/models/` the pack installs into
 * @property folderName a folder with this name in Downloads is also accepted (a tester copied the unzipped pack)
 * @property archive Downloads file names that count as the pack's zip (a browser may add " (1)")
 */
class PackFiles(val dir: String, val manifest: List<AsrModelFile>, val folderName: String, val archive: Regex)

object PackFilesRegistry {
  val speech = PackFiles(
    dir = "asr",
    manifest = AsrModelPack.files,
    folderName = AsrModelPack.FOLDER_NAME,
    archive = Regex("""findr-speech-models.*\.zip""", RegexOption.IGNORE_CASE),
  )

  /** Quick look (D2, D10): TinyCLIP-61M/32, both towers (engine/QuickLookPack.kt). */
  val quickLook = PackFiles(
    dir = QuickLookPack.DIR,
    manifest = QuickLookPack.files,
    folderName = QuickLookPack.FOLDER_NAME,
    archive = Regex("""findr-quicklook-models.*\.zip""", RegexOption.IGNORE_CASE),
  )

  /** Blur private-looking previews: Marqo/nsfw-image-detection-384 and its licence (engine/PrivatePack.kt). */
  val privacy = PackFiles(
    dir = PrivatePack.DIR,
    manifest = PrivatePack.files,
    folderName = PrivatePack.FOLDER_NAME,
    archive = Regex("""findr-private-models.*\.zip""", RegexOption.IGNORE_CASE),
  )

  /** `null` for a pack whose files do not exist yet. */
  fun of(id: String): PackFiles? = when (id) {
    PackCatalog.SPEECH -> speech
    PackCatalog.QUICK_LOOK -> quickLook
    PackCatalog.PRIVACY -> privacy
    else -> null
  }
}

/**
 * The pack's own folder first, then its own archives newest first (a browser may have saved "findr-speech-models (1).zip"),
 * then the one-file bundle ([ModelBundle]): its folder or archives, where the pack sits in a folder of its own name. A
 * bundle that does not carry this pack is skipped, not reported as a failure.
 */
internal fun findPackSources(
  inDownloads: List<File>,
  pack: PackFiles,
  /** An archive with the pack's name that can't be opened (truncated, not a zip): reported, so a failure is never silent. */
  onUnreadable: (File, Throwable) -> Unit = { _, _ -> },
): List<AsrModelSource> {
  val folder = inDownloads.firstOrNull { it.isDirectory && it.name == pack.folderName }
  val archives = inDownloads.filter { it.isFile && pack.archive.matches(it.name) }.sortedByDescending { it.lastModified() }
  val bundleFolder = ModelBundle.folder(inDownloads)?.let { File(it, pack.folderName) }?.takeIf { it.isDirectory }
  return listOfNotNull(folder?.let { FolderModelSource(it) }) +
    archives.mapNotNull { f -> runCatching { ZipModelSource(f) }.onFailure { onUnreadable(f, it) }.getOrNull() } +
    listOfNotNull(bundleFolder?.let { FolderModelSource(it) }) +
    ModelBundle.archives(inDownloads).mapNotNull { f ->
      runCatching { ZipModelSource(f, prefix = pack.folderName + "/") }.onFailure { onUnreadable(f, it) }.getOrNull()
        ?.let { z -> if (z.carriesPrefix()) z else { z.close(); null } }
    }
}

/**
 * Why a pack found in Downloads must not be installed without being asked, or null when it may be. "Not now" and Remove both
 * set the flag ([PackChoices]), so a choice the person made is kept; only Settings → Add-ons, Add clears it. Plain data in,
 * so the rule is unit-tested.
 */
internal fun autoInstallBlock(choices: PackChoices, packId: String, hasFileAccess: Boolean): String? = when {
  choices.declined(packId) -> "declined earlier (Settings, Add-ons, Add installs it)"
  !hasFileAccess -> "no All files access to read Downloads"
  else -> null
}

/** One lock per pack: the engine's start-up, the index worker and the Add button can all install the same pack at once. */
object PackLocks {
  private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
  fun of(id: String): Mutex = locks.getOrPut(id) { Mutex() }
}
