package com.craneai.findr.engine

/**
 * Trawl stays out of the way while the phone is in someone's hand (polite mode). Pure, so the rule is unit-tested.
 *
 * Polite means: the screen is on and Trawl is not in front (on the lock screen too, unless [Tuning.politeOnLockScreen] is off:
 * [LearningSpeed.FAST] works through a glance at the lock screen and steps aside once the phone is unlocked). Then every heavy stage waits (the words pass's OCR, quick look,
 * privacy scores, EmbeddingGemma's image/text/audio work, speech). On a phone that is short of memory ([MemoryBudget.tight]) the
 * models are also given back after [RELEASE_AFTER_MS]; on a roomy phone they stay loaded until the run hands off
 * ([RunHandoff.HAND_OFF_AFTER_MS]) or the system signals memory pressure ([pressure]).
 * It is on top of [IndexPolicy], not a replacement: the battery tiers decide WHAT may run, this decides WHEN. Trawl in front
 * (search, the quick panel, Keep learning) or the screen off (the phone is down: night, charger, pocket) run at full speed.
 *
 * Measured on the A06 (the research notes): every heavy stage costs the launcher or the next app's cold start, even at background
 * priority and 2 threads (a quiet quick look still costs +30-60% per cold launch), and the GPU image embed alone takes the
 * launcher from 2% to 20% janky frames. Nothing short of waiting meets "within 15% of paused", so nothing is half-run.
 */
object PolitePolicy {
  /** Polite for this long on a memory-tight phone and the models are released: the 4 GB phones need the ~1 GB back for the app in front. */
  const val RELEASE_AFTER_MS = 30_000L

  /** How often the worker looks again while it waits. */
  const val POLL_MS = 1_000L

  /** The person is using the phone and Trawl is not what they are using. */
  fun polite(screenOn: Boolean, trawlVisible: Boolean, keyguardLocked: Boolean = false, politeOnLockScreen: Boolean = true): Boolean =
    screenOn && !trawlVisible && (politeOnLockScreen || !keyguardLocked)

  /**
   * Time to give the models back, once. Either the system asked for memory ([pressure]: that cannot wait), or the phone is short of
   * memory ([memoryTight], [MemoryBudget.tight]) and the person has been in another app long enough (a notification waking the screen
   * for a few seconds does not reload them). A phone with room keeps the models while it waits: on a 12 GB phone, giving them back
   * after 30 s meant a 1.6-14 s reload (and a cold start of the whole stack) every time the owner hopped between apps.
   */
  fun releaseDue(politeForMs: Long, released: Boolean, memoryTight: Boolean, pressure: Boolean = false): Boolean =
    !released && (pressure || (memoryTight && politeForMs >= RELEASE_AFTER_MS))

  /**
   * An onTrimMemory level that means the system is short of memory while we run or sit cached: RUNNING_LOW (10), RUNNING_CRITICAL (15)
   * and the background levels (40 and up). Not RUNNING_MODERATE (5, an early hint) and not UI_HIDDEN (20, which fires whenever our
   * last screen goes away and is not memory pressure).
   */
  fun pressure(trimLevel: Int): Boolean = trimLevel == 10 || trimLevel == 15 || trimLevel >= 40
}
