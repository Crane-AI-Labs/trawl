package com.craneai.findr.index

/**
 * What the person has said is private: folders they marked, and single files they marked. These are the only things that
 * ever set `files.sensitive` (there is no classifier, the research notes).
 *
 * They are kept in the app's settings, NOT in the index. The index is a cache that "Delete index" and a corruption
 * rebuild both throw away; if the marks lived there, rebuilding would quietly make private files findable again.
 * The store takes the rules from the engine and stamps them onto rows as the crawler writes them.
 *
 * @param privateFolders relative folders ("DCIM/Private"), matched like excluded folders: the folder and everything under it
 * @param markedFiles files marked one by one. Each file is remembered by two keys: [key] ("volume:mediastoreId", stable across a
 *   rebuild) and [pathKey] ("path:volume:folder/name", which survives MediaStore handing the same file a new id after a media
 *   rescan or a restore). A file matches on either. A rename carries the mark along ([learn]). Failing closed: a different file
 *   later saved at a marked path is hidden too, which costs one tap to undo.
 */
data class PrivacyRules(
  val privateFolders: Set<String> = emptySet(),
  val markedFiles: Set<String> = emptySet(),
) {
  /** The `files.sensitive` bits this file gets from the rules. */
  fun flagsFor(volume: String, mediaStoreId: Long, relativePath: String, name: String): Int =
    (if (isMarked(volume, mediaStoreId, relativePath, name)) Schema.Sensitive.MARKED else 0) or folderFlag(relativePath)

  fun isMarked(volume: String, mediaStoreId: Long, relativePath: String, name: String): Boolean =
    markedFiles.isNotEmpty() && (key(volume, mediaStoreId) in markedFiles || pathKey(volume, relativePath, name) in markedFiles)

  /**
   * Called as the crawler writes a row. When the file is marked (by either key) both of its keys are made current: a file
   * found again under a new MediaStore id gains the new id key, and a renamed or moved one trades its old path key for the
   * new one ([oldRelativePath]/[oldName] when it was already known). Returns this when nothing changed.
   */
  fun learn(
    volume: String, mediaStoreId: Long, relativePath: String, name: String,
    oldRelativePath: String? = null, oldName: String? = null,
  ): PrivacyRules {
    if (!isMarked(volume, mediaStoreId, relativePath, name)) return this
    var keys = markedFiles
    if (oldRelativePath != null && oldName != null) keys = keys - pathKey(volume, oldRelativePath, oldName)
    keys = keys + key(volume, mediaStoreId) + pathKey(volume, relativePath, name)
    return if (keys == markedFiles) this else copy(markedFiles = keys)
  }

  /** Marks one file: both keys. */
  fun withMark(volume: String, mediaStoreId: Long, relativePath: String, name: String): PrivacyRules =
    copy(markedFiles = markedFiles + key(volume, mediaStoreId) + pathKey(volume, relativePath, name))

  /** Unmarks one file: both keys. */
  fun withoutMark(volume: String, mediaStoreId: Long, relativePath: String, name: String): PrivacyRules =
    copy(markedFiles = markedFiles - key(volume, mediaStoreId) - pathKey(volume, relativePath, name))

  /** [Schema.Sensitive.FOLDER] when [relativePath] is a private folder or sits under one, else 0. */
  fun folderFlag(relativePath: String): Int {
    if (privateFolders.isEmpty()) return 0
    val here = normalize(relativePath)
    for (folder in privateFolders) {
      val p = normalize(folder)
      if (p.isNotEmpty() && (here == p || here.startsWith("$p/"))) return Schema.Sensitive.FOLDER
    }
    return 0
  }

  companion object {
    val NONE = PrivacyRules()

    fun key(volume: String, mediaStoreId: Long) = "$volume:$mediaStoreId"

    /** The identity that outlives a MediaStore id: where the file lives and what it is called. */
    fun pathKey(volume: String, relativePath: String, name: String) = "path:$volume:${normalize(relativePath)}/${name.lowercase()}"

    fun normalize(folder: String) = folder.trim('/').lowercase()
  }
}
