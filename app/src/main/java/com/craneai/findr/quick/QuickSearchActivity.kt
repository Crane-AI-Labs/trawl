package com.craneai.findr.quick

import com.craneai.findr.ui.language.AppLocale
import android.content.Context
import com.craneai.findr.ui.common.appString
import android.app.KeyguardManager
import android.app.StatusBarManager
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.craneai.findr.ui.privacy.LocalPrivacy
import com.craneai.findr.ui.privacy.SensitiveLeaveDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.craneai.findr.FindrApp
import com.craneai.findr.MainActivity
import com.craneai.findr.R
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackState
import com.craneai.findr.ui.common.FileActions
import com.craneai.findr.ui.common.LocalPreviewBlur
import com.craneai.findr.ui.common.PreviewBlur
import com.craneai.findr.ui.common.blurHintOnce
import com.craneai.findr.ui.common.OpenResult
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.common.MediaThumbFetcher
import com.craneai.findr.ui.common.MediaThumbKeyer
import com.craneai.findr.ui.theme.FindrTheme

/**
 * Quick search: a translucent panel over whatever app is in front, opened from the Quick Settings tile, the launcher
 * shortcut or the home-screen widget. It has its own task (affinity `.quick`), stays out of Recents, and closes on Back or
 * a tap outside the card, so the app behind it is where the person lands again.
 *
 * It is a window, not an overlay: no SYSTEM_ALERT_WINDOW, no service. It exists only while it is on screen.
 */
class QuickSearchActivity : FragmentActivity() {   // FragmentActivity: the phone's unlock prompt (BiometricPrompt) needs one
  private val engine get() = (application as FindrApp).engine
  private val viewModel: QuickSearchViewModel by viewModels { QuickSearchViewModel.factory(engine) }
  private val prefs by lazy { QuickPrefs(this) }
  private val created = SystemClock.uptimeMillis()

  /** Bumped when the panel is opened again while it is already open, so the box is cleared and focused afresh. */
  private var reopen by mutableIntStateOf(0)
  private var offerShown by mutableStateOf(false)
  private var cameFromTile = false
  private val privacy get() = (application as FindrApp).privacy

  /** The phone is locked (a held phone): the panel shows nothing. Read again each time the panel comes to the front. */
  private var deviceLocked by mutableStateOf(true)

  /** A sensitive file the person asked to Share or Copy, waiting for the plain confirmation. Second is true for Copy. */
  private var leaving by mutableStateOf<Pair<IndexedFile, Boolean>?>(null)
  private var tapUptime = 0L

  // Android 12 and older: the app's own language choice (ui/language/AppLocale). From 13 the system applies it.
  override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    readIntent(intent)
    refreshDeviceLocked()
    enableEdgeToEdge()
    installImageLoader()
    logStartup()

