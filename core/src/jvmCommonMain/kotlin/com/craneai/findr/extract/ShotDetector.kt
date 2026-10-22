package com.craneai.findr.extract

import kotlin.math.abs

/** One shot of a video: `startMs` until `endMs`, with the representative frame taken from its middle. */
data class ShotSpan(val startMs: Long, val endMs: Long) {
  val lengthMs get() = endMs - startMs
  val midMs get() = startMs + lengthMs / 2
}

/**
 * Shot-change detection over tiny frames (luminance values 0..255, all the same length), kept free of Android types
 * so it is unit-tested on the JVM.
 *
 * Two signals, because videos cut in two ways:
 *  - a hard cut: the step from one sample to the next is large against what the video has been doing lately
 *    (adaptive: 4x the recent median step, never under [CUT_MIN]);
 *  - a dissolve or fade: each step is small, but the frame has drifted far from where the shot began ([DRIFT]).
 * The distance is the larger of the mean absolute pixel difference and the luminance-histogram distance, so a scene
 * that moves objects around (histogram barely changes) and one that shifts brightness (pixels barely change in
 * structure) are both noticed.
 */
object ShotDetector {
  private const val BINS = 16
  const val CUT_MIN = 0.25
  const val DRIFT = 0.5
  private const val RECENT = 8
  private const val CUT_FACTOR = 4.0

  /** 0 (identical) to 1 (nothing in common). */
  fun distance(a: IntArray, b: IntArray): Double {
    require(a.size == b.size) { "frames differ in size" }
    if (a.isEmpty()) return 0.0
    var sum = 0L
    val ha = IntArray(BINS)
    val hb = IntArray(BINS)
    for (i in a.indices) {
      sum += abs(a[i] - b[i])
      ha[a[i].coerceIn(0, 255) * BINS / 256]++
      hb[b[i].coerceIn(0, 255) * BINS / 256]++
    }
    val mad = sum.toDouble() / a.size / 128.0   // a mean step of 128 grey levels is already "completely different"
    var l1 = 0
    for (k in 0 until BINS) l1 += abs(ha[k] - hb[k])
    return maxOf(minOf(1.0, mad), l1.toDouble() / (2.0 * a.size))
  }

  /** Indices of frames that start a new shot. Always begins with 0 (when there are frames). */
  fun boundaries(frames: List<IntArray>): List<Int> {
    if (frames.isEmpty()) return emptyList()
    val result = arrayListOf(0)
    val steps = ArrayList<Double>()
    var reference = frames[0]
    for (i in 1 until frames.size) {
      val step = distance(frames[i - 1], frames[i])
      val recent = steps.takeLast(RECENT).sorted()
      val median = if (recent.isEmpty()) 0.0 else recent[recent.size / 2]
      steps.add(step)
      val cut = step > maxOf(CUT_MIN, CUT_FACTOR * median)
      val drifted = !cut && distance(reference, frames[i]) > DRIFT
      if (cut || drifted) {
        result.add(i)
        reference = frames[i]
      }
    }
    return result
  }

  /**
   * Shots as time spans. `timesMs[i]` is when frame i was sampled. A shot starts at the sample that opened it (the
   * first at 0) and ends where the next begins (the last at `durationMs`).
   */
  fun spans(boundaries: List<Int>, timesMs: LongArray, durationMs: Long): List<ShotSpan> {
    if (boundaries.isEmpty()) return emptyList()
    val end = maxOf(durationMs, timesMs.last() + 1)
    return boundaries.mapIndexed { k, b ->
      val start = if (k == 0) 0L else timesMs[b]
      val stop = if (k + 1 < boundaries.size) timesMs[boundaries[k + 1]] else end
      ShotSpan(start, maxOf(stop, start + 1))
    }
  }

  /** At most `max` shots: the longest ones (a meme's punchline shot is rarely the shortest), back in time order. */
  fun pick(shots: List<ShotSpan>, max: Int): List<ShotSpan> {
    if (shots.size <= max) return shots
    return shots.withIndex().sortedWith(compareBy({ -it.value.lengthMs }, { it.index })).take(max)
      .sortedBy { it.index }.map { it.value }
  }

  /**
   * When to look at a video of `durationMs` for the cheap scan: about once a second, but never more than `maxSamples`
   * in total, and offset by half a step so a fade-in from black isn't what we sample. A video under 3 s gets its
   * middle only.
   */
  fun sampleTimes(durationMs: Long, maxSamples: Int = 600): LongArray {
    if (durationMs < 3000) return longArrayOf(durationMs.coerceAtLeast(0) / 2)
    val step = maxOf(1000L, durationMs / maxSamples)
    val list = ArrayList<Long>()
    var t = step / 2
    while (t < durationMs) {
      list.add(t)
      t += step
    }
    return list.toLongArray()
  }
}
