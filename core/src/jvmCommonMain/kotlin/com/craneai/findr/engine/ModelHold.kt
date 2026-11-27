package com.craneai.findr.engine

/**
 * How long one model stays loaded between uses. First user: a speech pack's recogniser (125 MiB), which a run of
 * not-English voice notes needs once per file; loading it for each file cost 809 s against 1,714 s of reading on the October WAXAL run.
 *
 * The rules (one place, so the phone engine and the desktop harness keep the same lifetime):
 *  - a lease ([lease]) builds the model if it is not loaded and, when it ends, KEEPS it for the next file, unless the caller says the
 *    phone is [tight] (MemoryBudget: one heavy model at a time, as Parakeet, the audio encoder and quick look share it), in which case
 *    it is closed at once, exactly as before;
 *  - a kept model is closed after [idleMs] with no lease (the run went on to English files, photos or documents, or it ended);
 *  - [release] closes it now (run end, pause, memory pressure, pack removal). If a lease is open the close waits for it to end, so a
 *    native recogniser is never freed under a read.
 *
 * Thread-safe. [schedule] runs an action after a delay and returns a way to cancel it; the default is one daemon timer thread.
 */
class ModelHold<T : Any>(
  private val idleMs: Long = IDLE_MS,
  private val now: () -> Long = { System.nanoTime() / 1_000_000 },
  private val schedule: (Long, () -> Unit) -> (() -> Unit) = DefaultTimer::after,
  private val close: (T) -> Unit,
) {
  private val lock = Any()
  private var model: T? = null
  private var open = 0                 // leases in progress
  private var closeWhenFree = false    // a release asked while a lease was open
  private var lastUsedAt = 0L
  private var cancelIdle: (() -> Unit)? = null

  /** Loaded right now (a lease may be using it). */
  val loaded: Boolean get() = synchronized(lock) { model != null }

  /**
   * Runs [block] with a getter that loads the model on first call. When the block ends the model stays loaded unless [tight] says to
   * give it back now. [tight] is read when the lease ends, so a phone that got short of memory during the file lets go.
   */
  suspend fun <R> lease(tight: () -> Boolean, build: () -> T, block: suspend (get: () -> T) -> R): R {
    synchronized(lock) { open++; cancelIdle?.invoke(); cancelIdle = null }
    try {
      return block {
        synchronized(lock) { model ?: build().also { model = it } }
      }
    } finally {
      synchronized(lock) {
        open--
        lastUsedAt = now()
        if (open == 0) {
          if (closeWhenFree || tight()) closeNow() else if (model != null) armIdle()
        }
      }
    }
  }

  /** Closes the model now, or as soon as the lease in progress ends. */
  fun release() = synchronized(lock) {
    cancelIdle?.invoke(); cancelIdle = null
    if (open > 0) closeWhenFree = true else closeNow()
  }

  /** Closes the model when it has been idle for [idleMs]. Called by the timer; harmless at any time. */
  fun expireIfIdle() = synchronized(lock) {
    if (model != null && open == 0 && now() - lastUsedAt >= idleMs) closeNow()
  }

  private fun armIdle() {
    cancelIdle?.invoke()
    cancelIdle = schedule(idleMs) { expireIfIdle() }
  }

  private fun closeNow() {
    cancelIdle?.invoke(); cancelIdle = null
    closeWhenFree = false
    val m = model ?: return
    model = null
    try { close(m) } catch (_: Throwable) { /* freeing must not take the run down */ }
  }

  companion object {
    /**
     * 45 s: files of one run follow each other within seconds (the pause between files is short), so 45 s bridges any run of voice
     * notes with a few English files, photos or a polite wait in between, and it costs a pack's 125 MiB (plus the runtime's arena) for under a
     * minute after the last not-English file. A reload is 0.9 s on a PC and 5-10 s on a phone, so a shorter hold would pay it often.
     */
    const val IDLE_MS = 45_000L
  }

  private object DefaultTimer {
    private val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
      Thread(r, "model-hold-idle").apply { isDaemon = true }
    }
    fun after(ms: Long, action: () -> Unit): () -> Unit {
      val f = executor.schedule({ action() }, ms, java.util.concurrent.TimeUnit.MILLISECONDS)
      return { f.cancel(false) }
    }
  }
}
