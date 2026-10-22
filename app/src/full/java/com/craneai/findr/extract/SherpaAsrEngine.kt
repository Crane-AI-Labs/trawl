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
 */
class SherpaAsrEngine(
  private val modelsDir: File,
  private val decoder: AudioDecoder = MediaAudioDecoder(),
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

  override suspend fun transcribe(context: Context, uri: Uri, maxSeconds: Int, startMs: Long): AudioTranscript = withContext(Dispatchers.Default) {
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
        recognise(first, windows, started, startMs) { decodedMs }
      } finally {
        windows.cancel()
      }
    }
  }

  private suspend fun recognise(first: AudioWindow, rest: Channel<AudioWindow>, started: Long, offsetMs: Long, decodedMs: () -> Long): AudioTranscript =
    mutex.withLock {
      val parakeetNow = useParakeet()
      if (parakeetNow != loadedParakeet) { recognizer?.release(); recognizer = null; vad?.release(); vad = null }
      val rec = recognizer ?: buildRecognizer(parakeetNow).also { recognizer = it; loadedParakeet = parakeetNow }
      val detector = vad ?: buildVad(if (parakeetNow) Tuning.current.asrMaxSpeechS.toFloat() else MAX_SPEECH_S).also { vad = it }
      val tags = if (ced.isDirectory) tagger ?: buildTagger()?.also { tagger = it } else null
      detector.reset()
      val speech = ArrayList<SpeechSegment>()
      val sounds = ArrayList<SoundTags>()
      var fed = 0L              // samples given to the VAD so far
      var tagBuffer = FloatArray(0)
      var tagStart = 0L         // samples before the tag buffer
      var waitedMs = 0L         // time spent waiting for the decoder: the stage is decode-bound when this is large
      val ctx = kotlin.coroutines.coroutineContext

      fun drain() {
        while (!detector.empty()) {
          val seg = detector.front()
          detector.pop()
          if (seg.samples.size < MIN_SPEECH_SAMPLES) continue
          // The VAD only splits at a pause, so continuous speech or music can come out a minute long; cap what Parakeet is given.
          val cap = if (parakeetNow) (Tuning.current.asrMaxSpeechS * RATE) else Int.MAX_VALUE
          for (r in SpeechSplit.ranges(seg.samples, seg.samples.size, cap, RATE)) {
            if (r.last + 1 - r.first < MIN_SPEECH_SAMPLES) continue
            val piece = if (r.first == 0 && r.last + 1 == seg.samples.size) seg.samples else seg.samples.copyOfRange(r.first, r.last + 1)
            val text = read(rec, piece)
            if (text.isNotBlank()) {
              val startMs = offsetMs + (seg.start + r.first) * 1000L / RATE
              speech += SpeechSegment(startMs, startMs + piece.size * 1000L / RATE, text)
            }
          }
        }
      }

      fun tag() {   // label every full 10 s of the buffer
        if (tags == null) return
        while (tagBuffer.size >= TAG_SAMPLES) {
          sounds += SoundTags(offsetMs + tagStart * 1000L / RATE, offsetMs + (tagStart + TAG_SAMPLES) * 1000L / RATE, label(tags, tagBuffer.copyOfRange(0, TAG_SAMPLES)))
          tagBuffer = tagBuffer.copyOfRange(TAG_SAMPLES, tagBuffer.size)
          tagStart += TAG_SAMPLES
        }
      }

      var w: AudioWindow? = first
      while (w != null) {
        ctx.ensureActive()
        val x = pcm(w.wav)
        var i = 0
        while (i < x.size) {
          val n = minOf(VAD_FEED, x.size - i)
          detector.acceptWaveform(x.copyOfRange(i, i + n))
          i += n
        }
        fed += x.size
        drain()
        if (tags != null) {
          tagBuffer += x
          tag()
        }
        val t0 = SystemClock.elapsedRealtime()
        w = rest.receiveCatching().getOrNull()
        waitedMs += SystemClock.elapsedRealtime() - t0
      }
      detector.flush()
      drain()
      if (tags != null && tagBuffer.size >= RATE) {   // the last, shorter stretch (at least a second)
        sounds += SoundTags(offsetMs + tagStart * 1000L / RATE, offsetMs + (tagStart + tagBuffer.size) * 1000L / RATE, label(tags, tagBuffer))
      }
      val seconds = fed.toDouble() / RATE
      Log.i(TAG, "transcribed ${"%.1f".format(seconds)} s of audio in ${SystemClock.elapsedRealtime() - started} ms " +
        "(decode ${decodedMs()} ms, alongside; waited $waitedMs ms for it): ${speech.size} speech segments, " +
        "${speech.sumOf { it.text.length }} chars, ${sounds.count { it.labels.isNotEmpty() }} labelled windows")
      AudioTranscript(speech, sounds.filter { it.labels.isNotEmpty() }, seconds)
    }

  private fun read(rec: OfflineRecognizer, samples: FloatArray): String {
    val stream = rec.createStream()
    try {
      stream.acceptWaveform(samples, RATE)
      rec.decode(stream)
      return clean(rec.getResult(stream).text, samples.size.toDouble() / RATE)
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

  private fun buildRecognizer(useParakeet: Boolean): OfflineRecognizer {
    val t = SystemClock.elapsedRealtime()
    val config = if (useParakeet) {
      fun f(n: String) = File(parakeet, n).absolutePath
      OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        nemo = OfflineNemoEncDecCtcModelConfig(model = f("model.int8.onnx")),
        tokens = f("tokens.txt"), numThreads = Tuning.current.asrThreads, debug = false,
      ))
    } else {
      fun f(n: String) = File(moonshine, n).absolutePath
      OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
        moonshine = OfflineMoonshineModelConfig(
          preprocessor = f("preprocess.onnx"), encoder = f("encode.int8.onnx"),
          uncachedDecoder = f("uncached_decode.int8.onnx"), cachedDecoder = f("cached_decode.int8.onnx"),
        ),
        tokens = f("tokens.txt"), numThreads = Tuning.current.asrThreads, debug = false,
      ))
    }
    return OfflineRecognizer(null, config).also { Log.i(TAG, "speech model (${if (useParakeet) "parakeet" else "moonshine"}) loaded in ${SystemClock.elapsedRealtime() - t} ms") }
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

  override fun release() {
    recognizer?.release(); recognizer = null
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
    fun clean(raw: String, seconds: Double): String {
      val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      if (words.isEmpty() || words.size / maxOf(seconds, 0.5) > 7.0) return ""
      val out = ArrayList<String>(words.size)
      var run = 0
      for (w in words) {
        run = if (out.isNotEmpty() && out.last().equals(w, ignoreCase = true)) run + 1 else 1
        if (run <= 4) out += w
      }
      return out.joinToString(" ")
    }
  }
}
