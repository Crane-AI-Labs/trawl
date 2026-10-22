package trawl.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.privacy.LeaveDialog

/** The "take a sensitive file out of Trawl?" question (audit row B7): it counts the files, Continue goes on, Cancel does not. */
@OptIn(ExperimentalTestApi::class)
class LeaveDialogTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  @Test fun oneFileAndSeveralFilesAreWorded() = runComposeUiTest {
    var n by mutableIntStateOf(1)
    setContent { SunlightTheme(ThemeChoice.LIGHT) { LeaveDialog(n, onContinue = {}, onCancel = {}) } }
    onNodeWithText("Take a sensitive file out of Trawl?").assertExists()
    onNodeWithText("This file is marked sensitive", substring = true).assertExists()
    n = 3
    waitForIdle()
    onNodeWithText("3 of these files are marked sensitive", substring = true).assertExists()
  }

  @Test fun continueGoesOnAndCancelDoesNot() = runComposeUiTest {
    val log = ArrayList<String>()
    setContent { SunlightTheme(ThemeChoice.LIGHT) { LeaveDialog(2, onContinue = { log += "go" }, onCancel = { log += "stop" }) } }
    onNodeWithText("Cancel").performClick()
    assertEquals(listOf("stop"), log)
    onNodeWithText("Continue").performClick()
    assertEquals(listOf("stop", "go"), log)
  }
}
