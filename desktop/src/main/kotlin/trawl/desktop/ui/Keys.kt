package trawl.desktop.ui

/**
 * How a shortcut is written on a key cap. Callers write it in words ("Cmd Shift C", "Enter", "Up Down"); on a Mac the words become the
 * symbols that sit on a Mac keyboard and in its menus, on Windows and Linux they stay as words. Pure, so it is tested without a window.
 */
object Keys {
  private val mac = mapOf(
    "Cmd" to "⌘", "Command" to "⌘", "Alt" to "⌥", "Opt" to "⌥", "Option" to "⌥", "Shift" to "⇧", "Ctrl" to "⌃",
    "Control" to "⌃", "Enter" to "⏎", "Return" to "⏎", "Esc" to "⎋", "Backspace" to "⌫", "Tab" to "⇥",
    "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
  )

  /** The words a screen reader says for a token that is written short. */
  private val spokenWords = mapOf("Cmd" to "Command", "Ctrl" to "Control", "Esc" to "Escape", "Opt" to "Option", "\u2318" to "Command", "\u21E7" to "Shift", "\u2325" to "Option", "\u2303" to "Control")

  /** The text drawn on the cap: on a Mac the symbols, joined without spaces as the Mac writes them ("⌘⇧C"); elsewhere words with spaces. */
  fun cap(text: String, isMac: Boolean): String {
    if (!isMac) return text
    val parts = text.split(' ').filter { it.isNotEmpty() }
    // Arrows between two words ("Up Down") stay a pair; modifiers and a key run together.
    val mapped = parts.map { mac[it] ?: it }
    val modifierRun = parts.size > 1 && parts.dropLast(1).all { it in modifiers }
    return if (modifierRun) mapped.joinToString("") else mapped.joinToString(" ")
  }

  /** What a screen reader says for the cap: always words ("Command Shift C"), on every system, never a symbol it may skip. */
  fun spoken(text: String): String = text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { spokenWords[it] ?: it }

  private val modifiers = setOf("\u2318", "Cmd", "Command", "Alt", "Opt", "Option", "Shift", "Ctrl", "Control")
}
