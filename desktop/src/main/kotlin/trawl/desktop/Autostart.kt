package trawl.desktop

import java.io.File

/**
 * "Start Trawl when I sign in", off unless the person switches it on. Each system has its own per-person place for it and none needs
 * administrator rights: a LaunchAgent on macOS, the current user's Run key on Windows, an autostart entry on Linux. The file contents and
 * the commands are pure builders (tested on any machine); only [apply] touches the system. Nothing here goes online.
 */
object Autostart {
  /** The installed launcher's path, or null when Trawl is running from a plain `java` (a developer's run), where nothing sensible can be registered. */
  fun launcher(command: String? = ProcessHandle.current().info().command().orElse(null)): File? {
    val f = command?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
    val name = f.name.lowercase().removeSuffix(".exe")
    if (name == "java" || name == "javaw" || name == "gradlew" || !f.isFile) return null
    return f
  }

  const val LABEL = "ai.craneailabs.trawl.autostart"

  fun macPlist(launcher: String): String =
    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>${xml(launcher)}</string></array>
  <key>RunAtLoad</key><true/>
</dict>
</plist>
"""

  fun linuxDesktopEntry(launcher: String): String =
    """[Desktop Entry]
Type=Application
Name=Trawl
Comment=Search your own files by meaning, on this computer.
Exec=${desktopExec(launcher)}
Terminal=false
X-GNOME-Autostart-enabled=true
"""

  /** `reg add` for the current user's Run key: the value is the quoted path, so a folder with spaces still starts. */
  fun windowsAdd(launcher: String): List<String> =
    listOf("reg", "add", RUN_KEY, "/v", "Trawl", "/t", "REG_SZ", "/d", "\"$launcher\"", "/f")

  fun windowsRemove(): List<String> = listOf("reg", "delete", RUN_KEY, "/v", "Trawl", "/f")
  fun windowsQuery(): List<String> = listOf("reg", "query", RUN_KEY, "/v", "Trawl")

  private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

  /** Where the macOS and Linux entries go. */
  fun entryFile(os: Os, home: File = File(System.getProperty("user.home")), env: Map<String, String> = System.getenv()): File? = when (os) {
    Os.MAC -> File(home, "Library/LaunchAgents/$LABEL.plist")
    Os.LINUX -> File(env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: "${home.path}/.config", "autostart/trawl.desktop")
    Os.WINDOWS -> null
  }

  /** Whether the entry is in place right now: read from the system, not remembered, so it stays true if the person removes it by hand. */
  fun isOn(os: Os = Platform.os, home: File = File(System.getProperty("user.home")), env: Map<String, String> = System.getenv(), run: (List<String>) -> Int = ::exec): Boolean =
    if (os == Os.WINDOWS) run(windowsQuery()) == 0 else entryFile(os, home, env)?.isFile == true

  /** Switches it on or off. Returns true when the system now matches [on]. */
  fun apply(
    on: Boolean, launcher: File?, os: Os = Platform.os, home: File = File(System.getProperty("user.home")),
    env: Map<String, String> = System.getenv(), run: (List<String>) -> Int = ::exec,
  ): Boolean = runCatching {
    if (os == Os.WINDOWS) {
      if (on) { val l = launcher ?: return false; run(windowsAdd(l.absolutePath)) == 0 } else { run(windowsRemove()); true }
    } else {
      val f = entryFile(os, home, env) ?: return false
      if (on) {
        val l = launcher ?: return false
        f.parentFile.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        part.writeText(if (os == Os.MAC) macPlist(l.absolutePath) else linuxDesktopEntry(l.absolutePath))
        java.nio.file.Files.move(part.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
      } else { f.delete(); !f.exists() }
    }
  }.getOrDefault(false)

  private fun exec(cmd: List<String>): Int = try {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    p.inputStream.readBytes()
    p.waitFor()
  } catch (_: Exception) { -1 }

  private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  /** The Exec= value: quoted when it holds a space or another character the format reserves, with quotes and backslashes escaped. */
  private fun desktopExec(path: String): String {
    val special = " \t\n\"'\\><~|&;\$*?#()`"
    if (path.none { it in special }) return path
    return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("\$", "\\\$") + "\""
  }
}
