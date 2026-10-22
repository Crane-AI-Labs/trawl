package com.craneai.findr.diag

import com.craneai.findr.core.FindrLog as Log

/**
 * Test builds only (`-PfindrHeapLog=true`, optionally `-PheapBallastMb=N`): a sampler that writes the Java heap's numbers to
 * logcat as "FindrHeap", so a long indexing run on a phone shows how much headroom the app really has.
 *
 * - Every [PERIOD_MS] it logs the lowest and the highest heap use seen in the window (sampled every 250 ms), the heap's growth
 *   limit, the biggest peak since the process started, and how often the collector ran. The lowest value of a window is
 *   close to what is really alive (the collector has just run); the highest is what the allocations needed in between.
 * - [ballastMb] holds that many MB of byte arrays for the life of the process, so a phone with a larger Java heap limit than
 *   the 128 MB that low-end Android phones get behaves like one with the smaller limit (the arrays count against the limit
 *   like any live object). Zero in every shipped build.
 *
 * Counts and sizes only, nothing about the person's files. Both switches are build-time and off in every shipped build.
 */
object HeapProbe {
  private const val TAG = "FindrHeap"
  private const val PERIOD_MS = 10_000L
  private const val SAMPLE_MS = 250L

  @Volatile private var started = false
  private var ballast: Array<ByteArray>? = null

  /** The heap use the process has seen at its worst, in bytes (read by tests and the status line). */
  @Volatile var peakBytes = 0L
    private set

  fun start(enabled: Boolean, ballastMb: Int) {
    this.ballastMb = ballastMb
    if (ballastMb > 0 && ballast == null) {
      try {
        ballast = Array(ballastMb) { ByteArray(1 shl 20) }
      } catch (e: OutOfMemoryError) {
        Log.w(TAG, "ballast of $ballastMb MB does not fit")
      }
    }
    if (!enabled || started) return
    started = true
    MemProbe.start(true)
    Thread({
      val rt = Runtime.getRuntime()
      val limitMb = rt.maxMemory() / MB
      val ballastBytes = ballastMb.toLong() shl 20
      Log.i(TAG, "start: heap limit $limitMb MB, ballast $ballastMb MB, so the app has ${limitMb - ballastMb} MB to work with")
      var windowStart = System.currentTimeMillis()
      var lo = Long.MAX_VALUE; var hi = 0L
      var gcPrev = gcCount()
      while (!Thread.currentThread().isInterrupted) {
        try {
          Thread.sleep(SAMPLE_MS)
          val used = rt.totalMemory() - rt.freeMemory() - ballastBytes
          if (used < lo) lo = used
          if (used > hi) hi = used
          if (used > peakBytes) peakBytes = used
          val now = System.currentTimeMillis()
          if (now - windowStart >= PERIOD_MS) {
            val gc = gcCount()
            Log.i(TAG, "heap used low ${lo / MB} high ${hi / MB} peak ${peakBytes / MB} MB of ${limitMb - ballastMb} (gcs ${gc - gcPrev})")
            windowStart = now; lo = Long.MAX_VALUE; hi = 0; gcPrev = gc
          }
        } catch (e: InterruptedException) {
          break
        }
      }
    }, "findr-heapprobe").apply { isDaemon = true; start() }
  }

  /**
   * Heap-log builds only: collect, then say how much is alive, in MB. Used to chart one operation's live set (a PDF opened by
   * pdfbox, say) page by page. Always 0 in a shipped build, where it must never force a collection.
   */
  fun liveMb(enabled: Boolean): Long {
    if (!enabled) return 0L
    Runtime.getRuntime().gc()
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory() - ballastMb.toLong() * MB) / MB
  }

  @Volatile private var ballastMb = 0

  /** Bytes of test ballast held (0 in a shipped build), so a budget can ignore it. */
  fun ballastBytes(): Long = ballastMb.toLong() * MB

  private fun gcCount(): Long = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L

  private const val MB = 1L shl 20
}
