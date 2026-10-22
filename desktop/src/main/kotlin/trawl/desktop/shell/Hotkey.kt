package trawl.desktop.shell

import trawl.desktop.Os

/** A modifier key. [META] is Command on a Mac, the Windows key on Windows and Super on Linux. */
enum class Mod(val token: String) { CTRL("ctrl"), ALT("alt"), SHIFT("shift"), META("meta") }

/**
 * A global shortcut: some modifiers and one key. Stored as a plain lowercase string ("ctrl+alt+space") in shell.json so the file reads
 * the same on every OS; shown as "Control+Shift+Space", "Ctrl+Alt+Space" and so on by [label]. The key is a letter, a digit, F1 to F12
 * or Space: the keys all three native layers (Carbon, user32, X11) can name without a keyboard-layout lookup.
 */
data class Hotkey(val mods: Set<Mod>, val key: String) {
  val spec: String get() = (Mod.entries.filter { it in mods }.map { it.token } + key.lowercase()).joinToString("+")

  /** What the person sees, in their OS's words. */
  fun label(os: Os): String = (Mod.entries.filter { it in mods }.map { modName(it, os) } + keyName(key)).joinToString("+")

  /**
   * A shortcut that would steal ordinary typing is refused: it needs Ctrl, Alt or Meta (Shift alone is just a capital letter), and a
   * bare F-key is what every app already uses.
   */
  val usable: Boolean get() = key in KEYS && mods.any { it == Mod.CTRL || it == Mod.ALT || it == Mod.META }

  companion object {
    val KEYS: List<String> = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } + (1..12).map { "F$it" } + "SPACE"

    fun parse(spec: String?): Hotkey? {
      if (spec.isNullOrBlank()) return null
      val parts = spec.split('+').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
      if (parts.size < 2) return null
      val key = parts.last().uppercase()
      val mods = parts.dropLast(1).map { p ->
        when (p) {
          "ctrl", "control" -> Mod.CTRL
          "alt", "option", "opt" -> Mod.ALT
          "shift" -> Mod.SHIFT
          "meta", "cmd", "command", "super", "win", "windows" -> Mod.META
          else -> return null
        }
      }.toSet()
      return Hotkey(mods, key).takeIf { it.usable }
    }

    private fun hk(vararg m: Mod) = Hotkey(m.toSet(), "SPACE")

    /**
     * The default, per OS. Chosen from the shortcuts each OS and the common launchers already own (table in internal/research/desktop-shell.md):
     *  - Mac: Control+Shift+Space. Nearly every other Space combination on a Mac has an owner: Option (Alfred, Raycast, ChatGPT, Claude;
     *    also types a non-breaking space), Control and Control+Option (input sources), Command+Option (Finder search), Control+Command
     *    (emoji), and Shift+Command+Space (1Password Quick Access, and Siri's "ask about the active window" in macOS 27). Carbon cannot
     *    see another app's hold on a key, so on a Mac the default has to be one nobody is likely to own; Control+Shift+Space is in none
     *    of Apple's lists and is only 1Password's Windows and Linux default.
     *  - Windows and Linux: Ctrl+Alt+Space. Alt+Space is the window menu and PowerToys Run / Copilot / KRunner; Win+Space and Ctrl+Space are
     *    input languages; Ctrl+Shift+Space is Word's non-breaking space. Ctrl+Alt+Space is "Alt+Space, plus Ctrl to make it ours".
     */
    fun default(os: Os): Hotkey = if (os == Os.MAC) hk(Mod.CTRL, Mod.SHIFT) else hk(Mod.CTRL, Mod.ALT)

    /** Tried in order when the default is held by another app. Only the default falls back; a shortcut the person chose is never swapped. */
    fun alternates(os: Os): List<Hotkey> =
      if (os == Os.MAC) listOf(hk(Mod.CTRL, Mod.ALT, Mod.SHIFT), hk(Mod.META, Mod.ALT, Mod.SHIFT))
      else listOf(hk(Mod.CTRL, Mod.ALT, Mod.SHIFT), hk(Mod.CTRL, Mod.ALT, Mod.META))

