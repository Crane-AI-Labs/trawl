package trawl.desktop

import com.craneai.findr.diag.Redaction
import com.craneai.findr.ui.common.formatBytes

/** Facts about the app itself that the About section and the problem report print. */
object AppInfo {
  /** Kept in step with `packageVersion` in desktop/build.gradle.kts (a test compares them). */
  const val VERSION = "1.0.0"
  const val MAKER = "Crane AI Labs"
  const val MODEL = "EmbeddingGemma 2"
}

/**
 * The Java runtime the app is running on, for the About and Licences screens and the problem report. The installers bundle a pinned Temurin
 * 21 runtime ([MIN_SAFE] or newer, enforced by the build: `checkRuntimeJdk` in desktop/build.gradle.kts). Temurin 21.0.0 to 21.0.7 aborted the
 * whole app after about an hour and a half of use (a C1 compiler assertion on Apple silicon, JDK-8310844, JDK-8320682 and JDK-8335662;
 * see internal/research/desktop-network-and-jvm.md), so the version is printed where a person can read it back to us.
 */
object RuntimeInfo {
  /** The oldest runtime that holds all three AArch64 C1 fixes (JDK-8335662 reached Temurin 21 in 21.0.8). Keep in step with MIN_SAFE_JDK in build.gradle.kts. */
  const val MIN_SAFE = "21.0.8"

  /** "21.0.12.1+1-LTS" and the like, exactly as the runtime reports it. */
  val version: String get() = System.getProperty("java.runtime.version") ?: System.getProperty("java.version") ?: "unknown"
  val vendor: String get() = System.getProperty("java.vendor") ?: "unknown vendor"

  /** "Eclipse Adoptium 21.0.12.1+1-LTS", the one line the screens show. */
  val display: String get() = "$vendor $version"

  /** The numbers of a version string, padded to four: "21.0.12.1+1-LTS" is 21, 0, 12, 1; "21+35" is 21, 0, 0, 0. */
  fun numbers(v: String): List<Int> {
    val head = v.trim().takeWhile { it.isDigit() || it == '.' }
    val parts = head.split('.').mapNotNull { it.toIntOrNull() }
    return (parts + List(4) { 0 }).take(4)
  }

  /** True when [v] is [min] or newer. An unreadable version is not safe. */
  fun atLeast(v: String, min: String = MIN_SAFE): Boolean {
    if (numbers(v).all { it == 0 }) return false
    val a = numbers(v); val b = numbers(min)
    for (i in 0 until 4) if (a[i] != b[i]) return a[i] > b[i]
    return true
  }

  val isSafe: Boolean get() = atLeast(version)
}

/**
 * The short plain-text report behind "Copy diagnostics", and the rules that keep it clean. It holds the app and system versions,
 * the machine's size, the engines in use, counts and sizes, and a few error lines. It never holds a file name, a folder name, a
 * path or any text from a file; nothing here is sent anywhere (the person pastes it themselves).
 */
object Diagnostics {
  data class Facts(
    val version: String = AppInfo.VERSION,
    val os: String,
    val osVersion: String,
    val arch: String,
    val cores: Int,
    val ramMb: Long,
    val ocr: String,
    val vision: String?,
    val textThreads: Int?,
    val speed: String,
    val language: String,
    val folders: Int,
    val skippedFolders: Int,
    val photosOn: Boolean,
    val screenshotsOn: Boolean,
    val documentsOn: Boolean,
    val photos: Int,
    val screenshots: Int,
    val documents: Int,
    val unreadable: Int,
    val indexBytes: Long,
    val modelBytes: Long,
    val ratePerMin: Double?,
    val models: String?,
    val errors: List<String>,
    val videosOn: Boolean = true,
    val audioOn: Boolean = true,
    val videos: Int = 0,
    val recordings: Int = 0,
    /** What reads videos and sound here ("ffmpeg", "avfoundation", "media-foundation", "none"), null before the first check. */
    val decoder: String? = null,
    val speechPack: Boolean = false,
    /** The Java runtime ([RuntimeInfo.display]); it is part of the report so a crash can be matched to its runtime. */
    val runtime: String = RuntimeInfo.display,
  )

  /** "8 cores, 31.0 GB memory"; a memory size that could not be read says "memory unknown", never "0.0 GB". */
  fun machine(cores: Int, ramMb: Long): String {
    val memory = if (ramMb > 0) "${"%.1f".format(java.util.Locale.ROOT, ramMb / 1024.0)} GB memory" else "memory unknown"
    return "${count(cores, "core", "cores")}, $memory"
  }

  /** "1 photo", "2 photos": the report is English only, so it uses English's one/other rule. */
  private fun count(n: Int, one: String, other: String) = "$n ${Plurals.english(n.toLong(), one, other)}"

