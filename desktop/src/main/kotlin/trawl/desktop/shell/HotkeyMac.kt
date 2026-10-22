package trawl.desktop.shell

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * macOS: Carbon's RegisterEventHotKey, in a small Swift helper (native/hotkey-mac.swift) that the build compiles into the app, the same way
 * the Apple Vision OCR helper is. The helper registers one system-wide key and prints "press" when it is pressed. This API needs NO
 * Accessibility, Input Monitoring or Screen Recording permission (those are for event taps and global monitors, which Trawl does not use).
 * Why a helper and not JNA: the hotkey event arrives on the main thread's Carbon event loop, which the JVM does not run; a separate
 * process gives it a loop of its own, and when the app dies the helper's stdin closes and it exits too.
 *
 * What registration can and cannot tell us (measured, see internal/research/desktop-shell.md): a key held by THIS process is refused
 * ("taken", eventHotKeyExistsErr -9878), but a key another app registered is NOT reported; both registrations succeed. So the conflict
 * test on a Mac is the helper's list of enabled system shortcuts (com.apple.symbolichotkeys) plus the documented table in [Hotkey.knownOwner].
 */
class HotkeyMac(private val dir: File, private val helper: File? = null) : GlobalHotkey {
  private var proc: Process? = null
  private var out: PrintWriter? = null
  private val replies = LinkedBlockingQueue<String>()
  @Volatile private var onPressed: () -> Unit = {}

  private fun helperFile(): File? {
    helper?.let { return it.takeIf(File::canExecute) }
    val bytes = HotkeyMac::class.java.classLoader.getResourceAsStream("hotkey-mac")?.use { it.readBytes() } ?: return null
    val f = File(File(dir, "helpers").apply { mkdirs() }, "hotkey-mac")
    if (!f.isFile || f.length() != bytes.size.toLong()) f.writeBytes(bytes)
    f.setExecutable(true, true)
    return f
  }

  @Synchronized
  private fun ensureStarted(): Boolean {
    if (proc?.isAlive == true) return true
    val exe = helperFile() ?: return false
    val p = runCatching { ProcessBuilder(exe.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull() ?: return false
    proc = p
    out = PrintWriter(p.outputStream.bufferedWriter(), true)
    replies.clear()
    Thread({
      BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
        for (l in lines) if (l == "press") runCatching { onPressed() } else replies.put(l)
      }
    }, "trawl-hotkey-mac").apply { isDaemon = true }.start()
    return true
  }

  private fun ask(cmd: String, until: (String) -> Boolean = { true }): List<String>? {
    out?.println(cmd) ?: return null
    val got = ArrayList<String>()
    while (true) {
      val l = replies.poll(3, TimeUnit.SECONDS) ?: return null
      got += l
      if (until(l)) return got
    }
  }

  /** The system shortcuts the person has enabled, as (keyCode, carbonMods) pairs. */
  private fun systemHotkeys(): Set<Pair<Int, Int>> =
    ask("syshot") { it == "end" }.orEmpty().mapNotNull { l ->
      val w = l.split(' ')
      if (w.size == 3 && w[0] == "sys") (w[1].toIntOrNull() ?: return@mapNotNull null) to (w[2].toIntOrNull() ?: return@mapNotNull null) else null
    }.toSet()

  @Synchronized
  override fun register(h: Hotkey, onPressed: () -> Unit): HotkeyResult {
    if (!ensureStarted()) return HotkeyResult.Unsupported(false, "the macOS hotkey helper is not in this build")
    this.onPressed = onPressed
    val code = HotkeyCodes.macKeyCode(h.key)
    val mods = HotkeyCodes.macMods(h)
    if ((code to mods) in systemHotkeys()) return HotkeyResult.Taken("an enabled system shortcut")
    val r = ask("reg $code $mods")?.firstOrNull() ?: return HotkeyResult.Failed("the helper did not answer")
    return when {
      r == "ok" -> HotkeyResult.Ok
      r == "taken" -> HotkeyResult.Taken("already registered")
      else -> HotkeyResult.Failed(r)
    }
  }

  @Synchronized
  override fun unregister() {
    if (proc?.isAlive == true) ask("unreg")
  }

  @Synchronized
  override fun close() {
    runCatching { out?.println("quit") }
    proc?.let { p -> if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroy() }
    proc = null
  }
}
