package com.craneai.findr.engine

/**
 * Waiting for the user inside one file (the worker's yield, the research notes). Between expensive steps the file waits
 * while the user scrolls or types, at most [capMs] per attempt, so a long scroll can't push it past its timeout. Past the
 * cap with the user still busy, the file is handed back ([DeferredForUser]) instead of being worked on under a moving
 * grid; the worker leaves it pending and, waiting between files as before, starts it again once the user stops. Each
 * attempt starts with a fresh budget ([reset]), so a deferred file can't starve.
 */
class UserYield(
  private val capMs: Long,
  /** Waits up to maxMs while the user is busy; `gpu`: before a GPU step, which needs a longer quiet (FindrEngine.gpuShouldWait). */
  private val pauseForUser: suspend (maxMs: Long, gpu: Boolean) -> Unit,
  private val userBusy: (gpu: Boolean) -> Boolean,
  private val nowNs: () -> Long = System::nanoTime,
) {
  /** Time this attempt spent waiting for the user (not indexing time). */
  @Volatile var waitedMs = 0L   // written from the frame and soundtrack coroutines of one video
    private set

  fun reset() { waitedMs = 0 }

  /** [gpu]: the next step runs on the GPU (an image or frame embed), which can't be interrupted once started. */
  suspend fun letUserFirst(gpu: Boolean = false) {
    val left = capMs - waitedMs
    if (left > 0) {
      val t0 = nowNs()
      pauseForUser(left, gpu)
      waitedMs += (nowNs() - t0) / 1_000_000
    }
    if (waitedMs >= capMs && userBusy(gpu)) throw DeferredForUser()
  }

  /** The user kept scrolling past a file's wait budget: not a failure, the file is picked up again later. */
  class DeferredForUser : Exception("deferred: the user kept scrolling")
}
