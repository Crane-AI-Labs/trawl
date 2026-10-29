package com.craneai.findr.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Which frames to show in the little strip under a video result that has a matched moment (a few pictures around that second, like a
 * scrub bar), and what they cost to fetch. Pure arithmetic: nothing here touches a decoder, so it is tested on the JVM.
 *
 * Why not just ask the platform for five pictures: a video decoder can only start at a key frame (a full picture; the frames between are
 * differences). Asking for "the frame at 12.0 s" decodes from the key frame before it, one frame at a time, and on the reference phone
 * (Galaxy A06) that is about 0.245 s per fetch plus 0.010 s for every frame decoded past the key frame (fitted on 31 in-app timings, see
 * internal/research/video-sync-frames.md). Camera videos have a key frame every second, so an exact frame costs at most ~0.3 s extra. A
 * video that was re-encoded or forwarded often has one every 5 to 8 seconds, so exact frames can cost over a second each, and "the
 * nearest key frame" for two neighbouring spots is the very same picture. The plan therefore asks for each spot's exact frame only where
 * it is cheap ([Tuning.exactMaxFrames]) and a total stays inside [Tuning.budgetMs]; otherwise it takes the key frame (one fetch, no
 * decoding past it) and drops pictures that would repeat.
 */
object VideoStrip {
  /** How many pictures the strip aims for. */
  const val FRAMES = 5

  /** How far either side of the matched moment the strip reaches. */
  const val HALF_SPAN_MS = 4_000L

  /** The A06 fit: a fixed cost per fetch ... */
  const val FETCH_MS = 245L

  /** ... plus this for each frame decoded (a key frame itself is one). */
  const val PER_FRAME_MS_X10 = 100L   // 10.0 ms, kept in tenths so the sums stay whole numbers

  /** Pictures closer together than this are the same moment; the later one is dropped. */
  const val SAME_PICTURE_MS = 500L

  /** The last frame asked for sits this far before the end, because some decoders return nothing at the very last timestamp. */
  const val END_GUARD_MS = 100L

  /** Below this spacing there is nothing to show between two frames, so a very short video gets fewer pictures. */
  const val MIN_STEP_MS = 500L

  /** The knobs, picked by the measurement in scripts/video_strip_cost.py (see internal/research/video-strip.md). */
  data class Tuning(
    /** An exact frame is asked for only when it is at most this many frames past its key frame. */
    val exactMaxFrames: Int = 60,
    /** Total modelled work for the strip, in ms of A06 time (not wall time: two fetches can run side by side). */
    val budgetMs: Long = 2_400,
  )

  /** Where the key frames of one video are. Looked up with MediaExtractor on a phone (a container read, no decoding). */
  fun interface Keyframes {
    /** The time of the last key frame at or before [timeMs], or null when it cannot be told. */
    fun atOrBefore(timeMs: Long): Long?

    /** The time of the first key frame after [timeMs], or null when there is none (or it cannot be told). */
    fun after(timeMs: Long): Long? = null
  }

  /** Key frames from a sorted list (tests, and the desktop/ffprobe measurement). */
  class KeyframeList(times: List<Long>) : Keyframes {
    private val sorted = times.sorted()
    override fun after(timeMs: Long): Long? = sorted.firstOrNull { it > timeMs }

