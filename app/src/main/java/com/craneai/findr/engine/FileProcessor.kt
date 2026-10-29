package com.craneai.findr.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.core.AsrEngine
import com.craneai.findr.core.AudioDecoder
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FormatNotReadable
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.OcrEngine
import com.craneai.findr.core.TextExtractor
import com.craneai.findr.core.VideoSampler
import com.craneai.findr.extract.DocumentTextExtractor
import com.craneai.findr.extract.MediaAudioDecoder
import com.craneai.findr.extract.MediaVideoSampler
import com.craneai.findr.extract.MlKitOcrEngine
import com.craneai.findr.extract.PdfPageRenderer
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.index.Segment
import com.craneai.findr.index.Modality
import java.io.ByteArrayOutputStream
import com.craneai.findr.core.VideoFrame
import com.craneai.findr.core.FrameMoment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val TAG = "FindrProcessor"

/** What a piece of a file is. `vector == null` means words only (FTS), e.g. text read from an image (M2). */
enum class PieceKind { CHUNK, OCR_PAGE, IMAGE, OCR, FRAME, AUDIO, TRANSCRIPT, SOUND }
/** CLIP and OCR_TEXT come only from the lite profile: the quick-look model's picture vector, and the picture's text embedded. */
enum class PieceModality { TEXT, IMAGE, AUDIO, SOUND, CLIP, OCR_TEXT }

/**
 * The lite profile's picture model: quick look's image tower on the CPU, instead of EmbeddingGemma's vision encoder. When it
 * can't run ([ready] false: the pack isn't installed, or quick look is off) a picture keeps its words and text vectors and is
 * marked to be read again once it can ([com.craneai.findr.index.IndexStore.QUICK_MISSING]).
 */
interface QuickImages {
  /** The model can run now (its pack is installed and quick look is on). */
  val ready: Boolean
  /** A picture file, decoded the way the quick-look pass decodes it (the input its phone parity was measured on); null if unreadable. */
  fun ofFile(uri: Uri): FloatArray?
  /** A video frame already in memory. */
  fun ofBitmap(bitmap: Bitmap): FloatArray
  /** The quick-look pass already gave this file its picture vector (it runs beside the words pass): no need to compute it again. */
  fun has(fileId: Long): Boolean = false
}

class Piece(
  val kind: PieceKind,
  val modality: PieceModality,
  val locStart: Long? = null,   // page (1-based) for documents, ms for video/audio
  val locEnd: Long? = null,
  val text: String? = null,     // goes into FTS and snippets
  val vector: FloatArray? = null,
  val marks: String? = null,    // transcript pieces: where each spoken word sits in the text (core.TranscriptMarks); frames: the keyframe's own time (core.FrameMoment)
)

/** One step of a file's indexing. `nextChunk` set: a long document has more to read in a later pass (DigUp). */
class Processed(val pieces: List<Piece>, val nextChunk: Int?, val detail: String?)

/**
 * Turns one file into searchable pieces. The rules come from the M2 eval (the build notes):
 * documents → ~400-char chunks, embedded and in FTS; scanned PDF pages → OCR, which *is* their text, so embedded too;
 * images, screenshots, memes → the image embedded, OCR text into FTS only (embedding it hurt documents, 0.95 → 0.88);
 * videos → one frame per shot embedded, burned-in captions into FTS, soundtrack windows embedded when audio is on.
 */
