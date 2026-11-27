package com.craneai.findr.extract

import kotlinx.coroutines.CancellationException
import com.craneai.findr.core.AudioWindow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.channels.Channel
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.AsrEngine
import com.craneai.findr.engine.Tuning
import com.craneai.findr.core.AudioDecoder
import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SoundTags
import com.craneai.findr.core.SpeechSegment
import com.craneai.findr.core.SpokenWord
import com.craneai.findr.core.SungReader
import com.craneai.findr.core.WordTimes
import com.craneai.findr.engine.LocalSpeech
import com.craneai.findr.engine.ModelHold
import com.craneai.findr.engine.SpeechPack
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "FindrAsr"

/**
 * Speech to words with sherpa-onnx: Silero VAD cuts the soundtrack into speech, Moonshine tiny (int8) reads each piece,
 * CED-mini puts AudioSet labels on every 10 s so the sounds without words (a dog, a siren, applause) are findable too.
 * The settings are the ones the eval measured (the experiment code): 2 threads for the recogniser (the A06's two big cores;
 * more threads were slower), VAD at 0.5 with at most 28 s per segment, labels at probability 0.15, top 5.
 *
 * Two recognisers, chosen by `engine.txt` in the models folder ("parakeet" or "moonshine"; without it, Parakeet when installed):
 * Parakeet-TDT-CTC 110M int8 (CC-BY-4.0; Mac WER 12.8% at 15 s chunks, A06 RTF 0.114, 281 MB per process) with VAD segments
 * capped at 15 s, or Moonshine tiny (MIT; WER 25%, RTF 0.164).
 *
 * Models live in `files/models/asr/` (pushed by scripts/push-asr-models.sh; not in the APK yet). Everything loads on first use
 * and stays loaded while the indexer runs; [release] gives the memory back.
 *
 * The opt-in speech add-on ([packDir]: the folder of the speech pack in use, [SpeechPack]) is a second recogniser, a FastConformer CTC
 * for one language, that reads again the segments a [LocalSpeech.Reading] picks once a window's first pass is done (LocalSpeech has the
 * rules), one segment at a time, with the frame and lateness its pack.json gives. It is loaded when a file needs it and KEPT for the next
 * file ([ModelHold]: reloading it per file cost 40% of the October add-on's time), and given back after [ModelHold.IDLE_MS] without use,
 * at the end of a run, a pause, memory pressure ([releaseLocal]), a [release] and when the pack is removed or another is chosen. On a
 * tight phone (MemoryBudget: one heavy model at a time) it is not kept: it is released before the call returns, so it is never resident
 * beside the next window's first pass, and [tight] also releases the English recogniser and the sound labeller while it is loaded.
 */
