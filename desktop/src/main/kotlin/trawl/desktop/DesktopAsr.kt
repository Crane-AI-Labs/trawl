package trawl.desktop

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SoundTags
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.craneai.findr.core.SpeechSegment as Spoken

/**
 * 16 kHz mono 16-bit little-endian PCM, arriving in pieces of any size, as floats in [-1, 1). A piece may end in the middle of a sample:
 * the odd byte waits for the next one.
 */
class PcmAssembler {
  private var carry = -1
  /** Converts [length] bytes of [bytes]; the result holds only whole samples (possibly none). */
  fun add(bytes: ByteArray, length: Int): FloatArray {
    val total = length + (if (carry >= 0) 1 else 0)
    val n = total / 2
    val out = FloatArray(n)
    var i = 0
    var k = 0
    if (carry >= 0 && length > 0) { out[0] = sample(carry, bytes[0].toInt()); k = 1; i = 1 }
    else if (carry >= 0) return out
    while (k < n) { out[k++] = sample(bytes[i].toInt() and 0xff, bytes[i + 1].toInt()); i += 2 }
    carry = if (i < length) bytes[i].toInt() and 0xff else -1
    return out
  }
  private fun sample(lo: Int, hi: Int): Float = ((hi shl 8) or (lo and 0xff)).toShort() / 32768f
}

/**
 * Speech to words on the desktop with sherpa-onnx, the phone's engine (SherpaAsrEngine) and its settings: the Silero VAD cuts the sound
 * into speech (at 0.5, no segment over 15 s, and [SpeechSplit] cuts any that still are), Parakeet-TDT-CTC 110M int8 reads each piece,
 * and CED-mini puts AudioSet labels on every 10 s so sounds without words (a dog, a siren, applause) are findable. The models are the
 * `asr` pack of trawl-models.zip, installed under models/asr/. Everything loads on first use and stays while the indexer runs.
 *
 * The decoder (see [MediaDecoder]) runs on its own thread while the VAD and the recogniser work through what has arrived, so a file costs
 * about the slower of the two, not their sum.
 */
class DesktopAsr(private val dir: File, private val threads: () -> Int = { 2 }) : Transcriber, AutoCloseable {
  private var recognizer: OfflineRecognizer? = null
  private var vad: Vad? = null
  private var tagger: AudioTagging? = null
  private var loadedThreads = 0

  private val parakeet = File(dir, PARAKEET_DIR)
  private val vadModel = File(dir, VAD_FILE)
  private val ced = File(dir, CED_DIR)

  /** The speech pack is on disk (the recogniser and the voice detector; the sound labels are optional). */
  fun installed(): Boolean = PARAKEET_FILES.all { File(parakeet, it).isFile } && vadModel.isFile

  @Volatile private var nativeOk: Boolean? = null

  /** Installed, and the native library of this computer loads. */
  override fun available(): Boolean {
    if (!installed()) return false
    nativeOk?.let { return it }
    return try {
      Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")   // its static initialiser loads the native library
      true
    } catch (t: Throwable) { false }.also { nativeOk = it }
  }