    val ui = UiPrefs(this)
    setContent {
      FindrTheme(mode = ui.themeMode.value, palette = ui.palette.value) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val text = rememberTextFieldState()
        val blur = remember {
          PreviewBlur(
            // Blur needs the "Blur private-looking previews" add-on: without its pack nothing carries a score, so nothing is blurred.
            enabled = prefs.blurPrivate && engine.settings.value.privateScoring &&
              engine.packs.states.value[PackCatalog.PRIVACY] is PackState.Installed,
            // Showing a blurred picture needs the phone unlocked: the panel can be opened from a lock screen.
            allowReveal = { !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked },
            onFirstBlur = blurHintOnce(this@QuickSearchActivity),
            onDenied = { Toast.makeText(this@QuickSearchActivity, R.string.quick_unlock_to_show, Toast.LENGTH_SHORT).show() },
          )
        }
        val appLocked by privacy.locked.collectAsStateWithLifecycle()
        val secure by privacy.secureWindow.collectAsStateWithLifecycle()
        val mode = quickMode(deviceLocked, appLocked)
        // Locked: stop searching and drop what was on screen, before anything can be drawn.
        LaunchedEffect(mode) { viewModel.setBlocked(mode != QuickMode.SEARCH) }
        // No screenshots and a blank card in recents while a sensitive result is on screen, or when the person asked for it.
        LaunchedEffect(secure) {
          if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val showing = mode == QuickMode.SEARCH && state.showsSensitive
        LaunchedEffect(showing) { privacy.setSensitiveOnScreen("quick", showing) }
        DisposableEffect(Unit) { onDispose { privacy.setSensitiveOnScreen("quick", false) } }
        // With the lock on, ask for the unlock by itself each time the panel comes to the front.
        val owner = LocalLifecycleOwner.current
        val promptTitle = appString(R.string.lock_prompt_title)
        val promptSubtitle = stringResource(R.string.lock_prompt_subtitle)
        LaunchedEffect(mode, owner) {
          if (mode != QuickMode.APP_LOCKED) return@LaunchedEffect
          owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (privacy.shouldAutoPrompt()) privacy.requestUnlock(this@QuickSearchActivity, promptTitle, promptSubtitle)
          }
        }
        CompositionLocalProvider(LocalPrivacy provides privacy, LocalPreviewBlur provides blur) {
        QuickSearchPanel(
          state = state,
          mode = mode,
          text = text,
          reopen = reopen,
          offerTile = offerShown,
          onQuery = viewModel::setQuery,
          onFocused = viewModel::prewarm,
          onTyping = viewModel::setInteracting,
          onHitsDrawn = viewModel::hitsDrawn,
          onResultsShown = ::maybeOffer,
          onClose = ::finish,
          // A sensitive item leaves the phone (Share) or goes on the clipboard (Copy) only after a plain confirmation.
          onShare = { if (it.sensitive) leaving = it to false else act(it, QuickActions.share(this, it), closeAfter = true) },
          onCopy = { if (it.sensitive) leaving = it to true else act(it, QuickActions.copy(this, it), closeAfter = true) },
          onOpen = { act(it, QuickActions.open(this, it), closeAfter = true) },
          onShowFolder = { actionFor(it, FileActions.showInFolder(this, it)) },
          onMarkSensitive = { file, on ->
            viewModel.markSensitive(file, on)
            Toast.makeText(this, if (on) R.string.snack_marked_sensitive else R.string.snack_unmarked_sensitive, Toast.LENGTH_SHORT).show()
          },
          onUnlock = { privacy.requestUnlock(this, promptTitle, promptSubtitle) },
          onOpenFindr = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
          },
          onAddTile = ::requestTile,
          onDeclineTile = { prefs.tileOffer = TileOfferState.DECLINED; offerShown = false },
        )
        leaving?.let { (file, copy) ->
          SensitiveLeaveDialog(
            copy = copy,
            onConfirm = {
              leaving = null
              act(file, if (copy) QuickActions.copy(this@QuickSearchActivity, file) else QuickActions.share(this@QuickSearchActivity, file), closeAfter = true)
            },
            onDismiss = { leaving = null },
          )
        }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    readIntent(intent)
    viewModel.setQuery("")
    reopen++
  }

  override fun onStart() {
    super.onStart()
    refreshDeviceLocked()
  }

  override fun onResume() {
    super.onResume()
    refreshDeviceLocked()
  }

  private fun refreshDeviceLocked() {
    deviceLocked = getSystemService(KeyguardManager::class.java).isDeviceLocked
  }

  override fun onStop() {
    super.onStop()
    viewModel.setInteracting(false)
    leaving = null
    // A panel that lost the screen (Home, a call, the chooser) is finished: the next tap should open a fresh one.
    // The phone's own unlock prompt is the one exception: it must be able to answer.
    if (!isChangingConfigurations && !privacy.authInFlight()) finish()
  }

  private fun readIntent(i: Intent) {
    cameFromTile = i.getStringExtra(EXTRA_SOURCE) == SOURCE_TILE
    tapUptime = i.getLongExtra(EXTRA_TAP_UPTIME, 0L)
    // Someone who got here through the tile already has it; never offer it to them.
    if (cameFromTile && prefs.tileOffer == TileOfferState.NEW) prefs.tileOffer = TileOfferState.ADDED
  }

