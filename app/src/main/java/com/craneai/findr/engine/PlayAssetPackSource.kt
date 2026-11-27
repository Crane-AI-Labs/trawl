package com.craneai.findr.engine

import com.craneai.findr.R
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackDef
import com.craneai.findr.core.PackIds
import com.craneai.findr.core.sizeCopy
import java.io.File

/**
 * Maps Trawl pack IDs and storage directories to their Google Play Asset Delivery (`com.android.asset-pack`)
 * `on-demand` module names. Android App Bundle pack names must match `^[a-zA-Z][a-zA-Z0-9_]*$` (no hyphens), so
 * hyphenated pack IDs (`speech-lg`) map to underscore PAD pack names (`pack_speech_lg`).
 */
object PlayAssetPacks {
  const val SPEECH = "pack_speech"
  const val QUICK_LOOK = "pack_quicklook"
  const val PRIVACY = "pack_privacy"
  const val SPEECH_LG = "pack_speech_lg"
  const val SPEECH_NYN = "pack_speech_nyn"
  const val SPEECH_ACH = "pack_speech_ach"
  const val SPEECH_SW = "pack_speech_sw"
  const val SPEECH_XOG = "pack_speech_xog"

  val BY_PACK_ID: Map<String, String> = mapOf(
    PackIds.SPEECH to SPEECH,
    PackIds.QUICK_LOOK to QUICK_LOOK,
    PackIds.PRIVACY to PRIVACY,
    PackIds.SPEECH_LG to SPEECH_LG,
    PackIds.SPEECH_NYN to SPEECH_NYN,
    PackIds.SPEECH_ACH to SPEECH_ACH,
    PackIds.SPEECH_SW to SPEECH_SW,
    PackIds.SPEECH_XOG to SPEECH_XOG,
  )

  private val BY_DIR: Map<String, String> = mapOf(
    "asr" to SPEECH,
    QuickLookPack.DIR to QUICK_LOOK,
    PrivatePack.DIR to PRIVACY,
    SpeechPacks.lg.dir to SPEECH_LG,
    SpeechPacks.nyn.dir to SPEECH_NYN,
    SpeechPacks.ach.dir to SPEECH_ACH,
    SpeechPacks.sw.dir to SPEECH_SW,
    SpeechPacks.xog.dir to SPEECH_XOG,
  )

  val ALL_PACK_NAMES: List<String> = BY_PACK_ID.values.toList()

  fun packNameForId(id: String): String? = BY_PACK_ID[id]

  fun packNameFor(pack: PackFiles): String? = BY_DIR[pack.dir]
}

/** Play Asset Delivery transfer status mirrored in pure Kotlin so state transitions are unit-tested off-device. */
enum class PlayAssetStatus {
  UNKNOWN,
  PENDING,
  DOWNLOADING,
  TRANSFERRING,
  COMPLETED,
  FAILED,
  CANCELED,
  WAITING_FOR_WIFI,
  NOT_INSTALLED,
  REQUIRES_USER_CONFIRMATION,
}

data class PlayAssetProgress(
  val status: PlayAssetStatus,
  val bytesDownloaded: Long = 0L,
  val totalBytes: Long = 0L,
  val errorCode: Int = 0,
)

/** Outcome of an on-demand Play Asset Delivery fetch request. */
sealed interface PlayAssetFetchResult {
  data class Ready(val assetsDir: File) : PlayAssetFetchResult
  data class WaitingForWifi(val packName: String) : PlayAssetFetchResult
  data class NeedsConfirmation(val packName: String) : PlayAssetFetchResult
  data class Canceled(val packName: String) : PlayAssetFetchResult
  data class Failed(val reason: String, val errorCode: Int = 0) : PlayAssetFetchResult
  data class Unavailable(val reason: String) : PlayAssetFetchResult
}

/**
 * Abstraction over `com.google.android.play.core.assetpacks.AssetPackManager`.
 * Play AAB builds (`-PtrawlPlayAssetDelivery=true`) bind this to Google Play's real IPC client (zero `INTERNET`
 * permission in Trawl itself); sideload APKs bind no Play Core dependency; JVM unit tests supply a fake client.
 */
interface PlayAssetDeliveryClient {
  /** True when the app was installed via Google Play (`com.android.vending`) and can request on-demand asset packs. */
  fun isPlayStoreInstall(): Boolean

  /** Returns the local extracted assets directory if [packName] is already delivered by Play, or null otherwise. */
  fun installedAssetsDir(packName: String): File?

  /** Requests on-demand delivery of [packName], invoking [onUpdate] on each Play status/progress event. */
  suspend fun fetch(packName: String, onUpdate: (PlayAssetProgress) -> Unit = {}): PlayAssetFetchResult

  /** Asks Play Asset Delivery to delete its cached copy of [packName] after verified copy or user removal. */
  fun removePack(packName: String) {}
}

