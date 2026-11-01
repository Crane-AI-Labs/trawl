package com.craneai.findr.engine

/**
 * A frame's coarse look: a [COLS] x [ROWS] grid with, per tile, the mean brightness and the mean horizontal brightness step (how
 * much stroke detail the tile holds). It exists to answer one question the 9x8 difference hash cannot: does this frame show
 * different WORDS from a frame it was merged with? A slide deck keeps one layout and changes its words, and a 9x8 hash sees those
 * slides as one picture (a ten-slide deck came out as two distinct frames), but between two slides 70 to 260 of the 2,304 tiles
 * change while the same slide re-encoded at CRF 48 changes none (the research notes, video-text).
 *
 * Pure arithmetic on brightness values, so it runs the same in a unit test as on the phone.
 */
class FrameSignature private constructor(private val mean: ByteArray, private val detail: ByteArray) {

  /** How many tiles differ visibly (by [MEAN_STEP] in brightness or [DETAIL_STEP] in stroke detail) between this frame and [other]. */
  fun changedTiles(other: FrameSignature): Int {
    var n = 0
    for (i in mean.indices) {
      if (kotlin.math.abs((mean[i].toInt() and 0xFF) - (other.mean[i].toInt() and 0xFF)) >= MEAN_STEP ||
        kotlin.math.abs((detail[i].toInt() and 0xFF) - (other.detail[i].toInt() and 0xFF)) >= DETAIL_STEP
      ) n++
    }
    return n
  }

  /** True when the two frames differ in enough tiles that their words may differ. */
  fun mayShowOtherText(other: FrameSignature): Boolean = changedTiles(other) >= MIN_CHANGED_TILES

  companion object {
    const val COLS = 64
    const val ROWS = 36
    const val MEAN_STEP = 12        // of 255: the largest step that re-encoding noise never reached (0 tiles at CRF 48)
    const val DETAIL_STEP = 8
    /** One word of a 20 px line at 768 px wide changes 6 or more tiles; a whole slide 70 or more. */
    const val MIN_CHANGED_TILES = 4

    /** [luma] is row-major brightness, 0..255, [width] x [height]. */
    fun of(luma: IntArray, width: Int, height: Int): FrameSignature {
      require(width > 0 && height > 0 && luma.size >= width * height) { "luma is smaller than $width x $height" }
      val mean = ByteArray(COLS * ROWS)
      val detail = ByteArray(COLS * ROWS)
      for (r in 0 until ROWS) {
        val y0 = (r * height / ROWS).coerceAtMost(height - 1)
        val y1 = ((r + 1) * height / ROWS).coerceIn(y0 + 1, height)
        for (c in 0 until COLS) {
          val x0 = (c * width / COLS).coerceAtMost(width - 1)
          val x1 = ((c + 1) * width / COLS).coerceIn(x0 + 1, width)
          var sum = 0L
          var step = 0L
          for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) {
              val v = luma[row + x]
              sum += v
              if (x > 0) step += kotlin.math.abs(v - luma[row + x - 1])
            }
          }
          val n = ((y1 - y0) * (x1 - x0)).toLong()
          mean[r * COLS + c] = (sum / n).toInt().coerceIn(0, 255).toByte()
          detail[r * COLS + c] = (step / n).toInt().coerceIn(0, 255).toByte()
        }
      }
      return FrameSignature(mean, detail)
    }
  }
}

/**
 * Which frames the sampler merged by difference hash (same picture, no second look) are read for words anyway.
 *
 * A merged frame is read when ALL of these hold, in this order of cheapness:
 *  1. its lineage (the kept frame it merged into) was read and SHOWED WORDS: a camera clip with no text never claims, however much
 *     its light or its subject moves (the signature changes in 170 to 205 tiles on a choir clip, so the signature alone cannot tell
 *     a slide from a shot that moved);
 *  2. the minute it falls in still has allowance: [MAX_PER_MINUTE] a minute, counted per minute and not per video, so a clip cannot
 *     spend its whole budget in the first minute, and a clip under ten seconds has none ([allowanceFor]);
 *  3. the lineage has read fewer than [MAX_READS_PER_LINEAGE] frames;
 *  4. its picture shows other words than every frame the lineage has read (see [FrameSignature.mayShowOtherText]).
 * Each read costs an OCR pass on the phone (video-text research notes), so these bound a moving picture's cost whatever its length.
 */
class MergedFrameReads(seconds: Int) {
  private val allowance = IntArray(((seconds + MINUTE_S - 1) / MINUTE_S).coerceAtLeast(1)) { m ->
    allowanceFor((seconds - m * MINUTE_S).coerceIn(0, MINUTE_S))
  }
  private val spentIn = IntArray(allowance.size)
  private val read = HashMap<Int, MutableList<FrameSignature>>()   // per lineage: the look of every frame of it that was read

  /** Extra reads made so far (the video's detail line shows it). */
  var used = 0
    private set

  /** False when no minute of this video has any allowance (a clip under ten seconds): no frame is worth a signature. */
  val enabled: Boolean get() = allowance.any { it > 0 }

  /** A kept frame whose reading showed words, with its look: its lineage may now claim, and its look is covered from now on. */
  fun covered(lineage: Int, signature: FrameSignature) = add(lineage, signature)

  private fun add(lineage: Int, signature: FrameSignature) {
    val list = read.getOrPut(lineage) { mutableListOf() }
    if (list.size < MAX_READS_PER_LINEAGE) list += signature
  }

  /**
   * Cheap pre-check for a merged frame at [atMs] (from the start of this video or window) of [lineage]: false means no signature is
   * worth computing, because [claim] would refuse whatever it showed.
   */
  fun mayClaim(lineage: Int, atMs: Long): Boolean {
    val list = read[lineage] ?: return false   // the lineage's frame showed no words, or was not looked at: it never claims
    if (list.size >= MAX_READS_PER_LINEAGE) return false
    val m = minuteOf(atMs)
    return spentIn[m] < allowance[m]
  }

  /** True, and the read is counted and covered, when the merged frame [signature] of [lineage] at [atMs] should be read. */
  fun claim(lineage: Int, signature: FrameSignature, atMs: Long): Boolean {
    if (!mayClaim(lineage, atMs)) return false
    if (read.getValue(lineage).any { !signature.mayShowOtherText(it) }) return false
    spentIn[minuteOf(atMs)]++
    used++
    add(lineage, signature)
    return true
  }

  private fun minuteOf(atMs: Long): Int = (atMs / (MINUTE_S * 1000L)).toInt().coerceIn(0, allowance.size - 1)

  companion object {
    /** The extra OCR passes one video minute may cost (measured per call on the A06 in the research notes, video-text). */
    const val MAX_PER_MINUTE = 6
    /** Frames of one lineage that may be read in all (the kept frame and its extra reads); also what bounds the looks it remembers. */
    const val MAX_READS_PER_LINEAGE = 12
    private const val MINUTE_S = 60

    /** Extra reads allowed in [spanSeconds] (at most one minute) of video: [MAX_PER_MINUTE] a minute, whole reads only, so 0 under ten seconds. */
    fun allowanceFor(spanSeconds: Int): Int = spanSeconds.coerceIn(0, MINUTE_S) * MAX_PER_MINUTE / MINUTE_S
  }
}
