package com.craneai.findr.engine

/** The scorer's picture preprocessing, pure JVM so the unit tests can check it against Pillow. */
object PrivateImage {
  const val SIZE = 384

  /**
   * [argb] to the model's input: the whole picture squashed to 384×384 with Pillow's bicubic filter (no crop, so nothing at
   * the edge goes unseen), channels-first, `x / 127.5 - 1` (mean and deviation 0.5), as the model was evaluated.
   */
  fun prepare(argb: IntArray, width: Int, height: Int, out: FloatArray = FloatArray(3 * SIZE * SIZE)): FloatArray {
    require(argb.size >= width * height && width > 0 && height > 0) { "bad picture ${width}x$height" }
    val rgb = ClipImage.resize(argb, width, height, SIZE)
    val plane = SIZE * SIZE
    for (i in 0 until plane) for (c in 0 until 3) out[c * plane + i] = rgb[i * 3 + c] / 127.5f - 1f
    return out
  }

  /** The decode factor that leaves both sides at least twice [SIZE]. */
  fun sampleSize(width: Int, height: Int): Int = ClipImage.sampleSize(width, height, SIZE)

  /**
   * The same input as [prepare] from a picture that is already [SIZE]×[SIZE] (the phone's own scaler did the resize): channels-first,
   * `x / 127.5 - 1`.
   */
  fun normalise(argb: IntArray, out: FloatArray = FloatArray(3 * SIZE * SIZE)): FloatArray {
    val plane = SIZE * SIZE
    require(argb.size >= plane)
    for (i in 0 until plane) {
      val p = argb[i]
      out[i] = (p shr 16 and 0xFF) / 127.5f - 1f
      out[plane + i] = (p shr 8 and 0xFF) / 127.5f - 1f
      out[2 * plane + i] = (p and 0xFF) / 127.5f - 1f
    }
    return out
  }
}