    /** The default followed by its fallbacks: the whole list [HotkeyChooser] walks. */
    fun candidates(os: Os): List<Hotkey> = listOf(default(os)) + alternates(os)

    /**
     * Shortcuts the OS or a mainstream launcher is known to own, from Apple's and Microsoft's shortcut pages, the GNOME/KDE defaults and the
     * launchers' own docs. Returns who owns it, or null. This is advice shown before registering; the registration itself is the real test.
     * A [Owner.hard] owner is the system itself (Spotlight, the emoji viewer, the Windows key): refused. A soft owner is an app that may
     * not even be installed (Alfred, PowerToys): allowed, with a warning, if the registration succeeds.
     */
    fun knownOwner(h: Hotkey, os: Os): Owner? = KNOWN.firstOrNull { it.os == os && it.hotkey == h }?.owner

    data class Owner(val name: String, val hard: Boolean)
    private data class Known(val os: Os, val hotkey: Hotkey, val owner: Owner)

    private val KNOWN: List<Known> = buildList {
      fun add(os: Os, owner: String, hard: Boolean, key: String, vararg m: Mod) = add(Known(os, Hotkey(m.toSet(), key), Owner(owner, hard)))
      add(Os.MAC, "Spotlight", true, "SPACE", Mod.META)
      add(Os.MAC, "Finder search (Spotlight)", true, "SPACE", Mod.META, Mod.ALT)
      add(Os.MAC, "the emoji and symbols viewer", true, "SPACE", Mod.CTRL, Mod.META)
      add(Os.MAC, "switching the input source", false, "SPACE", Mod.CTRL)
      add(Os.MAC, "switching the input source", false, "SPACE", Mod.CTRL, Mod.ALT)
      add(Os.MAC, "Alfred, Raycast, ChatGPT or Claude", false, "SPACE", Mod.ALT)
      add(Os.MAC, "1Password Quick Access (and Siri on macOS 27)", false, "SPACE", Mod.META, Mod.SHIFT)
      add(Os.MAC, "full screen", true, "F", Mod.CTRL, Mod.META)
      add(Os.WINDOWS, "the window menu, PowerToys Run and Copilot", false, "SPACE", Mod.ALT)
      add(Os.WINDOWS, "the Chinese input method", false, "SPACE", Mod.CTRL)
      add(Os.WINDOWS, "switching the input language", true, "SPACE", Mod.META)
      add(Os.WINDOWS, "switching the input language", true, "SPACE", Mod.META, Mod.SHIFT)
      add(Os.WINDOWS, "Word's non-breaking space", false, "SPACE", Mod.CTRL, Mod.SHIFT)
      add(Os.LINUX, "the window menu and KDE's KRunner", false, "SPACE", Mod.ALT)
      add(Os.LINUX, "switching the input source", false, "SPACE", Mod.META)
      add(Os.LINUX, "the non-breaking space in many editors", false, "SPACE", Mod.CTRL, Mod.SHIFT)
    }

    private fun modName(m: Mod, os: Os) = when (m) {
      Mod.CTRL -> if (os == Os.MAC) "Control" else "Ctrl"
      Mod.ALT -> if (os == Os.MAC) "Option" else "Alt"
      Mod.SHIFT -> "Shift"
      Mod.META -> when (os) { Os.MAC -> "Command"; Os.WINDOWS -> "Windows"; Os.LINUX -> "Super" }
    }

    private fun keyName(k: String) = if (k == "SPACE") "Space" else k
  }
}

/** The numbers each OS's own API wants for a [Hotkey]. Pure tables, so they are unit-tested on any machine. */
object HotkeyCodes {
  // ---- Windows (user32 RegisterHotKey) ----
  const val WIN_ALT = 0x1; const val WIN_CTRL = 0x2; const val WIN_SHIFT = 0x4; const val WIN_META = 0x8; const val WIN_NOREPEAT = 0x4000

