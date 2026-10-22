package com.craneai.findr.engine

import com.craneai.findr.core.LearningSpeed

/**
 * What each [LearningSpeed] means, in the knobs that already exist: no new machinery. Pure, so the mapping is unit-tested and the
 * Settings text has a single source of truth.
 *
 *  - BALANCED: exactly [Tuning.forDevice] (the measured default) and the power policy it has always had.
 *  - GENTLE: [FindrSettings.onlyWhileCharging] (only words on battery, everything else on the charger), one thread per side lane,
 *    the side lanes at a lower duty, and it rests when the battery is a little cooler ([GENTLE_WARM_C]).
 *  - FAST: [FindrSettings.fullOnBattery] (the whole backlog on battery too; the 30% floor and the heat limits still stop it), polite
 *    only for an unlocked phone ([Tuning.politeOnLockScreen] false), and on a roomy 8-core phone more threads for the side lanes.
 *
 * The thread counts are read when a model loads, so a change applies from the next run; the policy and polite rules apply at once.
 */
object LearningProfile {
  /** GENTLE rests (between files) at this battery temperature, 2 °C under [IndexWorker.WARM_C]. */
  const val GENTLE_WARM_C = 39.0

  /** The Tuning in force for [speed] on a phone whose own profile is [base]. [roomy]: 8 or more cores and not tight on memory. */
  fun tuning(base: Tuning, speed: LearningSpeed, roomy: Boolean): Tuning = when (speed) {
    LearningSpeed.BALANCED -> base
    LearningSpeed.GENTLE -> base.copy(
      textThreadsOff = minOf(base.textThreadsOff, 2),
      clipImageThreads = 1, privacyThreads = 1, asrThreads = 1,
      quickDuty = minOf(base.quickDuty, 0.3), privacyDuty = minOf(base.privacyDuty, 0.3),
      warmC = minOf(base.warmC, GENTLE_WARM_C),
    )
    LearningSpeed.FAST -> base.copy(
      politeOnLockScreen = false,
      asrThreads = if (roomy) 4 else base.asrThreads,
      clipImageThreads = if (roomy) 4 else base.clipImageThreads,
      privacyThreads = if (roomy) 4 else base.privacyThreads,
      quickDuty = if (roomy) 1.0 else base.quickDuty,
    )
  }

  /** The phone-class gate for FAST's extra threads: [cores] logical cores and [tight] from [MemoryBudget.tight]. */
  fun roomy(cores: Int, tight: Boolean): Boolean = cores >= 8 && !tight
}
