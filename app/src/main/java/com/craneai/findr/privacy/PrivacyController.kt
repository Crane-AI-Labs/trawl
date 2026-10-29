package com.craneai.findr.privacy

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.ui.common.VideoStripLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one place that ties the lock rules ([PrivacyGate]) to Android: which screens are in front, the screen turning off,
 * and the phone's unlock prompt ([DeviceAuth]). One per process, shared by the main screen and the quick-search panel, so
 * unlocking in one unlocks the other and they re-lock together.
 *
 * Everything here runs on the main thread.
 */
class PrivacyController(private val app: Application, private val engine: SearchEngine) {
  val prefs = PrivacyPrefs(app)
  private val gate = PrivacyGate(configOf())
  private val main = Handler(Looper.getMainLooper())

  private val _locked = MutableStateFlow(gate.isLocked)
  /** The app lock is closed: show only the lock screen, no results. */
  val locked: StateFlow<Boolean> = _locked

  private val _revealed = MutableStateFlow(false)
  /** Sensitive results are shown (the person unlocked them). Mirrored into the engine, which does the hiding. */
  val revealed: StateFlow<Boolean> = _revealed

  private val _secureWindow = MutableStateFlow(prefs.hideInRecents.value)
  /** Windows should be FLAG_SECURE: the person asked to hide Findr in recents, or sensitive results are on screen. */
  val secureWindow: StateFlow<Boolean> = _secureWindow

  /** Which screens are showing a sensitive result right now (a set, so one screen clearing does not clear the other). */
  private val sensitiveOn = HashSet<String>()

  private var started = 0
  private var stoppedAt = 0L
  private var backgroundCommitted = false
  private var promptDismissed = false

  private val commitBackground = Runnable {
    if (started != 0) return@Runnable
    gate.onBackground(stoppedAt)
    backgroundCommitted = true
    // The reveal ends on its own after the grace period, even if the app is never opened again.
    main.postDelayed(graceElapsed, gate.config.revealGraceMs)
  }
  private val graceElapsed = Runnable {
    gate.onRevealGraceElapsed(SystemClock.elapsedRealtime())
    publish()
  }

