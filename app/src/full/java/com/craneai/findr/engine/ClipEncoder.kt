package com.craneai.findr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.craneai.findr.core.FindrLog as Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "FindrQuickLook"

/**
 * The quick-look model (D2, D10): TinyCLIP ViT-61M/32 (QuickLookPack), weight-only int8, on ONNX Runtime's CPU provider,
 * beside EmbeddingGemma.
 *
 *  - The image tower runs the first pass on [IMAGE_THREADS] threads, never more: 4 threads next to EmbeddingGemma's GPU
 *    images cost those 12-14% on the Galaxy A06 (the research notes, D2). Spinning is off, so an idle pool thread doesn't hold a
 *    core EmbeddingGemma's CPU half needs. The session is created on the caller's thread, so its pool thread inherits that
 *    thread's (lowered) priority.
 *  - The text tower embeds a query (~0.06 s on the Infinix X6885) and is freed after [TEXT_IDLE_MS] without searches.
 *
 * Both towers output unnormalised features; vectors leave here L2-normalised, as the index stores them.
 */
class ClipEncoder(
  private val dir: File,
  private val tokenizer: Lazy<ClipTokenizer?>,
  private val scope: CoroutineScope,
  /** ONNX Runtime threads for the image tower (benches compare settings; the app uses [IMAGE_THREADS]). */
  private val imageThreads: Int = Tuning.current.clipImageThreads,
) {
  private val imageLock = Any()
  private var imageSession: OrtSession? = null
  private val textLock = Mutex()
  private var textSession: OrtSession? = null
  private var textRelease: Job? = null
  private val pixels = FloatArray(3 * ClipImage.SIZE * ClipImage.SIZE)

  val imageFile: File get() = File(dir, QuickLookPack.IMAGE)
  val textFile: File get() = File(dir, QuickLookPack.TEXT)

  /** Both towers are installed (sizes match the manifest; the installer checked their SHA-256 before moving them in). */
  fun available(): Boolean = QuickLookPack.files.all { f -> File(dir, f.path).let { it.isFile && it.length() == f.size } }

  /** Time the last [image] call spent preparing the picture and running the model, for the pass's log line. */
  @Volatile var lastPrepMs = 0L
    private set
  @Volatile var lastModelMs = 0L
    private set

  /**
   * One picture's vector. Blocking: call it from the quick-look pass's own thread. [bitmap] is any size (the caller
   * decodes it near twice [ClipImage.SIZE], see [ClipImage.sampleSize]); it is squashed to SIZE×SIZE here.
   */
  fun image(bitmap: Bitmap): FloatArray = synchronized(imageLock) {
    val t0 = System.nanoTime()
    val w = bitmap.width
    val h = bitmap.height
    val argb = IntArray(w * h)
    bitmap.getPixels(argb, 0, w, 0, 0, w, h)
    ClipImage.prepare(argb, w, h, pixels)
    val t1 = System.nanoTime()
    val session = imageSession ?: createSession(imageFile, imageThreads).also { imageSession = it }
    val env = environment()
    val out = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, ClipImage.SIZE.toLong(), ClipImage.SIZE.toLong())).use { input ->
      session.run(mapOf(IMAGE_INPUT to input)).use { result -> features(result) }
    }
    lastPrepMs = (t1 - t0) / 1_000_000
    lastModelMs = (System.nanoTime() - t1) / 1_000_000
    out
  }

  /** The query's vector in the quick-look space, or null when the tokenizer can't load. */
  suspend fun query(text: String): FloatArray? = withContext(Dispatchers.Default) {
    val tok = tokenizer.value ?: return@withContext null
    val vector = textLock.withLock {
      val session = textSession ?: createSession(textFile, TEXT_THREADS).also { textSession = it }
      val ids = tok.encode(text)
      val env = environment()
      OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ClipTokenizer.CONTEXT.toLong())).use { input ->
        session.run(mapOf(TEXT_INPUT to input)).use { result -> features(result) }
      }
    }
    scheduleTextRelease()
    vector
  }

  /** Loads the text tower now (the search box got focus), so the first query doesn't pay for it. */
  suspend fun warmText() = withContext(Dispatchers.Default) {
    if (!available() || tokenizer.value == null) return@withContext
    textLock.withLock { if (textSession == null) textSession = createSession(textFile, TEXT_THREADS) }
    scheduleTextRelease()
  }

  /** The first pass is over: give the image tower's memory back. */
  fun releaseImage() = synchronized(imageLock) {
    imageSession?.close()
    imageSession = null
  }

  /** Both towers closed (the pack is about to be removed). */
  suspend fun release() {
    releaseImage()
    textRelease?.cancel()
    textLock.withLock {
      textSession?.close()
      textSession = null
    }
  }

  private fun scheduleTextRelease() {
    textRelease?.cancel()
    textRelease = scope.launch {
      delay(TEXT_IDLE_MS)
      textLock.withLock {
        textSession?.close()
        textSession = null
      }
    }
  }

  private fun createSession(file: File, threads: Int): OrtSession {
    val t0 = System.nanoTime()
    val options = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(threads)
      setInterOpNumThreads(1)
      setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      addConfigEntry("session.intra_op.allow_spinning", "0")
      Tuning.current.let { t ->
        if (!t.ortArena) setCPUArenaAllocator(false)
        if (!t.ortPattern) setMemoryPatternOptimization(false)
        if (t.ortInitDevice) addConfigEntry("session.use_device_allocator_for_initializers", "1")
      }
    }
    val session = environment().createSession(file.absolutePath, options)
    Log.i(TAG, "loaded ${file.name} on $threads threads in ${(System.nanoTime() - t0) / 1_000_000} ms")   // log-safe: our model file
    return session
  }

  private fun features(result: OrtSession.Result): FloatArray {
    @Suppress("UNCHECKED_CAST")
    val v = (result.get(0).value as Array<FloatArray>)[0]
    var sum = 0.0
    for (x in v) sum += x.toDouble() * x
    val norm = sqrt(sum).toFloat()
    if (norm > 0f) for (i in v.indices) v[i] /= norm
    return v
  }

  companion object {
    /**
     * The process's ONNX Runtime environment, with its telemetry off. The slim build (scripts/ort-slim) has no telemetry client
     * at all (--no_telemetry) and its AAR no start-up provider or INTERNET permission; the call stays as a second guard.
     */
    private val environmentOff by lazy {
      OrtEnvironment.getEnvironment().also { env ->
        runCatching { env.setTelemetry(false) }.onFailure { Log.w(TAG, "could not switch ONNX Runtime telemetry off", it) }
      }
    }
    fun environment(): OrtEnvironment = environmentOff

    const val DIM = QuickLookPack.DIM
    const val IMAGE_THREADS = 2
    const val TEXT_THREADS = 4
    const val TEXT_IDLE_MS = 60_000L
    private const val IMAGE_INPUT = "pixel_values"
    private const val TEXT_INPUT = "input_ids"
  }
}
