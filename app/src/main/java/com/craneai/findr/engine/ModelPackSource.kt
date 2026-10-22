package com.craneai.findr.engine

import android.content.Context
import android.os.Environment
import com.craneai.findr.R
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackDef
import com.craneai.findr.core.sizeCopy
import java.io.File

/** What a [ModelPackSource] found for one pack. */
sealed interface PackSourceResult {
  /** One or more places the pack's files may be read from, best first. The caller closes them. */
  data class Found(val sources: List<AsrModelSource>) : PackSourceResult
  /** Nothing that looks like the pack. */
  data object NotFound : PackSourceResult
  /** The source needs All files access and Findr does not have it. */
  data object NeedsFileAccess : PackSourceResult
  /** This build cannot get the pack this way (yet): [reason] is for the log. */
  data class Unavailable(val reason: String) : PackSourceResult
  /** The pack's file is there but can't be read (a truncated copy, not a zip): [detail] names it. */
  data class Unreadable(val detail: String) : PackSourceResult
}

/**
 * How a pack's files reach the phone. Findr has no INTERNET permission by design, so it never downloads anything itself:
 * today a sideloader copies a .zip into Downloads ([DownloadsFolderSource]); on Play the same Add button asks Play Asset
 * Delivery for an on-demand pack ([PlayAssetPackSource]). Either way the result is a list of [AsrModelSource]s fed to the
 * same checked installer, so nothing downstream changes.
 */
interface ModelPackSource {
  suspend fun open(pack: PackFiles): PackSourceResult

  /** What to tell the user before they tap Install: where the file comes from and where it has to be (read with `PackCopy.resolve`, ui/addons/PackText.kt). */
  fun howToAdd(def: PackDef): PackCopy
}

/** The sideload path: the pack's `.zip` (or unzipped folder) sits in the phone's Downloads folder. */
class DownloadsFolderSource(
  private val downloads: () -> File = { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) },
  private val hasAccess: () -> Boolean = { Environment.isExternalStorageManager() },
) : ModelPackSource {
  override suspend fun open(pack: PackFiles): PackSourceResult {
    if (!hasAccess()) return PackSourceResult.NeedsFileAccess
    val unreadable = mutableListOf<String>()
    val sources = findPackSources(downloads().listFiles()?.toList().orEmpty(), pack) { f, t -> unreadable += "${f.name}: ${t.message ?: t}" }
    return when {
      sources.isNotEmpty() -> PackSourceResult.Found(sources)
      unreadable.isNotEmpty() -> PackSourceResult.Unreadable(unreadable.joinToString("; "))
      else -> PackSourceResult.NotFound
    }
  }

  override fun howToAdd(def: PackDef): PackCopy =
    PackCopy(R.string.pack_how_to_add_downloads, listOf(def.archiveName, def.sizeCopy))
}

/**
 * TODO(Play): the store-ready path. Declare the pack as an on-demand Play Asset Delivery asset pack named after the
 * [PackDef.id], add `com.google.android.play:asset-delivery`, then in [open]:
 *   1. `AssetPackManager.getPackLocation(name)`; if null, `fetch(listOf(name))` and wait for `COMPLETED`
 *      (surface `AssetPackState.bytesDownloaded()` through the same progress callback the installer uses).
 *   2. return `PackSourceResult.Found(listOf(FolderModelSource(File(location.assetsPath()))))`.
 * The checked installer then verifies and copies the files exactly as it does for Downloads. This build is sideload
 * only, so the stub says so. It adds no dependency and no permission.
 */
class PlayAssetPackSource : ModelPackSource {
  override suspend fun open(pack: PackFiles): PackSourceResult =
    PackSourceResult.Unavailable("Play Asset Delivery is not wired up in this build")

  override fun howToAdd(def: PackDef): PackCopy =
    PackCopy(R.string.pack_how_to_add_play, listOf(def.nameCopy, def.sizeCopy))
}

/** Remembers choices the user made about packs, so nothing is installed behind their back. */
interface PackChoices {
  /** True after "Not now" or Remove: a pack file found in Downloads is left alone until the user taps Add. */
  fun declined(id: String): Boolean
  fun setDeclined(id: String, declined: Boolean)
}

class PackPrefs(context: Context) : PackChoices {
  private val prefs = context.applicationContext.getSharedPreferences("findr_packs", Context.MODE_PRIVATE)
  override fun declined(id: String): Boolean = prefs.getBoolean("declined_$id", false)
  override fun setDeclined(id: String, declined: Boolean) = prefs.edit().putBoolean("declined_$id", declined).apply()
}
