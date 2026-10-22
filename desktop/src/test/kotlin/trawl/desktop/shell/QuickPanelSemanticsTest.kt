package trawl.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.core.FileKind
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import trawl.desktop.Os
import trawl.desktop.Strings
import trawl.desktop.ui.A11yIconButton
import trawl.desktop.ui.Icons
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.quick.QuickPanel

/**
 * The accessibility checks for the quick panel (audit rows H2 and H4), through Compose's own semantics tree: the same tree the platform
 * accessibility bridges (Java Access Bridge on Windows, NSAccessibility on macOS) are fed from. They check names, roles, focus and keys; they
 * do not replace a pass with a real screen reader, which the write-up says plainly.
 */
@OptIn(ExperimentalTestApi::class)
class QuickPanelSemanticsTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val files = listOf(
    fakeFile(1, "invoice-march.pdf", FileKind.PDF),
    fakeFile(2, "invoice-april.pdf", FileKind.PDF),
    fakeFile(3, "holiday.jpg", FileKind.IMAGE),
  )

  private class Rig(val api: FakeSearch, val state: QuickPanelState, val closed: IntArray, val scope: CoroutineScope)

  private fun rig(files: List<com.craneai.findr.core.IndexedFile>): Rig {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeSearch(files)
    val closed = IntArray(1)
    return Rig(api, QuickPanelState(api, scope, onClose = { closed[0]++ }, debounceMs = 30), closed, scope)
  }

  @Composable
  private fun Panel(r: Rig, os: Os, shortcut: String? = "Ctrl+Alt+Space") = SunlightTheme { QuickPanel(r.state, os, shortcut) }

  private val field = "Search your files"

  @Test fun theSearchBoxHasAName() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      onNodeWithContentDescription(field).assertExists()
    } finally { r.scope.cancel() }
  }

  @Test fun typingSearchesAndEveryRowIsNamedAndClickable() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      onNodeWithContentDescription(field).performTextInput("invoice")
      waitUntil(timeoutMillis = 3000) { r.state.answered.value == "invoice" }
      waitForIdle()
      val rows = onAllRows()
      assertEquals(2, rows.size)
      for (node in rows) {
        val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertTrue(label != null && label.startsWith("invoice-"), "a row reads its file name: $label")
        assertTrue(label!!.contains("/tmp"), "a row says the real folder the file is in: $label")
      }
    } finally { r.scope.cancel() }
  }

  private fun androidx.compose.ui.test.ComposeUiTest.onAllRows() =
    onAllNodes(hasClickAction() and hasContentDescription("invoice-", substring = true)).fetchSemanticsNodes()

  @Test fun everyClickableThingHasANameAndCanTakeFocus() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      onNodeWithContentDescription(field).performTextInput("a")   // makes the Clear button appear
      waitForIdle()
      val clickables = onAllNodes(hasClickAction()).fetchSemanticsNodes()
      assertTrue(clickables.size >= 2, "the clear button and the rows")
      for (n in clickables) {
        val label = n.config.getOrNull(SemanticsProperties.ContentDescription)
        assertTrue(!label.isNullOrEmpty(), "a clickable without a name: ${n.config}")
        assertTrue(n.config.contains(SemanticsProperties.Focused), "not focusable (so no Tab, no focus ring): $label")
      }
    } finally { r.scope.cancel() }
  }

  @Test fun theClearButtonIsNamedAndClears() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      onNodeWithContentDescription("Clear the search").assertDoesNotExist()
      onNodeWithContentDescription(field).performTextInput("holiday")
      waitForIdle()
      onNodeWithContentDescription("Clear the search").assertHasClickAction().performClick()
      waitForIdle()
      assertEquals("", r.state.query.value)
    } finally { r.scope.cancel() }
  }

  @Test fun downThenEnterOpensTheSecondFile() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      val box = onNodeWithContentDescription(field)
      box.requestFocus()
      box.performKeyInput { pressKey(Key.DirectionDown) }
      waitForIdle()
      assertEquals(1, r.state.selected.value)
      box.performKeyInput { pressKey(Key.Enter) }
      assertEquals(listOf(files[1]), r.api.opened)
      assertEquals(1, r.closed[0])
    } finally { r.scope.cancel() }
  }

  @Test fun theSelectedRowIsMarkedSelected() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      waitForIdle()
      onNode(hasContentDescription("invoice-march.pdf", substring = true)).assertIsSelected()
    } finally { r.scope.cancel() }
  }

  @Test fun ctrlEnterShowsInFolderOnWindows() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS) }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      val box = onNodeWithContentDescription(field)
      box.requestFocus()
      box.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.Enter); keyUp(Key.CtrlLeft) }
      assertEquals(listOf(files[0]), r.api.shown)
      assertTrue(r.api.opened.isEmpty())
    } finally { r.scope.cancel() }
  }

  @Test fun commandEnterShowsInFolderOnAMac() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.MAC, shortcut = "Command+Shift+Space") }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      val box = onNodeWithContentDescription(field)
      box.requestFocus()
      box.performKeyInput { keyDown(Key.MetaLeft); pressKey(Key.Enter); keyUp(Key.MetaLeft) }
      assertEquals(listOf(files[0]), r.api.shown)
    } finally { r.scope.cancel() }
  }

  @Test fun escCloses() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.LINUX) }
      waitUntil(timeoutMillis = 3000) { r.state.hits.value.size == 3 }
      val box = onNodeWithContentDescription(field)
      box.requestFocus()
      box.performKeyInput { pressKey(Key.Escape) }
      assertEquals(1, r.closed[0])
    } finally { r.scope.cancel() }
  }

  @Test fun theFooterShowsTheCurrentShortcut() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS, shortcut = "Ctrl+Alt+Space") }
      onNode(androidx.compose.ui.test.hasText("Ctrl+Alt+Space", substring = true)).assertExists()
    } finally { r.scope.cancel() }
  }

  @Test fun theFooterSaysNothingAboutAKeyWhenThereIsNone() = runComposeUiTest {
    val r = rig(files)
    try {
      setContent { Panel(r, Os.WINDOWS, shortcut = null) }
      onNode(androidx.compose.ui.test.hasText("opens this panel", substring = true)).assertDoesNotExist()
      onNode(androidx.compose.ui.test.hasText("Esc closes", substring = true)).assertExists()
    } finally { r.scope.cancel() }
  }

  @Test fun anIconButtonIsNamedAndEnterAndSpaceActivateIt() = runComposeUiTest {
    var clicks = 0
    setContent { SunlightTheme { A11yIconButton(Icons.Close, "Close the thing", { clicks++ }) } }
    val b = onNodeWithContentDescription("Close the thing")
    b.assertHasClickAction()
    b.requestFocus()
    b.performKeyInput { pressKey(Key.Enter) }
    b.performKeyInput { pressKey(Key.Spacebar) }
    assertEquals(2, clicks)
  }
}
