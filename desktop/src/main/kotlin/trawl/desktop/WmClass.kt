package trawl.desktop

import java.awt.Toolkit
import java.lang.reflect.Field

/**
 * The window class Linux desktops group Trawl's windows by (X11's WM_CLASS; on Wayland the app id XWayland derives from it).
 *
 * Java names it after the main class, `trawl-desktop-MainKt`, while the .deb's launcher entry is `trawl-Trawl.desktop` and jpackage writes
 * no StartupWMClass into it, so the dock and Alt-Tab showed a generic icon, apart from the launcher (matrix, Ubuntu 24.04). GNOME and the
 * other shells also pair a window with the entry whose file name equals its class, ignoring case, so naming the class after the entry is
 * enough, without touching the .deb. The class name is a private static field of the X11 toolkit; the desktop's start-up flags open that
 * package (build.gradle.kts), and a run without them just keeps Java's own name.
 */
object WmClass {
  /** The .desktop file jpackage makes for the .deb is `<package>-<app>.desktop`, here `trawl-Trawl.desktop`. */
  const val NAME = "trawl-Trawl"

  /** Names the class before the first window opens. True when it was set; false off Linux, with no X11 toolkit, or without the opened package. */
  fun apply(os: Os = Platform.os, field: () -> Field? = ::toolkitField, name: String = NAME, startToolkit: () -> Unit = { Toolkit.getDefaultToolkit() }): Boolean {
    if (os != Os.LINUX) return false
    return runCatching {
      // The toolkit's constructor writes its own name (from the main class) into the field, so the toolkit has to exist first; the class
      // is only read when a window is created, which comes after this.
      startToolkit()
      val f = field() ?: return false
      f.isAccessible = true
      f.set(null, name)
      true
    }.getOrDefault(false)
  }

  private fun toolkitField(): Field? = runCatching { Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName") }.getOrNull()
}
