package trawl.desktop.shell

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * What the person chose about the shell: the quick-search shortcut and whether Trawl keeps running with its window closed. Kept in its own
 * `shell.json` beside settings.json so this feature adds a file instead of changing Settings.
 */
data class ShellPrefs(
  /** The quick-search shortcut is on (default). */
  val hotkeyOn: Boolean = true,
  /** The person's own shortcut as a [Hotkey.spec], or null: the OS default, falling back if it is taken. */
  val hotkey: String? = null,
  /** Closing the window hides it and Trawl keeps indexing from the tray (default). Off: closing the window quits. */
  val keepRunning: Boolean = true,
  /** The "your files are read" notification was already shown (or was not needed) once. */
  val firstIndexNotified: Boolean = false,
)

class ShellPrefsStore(private val dir: File) {
  private val file = File(dir, "shell.json")

  fun load(): ShellPrefs = try {
    val o = JSONObject(file.readText())
    ShellPrefs(
      hotkeyOn = o.optBoolean("hotkeyOn", true),
      hotkey = o.optString("hotkey").takeIf { it.isNotBlank() && Hotkey.parse(it) != null },
      keepRunning = o.optBoolean("keepRunning", true),
      firstIndexNotified = o.optBoolean("firstIndexNotified", false),
    )
  } catch (_: Exception) {
    ShellPrefs()
  }

  fun save(p: ShellPrefs) {
    dir.mkdirs()
    val part = File(dir, "shell.json.part")
    part.writeText(
      JSONObject().put("hotkeyOn", p.hotkeyOn).put("hotkey", p.hotkey ?: "").put("keepRunning", p.keepRunning)
        .put("firstIndexNotified", p.firstIndexNotified).toString(2),
    )
    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}
