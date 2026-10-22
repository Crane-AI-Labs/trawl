package trawl.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import trawl.desktop.shell.QuickPreviewApi
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.quick.MediaBox

/**
 * Matrix bug 13: the video preview showed no frame ("Open it to watch"). That pane was replaced (quick panel v2, part 2) by the quick
 * panel's [MediaBox]: the decoder's frame at the matched moment when this computer can give one, else the video tile with no frame.
 */
@OptIn(ExperimentalTestApi::class)
class VideoPreviewFrameTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val video = IndexedFile(7, "file:///tmp/clip.mp4", "clip.mp4", "clip.mp4", FileKind.VIDEO, null, 1, 1, durationMs = 60_000)

  private fun api(frame: ImageBitmap?) = object : QuickPreviewApi {
    override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? = null
    override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap? = frame
    override suspend fun lead(file: IndexedFile): String? = null
  }

  @Test fun theFrameAtTheMatchedMomentIsDrawn() = runComposeUiTest {
    setContent { SunlightTheme { MediaBox(video, 42_000, null, api(ImageBitmap(64, 36)), video = true) } }
    waitUntil(timeoutMillis = 5000) { onAllNodesWithContentDescription("Frame at 0:42").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test fun withoutADecoderThereIsNoFrameAndNoPretendOne() = runComposeUiTest {
    setContent { SunlightTheme { MediaBox(video, 42_000, null, api(null), video = true) } }
    waitForIdle(); Thread.sleep(300); waitForIdle()
    onAllNodesWithContentDescription("Frame at 0:42").assertCountEquals(0)
  }
}
