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

  /** Android ADPF forecast horizon for `PowerManager.getThermalHeadroom(30)`. */
  const val FORECAST_SECONDS = 30

  /** Minimum interval between `getThermalHeadroom` polls per Android ADPF guidance (>= 10 s). */
  const val HEADROOM_POLL_MIN_MS = 10_000L

  /** Headroom >= 1.0 means severe throttling is imminent or active: pause for a full cool-down. */
  const val HEADROOM_COOL_DOWN = 1.0f

  /** Predictive pacing starts earlier while charging (battery + SoC share the thermal envelope). */
  const val HEADROOM_PACE_BATTERY = 0.85f
  const val HEADROOM_PACE_CHARGING = 0.78f

  /** Smooth duty range while pacing ahead of the throttle point. */
  const val MAX_PACE_DUTY = 0.60
  const val MIN_PACE_DUTY = 0.25

  /** At Android `THERMAL_STATUS_MODERATE` (2), duty is capped at or below this share. */
  const val MODERATE_MAX_DUTY = 0.40

  const val THERMAL_MODERATE = 2
  const val THERMAL_SEVERE = 3

  enum class Action { FULL_SPEED, PACE, COOL_DOWN }

  /**
   * Outcome of [decide]: run flat out ([Action.FULL_SPEED]), pace between files at [duty] ([Action.PACE]), or pause for a full
   * cool-down ([Action.COOL_DOWN]). [duty] is 1.0 at FULL_SPEED, 0.0 at COOL_DOWN, and in [MIN_PACE_DUTY, MAX_PACE_DUTY] when pacing.
   */
  data class Decision(
    val action: Action,
    val duty: Double = 1.0,
    val reason: String = "cool",
  )

  /**
   * Rate-limited cache for `PowerManager.getThermalHeadroom(30)`: calling the OS more often than every [minIntervalMs]
   * returns NaN or wastes binder/sensor work, so callers share one cache and pass a monotonic clock.
   */
  class HeadroomCache(private val minIntervalMs: Long = HEADROOM_POLL_MIN_MS) {
    private var lastPollAt: Long = Long.MIN_VALUE
    private var cached: Float = Float.NaN

    @Synchronized
    fun read(nowMs: Long, fetch: () -> Float): Float {
      if (lastPollAt != Long.MIN_VALUE && nowMs - lastPollAt < minIntervalMs) return cached
      val v = fetch()
      lastPollAt = nowMs
      cached = if (v.isFinite() && v >= 0f) v else Float.NaN
      return cached
    }
  }

  /** Hot: the battery at or above [fromC], or the fast cores' clock cap under [capBelowKhz] (0: not used; a null cap is unreadable). */
  fun hot(batteryC: Double, fromC: Double, capKhz: Long?, capBelowKhz: Long): Boolean =
    batteryC >= fromC || (capBelowKhz > 0 && capKhz != null && capKhz < capBelowKhz)

  /** The rest after a file that took [workMs] when [hot]: none when not hot, at full [duty] or for a file that took no time. */
  fun restMs(workMs: Long, hot: Boolean, duty: Double): Long =
    if (!hot || duty >= 1.0 || duty <= 0.0 || workMs <= 0) 0L
    else (workMs * (1.0 / duty - 1.0)).toLong().coerceAtMost(MAX_REST_MS)

  /**
   * Predictive thermal decision combining Android ADPF [headroom] (from `getThermalHeadroom(30)`, `NaN` when unsupported),
   * [thermalStatus] (0 none .. 2 moderate .. 3 severe), [batteryC], [charging], and fast-core [capKhz].
   *
   * - [Action.COOL_DOWN] when `headroom >= 1.0f`, `thermalStatus >= 3`, or `batteryC >= hotStopC`.
   * - [Action.PACE] ahead of throttling when valid headroom reaches the charger-aware threshold (0.85 on battery, 0.78 charging),
   *   linearly scaling duty from 0.60 down to 0.25 toward headroom 1.0; `thermalStatus >= 2` caps duty at <= 0.40.
   * - Falls back cleanly to [batteryC] (`warmC` / `paceFromC`) and [capKhz] (`paceCapBelowKhz`) when headroom is NaN or < 0,
   *   and keeps battery temperature / clock-cap as an independent safety net for devices whose thermal HAL under-reports.
   */
  fun decide(
    headroom: Float,
    thermalStatus: Int,
    batteryC: Double,
    charging: Boolean,
    capKhz: Long? = null,
    warmC: Double = 41.0,
    hotStopC: Double = 44.0,
    paceFromC: Double = 99.0,
    paceCapBelowKhz: Long = 0L,
  ): Decision {
    val validHeadroom = headroom.isFinite() && headroom >= 0f
    if ((validHeadroom && headroom >= HEADROOM_COOL_DOWN) ||
      thermalStatus >= THERMAL_SEVERE ||
      batteryC >= hotStopC
    ) {
      val why = when {
        validHeadroom && headroom >= HEADROOM_COOL_DOWN -> "headroom"
        thermalStatus >= THERMAL_SEVERE -> "thermal_severe"
        else -> "battery_hot"
      }
      return Decision(Action.COOL_DOWN, duty = 0.0, reason = why)
    }

    var duty = 1.0
    var why = "cool"

    if (validHeadroom) {
      val threshold = if (charging) HEADROOM_PACE_CHARGING else HEADROOM_PACE_BATTERY
      if (headroom >= threshold) {
        val frac = ((headroom - threshold) / (HEADROOM_COOL_DOWN - threshold)).toDouble().coerceIn(0.0, 1.0)
        duty = MAX_PACE_DUTY - frac * (MAX_PACE_DUTY - MIN_PACE_DUTY)
        why = "headroom"
      }
    }

    if (thermalStatus >= THERMAL_MODERATE && MODERATE_MAX_DUTY < duty) {
      duty = MODERATE_MAX_DUTY
      why = "thermal_moderate"
    }

    if (batteryC >= warmC) {
      val span = (hotStopC - warmC).coerceAtLeast(0.1)
      val frac = ((batteryC - warmC) / span).coerceIn(0.0, 1.0)
      val tempDuty = MAX_PACE_DUTY - frac * (MAX_PACE_DUTY - MIN_PACE_DUTY)
      if (tempDuty < duty) {
        duty = tempDuty
        why = "battery_warm"
      }
    }

    if (hot(batteryC, paceFromC, capKhz, paceCapBelowKhz) && MAX_PACE_DUTY < duty) {
      duty = MAX_PACE_DUTY
      why = if (paceCapBelowKhz > 0 && capKhz != null && capKhz < paceCapBelowKhz) "clock_cap" else "pace_temp"
    }

    return if (duty < 1.0) Decision(Action.PACE, duty = duty, reason = why)
    else Decision(Action.FULL_SPEED, duty = 1.0, reason = "cool")
  }
}
