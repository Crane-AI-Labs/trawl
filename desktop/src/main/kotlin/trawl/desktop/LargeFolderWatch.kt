package trawl.desktop

import com.craneai.findr.core.FileKind
import java.nio.file.Path

/**
 * A subfolder inside one of the person's chosen folders that grew unusually large during a scan
 * (e.g., a massive JSON/log/CSV data dump or a folder with thousands of indexable files).
 */
data class LargeFolderAlert(
  val path: String,
  val name: String,
  val fileCount: Int,
  val totalBytes: Long,
)

/**
 * Tracks per-subfolder file counts and document/text byte totals during a directory walk so Trawl can warn the user
 * once per folder and offer one-click exclusion.
 *
 * Rules:
 * - Only subfolders strictly inside [root] (never [root] itself — excluding a chosen root uses "Remove" in Settings).
 * - Triggers when a subfolder reaches [fileThreshold] indexable files (default 2,000) OR [docBytesThreshold] bytes
 *   of document/text/JSON/CSV/log files (default 500 MB) — avoiding false positives on a normal folder with two large videos.
 * - Reports the most specific (deepest) offending subfolder first, never double-alerting a parent solely because a child
 *   already triggered.
 */
class LargeFolderDetector(
  private val fileThreshold: Int = DEFAULT_FILE_THRESHOLD,
  private val docBytesThreshold: Long = DEFAULT_DOC_BYTES_THRESHOLD,
) {
  private class Bucket(var files: Int = 0, var docBytes: Long = 0L, var totalBytes: Long = 0L)

  private val direct = HashMap<Path, Bucket>()
  private val alerted = HashSet<Path>()

  /** Records one kept file found under [root]. Returns a [LargeFolderAlert] the moment a subfolder crosses a threshold, or null. */
  fun observe(root: Path, file: Found): LargeFolderAlert? {
    val normRoot = root.toAbsolutePath().normalize()
    val dir = file.path.toAbsolutePath().normalize().parent ?: return null
    if (dir == normRoot || !dir.startsWith(normRoot)) return null
    if (dir in alerted) return null
    val b = direct.getOrPut(dir) { Bucket() }
    b.files++
    b.totalBytes += file.size.coerceAtLeast(0L)
    if (file.kind == FileKind.DOCUMENT || file.kind == FileKind.PDF) {
      b.docBytes += file.size.coerceAtLeast(0L)
    }
    if (b.files >= fileThreshold || b.docBytes >= docBytesThreshold) {
      alerted.add(dir)
      return LargeFolderAlert(
        path = dir.toString(),
        name = dir.fileName?.toString()?.ifBlank { dir.toString() } ?: dir.toString(),
        fileCount = b.files,
        totalBytes = b.totalBytes,
      )
    }
    return null
  }

  /**
   * Final roll-up at the end of a walk: catches split multi-level dumps (e.g., `root/dump/part-01..part-50` where each
   * child has 100 files, totaling >= [fileThreshold] or >= [docBytesThreshold] under `root/dump`), while ignoring
   * subtrees that already triggered a more specific child alert.
   */
  fun finalizeSubtrees(root: Path): List<LargeFolderAlert> {
    val normRoot = root.toAbsolutePath().normalize()
    if (direct.isEmpty()) return emptyList()
    val rollup = HashMap<Path, Bucket>()
    for ((dir, bucket) in direct) {
      if (dir in alerted) continue
      var cur: Path? = dir
      while (cur != null && cur != normRoot && cur.startsWith(normRoot)) {
        val acc = rollup.getOrPut(cur) { Bucket() }
        acc.files += bucket.files
        acc.docBytes += bucket.docBytes
        acc.totalBytes += bucket.totalBytes
        cur = cur.parent
      }
    }
    // Prefer deepest (most specific) qualifying subfolders so we suggest excluding `datasets/big-dump`, not `datasets`.
    val qualifying = rollup.entries
      .filter { (dir, b) -> dir !in alerted && (b.files >= fileThreshold || b.docBytes >= docBytesThreshold) }
      .sortedByDescending { it.key.nameCount }
    val out = ArrayList<LargeFolderAlert>()
    for ((dir, b) in qualifying) {
      if (alerted.any { it.startsWith(dir) }) continue
      alerted.add(dir)
      out += LargeFolderAlert(
        path = dir.toString(),
        name = dir.fileName?.toString()?.ifBlank { dir.toString() } ?: dir.toString(),
        fileCount = b.files,
        totalBytes = b.totalBytes,
      )
    }
    return out
  }

  companion object {
    const val DEFAULT_FILE_THRESHOLD = 2_000
    const val DEFAULT_DOC_BYTES_THRESHOLD = 500L * 1024L * 1024L
  }
}

/**
 * Fires a lightweight local OS notification (macOS `osascript display notification` and Linux `notify-send` via argv —
 * zero shell interpolation, zero network, non-blocking off EDT). On Windows (and Linux without `notify-send`), returns
 * `false` so [trawl.desktop.shell.Shell] delivers a native system-tray toast notification via `TrayState.sendNotification`.
 * Silently skipped when `TRAWL_NO_OS_NOTIFY=1` (tests / headless).
 */
object OsNotifier {
  internal fun notifyCommand(os: Os, title: String, body: String): List<String>? = when (os) {
    Os.MAC -> listOf(
      "osascript",
      "-e", "on run argv",
      "-e", "display notification (item 1 of argv) with title (item 2 of argv)",
      "-e", "end run",
      "--",
      body,
      title,
    )
    Os.LINUX -> listOf(
      "notify-send",
      "--app-name=Trawl",
      "--",
      title,
      body,
    )
    Os.WINDOWS -> null
  }

  fun notify(title: String, body: String, os: Os = Platform.os): Boolean {
    if (!System.getenv("TRAWL_NO_OS_NOTIFY").isNullOrBlank()) return false
    val cmd = notifyCommand(os, title, body) ?: return false
    return runCatching {
      val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
      val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
      if (!finished) {
        proc.destroyForcibly()
        false
      } else {
        proc.exitValue() == 0
      }
    }.getOrDefault(false)
  }
}
