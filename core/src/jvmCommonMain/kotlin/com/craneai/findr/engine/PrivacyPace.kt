package com.craneai.findr.engine

/**
 * How fast the background privacy pass may score while the phone is still making new photos searchable
 * (internal/research/privacy-pace-until-searchable.md). On the Galaxy A06 the pass's two threads heat the little cores next to quick
 * look, and resting the pass while the phone is warm made 1,000 new photos searchable 12-14 % sooner. That gain exists only while
 * quick look and the words pass still have work; after that the rest only leaves photos unscored (blurred "Checking" since 1.0.7).
 * So, after each photo the pass scores:
 *
 *  - [Mode.PACE]: searchable work is left, the phone is warm, and this episode has paced for less than [maxPaceMs]: rest
 *    [HeatPace.restMs] at [PACE_DUTY] on top of the usual share while EmbeddingGemma embeds.
 *  - [Mode.FLAT]: searchable work is left but the phone is cool, or the cap is spent: the usual share only (the 1.0.7 behaviour).
 *  - [Mode.CATCH_UP]: quick look and words are done: no rest at all, so the scores held back are caught up while EmbeddingGemma does
 *    the full reading. The usual share stays when [catchUp] is false (the Gentle learning speed).
 *
 * An episode starts at the first PACE and ends when searchable work runs out; the next backlog gets a fresh [maxPaceMs]. One
 * instance per index-worker run. Scoring a thumbnail on screen (PrivacyOnDemand) never asks: it is never paced.
 * Not thread-safe: the pass calls it from its own single thread.
 */
class PrivacyPace(
  private val clock: () -> Long,
  /** Quick look or the words pass still has work (a store read: asked at most every [WORK_READ_MS]). */
  private val searchableWorkLeft: () -> Boolean,
  /** The phone is warm ([HeatPace.hot]); the caller caches its own reads. */
  private val warm: () -> Boolean,
  /** Off: always [Mode.FLAT], the 1.0.7 behaviour (a knob for the baseline arm). */
  private val enabled: Boolean = true,
  /** CATCH_UP drops the usual share while EmbeddingGemma embeds; false keeps it (Gentle). */
  private val catchUp: Boolean = true,
  private val maxPaceMs: Long = MAX_PACE_MS,
) {
  enum class Mode { PACE, FLAT, CATCH_UP }

  private var workAt = Long.MIN_VALUE
  private var workLeft = true
  private var episodeStart = -1L
  private var capped = false

  /** The mode last returned by [mode] (null before the first call). */
  var last: Mode? = null
    private set

  /** Milliseconds this episode has paced so far (0 outside one). */
  fun pacedMs(): Long = if (episodeStart < 0) 0L else clock() - episodeStart

  fun mode(): Mode {
    if (!enabled) return Mode.FLAT.also { last = it }
    val now = clock()
    if (workAt == Long.MIN_VALUE || now - workAt >= WORK_READ_MS) {
      workLeft = searchableWorkLeft()
      workAt = now
    }
    val m = when {
      !workLeft -> {
        episodeStart = -1L; capped = false
        Mode.CATCH_UP
      }
      capped -> Mode.FLAT
      !warm() -> Mode.FLAT
      else -> {
        if (episodeStart < 0) episodeStart = now
        if (now - episodeStart >= maxPaceMs) { capped = true; Mode.FLAT } else Mode.PACE
      }
    }
    last = m
    return m
  }

  /**
   * The rest after a photo that took [workMs], in [mode]. [embedding]: EmbeddingGemma is embedding now; [busyDuty]: the pass's share
   * of the time then (Tuning.privacyDuty, 0.2).
   */
  fun restMs(mode: Mode, workMs: Long, embedding: Boolean, busyDuty: Double): Long {
    val busy = if (embedding && workMs > 0 && busyDuty > 0.0 && busyDuty < 1.0 && !(mode == Mode.CATCH_UP && catchUp))
      (workMs * (1 / busyDuty - 1)).toLong() else 0L
    val pace = if (mode == Mode.PACE) HeatPace.restMs(workMs, hot = true, duty = PACE_DUTY) else 0L
    return busy + pace
  }

  companion object {
    /** The pass's share of the time while paced: the A06 study's privacy-only arm (pacePrivacyDuty=0.2). */
    const val PACE_DUTY = 0.2
    /** Battery temperature from which the phone counts as warm (the study's trigger). */
    const val WARM_FROM_C = 38.0
    /** The longest one episode paces: a very large import gets the speed-up early, then the scores catch up. */
    const val MAX_PACE_MS = 15 * 60_000L
    const val WORK_READ_MS = 5_000L

    /**
     * Warm for pacing: the battery at [WARM_FROM_C] or more, or the fast cores capped under their own maximum (on the A06, under
     * 2.0 GHz: the study's trigger). An unreadable cap or maximum is not warm.
     */
    fun warm(batteryC: Double, capKhz: Long?, maxKhz: Long?): Boolean =
      batteryC >= WARM_FROM_C || (capKhz != null && maxKhz != null && maxKhz > 0 && capKhz < maxKhz)
  }
}