  fun winMods(h: Hotkey): Int = (if (Mod.ALT in h.mods) WIN_ALT else 0) or (if (Mod.CTRL in h.mods) WIN_CTRL else 0) or
    (if (Mod.SHIFT in h.mods) WIN_SHIFT else 0) or (if (Mod.META in h.mods) WIN_META else 0) or WIN_NOREPEAT

  /** Virtual-key codes: letters and digits are their ASCII capitals, F1 is 0x70, Space 0x20. */
  fun winVk(key: String): Int = when {
    key == "SPACE" -> 0x20
    key.length == 1 -> key[0].code
    key.startsWith("F") -> 0x70 + key.drop(1).toInt() - 1
    else -> error("no key $key")
  }

  // ---- X11 (XGrabKey) ----
  const val X_SHIFT = 1; const val X_LOCK = 2; const val X_CONTROL = 4; const val X_MOD1 = 8; const val X_MOD2 = 0x10; const val X_MOD4 = 0x40

  /** Alt is Mod1 and the Windows/Super key Mod4 on every mainstream X11 setup. */
  fun xMods(h: Hotkey): Int = (if (Mod.ALT in h.mods) X_MOD1 else 0) or (if (Mod.CTRL in h.mods) X_CONTROL else 0) or
    (if (Mod.SHIFT in h.mods) X_SHIFT else 0) or (if (Mod.META in h.mods) X_MOD4 else 0)

  /** The grab must also be made with Caps Lock and Num Lock on, or the shortcut dies whenever one is lit. */
  fun xLockVariants(): List<Int> = listOf(0, X_LOCK, X_MOD2, X_LOCK or X_MOD2)

  /** X keysyms: lowercase ASCII for letters, ASCII digits, 0x20 Space, F1 = 0xFFBE. */
  fun xKeysym(key: String): Long = when {
    key == "SPACE" -> 0x20L
    key.length == 1 -> if (key[0] in 'A'..'Z') key[0].lowercaseChar().code.toLong() else key[0].code.toLong()
    key.startsWith("F") -> 0xFFBEL + key.drop(1).toInt() - 1
    else -> error("no key $key")
  }

  // ---- macOS (Carbon RegisterEventHotKey) ----
  const val MAC_CMD = 256; const val MAC_SHIFT = 512; const val MAC_OPTION = 2048; const val MAC_CONTROL = 4096

  fun macMods(h: Hotkey): Int = (if (Mod.META in h.mods) MAC_CMD else 0) or (if (Mod.SHIFT in h.mods) MAC_SHIFT else 0) or
    (if (Mod.ALT in h.mods) MAC_OPTION else 0) or (if (Mod.CTRL in h.mods) MAC_CONTROL else 0)

  private val MAC_LETTERS = mapOf(
    'A' to 0, 'S' to 1, 'D' to 2, 'F' to 3, 'H' to 4, 'G' to 5, 'Z' to 6, 'X' to 7, 'C' to 8, 'V' to 9, 'B' to 11, 'Q' to 12, 'W' to 13, 'E' to 14,
    'R' to 15, 'Y' to 16, 'T' to 17, 'O' to 31, 'U' to 32, 'I' to 34, 'P' to 35, 'L' to 37, 'J' to 38, 'K' to 40, 'N' to 45, 'M' to 46,
  )
  private val MAC_DIGITS = mapOf('1' to 18, '2' to 19, '3' to 20, '4' to 21, '6' to 22, '5' to 23, '9' to 25, '7' to 26, '8' to 28, '0' to 29)
  private val MAC_FKEYS = listOf(122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111)

  /** ANSI virtual key codes (Carbon's kVK_ANSI_*; Space 49; F1..F12). */
  fun macKeyCode(key: String): Int = when {
    key == "SPACE" -> 49
    key.length == 1 -> MAC_LETTERS[key[0]] ?: MAC_DIGITS[key[0]] ?: error("no key $key")
    key.startsWith("F") -> MAC_FKEYS[key.drop(1).toInt() - 1]
    else -> error("no key $key")
  }
}
