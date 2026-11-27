package com.craneai.findr.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.craneai.findr.core.AudioDecoder
import com.craneai.findr.core.AudioWindow
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.OcrEngine
import com.craneai.findr.core.OcrLine
import com.craneai.findr.core.TextExtractor
import com.craneai.findr.core.VideoFrame
import com.craneai.findr.core.VideoSampler
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.PendingFile
import com.craneai.findr.index.ReadingStamps
import com.craneai.findr.index.SeesCatchUp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The catch-up's reading of an old video (FileProcessor.videoWords): the same frame loop a fresh read runs, words only. Its words piece is
 * the one a fresh read stores (text, span and line times), nothing is embedded, no soundtrack is opened, every sampled slot's keyframe
 * time comes back, and a long video is read window by window as a fresh read reads it. Real Bitmaps (native graphics), fake decoders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class VideoWordsTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()

  /**
   * A frame for one scene: a grey ramp whose direction differs by scene (so the look-alike hash tells the scenes apart and merges the
   * frames of one scene), and the scene's colour in its first pixel, which is what the fake recogniser reads.
   */
  private fun frame(color: Int, at: Long, start: Long, end: Long): VideoFrame {
    val b = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
    for (y in 0 until 36) for (x in 0 until 64) {
      val v = when (color) { Color.RED -> 255 - x * 4; Color.BLUE -> x * 4; else -> if (y < 18) 255 - x * 4 else x * 4 }
      b.setPixel(x, y, Color.rgb(v, v, v))
    }
    b.setPixel(0, 0, color)
    return VideoFrame(at, start, end, byteArrayOf(1, 2, 3), b)
  }

  private class Calls { val images = AtomicInteger(); val documents = AtomicInteger(); val audio = AtomicInteger(); val sampled = ArrayList<Triple<Int, Long, Long>>() }

  private fun embedder(calls: Calls) = object : Embedder {
    override val profile = MemoryProfile.STANDARD
    override val repairing: StateFlow<Boolean> = MutableStateFlow(false)
    override val modelNames = emptyList<String>()
    override val modelFile: File? = null
    override val supportsAudio = true
    override val imagesByEmbeddingGemma = true
    override val imageFingerprint: String? = "test"
    override val textFingerprint: String? = "test"
    override suspend fun query(text: String) = FloatArray(768)
    override suspend fun warm() {}
    override suspend fun document(text: String, title: String?) = FloatArray(768).also { calls.documents.incrementAndGet() }
    override suspend fun image(jpeg: ByteArray) = FloatArray(768) { if (it == 0) 1f else 0f }.also { calls.images.incrementAndGet() }
    override suspend fun audio(wav: ByteArray) = FloatArray(768).also { calls.audio.incrementAndGet() }
    override suspend fun release() {}
    override suspend fun dropAudioEncoder() {}
  }

  /** Red frames show a one-word sign (the short rule), green ones a long caption (the long rule), blue ones nothing. */
  private val ocr = object : OcrEngine {
    override suspend fun read(bitmap: Bitmap) = readLines(bitmap).joinToString("\n") { it.text }
    override suspend fun readLines(bitmap: Bitmap): List<OcrLine> = when (bitmap.getPixel(0, 0)) {
      Color.RED -> listOf(OcrLine("Harley", 0.95f, 0f, 0.08f, 0.3f, "en", 0.95f, 1))
      Color.GREEN -> listOf(OcrLine("GRAND OPENING TODAY", 0.95f, 0f, 0.08f, 0.6f, "en", 0.95f, 3))
      else -> emptyList()
    }
  }

  /** The sampler's plan for a window (the real one is MediaVideoSampler's evenly spaced slots), with the colours of [scene]. */
  private fun sampler(calls: Calls, scene: (Long) -> Int, keyframeEveryMs: Long = 8_000L, lengthMs: Long) = object : VideoSampler {
    override suspend fun sample(context: Context, uri: Uri, maxFrames: Int) = error("the frame loop streams")
    override suspend fun stream(context: Context, uri: Uri, maxFrames: Int, fromMs: Long, spanMs: Long, onFrame: suspend (VideoFrame) -> Unit) {
      calls.sampled += Triple(maxFrames, fromMs, spanMs)
      val span = minOf(lengthMs - fromMs, spanMs)
      for (i in 0 until maxFrames) {
        val start = span * i / maxFrames; val end = span * (i + 1) / maxFrames
        val at = fromMs + (start + end) / 2 / keyframeEveryMs * keyframeEveryMs   // the keyframe at or before the slot's middle
        onFrame(frame(scene(at), at, fromMs + start, fromMs + end))
      }
    }
  }

  private val noText = object : TextExtractor {
    override fun canRead(mimeType: String?, name: String) = false
    override suspend fun extract(context: Context, uri: Uri, mimeType: String?, name: String, maxChars: Int) = error("not a document")
  }

  private fun audio(calls: Calls) = object : AudioDecoder {
    override suspend fun windows(context: Context, uri: Uri, windowMs: Long, hopMs: Long, maxWindows: Int, startMs: Long): List<AudioWindow> {
      calls.audio.incrementAndGet(); return emptyList()
    }
  }

  private fun processor(calls: Calls, scene: (Long) -> Int, lengthMs: Long) = FileProcessor(
    context, embedder(calls), text = noText, ocr = ocr, video = sampler(calls, scene, lengthMs = lengthMs), audio = audio(calls), asr = null,
    speechGate = SpeechLanguageGate(lazyOf(null)),
  )

  private fun video(lengthMs: Long) =
    IndexedFile(7, "content://media/external/file/7", "Movies/", "clip.mp4", FileKind.VIDEO, "video/mp4", 1_000_000, 1_000, durationMs = lengthMs)

  /** Off the main thread, and bounded: a frame loop that waits for ever fails the test instead of holding the build. */
  private fun run(block: suspend () -> Unit) = runBlocking(Dispatchers.Default) { withTimeout(120_000) { block() } }

  private fun ocrOf(pieces: List<Piece>) = pieces.filter { it.kind == PieceKind.OCR }.map { listOf(it.text, it.locStart, it.locEnd, it.marks) }

  @Test fun freshReadAndCatchUpUseTheSameStoredFramesWithoutDecodingAgain() = run {
    assumeFalse(Edition.GO)
    val directory = Files.createTempDirectory("sees-fresh").toFile()
    val store = IndexStore.open(directory, log = {})
    try {
      store.upsertFromCrawl(listOf(CrawlRecord("test", 1, "content://test/1", "Movies/", "clip.mp4", FileKind.VIDEO, "video/mp4", 100, 1000, durationMs = 45000)))
      val fileId = store.knownMediaStoreIds("test").getValue(1)
      val calls = Calls()
      val processor = processor(calls, { Color.BLUE }, 45000)
      val pending = PendingFile(store.fileById(fileId)!!, FileState.SEARCHABLE, null)
      val result = UnderstandingStep(store, processor).process(pending, false, false, 120000, true)
      assertTrue(result.finished)
      val freshWords = store.read { it.text("SELECT body FROM sees_fts") }
      assertTrue(store.nextOldSees(1).isEmpty())
      val segments = result.segments
      store.saveSegments(fileId, segments, FileState.UNDERSTOOD, info = "video-reading=1 soundtrack-reading=1")
      val noRead = Calls()
      assertTrue(processor(noRead, { error("no frame may be sampled") }, 45000).seesWords(store, fileId))
      assertEquals(freshWords, store.read { it.text("SELECT body FROM sees_fts") })
      assertTrue(noRead.sampled.isEmpty())
      assertEquals(0, noRead.images.get())
      assertEquals(0, noRead.documents.get())
      assertEquals(0, noRead.audio.get())
    } finally {
      store.close()
      directory.deleteRecursively()
    }
  }

  @Test fun windowedFreshReadStampsSeesOnlyAfterItsLastWindow() = run {
    assumeFalse(Edition.GO)
    val directory = Files.createTempDirectory("sees-windows").toFile()
    val store = IndexStore.open(directory, log = {})
    try {
      store.upsertFromCrawl(listOf(CrawlRecord("test", 1, "content://test/1", "Movies/", "clip.mp4", FileKind.VIDEO, "video/mp4", 100, 1000, durationMs = 240000)))
      val fileId = store.knownMediaStoreIds("test").getValue(1)
      val reader = processor(Calls(), { Color.BLUE }, 240000)
      for (window in 0 until 4) {
        val pending = PendingFile(store.fileById(fileId)!!, if (window == 0) FileState.SEARCHABLE else FileState.PARTIAL, if (window == 0) null else "next=$window")
        UnderstandingStep(store, reader).process(pending, false, false, 120000, true)
        val info = store.read { it.text("SELECT info FROM files WHERE id = ?", fileId) }
        if (window < 3) assertFalse(ReadingStamps.has(info, SeesCatchUp.KEY, SeesCatchUp.VERSION))
        else assertTrue(ReadingStamps.has(info, SeesCatchUp.KEY, SeesCatchUp.VERSION))
      }
      assertTrue(store.nextOldSees(1).isEmpty())
    } finally {
      store.close()
      directory.deleteRecursively()
    }
  }

  @Test fun aShortVideosWordsAreWhatAFreshReadStoresWithNothingEmbedded() = run {
    // 45 s: red (a sign) to 16 s, blue to 32 s, green (a caption) after
    val scene = { at: Long -> if (at < 16_000) Color.RED else if (at < 32_000) Color.BLUE else Color.GREEN }
    val fresh = Calls()
    val read = processor(fresh, scene, 45_000).process(video(45_000), withAudio = true)
    assertTrue("a fresh read embeds its distinct pictures", fresh.images.get() > 0)
    assertTrue("and opens the soundtrack", fresh.audio.get() > 0)

    val calls = Calls()
    val words = processor(calls, scene, 45_000).videoWords(video(45_000))
    assertEquals("nothing embedded", 0, calls.images.get())
    assertEquals("no soundtrack", 0, calls.audio.get())
    assertEquals(0, calls.documents.get())
    assertEquals("the same sampler plan", fresh.sampled, calls.sampled)
    assertEquals("the words piece a fresh read stores", ocrOf(read.pieces), ocrOf(words.pieces.map { it.second }))
    assertEquals(listOf("Harley\nGRAND OPENING TODAY"), words.pieces.map { it.second.text })
    assertTrue(words.pieces.all { it.first == 0 })
    // every slot's keyframe: the 9 slots of a 45 s video, each with the keyframe its picture came from
    assertEquals((0 until 9).map { it * 5_000L }, words.stats.frameTimes.keys.sorted())
    assertEquals(0L, words.stats.frameTimes[0L]); assertEquals(16_000L, words.stats.frameTimes[15_000L])
    assertEquals(9, words.stats.frames)
    assertEquals(3, words.stats.distinct)
  }

  @Test fun aVideoWithNoWordsGivesNoPieceAndStillItsTimes() = run {
    val calls = Calls()
    val words = processor(calls, { Color.BLUE }, 30_000).videoWords(video(30_000))
    assertEquals(emptyList<Pair<Int, Piece>>(), words.pieces)
    assertEquals(6, words.stats.frameTimes.size)
    assertEquals(0, calls.images.get())
  }

  @Test fun aLongVideoIsReadWindowByWindowAsAFreshReadReadsIt() = run {
    // 4 minutes: a red sign in the second minute, a green caption in the fourth
    val scene = { at: Long -> if (at in 60_000L until 120_000L) Color.RED else if (at >= 180_000L) Color.GREEN else Color.BLUE }
    val calls = Calls()
    val words = processor(calls, scene, 240_000).videoWords(video(240_000))
    assertEquals("one sampler call per window, at the fresh read's plan",
      (0 until 4).map { w -> Triple(FileProcessor.framesInWindow(w, 240_000, Tuning.current.deepFramesPerMin), w * 60_000L, FileProcessor.MEDIA_WINDOW_MS) },
      calls.sampled)
    assertEquals(listOf(1 to "Harley", 3 to "GRAND OPENING TODAY"), words.pieces.map { it.first to it.second.text })
    // the same windows read fresh, one step each as the worker reads them
    val fresh = Calls()
    val p = processor(fresh, scene, 240_000)
    val freshOcr = (0 until 4).flatMap { w -> p.process(video(240_000), fromChunk = w).pieces.filter { it.kind == PieceKind.OCR } }
    assertEquals(ocrOf(freshOcr), ocrOf(words.pieces.map { it.second }))
    assertEquals(0, calls.images.get())
    assertEquals(9 + 9 + 9 + 6, words.stats.frames)
  }
}
