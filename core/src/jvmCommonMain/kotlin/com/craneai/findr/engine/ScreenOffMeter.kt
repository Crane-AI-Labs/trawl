package com.craneai.findr.engine

/**
 * Does this phone let Trawl work once the screen is off? Not a guess from the brand: the worker measures it. The device-profile
 * study found that two Transsion phones on the same HiOS/XOS Hiber behave opposite ways: a TECNO KN4 (3.7 GB) freezes the app's
 * process about 5 s after the screen goes off and stays frozen for the run (22 files in 30 minutes on the charger, against ~520 for
 * the Infinix HOT 60 Pro with the same setup), while the Infinix thaws the app every 5 s and works at 90% of its screen-on speed.
 * So the "this phone pauses apps" card ([com.craneai.findr.ui.background.BackgroundAccess]) is shown where the pause is observed.
 *
 * How it measures: while a run is alive a one-second tick compares the clock with the last tick. With the screen off, a tick that
 * comes on time means the process ran for that whole stretch; one that comes late means the process was frozen (or the CPU asleep)
 * for all but one second of it. A frozen process cannot report its own freeze, so the late tick is the evidence.
 *
 * Pure (times in, numbers out) so the rule is unit-tested; the worker feeds it and [ScreenOffRecord] keeps the totals.
 */
class ScreenOffMeter {
  private var lastAt = -1L
  private var lastOff = false

  /** Screen-off time watched, and the part of it the process actually ran, since the last [drain]. */
  var observedMs = 0L
    private set
  var ranMs = 0L
    private set

  /** One tick: [nowMs] on a monotonic clock that keeps counting while the CPU sleeps (SystemClock.elapsedRealtime). */
  fun tick(nowMs: Long, screenOff: Boolean) {
    val prev = lastAt
    val wasOff = lastOff
    lastAt = nowMs
    lastOff = screenOff
    // Only a stretch that began and ended with the screen off says anything: a screen turned on in between is the person, not the OS.
    if (prev < 0 || !wasOff || !screenOff || nowMs <= prev) return
    val gap = nowMs - prev
    observedMs += gap
    ranMs += if (gap <= LATE_MS) gap else TICK_MS
  }

  /** What was measured since the last call, then start again from zero. */
  fun drain(): ScreenOffRecord = ScreenOffRecord(observedMs, ranMs).also { observedMs = 0; ranMs = 0 }

  companion object {
    const val TICK_MS = 1_000L

    /** A tick later than this after the previous one is a stall, not scheduling jitter. */
    const val LATE_MS = 3_000L
  }
}

/** The running totals of [ScreenOffMeter], kept across runs. */
data class ScreenOffRecord(val observedMs: Long = 0, val ranMs: Long = 0) {
  enum class Verdict {
    /** Not enough screen-off time watched yet. */
    UNKNOWN,

    /** The app ran for at least half of the screen-off time: overnight indexing works on this phone. */
    WORKS,

    /** The phone froze the app for more than half of it. */
    PAUSED,
  }

  operator fun plus(o: ScreenOffRecord) = ScreenOffRecord(observedMs + o.observedMs, ranMs + o.ranMs).decayed()

  /** Older evidence fades, so a phone that changes (a settings change, an update) is judged on what it does now. */
  fun decayed(): ScreenOffRecord = if (observedMs > KEEP_MS) ScreenOffRecord(observedMs / 2, ranMs / 2) else this

  val share: Double get() = if (observedMs <= 0) 0.0 else ranMs.toDouble() / observedMs

  val verdict: Verdict
    get() = when {
      observedMs < MIN_OBSERVED_MS -> Verdict.UNKNOWN
      share >= WORKS_SHARE -> Verdict.WORKS
      else -> Verdict.PAUSED
    }

  fun encode() = "$observedMs,$ranMs"

  companion object {
    /** Where the engine keeps the totals (the app's one preferences file); the home screen reads the verdict from the same place. */
    const val PREFS = "findr"
    const val KEY = "screen_off_record"
    const val MIN_OBSERVED_MS = 10 * 60_000L
    const val KEEP_MS = 12 * 3_600_000L
    const val WORKS_SHARE = 0.5

    fun decode(s: String?): ScreenOffRecord {
      val p = s?.split(',')?.map { it.trim().toLongOrNull() } ?: return ScreenOffRecord()
      return if (p.size == 2 && p[0] != null && p[1] != null && p[0]!! >= 0 && p[1]!! in 0..p[0]!!) ScreenOffRecord(p[0]!!, p[1]!!) else ScreenOffRecord()
    }
  }
}
