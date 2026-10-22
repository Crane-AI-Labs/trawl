package trawl.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.awt.Color as AwtColor
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Assumptions.assumeTrue
import trawl.desktop.Os
import trawl.desktop.PreviewPlayer
import trawl.desktop.Strings
import trawl.desktop.ThemeChoice
import trawl.desktop.ui.SunPanel
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.quick.QuickPanel

/**
 * Screenshots of the quick panel as it is sized on a real laptop screen (1512 x 982 and 1280 x 800), light and dark, with the in-panel
 * preview open on a document, a picture, a video and a hidden picture. Every name, picture and sentence is made up here. Writes only
 * when TRAWL_SHOT_DIR is set.
 */
@OptIn(ExperimentalTestApi::class)
class QuickPanelV2ShotsTest {
  private fun gradient(w: Int, h: Int, top: Int, bottom: Int, draw: (java.awt.Graphics2D) -> Unit = {}): BufferedImage {
    val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = im.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint = GradientPaint(0f, 0f, AwtColor(top), 0f, h.toFloat(), AwtColor(bottom))
    g.fillRect(0, 0, w, h)
    draw(g)
    g.dispose()
    return im
  }

  private fun lake(w: Int = 1200, h: Int = 800) = gradient(w, h, 0xF0A050, 0x284878) { g ->
    g.paint = AwtColor(0xFFE9A8); g.fillOval(w / 2 - 70, h / 3 - 70, 140, 140)
    g.paint = AwtColor(0x1D3557); g.fillPolygon(intArrayOf(0, w / 4, w / 2, w * 3 / 4, w), intArrayOf(h * 2 / 3, h / 2, h * 3 / 5, h / 2 - 20, h * 2 / 3), 5)
  }
  private fun frameOfTalk(w: Int = 900, h: Int = 506) = gradient(w, h, 0x3A3F4B, 0x151820) { g ->
    g.paint = AwtColor(0xE6C9A8); g.fillOval(w / 2 - 60, h / 4, 120, 140)
    g.paint = AwtColor(0x4C6A92); g.fillRoundRect(w / 2 - 130, h / 4 + 150, 260, 200, 60, 60)
  }
  private fun blurred(src: BufferedImage): BufferedImage {
    val small = BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB)
    small.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(src, 0, 0, 24, 16, null); dispose() }
    val out = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(small, 0, 0, 600, 400, null); dispose() }
    return out
  }

  private val dir0: File = java.nio.file.Files.createTempDirectory("trawl-shots").toFile().also { it.deleteOnExit() }

  /** Pictures are real (small) files so the rows draw real thumbnails; everything else only needs a name. */
  private fun file(id: Long, name: String, kind: FileKind, folder: String, size: Long, looks: Float? = null, durationMs: Long? = null): IndexedFile =
    fakeFile(id, name, kind).copy(
      uri = if (kind == FileKind.IMAGE) File(dir0, "$id.jpg").also { ImageIO.write(lake(640, 427), "jpg", it); it.deleteOnExit() }.toURI().toString() else "file:///home/maya/$folder/$name", relativePath = "$folder/$name", sizeBytes = size, dateMillis = 1_760_000_000_000, modifiedMillis = 1_760_000_000_000,
      looksPrivate = looks, durationMs = durationMs, width = if (kind == FileKind.IMAGE) 4032 else null, height = if (kind == FileKind.IMAGE) 3024 else null,
    )

  private val doc = file(1, "Water pump repair quote.pdf", FileKind.PDF, "Documents/Farm", 184_000)
  private val pic = file(2, "Sunset over the lake.jpg", FileKind.IMAGE, "Pictures/Trips", 3_400_000, looks = 0.1f)
  private val clip = file(3, "Village meeting recording.mp4", FileKind.VIDEO, "Videos", 88_000_000, durationMs = 2_820_000)
  private val shy = file(4, "weekend-away.jpg", FileKind.IMAGE, "Pictures", 2_100_000, looks = 0.97f)
  private val notes = file(5, "Budget notes 2026.docx", FileKind.DOCUMENT, "Documents", 42_000)
  private val hits = listOf(
    QuickHit(doc, "…the «water pump» repair is quoted at 1,200,000 shillings including parts and a week of labour…"),
    QuickHit(pic, null),
    QuickHit(clip, "…and then we agreed the «water pump» should be fixed before the rains…", startMs = 1_443_000),
    QuickHit(shy, null),
    QuickHit(notes, "…set aside money for the «water pump» and the new roof…"),
  )

  private val leadText = "Quote for repair of the borehole water pump at the farm.\n\nThe pump stopped working in September. The technician found the impeller worn and the seals cracked. The water pump repair includes a new impeller, seals, and a week of labour. Payment is half now and half on completion."

  private class FakePreview(val pics: Map<Long, ImageBitmap>, val frames: Map<Long, ImageBitmap>, val lead: String, override val full: FullPreview) : QuickPreviewApi {
    override val player = PreviewPlayer({ null })
    override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? = pics[file.id]
    override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap? = frames[file.id]
    override suspend fun lead(file: IndexedFile): String? = if (file.id == 1L) lead else null
    override fun fileOf(file: IndexedFile): File = runCatching { File(java.net.URI(file.uri)) }.getOrElse { File(file.uri.removePrefix("file://")) }
  }

  private class Api(val rows: List<QuickHit>, override val preview: QuickPreviewApi) : QuickSearchApi {
    override fun search(query: String): Flow<List<QuickHit>> = flow { emit(rows) }
    override fun open(file: IndexedFile) {}
    override fun showInFolder(file: IndexedFile) {}
    override fun blurOn() = true
  }

  private fun shot(name: String, screenW: Int, screenH: Int, dark: Boolean, select: Int?, os: Os = Os.LINUX, query: String = "water pump", full: Boolean = false) {
    val dir = System.getenv("TRAWL_SHOT_DIR")
    assumeTrue(!dir.isNullOrBlank(), "set TRAWL_SHOT_DIR to write the panel screenshots")
    Strings.use(Locale.ENGLISH)
    val real = lake()
    val preview = FakePreview(
      pics = mapOf(2L to lake().toComposeImageBitmap(), 4L to blurred(lake(600, 400)).toComposeImageBitmap(), 1L to lake(96, 96).toComposeImageBitmap()),
      frames = mapOf(3L to frameOfTalk().toComposeImageBitmap()), lead = leadText, full = if (full) FakeFull(true) else NoFullPreview,
    )
    real.flush()
    val inset = SunPanel.ShadowInset
    val size = QuickPanelSizing.of(screenW, screenH, select != null)
    val w = size.width + inset.value.toInt() * 2
    val h = size.height + inset.value.toInt() * 2
    val x = QuickPanelSizing.left(0, screenW, w)
    val y = QuickPanelSizing.top(0, screenH, size.height) - inset.value.toInt()
    runDesktopComposeUiTest(width = screenW, height = screenH) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val state = QuickPanelState(Api(hits, preview), scope, onClose = {}, debounceMs = 10)
        setContent {
          SunlightTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT) {
            Box(
              Modifier.size(screenW.dp, screenH.dp).background(
                Brush.verticalGradient(if (dark) listOf(Color(0xFF1B2A41), Color(0xFF0C1220)) else listOf(Color(0xFFBFD4E6), Color(0xFF8AA9C4))),
              ),
            ) {
              Box(Modifier.offset(x.dp, y.dp).size(w.dp, h.dp)) {
                QuickPanel(state, os, "Ctrl+Shift+Space", modifier = Modifier.size(w.dp, h.dp), blurOn = true)
              }
            }
          }
        }
        waitUntil(timeoutMillis = 3000) { state.hits.value.size == hits.size }
        state.setQuery(query)
        waitUntil(timeoutMillis = 3000) { state.answered.value == query }
        if (select != null) { state.select(select); state.togglePreview() }
        Thread.sleep(700)   // let the pictures arrive
        waitForIdle()
        val img = onRoot().captureToImage().toAwtImage()
        File(dir!!).mkdirs()
        ImageIO.write(img, "png", File(dir, "qp2-$name-${screenW}x$screenH-${if (dark) "dark" else "light"}.png"))
      } finally { scope.cancel() }
    }
  }

  @Test fun list1512Light() = shot("list", 1512, 982, false, null)
  @Test fun list1512Dark() = shot("list", 1512, 982, true, null)
  @Test fun list1280Light() = shot("list", 1280, 800, false, null)
  @Test fun list1280Dark() = shot("list", 1280, 800, true, null)

  @Test fun document1512Light() = shot("doc", 1512, 982, false, 0)
  @Test fun document1512Dark() = shot("doc", 1512, 982, true, 0)
  @Test fun document1280Light() = shot("doc", 1280, 800, false, 0)
  @Test fun document1280Dark() = shot("doc", 1280, 800, true, 0)

  @Test fun picture1512Light() = shot("picture", 1512, 982, false, 1)
  @Test fun picture1280Dark() = shot("picture", 1280, 800, true, 1)

  @Test fun video1512Dark() = shot("video", 1512, 982, true, 2, os = Os.MAC, full = true)
  @Test fun video1280Light() = shot("video", 1280, 800, false, 2)

  @Test fun hidden1512Light() = shot("hidden", 1512, 982, false, 3)
  @Test fun hidden1280Dark() = shot("hidden", 1280, 800, true, 3)
}
