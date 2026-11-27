package trawl.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import trawl.desktop.shell.QuickHit
import trawl.desktop.shell.QuickPreviewApi
import trawl.desktop.ui.PreviewMedia
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.ThumbState
import trawl.desktop.ui.quick.QuickPreview
import trawl.desktop.ui.rememberThumb

/**
 * Adversarial stress tests for EVERY desktop previewer & media player:
 * - PDF page rendering & rapid switching across distinct PDFs (reproduces & prevents the frozen-first-PDF bug).
 * - Image thumbnail switching across cached & uncached images (prevents produceState frozen-bitmap bug).
 * - PreviewPlayer rapid play/stop/switch, Failed/Done reset on stop, zero leaked decoders or audio lines.
 * - FrameThumbs cancellation when scrubbing rapidly across timestamps (skips stale queued decodes).
 * - Mixed-media rapid switching (PDF -> Image -> Video -> Audio -> JSON -> Markdown -> PDF #2).
 */
@OptIn(ExperimentalTestApi::class)
class PreviewPlayerStressTest {
  private val dirs = ArrayList<File>()
  private fun tmpDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dirs += it }

  @BeforeTest fun setup() {
    Strings.use(Locale.ENGLISH)
    PdfThumbs.clearCache()
  }

  @AfterTest fun tearDown() {
    PdfThumbs.clearCache()
    dirs.forEach { it.deleteRecursively() }
    dirs.clear()
  }

  /** Creates a genuine single-page PDF filled with a solid [color] so pixel sampling distinguishes every PDF deterministically. */
  private fun createColoredPdf(dir: File, name: String, color: Color): File {
    val file = File(dir, name)
    PDDocument().use { doc ->
      val page = PDPage(PDRectangle.LETTER)
      doc.addPage(page)
      PDPageContentStream(doc, page).use { cs ->
        cs.setNonStrokingColor(color)
        cs.addRect(0f, 0f, page.mediaBox.width, page.mediaBox.height)
        cs.fill()
      }
      doc.save(file)
    }
    return file
  }

  private fun createColoredPng(dir: File, name: String, color: Color): File {
    val file = File(dir, name)
    val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
      g.color = color
      g.fillRect(0, 0, 48, 48)
    } finally {
      g.dispose()
    }
    ImageIO.write(img, "png", file)
    return file
  }

  private fun createColoredJpegBytes(color: Color): ByteArray {
    val img = BufferedImage(48, 36, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
      g.color = color
      g.fillRect(0, 0, 48, 36)
    } finally {
      g.dispose()
    }
    return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
  }

  private fun centerRgb(bmp: ImageBitmap): Triple<Int, Int, Int> {
    val map = bmp.toPixelMap()
    val c = map[map.width / 2, map.height / 2]
    return Triple((c.red * 255f).toInt(), (c.green * 255f).toInt(), (c.blue * 255f).toInt())
  }

  private fun indexed(id: Long, file: File, kind: FileKind, durationMs: Long? = null): IndexedFile = IndexedFile(
    id = id,
    uri = file.toURI().toString(),
    relativePath = file.name,
    name = file.name,
    kind = kind,
    mimeType = null,
    sizeBytes = file.length().coerceAtLeast(1L),
    modifiedMillis = file.lastModified(),
    durationMs = durationMs,
  )

  @Test fun pdfThumbsRendersDistinctBitmapsForMultiplePdfsAndHandlesCorruptPdfCleanly() = runBlocking {
    val dir = tmpDir("trawl-pdf-stress")
    val july = createColoredPdf(dir, "july-statement.pdf", Color(220, 30, 30))
    val august = createColoredPdf(dir, "august-statement.pdf", Color(30, 190, 40))
    val september = createColoredPdf(dir, "september-statement.pdf", Color(30, 60, 220))
    val corrupt = File(dir, "corrupt.pdf").apply { writeText("not a real pdf %PDF-broken") }

    val bmpJuly = PdfThumbs.loadSuspend(july, 1, side = 320, modifiedMillis = july.lastModified())
    val bmpAug = PdfThumbs.loadSuspend(august, 1, side = 320, modifiedMillis = august.lastModified())
    val bmpSep = PdfThumbs.loadSuspend(september, 1, side = 320, modifiedMillis = september.lastModified())
    val bmpCorrupt = PdfThumbs.loadSuspend(corrupt, 1, side = 320, modifiedMillis = corrupt.lastModified())

    assertNotNull(bmpJuly)
    assertNotNull(bmpAug)
    assertNotNull(bmpSep)
    assertNull(bmpCorrupt, "corrupt PDF must return null cleanly without throwing")

    val (jr, jg, jb) = centerRgb(bmpJuly)
    val (ar, ag, ab) = centerRgb(bmpAug)
    val (sr, sg, sb) = centerRgb(bmpSep)
    assertTrue(jr > 180 && jg < 80 && jb < 80, "July PDF must render red, got ($jr,$jg,$jb)")
    assertTrue(ag > 150 && ar < 80 && ab < 80, "August PDF must render green, got ($ar,$ag,$ab)")
    assertTrue(sb > 180 && sr < 80 && sg < 100, "September PDF must render blue, got ($sr,$sg,$sb)")
  }

  @Test fun rapidPdfSwitchingInPreviewMediaAndQuickPreviewNeverFreezesOnFirstPdf() = runComposeUiTest {
    val dir = tmpDir("trawl-pdf-ui-switch")
    val dataDir = tmpDir("trawl-pdf-ui-data")
    val pdfs = listOf(
      createColoredPdf(dir, "july-statement.pdf", Color(220, 30, 30)),
      createColoredPdf(dir, "august-statement.pdf", Color(30, 190, 40)),
      createColoredPdf(dir, "september-statement.pdf", Color(30, 60, 220)),
      createColoredPdf(dir, "october-statement.pdf", Color(230, 200, 20)),
      createColoredPdf(dir, "november-statement.pdf", Color(200, 30, 200)),
    )
    val files = pdfs.mapIndexed { i, f -> indexed(100L + i, f, FileKind.PDF) }
    val rows = files.map { f -> ResultRow(f, snippet = "Statement text for ${f.name}", reason = null, strong = true, page = 1) }

    val controller = AppController(dataDir, Platform.UserFolders(dataDir, dataDir, dataDir), logSink = {})
    try {
      var activeIdx by mutableStateOf(0)
      val quickApi = object : QuickPreviewApi {
        override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? = null
        override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap? = null
        override suspend fun lead(file: IndexedFile): String = "Extracted text for ${file.name}"
        override fun fileOf(file: IndexedFile): File = File(java.net.URI(file.uri))
      }

      // 1. Test Main Window PreviewMedia switching across all 5 distinct PDFs
      setContent {
        SunlightTheme {
          PreviewMedia(rows[activeIdx], controller, height = 420.dp, side = 720, excerptLines = 10)
        }
      }

      // Step through July -> August -> September -> October -> November -> back to August
      for (target in listOf(0, 1, 2, 3, 4, 1)) {
        activeIdx = target
        val expectedName = files[target].name
        val expectedFile = pdfs[target]
        waitUntil(timeoutMillis = 8_000) {
          onAllNodesWithContentDescription(expectedName).fetchSemanticsNodes().isNotEmpty() &&
            PdfThumbs.peek(expectedFile, 1, side = 720, modifiedMillis = expectedFile.lastModified()) != null
        }
        val rendered = PdfThumbs.peek(expectedFile, 1, side = 720, modifiedMillis = expectedFile.lastModified())
        assertNotNull(rendered, "PDF $expectedName must actually render into PdfThumbs cache")
        // Verify previous PDF is NOT the contentDescription currently drawn
        val otherName = files[(target + 1) % files.size].name
        assertEquals(0, onAllNodesWithContentDescription(otherName).fetchSemanticsNodes().size)
      }

      // 2. Test QuickPreview switching across distinct PDFs (fresh cache to ensure real loads happen!)
      PdfThumbs.clearCache()
      var quickIdx by mutableStateOf(0)
      setContent {
        SunlightTheme {
          val f = files[quickIdx]
          QuickPreview(
            hit = QuickHit(f, "Matched statement ${f.name}"),
            hidden = false,
            query = "statement",
            preview = quickApi,
            mod = "⌘",
            onOpen = {},
            onFolder = {},
            onFull = {},
            onClose = {},
          )
        }
      }
      for (target in listOf(0, 1, 2, 0)) {
        quickIdx = target
        val expectedName = files[target].name
        val expectedFile = pdfs[target]
        waitUntil(timeoutMillis = 8_000) {
          onAllNodesWithContentDescription(expectedName).fetchSemanticsNodes().isNotEmpty() &&
            PdfThumbs.peek(expectedFile, 1, side = 560, modifiedMillis = expectedFile.lastModified()) != null
        }
      }
    } finally {
      controller.close()
    }
  }

  @Test fun rememberThumbNeverFreezesOnFirstImageWhenSwitchingToCachedOrUncachedImage() = runComposeUiTest {
    val dir = tmpDir("trawl-thumb-switch")
    val red = createColoredPng(dir, "red.png", Color(230, 20, 20))
    val green = createColoredPng(dir, "green.png", Color(20, 210, 30))
    val blue = createColoredPng(dir, "blue.png", Color(20, 40, 230))

    // Pre-warm green.png in cache: with the old produceState bug, switching red -> cached green froze red.png forever!
    assertNotNull(Thumbnails.load(green, 64, green.lastModified()))

    var active by mutableStateOf(red)
    var latest by mutableStateOf(ThumbState(false, null))
    setContent {
      latest = rememberThumb(active, 64, modifiedMillis = active.lastModified())
    }

    // Step 1: red.png loads
    waitUntil(timeoutMillis = 5_000) { latest.done && latest.bitmap != null }
    val (rr, rg, _) = centerRgb(latest.bitmap!!)
    assertTrue(rr > 180 && rg < 80, "Expected RED bitmap for red.png, got ($rr,$rg)")

    // Step 2: switch to PRE-CACHED green.png — MUST immediately switch to GREEN (never stay frozen on RED!)
    active = green
    waitUntil(timeoutMillis = 5_000) {
      val b = latest.bitmap ?: return@waitUntil false
      val (r, g, _) = centerRgb(b)
      g > 150 && r < 80
    }

    // Step 3: switch to UNCACHED blue.png — MUST load BLUE!
    active = blue
    waitUntil(timeoutMillis = 5_000) {
      val b = latest.bitmap ?: return@waitUntil false
      val (r, _, bl) = centerRgb(b)
      bl > 180 && r < 80
    }

    // Step 4: switch back to now-cached red.png — MUST switch back to RED!
    active = red
    waitUntil(timeoutMillis = 5_000) {
      val b = latest.bitmap ?: return@waitUntil false
      val (r, g, _) = centerRgb(b)
      r > 180 && g < 80
    }
  }

  @Test fun previewPlayerStopResetsFailedAndDoneStatesAndRapidSwitchLeaksZeroResources() {
    val openDecoders = AtomicInteger(0)
    val openOutputs = AtomicInteger(0)

    fun makeDecoder(chunks: Int, fail: Boolean = false, delayMs: Long = 15L) = object : MediaDecoder {
      override val name = "fake"
      private val closed = AtomicBoolean(false)
      init { openDecoders.incrementAndGet() }
      override fun probe(file: File) = MediaInfo(60_000, false, true)
      override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? = null
      override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {
        if (fail) throw RuntimeException("simulated decoder failure")
        val buf = ByteArray(1024)
        for (i in 0 until chunks) {
          if (closed.get() || Thread.currentThread().isInterrupted) return
          sink(buf, buf.size)
          if (delayMs > 0) Thread.sleep(delayMs)
        }
      }
      override fun close() {
        if (closed.compareAndSet(false, true)) openDecoders.decrementAndGet()
      }
    }

    fun makeOutput() = object : PcmOut {
      private val closed = AtomicBoolean(false)
      private var written = 0L
      init { openOutputs.incrementAndGet() }
      override fun write(bytes: ByteArray, length: Int) { written += length }
      override fun playedMs(): Long = written / 32L
      override fun finish() {}
      override fun close() {
        if (closed.compareAndSet(false, true)) openOutputs.decrementAndGet()
      }
    }

    fun waitState(p: PreviewPlayer, cond: (PlayState) -> Boolean) {
      val end = System.currentTimeMillis() + 5_000
      while (System.currentTimeMillis() < end) {
        if (cond(p.state.value)) return
        Thread.sleep(10)
      }
      throw AssertionError("Timed out; state=${p.state.value}")
    }

    // 1. Failed state on File A MUST clear back to Idle + null activeFile on stop() (switching to File B)
    val failPlayer = PreviewPlayer({ makeDecoder(1, fail = true) }, { makeOutput() })
    val fileA = File("/tmp/audio-a.mp3")
    failPlayer.play(fileA, 0L)
    waitState(failPlayer) { it is PlayState.Failed }
    assertEquals(fileA, failPlayer.activeFile.value)
    failPlayer.stop()
    assertEquals(PlayState.Idle, failPlayer.state.value, "stop() must reset Failed back to Idle so next file never shows stale error")
    assertNull(failPlayer.activeFile.value)

    // 2. Done state MUST clear back to Idle + null activeFile on stop()
    val donePlayer = PreviewPlayer({ makeDecoder(2, delayMs = 2L) }, { makeOutput() })
    donePlayer.play(File("/tmp/audio-b.mp3"), 0L)
    waitState(donePlayer) { it is PlayState.Done }
    donePlayer.stop()
    assertEquals(PlayState.Idle, donePlayer.state.value, "stop() must reset Done back to Idle")
    assertNull(donePlayer.activeFile.value)

    // 3. Rapid 25-file play/stop churn must leave ZERO open decoders, ZERO open audio outputs, and Idle state
    val churnPlayer = PreviewPlayer({ makeDecoder(40, delayMs = 20L) }, { makeOutput() })
    for (i in 0 until 25) {
      churnPlayer.play(File("/tmp/churn-$i.mp3"), i * 1_000L)
      Thread.sleep(5)
    }
    churnPlayer.stop()
    val deadline = System.currentTimeMillis() + 5_000
    while ((openDecoders.get() != 0 || openOutputs.get() != 0) && System.currentTimeMillis() < deadline) {
      Thread.sleep(15)
    }
    assertEquals(PlayState.Idle, churnPlayer.state.value)
    assertNull(churnPlayer.activeFile.value)
    assertEquals(0, openDecoders.get(), "All decoders must be closed after rapid switch + stop")
    assertEquals(0, openOutputs.get(), "All audio outputs must be closed after rapid switch + stop")
  }

  @Test fun frameThumbsCancelsStaleQueuedScrubDecodesAndDeliversFinalFrame() = runBlocking {
    val dir = tmpDir("trawl-frames-cancel")
    val video = File(dir, "clip.mp4").apply { writeBytes(ByteArray(32)) }
    val decodeCount = AtomicInteger(0)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val blueJpeg = createColoredJpegBytes(Color(30, 80, 230))

    val fakeDec = object : MediaDecoder {
      override val name = "fake-frames"
      override fun probe(file: File) = MediaInfo(120_000, true, false)
      override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray {
        val n = decodeCount.incrementAndGet()
        if (n == 1) {
          firstStarted.countDown()
          releaseFirst.await(3, TimeUnit.SECONDS)
        }
        return blueJpeg
      }
      override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {}
      override fun close() {}
    }

    val frames = FrameThumbs({ fakeDec })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      // Launch first scrub request that occupies the single worker thread
      val job0 = scope.launch { frames.loadSuspend(video, 0L, 160, video.lastModified()) }
      assertTrue(firstStarted.await(3, TimeUnit.SECONDS))

      // Queue 15 intermediate scrub timestamps, then cancel all 15 before the first decode finishes!
      val staleJobs = (1..15).map { idx ->
        scope.launch {
          try {
            frames.loadSuspend(video, idx * 2_000L, 160, video.lastModified())
          } catch (_: CancellationException) {
          }
        }
      }
      delay(40)
      staleJobs.forEach { it.cancel() }
      job0.cancel()
      releaseFirst.countDown()

      // Final active scrub timestamp must decode immediately without waiting for all 15 cancelled decodes!
      val finalBmp = frames.loadSuspend(video, 50_000L, 160, video.lastModified())
      assertNotNull(finalBmp)
      assertTrue(
        decodeCount.get() <= 3,
        "Cancelled intermediate scrub frames must be skipped; expected <= 3 decodes, got ${decodeCount.get()}",
      )
    } finally {
      scope.cancel()
      frames.close()
    }
  }

  @Test fun mixedMediaRapidSwitchStressInQuickPreviewNeverBleedsState() = runComposeUiTest {
    val dir = tmpDir("trawl-mixed-preview")
    val pdf1 = createColoredPdf(dir, "alpha.pdf", Color(210, 40, 40))
    val img1 = createColoredPng(dir, "photo.png", Color(40, 200, 60))
    val vid1 = File(dir, "clip.mp4").apply { writeBytes(ByteArray(32)) }
    val aud1 = File(dir, "memo.m4a").apply { writeBytes(ByteArray(32)) }
    val json1 = File(dir, "config.json").apply { writeText("""{"service":"trawl","offline":true}""") }
    val md1 = File(dir, "readme.md").apply { writeText("# Release Notes\nAll local search.") }
    val pdf2 = createColoredPdf(dir, "beta.pdf", Color(40, 70, 210))

    val hits = listOf(
      QuickHit(indexed(1, pdf1, FileKind.PDF), "Alpha PDF match"),
      QuickHit(indexed(2, img1, FileKind.IMAGE), null),
      QuickHit(indexed(3, vid1, FileKind.VIDEO, durationMs = 30_000L), "Video match"),
      QuickHit(indexed(4, aud1, FileKind.AUDIO, durationMs = 20_000L), "Audio match"),
      QuickHit(indexed(5, json1, FileKind.DOCUMENT), null),
      QuickHit(indexed(6, md1, FileKind.DOCUMENT), null),
      QuickHit(indexed(7, pdf2, FileKind.PDF), "Beta PDF match"),
    )

    val player = PreviewPlayer({ null })
    val previewApi = object : QuickPreviewApi {
      override val player: PreviewPlayer = player
      override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? =
        Thumbnails.load(fileOf(file), side, file.modifiedMillis)
      override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap = ImageBitmap(48, 36)
      override suspend fun lead(file: IndexedFile): String? =
        fileOf(file).takeIf { it.name.endsWith(".json") || it.name.endsWith(".md") }?.readText()
      override fun fileOf(file: IndexedFile): File = File(java.net.URI(file.uri))
    }

    var idx by mutableStateOf(0)
    var mounted by mutableStateOf(true)
    setContent {
      SunlightTheme {
        if (mounted) {
          QuickPreview(
            hit = hits[idx],
            hidden = false,
            query = "offline",
            preview = previewApi,
            mod = "⌘",
            onOpen = {},
            onFolder = {},
            onFull = {},
            onClose = {},
          )
        }
      }
    }

    // Trigger a failed playback attempt on Video, then rapidly cycle through every media kind to PDF #2
    idx = 2
    waitForIdle()
    player.play(vid1, 0L)
    waitUntil(timeoutMillis = 5_000) { player.state.value is PlayState.Failed }

    for (next in listOf(3, 4, 5, 6, 0, 1, 6)) {
      idx = next
      waitForIdle()
    }

    // Switching off Video must have reset PreviewPlayer cleanly to Idle
    assertEquals(PlayState.Idle, player.state.value)
    assertNull(player.activeFile.value)

    // Final active item is beta.pdf: must show beta.pdf title & render beta.pdf (distinct from alpha.pdf!)
    waitUntil(timeoutMillis = 8_000) {
      onAllNodesWithText("beta.pdf").fetchSemanticsNodes().isNotEmpty() &&
        onAllNodesWithContentDescription("beta.pdf").fetchSemanticsNodes().isNotEmpty()
    }
    val bmpAlpha = PdfThumbs.peek(pdf1, 1, side = 560, modifiedMillis = pdf1.lastModified())
    val bmpBeta = PdfThumbs.peek(pdf2, 1, side = 560, modifiedMillis = pdf2.lastModified())
    assertNotNull(bmpBeta)
    if (bmpAlpha != null) assertNotEquals(centerRgb(bmpAlpha), centerRgb(bmpBeta))

    // Disposing QuickPreview leaves player strictly Idle
    mounted = false
    waitForIdle()
    assertEquals(PlayState.Idle, player.state.value)
  }
}
