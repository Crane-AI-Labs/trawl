package trawl.desktop

import java.awt.image.BufferedImage

/**
 * How a video or a recording is cut into steps and how many pictures each step gets: the phone's rules (FileProcessor's companion and
 * Tuning), copied here because that class lives in the Android app. Numbers are the phone's own, so a video is read the same on both:
 *
 *  - a file up to two minutes (or of unknown length) is one step; a longer one is read a window (one minute) at a time, and each window
 *    is saved as it is done, so the work is bounded whatever the length and a restart goes on from the first window not saved;
 *  - 9 pictures in each of the first three windows, 6 a minute after that, and past minute 30 only [DEEP_FRAMES_PER_MIN] a minute
 *    (the words of the soundtrack go on to the end, the pictures stop being dense);
 *  - frames are evenly spaced inside the window; a frame that looks like one already kept (a still meme with a soundtrack, a slide held
 *    for a minute) gets no embedding of its own, found by a 64-bit difference hash.
 */
object MediaSchedule {
  const val MEDIA_WINDOW_MS = 60_000L
  const val MAX_MEDIA_WINDOWS = 720
  const val DEEP_WINDOWS = 30
  /** Tuning.deepFramesPerMin on the phone. */
  const val DEEP_FRAMES_PER_MIN = 1
  const val OVERLAP_MAX_SECONDS = 120
  /** Of 64 bits: the loosest difference with zero loss on the phone's video eval. */
  const val SAME_FRAME_BITS = 6
  /** Pictures are embedded at 768 px on the long side, JPEG quality 90, as on the phone. */
  const val EMBED_MAX_SIDE = 768
  /** Tuning.mediaShare: about one second in five of busy time goes to videos and recordings. */
  const val MEDIA_SHARE = 0.2
  /** Tuning.asrMaxSpeechS: the longest speech segment the recogniser is given. */
  const val ASR_MAX_SPEECH_S = 15
  /** A file never waits more than this for the person using the computer (the phone's MAX_WAIT_PER_FILE_MS). */
  const val MAX_WAIT_PER_FILE_MS = 60_000L

  private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b

  fun isDeepWindow(window: Int?): Boolean = window == null || window < DEEP_WINDOWS

  fun windowCount(durationMs: Long?): Int =
    if (durationMs == null || durationMs <= 2 * MEDIA_WINDOW_MS) 1
    else minOf(ceilDiv(durationMs, MEDIA_WINDOW_MS), MAX_MEDIA_WINDOWS.toLong()).toInt()

  fun isWindowed(durationMs: Long?): Boolean = windowCount(durationMs) > 1

  /** Pictures for a whole short video (one step): a frame every 5 s for the first minute, every 10 s after, 1 to 24. */
  fun framesFor(seconds: Int): Int = (ceilDiv(minOf(seconds, 60).toLong(), 5) + ceilDiv(maxOf(seconds - 60, 0).toLong(), 10)).toInt().coerceIn(1, 24)

  /** Pictures in window [window] of a video [durationMs] long; a short last window gets its share, not the full number. */
  fun framesInWindow(window: Int, durationMs: Long?, deepFramesPerMin: Int = DEEP_FRAMES_PER_MIN): Int {
    val full = if (window >= DEEP_WINDOWS && deepFramesPerMin > 0) deepFramesPerMin.coerceAtMost(6) else if (window < 3) 9 else 6
    val span = ((durationMs ?: Long.MAX_VALUE) - window * MEDIA_WINDOW_MS).coerceIn(1, MEDIA_WINDOW_MS)
    return ceilDiv(full * span, MEDIA_WINDOW_MS).toInt().coerceAtLeast(1)
  }

  /** A moment to take a picture at, and the stretch of the video it stands for. */
  class Shot(val midMs: Long, val startMs: Long, val endMs: Long)

  /**
   * [n] evenly spaced shots in a stretch of [spanMs] that starts at [fromMs]: the picture comes from the middle of each, the way the phone
   * does with shot detection off (evenly spaced frames beat one-per-shot on its video eval).
   */
  fun shots(fromMs: Long, spanMs: Long, n: Int): List<Shot> {
    val count = n.coerceAtLeast(1)
    val span = spanMs.coerceAtLeast(1)
    return (0 until count).map { i ->
      val a = span * i / count
      val b = maxOf(span * (i + 1) / count, a + 1)
      Shot(fromMs + (a + b) / 2, fromMs + a, fromMs + b)
    }
  }

  /** The step a stored file is at: "next=N" in its detail (a window of a long file), else 0. */
  fun nextWindow(detail: String?): Int = detail?.let { NEXT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
  fun hasNext(detail: String?): Boolean = detail?.let { NEXT.containsMatchIn(it) } == true
  private val NEXT = Regex("next=(\\d+)")

  /** 64-bit difference hash: 9 by 8 grey, each pixel brighter than its right neighbour? Equal for re-encoded copies of one picture. */
  fun differenceHash(image: BufferedImage): Long {
    val small = BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB)
    val g = small.createGraphics()
    try {
      g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.drawImage(image.getScaledInstance(9, 8, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
    } finally { g.dispose() }
    var hash = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
      if (luma(small.getRGB(x, y)) > luma(small.getRGB(x + 1, y))) hash = hash or (1L shl (y * 8 + x))
    }
    return hash
  }

  private fun luma(c: Int) = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000

  fun similar(a: Long, b: Long): Boolean = java.lang.Long.bitCount(a xor b) <= SAME_FRAME_BITS
}
