package trawl.desktop

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FormatNotReadable
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.engine.AudioPolicy
import com.craneai.findr.engine.Chunker
import com.craneai.findr.engine.SpeechLanguageGate
import com.craneai.findr.engine.TranscriptPieces
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** What the processor needs from the speech side: the engine says whether it can run, and reads a stretch of a file. */
interface Transcriber {
  fun available(): Boolean
  fun transcribe(decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript
}

/** What the processor needs from the models: a picture's vector and a text's vector (DesktopEmbedder is both). */
interface MediaEmbedder {
  /** False on the Save data pack's text-only model: video frames are not embedded as pictures; their captions and soundtrack are read. */
  val vision: Boolean get() = true
  fun image(jpeg: ByteArray): FloatArray
  fun document(text: String): FloatArray
}

/** One step of one file: the pieces to save, the window to go on with (null: this was the last), and the line for the index's detail. */
class MediaStep(val segments: List<Segment>, val next: Int?, val detail: String?)

/**
 * The phone's FileProcessor video and soundtrack steps (the same schedule, language gate and pieces), on top of a [MediaDecoder] and a
 * [Transcriber] instead of Android's codecs and its ASR engine:
 *  - a video step samples frames evenly across its window, keeps one per look (a 64-bit difference hash), embeds each kept frame as a
 *    picture, reads any caption text in it, and reads the soundtrack beside the frames (or after them for a long single-step video);
 *  - a recording step is the soundtrack alone;
 *  - the speech goes through the language gate: English is kept whole, mixed keeps its English runs, anything else keeps only the sound labels.
 *    (The phone also embeds the audio of speech the gate dropped with EmbeddingGemma's audio encoder. That encoder is not in the
 *    desktop engine, so on a computer such a recording is findable by its sound labels and name only.)
 */
class MediaProcessor(
  private val decoder: MediaDecoder,
  private val embedder: MediaEmbedder,
  private val transcriber: Transcriber?,
  private val gate: SpeechLanguageGate,
  private val ocr: () -> Ocr?,
  private val tmpDir: File,
  private val stop: () -> Boolean = { false },
) {
  /** Reads [info] of [file]'s window [window] (null: the whole file in one step). [windows] is how many there are. */
  fun step(file: IndexedFile, path: File, info: MediaInfo, window: Int?, windows: Int): MediaStep {
    val next = if (window != null && window + 1 < windows) window + 1 else null
    return when (file.kind) {
      FileKind.VIDEO -> video(file, path, info, window, next)
      FileKind.AUDIO -> audio(file, path, info, window, next)
      else -> MediaStep(emptyList(), null, "not readable")
    }
  }

  private fun audio(file: IndexedFile, path: File, info: MediaInfo, window: Int?, next: Int?): MediaStep {
    if (!info.hasAudio) throw FormatNotReadable("no sound in it")
    val plan = AudioPolicy.plan(FileKind.AUDIO, file.durationMs, speechToText = true, speechPack = transcriber?.available() == true, deeper = false)
    if (plan.nothing) return MediaStep(emptyList(), null, IndexStore.AUDIO_OFF)
    val late = (window ?: 0) > 0
    val track = if (window == null) soundtrack(path, 0, null, late, soundFile = true)
    else soundtrack(path, window * MediaSchedule.MEDIA_WINDOW_MS, MediaSchedule.MEDIA_WINDOW_MS, late, soundFile = true)
    return MediaStep(track.segments, next, track.detail)
  }

  private class Track(val segments: List<Segment>, val detail: String?)

  private fun video(file: IndexedFile, path: File, info: MediaInfo, window: Int?, next: Int?): MediaStep {
    val windowed = window != null
    val startMs = (window ?: 0) * MediaSchedule.MEDIA_WINDOW_MS
    val durationMs = file.durationMs ?: info.durationMs
    val late = (window ?: 0) > 0
    val seconds = if (windowed) (MediaSchedule.MEDIA_WINDOW_MS / 1000).toInt() else ((durationMs ?: 0L) / 1000).toInt()
    // Up to two minutes (and any window of a long video) the soundtrack is read beside the frames; a longer single step reads it after,
    // so a decoder, the picture engine and the speech engine are not all in memory at once.
    val together = windowed || seconds <= MediaSchedule.OVERLAP_MAX_SECONDS
    // A sliver of a last window (a video 3 min 0.2 s long has one) holds no speech the decoder can open: leave it out, quietly.
    val wantsSound = info.hasAudio && (!windowed || soundWorthReading(durationMs, startMs))
    fun trackOrNote(): Track = try {
      if (windowed) soundtrack(path, startMs, MediaSchedule.MEDIA_WINDOW_MS, late) else soundtrack(path, 0, null, late)
    } catch (e: FormatNotReadable) {
      Track(emptyList(), "soundtrack can't be opened on this computer")
    }
    val sound: CompletableFuture<Track>? = if (wantsSound && together) CompletableFuture.supplyAsync(::trackOrNote, SOUND_POOL) else null

    var cantOpen: FormatNotReadable? = null
    var frameCount = 0
    var sampleNs = 0L
    var embedNs = 0L
    var ocrNs = 0L
    class Kept(val hash: Long, val start: Long, var end: Long, val vector: CompletableFuture<FloatArray>?)
    val kept = ArrayList<Kept>()
    val captions = LinkedHashSet<String>()   // a meme's caption repeats in every frame: keep each line once
    try {
      if (info.hasVideo) {
        val n = if (windowed) MediaSchedule.framesInWindow(window!!, durationMs) else MediaSchedule.framesFor(seconds)
        val span = if (windowed) MediaSchedule.MEDIA_WINDOW_MS else (durationMs ?: 1000L)
        for (shot in MediaSchedule.shots(startMs, span, n)) {
          if (stop()) throw java.util.concurrent.CancellationException("stopped")
          val ts = System.nanoTime()
          val jpeg = try { decoder.frame(path, shot.midMs, MediaSchedule.EMBED_MAX_SIDE) } catch (e: FormatNotReadable) { cantOpen = e; break }
          sampleNs += System.nanoTime() - ts
          if (jpeg == null) continue
          frameCount++
          val image = runCatching { ImageIO.read(jpeg.inputStream()) }.getOrNull()
          val hash = image?.let(MediaSchedule::differenceHash)
          val same = hash?.let { h -> kept.firstOrNull { MediaSchedule.similar(it.hash, h) } }
          if (same != null) { same.end = maxOf(same.end, shot.endMs); continue }
          // The picture's vector is computed while the caption OCR reads the same frame (different processors, as on the phone).
          val te = System.nanoTime()
          val vector = if (embedder.vision) CompletableFuture.supplyAsync({ embedder.image(jpeg) }, EMBED_POOL) else null
          val to = System.nanoTime()
          readCaption(jpeg)?.let { captions += it }
          ocrNs += System.nanoTime() - to
          kept += Kept(hash ?: 0L, shot.startMs, shot.endMs, vector)
          vector?.join()
          embedNs += System.nanoTime() - te
        }
      }
    } catch (t: Throwable) {
      sound?.cancel(true)
      throw t
    }
    val segments = ArrayList<Segment>()
    kept.forEach { k -> k.vector?.let { v -> segments += Segment(SegmentKind.FRAME, Modality.IMAGE, k.start, k.end, vector = v.join(), late = late) } }
    if (captions.isNotEmpty()) {
      val words = captions.joinToString("\n")
      // Without picture vectors (the text-only model) the caption words get their own text vector, as a picture's words do.
      if (embedder.vision) segments += Segment(SegmentKind.OCR, Modality.NONE, text = words, late = late)
      else Chunker.chunks(words).take(Indexer.MAX_READ_CHUNKS).forEachIndexed { i, c ->
        segments += Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = words.takeIf { i == 0 }, vector = embedder.document(c), late = late)
      }
    }
    var detail = "$frameCount frames, ${kept.size} distinct (sample ${secs(sampleNs)} embed ${secs(embedNs)} ocr ${secs(ocrNs)})"
    val soundtrack: Track? = sound?.join() ?: if (wantsSound && !together) trackOrNote() else null
    soundtrack?.let { t -> segments += t.segments; t.detail?.let { detail += ", $it" } }
    // Nothing came out of the picture or the sound, and the computer said it could not open the video: a failure, not an empty file.
    cantOpen?.let { if (frameCount == 0 && soundtrack?.segments.isNullOrEmpty()) throw it }
    if (cantOpen != null && frameCount == 0) detail += ", pictures: ${IndexStore.CANT_OPEN}"
    return MediaStep(segments, next, detail)
  }

