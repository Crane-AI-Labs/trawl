package trawl.desktop

/**
 * Reading steps aside while the person is searching (the phone's "user first" rule, `UserYield`): a search is a few hundred
 * milliseconds of work that must feel instant, and the reader uses most of the computer's cores. [touch] is called when a query
 * arrives; the reader calls [awaitQuiet] between files and waits until no query has come for [quietMs], but never longer than
 * [capMs] in a row, so a person who keeps typing for a minute cannot stop the reading for good.
 *
 * Nothing here knows about threads or the real clock: the clock and the sleep are passed in, so the tests run it in no time.
 */
class SearchYield(
  private val quietMs: Long = QUIET_MS,
  private val capMs: Long = CAP_MS,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val sleep: (Long) -> Unit = Thread::sleep,
) {
  @Volatile private var lastQuery = Long.MIN_VALUE / 2

  /** A query has just been asked (or typed). */
  fun touch() { lastQuery = nowMs() }

  /** True while a query was asked within the last [quietMs]. */
  fun searching(): Boolean = nowMs() - lastQuery < quietMs

  /**
   * Waits until nobody is searching, in small steps, and returns how long it waited. Gives up at [capMs], or as soon as [stop] says so
   * (a new job, pause, closing).
   */
  fun awaitQuiet(stop: () -> Boolean = { false }): Long {
    var waited = 0L
    while (searching() && waited < capMs && !stop()) {
      sleep(STEP_MS)
      waited += STEP_MS
    }
    return waited
  }

  companion object {
    const val QUIET_MS = 1_200L
    const val CAP_MS = 8_000L
    const val STEP_MS = 100L
  }
}
