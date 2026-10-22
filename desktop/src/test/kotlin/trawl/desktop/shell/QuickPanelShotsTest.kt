package trawl.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assumptions.assumeTrue
import trawl.desktop.Os
import trawl.desktop.Strings
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ThemeChoice
import trawl.desktop.ui.quick.QuickPanel

/**
 * Renders the quick panel to PNG files, light and dark at 1x and 2x, through Compose's own offscreen renderer (the same Skia drawing the
 * real window uses). It only writes when TRAWL_SHOT_DIR is set, so a normal test run leaves nothing behind.
 */
@OptIn(ExperimentalTestApi::class)
class QuickPanelShotsTest {
  private val files = listOf(
    fakeFile(1, "invoice-march.pdf", FileKind.PDF),
    fakeFile(2, "invoice-april.pdf", FileKind.PDF),
    fakeFile(3, "Invoice scan 2026.jpg", FileKind.IMAGE),
    fakeFile(4, "Budget notes.docx", FileKind.DOCUMENT),
  )

  private fun shot(dark: Boolean, scale: Float, fontScale: Float = 1f) {
    val dir = System.getenv("TRAWL_SHOT_DIR")
    assumeTrue(!dir.isNullOrBlank(), "set TRAWL_SHOT_DIR to write the panel screenshots")
    Strings.use(Locale.ENGLISH)
    runDesktopComposeUiTest(width = (736 * scale).toInt(), height = (516 * scale).toInt()) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val state = QuickPanelState(FakeSearch(files), scope, onClose = {}, debounceMs = 10)
        setContent {
          CompositionLocalProvider(LocalDensity provides Density(scale, fontScale)) {
            SunlightTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT) {
              // 680 x 460 plus the 28 dp margin the real window leaves for the shadow, over a stand-in desktop.
              Box(Modifier.size(736.dp, 516.dp).background(if (dark) Color(0xFF2B2E33) else Color(0xFFB9BDC4))) {
                QuickPanel(state, Os.LINUX, "Ctrl+Alt+Space", modifier = Modifier.size(736.dp, 516.dp))
              }
            }
          }
        }
        waitUntil(timeoutMillis = 3000) { state.hits.value.size == 4 }
        state.setQuery("invoice")
        waitUntil(timeoutMillis = 3000) { state.answered.value == "invoice" }
        waitForIdle()
        val img = onRoot().captureToImage().toAwtImage()
        val name = "quick-panel-${if (dark) "dark" else "light"}-${scale.toInt()}x${if (fontScale != 1f) "-text${(fontScale * 100).toInt()}" else ""}.png"
        File(dir!!).mkdirs()
        ImageIO.write(img, "png", File(dir, name))
      } finally { scope.cancel() }
    }
  }

  // Audit row H3: the fixed-size panel must hold when the person has chosen larger text (130 % and 150 %).
  @Test fun lightLargeText() = shot(false, 1f, 1.3f)
  @Test fun darkLargerText() = shot(true, 1f, 1.5f)
  @Test fun lightLargerText() = shot(false, 1f, 1.5f)

  @Test fun lightOneX() = shot(false, 1f)
  @Test fun lightTwoX() = shot(false, 2f)
  @Test fun darkOneX() = shot(true, 1f)
  @Test fun darkTwoX() = shot(true, 2f)

  // ---- the privacy states: locked, and unlocked with a hidden picture (made-up pictures drawn here, nothing of anyone's) ----

  private class LockableSearch(private val hits: List<QuickHit>, start: Boolean) : QuickSearchApi {
    val lock = MutableStateFlow(start)
    override val locked: StateFlow<Boolean> get() = lock
    override fun blurOn() = true
    override fun search(query: String) = kotlinx.coroutines.flow.flow { emit(if (lock.value) emptyList() else hits) }
    override fun open(file: com.craneai.findr.core.IndexedFile) {}
    override fun showInFolder(file: com.craneai.findr.core.IndexedFile) {}
  }

  private fun picture(dir: File, name: String, top: Int, bottom: Int): File {
    val im = java.awt.image.BufferedImage(160, 120, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = im.createGraphics()
    g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(top), 0f, 120f, java.awt.Color(bottom))
    g.fillRect(0, 0, 160, 120); g.paint = java.awt.Color(250, 215, 190); g.fillOval(55, 25, 50, 60); g.dispose()
    return File(dir, name).also { ImageIO.write(im, "jpg", it) }
  }

  private fun privacyShot(dark: Boolean, locked: Boolean) {
    val dir = System.getenv("TRAWL_SHOT_DIR")
    assumeTrue(!dir.isNullOrBlank(), "set TRAWL_SHOT_DIR to write the panel screenshots")
    Strings.use(Locale.ENGLISH)
    val pics = java.nio.file.Files.createTempDirectory("trawl-qp-pics").toFile()
    val plain = picture(pics, "sunset-lake.jpg", 0xF0A050, 0x284878)
    val shy = picture(pics, "weekend-away.jpg", 0xF0BEAA, 0x78506A)
    fun img(id: Long, f: File, looks: Float?) = fakeFile(id, f.name, FileKind.IMAGE).copy(uri = f.toURI().toString(), relativePath = "Pictures/${f.name}", looksPrivate = looks)
    val hits = listOf(
      QuickHit(fakeFile(1, "meeting-notes-friday.txt"), "the pump repair is booked"),
      QuickHit(img(2, plain, 0.1f), null),
      QuickHit(img(3, shy, 0.97f), null),
      QuickHit(fakeFile(4, "garden-plan.txt"), null),
    )
    runDesktopComposeUiTest(width = 736, height = 516) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val api = LockableSearch(hits, locked)
        val state = QuickPanelState(api, scope, onClose = {}, debounceMs = 10)
        setContent {
          SunlightTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT) {
            Box(Modifier.size(736.dp, 516.dp).background(if (dark) Color(0xFF2B2E33) else Color(0xFFB9BDC4))) {
              QuickPanel(state, Os.LINUX, "Ctrl+Alt+Space", modifier = Modifier.size(736.dp, 516.dp), blurOn = true)
            }
          }
        }
        if (!locked) waitUntil(timeoutMillis = 3000) { state.hits.value.size == 4 }
        Thread.sleep(600) // let the small pictures decode
        waitForIdle()
        val img = onRoot().captureToImage().toAwtImage()
        File(dir!!).mkdirs()
        ImageIO.write(img, "png", File(dir, "quick-panel-${if (locked) "locked" else "hidden-picture"}-${if (dark) "dark" else "light"}.png"))
      } finally { scope.cancel(); pics.deleteRecursively() }
    }
  }

  @Test fun lockedLight() = privacyShot(false, true)
  @Test fun lockedDark() = privacyShot(true, true)
  @Test fun hiddenPictureLight() = privacyShot(false, false)
  @Test fun hiddenPictureDark() = privacyShot(true, false)
}
