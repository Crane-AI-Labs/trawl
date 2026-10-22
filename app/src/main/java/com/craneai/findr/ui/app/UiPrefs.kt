package com.craneai.findr.ui.app

import android.content.Context
import androidx.core.content.edit
import com.craneai.findr.ui.theme.Palette
import com.craneai.findr.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The few things only the UI cares about: has onboarding run, light/dark and colour choice. Not the index. */
class UiPrefs(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("findr_ui", Context.MODE_PRIVATE)

  private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
  val onboardingDone: StateFlow<Boolean> = _onboardingDone

  private val _themeMode = MutableStateFlow(
    runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
  )
  val themeMode: StateFlow<ThemeMode> = _themeMode

  /** Sunlight (the default) or the wallpaper's colours. Kept beside the light/dark choice: both are how the app looks, nothing else. */
  private val _palette = MutableStateFlow(
    runCatching { Palette.valueOf(prefs.getString(KEY_PALETTE, null) ?: "") }.getOrDefault(Palette.SUNLIGHT),
  )
  val palette: StateFlow<Palette> = _palette

  /** The one-time "Trawl blurs previews that may be private" note has been shown (the first time a preview was blurred). */
  var blurHintShown: Boolean
    get() = prefs.getBoolean(KEY_BLUR_HINT, false)
    set(value) = prefs.edit { putBoolean(KEY_BLUR_HINT, value) }

  /**
   * Things asked at the moment they matter instead of during setup (onboarding v2): each is asked or shown at most once and
   * the answer is kept. [firstSearchDone] is set by the first search that found something; the notification question follows it.
   */
  var firstSearchDone: Boolean
    get() = prefs.getBoolean(KEY_FIRST_SEARCH, false)
    set(value) = prefs.edit { putBoolean(KEY_FIRST_SEARCH, value) }

  /** The notification question (after the first search) was answered with Allow or Not now. */
  var notificationsAsked: Boolean
    get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
    set(value) = prefs.edit { putBoolean(KEY_NOTIF_ASKED, value) }

  /** The app-lock offer (the first time a file is marked sensitive) was answered. */
  var lockOffered: Boolean
    get() = prefs.getBoolean(KEY_LOCK_OFFERED, false)
    set(value) = prefs.edit { putBoolean(KEY_LOCK_OFFERED, value) }

  /** Transsion phones: the "This phone pauses apps" card on Home was dismissed with OK or acted on. */
  var pauseCardDismissed: Boolean
    get() = prefs.getBoolean(KEY_PAUSE_CARD, false)
    set(value) = prefs.edit { putBoolean(KEY_PAUSE_CARD, value) }

  /** Samsung phones: the "Samsung may stop this app at night" card on Home was dismissed with OK. */
  var samsungCardDismissed: Boolean
    get() = prefs.getBoolean(KEY_SAMSUNG_CARD, false)
    set(value) = prefs.edit { putBoolean(KEY_SAMSUNG_CARD, value) }

  fun setOnboardingDone(done: Boolean) {
    prefs.edit { putBoolean(KEY_ONBOARDED, done) }
    _onboardingDone.value = done
  }

  fun setThemeMode(mode: ThemeMode) {
    prefs.edit { putString(KEY_THEME, mode.name) }
    _themeMode.value = mode
  }

  fun setPalette(palette: Palette) {
    prefs.edit { putString(KEY_PALETTE, palette.name) }
    _palette.value = palette
  }

  private companion object {
    const val KEY_PALETTE = "palette"
    const val KEY_ONBOARDED = "onboarded"
    const val KEY_THEME = "theme"
    const val KEY_BLUR_HINT = "blur_hint_shown"
    const val KEY_FIRST_SEARCH = "first_search_done"
    const val KEY_NOTIF_ASKED = "notifications_asked"
    const val KEY_LOCK_OFFERED = "lock_offered"
    const val KEY_PAUSE_CARD = "pause_card_dismissed"
    const val KEY_SAMSUNG_CARD = "samsung_card_dismissed"
  }
}
