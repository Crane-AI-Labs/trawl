package trawl.desktop.shell

import trawl.desktop.Os
import trawl.desktop.Platform
import trawl.desktop.t

/** Turns what the recorder in Settings hears (an AWT key code plus the modifier flags) into a [Hotkey], and what the check says into words. */
object RecorderKeys {
  const val VK_ESCAPE = 27
  private const val VK_SPACE = 0x20
  private const val VK_0 = 0x30
  private const val VK_9 = 0x39
  private const val VK_A = 0x41
  private const val VK_Z = 0x5A
  private const val VK_F1 = 0x70
  private const val VK_F12 = 0x7B

  /** The key's name in [Hotkey.KEYS], or null for keys a shortcut cannot use (arrows, Tab, Enter, the modifiers themselves, punctuation). */
  fun keyName(awtCode: Int): String? = when (awtCode) {
    VK_SPACE -> "SPACE"
    in VK_0..VK_9 -> awtCode.toChar().toString()
    in VK_A..VK_Z -> awtCode.toChar().toString()
    in VK_F1..VK_F12 -> "F${awtCode - VK_F1 + 1}"
    else -> null
  }

  /** The combination just pressed, or null while only modifiers (or an unusable key) are down. It may still be unusable: the check says why. */
  fun fromEvent(awtCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): Hotkey? {
    val key = keyName(awtCode) ?: return null
    val mods = buildSet {
      if (ctrl) add(Mod.CTRL)
      if (alt) add(Mod.ALT)
      if (shift) add(Mod.SHIFT)
      if (meta) add(Mod.META)
    }
    return Hotkey(mods, key)
  }
}

/** What to tell the person about a shortcut they tried, in their language. */
object ShortcutMessages {
  fun metaName(os: Os) = when (os) { Os.MAC -> t("shell_key_meta_mac"); Os.WINDOWS -> t("shell_key_meta_win"); Os.LINUX -> t("shell_key_meta_other") }

  /** The line under the recorder after a try. [good] is true when the shortcut now works. */
  fun forCheck(c: ShortcutCheck, os: Os): Pair<String, Boolean> = when (c) {
    is ShortcutCheck.Ok ->
      (if (c.warning != null) t("shell_hotkey_soft", c.hotkey.label(os), c.warning) else t("shell_hotkey_active", c.hotkey.label(os))) to true
    ShortcutCheck.NeedModifier -> t("shell_hotkey_need_modifier", metaName(os)) to false
    is ShortcutCheck.Owned -> t("shell_hotkey_owned", c.hotkey.label(os), c.by) to false
    is ShortcutCheck.Taken -> t("shell_hotkey_taken", c.hotkey.label(os)) to false
    is ShortcutCheck.Failed -> t("shell_hotkey_failed", c.detail) to false
  }

  /** The standing line about where the shortcut is now. [good] false draws it as a warning. */
  fun forStatus(s: HotkeyStatus, os: Os, quickCommand: String = Platform.quickCommand()): Pair<String, Boolean> = when (s) {
    HotkeyStatus.Off -> t("shell_hotkey_off") to true
    is HotkeyStatus.Active -> when {
      s.fellBackFrom != null -> t("shell_hotkey_fallback", s.fellBackFrom.label(os), s.hotkey.label(os)) to true
      s.warning != null -> t("shell_hotkey_soft", s.hotkey.label(os), s.warning) to true
      else -> t("shell_hotkey_active", s.hotkey.label(os)) to true
    }
    HotkeyStatus.AllTaken -> t("shell_hotkey_all_taken") to false
    is HotkeyStatus.Taken -> t("shell_hotkey_taken", s.hotkey.label(os)) to false
    HotkeyStatus.Wayland -> t("shell_hotkey_wayland", quickCommand) to false
    is HotkeyStatus.Portal -> if (s.trigger != null) t("shell_hotkey_portal", s.trigger) to true else t("shell_hotkey_portal_none", quickCommand) to false
    is HotkeyStatus.Failed -> t("shell_hotkey_failed", s.detail) to false
  }
}