  /**
   * Reads [startMs] to [startMs] + [spanMs] of [file]. Times in the result are the file's own (the window's offset is added).
   * [stop] is asked between pieces of sound; a true answer ends the work with a [java.util.concurrent.CancellationException].
   */
  @Synchronized override fun transcribe(decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript {
    check(available()) { "speech models are not installed in $dir" }
    val rec = recognizerNow()
    val detector = vad ?: buildVad().also { vad = it }
    val tags = if (File(ced, "model.int8.onnx").isFile) tagger ?: buildTagger()?.also { tagger = it } else null
    detector.reset()

    val speech = ArrayList<Spoken>()
    val sounds = ArrayList<SoundTags>()
    var fed = 0L
    var tagBuffer = FloatArray(0)
    var tagStart = 0L

    fun drain() {
      while (!detector.empty()) {
        val seg = detector.front()
        detector.pop()
        val samples = seg.samples
        if (samples.size < MIN_SPEECH_SAMPLES) continue
        // The VAD only splits at a pause, so continuous speech or music can come out a minute long; cap what the recogniser is given.
        for (r in SpeechSplit.ranges(samples, samples.size, MediaSchedule.ASR_MAX_SPEECH_S * RATE, RATE)) {
          if (r.last + 1 - r.first < MIN_SPEECH_SAMPLES) continue
          val piece = if (r.first == 0 && r.last + 1 == samples.size) samples else samples.copyOfRange(r.first, r.last + 1)
          val text = read(rec, piece)
          if (text.isNotBlank()) {
            val at = startMs + (seg.start + r.first) * 1000L / RATE
            speech += Spoken(at, at + piece.size * 1000L / RATE, text)
          }
        }
      }
    }

    fun tag() {
      if (tags == null) return
      while (tagBuffer.size >= TAG_SAMPLES) {
        sounds += SoundTags(startMs + tagStart * 1000L / RATE, startMs + (tagStart + TAG_SAMPLES) * 1000L / RATE, label(tags, tagBuffer.copyOfRange(0, TAG_SAMPLES)))
        tagBuffer = tagBuffer.copyOfRange(TAG_SAMPLES, tagBuffer.size)
        tagStart += TAG_SAMPLES
      }
    }

    // Decode on its own thread; a bounded queue keeps a fast decoder from running far ahead of a slow recogniser.
    val queue = ArrayBlockingQueue<Any>(QUEUE_SLOTS)
    val failure = AtomicReference<Throwable?>(null)
    val done = Any()
    val decoding = Thread({
      val assembler = PcmAssembler()
      try {
        decoder.audio(file, startMs, spanMs) { bytes, n ->
          val x = assembler.add(bytes, n)
          if (x.isNotEmpty()) {
            while (!queue.offer(x, 200, TimeUnit.MILLISECONDS)) if (Thread.currentThread().isInterrupted) throw InterruptedException()
          }
        }
      } catch (t: Throwable) { failure.set(t) }
      finally { runCatching { while (!queue.offer(done, 200, TimeUnit.MILLISECONDS)) if (Thread.currentThread().isInterrupted) break } }
    }, "trawl-media-decode").apply { isDaemon = true; start() }

    try {
      while (true) {
        if (stop() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("transcription stopped")
        val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
        if (item === done) break
        val x = item as FloatArray
        var i = 0
        while (i < x.size) {
          val n = minOf(VAD_FEED, x.size - i)
          detector.acceptWaveform(x.copyOfRange(i, i + n))
          i += n
        }
        fed += x.size
        drain()
        if (tags != null) { tagBuffer += x; tag() }
      }
    } finally { decoding.interrupt() }
    failure.get()?.let { if (fed == 0L) throw it as? com.craneai.findr.core.FormatNotReadable ?: com.craneai.findr.core.FormatNotReadable("sound not decoded: ${it.message}", it) }
    detector.flush()
    drain()
    if (tags != null && tagBuffer.size >= RATE) {   // the last, shorter stretch (at least a second)
      sounds += SoundTags(startMs + tagStart * 1000L / RATE, startMs + (tagStart + tagBuffer.size) * 1000L / RATE, label(tags, tagBuffer))
    }
    return AudioTranscript(speech, sounds.filter { it.labels.isNotEmpty() }, fed.toDouble() / RATE)
  }

  private fun recognizerNow(): OfflineRecognizer {
    val want = threads().coerceIn(1, 8)
    if (recognizer != null && want != loadedThreads) { recognizer?.release(); recognizer = null }
    return recognizer ?: buildRecognizer(want).also { recognizer = it; loadedThreads = want }
  }

  private fun read(rec: OfflineRecognizer, samples: FloatArray): String {
    val stream = rec.createStream()
    try {
      stream.acceptWaveform(samples, RATE)
      rec.decode(stream)
      return clean(rec.getResult(stream).text, samples.size.toDouble() / RATE)
    } finally { stream.release() }
  }

  private fun label(tg: AudioTagging, samples: FloatArray): List<String> {
    val stream = tg.createStream()
    try {
      stream.acceptWaveform(samples, RATE)
      return tg.compute(stream, TAG_TOP_K).filter { it.prob >= TAG_MIN_PROB }.map { it.name }.distinct()
    } finally { stream.release() }
  }

  private fun buildRecognizer(numThreads: Int): OfflineRecognizer {
    fun f(n: String) = File(parakeet, n).absolutePath
    val model = OfflineModelConfig.builder()
      .setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(f("model.int8.onnx")).build())
      .setTokens(f("tokens.txt")).setNumThreads(numThreads).setDebug(false).build()
    return OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model).build())
  }

  private fun buildVad(): Vad = Vad(
    VadModelConfig.builder()
      .setSileroVadModelConfig(
        SileroVadModelConfig.builder().setModel(vadModel.absolutePath).setThreshold(0.5f).setMinSilenceDuration(0.4f)
          .setMinSpeechDuration(0.15f).setWindowSize(512).setMaxSpeechDuration(MediaSchedule.ASR_MAX_SPEECH_S.toFloat()).build(),
      )
      .setSampleRate(RATE).setNumThreads(1).setDebug(false).build(),
  )

  private fun buildTagger(): AudioTagging? = try {
    AudioTagging(
      AudioTaggingConfig.builder()
        .setModel(AudioTaggingModelConfig.builder().setCED(File(ced, "model.int8.onnx").absolutePath).setNumThreads(2).setDebug(false).build())
        .setLabels(File(ced, "class_labels_indices.csv").absolutePath).setTopK(TAG_TOP_K).build(),
    )
  } catch (t: Throwable) { null }   // sound labels are an extra: without them the words still work

  /** Gives the memory back (the models take about 0.3 to 0.8 GB while loaded). */
  fun release() {
    recognizer?.release(); recognizer = null
    vad?.release(); vad = null
    tagger?.release(); tagger = null
  }

  override fun close() = release()

  companion object {
    const val PARAKEET_DIR = "parakeet-tdt-ctc-110m-int8"
    val PARAKEET_FILES = listOf("model.int8.onnx", "tokens.txt")
    const val VAD_FILE = "silero_vad.onnx"
    const val CED_DIR = "ced-mini-int8"
    private const val RATE = 16_000
    private const val VAD_FEED = 512 * 16        // 0.5 s per call
    private const val MIN_SPEECH_SAMPLES = (0.3 * RATE).toInt()
    private const val TAG_SAMPLES = 10 * RATE
    private const val TAG_TOP_K = 5
    private const val TAG_MIN_PROB = 0.15f
    private const val QUEUE_SLOTS = 8

    /**
     * Small decoders sometimes loop on noise ("the the the the..."). Drops text that is faster than anyone speaks (more than 7 words
     * a second) and cuts a word repeated more than 4 times in a row to 4.
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