class FileProcessor(
  private val context: Context,
  private val embedder: Embedder,
  private val text: TextExtractor = DocumentTextExtractor(),
  private val ocr: OcrEngine = MlKitOcrEngine(),
  private val video: VideoSampler = MediaVideoSampler(),
  private val audio: AudioDecoder = MediaAudioDecoder(),
  private val asr: AsrEngine? = null,
  /** Tells English speech from the junk an English-only speech model writes for Luganda or Swahili (see [SpeechLanguageGate]). */
  private val speechGate: SpeechLanguageGate = SpeechLanguageGate.fromAssets(ContextAssets(context)),
  /** Waits (up to the given ms) while the user is scrolling or typing. The worker supplies it; one video holds the CPU for seconds. */
  private val pauseForUser: suspend (maxMs: Long, gpu: Boolean) -> Unit = { _, _ -> },
  /** True while the worker should yield to the user (the same condition [pauseForUser] waits on; `gpu` for GPU steps). */
  private val userBusy: (gpu: Boolean) -> Boolean = { false },
  /** A long document's chunks from the index (file id, from, limit → chunks, total), for steps after the first. */
  private val storedChunks: ((fileId: Long, from: Int, limit: Int) -> Pair<List<Pair<Long?, String>>, Int>)? = null,
  /** Raised while speech recognition runs, so other CPU work (the quick-look pass) can step aside meanwhile. */
  private val speechActive: java.util.concurrent.atomic.AtomicInteger? = null,
  /** The lite profile's picture model (used only when [Embedder.imagesByEmbeddingGemma] is false). */
  private val quick: QuickImages? = null,
  /**
   * A phone short of memory (MemoryBudget): the speech model leaves before EmbeddingGemma's audio encoder loads, so the two are
   * never resident together (a Luganda recording on a 3.7 GB phone held both and the vision engine: 1.7-1.9 GB).
   */
  private val oneHeavyModelAtATime: () -> Boolean = { false },
) {
  /** The lite profile: pictures and frames by the quick-look model, their text embedded by EmbeddingGemma's text model. */
  private val lite get() = !embedder.imagesByEmbeddingGemma
  private val yieldToUser = UserYield(MAX_WAIT_PER_FILE_MS, pauseForUser, userBusy)

  /** Time the last [process] call spent waiting for the user, which isn't indexing time (excluded from pace and logs). */
  val waitedMs: Long get() = yieldToUser.waitedMs

  /** Between expensive steps inside one file: see [UserYield] (waits for the user, defers a file past its budget). */
  private suspend fun letUserFirst(gpu: Boolean = false) = yieldToUser.letUserFirst(gpu)

  /**
   * [withAudio]: the "Deeper sound search" setting, embed soundtracks with EmbeddingGemma's audio encoder. [withSpeech]: read
   * them with the speech model (words + sound labels). For a sound file, [AudioPolicy] decides what is done: a short
   * recording with no working speech model gets the audio vector even with [withAudio] off. Videos use the flags as given.
   */
  suspend fun process(file: IndexedFile, fromChunk: Int = 0, withAudio: Boolean = false, withSpeech: Boolean = false): Processed {
    val uri = Uri.parse(file.uri)
    yieldToUser.reset()
    // A long video or recording is read one window (MEDIA_WINDOW_MS) per step: `fromChunk` is the window. Each step is saved by
    // the worker, so memory is that of a one-minute file whatever the length, and a kill only loses the window in hand.
    val windows = windowCount(file.durationMs)
    val window = if (windows > 1 && (file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO)) fromChunk else null
    if (window != null && window >= windows) return Processed(emptyList(), null, null)
    return when (file.kind) {
      FileKind.DOCUMENT, FileKind.PDF -> document(file, uri, fromChunk)
      FileKind.IMAGE, FileKind.SCREENSHOT -> image(file, uri)
      FileKind.VIDEO -> video(file, uri, withAudio, withSpeech, window, windows)
      FileKind.AUDIO -> AudioPolicy.plan(file.kind, file.durationMs, withSpeech, asr?.available() == true, withAudio).let { plan ->
        if (plan.nothing) Processed(emptyList(), null, com.craneai.findr.index.IndexStore.AUDIO_OFF)
        else if (window == null) soundtrack(uri, plan.vector, plan.speech, soundFile = file)
        else soundtrack(uri, plan.vector, plan.speech, window * MEDIA_WINDOW_MS, MEDIA_WINDOW_MS).let {
          Processed(it.pieces, if (window + 1 < windows) window + 1 else null, it.detail)
        }
      }
      FileKind.OTHER -> Processed(emptyList(), null, "not readable")
    }
  }

  /**
   * The fast pass: words only, no model. A document's whole text (every chunk, not just the first step) and the text
   * read from an image, so both are searchable by their words long before they are understood. Null for kinds this
   * pass doesn't cover (video captions need frames, which wait for the slow pass).
   */
  suspend fun words(file: IndexedFile): List<Piece>? {
    val uri = Uri.parse(file.uri)
    return when (file.kind) {
      FileKind.DOCUMENT, FileKind.PDF -> {
        val pages = text.extract(context, uri, file.mimeType, file.name).pages.toMutableList()
        // Trawl Go has no slow pass to read a scan later, so its words pass reads the first scanned pages itself (full: the slow pass does).
        if (Edition.GO && file.kind == FileKind.PDF && pages.any { it.isBlank() }) readScannedPages(uri, pages)
        pages.flatMapIndexed { page, body -> Chunker.chunks(body).map { Piece(PieceKind.CHUNK, PieceModality.TEXT, page + 1L, page + 1L, it) } }
      }
      FileKind.IMAGE, FileKind.SCREENSHOT -> {
        val bitmap = decode(uri, Edition.ocrMaxSide()) ?: return emptyList()
        try { listOfNotNull(readable(ocr.read(bitmap))?.let { Piece(PieceKind.OCR, PieceModality.TEXT, text = it) }) }
        finally { bitmap.recycle() }
      }
      else -> null
    }
  }

  /** Trawl Go: OCR the first [Edition.GO_OCR_PDF_PAGES] blank pages of a PDF, one at a time at a heap-safe size, in place. */
  private suspend fun readScannedPages(uri: Uri, pages: MutableList<String>) {
    PdfPageRenderer.open(context, uri)?.use { renderer ->
      var read = 0
      for (i in pages.indices) {
        if (pages[i].isNotBlank()) continue
        if (read >= Edition.GO_OCR_PDF_PAGES) break
        coroutineContext.ensureActive()
        letUserFirst()
        val bitmap = renderer.render(i, Edition.GO_PDF_PAGE_WIDTH) ?: continue
        try { pages[i] = ocr.read(bitmap) } finally { bitmap.recycle() }
        read++
      }
    }
  }

  private suspend fun document(file: IndexedFile, uri: Uri, fromChunk: Int): Processed {
    // Later steps: the first step stored every chunk's text, so embed the next ones from the index, not the file.
    if (fromChunk > 0) storedChunks?.invoke(file.id, fromChunk, CHUNKS_PER_STEP)?.let { (chunks, total) ->
      if (chunks.isNotEmpty()) {
        val pieces = chunks.map { (page, chunk) ->
          coroutineContext.ensureActive()
          letUserFirst()
          Piece(PieceKind.CHUNK, PieceModality.TEXT, page, page, null, embedder.document(chunk))
        }
        val read = fromChunk + chunks.size
        return Processed(pieces, read.takeIf { it < total }, "chunks $read/$total")
      }
    }
    val extracted = text.extract(context, uri, file.mimeType, file.name)
    val pages = extracted.pages.toMutableList()
    // Scanned pages (no text layer) of a PDF: render and read them all here, up to OCR_MAX_PAGES. Later steps embed the
    // chunks this step stores and don't open the file again, so a page not read now is never read (it used to be
    // 10 per step, and pages 11+ of a scan were never read).
    var unread = 0
    if (file.kind == FileKind.PDF && pages.any { it.isBlank() }) {
      PdfPageRenderer.open(context, uri)?.use { renderer ->
        var read = 0
        for (i in pages.indices) {
          if (pages[i].isNotBlank()) continue
          if (read >= OCR_MAX_PAGES) { unread++; continue }
          coroutineContext.ensureActive()
          letUserFirst()
          val bitmap = renderer.render(i) ?: continue
          pages[i] = ocr.read(bitmap)
          bitmap.recycle()
          read++
        }
      }
    }
    val all = pages.flatMapIndexed { page, body -> Chunker.chunks(body).map { page + 1 to it } }
    val step = all.drop(fromChunk).take(CHUNKS_PER_STEP)
    // The first step replaces everything the file had, so it carries the rest of the text too (words only): a long
    // document stays searchable by all its words while later steps add meaning. Those later steps add vectors only,
    // so no text is in the keyword index twice.
    val pieces = step.map { (page, chunk) ->
      coroutineContext.ensureActive()
      letUserFirst()
      Piece(PieceKind.CHUNK, PieceModality.TEXT, page.toLong(), page.toLong(), chunk.takeIf { fromChunk == 0 }, embedder.document(chunk))
    } + if (fromChunk == 0) all.drop(step.size).map { (page, chunk) ->
      Piece(PieceKind.CHUNK, PieceModality.TEXT, page.toLong(), page.toLong(), chunk, null)
    } else emptyList()
    val next = (fromChunk + step.size).takeIf { it < all.size }
    val detail = (if (all.isEmpty()) "no text" else "chunks ${fromChunk + step.size}/${all.size}") +
      (if (unread > 0) ", $unread scanned pages past $OCR_MAX_PAGES not read" else "") +
      (if (extracted.truncated && all.isNotEmpty()) ", long file: first part read" else "")
    return Processed(pieces, next, detail)
  }

  private suspend fun image(file: IndexedFile, uri: Uri): Processed = if (lite) liteImage(file, uri) else coroutineScope {
    val t0 = System.nanoTime()
    var visionMs = 0L
    val bitmap = decode(uri, Edition.ocrMaxSide()) ?: throw notReadable(file, "no decoder produced a picture")
    val decodeMs = (System.nanoTime() - t0) / 1_000_000
    try {
      coroutineContext.ensureActive()
      letUserFirst(gpu = true)
      // An image embedding is ~85% GPU with the CPU idle (A06: ~3.6 of 4.2 s, then ~0.5 s of text tower on the CPU;
      // the research notes), so OCR reads the picture during the embedding instead of before it.
      val tj = System.nanoTime()
      val bytes = jpeg(bitmap, EMBED_MAX_SIDE)
      val jpegMs = (System.nanoTime() - tj) / 1_000_000
      val tv = System.nanoTime()
      val vector = async { embedder.image(bytes).also { visionMs = (System.nanoTime() - tv) / 1_000_000 } }
      val to = System.nanoTime()
      val words = readable(ocr.read(bitmap))
      val ocrMs = (System.nanoTime() - to) / 1_000_000
      val embedded = vector.await()
      // Numbers only (no names), so it is safe in a release log: where a picture's time goes (OCR runs beside the GPU embed).
      Log.i(TAG, "image stages: decode $decodeMs ms, jpeg $jpegMs ms, ocr $ocrMs ms (beside the embed), vision embed $visionMs ms, wall ${(System.nanoTime() - t0) / 1_000_000} ms")
      Processed(listOfNotNull(words?.let { Piece(PieceKind.OCR, PieceModality.TEXT, text = it) }) +
        Piece(PieceKind.IMAGE, PieceModality.IMAGE, vector = embedded), null, null)
    } finally {
      bitmap.recycle()
    }
  }

  /**
   * A picture in the lite profile, one model at a time: OCR reads its text (words, and EmbeddingGemma's text vector of them, so
   * screenshots and memes are found by meaning: eval-en screenshots 0.52 -> 0.87 r@1 against the picture vector alone), then
   * the quick-look model gives the picture's own vector (photos), unless the quick-look pass already did (the worker then keeps
   * that row). No GPU, no vision encoder.
   */
  private suspend fun liteImage(file: IndexedFile, uri: Uri): Processed {
    val bitmap = decode(uri, Edition.ocrMaxSide()) ?: throw notReadable(file, "no decoder produced a picture")
    val words = try {
      coroutineContext.ensureActive()
      letUserFirst()
      readable(ocr.read(bitmap))
    } finally {
      bitmap.recycle()
    }
    val pieces = words?.let { readText(it) }.orEmpty().toMutableList()
    coroutineContext.ensureActive()
    val ready = quick?.ready == true
    val vector = if (ready && !quick!!.has(file.id)) quick.ofFile(uri) else null
    if (vector != null) pieces += Piece(PieceKind.IMAGE, PieceModality.CLIP, vector = vector)
    return Processed(pieces, null, if (ready) null else com.craneai.findr.index.IndexStore.QUICK_MISSING)
  }

  /**
   * Text read from a picture or video frames, in the lite profile: the words for the keyword index, and EmbeddingGemma's text
   * vectors of its ~400-char pieces (at most [MAX_READ_CHUNKS]) as their own modality. The first piece carries the words.
   */
  private suspend fun readText(words: String): List<Piece> =
    Chunker.chunks(words).take(MAX_READ_CHUNKS).mapIndexed { i, chunk ->
      coroutineContext.ensureActive()
      letUserFirst()
      Piece(PieceKind.OCR, PieceModality.OCR_TEXT, text = words.takeIf { i == 0 }, vector = embedder.document(chunk))
    }.ifEmpty { listOf(Piece(PieceKind.OCR, PieceModality.TEXT, text = words)) }

  private suspend fun video(file: IndexedFile, uri: Uri, withAudio: Boolean, withSpeech: Boolean, window: Int? = null, windows: Int = 1): Processed {
    // past the first half hour: the soundtrack only (see DEEP_WINDOWS), unless Tuning.deepFramesPerMin asks for a few pictures a minute
    if (!isDeepWindow(window) && Tuning.current.deepFramesPerMin <= 0) {
      val sound = if (withAudio || withSpeech) soundtrack(uri, withAudio, withSpeech, window!! * MEDIA_WINDOW_MS, MEDIA_WINDOW_MS) else Processed(emptyList(), null, null)
      return Processed(sound.pieces, if (window!! + 1 < windows) window + 1 else null, sound.detail)
    }
    return videoFrames(file, uri, withAudio, withSpeech, window, windows)
  }

  private suspend fun videoFrames(file: IndexedFile, uri: Uri, withAudio: Boolean, withSpeech: Boolean, window: Int?, windows: Int): Processed = coroutineScope {
    // The soundtrack (decode, speech, labels) runs alongside the frames instead of after them: the platform's audio
    // decoder mostly waits, and frames mostly use the GPU path, so a video costs nearer the longer of the two.
    val windowed = window != null
    val startMs = (window ?: 0) * MEDIA_WINDOW_MS
    val seconds = if (windowed) (MEDIA_WINDOW_MS / 1000).toInt() else ((file.durationMs ?: 0) / 1000).toInt()
    // ...but not for a long video: on a 3.7 GB phone (TECNO) a 5-minute video with speech held ~2 GB (GPU vision plus the speech model
    // plus the decoders) and the system's low-memory killer ended the app every time, so the queue never moved (formats sweep).
    // Past two minutes the soundtrack waits until the frames are done.
    // (A window of a long video is a one-minute video as far as memory goes, so its frames and soundtrack do overlap.)
    val together = windowed || shouldOverlapSoundtrack(seconds)
    val wantsSound = withAudio || withSpeech
    // (An async child that throws cancels the whole scope, so the codec-missing case is turned into a note inside it.)
    suspend fun trackOrNote() = try {
      if (windowed) soundtrack(uri, withAudio, withSpeech, startMs, MEDIA_WINDOW_MS) else soundtrack(uri, withAudio, withSpeech)
    } catch (e: FormatNotReadable) {
      Processed(emptyList(), null, "soundtrack can't be opened on this phone")
    }
    val sound = if (wantsSound && together) async { trackOrNote() } else null
    // Frames arrive one at a time: the sampler fetches the next keyframe while this one is on the GPU. A small buffer
    // bounds the bitmaps held at once (each up to 768 px, ~2.4 MB).
    val frames = Channel<VideoFrame>(FRAME_BUFFER)
    var sampleMs = 0L   // the sampler's own time, not counting its waits for a free buffer slot
    var cantOpen: FormatNotReadable? = null   // the sampler could not open the video at all (no codec, damaged)
    launch {
      val t0 = System.nanoTime()
      var waitedNs = 0L
      try {
        video.stream(context, uri, if (windowed) framesInWindow(window!!, file.durationMs, Tuning.current.deepFramesPerMin) else framesFor(seconds), if (windowed) startMs else 0L, if (windowed) MEDIA_WINDOW_MS else Long.MAX_VALUE) { f ->
          val tw = System.nanoTime()
          frames.send(f)
          waitedNs += System.nanoTime() - tw
        }
      } catch (e: FormatNotReadable) {
        cantOpen = e   // not thrown yet: the soundtrack may still be readable, and then the file is not a failure
      } finally {
        sampleMs = (System.nanoTime() - t0 - waitedNs) / 1_000_000
        frames.close()
      }
    }
    var frameCount = 0
    var embedMs = 0L
    var ocrMs = 0L   // where a video's time goes, in its detail line: profile before optimising (the research notes)
    val pieces = mutableListOf<Piece>()
    val captions = LinkedHashSet<String>()   // a meme's caption repeats in every frame: keep each line once
    // A frame that looks the same as one already embedded (a still meme with a soundtrack, a slide held for a minute)
    // gets no embedding of its own: the earlier frame's time range grows to cover it. Same picture, same vector, so
    // nothing is lost, and each skipped frame saves ~4 s on the A06.
    class Kept(val hash: Long, val start: Long, var end: Long, val at: Long, val vector: FloatArray?)
    val kept = mutableListOf<Kept>()
    val quickReady = lite && quick?.ready == true
    for (f in frames) {
      frameCount++
      coroutineContext.ensureActive()
      val hash = f.bitmap?.let(::differenceHash)
      val same = hash?.let { h -> kept.firstOrNull { java.lang.Long.bitCount(it.hash xor h) <= SAME_FRAME_BITS } }
      if (same != null) {
        same.end = maxOf(same.end, f.shotEndMs)
        f.bitmap?.recycle()
        continue
      }
      letUserFirst(gpu = true)
      val te = System.nanoTime()
      // The frame's OCR runs while the GPU embeds it (the CPU is idle for ~85% of an embedding): `embed` is the wall
      // time of both together, `ocr` the OCR's own time inside it. The lite profile reads the frame with the quick-look model
      // first, on the CPU, before OCR recycles the bitmap.
      val vector = if (lite) CompletableDeferred(if (quickReady) quickFrame(f) else null) else async { embedder.image(f.jpeg) }
      f.bitmap?.let { bmp ->
        val to = System.nanoTime()
        try { readable(ocr.read(bmp))?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { captions += it } }
        finally { bmp.recycle() }
        ocrMs += (System.nanoTime() - to) / 1_000_000
      }
      kept += Kept(hash ?: 0L, f.shotStartMs, f.shotEndMs, f.timeMs, vector.await())
      embedMs += (System.nanoTime() - te) / 1_000_000
    }
    val frameModality = if (lite) PieceModality.CLIP else PieceModality.IMAGE
    // The slot is what the picture stands for; its marks say when the picture really is, which is what the result's time shows.
    kept.forEach { k -> k.vector?.let { pieces += Piece(PieceKind.FRAME, frameModality, k.start, k.end, vector = it, marks = FrameMoment.encode(k.at)) } }
    if (captions.isNotEmpty()) {
      val text = captions.joinToString("\n")
      pieces += if (lite) readText(text) else listOf(Piece(PieceKind.OCR, PieceModality.TEXT, text = text))
    }
    var detail = "$frameCount frames, ${kept.size} distinct (sample ${secs(sampleMs)} embed ${secs(embedMs)} ocr ${secs(ocrMs)})"
    if (lite && !quickReady) detail += ", ${com.craneai.findr.index.IndexStore.QUICK_MISSING}"
    // A soundtrack in a codec this phone lacks (AC3 in an .mkv, say) must not lose the frames that were read.
    val soundtrack = sound?.await() ?: if (wantsSound && !together) trackOrNote() else null
    soundtrack?.let { track ->
      pieces += track.pieces
      track.detail?.let { detail += ", $it" }
    }
    // Nothing came out of the picture or the sound, and the phone said it could not open the video: a failure, not an empty file.
    cantOpen?.let { if (frameCount == 0 && soundtrack?.pieces.isNullOrEmpty()) throw notReadable(file, it.why) }
    // The sound was read but the pictures were not (a 4K HEVC or ProRes track this phone has no decoder for): say so in the detail.
    if (cantOpen != null && frameCount == 0) detail += ", pictures: ${com.craneai.findr.index.IndexStore.CANT_OPEN}"
    Processed(pieces, if (window != null && window + 1 < windows) window + 1 else null, detail)
  }

  /** A file's soundtrack: words and sound labels from the speech model, and/or EmbeddingGemma's audio vectors. */
  private suspend fun soundtrack(uri: Uri, withAudio: Boolean, withSpeech: Boolean, startMs: Long = 0, spanMs: Long? = null, soundFile: IndexedFile? = null): Processed {
    // For a sound FILE (not a video's track) a decoder that cut no window at all means this phone can't open it (Speex, a cut-off
    // M4A with no moov): FAILED with the reason, not "understood" with nothing. A real recording always has at least one window.
    var speechSeconds: Double? = null      // audio the speech engine was handed (null: it did not run)
    var vectorWindows: Int? = null         // windows the audio encoder was handed (null: it did not run)
    val pieces = mutableListOf<Piece>()
    val notes = mutableListOf<String>()
    var notEnglish = false
    if (withSpeech) {
      val engine = asr?.takeIf { it.available() }
      if (engine == null) notes += com.craneai.findr.index.IndexStore.SPEECH_MISSING else {
        val ta = System.nanoTime()
        speechActive?.incrementAndGet()
        val t = try { engine.transcribe(context, uri, spanMs?.let { (it / 1000).toInt() } ?: 300, startMs) } finally { speechActive?.decrementAndGet() }
        val asrMs = (System.nanoTime() - ta) / 1_000_000
        speechSeconds = if (t.speech.isEmpty() && t.sounds.isEmpty()) t.seconds else 1.0
        val tt = System.nanoTime()
        // English-only models write English-looking junk for Luganda and Swahili speech; junk transcripts hurt retrieval (the research notes),
        // so a transcript that is not mostly common English words is dropped, keeping only its long English runs (a note that switches
        // language keeps its English part). The sounds line is kept: it comes from the sound classifier.
        val verdict = speechGate.judge(t.speech.joinToString(" ") { it.text })
        notEnglish = !verdict.english
        val speech = when {
          verdict.english -> TranscriptPieces.speech(t)
          verdict.partial.isNotEmpty() ->
            TranscriptPieces.ofText(verdict.partial)
          else -> emptyList()
        }
        val sounds = TranscriptPieces.sounds(t)
        // Each piece is words for FTS and a text embedding for meaning. The sounds line is its own modality, so search centres its
        // cosines apart from the transcripts (tags eval ii: speech r@1 back to 0.84 from 0.76, all 0.73 -> 0.81).
        (speech.map { PieceKind.TRANSCRIPT to it } + listOfNotNull(sounds?.let { PieceKind.SOUND to it })).forEach { (kind, x) ->
          coroutineContext.ensureActive()
          letUserFirst()
          pieces += Piece(kind, if (kind == PieceKind.SOUND) PieceModality.SOUND else PieceModality.TEXT, x.startMs, x.endMs, x.text, embedder.document(x.text), x.marks)
        }
        notes += SpeechLanguageGate.note(verdict, speech.size, embedder.supportsAudio) +
          (if (sounds != null) ", sounds" else "") +
          " (speech ${secs(asrMs)} text ${secs((System.nanoTime() - tt) / 1_000_000)})"
      }
    }
    // The audio vector is the way back in for speech the transcript could not carry: no words to search, but the model hears the meaning
    // (Swahili 0.79 R@1 on the FLEURS clips, Luganda 0.36), even when the "index sounds" setting is off.
    // Not past DEEP_WINDOWS minutes of a long file: the words go on to the end, the sound vector stops (cost).
    if ((withAudio || notEnglish) && embedder.supportsAudio && isDeepWindow((startMs / MEDIA_WINDOW_MS).toInt())) {
      if (oneHeavyModelAtATime()) asr?.release()
      val a = sound(uri, startMs, spanMs)
      pieces += a.pieces
      a.detail?.let { notes += it }
      vectorWindows = a.pieces.size
    }
    if (soundFile != null && decodedNothing(speechSeconds, vectorWindows, soundFile.durationMs)) throw notReadable(soundFile, "no audio could be decoded")
    return Processed(pieces, null, notes.joinToString(", ").ifEmpty { null })
  }

  private suspend fun sound(uri: Uri, startMs: Long = 0, spanMs: Long? = null): Processed {
    if (!embedder.supportsAudio) return Processed(emptyList(), null, "model has no audio encoder")
    val max = spanMs?.let { ceilDiv(it.toInt(), AUDIO_WINDOW_MS.toInt()) } ?: MAX_AUDIO_WINDOWS
    val pieces = audio.windows(context, uri, windowMs = AUDIO_WINDOW_MS, hopMs = AUDIO_WINDOW_MS, maxWindows = max, startMs = startMs).map { w ->
      coroutineContext.ensureActive()
      letUserFirst()
      Piece(PieceKind.AUDIO, PieceModality.AUDIO, w.startMs, w.endMs, vector = embedder.audio(w.wav))
    }
    return Processed(pieces, null, "${pieces.size} sound windows")
  }

  private fun secs(ms: Long) = String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)

  /** A frame's quick-look vector (the lite profile): from its bitmap, or its JPEG when the sampler kept no bitmap. */
  private fun quickFrame(f: VideoFrame): FloatArray? {
    val q = quick ?: return null
    f.bitmap?.let { return q.ofBitmap(it) }
    val decoded = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) ?: return null
    return try { q.ofBitmap(decoded) } finally { decoded.recycle() }
  }

  /** 64-bit difference hash (9×8 grey, each pixel brighter than its right neighbour?): equal for re-encoded copies of one picture. */
  private fun differenceHash(bitmap: Bitmap): Long {
    val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    var hash = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
      if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) hash = hash or (1L shl (y * 8 + x))
    }
    if (small !== bitmap) small.recycle()
    return hash
  }

  private fun luma(c: Int) = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000

  /**
   * The file this phone cannot open: logged with its extension and type (never its name) so a format that SHOULD decode but
   * does not can be spotted in the log, then thrown for the worker to record as failed ([IndexStore.CANT_OPEN]).
   */
  private fun notReadable(file: IndexedFile, why: String): FormatNotReadable {
    val ext = com.craneai.findr.index.Rules.extensionOf(file.name).ifEmpty { "(none)" }
    val kind = file.kind
    val type = file.mimeType ?: "no type"
    Log.w(TAG, "can't open $kind format .$ext ($type): $why")
    return FormatNotReadable(why)
  }

  private fun decode(uri: Uri, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
    context.contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
  } catch (t: Throwable) {
    Log.w(TAG, "decode failed: ${redact(uri)}", t)
    com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.DECODER, t)
    null
  }

  /** The phone's image path from M1: longest side 768 px, JPEG quality 90. */
  private fun jpeg(bitmap: Bitmap, maxSide: Int): ByteArray {
    val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    return ByteArrayOutputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
      .also { if (scaled !== bitmap) scaled.recycle() }
  }

  /** Text read from an image counts only with a few letters in it: logos and stray glyphs don't (as in the eval). */
  private fun readable(s: String): String? = s.trim().takeIf { t -> t.count { it.isLetter() } >= 10 }

  companion object {
    /**
     * True when every stage that ran was handed no audio at all (the extractor found no decodable audio track). A stage that ran and
     * found silence still saw a window, so a silent recording is not this, and neither is a clip too short for a window. Both null (nothing ran) is not this either.
     */
    internal fun decodedNothing(speechSeconds: Double?, vectorWindows: Int?, durationMs: Long? = null): Boolean {
      if ((durationMs ?: 0L) > 0L) return false   // the platform read a length from it, so the file opened (a 0.3 s clip is shorter than one window)
      if (speechSeconds == null && vectorWindows == null) return false
      return (speechSeconds == null || speechSeconds <= 0.0) && (vectorWindows == null || vectorWindows == 0)
    }

    /**
     * The decode factor (a power of two) that leaves the longest side at [maxSide] or more but under twice that, so a picture is
     * never decoded at full size: a 50 MP photo (8165x6124) decodes at 1/2 (about 12.5 MP, 50 MB), a 20000x2000 strip at 1/8.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
      var sample = 1
      while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
      return sample
    }

    const val CHUNKS_PER_STEP = 40        // a long document's first step; the rest comes after everything else (DigUp)
    const val OCR_MAX_PAGES = 50   // scanned pages read per PDF: ~0.5-1 s each on the A06, well inside the 5 min limit
    /**
     * A frame every 5 s for the first minute (memes, clips), every 10 s after, at most 24: on the video eval, frames
     * every 5 s took r@1 from 0.76 to 0.82 and r@5 from 0.90 to 0.97; sound added nothing for 3× the cost, so it is
     * opt-in (settings.indexAudio). A 4-minute video: 24 frames, ~110 s on the A06.
     */
    /** Soundtrack and frames overlap only for a video up to this long; a longer one does them one after the other to bound peak memory. */
    const val OVERLAP_MAX_SECONDS = 120
    fun shouldOverlapSoundtrack(seconds: Int): Boolean = seconds <= OVERLAP_MAX_SECONDS

    fun framesFor(seconds: Int): Int = (ceilDiv(minOf(seconds, 60), 5) + ceilDiv(maxOf(seconds - 60, 0), 10)).coerceIn(1, 24)

    /**
     * Videos and recordings longer than two windows are read a window at a time (one worker step each, saved as it goes): a minute
     * of frames (one every 10 s) plus that minute's soundtrack. Bounded memory whatever the length, and a restart after a kill
     * resumes at the first window not yet saved. The words are read for the whole length: every window of a recording or a video's
     * soundtrack gets its speech, up to [MAX_MEDIA_WINDOWS] (12 hours, a guard against a broken duration, not a limit anyone meets).
     * Only the costly extras stop earlier, at [DEEP_WINDOWS]: see there.
     */
    const val MEDIA_WINDOW_MS = 60_000L
    const val MAX_MEDIA_WINDOWS = 720
    /**
     * Windows (minutes) that also get the heavy extras: a video's frames (GPU vision, OCR, ~10 s a minute on the A06) and the audio
     * vector (EmbeddingGemma's audio encoder, ~0.3 s per second of sound). Past it a window gets its speech and sound labels only, which
     * cost ~15 s a minute on the TECNO and no extra memory. A cost choice: the words stay searchable at every minute of a three-hour
     * lecture; the pictures and the sound vector cover its first half hour.
     */
    const val DEEP_WINDOWS = 30
    fun isDeepWindow(window: Int?): Boolean = window == null || window < DEEP_WINDOWS
    /**
     * Frames per window: 9 in the first three minutes (never fewer than [framesFor] gave a video that length: frame density
     * is what retrieval needs, the research notes), 6 a minute after that. A short last window gets its share, not the full number.
     */
    fun framesInWindow(window: Int, durationMs: Long?, deepFramesPerMin: Int = 0): Int {
      // Past DEEP_WINDOWS the knob sets the density (video() reads no frames there when it is 0).
      val full = if (window >= DEEP_WINDOWS && deepFramesPerMin > 0) deepFramesPerMin.coerceAtMost(6) else if (window < 3) 9 else 6
      val span = ((durationMs ?: Long.MAX_VALUE) - window * MEDIA_WINDOW_MS).coerceIn(1, MEDIA_WINDOW_MS)
      return ceilDiv((full * span).toInt(), MEDIA_WINDOW_MS.toInt()).coerceAtLeast(1)
    }
    fun windowCount(durationMs: Long?): Int =
      if (durationMs == null || durationMs <= 2 * MEDIA_WINDOW_MS) 1 else minOf(ceilDiv(durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), MEDIA_WINDOW_MS.toInt()), MAX_MEDIA_WINDOWS)
    fun isWindowed(kind: FileKind, durationMs: Long?): Boolean = (kind == FileKind.VIDEO || kind == FileKind.AUDIO) && windowCount(durationMs) > 1

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b   // Math.ceilDiv needs API 35; Findr runs on 30+
    /** 10 s windows: 359 ms per second of audio on the A06 against 528 ms for 30 s (the research notes). */
    const val AUDIO_WINDOW_MS = 10_000L
    const val MAX_AUDIO_WINDOWS = 30      // a file read in one step (up to two minutes, or of unknown length): 5 minutes of sound at most (AudioPolicy.SHORT_MS)
    const val SAME_FRAME_BITS = 6         // of 64: the loosest with zero loss on the video eval (the research notes)
    const val FRAME_BUFFER = 2            // frames fetched ahead of the one being embedded
    const val MAX_WAIT_PER_FILE_MS = 60_000L   // the shortest timeout is 2 min (images); a file never waits more than this for the user
    const val OCR_MAX_SIDE = 2048
    const val EMBED_MAX_SIDE = 768
    /** Text pieces embedded per picture in the lite profile: ~1,600 chars, ~0.5-0.8 s each on the A06; the rest stays words. */
    const val MAX_READ_CHUNKS = 4
  }
}

internal fun Piece.toSegment(late: Boolean) = Segment(
  kind = when (kind) {
    PieceKind.CHUNK -> SegmentKind.CHUNK
    PieceKind.OCR_PAGE -> SegmentKind.PAGE
    PieceKind.IMAGE -> SegmentKind.IMAGE
    PieceKind.OCR -> SegmentKind.OCR
    PieceKind.FRAME -> SegmentKind.FRAME
    PieceKind.AUDIO -> SegmentKind.AUDIO
    PieceKind.TRANSCRIPT -> SegmentKind.TRANSCRIPT
    PieceKind.SOUND -> SegmentKind.SOUND
  },
  modality = when (modality) {
    PieceModality.TEXT -> Modality.TEXT
    PieceModality.IMAGE -> Modality.IMAGE
    PieceModality.AUDIO -> Modality.AUDIO
    PieceModality.SOUND -> Modality.SOUND
    PieceModality.CLIP -> Modality.CLIP
    PieceModality.OCR_TEXT -> Modality.OCR_TEXT
  },
  locStart = locStart,
  locEnd = locEnd,
  text = text,
  vector = vector,
  late = late,
  marks = marks,
)