    override fun atOrBefore(timeMs: Long): Long? {
      var lo = 0
      var hi = sorted.size - 1
      var found: Long? = null
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] <= timeMs) { found = sorted[mid]; lo = mid + 1 } else hi = mid - 1
      }
      return found
    }
  }

  /**
   * One picture in the strip. [atMs] is the time the picture really is from (the key frame's time for a key-frame slot), so tapping it
   * opens the video where the picture is, not where it was asked for. [exact] picks the decoder option: false asks for the key frame at
   * or before [atMs], true decodes up to [atMs].
   */
  data class Slot(val atMs: Long, val exact: Boolean, val costMs: Long)

  /** The plan: pictures in time order. [anchor] is the one nearest the matched moment (the strip marks it). */
  class Plan(val slots: List<Slot>, val anchor: Int, val momentMs: Long) {
    /** A single picture is not a strip: the row already has a thumbnail. */
    val worthShowing: Boolean get() = slots.size >= 2

    /** Slot numbers in the order to fetch them: the matched moment first, then outwards, so the strip fills from the middle. */
    val fetchOrder: List<Int> get() = slots.indices.sortedBy { abs(slots[it].atMs - momentMs) }

    /** All the work, one fetch after another, in ms of A06 time. */
    val workMs: Long get() = slots.sumOf { it.costMs }

    /** The time until the first (middle) picture is there. */
    val firstMs: Long get() = fetchOrder.firstOrNull()?.let { slots[it].costMs } ?: 0L

    /** The time until every picture is there when [workers] fetches run at once (longest first onto the freest worker). */
    fun wallMs(workers: Int): Long {
      val lanes = LongArray(max(1, workers))
      for (c in slots.map { it.costMs }.sortedDescending()) {
        val i = lanes.indices.minByOrNull { lanes[it] }!!
        lanes[i] += c
      }
      return lanes.max()
    }
  }

  /** What one exact frame [framesPast] frames after its key frame costs. */
  fun exactCostMs(framesPast: Int): Long = FETCH_MS + (PER_FRAME_MS_X10 * (framesPast + 1) + 5) / 10

  /** What the key frame itself costs. */
  fun keyCostMs(): Long = exactCostMs(0)

  /**
   * The times the strip is spread over: [count] evenly spaced from [momentMs] - 4 s to + 4 s. A moment near either end slides the
   * window so it stays 8 s wide inside the video; a video shorter than that is covered whole, with fewer pictures when it is very short.
   * [durationMs] null or 0 (unknown) leaves the far end open.
   */
  fun targets(momentMs: Long, durationMs: Long?, count: Int = FRAMES, halfSpanMs: Long = HALF_SPAN_MS): List<Long> {
    val last = if (durationMs != null && durationMs > 0) max(0L, durationMs - END_GUARD_MS) else Long.MAX_VALUE
    val moment = momentMs.coerceIn(0L, last)
    val width = 2 * halfSpanMs
    val first: Long
    val end: Long
    if (last == Long.MAX_VALUE) {
      first = max(0L, moment - halfSpanMs); end = moment + halfSpanMs
    } else if (last <= width) {
      first = 0L; end = last
    } else {
      first = (moment - halfSpanMs).coerceIn(0L, last - width); end = first + width
    }
    val n = min(count, ((end - first) / MIN_STEP_MS).toInt() + 1).coerceAtLeast(1)
    if (n == 1) return listOf(moment)
    return List(n) { i -> first + ((end - first) * i.toDouble() / (n - 1)).roundToLong() }
  }

  /**
   * The strip for a hit at [momentMs]. [keys] says where the key frames are (null = unknown: every spot is then one key-frame fetch
   * and nothing can be de-duplicated up front). [fps] is the video's frame rate, used to count the frames an exact fetch decodes.
   */
  fun plan(momentMs: Long, durationMs: Long?, keys: Keyframes?, fps: Float = 30f, tune: Tuning = Tuning()): Plan {
    val frameMs = 1000f / fps.coerceIn(10f, 60f)
    val spots = targets(momentMs, durationMs)
    val moment = momentMs.coerceIn(spots.first(), spots.last())

    if (keys == null) {
      val slots = spots.map { Slot(it, exact = false, costMs = keyCostMs()) }
      return Plan(slots, nearest(slots, moment), moment)
    }

    // Per spot: the key frame a key-frame fetch would give (the nearer of the one before and the one after, as the platform's
    // "closest sync" does), how many frames an exact fetch would decode, and so what that costs.
    class Spot(val at: Long, val key: Long, val past: Int) {
      val exactCost get() = exactCostMs(past)
    }
    val all = spots.map { t ->
      val before = (keys.atOrBefore(t) ?: t).coerceAtMost(t)
      val after = keys.after(t)
      val nearest = if (after != null && after - t < t - before) after else before
      Spot(t, nearest, ((t - before) / frameMs).toInt())
    }
    val exact = all.filter { it.past <= tune.exactMaxFrames }.toMutableList()
    var keyOnly = all.filter { it.past > tune.exactMaxFrames }

    fun build(ex: List<Spot>, ko: List<Spot>): List<Slot> {
      val out = ex.map { Slot(it.at, true, it.exactCost) }.toMutableList()
      // Spots that share a key frame show the same picture: keep one per key frame, and none that an exact frame already shows.
      for (k in ko.map { it.key }.distinct().sorted()) {
        if (out.none { abs(it.atMs - k) < SAME_PICTURE_MS }) out += Slot(k, false, keyCostMs())
      }
      return out.sortedBy { it.atMs }
    }

    var slots = build(exact, keyOnly)
    // Over budget: the exact frame that costs most gives way to its key frame, until it fits.
    while (slots.sumOf { it.costMs } > tune.budgetMs && exact.isNotEmpty()) {
      val worst = exact.maxByOrNull { it.past }!!
      exact.remove(worst)
      keyOnly = keyOnly + worst
      slots = build(exact, keyOnly)
    }
    return Plan(slots, nearest(slots, moment), moment)
  }

  private fun nearest(slots: List<Slot>, moment: Long): Int =
    slots.indices.minByOrNull { abs(slots[it].atMs - moment) } ?: 0
}