  /** Words in a frame: the OCR's, kept only when they are text and not specks; one line per caption line. */
  private fun readCaption(jpeg: ByteArray): String? {
    val reader = ocr() ?: return null
    tmpDir.mkdirs()
    val tmp = File.createTempFile("frame", ".jpg", tmpDir)
    try {
      tmp.writeBytes(jpeg)
      return Indexer.readable(reader.read(tmp))?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n")
    } catch (e: Exception) {
      return null   // a picture OCR cannot read has no caption; it is still a picture
    } finally { tmp.delete() }
  }

  /**
   * A file's soundtrack: the words and sound labels from the speech engine, through the language gate. [spanMs] null: the first five
   * minutes (the phone's single-step cap). [soundFile]: this is a recording, so a decoder that produced no sound at all is a failure.
   */
  private fun soundtrack(path: File, startMs: Long, spanMs: Long?, late: Boolean, soundFile: Boolean = false): Track {
    val engine = transcriber?.takeIf { it.available() }
    if (engine == null) return Track(emptyList(), IndexStore.SPEECH_MISSING)
    val ta = System.nanoTime()
    val t = engine.transcribe(decoder, path, startMs, spanMs ?: SINGLE_STEP_MS, stop)
    val asrNs = System.nanoTime() - ta
    if (soundFile && startMs == 0L && t.seconds <= 0.0) throw FormatNotReadable("no audio could be decoded")
    val tt = System.nanoTime()
    // The English-only recogniser writes English-looking junk for other languages: keep the transcript only when it is English.
    val verdict = gate.judge(t.speech.joinToString(" ") { it.text })
    val speech = when {
      verdict.english -> TranscriptPieces.speech(t)
      verdict.partial.isNotEmpty() -> TranscriptPieces.ofText(verdict.partial)
      else -> emptyList()
    }
    val sounds = TranscriptPieces.sounds(t)
    val segments = ArrayList<Segment>()
    for (x in speech) {
      if (stop()) throw java.util.concurrent.CancellationException("stopped")
      segments += Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, x.startMs, x.endMs, x.text, embedder.document(x.text), late, x.marks)
    }
    sounds?.let { x -> segments += Segment(SegmentKind.SOUND, Modality.SOUND, x.startMs, x.endMs, x.text, embedder.document(x.text), late, x.marks) }
    // No audio encoder on a computer, so the "audio vector" half of the phone's note is never there.
    val note = SpeechLanguageGate.note(verdict, speech.size, audioVectors = false) + (if (sounds != null) ", sounds" else "") +
      " (speech ${secs(asrNs)} text ${secs(System.nanoTime() - tt)})"
    return Track(segments, note)
  }

  private fun secs(ns: Long) = String.format(java.util.Locale.ROOT, "%.1f s", ns / 1e9)

  companion object {
    /** Less than this much sound left at [startMs] (in a file [durationMs] long; null when unknown) is not read: decoders cannot open a sliver, and it holds no words. */
    const val MIN_SOUND_MS = 500L

    fun soundWorthReading(durationMs: Long?, startMs: Long): Boolean = durationMs == null || durationMs - startMs >= MIN_SOUND_MS

    /** The phone reads a single-step file's first five minutes of sound at most (it asked ASR for 300 s). */
    const val SINGLE_STEP_MS = 300_000L
    private val EMBED_POOL = Executors.newSingleThreadExecutor { r -> Thread(r, "trawl-frame-embed").apply { isDaemon = true } }
    private val SOUND_POOL = Executors.newCachedThreadPool { r -> Thread(r, "trawl-soundtrack").apply { isDaemon = true } }
  }
}
