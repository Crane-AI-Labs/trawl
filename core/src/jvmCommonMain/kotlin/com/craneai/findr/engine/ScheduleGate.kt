package com.craneai.findr.engine

/**
 * Coalesces requests to (re)schedule indexing work. On a fresh install the TECNO Spark 50 started IndexWorker 40 times and
 * ChangeWorker 13 times in the first minute (every MediaStore notification ran ChangeWorker, and every ChangeWorker run
 * enqueued the index work again after the previous run had already exited with nothing to do); after that storm
 * JobScheduler left our jobs pending for 12 minutes (the research notes). At most one enqueue per [gapMs]: a request
 * inside the window turns into one trailing enqueue at its end, so a request that matters is never lost.
 */
class ScheduleGate(private val gapMs: Long) {
  sealed interface Decision {
    /** Enqueue now. */
    data object Now : Decision
    /** Enqueue at [atMs] (the end of the window); the caller arranges it and reports back with [trailingFired]. */
    data class Later(val atMs: Long) : Decision
    /** A trailing enqueue is already arranged; nothing to do. */
    data object Coalesced : Decision
  }

  private var lastAt: Long? = null
  private var trailingAt: Long? = null

  @Synchronized
  fun request(nowMs: Long): Decision {
    trailingAt?.let { return Decision.Coalesced }
    val last = lastAt
    if (last == null || nowMs - last >= gapMs) {
      lastAt = nowMs
      return Decision.Now
    }
    val at = last + gapMs
    trailingAt = at
    return Decision.Later(at)
  }

  @Synchronized
  fun trailingFired(nowMs: Long) {
    lastAt = nowMs
    trailingAt = null
  }
}
