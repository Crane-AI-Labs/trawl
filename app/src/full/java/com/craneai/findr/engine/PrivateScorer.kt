package com.craneai.findr.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.PrivateLook
import java.io.File
import java.nio.FloatBuffer

private const val TAG = "FindrPrivacy"

/**
 * The "looks private" model (see [PrivateLook]): Marqo/nsfw-image-detection-384, Apache-2.0, ViT-tiny/16 fp32, 22 MB. It is the
 * "Blur private-looking previews" add-on pack ([PrivatePack]), not part of the APK: the file is
 * `files/models/private/nsfw-384/nsfw-384.onnx`, put there (checked) by the pack installer. It runs on ONNX Runtime's CPU
 * provider with the same session settings as [ClipEncoder]'s image tower, on 2 threads, and is loaded only while the scoring
 * pass runs ([release] gives the ~60 MB back).
 */
class PrivateScorer(private val modelFile: File) {
  constructor(context: Context) : this(File(context.filesDir, "models/${PrivatePack.DIR}/${PrivatePack.MODEL}"))

  private val lock = Any()
  private var session: OrtSession? = null
  private val pixels = FloatArray(3 * PrivateImage.SIZE * PrivateImage.SIZE)

  @Volatile var lastPrepMs = 0L
    private set
  @Volatile var lastModelMs = 0L
    private set

  /** The pack is installed (its files were checked on the way in); a stat, so the pass can ask on every file. */
  fun available(): Boolean = modelFile.isFile

  /**
   * P(NSFW) of one picture, 0..1. Blocking: call it from the scoring pass's own thread.
   * [exact]: resize with Pillow's bicubic in plain Kotlin, as the model was evaluated (about 190 ms for a 1 MP picture on a Helio
   * G99); by default the phone's scaler does it (halving, then one smooth scale to 384×384), a few ms.
   */
  fun score(bitmap: Bitmap, exact: Boolean = false): Float = synchronized(lock) {
    val t0 = System.nanoTime()
    if (exact) {
      val w = bitmap.width
      val h = bitmap.height
      val argb = IntArray(w * h)
      bitmap.getPixels(argb, 0, w, 0, 0, w, h)
      PrivateImage.prepare(argb, w, h, pixels)
    } else {
      val side = PrivateImage.SIZE
      var current = bitmap
      // Halve while still at least twice the target on both sides: each step averages 2x2 pixels, so a big photo is not just sampled.
      while (current.width / 2 >= side && current.height / 2 >= side) {
        val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        if (current !== bitmap) current.recycle()
        current = next
      }
      val square = Bitmap.createScaledBitmap(current, side, side, true)
      if (current !== bitmap) current.recycle()
      val argb = IntArray(side * side)
      square.getPixels(argb, 0, side, 0, 0, side, side)
      if (square !== bitmap) square.recycle()
      PrivateImage.normalise(argb, pixels)
    }
    val t1 = System.nanoTime()
    val s = session ?: open().also { session = it }
    val shape = longArrayOf(1, 3, PrivateImage.SIZE.toLong(), PrivateImage.SIZE.toLong())
    val p = OnnxTensor.createTensor(ClipEncoder.environment(), FloatBuffer.wrap(pixels), shape).use { input ->
      s.run(mapOf(INPUT to input)).use { result ->
        @Suppress("UNCHECKED_CAST")
        val logits = (result.get(0).value as Array<FloatArray>)[0]
        PrivateLook.probability(logits[0], logits[1])
      }
    }
    lastPrepMs = (t1 - t0) / 1_000_000
    lastModelMs = (System.nanoTime() - t1) / 1_000_000
    p
  }

  fun release() = synchronized(lock) {
    session?.close()
    session = null
  }

  private fun open(): OrtSession {
    val t0 = System.nanoTime()
    val options = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(Tuning.current.privacyThreads)
      setInterOpNumThreads(1)
      setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      addConfigEntry("session.intra_op.allow_spinning", "0")
      // Lean memory (native-mem study, tablet): no arena, no memory-pattern plan, weights in plain allocations. The scorer sees one
      // fixed 384 px input, so the arena has nothing to reuse. Dirty native 139 -> 113 MB, peak PSS 320 -> 297 MB, 30 of 30 scores
      // identical, speed within noise. The quick-look towers showed no such gain, so they keep ORT's defaults.
      setCPUArenaAllocator(false)
      setMemoryPatternOptimization(false)
      addConfigEntry("session.use_device_allocator_for_initializers", "1")
    }
    val s = ClipEncoder.environment().createSession(modelFile.absolutePath, options)
    Log.i(TAG, "loaded the privacy model on ${Tuning.current.privacyThreads} threads in ${(System.nanoTime() - t0) / 1_000_000} ms")
    return s
  }

  companion object {
    const val THREADS = 2
    private const val INPUT = "pixel_values"
  }
}
