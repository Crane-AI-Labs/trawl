package com.craneai.findr.engine

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/** Trawl Go does not blur previews (the scorer runs on ONNX Runtime, which has no 32-bit build we ship): never available. */
@Suppress("UNUSED_PARAMETER")
class PrivateScorer(private val modelFile: File) {
  constructor(context: Context) : this(File(context.filesDir, "models/${PrivatePack.DIR}/${PrivatePack.MODEL}"))

  @Volatile var lastPrepMs = 0L
    private set
  @Volatile var lastModelMs = 0L
    private set
  fun available(): Boolean = false
  fun score(bitmap: Bitmap, exact: Boolean = false): Float = throw UnsupportedOperationException("Trawl Go has no privacy scorer")
  fun release() {}

  companion object {
    const val THREADS = 2
  }
}
