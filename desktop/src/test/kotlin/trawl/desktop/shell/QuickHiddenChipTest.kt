package trawl.desktop.shell

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import trawl.desktop.Os
import trawl.desktop.Strings
import trawl.desktop.privacy.PreviewBlur
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.quick.QuickHidden
import trawl.desktop.ui.quick.QuickPanel

/** G4: the quick panel says how many pictures it is hiding, and where to go to show them. */
@OptIn(ExperimentalTestApi::class)
class QuickHiddenChipTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private fun pic(id: Long, score: Float?) = IndexedFile(
    id = id, uri = "file:///tmp/p$id.jpg", relativePath = "p$id.jpg", name = "p$id.jpg", kind = FileKind.IMAGE, mimeType = null,
    sizeBytes = 1, modifiedMillis = 0, looksPrivate = score,
  )

  private val files = listOf(pic(1, 1f), pic(2, 1f), pic(3, 0f), fakeFile(4, "notes.txt"))

  @Test fun countsOnlyTheBlurredRows() {
    assertEquals(2, QuickHidden.count(files, PreviewBlur(enabled = true, allowReveal = { false }, threshold = 0.5f)))
    assertEquals(0, QuickHidden.count(files, PreviewBlur(enabled = false, allowReveal = { false }, threshold = 0.5f)), "blur off: nothing is hidden")
  }

  private fun panel(blurOn: Boolean, body: androidx.compose.ui.test.ComposeUiTest.(QuickPanelState) -> Unit) = runComposeUiTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = QuickPanelState(FakeSearch(files), scope, onClose = {}, debounceMs = 30)
    try {
      setContent { SunlightTheme { QuickPanel(state, Os.WINDOWS, null, blurOn = blurOn) } }
      waitUntil(timeoutMillis = 3000) { state.hits.value.size == 4 }
      waitForIdle()
      body(state)
    } finally { scope.cancel() }
  }

  @Test fun theChipIsThereWhenPicturesAreBlurredAndSaysWhereToGoToShowThem() = panel(blurOn = true) {
    onNodeWithContentDescription("2 pictures are hidden. Open Trawl to show them.").assertExists()
  }

  @Test fun noChipWhenNothingIsBlurred() = panel(blurOn = false) {
    onNodeWithContentDescription("2 pictures are hidden. Open Trawl to show them.").assertDoesNotExist()
  }
}