  /** Call once, from Application.onCreate. */
  fun start() {
    gate.onProcessStart()
    publish()
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityStarted(activity: Activity) {
        started++
        if (started != 1) return
        main.removeCallbacks(commitBackground)
        main.removeCallbacks(graceElapsed)
        if (backgroundCommitted) {   // a rotation or a hop between our own screens never gets here
          backgroundCommitted = false
          gate.onForeground(SystemClock.elapsedRealtime())
          publish()
        }
      }

      override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
        if (started != 0) return
        stoppedAt = SystemClock.elapsedRealtime()
        promptDismissed = false
        main.postDelayed(commitBackground, SETTLE_MS)
      }

      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityResumed(activity: Activity) {}
      override fun onActivityPaused(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
    ContextCompat.registerReceiver(
      app,
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          gate.onScreenOff()
          publish()
        }
      },
      IntentFilter(Intent.ACTION_SCREEN_OFF),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
  }

  // ---- what the screens call -----------------------------------------------------------------------------------

  /** The phone has a screen lock (or an enrolled biometric) for the prompt to check. The app lock can only be turned on when true. */
  fun deviceLockAvailable(): Boolean = DeviceAuth.available(app)

  /** KeyguardManager.isDeviceSecure: the phone has a PIN, pattern or password. */
  fun deviceSecure(): Boolean = DeviceAuth.deviceSecure(app)

  /** A phone prompt is up right now, so a screen that loses focus because of it should not close itself. */
  fun authInFlight(): Boolean = gate.authInFlight

  /** The lock screen may start the prompt by itself, unless the person just dismissed it. */
  fun shouldAutoPrompt(): Boolean = gate.isLocked && !gate.authInFlight && !promptDismissed

  /** Unlock the app with the phone's credential. No-op when not locked or a prompt is already up. */
  fun requestUnlock(activity: FragmentActivity, title: String, subtitle: String?) {
    if (!gate.isLocked || !gate.beginAuth()) return
    if (!deviceLockAvailable()) {   // the phone has no screen lock any more: there is nothing to check, and no one to lock out
      gate.onUnlocked()
      publish()
      return
    }
    DeviceAuth.authenticate(activity, title, subtitle) { outcome, byUser ->
      if (outcome == DeviceAuth.Outcome.SUCCESS) gate.onUnlocked() else gate.endAuth()
      if (byUser) promptDismissed = true
      publish()
    }
  }

  /**
   * Show sensitive results: needs the phone's credential unless they are already shown. [onDone] gets true when they are.
   * A phone with no screen lock reveals straight away (there is nothing to check).
   */
  fun requestReveal(activity: FragmentActivity, title: String, subtitle: String?, onDone: (Boolean) -> Unit = {}) {
    if (gate.isRevealed) return onDone(true)
    if (!deviceLockAvailable()) {
      gate.onRevealedByUser()
      publish()
      return onDone(true)
    }
    authenticateFresh(activity, title, subtitle) { ok ->
      if (ok) {
        gate.onRevealedByUser()
        publish()
      }
      onDone(ok)
    }
  }

  /** Put sensitive results away again. */
  fun hideSensitive() {
    gate.hide()
    VideoStripLoader.clear()   // pictures of a put-away sensitive video leave memory too
    publish()
  }

  /** Turn the app lock on or off. Both ask for the phone's credential first: turning it on proves it works, turning it off proves it is you. */
  fun setLockEnabled(activity: FragmentActivity, on: Boolean, title: String, subtitle: String?, onDone: (Boolean) -> Unit = {}) {
    if (on && !deviceLockAvailable()) return onDone(false)
    if (!on && !prefs.lockEnabled.value) return onDone(true)
    authenticateFresh(activity, title, subtitle) { ok ->
      if (ok) {
        prefs.setLockEnabled(on)
        gate.configure(configOf())
        publish()
      }
      onDone(ok)
    }
  }

  fun setLockAfterMinutes(minutes: Int) {
    prefs.setLockAfterMinutes(minutes)
    gate.configure(configOf())
    publish()
  }

  fun setHideInRecents(on: Boolean) {
    prefs.setHideInRecents(on)
    publish()
  }

  /** A screen tells us whether it is showing a sensitive result ([owner] names the screen). Turns FLAG_SECURE on and off. */
  fun setSensitiveOnScreen(owner: String, showing: Boolean) {
    if (if (showing) sensitiveOn.add(owner) else sensitiveOn.remove(owner)) publish()
  }

  // ---- internals -------------------------------------------------------------------------------------------------

  private fun authenticateFresh(activity: FragmentActivity, title: String, subtitle: String?, onDone: (Boolean) -> Unit) {
    if (!gate.beginAuth()) return onDone(false)
    DeviceAuth.authenticate(activity, title, subtitle) { outcome, _ ->
      gate.endAuth()
      onDone(outcome == DeviceAuth.Outcome.SUCCESS)
    }
  }

  private fun configOf() = PrivacyGate.Config(
    lockEnabled = prefs.lockEnabled.value,
    lockAfterMs = prefs.lockAfterMinutes.value * 60_000L,
  )

  private fun publish() {
    // The lock just closed: the queries typed while it was open go with it.
    if (gate.isLocked && !_locked.value) { engine.clearQueryCache(); VideoStripLoader.clear() }   // and the video strips' pictures
    _locked.value = gate.isLocked
    _revealed.value = gate.isRevealed
    _secureWindow.value = prefs.hideInRecents.value || sensitiveOn.isNotEmpty()
    // The engine does the hiding, so the quick panel, the home list and the next search all agree.
    engine.setSensitiveRevealed(gate.isRevealed)
  }

  private companion object {
    /** A rotation stops one activity and starts the next within a moment; only a gap longer than this is "left the app". */
    const val SETTLE_MS = 400L
  }
}
