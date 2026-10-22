package com.craneai.findr.ui.keeplearning

import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason

/**
 * "Keep learning": a dark, dimmed screen that keeps Trawl in front while it indexes. On TECNO, Infinix and itel phones the
 * system (HiOS/XOS Hiber) freezes an app a few seconds after the screen turns off, so indexing happens only while Trawl is on
 * screen. A screen saver does not help (Hiber freezes the dreaming app too); a lit screen with Trawl in front ran at full
 * speed with no freezes (728 photos/h on a TECNO KN4 at the lowest brightness, the research notes). The price is a phone
 * that stays unlocked while it runs, which the screen says.
 *
 * The rules are pure, so the whole table is unit-tested: when the button is offered, and when the screen stops by itself.
 */
object KeepLearning {
  /** Off the charger, below this battery percentage the screen stops ("plug in and start again"). */
  const val LOW_BATTERY_PCT = 20

  /** Battery temperature at which the screen stops. Below the worker's own cool-down (44 °C), so the phone never gets there. */
  const val HOT_C = 42.0

  /** Android's PowerManager.THERMAL_STATUS_SEVERE: at this status or above the screen stops. */
  const val THERMAL_SEVERE = 3

  /** Without Transsion's freezing, a backlog this large (an hour or more on a budget phone) is reason enough to offer it. */
  const val LARGE_BACKLOG = 1_000

  /** "Up to date" must hold this long before the screen calls it done (the phase can flicker between worker runs). */
  const val DONE_HOLD_MS = 20_000L

  /** A charger unplugged for this long (not a wobble of the cable) stops a screen that was started on the charger. */
  const val UNPLUG_GRACE_MS = 5_000L

  /** The engine stuck in a pause it can't leave by itself (paused by the user, no access, no space) for this long. */
  const val STUCK_MS = 30_000L

  /** On battery with nothing moving (the heavy work waits for the charger) for this long. */
  const val IDLE_ON_BATTERY_MS = 120_000L

  enum class Stop { DONE, LOW_BATTERY, HOT, UNPLUGGED, NEEDS_CHARGER, CANT_CONTINUE }

  /** What the screen knows each tick. Times are how long the current condition has held. */
  data class Inputs(
    val phase: IndexPhase,
    val pauseReason: PauseReason?,
    val needsCharger: Boolean,
    val sameStateForMs: Long,
    val noProgressForMs: Long,
    val plugged: Boolean,
    val batteryPct: Int,
    val batteryTempC: Double,
    val thermalStatus: Int,
    val startedOnCharger: Boolean,
    val unpluggedForMs: Long,
  )

  private val CANT_LEAVE = setOf(PauseReason.USER, PauseReason.NO_PERMISSION, PauseReason.STORAGE_LOW)

  /** Why the screen should stop now, or null to keep going. Safety first: heat and battery before anything else. */
  fun stopReason(i: Inputs): Stop? = when {
    i.thermalStatus >= THERMAL_SEVERE || i.batteryTempC >= HOT_C -> Stop.HOT
    !i.plugged && i.batteryPct in 0 until LOW_BATTERY_PCT -> Stop.LOW_BATTERY
    i.startedOnCharger && !i.plugged && i.unpluggedForMs >= UNPLUG_GRACE_MS -> Stop.UNPLUGGED
    i.phase == IndexPhase.UP_TO_DATE && i.sameStateForMs >= DONE_HOLD_MS -> Stop.DONE
    (i.phase == IndexPhase.ERROR || (i.phase == IndexPhase.PAUSED && i.pauseReason in CANT_LEAVE)) && i.sameStateForMs >= STUCK_MS ->
      Stop.CANT_CONTINUE
    !i.plugged && (i.needsCharger || i.pauseReason == PauseReason.NOT_CHARGING || i.pauseReason == PauseReason.BATTERY_LOW) &&
      i.noProgressForMs >= IDLE_ON_BATTERY_MS -> Stop.NEEDS_CHARGER
    else -> null
  }

  /** Files still to be understood. */
  fun remaining(s: IndexStatus): Int = (s.filesReadable - s.filesUnderstood).coerceAtLeast(0)

  /**
   * Whether the progress sheet offers "Keep learning now": work is left and Trawl is (or could be) working on it, on a
   * Transsion phone, or anywhere the backlog is large.
   */
  fun offered(s: IndexStatus, transsion: Boolean): Boolean {
    val left = remaining(s)
    val working = s.phase == IndexPhase.SCANNING || s.phase == IndexPhase.INDEXING ||
      (s.phase == IndexPhase.PAUSED && s.pauseReason == PauseReason.NOT_CHARGING)
    return working && left > 0 && (transsion || left >= LARGE_BACKLOG)
  }
}
