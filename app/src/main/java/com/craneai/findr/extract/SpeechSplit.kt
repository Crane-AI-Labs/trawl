package com.craneai.findr.extract

import kotlin.math.abs

/**
 * Cuts one long speech segment into pieces of at most [maxSamples], each cut at the quietest stretch near the end of its piece.
 *
 * Why: the Silero VAD's `maxSpeechDuration` only splits where it finds a pause, so continuous speech, music or noise comes out as
 * one segment of a minute or more (measured on the tablet: 59 s, 41 s and 38 s segments with a 15 s cap). Parakeet's ONNX arena
 * grows with the longest input it has seen and never shrinks (native-mem study: 223 -> 600 -> 970 -> 1100 MB), so the cap the
 * design assumed ("segments of 15 s or less", the WER measured at 15 s chunks) has to be enforced here.
 */
object SpeechSplit {
  /** How far back from the cap a quiet cut may be looked for. */
  private const val SEARCH_S = 3
  private const val FRAME = 800            // 50 ms at 16 kHz
  private const val MIN_TAIL = 16_000      // the last piece is at least a second, so it is worth recognising

  /** [start, end) sample ranges covering 0 until [n]; a single range when the segment is already short enough. */
  fun ranges(samples: FloatArray, n: Int, maxSamples: Int, rate: Int = 16_000): List<IntRange> {
    if (n <= maxSamples) return listOf(0 until n)
    val out = ArrayList<IntRange>()
    var start = 0
    while (n - start > maxSamples) {
      val hard = start + maxSamples
      val from = maxOf(start + FRAME, hard - SEARCH_S * rate)
      val to = minOf(hard, n - MIN_TAIL)
      var cut = hard.coerceAtMost(n - MIN_TAIL)
      if (to - FRAME > from) {
        var best = Double.MAX_VALUE
        var i = from
        while (i + FRAME <= to) {
          var e = 0.0
          for (j in i until i + FRAME) e += abs(samples[j])
          if (e < best) { best = e; cut = i + FRAME / 2 }
          i += FRAME / 2
        }
      }
      out += start until cut
      start = cut
    }
    out += start until n
    return out
  }
}
