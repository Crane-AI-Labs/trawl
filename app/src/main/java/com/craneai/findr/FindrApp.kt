package com.craneai.findr

import com.craneai.findr.ui.language.AppLocale
import android.content.Context
import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.craneai.findr.core.AndroidLogSink
import com.craneai.findr.core.BuildFlags
import com.craneai.findr.core.LogSinks
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.privacy.PrivacyController
import com.craneai.findr.privacy.PrivacyPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application root and the one place that decides which SearchEngine the app uses: the real one (engine/), shared
 * with the background worker. The stand-in with sample data (`ui/fake`) stays for UI work and previews.
 */
open class FindrApp : Application() {
  lateinit var engine: SearchEngine
    private set

  /** The app lock, the sensitive-result reveal and the window privacy; one per process, shared by every screen. */
  lateinit var privacy: PrivacyController
    private set

  private val ready = CompletableDeferred<SearchEngine>()
  private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  @Volatile var isReady = false
    private set

  suspend fun awaitEngine(): SearchEngine = ready.await()

  private var startedActivities = 0
  private val startupActivities = object : ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) { startedActivities++ }
    override fun onActivityStopped(activity: Activity) { startedActivities = maxOf(0, startedActivities - 1) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
  }

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
    registerActivityLifecycleCallbacks(startupActivities)
    startupScope.launch {
      try {
        val prepared = withContext(Dispatchers.IO) {
          com.craneai.findr.diag.Diag.init(com.craneai.findr.diag.AndroidDiagPlatform(this@FindrApp), null)
          com.craneai.findr.diag.HeapProbe.start(BuildConfig.FINDR_HEAPLOG, BuildConfig.HEAP_BALLAST_MB)
          val searchEngine = createSearchEngine()
          searchEngine to PrivacyPrefs(this@FindrApp)
        }
        engine = prepared.first
        com.craneai.findr.diag.Diag.attach(engine)
        privacy = PrivacyController(this@FindrApp, engine, prepared.second)
        privacy.start(startedActivities)
        (engine as? com.craneai.findr.engine.FindrEngine)?.startUiTracking(startedActivities)
        unregisterActivityLifecycleCallbacks(startupActivities)
        isReady = true
        ready.complete(engine)
      } catch (failure: Throwable) {
        ready.completeExceptionally(failure)
        throw failure
      }
    }
  }

  protected open fun createSearchEngine(): SearchEngine = createEngine(this)

  /**
   * The system wants memory back: drop the cached query vectors. Not on UI_HIDDEN, which fires whenever our last screen
   * goes away: the quick panel would lose its cache each time it is dismissed, and that is not memory pressure.
   */
  @Suppress("DEPRECATION")
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level != TRIM_MEMORY_UI_HIDDEN && ::engine.isInitialized) { engine.clearQueryCache(); engine.noteMemoryTrim(level); com.craneai.findr.ui.common.VideoStripLoader.clear() }
  }
}

internal fun createEngine(app: Application): SearchEngine = com.craneai.findr.engine.FindrEngine(app)
