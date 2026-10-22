package trawl.desktop

import java.io.File

enum class Os { WINDOWS, LINUX, MAC }

/**
 * What differs between the three desktops, in one place: where the app keeps its data, which folders to offer first, and how a
 * file is opened or shown in a folder window. The command builders are pure (a list of words in, a list of words out) so every
 * OS's command is unit-tested on whatever machine the tests run on.
 */
object Platform {
  val os: Os = detect(System.getProperty("os.name") ?: "")

  fun detect(name: String): Os = when {
    name.startsWith("Windows", ignoreCase = true) -> Os.WINDOWS
    name.startsWith("Mac", ignoreCase = true) || name.startsWith("Darwin", ignoreCase = true) -> Os.MAC
    else -> Os.LINUX
  }

  /** Where the index, the models and the settings live. `TRAWL_HOME` overrides it (tests, demos, a portable copy). */
  fun dataDir(os: Os = this.os, env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): File {
    env["TRAWL_HOME"]?.takeIf { it.isNotBlank() }?.let { return File(it) }
    return when (os) {
      Os.WINDOWS -> File(env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() } ?: "$home\\AppData\\Local", "Trawl")
      Os.MAC -> File(home, "Library/Application Support/Trawl")
      Os.LINUX -> File(env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "trawl")
    }
  }

  /**
   * The person's own Pictures, Documents, Downloads, Videos and Music. Linux honours `~/.config/user-dirs.dirs` (a localised "Bilder" is still
   * the Pictures folder); the other two use the fixed English names every Windows and macOS account has.
   */
  fun userFolders(os: Os = this.os, home: File = File(System.getProperty("user.home")), userDirs: String? = readUserDirs(home)): UserFolders {
    fun xdg(key: String, fallback: String): File {
      val line = userDirs?.lineSequence()?.firstOrNull { it.trimStart().startsWith("XDG_${key}_DIR=") } ?: return File(home, fallback)
      val value = line.substringAfter('=').trim().trim('"').replace("\$HOME", home.path)
      return if (value.isBlank() || File(value) == home) File(home, fallback) else File(value)
    }
    return if (os == Os.LINUX) UserFolders(xdg("PICTURES", "Pictures"), xdg("DOCUMENTS", "Documents"), xdg("DOWNLOAD", "Downloads"), xdg("VIDEOS", "Videos"), xdg("MUSIC", "Music"))
    else UserFolders(File(home, "Pictures"), File(home, "Documents"), File(home, "Downloads"), File(home, if (os == Os.MAC) "Movies" else "Videos"), File(home, "Music"))
  }

  /**
   * The command that opens quick search, as typed in a system shortcut. jpackage names its launcher in `jpackage.app-path`
   * (the .deb installs only `/opt/trawl/bin/Trawl`, there is no `trawl` on the PATH), else the running executable if it is Trawl's own
   * launcher, else the .deb's usual place.
   */
  fun quickCommand(
    appPath: String? = System.getProperty("jpackage.app-path"),
    running: String? = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
  ): String {
    val path = appPath?.takeIf { it.isNotBlank() }
      ?: running?.takeIf { File(it).name.removeSuffix(".exe").equals("Trawl", ignoreCase = true) }
      ?: "/opt/trawl/bin/Trawl"
    return "$path --quick"
  }

  private fun readUserDirs(home: File): String? = runCatching { File(home, ".config/user-dirs.dirs").takeIf { it.isFile }?.readText() }.getOrNull()

  /** [videos] and [music] are where the first-run list finds the videos and recordings the Video and Audio boxes read (Movies on a Mac). */
  data class UserFolders(val pictures: File, val documents: File, val downloads: File, val videos: File? = null, val music: File? = null) {
    val all: List<File> get() = listOfNotNull(pictures, documents, downloads, videos, music)
  }

  /** The command that opens [path] with whatever app the system has set for it. */
  fun openCommand(path: String, os: Os = this.os): List<String> = when (os) {
    Os.WINDOWS -> listOf("rundll32.exe", "url.dll,FileProtocolHandler", path)
    Os.MAC -> listOf("open", path)
    Os.LINUX -> listOf("xdg-open", path)
  }

  /**
   * The command that shows [path] selected in a folder window. Windows and macOS select the file; Linux asks the file manager
   * over D-Bus (org.freedesktop.FileManager1, which Nautilus, Dolphin, Nemo, Thunar and PCManFM-Qt answer) and
   * [showFallbackCommand] opens the containing folder when no file manager answers.
   */
  fun showCommand(path: String, os: Os = this.os): List<String> = when (os) {
    Os.WINDOWS -> listOf("explorer.exe", "/select,$path")
    Os.MAC -> listOf("open", "-R", path)
    Os.LINUX -> listOf(
      "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1", "--type=method_call",
      "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
      "array:string:${File(path).toURI().toASCIIString().replaceFirst("file:/", "file:///")}", "string:",
    )
  }

  fun showFallbackCommand(path: String, os: Os = this.os): List<String> = openCommand(File(path).parent ?: path, os)

  /** Runs a command and reports whether it started and exited cleanly within [waitMs] (explorer.exe exits 1 even on success, so Windows only checks it started). */
  private fun run(cmd: List<String>, waitMs: Long = 4000, exitMatters: Boolean = true): Boolean = try {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    if (!exitMatters) true
    else if (p.waitFor(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) p.exitValue() == 0 else true   // still running: a viewer that stays open is fine
  } catch (_: Exception) {
    false
  }

  /** Opens a link (a `mailto:` one, for the report email) with the system's default app for it. False when nothing could be started. */
  fun openUri(uri: String): Boolean = run(openCommand(uri), exitMatters = os != Os.WINDOWS)

  fun open(file: File): Boolean = run(openCommand(file.absolutePath), exitMatters = os != Os.WINDOWS)

  fun showInFolder(file: File): Boolean {
    val ok = run(showCommand(file.absolutePath), exitMatters = os != Os.WINDOWS)
    return ok || (os == Os.LINUX && run(showFallbackCommand(file.absolutePath)))
  }

  private val ramMb: Long by lazy { readTotalRamMb(os) }

  /**
   * Physical memory in MB, for choosing the models bundle's class the way the phone does (MemoryProfile.choose). 0 when unknown.
   * Read once. The JDK's own bean is tried first; it lives in the `jdk.management` module, which a jlinked installer runtime only
   * has if the build names it (it did not, so every installed build said 0 and the failed cast was swallowed). When the bean is not
   * there the operating system is asked instead: /proc/meminfo, `sysctl hw.memsize` or the CIM class, each with a pure parser below.
   */
  fun totalRamMb(): Long = ramMb

  internal fun readTotalRamMb(os: Os): Long {
    val viaBean = runCatching {
      (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / (1024 * 1024)
    }.getOrDefault(0L)
    if (viaBean > 0) return viaBean
    return runCatching {
      when (os) {
        Os.LINUX -> parseMeminfoMb(File("/proc/meminfo").readText())
        Os.MAC -> parseBytesMb(output(listOf("/usr/sbin/sysctl", "-n", "hw.memsize")))
        Os.WINDOWS -> parseBytesMb(output(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory")))
      }
    }.getOrDefault(0L)
  }

  /** `MemTotal:       16314716 kB` from /proc/meminfo, in MB; 0 when the line is not there. */
  fun parseMeminfoMb(text: String): Long {
    val line = text.lineSequence().firstOrNull { it.startsWith("MemTotal:") } ?: return 0L
    return (Regex("""\d+""").find(line)?.value?.toLongOrNull() ?: 0L) / 1024
  }

  /** A command's output that is one whole number of bytes (sysctl, PowerShell), in MB; 0 when it is anything else. */
  fun parseBytesMb(text: String?): Long = (text?.trim()?.toLongOrNull() ?: 0L).coerceAtLeast(0L) / (1024 * 1024)

  private fun output(cmd: List<String>, waitMs: Long = 8000): String? {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    return if (p.waitFor(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS) && p.exitValue() == 0) text else { p.destroyForcibly(); null }
  }
}
