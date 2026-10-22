package com.craneai.findr.ui.app

import com.craneai.findr.ui.theme.Palette
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.ui.language.AppLocale
import com.craneai.findr.core.SpeechDefault
import com.craneai.findr.ui.addons.AddOnsViewModel
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.search.SearchViewModel
import com.craneai.findr.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What Findr is currently allowed to do. Re-read whenever the app returns to the foreground. */
data class AccessState(val allFiles: Boolean, val notifications: Boolean)

class AppViewModel(app: Application, private val engine: SearchEngine) : AndroidViewModel(app) {
  private val prefs = UiPrefs(app)

  val onboardingDone: StateFlow<Boolean> = prefs.onboardingDone
  val themeMode: StateFlow<ThemeMode> = prefs.themeMode
  val palette: StateFlow<Palette> = prefs.palette

  private val _access = MutableStateFlow(readAccess())
  val access: StateFlow<AccessState> = _access

  private var indexingStarted = false

  init {
    startIndexingIfAllowed()
  }

  /** Called from the activity's onResume: the user may have flipped a switch in system Settings. */
  fun refreshAccess() {
    val before = _access.value.allFiles
    _access.value = readAccess()
    if (!before && _access.value.allFiles) engine.filesAccessGranted()
    startIndexingIfAllowed()
  }

  fun completeOnboarding() {
    prefs.setOnboardingDone(true)
    // D26: speech to text starts on only when the chosen language is English (SpeechDefault); set before reading starts.
    val language = AppLocale.effective(getApplication()).tag
    viewModelScope.launch {
      engine.updateSettings { it.copy(speechToText = SpeechDefault.atFirstRun(language, it.speechToText)) }
      startIndexingIfAllowed()
    }
  }

  fun setThemeMode(mode: ThemeMode) = prefs.setThemeMode(mode)

  fun setPalette(palette: Palette) = prefs.setPalette(palette)

  /** Reading starts once, as soon as there is something to read. The engine treats repeat calls as a no-op. */
  private fun startIndexingIfAllowed() {
    if (indexingStarted) return
    if (prefs.onboardingDone.value && _access.value.allFiles) {
      indexingStarted = true
      engine.startIndexing()
    }
  }

  private fun readAccess(): AccessState {
    val app = getApplication<Application>()
    return AccessState(
      allFiles = Environment.isExternalStorageManager(),
      notifications = NotificationManagerCompat.from(app).areNotificationsEnabled(),
    )
  }

  companion object {
    /** One factory for every view model, so screens just ask for the class they want. */
    fun factory(app: Application, engine: SearchEngine): ViewModelProvider.Factory = viewModelFactory {
      initializer { AppViewModel(app, engine) }
      initializer { SearchViewModel(engine) }
      initializer { IndexViewModel(engine) }
      initializer { AddOnsViewModel(engine) }
      initializer { DeliveryViewModel(engine) }
    }

    /** Intent for the "All files access" switch for this app, with the generic list as a fallback. */
    fun allFilesIntent(packageName: String): Intent =
      Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri())

    fun allFilesFallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    fun notificationsNeedRuntimePrompt(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS" // API 33; only requested there
  }
}
