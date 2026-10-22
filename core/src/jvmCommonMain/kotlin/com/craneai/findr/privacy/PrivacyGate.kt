package com.craneai.findr.privacy

/**
 * The rules for when Findr asks for the phone's unlock again, with no Android in it so they can be tested on a laptop.
 * The controller ([PrivacyController]) feeds it what happens (app to the background, screen off, a successful unlock)
 * with a monotonic clock, and reads back two answers: is the app locked, and are sensitive results revealed.
 *
 * Two separate things, on purpose:
 *  - The **app lock** (optional) covers the whole app. It closes on a cold start, when the screen goes off, and when the
 *    app has been in the background for [Config.lockAfterMs].
 *  - The **reveal** covers only results the person marked sensitive. It opens after the phone's own credential, and closes
 *    when the screen goes off or the app has been away for [Config.revealGraceMs]. It works with or without the app lock.
 *
 * Everything fails closed: a new process starts locked (if the lock is on) and hidden.
 */
class PrivacyGate(config: Config = Config()) {

  /**
   * @param lockEnabled the optional app lock
   * @param lockAfterMs how long the app may sit in the background before it asks again; 0 asks every time
   * @param revealGraceMs how long revealed results stay shown after leaving the app
   */
  data class Config(
    val lockEnabled: Boolean = false,
    val lockAfterMs: Long = DEFAULT_LOCK_AFTER_MS,
    val revealGraceMs: Long = DEFAULT_REVEAL_GRACE_MS,
  )

  var config: Config = config
    private set

  private var locked = config.lockEnabled
  private var revealed = false

  /** When the app last went fully to the background, or null while it is in the foreground. */
  private var backgroundedAt: Long? = null

  /** A system prompt is on screen; no second one is started over it. */
  var authInFlight = false
    private set

  val isLocked: Boolean get() = config.lockEnabled && locked
  val isRevealed: Boolean get() = revealed

  /** Settings changed. Turning the lock on while using the app does not lock you out of what you are looking at. */
  fun configure(new: Config) {
    val wasOn = config.lockEnabled
    config = new
    if (!new.lockEnabled) locked = false
    else if (!wasOn) locked = false   // just enabled by someone who authenticated to do it
  }

  /** The first activity of the process started. A new process always begins locked and hidden. */
  fun onProcessStart() {
    locked = config.lockEnabled
    revealed = false
    backgroundedAt = null
    authInFlight = false
  }

  /** The app (any of its screens) came to the front. [now] is a monotonic clock in milliseconds. */
  fun onForeground(now: Long) {
    val since = backgroundedAt
    backgroundedAt = null
    if (since == null) return
    val away = now - since
    if (config.lockEnabled && away >= config.lockAfterMs) locked = true
    if (away >= config.revealGraceMs) revealed = false
  }

  /** The last of the app's screens went out of view. */
  fun onBackground(now: Long) {
    if (backgroundedAt == null) backgroundedAt = now
  }

  /** The screen turned off: lock and hide at once, whatever the timeout says. */
  fun onScreenOff() {
    if (config.lockEnabled) locked = true
    revealed = false
  }

  /** The reveal grace ran out while the app was still away. */
  fun onRevealGraceElapsed(now: Long) {
    val since = backgroundedAt ?: return
    if (now - since >= config.revealGraceMs) revealed = false
  }

  /** Asks to start a prompt. False when one is already showing. */
  fun beginAuth(): Boolean {
    if (authInFlight) return false
    authInFlight = true
    return true
  }

  fun endAuth() {
    authInFlight = false
  }

  /** The phone's credential accepted the unlock. */
  fun onUnlocked() {
    locked = false
    authInFlight = false
  }

  /** The phone's credential accepted a reveal. */
  fun onRevealedByUser() {
    revealed = true
    authInFlight = false
  }

  /** Put sensitive results away again (the person pressed "Hide"). */
  fun hide() {
    revealed = false
  }

  companion object {
    const val DEFAULT_LOCK_AFTER_MS = 5 * 60_000L
    const val DEFAULT_REVEAL_GRACE_MS = 60_000L
  }
}