  fun report(f: Facts): String = buildString {
    fun onOff(b: Boolean) = if (b) "on" else "off"
    appendLine("Trawl diagnostics")
    appendLine("Version: ${f.version}")
    appendLine("System: ${f.os} ${f.osVersion} (${f.arch})")
    appendLine("Processor: ${machine(f.cores, f.ramMb)}")
    appendLine("Java runtime: ${f.runtime}")
    appendLine("Models: ${f.models ?: "not installed"}")
    appendLine("Text reading: CPU, ${f.textThreads?.let { "$it threads" } ?: "default threads"}")
    appendLine("Picture engine: ${f.vision ?: "not started"}")
    appendLine("Text in pictures: ${f.ocr}")
    appendLine("Work speed: ${f.speed}")
    appendLine("Language: ${f.language}")
    appendLine("Folders: ${f.folders} searched, ${f.skippedFolders} skipped")
    appendLine("Reads: photos ${onOff(f.photosOn)}, screenshots ${onOff(f.screenshotsOn)}, documents ${onOff(f.documentsOn)}, videos ${onOff(f.videosOn)}, recordings ${onOff(f.audioOn)}")
    appendLine(
      "Files known: ${count(f.photos, "photo", "photos")}, ${count(f.screenshots, "screenshot", "screenshots")}, " +
        "${count(f.documents, "document or PDF", "documents and PDFs")}, ${f.unreadable} unreadable",
    )
    appendLine("Videos and recordings known: ${count(f.videos, "video", "videos")}, ${count(f.recordings, "recording", "recordings")}; decoder ${f.decoder ?: "not checked yet"}, speech pack ${if (f.speechPack) "installed" else "not installed"}")
    appendLine("Index size: ${formatBytes(f.indexBytes)}; models: ${formatBytes(f.modelBytes)}")
    appendLine("Last reading rate: ${f.ratePerMin?.let { "%.0f files a minute".format(java.util.Locale.ROOT, it) } ?: "not measured yet"}")
    if (f.errors.isEmpty()) {
      appendLine("Recent errors: none")
    } else {
      appendLine("Recent errors (oldest first):")
      f.errors.forEach { appendLine("  $it") }
    }
    append("No file names, paths or file contents are in this report, and nothing was sent anywhere.")
  }

  // ---- making a log line safe ------------------------------------------------------------------------------------

  /** The words the app's own log lines are built from. A label keeps only these, so nothing a person named can pass as one. */
  private val VOCABULARY = setOf(
    "failed", "a", "document", "photo", "screenshot", "pdf", "image", "file", "index", "worker", "model", "ocr", "scan", "walk", "of",
    "settings", "not", "saved", "engine", "speed", "did", "restart", "dropped", "files", "that", "are", "gone", "watch", "watcher",
    "could", "read", "open", "reconcile", "forget", "work", "threads", "gpu", "cpu", "probe", "battery", "power", "log", "licences",
    "was", "cut", "short", "nothing", "deleted", "is", "there", "keeping", "what", "known", "about", "it", "stopped", "polling",
    "inotify", "limit", "ready", "init", "vision", "unreadable", "no", "reader", "damaged", "media", "video", "audio", "sound",
    "recording", "decoder", "speech", "pack",
  )

  /** Class names that may appear in a report: real exception classes from the JDK and Kotlin, found by name. Anything else is `<error>`. */
  private val PACKAGES = listOf("java.lang.", "java.io.", "java.nio.file.", "java.util.", "java.util.zip.", "java.sql.", "java.net.", "kotlin.")
  private val CLASS_NAME = Regex("""(?<![\w.])(?:[a-z][a-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*(?:Exception|Error))(?![\w.])""")

  private fun knownClass(name: String): Boolean = PACKAGES.any { p -> runCatching { Class.forName(p + name) }.isSuccess }

  /** Phrases from the operating system that explain a failure and name nothing. */
  private val OS_PHRASES = listOf("No space left on device", "Permission denied", "Access is denied", "Out of memory", "Too many open files", "Read-only file system")

  /**
   * The words that mark a problem. "unreadable" is not one: a walk that could not read a folder logs "walk: N folders unreadable
   * (...); nothing under them is deleted" on purpose (counts and kinds of place, never names), which is the deletion guard doing its
   * job (an unreadable folder must not make its files look deleted), not a failure. It stays in trawl.log for support, and out of the
   * report's "recent errors". (Older versions wrote "walk was cut short; nothing deleted" for the same thing, for every walk.)
   */
  private val ERROR_WORDS = Regex("""(?i)\b(fail|failed|error|exception|cannot|could not|not saved|refused|did not|crash)""")

  /** `~` for the person's home folder, and everything after it on the line dropped (a folder name can hold spaces and be anything). */
  fun redact(text: String, home: String): String {
    var cut = -1
    val variants = listOf(home, home.replace('\\', '/'), home.replace('/', '\\')).filter { it.length > 1 }.distinct()
    for (v in variants) {
      val i = text.indexOf(v, ignoreCase = true)
      if (i >= 0 && (cut < 0 || i < cut)) cut = i
    }
    val kept = if (cut >= 0) text.substring(0, cut) + "~" else text
    return Redaction.scrub(kept)
  }

  /** One log line made safe to keep and to put in a report. See [AppLog]. */
  fun safeLine(raw: String, home: String): String {
    val line = raw.replace('\n', ' ').replace('\r', ' ')
    val classes = CLASS_NAME.findAll(line).map { it.groupValues[1] }.toList()
    if (classes.isEmpty()) return redact(line, home).take(200)
    // An error line: the code's own words, the class of the error and, when the system gave one, a phrase that names no file.
    val label = StringBuilder()
    for (tok in line.split(' ').filter { it.isNotEmpty() }.take(6)) {
      val clean = tok.trimEnd(':')
      if (clean !in VOCABULARY) break
      if (label.isNotEmpty()) label.append(' ')
      label.append(clean)
      if (tok.endsWith(":")) break
    }
    val shown = classes.map { if (knownClass(it)) it else "<error>" }.distinct().joinToString(" ")
    val phrase = OS_PHRASES.firstOrNull { line.contains(it, ignoreCase = true) }
    return listOfNotNull(label.toString().ifEmpty { "error" } + ":", shown, phrase?.let { "($it)" }).joinToString(" ")
  }

  /** True for a line that reports a problem (an error class or the words for one). */
  fun isError(safe: String): Boolean = CLASS_NAME.containsMatchIn(safe) || ERROR_WORDS.containsMatchIn(safe)
}
