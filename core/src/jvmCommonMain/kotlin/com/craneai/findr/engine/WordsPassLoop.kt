package com.craneai.findr.engine

import kotlinx.coroutines.sync.Mutex

/**
 * The words pass's loop, one caller at a time. ChangeWorker's burst and IndexWorker's pass (and the in-process pass while
 * Findr is on screen) page the same "no words yet" list in batches; run together, each OCR'd the batch it had fetched
 * before the other committed it: on the A06, 300 + 316 file passes for at most 334 files (the research notes). Under
 * the one [lock] a later caller waits, then finds only what is still left.
 */
object WordsPassLoop {
  /**
   * Small, so a file crawled while a long pass runs (a note saved during 300 photos' OCR) is next within a few items:
   * [next] is ordered newest first. The query is milliseconds; a photo's OCR is 0.3-1.6 s.
   */
  const val BATCH = 8

  /**
   * Calls [process] for each item [next] hands out (up to [BATCH] at a time) until none are left, [limit] items are done
   * or [stop] says so. [process] returns false to end the pass early. Returns how many items were processed. With
   * [wait] false it returns 0 at once when another pass holds the lock (the in-app pass: the running one picks up what
   * the caller just crawled, and the caller is free to crawl again).
   */
  suspend fun <T> run(
    lock: Mutex, limit: Int, stop: () -> Boolean, next: (Int) -> List<T>, wait: Boolean = true, process: suspend (T) -> Boolean,
  ): Int {
    if (wait) lock.lock() else if (!lock.tryLock()) return 0
    try {
      var done = 0
      while (!stop() && done < limit) {
        val batch = next(minOf(BATCH, limit - done))
        if (batch.isEmpty()) break
        for (item in batch) {
          if (stop()) break
          if (!process(item)) return done
          done++
        }
      }
      return done
    } finally {
      lock.unlock()
    }
  }
}
