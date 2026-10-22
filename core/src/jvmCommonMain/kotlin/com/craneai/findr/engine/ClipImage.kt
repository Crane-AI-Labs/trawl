package com.craneai.findr.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The quick-look model's picture preprocessing, as the eval scored it (the experiment code `squash`,
 * scripts/eval_quicklook_candidates.py): the whole picture resized to [QuickLookPack.SIZE] square ignoring its aspect ratio
 * (no crop, so a screenshot's or meme's text isn't cut off), with Pillow's bicubic filter, then the model's mean and
 * deviation per channel. Pure JVM, so the unit tests check it against Pillow's own output.
 *
 * Pillow's resize is a separable convolution whose kernel widens with the shrink factor, so it averages every source
 * pixel instead of sampling a few (Android's bilinear `createScaledBitmap` aliases when shrinking more than 2×). Like
 * Pillow on 8-bit pictures, each pass rounds and clips to 0..255.
 */
object ClipImage {
  const val SIZE = QuickLookPack.SIZE
  private val MEAN = QuickLookPack.MEAN
  private val STD = QuickLookPack.STD

  /**
   * The power-of-two decode factor for a [width]×[height] picture: as coarse as possible while both sides stay at least
   * twice [SIZE], so the decoder (which shrinks JPEGs almost for free) does most of the work and the resize still sees
   * enough pixels. A 4000×3000 photo decodes at 1/4 (1000×750) for SIZE 224.
   */
  fun sampleSize(width: Int, height: Int, size: Int = SIZE): Int {
    var sample = 1
    while (min(width, height) / (sample * 2) >= 2 * size) sample *= 2
    return sample
  }

  /**
   * [argb] (row-major `0xAARRGGBB`, as `Bitmap.getPixels` gives it) to the model's input: channels-first floats,
   * 3×[SIZE]×[SIZE], normalised. [out] can be reused between calls.
   */
  fun prepare(argb: IntArray, width: Int, height: Int, out: FloatArray = FloatArray(3 * SIZE * SIZE)): FloatArray {
    require(argb.size >= width * height && width > 0 && height > 0) { "bad picture ${width}x$height" }
    val rgb = resize(argb, width, height)
    val plane = SIZE * SIZE
    for (i in 0 until plane) {
      for (c in 0 until 3) out[c * plane + i] = (rgb[i * 3 + c] / 255f - MEAN[c]) / STD[c]
    }
    return out
  }

  /** Pillow's `resize((size, size), BICUBIC)` on an RGB picture: interleaved RGB bytes (as ints 0..255), [size]×[size] ([SIZE] for the quick-look model; the privacy scorer's model takes 384). */
  fun resize(argb: IntArray, width: Int, height: Int, size: Int = SIZE): IntArray {
    val h = Coefficients(width, size)
    val v = Coefficients(height, size)
    // Horizontal pass first, as Pillow does: height rows of size pixels.
    val mid = IntArray(height * size * 3)
    for (y in 0 until height) {
      val row = y * width
      for (x in 0 until size) {
        var r = 0.0; var g = 0.0; var b = 0.0
        val start = h.start[x]
        val k = x * h.taps
        for (i in 0 until h.count[x]) {
          val p = argb[row + start + i]
          val w = h.weights[k + i]
          r += (p shr 16 and 0xFF) * w; g += (p shr 8 and 0xFF) * w; b += (p and 0xFF) * w
        }
        val o = (y * size + x) * 3
        mid[o] = clip(r); mid[o + 1] = clip(g); mid[o + 2] = clip(b)
      }
    }
    val out = IntArray(size * size * 3)
    for (y in 0 until size) {
      val start = v.start[y]
      val k = y * v.taps
      for (x in 0 until size) {
        var r = 0.0; var g = 0.0; var b = 0.0
        for (i in 0 until v.count[y]) {
          val o = ((start + i) * size + x) * 3
          val w = v.weights[k + i]
          r += mid[o] * w; g += mid[o + 1] * w; b += mid[o + 2] * w
        }
        val o = (y * size + x) * 3
        out[o] = clip(r); out[o + 1] = clip(g); out[o + 2] = clip(b)
      }
    }
    return out
  }

  private fun clip(v: Double): Int = v.roundToInt().coerceIn(0, 255)

  /** Pillow's `precompute_coeffs` for the bicubic filter (support 2, a = -0.5): which inputs feed each output, and how much. */
  private class Coefficients(inSize: Int, outSize: Int) {
    val start = IntArray(outSize)
    val count = IntArray(outSize)
    val taps: Int
    val weights: DoubleArray

    init {
      val scale = inSize.toDouble() / outSize
      val filterScale = max(scale, 1.0)
      val support = 2.0 * filterScale
      taps = ceil(support).toInt() * 2 + 1
      weights = DoubleArray(outSize * taps)
      for (xx in 0 until outSize) {
        val center = (xx + 0.5) * scale
        val xmin = max((center - support + 0.5).toInt(), 0)
        val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
        var total = 0.0
        for (x in 0 until xmax) {
          val w = bicubic((x + xmin - center + 0.5) / filterScale)
          weights[xx * taps + x] = w
          total += w
        }
        if (total != 0.0) for (x in 0 until xmax) weights[xx * taps + x] /= total
        start[xx] = xmin
        count[xx] = xmax
      }
    }

    private fun bicubic(x0: Double): Double {
      val a = -0.5
      val x = abs(x0)
      return when {
        x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
        x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
        else -> 0.0
      }
    }
  }
}