/** Default zero-dependency client used when Play Asset Delivery is not compiled into this build. */
object StubPlayAssetDeliveryClient : PlayAssetDeliveryClient {
  override fun isPlayStoreInstall(): Boolean = false
  override fun installedAssetsDir(packName: String): File? = null
  override suspend fun fetch(packName: String, onUpdate: (PlayAssetProgress) -> Unit): PlayAssetFetchResult =
    PlayAssetFetchResult.Unavailable("Play Asset Delivery is not wired up in this build")
}

/**
 * Store-ready [ModelPackSource] backed by Google Play Asset Delivery (`on-demand` asset packs).
 *
 * - When installed from Google Play (`client.isPlayStoreInstall() == true`): checks for an already-delivered pack
 *   first, otherwise triggers an on-demand Play fetch and forwards download/transfer progress to [EnginePackManager],
 *   then returns [FolderModelSource] candidates so [AsrModelInstaller] verifies every file's exact byte size and
 *   SHA-256 before moving it into app storage.
 * - When installed outside Google Play (sideloaded) or when Play reports the pack unavailable (`APP_NOT_OWNED` /
 *   `PACK_UNAVAILABLE`): cleanly falls back to [fallback] ([DownloadsFolderSource], including its external-browser
 *   download URL) when supplied.
 */
class PlayAssetPackSource(
  private val client: PlayAssetDeliveryClient = StubPlayAssetDeliveryClient,
  private val fallback: ModelPackSource? = null,
) : ModelPackSource {
  private fun usePlay(): Boolean = client.isPlayStoreInstall()

  override suspend fun open(pack: PackFiles): PackSourceResult = open(pack) { _, _ -> }

  override suspend fun open(pack: PackFiles, onProgress: (done: Long, total: Long) -> Unit): PackSourceResult {
    if (!usePlay()) {
      return fallback?.open(pack, onProgress)
        ?: PackSourceResult.Unavailable("Play Asset Delivery is not wired up in this build")
    }
    val padName = PlayAssetPacks.packNameFor(pack)
      ?: return fallback?.open(pack, onProgress)
        ?: PackSourceResult.Unavailable("No Play Asset Delivery pack declared for ${pack.dir}")

    client.installedAssetsDir(padName)?.let { readyDir ->
      val sources = candidateSources(readyDir, pack)
      if (sources.isNotEmpty()) return PackSourceResult.Found(sources)
    }

    val result = client.fetch(padName) { update ->
      if (update.totalBytes > 0L) {
        onProgress(update.bytesDownloaded.coerceIn(0L, update.totalBytes), update.totalBytes)
      }
    }
    return when (result) {
      is PlayAssetFetchResult.Ready -> {
        val sources = candidateSources(result.assetsDir, pack)
        if (sources.isNotEmpty()) PackSourceResult.Found(sources)
        else PackSourceResult.Unreadable("Play asset pack $padName has no readable assets directory")
      }
      is PlayAssetFetchResult.Unavailable ->
        fallback?.open(pack, onProgress) ?: PackSourceResult.Unavailable(result.reason)
      is PlayAssetFetchResult.WaitingForWifi ->
        PackSourceResult.Unavailable("Play Asset Delivery ($padName) is waiting for Wi-Fi")
      is PlayAssetFetchResult.NeedsConfirmation ->
        PackSourceResult.Unavailable("Play Asset Delivery ($padName) requires user confirmation for cellular download")
      is PlayAssetFetchResult.Canceled ->
        PackSourceResult.Unavailable("Play Asset Delivery ($padName) download was canceled")
      is PlayAssetFetchResult.Failed ->
        PackSourceResult.Unavailable("Play Asset Delivery ($padName) failed: ${result.reason}")
    }
  }

  override fun onInstalled(pack: PackFiles) {
    if (!usePlay()) return
    // Reclaim Play's duplicate asset-pack cache immediately once AsrModelInstaller has verified & copied every file.
    PlayAssetPacks.packNameFor(pack)?.let(client::removePack)
  }

  override fun onRemoved(pack: PackFiles) {
    if (!usePlay()) return
    PlayAssetPacks.packNameFor(pack)?.let(client::removePack)
  }

  override fun howToAdd(def: PackDef): PackCopy =
    if (usePlay() || fallback == null) PackCopy(R.string.pack_how_to_add_play, listOf(def.nameCopy, def.sizeCopy))
    else fallback.howToAdd(def)

  override fun downloadUrl(def: PackDef): String? =
    if (usePlay() || fallback == null) null else fallback.downloadUrl(def)

  private fun candidateSources(assetsDir: File, pack: PackFiles): List<AsrModelSource> {
    if (!assetsDir.isDirectory) return emptyList()
    val nested = File(assetsDir, pack.folderName).takeIf { it.isDirectory }
    return listOfNotNull(nested?.let { FolderModelSource(it) }, FolderModelSource(assetsDir))
  }
}
