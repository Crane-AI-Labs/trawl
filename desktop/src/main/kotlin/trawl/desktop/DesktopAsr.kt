package trawl.desktop

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.FixedBlocks
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.craneai.findr.core.SpeechSegment as Spoken
import com.craneai.findr.core.SungReader
import com.craneai.findr.core.SpokenWord
import com.craneai.findr.core.WordTimes

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

/** Internal native-handle lifecycle seam so idle-reclaim and reload tests do not require 110M ONNX weights on disk. */
internal interface AsrRuntime {
  fun installed(): Boolean
  fun available(): Boolean
  fun transcribe(numThreads: Int, decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript
  fun isLoaded(): Boolean
  /** Releases any loaded native handles; returns true when at least one handle was loaded and freed. */
  fun release(): Boolean
}

/**
 * Speech to words on the desktop with sherpa-onnx, the phone's engine (SherpaAsrEngine) and its settings: the Silero VAD cuts the sound
 * into speech (at 0.5, no segment over 15 s, and [SpeechSplit] cuts any that still are), Parakeet-TDT-CTC 110M int8 reads each piece,
 * and CED-mini puts AudioSet labels on every 10 s so sounds without words (a dog, a siren, applause) are findable. The models are the
 * `asr` pack of trawl-models.zip, installed under models/asr/.
 *
 * Everything loads lazily on first use and is released automatically after [idleReleaseMs] of inactivity (or immediately via [release])
 * so 300-750 MB of native ONNX/sherpa arenas do not stay pinned while idle. The next [transcribe] reloads seamlessly under the lock.
 *
 * The decoder (see [MediaDecoder]) runs on its own thread while the VAD and the recogniser work through what has arrived, so a file costs
 * about the slower of the two, not their sum.
 */
class DesktopAsr internal constructor(
  private val runtime: AsrRuntime,
  private val threads: () -> Int = { 2 },
  private val idleReleaseMs: Long = DEFAULT_IDLE_RELEASE_MS,
  private val clockNs: () -> Long = System::nanoTime,
  private val onReleased: () -> Unit = {},
) : Transcriber, AutoCloseable {

  constructor(
    dir: File,
    threads: () -> Int = { 2 },
    idleReleaseMs: Long = DEFAULT_IDLE_RELEASE_MS,
    onReleased: () -> Unit = {},
  ) : this(SherpaAsrRuntime(dir), threads, idleReleaseMs, System::nanoTime, onReleased)

  private val lock = Any()
  private var closed = false
  private var lastUsedNs = 0L
  private var pendingIdle: ScheduledFuture<*>? = null
  private val schedulerCreated = AtomicBoolean(false)
  private val scheduler: ScheduledThreadPoolExecutor by lazy {
    schedulerCreated.set(true)
    ScheduledThreadPoolExecutor(1) { r -> Thread(r, "trawl-asr-idle").apply { isDaemon = true } }.also {
      it.removeOnCancelPolicy = true
    }
  }

  /** The speech pack is on disk (the recogniser and the voice detector; the sound labels are optional). */
  fun installed(): Boolean = runtime.installed()

  /** Installed, and the native library of this computer loads. */
  override fun available(): Boolean = runtime.available()

  /** True while native speech/VAD/tagger models are currently loaded in memory. */
  val isLoaded: Boolean get() = synchronized(lock) { runtime.isLoaded() }

  /**
   * Reads [startMs] to [startMs] + [spanMs] of [file]. Times in the result are the file's own (the window's offset is added).
   * [stop] is asked between pieces of sound; a true answer ends the work with a [java.util.concurrent.CancellationException].
   */
  override fun transcribe(decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript {
    synchronized(lock) {
      if (closed) throw java.util.concurrent.CancellationException("asr closed")
      cancelIdleLocked()
      try {
        return runtime.transcribe(threads().coerceIn(1, 8), decoder, file, startMs, spanMs, stop)
      } finally {
        lastUsedNs = clockNs()
        if (!closed && runtime.isLoaded() && idleReleaseMs > 0L) scheduleIdleLocked(idleReleaseMs)
      }
    }
  }

  /** Gives the memory back immediately (the models take about 0.3 to 0.8 GB while loaded). Thread-safe; next [transcribe] reloads lazily. */
  fun release(): Boolean {
    val freed = synchronized(lock) {
      cancelIdleLocked()
      runtime.release()
    }
    if (freed) runCatching { onReleased() }
    return freed
  }

  /** Releases loaded models only if they have been idle for at least [idleReleaseMs]; otherwise reschedules for the remaining time. */
  fun releaseIfIdle(): Boolean {
    val freed = synchronized(lock) {
      if (!runtime.isLoaded()) { cancelIdleLocked(); return false }
      if (idleReleaseMs > 0L) {
        val elapsedMs = (clockNs() - lastUsedNs) / 1_000_000L
        val remaining = idleReleaseMs - elapsedMs
        if (remaining > 0L) {
          if (!closed) scheduleIdleLocked(remaining)
          return false
        }
      }
      cancelIdleLocked()
      runtime.release()
    }
    if (freed) runCatching { onReleased() }
    return freed
  }

  private fun cancelIdleLocked() {
    pendingIdle?.cancel(false)
    pendingIdle = null
  }

  private fun scheduleIdleLocked(delayMs: Long) {
    cancelIdleLocked()
    if (closed || delayMs <= 0L) return
    try {
      pendingIdle = scheduler.schedule({ releaseIfIdle() }, delayMs, TimeUnit.MILLISECONDS)
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      pendingIdle = null
    }
  }

  override fun close() {
    val freed = synchronized(lock) {
      closed = true
      cancelIdleLocked()
      runtime.release()
    }
    if (freed) runCatching { onReleased() }
    if (schedulerCreated.get()) scheduler.shutdownNow()
  }

  companion object {
    const val PARAKEET_DIR = "parakeet-tdt-ctc-110m-int8"
    val PARAKEET_FILES = listOf("model.int8.onnx", "tokens.txt")
    const val VAD_FILE = "silero_vad.onnx"
    const val CED_DIR = "ced-mini-int8"
    /** Automatically unload sherpa-onnx Parakeet/CED/VAD handles after 60 s without transcription activity. */
    const val DEFAULT_IDLE_RELEASE_MS = 60_000L
    internal const val RATE = 16_000
    internal const val VAD_FEED = 512 * 16        // 0.5 s per call
    internal const val MIN_SPEECH_SAMPLES = (0.3 * RATE).toInt()
    internal const val TAG_SAMPLES = 10 * RATE
    internal const val TAG_TOP_K = 5
    internal const val TAG_MIN_PROB = 0.15f
    internal const val QUEUE_SLOTS = 8

    /**
     * Small decoders sometimes loop on noise ("the the the the..."). Drops text that is faster than anyone speaks (more than 7 words
     * a second) and cuts a word repeated more than 4 times in a row to 4.
     */
    fun clean(raw: String, seconds: Double): String = WordTimes.clean(raw, seconds)
  }
}

/** Real sherpa-onnx native runtime holding Parakeet, Silero VAD, and optional CED-mini handles (caller holds [DesktopAsr]'s lock). */
private class SherpaAsrRuntime(private val dir: File) : AsrRuntime {
  private var recognizer: OfflineRecognizer? = null
  private var vad: Vad? = null
  private var tagger: AudioTagging? = null
  private var loadedThreads = 0

  private val parakeet = File(dir, DesktopAsr.PARAKEET_DIR)
  private val vadModel = File(dir, DesktopAsr.VAD_FILE)
  private val ced = File(dir, DesktopAsr.CED_DIR)

  @Volatile private var nativeOk: Boolean? = null

  override fun installed(): Boolean = DesktopAsr.PARAKEET_FILES.all { File(parakeet, it).isFile } && vadModel.isFile

  override fun available(): Boolean {
    if (!installed()) return false
    nativeOk?.let { return it }
    return try {
      Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")   // its static initialiser loads the native library
      true
    } catch (t: Throwable) { false }.also { nativeOk = it }
  }

  override fun isLoaded(): Boolean = recognizer != null || vad != null || tagger != null

  override fun transcribe(numThreads: Int, decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript {
    check(available()) { "speech models are not installed in $dir" }
    val rec = recognizerNow(numThreads)
    val detector = vad ?: buildVad().also { vad = it }
    val tags = if (File(ced, "model.int8.onnx").isFile) tagger ?: buildTagger()?.also { tagger = it } else null
    detector.reset()

    val speech = ArrayList<Spoken>()
    val sounds = ArrayList<SoundTags>()
    var fed = 0L
    var tagBuffer = FloatArray(0)
    var tagStart = 0L
    val rate = DesktopAsr.RATE
    val maxSamples = MediaSchedule.ASR_MAX_SPEECH_S * rate
    // Stretches the labeller calls singing are read whole, not in the VAD's fragments (SungReader, sung-words.md).
    val sung = SungReader(rate, maxSamples, DesktopAsr.MIN_SPEECH_SAMPLES, { x, n, cap -> SpeechSplit.ranges(x, n, cap, rate) }) { piece, from ->
      val at = startMs + from * 1000L / rate
      val heard = read(rec, piece, at)
      if (heard.text.isBlank()) null else Spoken(at, at + piece.size * 1000L / rate, heard.text, heard.words, sung = true)
    }

    fun drain() {
      while (!detector.empty()) {
        val seg = detector.front()
        detector.pop()
        val samples = seg.samples
        if (samples.size < DesktopAsr.MIN_SPEECH_SAMPLES || sung.covers(seg.start.toLong())) continue
        // The VAD only splits at a pause, so continuous speech or music can come out a minute long; cap what the recogniser is given.
        for (r in SpeechSplit.ranges(samples, samples.size, maxSamples, rate)) {
          if (r.last + 1 - r.first < DesktopAsr.MIN_SPEECH_SAMPLES) continue
          val piece = if (r.first == 0 && r.last + 1 == samples.size) samples else samples.copyOfRange(r.first, r.last + 1)
          val at = startMs + (seg.start + r.first) * 1000L / rate
          val heard = read(rec, piece, at)
          if (heard.text.isNotBlank()) speech += Spoken(at, at + piece.size * 1000L / rate, heard.text, heard.words)
        }
      }
    }

    fun tag() {
      if (tags == null) return
      while (tagBuffer.size >= DesktopAsr.TAG_SAMPLES) {
        val w = tagBuffer.copyOfRange(0, DesktopAsr.TAG_SAMPLES)
        val labels = label(tags, w)
        sounds += SoundTags(startMs + tagStart * 1000L / rate, startMs + (tagStart + DesktopAsr.TAG_SAMPLES) * 1000L / rate, labels)
        sung.window(tagStart, w, labels)
        tagBuffer = tagBuffer.copyOfRange(DesktopAsr.TAG_SAMPLES, tagBuffer.size)
        tagStart += DesktopAsr.TAG_SAMPLES
      }
    }

    // The detector's output depends on how its input is cut into calls, and a decoder's pipe reads vary from run to run, so the sound is
    // re-cut into the same blocks every time (FixedBlocks); the labels and the detector see each block together, so which windows are
    // labelled singing when a piece of speech comes out is repeatable too.
    val feed = FixedBlocks(DesktopAsr.VAD_FEED)
    fun feedBlock(b: FloatArray) {
      if (tags != null) { tagBuffer += b; tag() }   // labels first, so the VAD pieces of a sung stretch are not read twice
      detector.acceptWaveform(b)
      drain()
    }

    // Decode on its own thread; a bounded queue keeps a fast decoder from running far ahead of a slow recogniser.
    val queue = ArrayBlockingQueue<Any>(DesktopAsr.QUEUE_SLOTS)
    val failure = AtomicReference<Throwable?>(null)
    val cancelled = AtomicBoolean(false)
    val done = Any()
    val decoding = Thread({
      val assembler = PcmAssembler()
      try {
        decoder.audio(file, startMs, spanMs) { bytes, n ->
          if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
          val x = assembler.add(bytes, n)
          if (x.isNotEmpty()) {
            while (!queue.offer(x, 200, TimeUnit.MILLISECONDS)) {
              if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
            }
          }
        }
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        failure.set(e)
      } catch (t: Throwable) {
        failure.set(t)
      } finally {
        // Exit immediately if the consumer already left (cancelled/stopped), even when the queue is full.
        runCatching {
          while (!cancelled.get() && !Thread.currentThread().isInterrupted && !queue.offer(done, 200, TimeUnit.MILLISECONDS)) {
            // retry until space opens or consumer cancels
          }
        }
      }
    }, "trawl-media-decode").apply { isDaemon = true; start() }

    try {
      while (true) {
        if (stop() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("transcription stopped")
        val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
        if (item === done) break
        val x = item as FloatArray
        fed += x.size
        feed.add(x, ::feedBlock)
      }
    } finally {
      cancelled.set(true)
      decoding.interrupt()
    }
    failure.get()?.let { if (fed == 0L) throw it as? com.craneai.findr.core.FormatNotReadable ?: com.craneai.findr.core.FormatNotReadable("sound not decoded: ${it.message}", it) }
    feed.finish(::feedBlock)
    if (tags != null && tagBuffer.size >= rate) {   // the last, shorter stretch (at least a second)
      val labels = label(tags, tagBuffer)
      sounds += SoundTags(startMs + tagStart * 1000L / rate, startMs + (tagStart + tagBuffer.size) * 1000L / rate, labels)
      sung.window(tagStart, tagBuffer, labels)
    }
    sung.finish()
    detector.flush()
    drain()
    return AudioTranscript(SungReader.merge(speech, sung, startMs, rate), sounds.filter { it.labels.isNotEmpty() }, fed.toDouble() / rate)
  }

  private fun recognizerNow(want: Int): OfflineRecognizer {
    if (recognizer != null && want != loadedThreads) { recognizer?.release(); recognizer = null }
    return recognizer ?: buildRecognizer(want).also { recognizer = it; loadedThreads = want }
  }

  /** What the recogniser heard in one stretch: the cleaned text, and (when the model gave times) its words, [baseMs] being where the stretch begins in the file. */
  private class Heard(val text: String, val words: List<SpokenWord>)

  private fun read(rec: OfflineRecognizer, samples: FloatArray, baseMs: Long): Heard {
    val stream = rec.createStream()
    try {
      stream.acceptWaveform(samples, DesktopAsr.RATE)
      rec.decode(stream)
      val r = rec.getResult(stream)
      val seconds = samples.size.toDouble() / DesktopAsr.RATE
      val text = DesktopAsr.clean(r.text, seconds)
      return Heard(text, WordTimes.forText(text, r.tokens.asList(), r.timestamps, seconds, baseMs))
    } finally { stream.release() }
  }

  private fun label(tg: AudioTagging, samples: FloatArray): List<String> {
    val stream = tg.createStream()
    try {
      stream.acceptWaveform(samples, DesktopAsr.RATE)
      return tg.compute(stream, DesktopAsr.TAG_TOP_K).filter { it.prob >= DesktopAsr.TAG_MIN_PROB }.map { it.name }.distinct()
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
      .setSampleRate(DesktopAsr.RATE).setNumThreads(1).setDebug(false).build(),
  )

  private fun buildTagger(): AudioTagging? = try {
    AudioTagging(
      AudioTaggingConfig.builder()
        .setModel(AudioTaggingModelConfig.builder().setCED(File(ced, "model.int8.onnx").absolutePath).setNumThreads(2).setDebug(false).build())
        .setLabels(File(ced, "class_labels_indices.csv").absolutePath).setTopK(DesktopAsr.TAG_TOP_K).build(),
    )
  } catch (t: Throwable) { null }   // sound labels are an extra: without them the words still work

  override fun release(): Boolean {
    val had = isLoaded()
    recognizer?.release(); recognizer = null
    vad?.release(); vad = null
    tagger?.release(); tagger = null
    loadedThreads = 0
    return had
  }
}
