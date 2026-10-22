package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import trawl.desktop.ui.Dir
import trawl.desktop.ui.FocusZone
import trawl.desktop.ui.K
import trawl.desktop.ui.KeyAction
import trawl.desktop.ui.KeyContext
import trawl.desktop.ui.KeyMap
import trawl.desktop.ui.Keystroke

class KeyMapTest {
  private fun ctx(
    screen: Screen = Screen.MAIN, zone: FocusZone = FocusZone.SEARCH, quickLook: Boolean = false, queryBlank: Boolean = false,
    hasResults: Boolean = true, isMac: Boolean = false, inGrid: Boolean = false, atStart: Boolean = true, atEnd: Boolean = true,
    picked: Boolean = false, textSelected: Boolean = false,
  ) = KeyContext(screen, zone, quickLook, queryBlank, hasResults, isMac, inGrid, atStart, atEnd, picked, textSelected)

  private fun key(k: K, char: Int = 0, ctrl: Boolean = false, meta: Boolean = false, shift: Boolean = false) = Keystroke(k, char, ctrl, meta, shift)

  private fun map(k: Keystroke, c: KeyContext = ctx()) = KeyMap.map(k, c)

  @Test fun theCommandKeyIsCmdOnAMacAndCtrlElsewhere() {
    assertEquals(KeyAction.FocusSearch, map(key(K.LETTER_K, 'k'.code, ctrl = true), ctx(isMac = false)))
    assertNull(map(key(K.LETTER_K, 'k'.code, meta = true), ctx(isMac = false)))
    assertEquals(KeyAction.FocusSearch, map(key(K.LETTER_K, 'k'.code, meta = true), ctx(isMac = true)))
    assertNull(map(key(K.LETTER_K, 'k'.code, ctrl = true), ctx(isMac = true)), "Ctrl K on a Mac is the system's (kill line), not ours")
  }

