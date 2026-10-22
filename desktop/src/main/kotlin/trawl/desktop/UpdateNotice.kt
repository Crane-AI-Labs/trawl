package trawl.desktop

import com.craneai.findr.engine.InstalledAddons
import com.craneai.findr.engine.SignatureVerifier
import java.io.File

/** A newer signed version of an add-on that is already installed, waiting as a file on this computer. */
data class AddOnUpdate(val packId: String, val bundle: String, val installedVersion: Int, val newVersion: Int, val file: File)

/**
 * The update notice. Trawl never goes online, so "an update is available" can only mean one thing: a newer, correctly SIGNED add-on bundle
 * is sitting in Downloads (or another usual folder) for an add-on that is already installed. It is a notice with an Update button,
 * never an install by itself, and "Not now" is remembered per version so it does not nag.
 *
 * Applies only to signed add-on bundles, because only those carry a version: an add-on installed from its pinned folder or zip has none
 * (to replace it, remove it and add the new file). A file whose signature does not verify never produces a notice.
 */
object UpdateNotice {
  /**
   * @param dismissed true when "Not now" was already chosen for this add-on at this version
   * @return one update per add-on (the newest version found), in catalog order
   */
  fun find(
    modelsDir: File,
    folders: List<File>,
    catalog: List<AddOnDef> = AddOnCatalog.all,
    verifier: SignatureVerifier = SignatureVerifier(),
    dismissed: (packId: String, version: Int) -> Boolean = { _, _ -> false },
  ): List<AddOnUpdate> {
    val recorded = InstalledAddons.read(modelsDir)
    if (recorded.isEmpty()) return emptyList()
    val zips = folders.flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }?.toList().orEmpty() }
    val newest = HashMap<String, AddOnUpdate>()
    for (zip in zips) {
      val peek = SignedPeek.of(zip, verifier) ?: continue
      val m = peek.manifest ?: continue
      if (!peek.isAddon) continue
      val have = recorded[m.bundle] ?: continue
      if (m.version <= have) continue
      val def = catalog.firstOrNull { d -> d.files?.folderName?.let { it in peek.folders } == true } ?: continue
      if (dismissed(def.id, m.version)) continue
      val cur = newest[def.id]
      if (cur == null || m.version > cur.newVersion) newest[def.id] = AddOnUpdate(def.id, m.bundle, have, m.version, zip)
    }
    return catalog.mapNotNull { newest[it.id] }
  }
}
