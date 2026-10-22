package trawl.desktop.ui

import trawl.desktop.Screen

/** The keys the window cares about; everything else is [OTHER] (a letter is [CHAR] and carries its character). */
enum class K { UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, ENTER, SPACE, ESCAPE, BACKSPACE, TAB, LETTER_K, LETTER_F, LETTER_C, LETTER_A, COMMA, PLUS, MINUS, ZERO, MENU, F10, CHAR, OTHER }

/** A key press stripped of Compose types, so the whole shortcut table is tested without a window. */
data class Keystroke(
  val key: K,
  /** The character typed, for [K.CHAR] and the letters; 0 for keys that type nothing. */
  val char: Int = 0,
  val ctrl: Boolean = false,
  val meta: Boolean = false,
  val shift: Boolean = false,
  val alt: Boolean = false,
)

/** Where keyboard focus is: the search field, a result (the list), or something else (a button, a chip). */
enum class FocusZone { SEARCH, LIST, OTHER }

/** What the window knows when a key arrives. */
data class KeyContext(
  val screen: Screen,
  val zone: FocusZone,
  val quickLook: Boolean,
  val queryBlank: Boolean,
  val hasResults: Boolean,
  val isMac: Boolean,
  /** The selected result is a photo tile, so Left and Right move between tiles. */
  val selectionInGrid: Boolean = false,
  /** The search field's caret is at the start or end of its text, where Left or Right has nothing left to move. */
  val caretAtStart: Boolean = true,
  val caretAtEnd: Boolean = true,
  // ---- SEARCH PACKAGE (A) ----
  /** Several results are chosen together (Esc lets go of them before it does anything else). */
  val hasPicked: Boolean = false,
  /** Some text is selected in the search field, so Cmd/Ctrl C copies that text and not the chosen files. */
  val textSelected: Boolean = false,
)

sealed interface KeyAction {
  data object FocusSearch : KeyAction
  data class Move(val dir: Dir) : KeyAction
  data object Home : KeyAction
  data object End : KeyAction
  data class Page(val down: Boolean) : KeyAction
  /** Quick Look's previous or next result, straight through reading order. */
  data class Step(val delta: Int) : KeyAction
  data object Open : KeyAction
  data object ShowInFolder : KeyAction
  data object CopyPath : KeyAction
  // ---- SEARCH PACKAGE (A) ----
  /** Copy the chosen files themselves (so they can be pasted into a message or a folder). */
  data object CopyFiles : KeyAction
  data object SelectAll : KeyAction
  /** Shift+arrow: widen the choice from the anchor one step. */
  data class Extend(val dir: Dir) : KeyAction
  /** Let go of a multiple choice and keep the one result with the cursor. */
  data object ClearPicked : KeyAction
  /** The Menu key or Shift+F10: the same menu a right-click opens, on the selected result. */
  data object ContextMenu : KeyAction
  data object ToggleQuickLook : KeyAction
  data object CloseQuickLook : KeyAction
  data object ClearQuery : KeyAction
  data object Back : KeyAction
  data object OpenSettings : KeyAction
  /** Text size one step bigger (+1), smaller (-1) or back to Default (0). */
  data class Zoom(val step: Int) : KeyAction
  /** A typed character (or a Backspace, [char] = 8) that arrived while focus was not in the search field. */
  data class TypeIntoSearch(val char: Int) : KeyAction
}

/**
 * The shortcut table. [map] returns the action for a key, or null when the key is not ours and must go on to whatever has focus (a text
 * field's own caret keys, a button's Enter and Space). The command key is Cmd on a Mac and Ctrl elsewhere.
 */
