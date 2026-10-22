package com.craneai.findr

import com.craneai.findr.ui.language.AppLocale
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.craneai.findr.ui.FindrRoot
import com.craneai.findr.ui.app.AppViewModel
import com.craneai.findr.ui.common.MediaThumbFetcher
import com.craneai.findr.ui.common.MediaThumbKeyer
import com.craneai.findr.ui.privacy.LocalPrivacy
import com.craneai.findr.ui.privacy.LockScreen
import com.craneai.findr.ui.theme.FindrTheme

/** The only activity. Everything on screen is Compose; this wires the engine, the image loader and the theme. */
class MainActivity : FragmentActivity() {   // FragmentActivity because the phone's unlock prompt (BiometricPrompt) needs one
  private val factory by lazy { AppViewModel.factory(application, (application as FindrApp).engine) }
  private val appViewModel: AppViewModel by viewModels { factory }
  private val privacy by lazy { (application as FindrApp).privacy }

  // Android 12 and older: the app's own language choice (ui/language/AppLocale). From 13 the system applies it.
  override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Thumbnails of videos come from a frame of the file itself. Nothing is downloaded: Findr has no network.
    SingletonImageLoader.setSafe { context ->
      ImageLoader.Builder(context).components {
        add(MediaThumbKeyer())
        add(MediaThumbFetcher.Factory())
        add(VideoFrameDecoder.Factory())
      }.build()
    }

    setContent {
      val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
      val palette by appViewModel.palette.collectAsStateWithLifecycle()
      val locked by privacy.locked.collectAsStateWithLifecycle()
      val secure by privacy.secureWindow.collectAsStateWithLifecycle()
      val lockOn by privacy.prefs.lockEnabled.collectAsStateWithLifecycle()
      // Blank this window in recents and block screenshots while it shows sensitive results, or when the person asked for it.
      LaunchedEffect(secure) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
      }
      // With the lock on, the recents card must not be a screenshot of the last results (API 33+; older phones need the switch).
      LaunchedEffect(lockOn) {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!lockOn)
      }
      FindrTheme(mode = themeMode, palette = palette) {
        CompositionLocalProvider(LocalPrivacy provides privacy) {
          // While locked nothing of the app is composed: no results, no file names, no thumbnails.
          if (locked) LockScreen(privacy) else FindrRoot(appViewModel = appViewModel, factory = factory)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // The user may have just flipped All files access in system Settings.
    appViewModel.refreshAccess()
  }
}
