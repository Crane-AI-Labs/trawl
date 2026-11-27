package com.craneai.findr

import com.craneai.findr.ui.language.AppLocale
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.craneai.findr.privacy.followSecureWindow
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch

/** The only activity. Everything on screen is Compose; this wires the engine, the image loader and the theme. */
class MainActivity : FragmentActivity() {   // FragmentActivity because the phone's unlock prompt (BiometricPrompt) needs one
  private val factory by lazy { AppViewModel.factory(application, (application as FindrApp).engine) }
  private val appViewModel: AppViewModel by viewModels { factory }
  private val privacy by lazy { (application as FindrApp).privacy }

  // Android 12 and older: the app's own language choice (ui/language/AppLocale). From 13 the system applies it.
  override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen().setKeepOnScreenCondition { !(application as FindrApp).isReady }
    super.onCreate(savedInstanceState)
    com.craneai.findr.diag.StartupFrames.note("activity", if (savedInstanceState != null) "restored" else "fresh")
    enableEdgeToEdge()
    // Back on the first screen goes to the background, never ends the app (see RootBack). Registered before any screen's own handler.
    rootBack = RootBack.install(this, intent = { intent }, isTaskRoot = { isTaskRoot }, moveToBack = { moveTaskToBack(true) })

    lifecycleScope.launch {
      (application as FindrApp).awaitEngine()
      if (!isFinishing) {
        showContent()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) appViewModel.refreshAccess()
      }
    }
  }

  private fun showContent() {
    // Thumbnails of videos come from a frame of the file itself. Nothing is downloaded: Findr has no network.
    SingletonImageLoader.setSafe { context ->
      ImageLoader.Builder(context).components {
        add(MediaThumbKeyer())
        add(MediaThumbFetcher.Factory())
        add(VideoFrameDecoder.Factory())
      }.build()
    }

    // Blank this window in recents and block screenshots while it shows sensitive results, or when the person asked for it.
    // Followed from here, not from the composition, so the flag is on before the first frame that shows one (UI audit P10).
    followSecureWindow(privacy.secureWindow)

    setContent {
      val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
      val palette by appViewModel.palette.collectAsStateWithLifecycle()
      val locked by privacy.locked.collectAsStateWithLifecycle()
      val lockOn by privacy.prefs.lockEnabled.collectAsStateWithLifecycle()
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

  private var rootBack: androidx.activity.OnBackPressedCallback? = null

  // singleTask: another start of Trawl (the quick panel's "Open Findr", the icon) arrives here, and decides what Back does from now on.
  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    rootBack?.let { RootBack.refresh(it, isTaskRoot, intent) }
  }

  override fun onResume() {
    super.onResume()
    // The user may have just flipped All files access in system Settings.
    if ((application as FindrApp).isReady) appViewModel.refreshAccess()
  }
}
