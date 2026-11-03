package com.craneai.findr.engine

/**
 * Pacing by heat, a study knob (internal/research/a06-thermal-pace.md): once the phone is warm, a lane works only [duty] of the
 * time, resting after each file in proportion to the work that file took. The question it answers is whether a phone kept under
 * its throttle point finishes a big photo batch sooner than one that runs flat out into the throttle.
 *
 * Off unless a profiling build's knob file turns it on (duty 1.0 and no threshold): every shipped build runs flat out while it is
 * allowed to run, as before. It never changes WHEN work may run (IndexPolicy, the charger and polite rules), only how fast.
 */
object HeatPace {
  /** The longest rest after one file, so a slow file (a long OCR) never parks a lane for long. */
  const val MAX_REST_MS = 10_000L

  /** Hot: the battery at or above [fromC], or the fast cores' clock cap under [capBelowKhz] (0: not used; a null cap is unreadable). */
  fun hot(batteryC: Double, fromC: Double, capKhz: Long?, capBelowKhz: Long): Boolean =
    batteryC >= fromC || (capBelowKhz > 0 && capKhz != null && capKhz < capBelowKhz)

  /** The rest after a file that took [workMs] when [hot]: none when not hot, at full [duty] or for a file that took no time. */
  fun restMs(workMs: Long, hot: Boolean, duty: Double): Long =
    if (!hot || duty >= 1.0 || duty <= 0.0 || workMs <= 0) 0L
    else (workMs * (1.0 / duty - 1.0)).toLong().coerceAtMost(MAX_REST_MS)
}
