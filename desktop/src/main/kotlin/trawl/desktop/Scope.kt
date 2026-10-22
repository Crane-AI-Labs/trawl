package trawl.desktop

import com.craneai.findr.core.FileKind
import java.io.File
import java.nio.file.Path

/**
 * What Trawl reads: which kinds of file, and which folders inside the chosen ones are skipped. The kind rule is the phone's
 * (FindrEngine.enabledKinds, which lives in :app and so cannot be called from here) cut to what a computer reads: photos,
 * screenshots, documents with PDFs, videos and recordings. The skip rule is the phone's too (a skipped folder takes everything inside it with it),
 * but over absolute paths, because a computer has several chosen folders and no single storage root.
 */
object Scope {
  /** The kinds the person has switched on. */
  fun enabledKinds(s: Settings): Set<FileKind> = buildSet {
    if (s.indexPhotos) add(FileKind.IMAGE)
    if (s.indexScreenshots) add(FileKind.SCREENSHOT)
    if (s.indexDocuments) { add(FileKind.DOCUMENT); add(FileKind.PDF) }
    if (s.indexVideos) add(FileKind.VIDEO)
    if (s.indexAudio) add(FileKind.AUDIO)
  }

  fun paths(folders: List<String>): List<Path> = folders.map { File(it).toPath().toAbsolutePath().normalize() }

  /** True when [path] is one of the skipped folders or sits anywhere under one. */
  fun isSkipped(path: Path, skipped: List<Path>): Boolean {
    if (skipped.isEmpty()) return false
    val p = path.toAbsolutePath().normalize()
    return skipped.any { p.startsWith(it) }
  }

  /** Why a folder cannot be skipped, or null when it can. */
  enum class SkipProblem { NOT_INSIDE, IS_CHOSEN_FOLDER, NOT_A_FOLDER }

  /** A folder may be skipped when it exists and lies strictly inside one of the chosen folders (to stop reading a chosen folder itself, remove it). */
  fun skipProblem(pick: File, chosen: List<String>): SkipProblem? {
    if (!pick.isDirectory) return SkipProblem.NOT_A_FOLDER
    val p = pick.toPath().toAbsolutePath().normalize()
    val roots = paths(chosen)
    if (roots.any { it == p }) return SkipProblem.IS_CHOSEN_FOLDER
    if (roots.none { p.startsWith(it) }) return SkipProblem.NOT_INSIDE
    return null
  }

  /** [existing] with [add] in it, keeping the list free of entries that another entry already covers. */
  fun withSkipped(existing: List<String>, add: String): List<String> {
    val a = File(add).toPath().toAbsolutePath().normalize()
    val kept = paths(existing).filterNot { it.startsWith(a) }   // the new one covers these
    if (kept.any { a.startsWith(it) }) return kept.map { it.toString() }   // an existing one already covers the new one
    return (kept + listOf(a)).map { it.toString() }   // listOf: a Path is itself a list of its segments, and + would add those
  }

  /** The skipped folders that still lie inside a chosen folder: removing a chosen folder drops its skip entries with it. */
  fun pruneSkipped(skipped: List<String>, chosen: List<String>): List<String> {
    val roots = paths(chosen)
    return skipped.filter { s -> File(s).toPath().toAbsolutePath().normalize().let { p -> roots.any { p.startsWith(it) && p != it } } }
  }
}
