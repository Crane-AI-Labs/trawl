package trawl.desktop

/**
 * When the worker may work out the status for the window again. A change of state (pause, resume, done, a problem) is [immediate]: it
 * always goes out. Progress (a scan tick, one file read) is [progress]: it goes out at most once per [minGapMs], and when it is held back
 * it is remembered, so [flushDue] hands it out as soon as the gap is over. That keeps a long scan or a long read from costing a full
 * status pass per tick, and still leaves nothing stale for longer than about a gap.
 */
class PublishThrottle(private val minGapMs: Long = GAP_MS, private val now: () -> Long = System::currentTimeMillis) {
  private var lastAt = Long.MIN_VALUE / 2
  private var pending = false

  /** A state change: publish now, whatever the gap. */
  @Synchronized fun immediate() { lastAt = now(); pending = false }

  /** Progress: true when it may be published now; false when it is held back (it is then remembered as pending). */
  @Synchronized fun progress(): Boolean {
    val t = now()
    if (t - lastAt >= minGapMs) { lastAt = t; pending = false; return true }
    pending = true
    return false
  }

  /** True once when a held-back update is due (the gap is over): publish it now. */
  @Synchronized fun flushDue(): Boolean {
    if (!pending) return false
    val t = now()
    if (t - lastAt < minGapMs) return false
    lastAt = t; pending = false
    return true
  }

  @Synchronized fun hasPending(): Boolean = pending

  /** How long until a held-back update is due, or null when nothing is held back. */
  @Synchronized fun msUntilDue(): Long? = if (!pending) null else (minGapMs - (now() - lastAt)).coerceAtLeast(0L)

  companion object {
    const val GAP_MS = 1_000L
  }
}
