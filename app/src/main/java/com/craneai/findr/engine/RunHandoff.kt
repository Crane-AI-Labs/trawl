package com.craneai.findr.engine

import androidx.work.WorkInfo

/**
 * When the index worker lets go of its foreground service, and how it is asked back. Pure, so the rules are unit-tested.
 *
 * Android 15 allows a data-sync foreground service 6 hours in any 24, and the clock runs while the service is merely alive. Two
 * things used to spend it for nothing (the research notes): polite mode (the person is in another app) held the
 * service in a 1 s poll loop, and after the limit the system stopped the worker and nothing started it again until the app was
 * opened. So: a polite wait that outlasts a glance (5 min) ends the run (the service goes with it) and a short-lived resume request
 * brings it back, and a run the system stopped for the limit asks to be run again as plain work.
 */
object RunHandoff {
  /**
   * Polite for this long and the run ends instead of waiting on. Longer than [PolitePolicy.RELEASE_AFTER_MS] on purpose: a run that
   * ends loses the process to Transsion's Hiber (TECNO, Infinix, itel), so nothing brings it back at screen-off; a glance of a few
   * minutes must stay inside one run, while a long session must not burn the 6 h budget (which also resets when Trawl opens).
   */
  const val HAND_OFF_AFTER_MS = 5 * 60_000L

  /**
   * Safety net for the resume request, in case the screen-off signal never comes (the process was killed, or the phone
   * stays in someone's hand): a handed-off run that finds the phone still polite ends again at once, with no service and no
   * notification, so a look every few minutes costs next to nothing.
   */
  const val RESUME_DELAY_MIN = 10L

  /** The person has been in another app long enough that the worker should leave. A notification waking the screen for a few seconds does not. */
  fun handOffDue(politeForMs: Long): Boolean = politeForMs >= HAND_OFF_AFTER_MS

  /** A run that starts while the phone is polite has nothing to do yet: it must not become a foreground service for nothing. */
  fun startsWithoutService(polite: Boolean): Boolean = polite

  /**
   * The system ended this run because the app's foreground-service budget ran out (Android 15's data-sync limit). Nothing else
   * restarts it until the person opens the app, so the run asks to be started again, as plain work (the service cannot start now).
   */
  fun resumeAfterStop(stopReason: Int): Boolean = stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT

  /** The screen went off: the polite wait that ended a run is over, so the run it handed off is wanted now. */
  fun resumeOnScreenOff(handOffPending: Boolean): Boolean = handOffPending
}
