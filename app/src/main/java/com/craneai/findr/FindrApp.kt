package com.craneai.findr

import com.craneai.findr.ui.language.AppLocale
import android.content.Context
import android.app.Application
import com.craneai.findr.core.AndroidLogSink
import com.craneai.findr.core.BuildFlags
import com.craneai.findr.core.LogSinks
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.privacy.PrivacyController

/**
 * Application root and the one place that decides which SearchEngine the app uses: the real one (engine/), shared
 * with the background worker. The stand-in with sample data (`ui/fake`) stays for UI work and previews.
 */
class FindrApp : Application() {
  lateinit var engine: SearchEngine
    private set

  /** The app lock, the sensitive-result reveal and the window privacy; one per process, shared by every screen. */
  lateinit var privacy: PrivacyController
    private set

  // The shared code in :core reads its build switches from BuildFlags; fill them from this build's BuildConfig before anything else runs
  // (a constructor runs before every ContentProvider, Service, Receiver and Activity of the process).
  init {
    LogSinks.sink = AndroidLogSink
    BuildFlags.logDetail = BuildConfig.LOG_DETAIL
    BuildFlags.debug = BuildConfig.DEBUG
    BuildFlags.finetuned = BuildConfig.FINDR_FINETUNED
    BuildFlags.go = BuildConfig.EDITION_GO
  }

  // Android 12 and older: the app's own language choice (ui/language/AppLocale). From 13 the system applies it.
  override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

  override fun onCreate() {
    super.onCreate()
    com.craneai.findr.diag.HeapProbe.start(BuildConfig.FINDR_HEAPLOG, BuildConfig.HEAP_BALLAST_MB)   // test builds only; a no-op otherwise
    // First, so a crash while the engine starts is caught and reported too.
    com.craneai.findr.diag.Diag.init(com.craneai.findr.diag.AndroidDiagPlatform(this), null)
    engine = createEngine(this)
    com.craneai.findr.diag.Diag.attach(engine)
    privacy = PrivacyController(this, engine)
    privacy.start()
  }

  /**
   * The system wants memory back: drop the cached query vectors. Not on UI_HIDDEN, which fires whenever our last screen
   * goes away: the quick panel would lose its cache each time it is dismissed, and that is not memory pressure.
   */
  @Suppress("DEPRECATION")
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level != TRIM_MEMORY_UI_HIDDEN && ::engine.isInitialized) { engine.clearQueryCache(); engine.noteMemoryTrim(level) }
  }
}

internal fun createEngine(app: Application): SearchEngine = com.craneai.findr.engine.FindrEngine(app)