class SherpaAsrEngine(
  private val modelsDir: File,
  private val decoder: AudioDecoder = MediaAudioDecoder(),
  private val packDir: () -> File? = { null },
  private val tight: () -> Boolean = { false },
) : AsrEngine {
  private val mutex = Mutex()
  private var recognizer: OfflineRecognizer? = null
  private var vad: Vad? = null
  private var tagger: AudioTagging? = null

  private val moonshine = File(modelsDir, MOONSHINE_DIR)
  private val parakeet = File(modelsDir, PARAKEET_DIR)
  private val vadModel = File(modelsDir, VAD_FILE)
  private val ced = File(modelsDir, CED_DIR)

  private val hasMoonshine get() = MOONSHINE_FILES.all { File(moonshine, it).isFile }
  private val hasParakeet get() = PARAKEET_FILES.all { File(parakeet, it).isFile }

  /** Which recogniser to use: the one named in engine.txt if installed, else Parakeet, else Moonshine. */
  private fun useParakeet(): Boolean {
    val want = runCatching { File(modelsDir, "engine.txt").readText().trim() }.getOrNull()
    return when {
      want == "moonshine" && hasMoonshine -> false
      hasParakeet -> true
      else -> false
    }
  }

  private val installed: Boolean
    get() = (hasParakeet || hasMoonshine) && vadModel.isFile

  private var loadedParakeet = false
  private var loadedThreads: Int? = null
  private val localHold = ModelHold<OfflineRecognizer>(close = { it.release() })
  @Volatile private var localLoaded: String? = null   // the stamp of the pack whose model [localHold] holds
  @Volatile private var packCache: Pair<String, SpeechPack?>? = null   // pack.json path + its time -> the pack, so it is read once

  /** The speech pack in use and installed, read from its pack.json (cached until the file changes). */
  private fun pack(): Pair<File, SpeechPack>? {
    val dir = packDir() ?: return null
    val json = File(dir, SpeechPack.FILE)
    val key = "${json.absolutePath}:${json.lastModified()}"
    val p = packCache?.takeIf { it.first == key }?.second ?: SpeechPack.read(dir).also { packCache = key to it }
    return p?.let { dir to it }
  }
  @Volatile private var nativeOk: Boolean? = null

  override fun available(): Boolean {
    if (!installed) return false
    nativeOk?.let { return it }
    return try {
      Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")   // its static initialiser loads the JNI library
      true
    } catch (t: Throwable) {
      Log.w(TAG, "sherpa-onnx native library does not load", t)
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.SPEECH, t)
      false
    }.also { nativeOk = it }
  }

  override fun localAvailable(): Boolean = available() && pack() != null

  override fun localStamp(): String? = if (available()) pack()?.second?.stamp else null

  override suspend fun transcribe(context: Context, uri: Uri, maxSeconds: Int, startMs: Long, reading: LocalSpeech.Reading?): AudioTranscript = withContext(Dispatchers.Default) {
    check(available()) { "speech models are not installed in $modelsDir" }
    val started = SystemClock.elapsedRealtime()
    coroutineScope {
      // Decode on its own thread while VAD and recognition work through what has arrived, so the stage costs about the
      // slower of the two instead of their sum: decoding was ~70% of it on the A06 (the platform's software AAC decoder).
      val windows = Channel<AudioWindow>(capacity = 3)
      var decodedMs = -1L
      launch(Dispatchers.IO) {
        try {
          decoder.stream(context, uri, DECODE_WINDOW_MS, DECODE_WINDOW_MS, (maxSeconds * 1000L / DECODE_WINDOW_MS).toInt().coerceAtLeast(1), startMs) { w ->
            windows.trySendBlocking(w).onFailure { throw CancellationException("transcription stopped") }
          }
        } finally {
          decodedMs = SystemClock.elapsedRealtime() - started
          windows.close()
        }
      }
      try {
        val first = windows.receiveCatching().getOrNull() ?: return@coroutineScope AudioTranscript(emptyList(), emptyList(), 0.0)
        recognise(first, windows, started, startMs, reading?.let { r -> pack()?.let { r to it } }) { decodedMs }
      } finally {
        windows.cancel()
      }
    }
  }

  private suspend fun recognise(
    first: AudioWindow, rest: Channel<AudioWindow>, started: Long, offsetMs: Long,
    second: Pair<LocalSpeech.Reading, Pair<File, SpeechPack>>?, decodedMs: () -> Long,
  ): AudioTranscript =
    mutex.withLock {
      val parakeetNow = useParakeet()
      if (parakeetNow != loadedParakeet) { recognizer?.release(); recognizer = null; vad?.release(); vad = null }
      val threads = Tuning.current.asrThreads
      if (loadedThreads != threads) { recognizer?.release(); recognizer = null }
      val rec = recognizer ?: buildRecognizer(parakeetNow, threads).also {
        recognizer = it; loadedParakeet = parakeetNow; loadedThreads = threads
      }
      val detector = vad ?: buildVad(if (parakeetNow) Tuning.current.asrMaxSpeechS.toFloat() else MAX_SPEECH_S).also { vad = it }
      val tags = if (ced.isDirectory) tagger ?: buildTagger()?.also { tagger = it } else null
      detector.reset()
      val speech = ArrayList<SpeechSegment>()
      val heardAudio = java.util.IdentityHashMap<SpeechSegment, FloatArray>()   // with a [second] read: each spoken segment's samples, kept for it
      val sounds = ArrayList<SoundTags>()
      var fed = 0L              // samples given to the VAD so far
      var tagBuffer = FloatArray(0)
      var tagStart = 0L         // samples before the tag buffer
      var waitedMs = 0L         // time spent waiting for the decoder: the stage is decode-bound when this is large
      val ctx = kotlin.coroutines.coroutineContext
      // Stretches the labeller calls singing are read whole, not in the VAD's fragments (SungReader, sung-words.md).
      val sungCap = (if (parakeetNow) Tuning.current.asrMaxSpeechS.toFloat() else MAX_SPEECH_S).let { (it * RATE).toInt() }
      val sung = SungReader(RATE, sungCap, MIN_SPEECH_SAMPLES, { x, n, cap -> SpeechSplit.ranges(x, n, cap, RATE) }) { piece, from ->
        val at = offsetMs + from * 1000L / RATE
        val heard = read(rec, piece, at)
        if (heard.text.isBlank()) null else SpeechSegment(at, at + piece.size * 1000L / RATE, heard.text, heard.words, sung = true)
      }

      fun drain() {
        while (!detector.empty()) {
          val seg = detector.front()
          detector.pop()
          if (seg.samples.size < MIN_SPEECH_SAMPLES || sung.covers(seg.start.toLong())) continue
          // The VAD only splits at a pause, so continuous speech or music can come out a minute long; cap what Parakeet is given.
          val cap = if (parakeetNow) (Tuning.current.asrMaxSpeechS * RATE) else Int.MAX_VALUE
          for (r in SpeechSplit.ranges(seg.samples, seg.samples.size, cap, RATE)) {
            if (r.last + 1 - r.first < MIN_SPEECH_SAMPLES) continue
            val piece = if (r.first == 0 && r.last + 1 == seg.samples.size) seg.samples else seg.samples.copyOfRange(r.first, r.last + 1)
            val startMs = offsetMs + (seg.start + r.first) * 1000L / RATE
            val heard = read(rec, piece, startMs)
            // With a second read in view a segment Parakeet wrote nothing for stays (blank) so that it can be read again; it is dropped
            // after it unless the second read kept something, which is what it would have been without the add-on.
            if (heard.text.isNotBlank() || second != null) {
              val s = SpeechSegment(startMs, startMs + piece.size * 1000L / RATE, heard.text, heard.words)
              speech += s
              if (second != null) heardAudio[s] = piece
            }
          }
        }
      }

      fun tag() {   // label every full 10 s of the buffer
        if (tags == null) return
        while (tagBuffer.size >= TAG_SAMPLES) {
          val win = tagBuffer.copyOfRange(0, TAG_SAMPLES)
          val labels = label(tags, win)
          sounds += SoundTags(offsetMs + tagStart * 1000L / RATE, offsetMs + (tagStart + TAG_SAMPLES) * 1000L / RATE, labels)
          sung.window(tagStart, win, labels)
          tagBuffer = tagBuffer.copyOfRange(TAG_SAMPLES, tagBuffer.size)
          tagStart += TAG_SAMPLES
        }
      }

      val wholeWindow = second != null && LocalSpeech.WHOLE_NOTE && LocalSpeech.WHOLE_WINDOW_MAX_S > 0
      val windowSamples = ArrayList<FloatArray>()   // whole-window reading: the window's sound, kept for the pack
      var bufferedSamples = 0L
      val maxBufferedSamples = MAX_WHOLE_BUFFER_S.toLong() * RATE
      var w: AudioWindow? = first
      while (w != null) {
        ctx.ensureActive()
        val x = pcm(w.wav)
        if (wholeWindow && bufferedSamples < maxBufferedSamples) {
          val take = minOf(x.size.toLong(), maxBufferedSamples - bufferedSamples).toInt()
          windowSamples += if (take == x.size) x else x.copyOf(take)
          bufferedSamples += take
        }
        var i = 0
        while (i < x.size) {
          val n = minOf(VAD_FEED, x.size - i)
          detector.acceptWaveform(x.copyOfRange(i, i + n))
          i += n
        }
        fed += x.size
        if (tags != null) {   // labels first, so the VAD pieces of a sung stretch are not read twice
          tagBuffer += x
          tag()
        }
        drain()
        val t0 = SystemClock.elapsedRealtime()
        w = rest.receiveCatching().getOrNull()
        waitedMs += SystemClock.elapsedRealtime() - t0
      }
      if (tags != null && tagBuffer.size >= RATE) {   // the last, shorter stretch (at least a second)
        val labels = label(tags, tagBuffer)
        sounds += SoundTags(offsetMs + tagStart * 1000L / RATE, offsetMs + (tagStart + tagBuffer.size) * 1000L / RATE, labels)
        sung.window(tagStart, tagBuffer, labels)
      }
      sung.finish()
      detector.flush()
      drain()
      val firstAll = SungReader.merge(speech, sung, offsetMs, RATE)
      val seconds = fed.toDouble() / RATE
      val firstMs = SystemClock.elapsedRealtime() - started
      val heardAll = when {
        second == null -> firstAll
        wholeWindow -> readWholeWindow(second.first, second.second, firstAll, windowSamples, offsetMs)
        else -> readAgain(second.first, second.second, firstAll, heardAudio)
      }
      Log.i(TAG, "transcribed ${"%.1f".format(seconds)} s of audio in $firstMs ms " +
        "(decode ${decodedMs()} ms, alongside; waited $waitedMs ms for it): ${heardAll.size} speech segments " +
        "(${sung.segments.size} sung, ${"%.1f".format(sung.readSamples.toDouble() / RATE)} s read as song), " +
        "${heardAll.sumOf { it.text.length }} chars, ${sounds.count { it.labels.isNotEmpty() }} labelled windows" +
        (if (second != null) ", ${heardAll.count { it.local != null }} read again by ${second.second.second.stamp} in ${SystemClock.elapsedRealtime() - started - firstMs} ms" else ""))
      AudioTranscript(heardAll, sounds.filter { it.labels.isNotEmpty() }, seconds)
    }

  /**
   * The second read ([LocalSpeech.readAgain]): [reading] picks the segments, the pack's model reads each from its samples ([audio]), one
   * at a time, and its reading rides on the segment as [SpeechSegment.local]. The model is loaded on the first segment that needs it and
   * kept by [localHold] for the next file unless the phone is [tight]; a stop or an exception leaves it to the hold's rules (the hold
   * closes it on release, idle or tight). A different pack than the one held is loaded afresh.
   */
  private suspend fun readAgain(reading: LocalSpeech.Reading, installed: Pair<File, SpeechPack>, speech: List<SpeechSegment>, audio: Map<SpeechSegment, FloatArray>): List<SpeechSegment> {
    val (dir, pack) = installed
    if (reading.route(speech).isEmpty()) return speech.filter { it.text.isNotBlank() }
    if (localLoaded != null && localLoaded != pack.stamp) localHold.release()
    if (tight()) { recognizer?.release(); recognizer = null; loadedThreads = null; vad?.release(); vad = null; tagger?.release(); tagger = null }
    return localHold.lease(tight, { buildLocalRecognizer(dir, pack) }) { rec ->
      LocalSpeech.readAgain(speech, reading) read@{ _, s ->
        val samples = audio[s] ?: return@read null
        val heard = read(rec(), samples, s.startMs, pack.frameMs, pack.lateBiasMs)
        SpeechSegment(s.startMs, s.endMs, heard.text, heard.words)
      }
    }
  }

  /**
   * Whole-window reading ([LocalSpeech.WHOLE_WINDOW_MAX_S]): when [reading] routes the window, its spoken segments give way to the pack's
   * reading of ALL its sound, in pieces of at most that many seconds cut at quiet points, one at a time; sung segments stay.
   */
  private suspend fun readWholeWindow(reading: LocalSpeech.Reading, installed: Pair<File, SpeechPack>, first: List<SpeechSegment>, blocks: MutableList<FloatArray>, offsetMs: Long): List<SpeechSegment> {
    val (dir, pack) = installed
    if (reading.route(first).isEmpty()) { blocks.clear(); return first.filter { it.text.isNotBlank() } }
    if (localLoaded != null && localLoaded != pack.stamp) localHold.release()
    if (tight()) { recognizer?.release(); recognizer = null; loadedThreads = null; vad?.release(); vad = null; tagger?.release(); tagger = null }
    val total = blocks.sumOf { it.size }
    val all = FloatArray(total).also { a ->
      var o = 0
      for (idx in blocks.indices) {
        val b = blocks[idx]
        b.copyInto(a, o)
        o += b.size
      }
    }
    // Release chunk references before loading the local recognizer so peak heap never holds 2x audio + ONNX model together.
    blocks.clear()
    return localHold.lease(tight, { buildLocalRecognizer(dir, pack) }) { rec ->
      // The English model's segments stay, without a second reading, so the gate still judges the window on its words (and the detail
      // still says "speech not English", which the pack's catch-up needs); whole-note keep uses only the pack's readings.
      val out = ArrayList<SpeechSegment>(first.filter { it.text.isNotBlank() })
      for (r in SpeechSplit.ranges(all, all.size, LocalSpeech.WHOLE_WINDOW_MAX_S * RATE, RATE)) {
        kotlin.coroutines.coroutineContext.ensureActive()
        if (r.last + 1 - r.first < MIN_SPEECH_SAMPLES) continue
        reading.beforeEachRead()
        val at = offsetMs + r.first * 1000L / RATE
        val end = offsetMs + (r.last + 1) * 1000L / RATE
        val heard = read(rec(), all.copyOfRange(r.first, r.last + 1), at, pack.frameMs, pack.lateBiasMs)
        if (heard.text.isNotBlank()) out += SpeechSegment(at, end, "", local = SpeechSegment(at, end, heard.text, heard.words))
      }
      out.sortedBy { it.startMs }
    }
  }

  /** What one piece of audio said: the cleaned text, and when each word of it began where the recogniser gives times (Parakeet; Moonshine gives none). */
  private class Heard(val text: String, val words: List<SpokenWord>)

  private fun read(rec: OfflineRecognizer, samples: FloatArray, startMs: Long, frameMs: Long = WordTimes.FRAME_MS, lateBiasMs: Long = WordTimes.LATE_BIAS_MS): Heard {
    val stream = rec.createStream()
    try {
      stream.acceptWaveform(samples, RATE)
      rec.decode(stream)
      val r = rec.getResult(stream)
      val seconds = samples.size.toDouble() / RATE
      val text = clean(r.text, seconds)
      return Heard(text, WordTimes.forText(text, r.tokens.asList(), r.timestamps, seconds, startMs, frameMs, lateBiasMs))
    } finally {
      stream.release()
    }
  }

  private fun label(tg: AudioTagging, samples: FloatArray): List<String> {
    val stream = tg.createStream()
    try {
      stream.acceptWaveform(samples, RATE)
      return tg.compute(stream, TAG_TOP_K).filter { it.prob >= TAG_MIN_PROB }.map { it.name }.distinct()
    } finally {
      stream.release()
    }
  }

  private fun buildRecognizer(useParakeet: Boolean, threads: Int): OfflineRecognizer {
    val t = SystemClock.elapsedRealtime()
    val config = if (useParakeet) {
      fun f(n: String) = File(parakeet, n).absolutePath
      OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        nemo = OfflineNemoEncDecCtcModelConfig(model = f("model.int8.onnx")),
        tokens = f("tokens.txt"), numThreads = threads, debug = false,
      ))
    } else {
      fun f(n: String) = File(moonshine, n).absolutePath
      OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        moonshine = OfflineMoonshineModelConfig(
          preprocessor = f("preprocess.onnx"), encoder = f("encode.int8.onnx"),
          uncachedDecoder = f("uncached_decode.int8.onnx"), cachedDecoder = f("cached_decode.int8.onnx"),
        ),
        tokens = f("tokens.txt"), numThreads = threads, debug = false,
      ))
    }
    return OfflineRecognizer(null, config).also { Log.i(TAG, "speech model (${if (useParakeet) "parakeet" else "moonshine"}) loaded in ${SystemClock.elapsedRealtime() - t} ms") }
  }

  private fun buildLocalRecognizer(dir: File, pack: SpeechPack): OfflineRecognizer {
    val t = SystemClock.elapsedRealtime()
    val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
      nemo = OfflineNemoEncDecCtcModelConfig(model = File(dir, pack.model).absolutePath),
      tokens = File(dir, pack.tokens).absolutePath, numThreads = Tuning.current.asrThreads, debug = false,
    ))
    return OfflineRecognizer(null, config).also {
      localLoaded = pack.stamp
      Log.i(TAG, "speech pack ${pack.stamp} loaded in ${SystemClock.elapsedRealtime() - t} ms")
    }
  }

  private fun buildVad(maxSpeechS: Float): Vad = Vad(
    null,
    VadModelConfig(
      sileroVadModelConfig = SileroVadModelConfig(
        model = vadModel.absolutePath, threshold = 0.5f, minSilenceDuration = 0.4f, minSpeechDuration = 0.15f,
        windowSize = 512, maxSpeechDuration = maxSpeechS,
      ),
      sampleRate = RATE, numThreads = 1, debug = false,
    ),
  )

  private fun buildTagger(): AudioTagging? = try {
    AudioTagging(
      null,
      AudioTaggingConfig(
        model = AudioTaggingModelConfig(ced = File(ced, "model.int8.onnx").absolutePath, numThreads = 2, debug = false),
        labels = File(ced, "class_labels_indices.csv").absolutePath, topK = TAG_TOP_K,
      ),
    )
  } catch (t: Throwable) {
    Log.w(TAG, "sound labels unavailable", t)
    com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.SPEECH, t)
    null
  }

  override fun releaseLocal() {
    localHold.release()
    localLoaded = null
  }

  override suspend fun release() = mutex.withLock {
    releaseLocal()
    recognizer?.release(); recognizer = null
    loadedThreads = null
    vad?.release(); vad = null
    tagger?.release(); tagger = null
  }

  companion object {
    const val MOONSHINE_DIR = "moonshine-tiny-en-int8"
    val MOONSHINE_FILES = listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt")
    const val PARAKEET_DIR = "parakeet-tdt-ctc-110m-int8"
    val PARAKEET_FILES = listOf("model.int8.onnx", "tokens.txt")
    const val VAD_FILE = "silero_vad.onnx"
    const val CED_DIR = "ced-mini-int8"
    private const val RATE = 16_000
    /** Cap whole-window audio buffer to 90 s (~5.7 MB) so unknown-duration audio never causes a 38 MB heap spike beside ONNX. */
    private const val MAX_WHOLE_BUFFER_S = 90
    private const val DECODE_WINDOW_MS = 30_000L
    private const val VAD_FEED = 512 * 16        // 0.5 s per call
    private const val MIN_SPEECH_SAMPLES = (0.3 * RATE).toInt()
    private const val MAX_SPEECH_S = 28f
    // Parakeet's segment cap is Tuning.asrMaxSpeechS (15 s: flat from 28 s down to 15 s, WER 12.5 -> 12.8%, and 2.5x smaller in memory)
    private const val TAG_SAMPLES = 10 * RATE
    private const val TAG_TOP_K = 5
    private const val TAG_MIN_PROB = 0.15f

    /** 16-bit little-endian PCM after the 44-byte WAV header, as floats in [-1, 1). */
    fun pcm(wav: ByteArray): FloatArray {
      val n = (wav.size - 44) / 2
      val out = FloatArray(n.coerceAtLeast(0))
      for (i in out.indices) {
        val lo = wav[44 + 2 * i].toInt() and 0xff
        val hi = wav[45 + 2 * i].toInt()
        out[i] = ((hi shl 8) or lo).toShort() / 32768f
      }
      return out
    }

    /**
     * Moonshine, like every small decoder, sometimes loops on noise ("the the the the…"). Drops text that is faster than
     * anyone speaks (> 7 words a second) and cuts a word repeated more than 4 times in a row to 4.
     */
    fun clean(raw: String, seconds: Double): String = WordTimes.clean(raw, seconds)
  }
}
