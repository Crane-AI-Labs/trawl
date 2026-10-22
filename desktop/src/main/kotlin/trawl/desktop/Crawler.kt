package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.Rules
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/** One file the walk found and the shared rules keep. */
class Found(val path: Path, val rel: String, val name: String, val kind: FileKind, val size: Long, val modified: Long, val taken: Long = 0) {
  val id: Long get() = Crawler.idOf(path)
  /** [taken] (the photo's EXIF DateTimeOriginal) is the date shown and sorted on; 0 keeps the modified time, as for every other file. */
  fun record() = CrawlRecord(Crawler.VOLUME, id, path.toUri().toString(), rel, name, kind, null, size, modified, dateMillis = taken)
}

/** Why a folder could not be read: the system said no (macOS privacy, or the folder's own permissions), or it failed some other way. */
enum class Block { DENIED, OTHER }

/** A folder (or file) the walk could not look at. Everything under it is unknown: nothing may be concluded from what is missing there. */
class UnreadablePlace(val path: Path, val block: Block)

/**
 * How a walk ended. [unreadable] are the places the system would not let it look at, [pending] the folders it was asked to stop before
 * (it carries on from them later; see `stop` in [Crawler.walk]), and [vanished] how many files or folders were gone by the time it got to
 * them (a download that was renamed, a lock file: they are simply gone, which says nothing about the folder around them).
 * [complete] means everything was seen; otherwise only the subtrees named in [unreadable] and [pending] are unknown, and the rest of what
 * the walk saw is as good as from a complete walk.
 */
class WalkResult(val unreadable: List<UnreadablePlace> = emptyList(), val pending: List<Path> = emptyList(), val vanished: Int = 0) {
  val complete: Boolean get() = unreadable.isEmpty() && pending.isEmpty()
}

/**
 * The directory walk shared by the first scan, the watcher's reconcile and the polling fallback. java.nio only (no Spotlight,
 * no MediaStore): the same [Rules.classify] the phone uses decides what a file is. Hidden files and folders are never read.
 * Only the kinds the desktop reads are kept: documents, PDFs, photos, screenshots, videos and recordings.
 */
object Crawler {
  const val VOLUME = "desktop"
  val KINDS = setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO, FileKind.AUDIO)
  /** The kinds that go through a decoder and the speech engine (read a window at a time, in their own queue). */
  val MEDIA_KINDS = setOf(FileKind.VIDEO, FileKind.AUDIO)

  /** A stable 63-bit id per path: the desktop's stand-in for MediaStore's row id. */
  fun idOf(path: Path): Long = fnv64(path.toAbsolutePath().normalize().toString())

  fun fnv64(s: String): Long {
    var h = -3750763034362895579L
    for (b in s.toByteArray()) { h = h xor (b.toLong() and 0xff); h *= 1099511628211L }
    return h and Long.MAX_VALUE
  }

  fun isHidden(p: Path): Boolean {
    val n = p.fileName?.toString() ?: return false
    if (n.startsWith(".") || n.startsWith("$")) return true
    return try { Files.isHidden(p) } catch (_: IOException) { false }
  }

  /** The folder part of a path, relative to its root, with a trailing slash ("Trips/2024/"), as the phone stores relative paths. */
  fun relOf(root: Path, file: Path): String =
    root.relativize(file).parent?.toString()?.replace('\\', '/')?.let { "$it/" } ?: ""

  /** True when [dir] is inside a hidden folder of [root] (a ".git", a ".cache"): such a folder is never read, whatever happens inside it. */
  fun insideHidden(root: Path, dir: Path): Boolean {
    if (!dir.startsWith(root) || dir == root) return false
    var p: Path = root
    for (seg in root.relativize(dir)) { p = p.resolve(seg); if (isHidden(p)) return true }
    return false
  }

  /**
   * Why [e] stopped the walk from looking at something, or null when the thing is simply gone: a file listed a moment ago and renamed
   * or deleted before it was looked at is no longer there, which is not a problem. Anything else is a place the walk could not see.
   */
  internal fun why(e: IOException): Block? = when {
    e is NoSuchFileException || e is FileNotFoundException || e is NotDirectoryException -> null
    e is AccessDeniedException -> Block.DENIED
    // macOS says EPERM ("Operation not permitted") for a folder its privacy settings keep from this app; Java turns that into a plain FileSystemException.
    e is FileSystemException && e.reason?.let { it.contains("not permitted", true) || it.contains("permission denied", true) } == true -> Block.DENIED
    else -> Block.OTHER
  }

  /** The capture date of a JPEG photo (EXIF DateTimeOriginal), 0 for anything else or when it has none (B14). */
  internal fun takenOf(file: Path, name: String, kind: FileKind): Long {
    if (kind != FileKind.IMAGE) return 0
    val n = name.lowercase()
    if (!n.endsWith(".jpg") && !n.endsWith(".jpeg")) return 0
    return Exif.taken(file.toFile()) ?: 0
  }

  /**
   * Walks [dir] (all the way down when [recursive], else its own files only) and hands each kept file to [onFound]. [root] is the
   * chosen folder the relative paths are taken from. A folder that cannot be read is recorded in the result and the walk goes on around
   * it; a file that disappears while the walk is on is not a failure. A folder in [skipped] (the person's "skipped folders") is not
   * entered, with everything inside it, so nothing under it is ever recorded.
   *
   * [stop] is asked before each folder is entered (never before [dir] itself, so every call makes progress). When it says yes the folder
   * is not entered but listed in [WalkResult.pending]; walking those again later continues where this one stopped instead of starting over.
   */
  fun walk(root: Path, dir: Path, recursive: Boolean, skipped: List<Path> = emptyList(), stop: () -> Boolean = { false }, onFound: (Found) -> Unit): WalkResult {
    val unreadable = ArrayList<UnreadablePlace>()
    val pending = ArrayList<Path>()
    var vanished = 0
    Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption::class.java), if (recursive) Int.MAX_VALUE else 1, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
        if ((d != dir && isHidden(d)) || Scope.isSkipped(d, skipped)) return FileVisitResult.SKIP_SUBTREE
        if (d != dir && stop()) { pending.add(d); return FileVisitResult.SKIP_SUBTREE }
        return FileVisitResult.CONTINUE
      }

      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (!attrs.isRegularFile || isHidden(file)) return FileVisitResult.CONTINUE
        val rel = relOf(root, file)
        val name = file.fileName.toString()
        val kind = DocFormats.classify(file, name, rel)   // hook (Package C): Rules.classify plus first-bytes sniffing and the .log type, as on the phone
        if (kind in KINDS) onFound(Found(file.toAbsolutePath().normalize(), rel, name, kind, attrs.size(), attrs.lastModifiedTime().toMillis(), takenOf(file, name, kind)))
        return FileVisitResult.CONTINUE
      }

      override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
        val w = why(exc)
        if (w == null) vanished++ else unreadable += UnreadablePlace(file, w)
        return FileVisitResult.CONTINUE
      }

      // A folder that listed but failed part-way (a drive pulled, a share dropping): the names before the failure were seen, the rest were not.
      override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
        if (exc != null) { val w = why(exc); if (w == null) vanished++ else unreadable += UnreadablePlace(d, w) }
        return FileVisitResult.CONTINUE
      }
    })
    return WalkResult(unreadable, pending, vanished)
  }
}