object KeyMap {
  fun map(k: Keystroke, ctx: KeyContext): KeyAction? {
    val mod = if (ctx.isMac) k.meta else k.ctrl
    val otherMod = if (ctx.isMac) k.ctrl else k.meta
    if (otherMod) return null

    // Text size: Cmd/Ctrl + Plus, Minus and 0 work on every screen (and in the search field: a command key types nothing).
    if (mod && !k.alt) {
      when (k.key) {
        K.PLUS -> return KeyAction.Zoom(1)
        K.MINUS -> return KeyAction.Zoom(-1)
        K.ZERO -> return KeyAction.Zoom(0)
        else -> {}
      }
    }

    if (ctx.screen != Screen.MAIN) {
      return if (k.key == K.ESCAPE && !mod && (ctx.screen == Screen.SETTINGS || ctx.screen == Screen.LICENCES)) KeyAction.Back else null
    }

    if (mod) {
      return when {
        k.key == K.LETTER_K || k.key == K.LETTER_F -> if (!k.shift) KeyAction.FocusSearch else null
        k.key == K.COMMA -> KeyAction.OpenSettings
        k.key == K.ENTER -> if (ctx.hasResults) KeyAction.ShowInFolder else null
        k.key == K.LETTER_C && k.shift -> if (ctx.hasResults) KeyAction.CopyPath else null
        // ---- SEARCH PACKAGE (A): in the search field the text's own copy and select-all win ----
        k.key == K.LETTER_C && !k.alt -> when {
          !ctx.hasResults -> null
          ctx.zone == FocusZone.SEARCH && ctx.textSelected -> null
          else -> KeyAction.CopyFiles
        }
        k.key == K.LETTER_A && !k.shift -> if (ctx.hasResults && ctx.zone == FocusZone.LIST) KeyAction.SelectAll else null
        else -> null
      }
    }

    if (ctx.quickLook) {
      return when (k.key) {
        K.ESCAPE, K.SPACE -> KeyAction.CloseQuickLook
        K.ENTER -> KeyAction.Open
        K.UP, K.LEFT -> KeyAction.Step(-1)
        K.DOWN, K.RIGHT -> KeyAction.Step(1)
        else -> null
      }
    }

    val search = ctx.zone == FocusZone.SEARCH
    // ---- SEARCH PACKAGE (A): the menu key, and Shift to widen a choice ----
    if (k.key == K.MENU || (k.key == K.F10 && k.shift)) return if (ctx.hasResults) KeyAction.ContextMenu else null
    if (k.shift && ctx.hasResults) {
      when (k.key) {
        K.UP -> return KeyAction.Extend(Dir.UP)
        K.DOWN -> return KeyAction.Extend(Dir.DOWN)
        // Left and Right belong to the text in the search field; in the list (a photo row) they widen the choice
        K.LEFT -> if (ctx.zone == FocusZone.LIST && ctx.selectionInGrid) return KeyAction.Extend(Dir.LEFT)
        K.RIGHT -> if (ctx.zone == FocusZone.LIST && ctx.selectionInGrid) return KeyAction.Extend(Dir.RIGHT)
        else -> {}
      }
    }
    return when (k.key) {
      K.UP -> if (ctx.hasResults) KeyAction.Move(Dir.UP) else null
      K.DOWN -> if (ctx.hasResults) KeyAction.Move(Dir.DOWN) else null
      K.LEFT -> when {
        !ctx.hasResults || !ctx.selectionInGrid -> null
        !search || ctx.caretAtStart -> KeyAction.Move(Dir.LEFT)
        else -> null
      }
      K.RIGHT -> when {
        !ctx.hasResults || !ctx.selectionInGrid -> null
        !search || ctx.caretAtEnd -> KeyAction.Move(Dir.RIGHT)
        else -> null
      }
      K.HOME -> if (!search && ctx.hasResults) KeyAction.Home else null
      K.END -> if (!search && ctx.hasResults) KeyAction.End else null
      K.PAGE_UP -> if (ctx.hasResults) KeyAction.Page(down = false) else null
      K.PAGE_DOWN -> if (ctx.hasResults) KeyAction.Page(down = true) else null
      K.ENTER -> if (ctx.zone != FocusZone.OTHER && ctx.hasResults) KeyAction.Open else null
      K.SPACE -> when {
        !ctx.hasResults -> null
        ctx.zone == FocusZone.LIST -> KeyAction.ToggleQuickLook
        search && ctx.queryBlank -> KeyAction.ToggleQuickLook
        else -> null   // a space in a query is a space; on a button it is a click
      }
      K.ESCAPE -> when {
        ctx.hasPicked -> KeyAction.ClearPicked
        search -> if (ctx.queryBlank) null else KeyAction.ClearQuery
        else -> KeyAction.FocusSearch
      }
      K.BACKSPACE -> if (ctx.zone == FocusZone.SEARCH) null else KeyAction.TypeIntoSearch(8)
      K.CHAR, K.LETTER_K, K.LETTER_F, K.LETTER_C, K.LETTER_A, K.COMMA, K.PLUS, K.MINUS, K.ZERO -> if (ctx.zone == FocusZone.SEARCH || k.char == 0) null else KeyAction.TypeIntoSearch(k.char)
      else -> null
    }
  }
}
