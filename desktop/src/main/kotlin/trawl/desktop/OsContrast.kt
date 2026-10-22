package trawl.desktop

import java.util.concurrent.TimeUnit

/**
 * Whether the computer is asking for high contrast, where an app can read that without a native library.
 *
 * - Windows: the AWT desktop property `win.highContrast.on` (the Settings > Accessibility > Contrast themes switch).
 * - macOS: Increase contrast (System Settings > Accessibility > Display), a preference of the `com.apple.universalaccess` domain.
 * - Linux (GNOME and the desktops that share its settings): `org.gnome.desktop.a11y.interface high-contrast`, or a GTK theme named
 *   HighContrast. KDE has no single switch for it, so there it reads as "no" and Settings > Contrast > High is the way.
 *
 * Reading is best effort and never throws: an unreadable answer is "no". The parsers are plain functions so they are tested without a desktop.
 * `TRAWL_CONTRAST=high` or `standard` overrides the lookup (screenshots, tests, and a machine that cannot be read).
 */
object OsContrast {
  /** `defaults read com.apple.universalaccess increaseContrast` prints 1 or 0 (and errors when the key was never set, which is "no"). */
  fun parseMac(out: String?): Boolean = out?.trim() == "1"

  /** `gsettings get org.gnome.desktop.a11y.interface high-contrast` prints true or false. */
  fun parseGsettings(out: String?): Boolean = out?.trim().equals("true", ignoreCase = true)

  /** A GTK theme name such as `HighContrast` or `HighContrastInverse`. */
  fun isHighContrastTheme(name: String?): Boolean = name?.trim()?.trim('\'', '"')?.contains("highcontrast", ignoreCase = true) == true

  /** The value to use for this computer right now; [env] and the probes are parameters so a test can drive every branch. */
  fun active(
    os: Os = Platform.os,
    env: (String) -> String? = System::getenv,
    windows: () -> Boolean = { java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on") == true },
    run: (List<String>) -> String? = ::runQuietly,
  ): Boolean {
    when (env("TRAWL_CONTRAST")?.lowercase()) { "high" -> return true; "standard" -> return false }
    return runCatching {
      when (os) {
        Os.WINDOWS -> windows()
        Os.MAC -> parseMac(run(listOf("defaults", "read", "com.apple.universalaccess", "increaseContrast")))
        Os.LINUX ->
          parseGsettings(run(listOf("gsettings", "get", "org.gnome.desktop.a11y.interface", "high-contrast"))) ||
            isHighContrastTheme(env("GTK_THEME")) ||
            isHighContrastTheme(run(listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme")))
      }
    }.getOrDefault(false)
  }

  /** Runs a short read-only command and returns what it printed, or null if it failed, was missing or took more than 2 seconds. */
  private fun runQuietly(cmd: List<String>): String? = runCatching {
    val p = ProcessBuilder(cmd).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
    if (p.exitValue() != 0) null else p.inputStream.bufferedReader().readText()
  }.getOrNull()
}
