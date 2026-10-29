package com.craneai.findr.engine

import com.craneai.findr.core.PauseReason

/**
 * What the meter says about waiting, as pure functions so they can be tested without a phone.
 *
 * The facts behind the copy (see [IndexPolicy]): names and words always run; on battery quick look and new files run too;
 * full quality over the backlog starts on the charger. So when files still wait and the charger would unlock more, the
 * meter says so at once, and never promises a finish time that depends on the charger. Low battery and heat are shown the
 * moment they hold the work back, not when the worker next stops.
 */
object MeterRules {

  /**
   * Full quality is waiting for the charger: files still wait, and the phone is on battery with the charger the only thing missing.
   * Never without a search model ([modelReady] false): the charger unlocks nothing until the models are on the phone, so the meter
   * must not promise it (the first-run phone that has not got its models yet).
   */
  fun needsCharger(waiting: Int, decision: IndexPolicy.Decision, modelReady: Boolean = true): Boolean =
    modelReady && waiting > 0 && decision.heldBy == PauseReason.NOT_CHARGING

  /**
   * Why indexing is paused, if it is. [workerPause] is the worker's own signal (a warm battery resting it); the charger,
   * the battery level and the phone's heat come from the live [decision], so nothing stale outlives a plug-in.
   */
  fun pause(
    hasAllFilesAccess: Boolean,
    userPaused: Boolean,
    workerPause: PauseReason?,
    workerRunning: Boolean,
    waiting: Int,
    decision: IndexPolicy.Decision,
    modelReady: Boolean = true,
  ): PauseReason? = when {
    !hasAllFilesAccess -> PauseReason.NO_PERMISSION
    userPaused -> PauseReason.USER
    waiting > 0 && decision.tier == IndexPolicy.Tier.WORDS_ONLY &&
      (decision.heldBy == PauseReason.BATTERY_LOW || decision.heldBy == PauseReason.TOO_HOT) -> decision.heldBy
    workerPause != null && workerPause != PauseReason.NOT_CHARGING -> workerPause
    needsCharger(waiting, decision, modelReady) && !workerRunning -> PauseReason.NOT_CHARGING
    else -> null
  }

  /** No finish time while full quality waits for the charger: the pace is measured on the charger, so any number would be a guess. */
  fun eta(measured: Long?, needsCharger: Boolean): Long? = if (needsCharger) null else measured
}
