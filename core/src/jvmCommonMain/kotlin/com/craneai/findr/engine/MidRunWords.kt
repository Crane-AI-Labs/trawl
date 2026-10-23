package com.craneai.findr.engine

/**
 * Words for files that arrive while the meaning pass is running. The index worker reads words once, at the start of a run, and
 * then spends minutes to hours on the meaning pass; the in-app refresh stands aside while the worker runs (two writers), so a
 * document saved mid-run was crawled at best and stayed out of word search until the next run (TECNO KN4 soak: 532 s with
 * about 140 photos still queued).
 *
 * The worker calls [between] after each file it understands (and when it idles). Everything runs in the worker's own
 * coroutine, so it is the one writer: the meaning pass is simply paused for a moment. Cheap when there is nothing to do: one
 * time comparison, then a single-row query for files still without words every [lookEveryMs]. When there are some, one words
 * pass of at most [WordsPassLoop.BATCH] files runs (newest first, small documents ahead of photos), so a long burst takes
 * turns with the meaning pass instead of starving it. Every [crawlEveryMs] it also asks for an incremental crawl (two numbers
 * compared when nothing changed), the net for a ChangeWorker that Android delayed.
 */
class MidRunWords(
  private val lookEveryMs: Long = LOOK_EVERY_MS,
  private val crawlEveryMs: Long = CRAWL_EVERY_MS,
  private val clock: () -> Long,
) {
  private var lastLook: Long? = null
  private var lastCrawl: Long = clock()   // the run has just crawled

  /** Returns how many files got their words. [crawl] and [words] run only when due; [wordsWaiting] is the cheap query. */
  suspend fun between(crawl: suspend () -> Unit, wordsWaiting: () -> Boolean, words: suspend (limit: Int) -> Int): Int {
    val now = clock()
    if (lastLook?.let { now - it < lookEveryMs } == true) return 0
    lastLook = now
    if (now - lastCrawl >= crawlEveryMs) {
      lastCrawl = now
      crawl()
    }
    if (!wordsWaiting()) return 0
    return words(WordsPassLoop.BATCH).also { lastLook = clock() }
  }

  companion object {
    /** How soon after a file lands the worker looks: a few seconds, well under the time one photo takes to understand. */
    const val LOOK_EVERY_MS = 3_000L
    const val CRAWL_EVERY_MS = 15_000L
  }
}
