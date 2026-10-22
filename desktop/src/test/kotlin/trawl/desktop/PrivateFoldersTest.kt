package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.privacy.Later
import trawl.desktop.privacy.OsAvailability
import trawl.desktop.privacy.OsOutcome
import trawl.desktop.privacy.OsUnlock
import trawl.desktop.privacy.PassResult
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.privacy.PrivacyController.ChangeResult
import trawl.desktop.privacy.PrivacyStore
import trawl.desktop.privacy.Purpose
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.privacy.PrivacyUi
import trawl.desktop.ui.privacy.PrivateFolders

/** The private-folders editor in Settings, Privacy (audit rows D2 and D10): the phone's behaviour, on the desktop's controller. */
@OptIn(ExperimentalTestApi::class)
class PrivateFoldersTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private object NoOs : OsUnlock {
    override fun availability() = OsAvailability.Unavailable("test")
    override fun authenticate(reason: String) = OsOutcome.UNAVAILABLE
  }

  private val root = File("/tmp/trawl-test-root/Photos")
  private fun controller(): PrivacyController {
    val dir = Files.createTempDirectory("pf").toFile().also { it.deleteOnExit() }
    return PrivacyController(PrivacyStore(dir), NoOs, { 1_000_000L }, { 5_000_000L }, Later { _, _ -> {} }, passphraseIterations = 1000)
      .also { it.refreshOs() }
  }

  private val spoken = { name: String -> "Remove $name from private folders" }

  @Test fun `an empty list says so, and a chosen folder is kept relative to its searched folder`() = runComposeUiTest {
    val c = controller()
    val ui = PrivacyUi(c) { _, _ -> }
    var next: File? = null
    setContent { SunlightTheme(ThemeChoice.LIGHT) { PrivateFolders(ui, listOf(root)) { next } } }
    onNodeWithText("No folders are private yet.").assertIsDisplayed()
    onNodeWithText("Pictures in these folders are always treated as private.").assertIsDisplayed()

    next = File(root, "Private/Receipts")
    onNodeWithText("Add a private folder").performClick()
    waitForIdle()
    // The index matches folders without regard to case, so they are kept in lower case.
    assertEquals(setOf("private/receipts"), c.settings.value.rules.privateFolders)
    onNodeWithText("private/receipts").assertIsDisplayed()
    onNodeWithContentDescription(spoken("private/receipts")).assertIsDisplayed()
  }

  @Test fun `a folder outside the searched ones, or a searched folder itself, is refused with the reason`() = runComposeUiTest {
    val c = controller()
    val ui = PrivacyUi(c) { _, _ -> }
    var next: File? = File("/tmp/somewhere-else")
    setContent { SunlightTheme(ThemeChoice.LIGHT) { PrivateFolders(ui, listOf(root)) { next } } }
    onNodeWithText("Add a private folder").performClick()
    waitForIdle()
    onNodeWithText("Choose a folder inside one that Trawl searches. To stop searching a whole folder, remove it under Folders.").assertIsDisplayed()
    assertTrue(c.settings.value.rules.privateFolders.isEmpty())

    next = root   // a whole searched folder: remove it from Trawl instead
    onNodeWithText("Add a private folder").performClick()
    waitForIdle()
    assertTrue(c.settings.value.rules.privateFolders.isEmpty())

    next = null   // the chooser was cancelled: nothing changes
    onNodeWithText("Add a private folder").performClick()
    waitForIdle()
    assertTrue(c.settings.value.rules.privateFolders.isEmpty())
  }

  @Test fun `with no lock and no prompt a folder is lifted at once`() = runComposeUiTest {
    val c = controller()
    c.setPrivateFolder("Private", true)
    val ui = PrivacyUi(c) { _, _ -> }
    setContent { SunlightTheme(ThemeChoice.LIGHT) { PrivateFolders(ui, listOf(root)) { null }; ui.Dialogs() } }
    onNodeWithContentDescription(spoken("private")).performClick()
    waitForIdle()
    assertTrue(c.settings.value.rules.privateFolders.isEmpty())
    onNodeWithText("No folders are private yet.").assertIsDisplayed()
  }

  @Test fun `with a lock set, lifting a folder asks first and Cancel leaves it private`() = runComposeUiTest {
    val c = controller()
    assertEquals(ChangeResult.OK, c.enableLock("correct horse battery".toCharArray(), useOsPrompt = false))
    c.setPrivateFolder("Private", true)
    val ui = PrivacyUi(c) { _, _ -> }
    setContent { SunlightTheme(ThemeChoice.LIGHT) { PrivateFolders(ui, listOf(root)) { null }; ui.Dialogs() } }
    onNodeWithContentDescription(spoken("private")).performClick()
    waitForIdle()
    onNodeWithText("Make this folder searchable again?").assertIsDisplayed()
    assertEquals(setOf("private"), c.settings.value.rules.privateFolders)
    onNodeWithText("Cancel").performClick()
    waitForIdle()
    assertEquals(setOf("private"), c.settings.value.rules.privateFolders)
  }

  @Test fun `adding a folder never asks, even with a lock set`() = runComposeUiTest {
    val c = controller()
    c.enableLock("correct horse battery".toCharArray(), useOsPrompt = false)
    val ui = PrivacyUi(c) { _, _ -> }
    setContent { SunlightTheme(ThemeChoice.LIGHT) { PrivateFolders(ui, listOf(root)) { File(root, "Hidden") }; ui.Dialogs() } }
    onNodeWithText("Add a private folder").performClick()
    waitForIdle()
    onNodeWithText("Make this folder searchable again?").assertDoesNotExist()
    assertEquals(setOf("hidden"), c.settings.value.rules.privateFolders)
  }

  @Test fun `the proof for lifting a folder opens neither the lock-change window nor the hidden pictures`() {
    val c = controller()
    c.enableLock("correct horse battery".toCharArray(), useOsPrompt = false)
    assertEquals(PassResult.Ok, c.authWithPassphrase(Purpose.UNHIDE, "correct horse battery".toCharArray()))
    assertEquals(ChangeResult.NEEDS_PROOF, c.disableLock(), "the lock can still not be turned off on the back of it")
    assertFalse(c.revealed.value, "and hidden pictures are still hidden")
  }
}