  @Test fun focusSearchOnKAndFButNotShifted() {
    for (k in listOf(K.LETTER_K, K.LETTER_F)) assertEquals(KeyAction.FocusSearch, map(key(k, ctrl = true), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.LETTER_F, ctrl = true, shift = true)))
  }

  @Test fun commandCommaOpensSettingsAndCommandEnterShowsTheFolder() {
    assertEquals(KeyAction.OpenSettings, map(key(K.COMMA, ctrl = true)))
    assertEquals(KeyAction.ShowInFolder, map(key(K.ENTER, ctrl = true)))
    assertNull(map(key(K.ENTER, ctrl = true), ctx(hasResults = false)))
    assertEquals(KeyAction.CopyPath, map(key(K.LETTER_C, ctrl = true, shift = true)))
    assertNull(map(key(K.LETTER_C, ctrl = true), ctx(textSelected = true)), "plain Ctrl C stays a copy of selected text")
  }

  // ---- SEARCH PACKAGE (A) ----
  @Test fun commandCCopiesTheFilesUnlessTextIsSelectedInTheField() {
    assertEquals(KeyAction.CopyFiles, map(key(K.LETTER_C, ctrl = true), ctx(zone = FocusZone.SEARCH, textSelected = false)))
    assertEquals(KeyAction.CopyFiles, map(key(K.LETTER_C, ctrl = true), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.CopyFiles, map(key(K.LETTER_C, ctrl = true), ctx(zone = FocusZone.LIST, textSelected = true)), "a selection in the field matters only in the field")
    assertNull(map(key(K.LETTER_C, ctrl = true), ctx(hasResults = false)))
    assertEquals(KeyAction.CopyFiles, map(key(K.LETTER_C, meta = true), ctx(isMac = true)))
    assertNull(map(key(K.LETTER_C, ctrl = true), ctx(isMac = true)), "Ctrl C on a Mac is the system's")
    assertEquals(KeyAction.CopyPath, map(key(K.LETTER_C, ctrl = true, shift = true)), "the path copy keeps Shift")
  }

  @Test fun commandAChoosesEveryResultOnlyOnTheList() {
    assertEquals(KeyAction.SelectAll, map(key(K.LETTER_A, ctrl = true), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.LETTER_A, ctrl = true), ctx(zone = FocusZone.SEARCH)), "select-all of the field's text stays the field's")
    assertNull(map(key(K.LETTER_A, ctrl = true), ctx(zone = FocusZone.LIST, hasResults = false)))
    assertEquals(KeyAction.TypeIntoSearch('a'.code), map(key(K.LETTER_A, 'a'.code), ctx(zone = FocusZone.LIST)), "a bare A is a letter")
  }

  @Test fun shiftArrowsWidenTheChoice() {
    assertEquals(KeyAction.Extend(Dir.DOWN), map(key(K.DOWN, shift = true), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.Extend(Dir.UP), map(key(K.UP, shift = true), ctx(zone = FocusZone.SEARCH)))
    assertNull(map(key(K.DOWN, shift = true), ctx(hasResults = false)))
    assertEquals(KeyAction.Extend(Dir.RIGHT), map(key(K.RIGHT, shift = true), ctx(zone = FocusZone.LIST, inGrid = true)))
    assertNull(map(key(K.RIGHT, shift = true), ctx(zone = FocusZone.LIST, inGrid = false)))
    assertNull(map(key(K.LEFT, shift = true), ctx(zone = FocusZone.SEARCH, inGrid = true, atStart = false)), "Shift+Left in the field selects text")
  }

  @Test fun escapeLetsGoOfAMultipleChoiceBeforeAnythingElse() {
    assertEquals(KeyAction.ClearPicked, map(key(K.ESCAPE), ctx(zone = FocusZone.LIST, picked = true)))
    assertEquals(KeyAction.ClearPicked, map(key(K.ESCAPE), ctx(zone = FocusZone.SEARCH, queryBlank = false, picked = true)))
    assertEquals(KeyAction.ClearQuery, map(key(K.ESCAPE), ctx(zone = FocusZone.SEARCH, queryBlank = false, picked = false)))
  }

  @Test fun theMenuKeyAndShiftF10OpenTheResultMenu() {
    assertEquals(KeyAction.ContextMenu, map(key(K.MENU), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.ContextMenu, map(key(K.F10, shift = true), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.F10), ctx(zone = FocusZone.LIST)), "F10 alone is not ours")
    assertNull(map(key(K.MENU), ctx(hasResults = false)))
  }

  @Test fun arrowsMoveTheSelectionOnlyWhenThereAreResults() {
    assertEquals(KeyAction.Move(Dir.DOWN), map(key(K.DOWN)))
    assertEquals(KeyAction.Move(Dir.UP), map(key(K.UP), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.DOWN), ctx(hasResults = false)))
  }

  @Test fun leftAndRightMoveBetweenTilesButNeverStealTheCaret() {
    assertNull(map(key(K.LEFT), ctx(inGrid = false)), "in a list the caret keeps them")
    assertEquals(KeyAction.Move(Dir.LEFT), map(key(K.LEFT), ctx(inGrid = true, atStart = true)))
    assertNull(map(key(K.LEFT), ctx(inGrid = true, atStart = false)), "mid-text the caret moves")
    assertNull(map(key(K.RIGHT), ctx(inGrid = true, atEnd = false)))
    assertEquals(KeyAction.Move(Dir.RIGHT), map(key(K.RIGHT), ctx(inGrid = true, atEnd = true)))
    assertEquals(KeyAction.Move(Dir.RIGHT), map(key(K.RIGHT), ctx(zone = FocusZone.LIST, inGrid = true, atEnd = false)), "caret does not matter off the field")
  }

  @Test fun homeAndEndBelongToTheFieldWhileTyping() {
    assertNull(map(key(K.HOME)))
    assertEquals(KeyAction.Home, map(key(K.HOME), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.End, map(key(K.END), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.Page(down = true), map(key(K.PAGE_DOWN)))
    assertEquals(KeyAction.Page(down = false), map(key(K.PAGE_UP)))
  }

  @Test fun enterOpensFromTheFieldOrTheListButClicksAButton() {
    assertEquals(KeyAction.Open, map(key(K.ENTER)))
    assertEquals(KeyAction.Open, map(key(K.ENTER), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.ENTER), ctx(zone = FocusZone.OTHER)))
    assertNull(map(key(K.ENTER), ctx(hasResults = false)))
  }

  @Test fun spaceIsQuickLookOnTheListOrAnEmptyFieldAndATypedSpaceOtherwise() {
    assertEquals(KeyAction.ToggleQuickLook, map(key(K.SPACE), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.ToggleQuickLook, map(key(K.SPACE), ctx(zone = FocusZone.SEARCH, queryBlank = true)))
    assertNull(map(key(K.SPACE), ctx(zone = FocusZone.SEARCH, queryBlank = false)))
    assertNull(map(key(K.SPACE), ctx(zone = FocusZone.OTHER)))
    assertNull(map(key(K.SPACE), ctx(zone = FocusZone.LIST, hasResults = false)))
  }

  @Test fun escapeClearsThenFocusesThenDoesNothing() {
    assertEquals(KeyAction.ClearQuery, map(key(K.ESCAPE), ctx(zone = FocusZone.SEARCH, queryBlank = false)))
    assertNull(map(key(K.ESCAPE), ctx(zone = FocusZone.SEARCH, queryBlank = true)))
    assertEquals(KeyAction.FocusSearch, map(key(K.ESCAPE), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.FocusSearch, map(key(K.ESCAPE), ctx(zone = FocusZone.OTHER)))
  }

  @Test fun quickLookHasItsOwnKeysAndSwallowsTheRest() {
    val q = ctx(quickLook = true, zone = FocusZone.LIST)
    assertEquals(KeyAction.CloseQuickLook, map(key(K.ESCAPE), q))
    assertEquals(KeyAction.CloseQuickLook, map(key(K.SPACE), q))
    assertEquals(KeyAction.Open, map(key(K.ENTER), q))
    assertEquals(KeyAction.Step(-1), map(key(K.UP), q))
    assertEquals(KeyAction.Step(-1), map(key(K.LEFT), q))
    assertEquals(KeyAction.Step(1), map(key(K.DOWN), q))
    assertEquals(KeyAction.Step(1), map(key(K.RIGHT), q))
    assertNull(map(key(K.CHAR, 'x'.code), q))
    assertEquals(KeyAction.ShowInFolder, map(key(K.ENTER, ctrl = true), q), "command shortcuts still work over Quick Look")
  }

  @Test fun commandYPreviewsAsInTheQuickPanelAndABareYIsALetter() {
    assertEquals(KeyAction.ToggleQuickLook, map(key(K.LETTER_Y, 'y'.code, ctrl = true)))
    assertEquals(KeyAction.ToggleQuickLook, map(key(K.LETTER_Y, 'y'.code, ctrl = true), ctx(quickLook = true, zone = FocusZone.LIST)), "the same key closes it")
    assertEquals(KeyAction.ToggleQuickLook, map(key(K.LETTER_Y, 'y'.code, meta = true), ctx(isMac = true)))
    assertNull(map(key(K.LETTER_Y, 'y'.code, ctrl = true), ctx(hasResults = false)))
    assertEquals(KeyAction.TypeIntoSearch('y'.code), map(key(K.LETTER_Y, 'y'.code), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.LETTER_Y, 'y'.code), ctx(zone = FocusZone.SEARCH)), "in the search field a y is typed by the field")
  }

  @Test fun typingAnywhereGoesToTheSearchField() {
    assertEquals(KeyAction.TypeIntoSearch('a'.code), map(key(K.CHAR, 'a'.code), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.TypeIntoSearch('k'.code), map(key(K.LETTER_K, 'k'.code), ctx(zone = FocusZone.OTHER)), "a bare K is a letter")
    assertEquals(KeyAction.TypeIntoSearch(8), map(key(K.BACKSPACE), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.CHAR, 'a'.code), ctx(zone = FocusZone.SEARCH)), "the field takes its own keys")
    assertNull(map(key(K.BACKSPACE), ctx(zone = FocusZone.SEARCH)))
    assertNull(map(key(K.CHAR, 0), ctx(zone = FocusZone.LIST)), "a key that types nothing is ignored")
  }

  @Test fun otherScreensOnlyKnowEscape() {
    for (s in listOf(Screen.SETTINGS, Screen.LICENCES)) {
      assertEquals(KeyAction.Back, map(key(K.ESCAPE), ctx(screen = s)))
      assertNull(map(key(K.CHAR, 'a'.code), ctx(screen = s)))
      assertNull(map(key(K.DOWN), ctx(screen = s)))
      assertNull(map(key(K.COMMA, ctrl = true), ctx(screen = s)))
    }
    assertNull(map(key(K.ESCAPE), ctx(screen = Screen.FIRST_RUN)))
  }

  // ---- TEXT SIZE (H3) ----
  @Test fun commandPlusMinusAndZeroChangeTheTextSizeOnEveryScreen() {
    for (s in Screen.entries) {
      assertEquals(KeyAction.Zoom(1), map(key(K.PLUS, '+'.code, ctrl = true), ctx(screen = s)))
      assertEquals(KeyAction.Zoom(-1), map(key(K.MINUS, '-'.code, ctrl = true), ctx(screen = s)))
      assertEquals(KeyAction.Zoom(0), map(key(K.ZERO, '0'.code, ctrl = true), ctx(screen = s)))
    }
  }

  @Test fun theZoomKeysUseCmdOnAMacAndCtrlElsewhere() {
    assertEquals(KeyAction.Zoom(1), map(key(K.PLUS, meta = true), ctx(isMac = true)))
    assertNull(map(key(K.PLUS, ctrl = true), ctx(isMac = true)))
    assertNull(map(key(K.PLUS, meta = true), ctx(isMac = false)))
    assertNull(map(key(K.MINUS, ctrl = true).copy(alt = true), ctx(isMac = false)), "Alt is not Zoom")
  }

  @Test fun aBareZeroPlusOrMinusIsALetterOfTheQuery() {
    assertEquals(KeyAction.TypeIntoSearch('0'.code), map(key(K.ZERO, '0'.code), ctx(zone = FocusZone.LIST)))
    assertEquals(KeyAction.TypeIntoSearch('-'.code), map(key(K.MINUS, '-'.code), ctx(zone = FocusZone.LIST)))
    assertNull(map(key(K.ZERO, '0'.code), ctx(zone = FocusZone.SEARCH)), "in the field the field types it")
  }
}
