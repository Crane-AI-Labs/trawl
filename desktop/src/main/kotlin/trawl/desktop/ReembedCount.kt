package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.ReembedProgress

/**
 * "Updating for the new model: n of m" (the phone's line). The count is a join over every segment of the index (a table seek each:
 * 0.26 s of a core on 130k files, and the cause of 4.9 CPU-s a minute on a paused 180k-file index in 1.0.3), so it is
 * taken at most every [everyMs], and only when a model upgrade queued something ([queued] > 0): the desktop never keeps stale vectors,
 * so on a computer the total is absent and the count is skipped. Once a count finds nothing left it is not taken again until [reset]
 * (the next model attached).
 */
class ReembedCount(
  private val everyMs: Long,
  private val queued: () -> Int,
  private val scan: () -> ReembedProgress,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private var at = Long.MIN_VALUE / 2
  private var last: Pair<Int, Int>? = null
  private var finished = false

  /** How many times [scan] really ran. */
  @Volatile var scans = 0
    private set

  @Synchronized fun reset() { finished = false; at = Long.MIN_VALUE / 2; last = null }

  @Synchronized fun of(kinds: Set<FileKind>): Pair<Int, Int>? {
    val t = now()
    if (t - at < everyMs) return last
    at = t
    last = if (finished || queued() <= 0) null else runCatching {
      scans++
      val p = scan()
      if (p.waiting.values.sum() == 0) finished = true
      p.over(kinds)
    }.getOrNull()
    return last
  }
}
