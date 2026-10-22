package com.craneai.findr.engine

import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Trawl Go has no quick-look model (ONNX Runtime has no 32-bit build in our slim AAR, and the phone has no memory to spare for a
 * second tower). Same surface as the full edition's encoder, so the shared engine code compiles unchanged; it is never available,
 * so no pass runs and nothing calls [image] or [query].
 */
@Suppress("UNUSED_PARAMETER")
class ClipEncoder(
  private val dir: File,
  private val tokenizer: Lazy<ClipTokenizer?>,
  private val scope: CoroutineScope,
  imageThreads: Int = IMAGE_THREADS,
) {
  val imageFile: File get() = File(dir, QuickLookPack.IMAGE)
  val textFile: File get() = File(dir, QuickLookPack.TEXT)
  fun available(): Boolean = false
  @Volatile var lastPrepMs = 0L
    private set
  @Volatile var lastModelMs = 0L
    private set
  fun image(bitmap: Bitmap): FloatArray = throw UnsupportedOperationException("Trawl Go has no quick-look model")
  suspend fun query(text: String): FloatArray? = null
  suspend fun warmText() {}
  fun releaseImage() {}
  suspend fun release() {}

  companion object {
    const val DIM = QuickLookPack.DIM
    const val IMAGE_THREADS = 2
    const val TEXT_THREADS = 4
    const val TEXT_IDLE_MS = 60_000L
  }
}
