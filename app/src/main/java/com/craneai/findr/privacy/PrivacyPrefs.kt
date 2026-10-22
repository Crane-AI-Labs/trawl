package com.craneai.findr.privacy

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-lock and screen-privacy switches. They sit apart from the engine's settings on purpose: they are about who is
 * holding the phone, not about what is indexed, and the lock has to be readable the moment the process starts.
 */
class PrivacyPrefs(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("findr_privacy", Context.MODE_PRIVATE)

  private val _lockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK, false))
  /** Ask for the phone's unlock before showing anything. Off by default; offered in onboarding and Settings. */
  val lockEnabled: StateFlow<Boolean> = _lockEnabled

  private val _lockAfterMinutes = MutableStateFlow(prefs.getInt(KEY_AFTER, DEFAULT_LOCK_AFTER_MINUTES))
  /** How long Findr may sit in the background before it asks again; 0 asks every time. */
  val lockAfterMinutes: StateFlow<Int> = _lockAfterMinutes

  private val _hideInRecents = MutableStateFlow(prefs.getBoolean(KEY_RECENTS, false))
  /** Blank Findr's card in the recent-apps list (and block screenshots of it). Off by default. */
  val hideInRecents: StateFlow<Boolean> = _hideInRecents

  fun setLockEnabled(on: Boolean) {
    prefs.edit { putBoolean(KEY_LOCK, on) }
    _lockEnabled.value = on
  }

  fun setLockAfterMinutes(minutes: Int) {
    prefs.edit { putInt(KEY_AFTER, minutes) }
    _lockAfterMinutes.value = minutes
  }

  fun setHideInRecents(on: Boolean) {
    prefs.edit { putBoolean(KEY_RECENTS, on) }
    _hideInRecents.value = on
  }

  companion object {
    const val DEFAULT_LOCK_AFTER_MINUTES = 5
    /** The choices offered in Settings, in minutes; 0 means every time Findr is opened. */
    val LOCK_AFTER_CHOICES = listOf(0, 1, 5, 15, 30)
    private const val KEY_LOCK = "lock_enabled"
    private const val KEY_AFTER = "lock_after_minutes"
    private const val KEY_RECENTS = "hide_in_recents"
  }
}
