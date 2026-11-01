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

  /**
   * The system stopped the run for a reason other than the foreground-service limit, and the stop is not one that comes back by
   * itself or that the person asked for. WorkManager re-queues a stopped request, but only under its own constraints and the
   * system's quota and standby rules, and after a process kill nothing is queued at all until the app opens. So these stops also
   * ask for one delayed, constraint-light rerun ([RESUME_DELAY_MIN]): the worker lock makes a duplicate harmless.
   * Not for: a constraint stop (the constraint returning restarts the request), the app's own cancel, the person's stop, or a
   * background restriction the person set (asking again would only be refused).
   */
  fun followUpAfterStop(stopReason: Int): Boolean = when (stopReason) {
    WorkInfo.STOP_REASON_QUOTA, WorkInfo.STOP_REASON_APP_STANDBY, WorkInfo.STOP_REASON_TIMEOUT,
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING, WorkInfo.STOP_REASON_PREEMPT, WorkInfo.STOP_REASON_DEVICE_STATE,
    WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED, WorkInfo.STOP_REASON_UNKNOWN -> true
    else -> false
  }

  /**
   * The charger net ([FindrEngine.keepChargerNet]). Plugging the phone in cannot start an app that is gone: the runtime receiver
   * died with the process and a manifest receiver gets no POWER_CONNECTED (Android 8+). The one thing Android starts for the plug-in
   * is a job that waits for CHARGING, and a run consumes the one enqueueWork asked for. So a run that ends with work left on a phone
   * that is not plugged in puts one back, unless one already waits. Never while charging: a charger-gated job would start at once,
   * and on a polite phone hand itself off again in a loop (the delayed resume covers a phone that is already plugged in).
   */
  fun chargerNetWanted(charging: Boolean, paused: Boolean, workLeft: Boolean, alreadyWaiting: Boolean): Boolean =
    !paused && !charging && workLeft && !alreadyWaiting

  /**
   * The charger net waits this long before it may start. It is armed long before any plug-in, so the delay has passed by then;
   * what it prevents is a hot loop. A run that ends at once (another run holds the lock) puts the net back, and if WorkManager
   * believes the phone is charging while the app sees it unplugged (the two read different battery fields) the new request would
   * start immediately, end immediately and be put back again: measured on a TECNO KN4 as 6 runs a second until the process died.
   */
  const val NET_ARM_DELAY_S = 20L

  /** The screen went off: the polite wait that ended a run is over, so the run it handed off is wanted now. */
  fun resumeOnScreenOff(handOffPending: Boolean): Boolean = handOffPending

  /** `ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED`: a process nobody is using, and the first the system kills. */
  const val IMPORTANCE_CACHED = 400

  /**
   * Heavy work needs protection. Android checks every cached process (importance 400 and up, the "empty" process of an app nobody has
   * open) every 5 minutes and kills one that used more than 25% of a core in the window, falling to 2% once it has been cached for
   * 15 minutes ("excessive cpu ... limit=25", exit reason 9 / subreason 7; the research notes). A foreground service lifts the process
   * out of that class (importance 125), and so does a running JobScheduler job (the process is bound while the job runs). A worker
   * that WorkManager's own scheduler started inside an already cached process has neither: the system's job never ran, no service
   * could start from the background, and the process is still cached. That run must not burn the CPU: it ends, and a protected start
   * (an expedited request, the charger request, the app opening) does the work.
   */
  fun unprotected(importance: Int, holdsForegroundService: Boolean): Boolean = !holdsForegroundService && importance >= IMPORTANCE_CACHED

  /** `IMPORTANCE_VISIBLE`: the lowest importance a bound service or a running job gives the process (a screen on show is 100). */
  const val IMPORTANCE_VISIBLE = 200

  /** The longest a run that lost the worker lock keeps its job alive for the run that holds it. Under the 10-minute job limit, so the job ends in an orderly way. */
  const val LEND_WAIT_MS = 8 * 60_000L

  /**
   * A run that finds another run holding the worker lock normally ends at once. But when it is itself what keeps the process out of the
   * cached class (a started job: importance 200 to 399, no foreground service of its own), ending hands the process back to the
   * cached class and the run that won the lock, which WorkManager started in-process and which could get no foreground service,
   * is then "not protected" and stops after a few seconds (measured on a TECNO KN4: three runs begin together after a plug-in, two
   * lose the lock and end their jobs within 4 s). So that run waits, doing nothing, for the lock, and the job it lives in protects
   * the run that holds it. Not when it holds a foreground service or has a screen on show (already protected), and not when cached
   * (nothing protects the wait either).
   */
  fun lendsProtection(importance: Int, holdsForegroundService: Boolean): Boolean =
    !holdsForegroundService && importance >= IMPORTANCE_VISIBLE && importance < IMPORTANCE_CACHED
}

/**
 * [RunHandoff.unprotected], read at file boundaries. The importance is one cheap call to the system, still asked at most once per
 * [everyMs] (the loops look between files, and a file can take a few hundred milliseconds). A run that holds a foreground service never asks.
 */
class ProtectionGate(private val importance: () -> Int, private val now: () -> Long, private val everyMs: Long = 2_000L) {
  private var askedAt = 0L
  private var asked = false
  private var unprotected = false

  fun lost(holdsForegroundService: Boolean): Boolean {
    if (holdsForegroundService) return false
    val t = now()
    if (!asked || t - askedAt >= everyMs) {
      asked = true
      askedAt = t
      unprotected = RunHandoff.unprotected(importance(), holdsForegroundService = false)
    }
    return unprotected
  }
}
