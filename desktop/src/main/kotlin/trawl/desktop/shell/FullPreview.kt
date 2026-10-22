package trawl.desktop.shell

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import trawl.desktop.Helpers
import trawl.desktop.Os
import trawl.desktop.Platform

/**
 * The system's own big preview of a file, offered next to the panel's in-app one: on a Mac it is Quick Look (the panel Finder shows on
 * Space), which previews any file type the Mac knows. Windows and Linux have no equivalent that ships with the system, so they get the
 * in-app preview only ([NoFullPreview]).
 */
interface FullPreview {
  /** This computer has one (so the preview shows its button and key). */
  val available: Boolean

  /** True while the system's preview is on screen. The panel must not close itself when it loses focus to it. */
  val open: StateFlow<Boolean>

  /** Shows [files] with the one at [index] first (the arrow keys step through the rest). False when nothing could be shown. */
  fun show(files: List<File>, index: Int): Boolean

  /** Closes it if it is open. */
  fun close()
}

object NoFullPreview : FullPreview {
  override val available = false
  override val open: StateFlow<Boolean> = MutableStateFlow(false)
  override fun show(files: List<File>, index: Int) = false
  override fun close() {}
}

/** A running Quick Look helper: the lines it prints, and the two ways to end it. A fake in tests. */
interface QuickLookProcess {
  /** Blocks until the helper prints the next line; null when it has exited. */
  fun readLine(): String?
  /** Closing the helper's input is how it is told to close the panel. */
  fun closeInput()
  fun destroy()
}

/** The arguments of `quicklook-apple show <index> <path>...`: only files that exist, the index following the file it pointed at. Pure. */
object QuickLookArgs {
  /** More than this and the command line is silly; the panel's list is shorter anyway. */
  const val MAX_FILES = 60

  fun build(files: List<File>, index: Int): List<String>? {
    val kept = ArrayList<File>()
    var at = 0
    for ((i, f) in files.withIndex()) {
      if (!f.isFile || kept.size >= MAX_FILES) continue
      if (i == index) at = kept.size
      kept += f
    }
    if (kept.isEmpty()) return null
    return listOf("show", at.toString()) + kept.map { it.absolutePath }
  }
}

/**
 * macOS Quick Look through `quicklook-apple` (native/quicklook-apple.swift): the helper opens QLPreviewPanel on the files and exits when the
 * panel is closed, printing "shown" then "closed". It is started on demand and runs beside Trawl, so it is the active app while its panel is
 * up; [open] is true for exactly that time. Trawl closes the helper's input to close the panel (the helper treats that as "close").
 */
class QuickLookMac(
  private val prepare: () -> List<String>,
  private val start: (List<String>) -> QuickLookProcess = ::startProcess,
) : FullPreview {
  override val available = true
  private val _open = MutableStateFlow(false)
  override val open: StateFlow<Boolean> = _open.asStateFlow()
  @Volatile private var proc: QuickLookProcess? = null

  @Synchronized override fun show(files: List<File>, index: Int): Boolean {
    val args = QuickLookArgs.build(files, index) ?: return false
    close()
    val p = try { start(prepare() + args) } catch (_: Throwable) { return false }
    proc = p
    _open.value = true
    Thread({
      try {
        while (true) { val line = p.readLine() ?: break; if (line == "closed" || line.startsWith("error:")) break }
      } finally {
        runCatching { p.destroy() }
        synchronized(this) { if (proc === p) { proc = null; _open.value = false } }
      }
    }, "trawl-quicklook").apply { isDaemon = true }.start()
    return true
  }

  @Synchronized override fun close() {
    val p = proc ?: return
    runCatching { p.closeInput() }
    // The helper closes the panel and exits on its own; the reader thread above then clears the state. A helper that does not go is killed.
    Thread({ Thread.sleep(1500); runCatching { p.destroy() } }, "trawl-quicklook-kill").apply { isDaemon = true }.start()
  }

  companion object {
    const val HELPER = "quicklook-apple"

    /** The facility for [os], or [NoFullPreview] where there is none (and on a Mac build made elsewhere, which lacks the helper). */
    fun forOs(
      os: Os,
      dataDir: () -> File = { Platform.dataDir(os) },
      hasResource: (String) -> Boolean = { QuickLookMac::class.java.classLoader.getResource(it) != null },
    ): FullPreview = when {
      os == Os.MAC && hasResource(HELPER) ->
        QuickLookMac({ listOf(Helpers.extract(HELPER, File(dataDir(), "helpers")).also { it.setExecutable(true) }.absolutePath) })
      else -> NoFullPreview
    }

    private fun startProcess(cmd: List<String>): QuickLookProcess {
      val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      val out = p.inputStream.bufferedReader()
      return object : QuickLookProcess {
        override fun readLine(): String? = runCatching { out.readLine() }.getOrNull()
        override fun closeInput() { runCatching { p.outputStream.close() } }
        override fun destroy() { p.destroy() }
      }
    }
  }
}
