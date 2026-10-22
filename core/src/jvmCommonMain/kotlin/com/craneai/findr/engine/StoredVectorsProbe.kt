package com.craneai.findr.engine

/**
 * Answers "does the index hold any vector?" for the words pass, without paying for the SQLite probe on every keystroke.
 *
 * On a words-only index (the first minutes after install, before any vector exists) the probe finds no row and so has to
 * look at every segment. Asking that on each keystroke is the cost this class removes:
 *  - once the matrix is loaded, the answer is yes and the store is not asked;
 *  - a yes is remembered until [reset] (the index was deleted or swapped);
 *  - a no is remembered for [recheckMs], so a words-only index is probed at most once in that time.
 *
 * A stale no only affects the first answer's `complete` flag: the final answer re-checks against the loaded matrix.
 *
 * @param probe the store's own check ([com.craneai.findr.index.IndexStore.hasVectors])
 * @param loaded rows in the in-memory matrix
 * @param now a clock in ms, replaceable in tests
 */
class StoredVectorsProbe(
  private val probe: () -> Boolean,
  private val loaded: () -> Int,
  private val now: () -> Long = System::currentTimeMillis,
  private val recheckMs: Long = RECHECK_MS,
) {
  @Volatile private var known = false
  @Volatile private var lastNoAt = NEVER

  fun any(): Boolean {
    if (known) return true
    if (loaded() > 0) { known = true; return true }
    val t = now()
    val last = lastNoAt
    if (last != NEVER && t - last < recheckMs) return false
    val found = probe()
    if (found) known = true else lastNoAt = t
    return found
  }

  /** The index was deleted or swapped: forget both answers. */
  fun reset() {
    known = false
    lastNoAt = NEVER
  }

  companion object {
    const val RECHECK_MS = 30_000L
    private const val NEVER = Long.MIN_VALUE
  }
}