  private fun actionFor(file: IndexedFile, result: OpenResult) = act(
    file,
    when (result) { OpenResult.OPENED, OpenResult.COPIED -> ActionResult.DONE; OpenResult.NO_APP -> ActionResult.NO_APP; OpenResult.SAMPLE -> ActionResult.FAILED },
    closeAfter = true,
  )

  private fun act(file: IndexedFile, result: ActionResult, closeAfter: Boolean) {
    when (result) {
      ActionResult.DONE -> Unit
      ActionResult.NO_APP -> Toast.makeText(this, R.string.quick_no_app, Toast.LENGTH_SHORT).show()
      ActionResult.FAILED -> Toast.makeText(this, R.string.quick_copy_failed, Toast.LENGTH_SHORT).show()
    }
    Log.i(TAG, "action file=${redact(file.name)} result=$result")
    if (result == ActionResult.DONE && closeAfter) finish()
  }

  /** Called after a search finished: the first one that found something is when the tile is offered, once. */
  private fun maybeOffer(hits: Int) {
    if (offerShown || !TileOffer.shouldOffer(prefs.tileOffer, Build.VERSION.SDK_INT, hits, cameFromTile)) return
    prefs.tileOffer = TileOfferState.OFFERED // asked once, whatever the answer
    offerShown = true
  }

  private fun requestTile() {
    offerShown = false
    if (Build.VERSION.SDK_INT < TileOffer.MIN_SDK) return
    val bar = getSystemService(StatusBarManager::class.java)
    bar.requestAddTileService(
      android.content.ComponentName(this, QuickSettingsTile::class.java),
      getString(R.string.quick_name),
      android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_quick_search),
      mainExecutor,
    ) { result ->
      prefs.tileOffer = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) TileOfferState.DECLINED else TileOfferState.ADDED
      Log.i(TAG, "requestAddTileService result=$result")
    }
  }

  /** Same loader as the main screen: thumbnails of videos come from a frame of the file itself. No network. */
  private fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
      ImageLoader.Builder(context).components {
        add(MediaThumbKeyer())
        add(MediaThumbFetcher.Factory())
        add(VideoFrameDecoder.Factory())
      }.build()
    }
  }

  /** Logcat lines the timing runs read: how old the process is (cold or warm), first frame, keyboard up. Tag QuickPerf. */
  private fun logStartup() {
    val processAge = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    Log.i(PERF, "panel_create process_age_ms=$processAge source=${if (cameFromTile) SOURCE_TILE else "other"}")
    val decor = window.decorView
    decor.viewTreeObserver.registerFrameCommitCallback {
      val now = SystemClock.uptimeMillis()
      val sinceTap = if (tapUptime > 0) " tap_to_first_frame_ms=${now - tapUptime}" else ""
      Log.i(PERF, "panel_first_frame create_to_frame_ms=${now - created}$sinceTap")
    }
    var imeLogged = false
    ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
      if (!imeLogged && insets.isVisible(WindowInsetsCompat.Type.ime())) {
        imeLogged = true
        val now = SystemClock.uptimeMillis()
        val sinceTap = if (tapUptime > 0) " tap_to_keyboard_ms=${now - tapUptime}" else ""
        Log.i(PERF, "panel_keyboard create_to_keyboard_ms=${now - created}$sinceTap")
      }
      ViewCompat.onApplyWindowInsets(v, insets)
    }
  }

  companion object {
    const val EXTRA_SOURCE = "source"
    const val EXTRA_TAP_UPTIME = "tap_uptime_ms"
    const val SOURCE_TILE = "tile"
    const val SOURCE_WIDGET = "widget"
    const val SOURCE_SHORTCUT = "shortcut"
    private const val TAG = "QuickSearch"
    private const val PERF = "QuickPerf"

    /** The one way in for tile and widget: a new task of our own, one panel at a time. */
    fun intent(context: android.content.Context, source: String): Intent =
      Intent(context, QuickSearchActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_SOURCE, source)
  }
}
