package com.craneai.findr.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.craneai.findr.core.AsrEngine
import com.craneai.findr.core.DeliveryService
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.ReembedMeter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.LocalData
import com.craneai.findr.core.Locator
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.KindProgress
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackDef
import com.craneai.findr.core.PackManager
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.QuickLookProgress
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.SettingsCodec
import com.craneai.findr.core.StorageEstimate
import com.craneai.findr.extract.SherpaAsrEngine
import com.craneai.findr.index.CaptureDate
import com.craneai.findr.index.Crawler
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexOpenProblems
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.MediaStoreSource
import com.craneai.findr.index.PrivacyRules
import com.craneai.findr.index.Schema
import com.craneai.findr.index.quickLookProgress
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FindrEngine"

/**
 * The real SearchEngine: one per process, shared by the UI and the background worker.
 *
 * Two speeds, as the indexers Findr learns from work (the research notes): the crawl (names into the
 * keyword index) is cheap and runs right away, so the phone is searchable by name within minutes, Everything-style;
 * understanding files (embeddings) is slow and runs in [IndexWorker], by default only while charging.
 */
class FindrEngine(private val context: Context) : SearchEngine {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
    // Work that needs the index while it is not open (busy, or the disk is full) is skipped, not fatal: it is redone once the index
    // opens (see indexOpened). Any other uncaught error is handed on exactly as before.
    if (t is StoreNotReadyException) Log.i(TAG, "background work skipped: the index is not open")
    else Thread.currentThread().let { it.uncaughtExceptionHandler?.uncaughtException(it, t) }
  })
  private val prefs = context.getSharedPreferences("findr", Context.MODE_PRIVATE)
  private val indexDir = File(context.noBackupFilesDir, "index")

  /**
   * The index is opened off the main thread and kept trying until it opens ([StoreOpener]): a busy or unreadable file used to freeze
   * the app on its first line and then crash it on every launch. Until it is open there is no [store]; callers use [storeOrNull] or
   * [awaitStore], and the meter says what is happening ([refreshStatus]).
   */
  private val opener: StoreOpener<IndexStore> = StoreOpener(
    scope = scope, ioDispatcher = Dispatchers.IO,
    open = ::openIndex,
    classify = IndexOpenProblems::classify,
    // The person's privacy marks live in the settings, not the index: put them on the index before anything is crawled or shown.
    prepare = { st -> st.applyRules(rulesOf(settingsFlow.value)) },
    onReady = ::indexOpened,
    onFailed = { problem, n -> Log.w(TAG, "the index is not open ($problem, failed opens: $n); the engine keeps trying"); refreshStatus() },
    close = { runCatching { it.close() } },
    log = { Log.i(TAG, it) },
  )

  /** The open index; throws [StoreNotReadyException] while it is opening or failing. Entry points check [storeOrNull] or [awaitStore] first. */
  val store: IndexStore get() = opener.current ?: throw StoreNotReadyException()

  /** The open index, or null while it is opening or cannot be opened. Never blocks. */
  fun storeOrNull(): IndexStore? = opener.current

  /** The open index; waits while it is still opening, and returns null at once when it cannot be opened right now (it is being retried). */
  suspend fun awaitStore(): IndexStore? = opener.await()

  /**
   * Debug switch for a profiling build ([BuildConfig.FINDR_KNOBS]) only: the knob file's `openFail=N` makes the first N SQLite opens
   * of this process fail with `openFailCode` (default 13, disk full), to show the not-open screen and the recovery on a real phone.
   */
  private val openKnobs: Map<String, String> =
    if (com.craneai.findr.BuildConfig.FINDR_KNOBS) runCatching { Tuning.parse(File(KNOBS_FILE).readText()) }.getOrDefault(emptyMap()) else emptyMap()
  private val openFailures = java.util.concurrent.atomic.AtomicInteger(openKnobs["openFail"]?.toIntOrNull() ?: 0)
  private val openFailCode = openKnobs["openFailCode"]?.toIntOrNull() ?: 13

  private fun openIndex(onTransient: (Throwable) -> Unit): IndexStore {
    val real = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
    val driver = if (openFailures.get() <= 0) real else object : androidx.sqlite.SQLiteDriver {
      override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
        if (openFailures.getAndUpdate { if (it > 0) it - 1 else it } > 0) throw androidx.sqlite.SQLiteException("Error code: $openFailCode, message: debug switch openFail")
        return real.open(fileName)
      }
    }
    return IndexStore.open(indexDir, driver = driver, onRetry = onTransient)
  }

  /** Runs once the index is open (not on the opener's thread: a crawl can take minutes). After a failed start it does what the failure skipped. */
  private suspend fun indexOpened(opened: IndexStore, recovered: Boolean) {
    refreshStatus()
    val startNow = startWhenOpen
    startWhenOpen = false
    // Opening Trawl while the index could not be opened skipped its crawl; an index that already knows files is crawled again too.
    // (Never for an empty index otherwise: reading must not start before the person taps Start on the first-run screen.)
    if (startNow || (recovered && runCatching { opened.stats().filesKnown }.getOrDefault(0) > 0)) {
      Log.i(TAG, "the index is open again: catching up")
      scope.launch { crawl(forceFull = false); schedule() }
    }
  }

  /** Opening Trawl asked for indexing while the index could not be opened; it starts once the index opens ([indexOpened]). */
  @Volatile private var startWhenOpen = false
  /**
   * Both vector indexes keep their vectors off the Java heap, in memory-mapped files next to the database (see [VectorIndex]):
   * they are a cache of SQLite, rebuilt from it if missing, corrupt or out of date, and wiped with it by "Delete everything".
   * They settle (flush, and vouch for the database's generation) only while nothing is writing vectors, see [vectorsQuiet].
   */
  val vectors = VectorIndex(backing = File(indexDir, VECTORS_FILE), quiet = { vectorsQuiet() })
  /** Quick-look vectors (D2, D10): photos EmbeddingGemma hasn't understood yet, in TinyCLIP's 512-d space. */
  val quickVectors = VectorIndex(
    dim = IndexStore.QUICK_DIM, initialCapacity = 256, parallelism = 1, holds = VectorIndex.QUICK_LOOK,
    backing = File(indexDir, QUICK_VECTORS_FILE), quiet = { vectorsQuiet() },
  )

  /** No indexing pass or crawl is running, so no saved vector is still waiting to reach the in-memory index. */
  private fun vectorsQuiet(): Boolean = !workerState.running && !workerState.scanning && !workerLock.isLocked && !crawlLock.isLocked

  /**
   * Which model stack this phone gets, from its RAM (MemoryProfile): 2 GB phones get LITE, everything else today's STANDARD.
   * Read once per process; a debug build can force one (`BenchActivity --es set profile=lite|standard|auto`).
   */
  private val device = DeviceProfile.read(context).also { d ->
    // The phone's tuning (threads, duty, heat limits), before any model or lane is built. A profiling build may override settings
    // from a knob file; a shipped build never reads it.
    val knobs = if (com.craneai.findr.BuildConfig.FINDR_KNOBS) runCatching { Tuning.parse(File(KNOBS_FILE).readText()) }.getOrDefault(emptyMap()) else emptyMap()
    Tuning.install(d, knobs)
    knobs["stackDumpSec"]?.toIntOrNull()?.let { StackProbe.start(it) }
    Log.i(TAG, "tuning: ${Tuning.current}" + (if (knobs.isNotEmpty()) " (knob file: $knobs)" else ""))
  }
  /** The profile this phone's hardware gets, and what the signed bundles are matched against (a standard phone may hold the core model). */
  val hardwareProfile: MemoryProfile
  /** The profile that runs now: the hardware's, except that a standard phone holding only the core (text-only) model runs the lite stack. */
  val profile: MemoryProfile
  init {
    val d = device
    val override = com.craneai.findr.BuildConfig.FINDR_PROFILE.ifEmpty {
      if (com.craneai.findr.BuildConfig.DEBUG) prefs.getString(KEY_PROFILE_OVERRIDE, null) else null
    }
    hardwareProfile = d.memoryProfile(override)
    val models = File(context.filesDir, "models")
    profile = MemoryProfile.adopt(
      hardwareProfile, override,
      hasStandardModel = Embedder.MODEL_NAMES.any { File(models, it).isFile },
      hasTextOnlyModel = File(models, Embedder.TEXT_ONLY_NAME).isFile,
    )
    Log.i(TAG, "memory profile $profile (hardware $hardwareProfile, total RAM ${d.ramMb} MB, low-RAM device ${d.lowRamDevice}, memory class ${d.memoryClassMb} MB" +
      (override?.let { o -> ", forced: $o" } ?: "") + ")")
  }
  val embedder: Embedder = newEmbedder(context, profile)

  /**
   * The phone is short of memory for the heavy stages (MemoryBudget: 4 GB-class total RAM, or little free right now), so the indexer
   * keeps one heavy model loaded at a time. Read at the start of each run; the free-RAM part follows what else is open.
   */
  fun memoryTight(): Boolean {
    val mi = android.app.ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
    return MemoryBudget.tight(device.ramMb, mi.availMem / (1024 * 1024)).also {
      Log.i(TAG, "memory ${if (it) "tight" else "roomy"}: total ${device.ramMb} MB, free ${mi.availMem / (1024 * 1024)} MB")
    }
  }
  /** What the running (or last) indexer run judged with [memoryTight] at its start; null before any run. */
  @Volatile var runTight: Boolean? = null

  /**
   * Models stay loaded after a run ends or a pass finishes ([MemoryBudget.keepResident]): on a phone with room. Before any run
   * has judged the free memory, the phone's total RAM decides.
   */
  fun keepModelsResident(): Boolean = MemoryBudget.keepResident(runTight ?: MemoryBudget.tight(device.ramMb, 0))

  /** Gives every heavy model back (a tight phone's end of run, or the system asking for memory). Each reloads by itself when needed. */
  suspend fun releaseModels() {
    asr.release()
    embedder.release()
    if (!embedder.imagesByEmbeddingGemma) clip.releaseImage()
  }

  /** Speech to words (sherpa-onnx). Models are loaded on first use and released when the indexer stops. */
  val asr: AsrEngine = SherpaAsrEngine(File(context.filesDir, "models/asr"))
  /** The quick-look model (D2). Its text tower reads English only, so [queryGate] decides which queries it sees. */
  val clip = ClipEncoder(
    File(context.filesDir, "models/${QuickLookPack.DIR}"),
    lazy {
      runCatching { BundledAssets.open(ContextAssets(context), ClipTokenizer.ASSET).use { ClipTokenizer.fromGzip(it) } }
        .onSuccess { Log.i(TAG, "quick-look tokenizer loaded") }
        .onFailure { Log.w(TAG, "quick-look tokenizer unavailable: quick look can't read queries", it) }
        .getOrNull()
    },
    scope,
  )
  /** The "looks private" scorer (PrivateLook): its model ships in the APK. */
  val privacy = PrivateScorer(context)
  private val queryGate = SpeechLanguageGate.fromAssets(ContextAssets(context))
  /** The D19 word-list rule (the research notes 10e); separate from [queryGate]'s quick-look rule. Now the fallback of [wordWeights]. */
  private val queryLanguage = QueryLanguage(queryGate.words)
  /** The word weight by query language: D19c reads it from the query vector (the research notes 10i), D19 stays for a model without centroids. */
  private val wordWeights = WordWeightPolicy.fromAssets(ContextAssets(context), queryLanguage) { embedder.textFingerprint }

  /**
   * The lite profile's picture model: quick look's image tower, run by the index worker itself (FileProcessor), one file at a
   * time, for pictures the quick-look pass (which runs there only beside the words pass) hasn't reached.
   */
  val liteQuickImages: QuickImages = object : QuickImages {
    override val ready: Boolean get() = settingsFlow.value.quickLook && clip.available()
    override fun ofFile(uri: android.net.Uri): FloatArray? =
      QuickLookPass.decode(context, uri)?.let { b -> try { clip.image(b) } finally { b.recycle() } }
    override fun ofBitmap(bitmap: android.graphics.Bitmap): FloatArray = clip.image(bitmap)
    override fun has(fileId: Long): Boolean = storeOrNull()?.hasQuickVector(fileId) ?: false
  }
  private val vectorsLoaded = Mutex()
  @Volatile private var vectorsReady = false

  private val settingsFlow = MutableStateFlow(loadSettings()).also { applySpeed(it.value.learningSpeed) }
  override val settings: StateFlow<FindrSettings> = settingsFlow
  private val revealedFlow = MutableStateFlow(false)
  override val sensitiveRevealed: StateFlow<Boolean> = revealedFlow
  override val searchRepairing: StateFlow<Boolean> get() = embedder.repairing
  private val statusFlow = MutableStateFlow(IndexStatus())
  override val status: StateFlow<IndexStatus> = statusFlow

  /**
   * Optional model packs (Settings → Add-ons). Today the files come from Downloads; Play later swaps in
   * [PlayAssetPackSource] here and nothing else changes.
   */
  private val packManager = EnginePackManager(
    modelsDir = File(context.filesDir, "models"),
    source = DownloadsFolderSource(),
    choices = PackPrefs(context),
    host = PackHost(
      setEnabled = { def, on -> updateSettings { def.withEnabled(it, on) } },
      releaseForRemoval = ::releaseForRemoval,
      changed = { refreshStatus(); schedule() },
    ),
    scope = scope,
  )
  override val packs: PackManager = packManager

  /**
   * Getting the models: the signed bundle from a browser or a chosen file, the first-run card and the update notice
   * (engine/ModelDelivery.kt). After an install the loaded model is given back so the next query or index pass loads the new file.
   */
  private val modelDelivery = ModelDelivery(context, scope, hardwareProfile) {
    embedder.release()
    packManager.refresh()
    refreshStatus()
    schedule()
  }
  override val delivery: DeliveryService = modelDelivery

  /** Before a pack's files are deleted: the indexer may have them open. Stop it, wait a moment, give the models back. */
  private suspend fun releaseForRemoval(def: PackDef) {
    if (def.id != PackCatalog.SPEECH && def.id != PackCatalog.QUICK_LOOK && def.id != PackCatalog.PRIVACY) return
    val wm = WorkManager.getInstance(context)
    listOf(IndexWorker.NAME, IndexWorker.NOW, IndexWorker.BULK, IndexWorker.LATER, IndexWorker.NEXT, IndexWorker.RESUME, IndexWorker.PERIODIC).forEach { wm.cancelUniqueWork(it) }
    withTimeoutOrNull(20_000) { while (workerState.running) delay(250) }
    if (def.id == PackCatalog.PRIVACY) {
      // Without the pack nothing is blurred: forget the marks, so no stale one lingers (adding the pack scores the library afresh).
      // Done even if the worker is still winding down: the pass stops by itself once the model file is gone.
      privacy.release()
      Log.i(TAG, "privacy pack removed: ${awaitStore()?.clearLooksPrivate() ?: 0} scores cleared")
      return
    }
    if (workerState.running) return   // still running after 20 s: its own finally block releases them
    if (def.id == PackCatalog.SPEECH) asr.release() else clip.release()
  }

  private val searcher get() = Searcher(store, vectors, { enabledKinds() }, quick = quickVectors)

  /** Only one worker indexes at a time (the one-off and the periodic job can both be started by WorkManager). */
  val workerLock = Mutex()

  /** One words pass at a time, whoever runs it ([wordsPass]). */
  private val wordsPassLock = Mutex()

  /** At most one enqueue of the index work per [SCHEDULE_GAP_MS] ([schedule]). */
  private val scheduleGate = ScheduleGate(SCHEDULE_GAP_MS)

  /** What the worker reports while it runs; folded into the status. */
  @Volatile var workerState: WorkerProgress = WorkerProgress()

  /** The index worker's embedding loop is running (the quick-look pass then works only part of the time). */
  @Volatile var embeddingNow: Boolean = false

  /** A words pass is running (OCR on the CPU): the quick-look pass then works [QuickLookPass.wordsDuty] of the time. */
  @Volatile var wordsPassNow: Boolean = false

  /** Speech recognitions running now (FileProcessor raises it): the quick-look pass waits them out. */
  val speechActive = java.util.concurrent.atomic.AtomicInteger()

  /** Findr activities started (visible) right now: the search screen or the quick-search panel. Main thread only. */
  @Volatile private var visibleActivities = 0

  /** True while Findr's UI is on screen. Screen off or another app in front stops the activities and makes it false. */
  val uiVisible: Boolean get() = visibleActivities > 0

  private val powerManager = context.getSystemService(PowerManager::class.java)
  private val keyguardManager = context.getSystemService(android.app.KeyguardManager::class.java)

  /**
   * Polite mode ([PolitePolicy]): the screen is on and Findr is not in front, so the person is using something else. Heavy
   * stages wait, the models are given back, and everything runs again the moment Findr opens or the screen goes off.
   */
  fun polite(): Boolean = PolitePolicy.polite(
    powerManager?.isInteractive ?: false, uiVisible,
    keyguardLocked = Tuning.current.politeOnLockScreen.not() && keyguardManager?.isKeyguardLocked == true,
    politeOnLockScreen = Tuning.current.politeOnLockScreen,
  )

  /** True while the screen is off (a phone without a power manager reads as on, so nothing is measured). */
  fun screenOff(): Boolean = powerManager?.isInteractive == false

  /** What the worker has measured about working with the screen off on this phone ([ScreenOffMeter]). */
  fun screenOffRecord(): ScreenOffRecord = ScreenOffRecord.decode(prefs.getString(ScreenOffRecord.KEY, null))

  /** Adds a run's measurement to the totals. */
  fun addScreenOff(r: ScreenOffRecord) {
    if (r.observedMs > 0) prefs.edit().putString(ScreenOffRecord.KEY, (screenOffRecord() + r).encode()).apply()
  }

  // ---- while Findr is on screen, indexing doesn't wait for JobScheduler ----------------------------------------------
  // On the TECNO Spark 50 (Transsion, Android 16) JobScheduler left our jobs pending for 12 minutes with every constraint
  // satisfied, so a file saved after install was never crawled, let alone searchable (the research notes). While the app
  // is visible, Findr watches MediaStore itself and runs the crawl and the words pass in-process; schedule() then lets
  // WorkManager's in-process scheduler start the meaning loop. Hidden again, everything is WorkManager's.

  private var mediaObserver: android.database.ContentObserver? = null
  private var foregroundWatch: Job? = null
  private var foregroundRun: Job? = null

  private fun onUiVisible() {
    Log.i(TAG, "Findr on screen: watching MediaStore in-app")
    val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) = foregroundRefresh(debounceMs = FOREGROUND_DEBOUNCE_MS)
    }
    runCatching { context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer) }
      .onSuccess { mediaObserver = observer }
    foregroundWatch?.cancel()
    foregroundWatch = scope.launch {
      foregroundRefresh(debounceMs = 0)
      while (isActive && uiVisible) {
        delay(FOREGROUND_CHECK_MS)
        if (!workerState.running) foregroundRefresh(debounceMs = 0, crawlFirst = false)
      }
    }
  }

  private fun onUiHidden() {
    Log.i(TAG, "Findr hidden: indexing handed to WorkManager")
    mediaObserver?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
    mediaObserver = null
    foregroundWatch?.cancel()
    foregroundWatch = null
    schedule()   // hand back to WorkManager
  }

  /** In-process crawl and words pass while Findr is visible; then [schedule] for the rest. One at a time. */
  private fun foregroundRefresh(debounceMs: Long, crawlFirst: Boolean = true) {
    if (paused || foregroundRun?.isActive == true) return
    foregroundRun = scope.launch(Dispatchers.IO) {
      if (debounceMs > 0) delay(debounceMs)   // MediaStore notifies in bursts
      if (!uiVisible || paused || !hasAllFilesAccess()) return@launch
      if (awaitStore() == null) return@launch   // the index is not open: the engine retries, and catches up when it opens
      val changed = crawlFirst && crawl(forceFull = false)
      val kinds = enabledKinds()
      val wordsLeft = store.nextWithoutWords(1, kinds.intersect(WORDS_PASS_KINDS)).isNotEmpty()
      // wait = false: when ChangeWorker's pass is running it takes what this crawl found within a few files, and this refresh
      // ends now, so the next MediaStore change is crawled at once (waiting here kept a note uncrawled for 27 s on the TECNO).
      val words = if (wordsLeft && !workerState.running) wordsPass(FileProcessor(context, embedder), stop = { !uiVisible || paused }, wait = false) else 0
      if (changed || words > 0) Log.i(TAG, "in-app refresh (Findr on screen): crawl ${if (changed) "found changes" else "no changes"}, words for $words files")
      // Go has no slow pass: nothing waits for the worker once the words are in (and the unreadable kinds finish by name).
      if (Edition.GO) { if (changed || wordsLeft) schedule(); return@launch }
      if (changed || wordsLeft || (!workerState.running && store.nextPending(1, kinds).isNotEmpty())) schedule()
    }
  }

  init {
    if (com.craneai.findr.BuildConfig.FINDR_KNOBS) startQueryProbe()
    // A document copy left by a process killed mid-read: deleted before anything reads a file again.
    scope.launch(Dispatchers.IO) { LocalData.sweepScratch(context.cacheDir).let { if (it > 0) Log.i(TAG, "deleted $it leftover temporary files") } }
    (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(
      object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: android.app.Activity) { if (visibleActivities++ == 0) onUiVisible() }
        override fun onActivityStopped(activity: android.app.Activity) {
          visibleActivities = maxOf(0, visibleActivities - 1)
          if (visibleActivities == 0) onUiHidden()
        }
        override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
      },
    )
    if (Edition.MODELS) scope.launch { modelInstaller().installDefaultsFromDownloads(); modelDelivery.pollOnce() }
    scope.launch {
      while (isActive) {   // the meter: cheap counts, refreshed every couple of seconds
        refreshStatus()
        if (Edition.MODELS) packManager.refresh()
        delay(2_000)
      }
    }
    watchPower()
    watchScreen()
  }

  /**
   * Plugging in or unplugging moves the meter at once, and plugging in starts the meaning pass at once (rather than
   * waiting for WorkManager to notice the constraint, which took 90 s or more). These broadcasts are not delivered to
   * manifest receivers any more, so this one lives as long as the process does.
   */
  private fun watchPower() {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        Log.i(TAG, "power: ${intent.action?.substringAfterLast('.')}")
        refreshStatus()
        if (intent.action == Intent.ACTION_POWER_CONNECTED) schedule()
      }
    }
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction(Intent.ACTION_POWER_DISCONNECTED)
    }
    runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
  }

  // ---- privacy -----------------------------------------------------------------------------------------------

  private fun rulesOf(s: FindrSettings) = PrivacyRules(s.privateFolders, s.markedFiles)

  /**
   * The marks are kept in the settings (they outlive "Delete everything Findr knows"), by MediaStore id and by path. When a
   * crawl finds a marked file under a new id, or renamed, the store learns the new keys; this writes them back.
   */
  private fun saveLearnedMarks() {
    val learned = storeOrNull()?.takeLearnedRules() ?: return
    if (learned.markedFiles == settingsFlow.value.markedFiles) return
    val next = settingsFlow.value.copy(markedFiles = learned.markedFiles)
    settingsFlow.value = next
    saveSettings(next)
  }

  /** Sensitive files show when the person turned "Show sensitive results" on, or revealed them for this visit. */
  private fun includeSensitive() = settingsFlow.value.showSensitive || revealedFlow.value

  override fun setSensitiveRevealed(revealed: Boolean) {
    revealedFlow.value = revealed
  }

  override suspend fun setSensitive(file: IndexedFile, sensitive: Boolean) {
    val now = withContext(Dispatchers.IO) { awaitStore()?.setMarked(file.id, sensitive) } ?: return
    updateSettings { it.copy(markedFiles = now.markedFiles) }
  }

  override suspend fun recentHiddenCount(filter: KindFilter, dates: com.craneai.findr.core.DateRange?): Int = withContext(Dispatchers.IO) {
    if (includeSensitive()) 0 else awaitStore()?.recentHiddenCount(filter.kinds, dates) ?: 0
  }

  override suspend fun newestDates(limit: Int, notAfterMs: Long): List<Long> = withContext(Dispatchers.IO) { awaitStore()?.newestDates(limit, notAfterMs) ?: emptyList() }

  // ---- search ------------------------------------------------------------------------------------------------

  override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults = withContext(Dispatchers.Default) {
    val q = query.trim()
    if (q.isEmpty()) return@withContext SearchResults(query, emptyList(), 0, 0, indexIncomplete = false)
    if (awaitStore() == null) return@withContext indexNotOpen(query)
    ensureVectors()
    val (vector, quick) = queryVectors(q)
    val weight = wordWeights.meaningWeight(q, vector)
    searcher.search(q, vector, filter, limit, prefixLast = true, quickVector = quick.vector, includeSensitive = includeSensitive(), wordWeight = weight, view = view)
  }

  /**
   * Profiling builds only ([BuildConfig.FINDR_KNOBS]): a headless query run for studies that cannot drive the screen. When
   * [PROBE_QUERIES_FILE] changes (lines `id<TAB>query`), every query is searched over everything and one `FindrProbe` log line per
   * query gives the top hits as JSON: file, kind, score, and the moment (`Locator.Time`, ms) the hit points at.
   */
  private fun startQueryProbe() {
    scope.launch(Dispatchers.IO) {
      var seen = 0L
      while (isActive) {
        delay(5_000)
        val f = File(PROBE_QUERIES_FILE)
        val stamp = if (f.exists()) f.lastModified() else 0L
        if (stamp == 0L || stamp == seen) continue
        seen = stamp
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        Log.i("FindrProbe", "begin ${lines.size} queries")
        for (line in lines) {
          val id = line.substringBefore('\t')
          val q = line.substringAfter('\t', "")
          val r = runCatching { search(q, KindFilter.ALL, PROBE_LIMIT, ResultView.DEFAULT) }.getOrNull()
          val hits = JSONArray()
          r?.hits?.forEach { h ->
            val t = h.locator as? Locator.Time
            hits.put(JSONObject().put("n", redact(h.file.name)).put("k", h.file.kind.name).put("s", Math.round(h.score * 10_000) / 10_000.0).put("why", h.reason.name)
              .put("t0", t?.startMs ?: -1).put("t1", t?.endMs ?: -1))
          }
          Log.i("FindrProbe", JSONObject().put("id", id).put("q", redact(q)).put("ms", r?.tookMs ?: -1).put("strong", r?.strongCount ?: -1).put("hits", hits).toString())
        }
        Log.i("FindrProbe", "end")
      }
    }
  }

  override suspend fun anyMentions(phrase: String, files: List<IndexedFile>): Boolean = withContext(Dispatchers.Default) {
    awaitStore() != null && searcher.mentioning(phrase, files.map { it.id }).isNotEmpty()
  }

  override suspend fun similar(file: IndexedFile, filter: KindFilter, limit: Int, view: ResultView): SearchResults = withContext(Dispatchers.Default) {
    if (awaitStore() == null) return@withContext indexNotOpen(file.name)
    ensureVectors()
    var embedMs = -1L
    val results = searcher.similar(file.id, filter, limit, includeSensitive = includeSensitive(), view = view, label = file.name, quickSeed = {
      val t0 = System.nanoTime()
      quickSeedOf(file).also { embedMs = (System.nanoTime() - t0) / 1_000_000 }
    })
    Log.i(TAG, "similar to #${file.id}: ${if (embedMs >= 0) "seed embed $embedMs ms, " else ""}rank ${results.tookMs} ms, ${results.hits.size} hits (${results.strongCount} strong), ${results.hiddenCount} hidden, " +
      "${vectors.size} + ${quickVectors.size} vectors")
    results
  }

  /** [file]'s picture (a video's first frame) through the quick-look image tower: its vector for "find similar" when it has none stored. */
  private fun quickSeedOf(file: IndexedFile): FloatArray? {
    if (file.kind !in IndexStore.QUICK_KINDS || !settingsFlow.value.quickLook || !clip.available()) return null
    val uri = android.net.Uri.parse(file.uri)
    val bitmap = (if (file.kind == FileKind.VIDEO) QuickLookPass.firstFrame(context, uri) else QuickLookPass.decode(context, uri)) ?: return null
    return try { clip.image(bitmap) } catch (e: Exception) { Log.w(TAG, "similar: seed embed failed: ${e.javaClass.simpleName}"); null } finally { bitmap.recycle() }
  }

  private val storedVectors = StoredVectorsProbe(
    probe = {
      val t0 = System.nanoTime()
      (storeOrNull()?.hasVectors() ?: false).also { Log.i(TAG, "hasVectors probe=$it in ${(System.nanoTime() - t0) / 1_000} us") }
    },
    loaded = { vectors.size + quickVectors.size },
  )

  private val progressive = ProgressiveSearch(
    searcher = { searcher },
    meaningPossible = { embedder.modelFile != null || settingsFlow.value.quickLook },
    storedVectors = storedVectors::any,
    ensureVectors = ::ensureVectors,
    wantsMeaning = ::wantsMeaning,
    includeSensitive = ::includeSensitive,
    wordsWeight = { q -> wordWeights.wordsWeight(q) },
    meaningWeight = wordWeights::meaningWeight,
    embed = { q ->
      val (vector, quick) = queryVectors(q)
      QueryVectors(vector, quick.vector, "quick look: ${quick.note}; query cache ${queryCache.hits} hits, ${queryCache.misses} misses")
    },
    onMeaning = { q, embedMs, results, note ->
      Log.i(TAG, "search \"${redact(q)}\": embed $embedMs ms ($note), word weight ${wordWeights.counts}, rank ${results.tookMs} ms, ${results.hits.size} hits, " +
        "top ${redact(results.hits.firstOrNull()?.file?.name)}")
    },
  )

  /** Words at once (SQLite only: a cold process does not wait for the vector matrix), then the fused answer. See [ProgressiveSearch]. */
  override fun searchProgressive(query: String, filter: KindFilter, limit: Int, meaningDelayMs: Long, view: ResultView): Flow<SearchResults> = flow {
    // The index still opening is waited for (milliseconds); one that cannot be opened answers with nothing, and the screen re-asks when it opens.
    if (awaitStore() == null) emit(indexNotOpen(query)) else emitAll(progressive.run(query, filter, limit, meaningDelayMs, view))
  }.flowOn(Dispatchers.Default)

  /** The answer while the index cannot be opened: no hits, flagged incomplete so the screen does not claim nothing matched. */
  private fun indexNotOpen(query: String) = SearchResults(query, emptyList(), 0, 0, indexIncomplete = true)

  /** The query's quick-look vector, or why there is none (for the log). */
  private class QuickQuery(val vector: FloatArray?, val note: String)

  /** Both query vectors at once: the quick-look text tower (~0.05 s) runs while EmbeddingGemma's (0.5 s+) does. */
  private suspend fun queryVectors(q: String): Pair<FloatArray?, QuickQuery> = coroutineScope {
    val quick = async { quickQueryVector(q) }
    val vector = queryVector(q)
    vector to quick.await()
  }

  /**
   * The quick-look vector, only when it can matter: some photos are waiting on EmbeddingGemma with a quick vector, quick
   * look is on, and the query is English (CLIP's text tower reads only English; Luganda and Swahili queries keep using
   * EmbeddingGemma alone, exactly as before).
   */
  private suspend fun quickQueryVector(q: String): QuickQuery {
    if (q.length < 3 || quickVectors.size == 0) return QuickQuery(null, "no photos waiting")
    if (!settingsFlow.value.quickLook || !clip.available()) return QuickQuery(null, "off")
    if (!queryGate.isEnglishQuery(q)) return QuickQuery(null, "skipped, not English")
    val t0 = System.nanoTime()
    return try {
      val vector = quickQueryCache.get(QuickLookPack.FINGERPRINT, q) { clip.query(it) }
      QuickQuery(vector, "${(System.nanoTime() - t0) / 1_000_000} ms over ${quickVectors.size} photos")
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "quick-look query failed", t)
      QuickQuery(null, "failed")
    }
  }

  @Volatile private var interactingUntil = 0L

  override fun setUserInteracting(active: Boolean) {
    interactingUntil = if (active) Long.MAX_VALUE else System.currentTimeMillis() + YIELD_AFTER_MS
  }

  /** True while the user scrolls, and for a moment after: the worker waits between files. */
  val userInteracting: Boolean get() = System.currentTimeMillis() < interactingUntil

  /** Scrollable lists on screen now ([setListVisible]); counted, so the grid and any other list balance out. */
  @Volatile private var listsOnScreen = 0

  override fun setListVisible(visible: Boolean) {
    listsOnScreen = (listsOnScreen + if (visible) 1 else -1).coerceAtLeast(0)
  }

  /** See [GpuGate]: whether the worker should hold a GPU image encode back for the user right now. */
  val gpuShouldWait: Boolean get() {
    val until = interactingUntil
    return GpuGate.shouldWait(System.currentTimeMillis(), (until - YIELD_AFTER_MS).takeIf { until != Long.MAX_VALUE }, uiVisible, listsOnScreen)
  }

  override fun prewarm() {
    scope.launch {
      ensureVectors()
      if (embedder.modelFile != null && vectors.size > 0) runCatching { embedder.warm() }.onFailure { Log.w(TAG, "prewarm failed", it) }
      if (quickVectors.size > 0 && settingsFlow.value.quickLook) runCatching { clip.warmText() }.onFailure { Log.w(TAG, "quick-look prewarm failed", it) }
    }
  }

  /** Meaning only once something has a vector, and only for queries long enough to mean something. */
  private fun wantsMeaning(q: String) = q.length >= ProgressiveSearch.MIN_MEANING_CHARS &&
    ((vectors.size > 0 && embedder.modelFile != null) || (quickVectors.size > 0 && settingsFlow.value.quickLook))

  /**
   * Query vectors already made, in memory only (see [QueryVectorCache]): a repeated query skips the embed. EmbeddingGemma's are
   * keyed by its text model, the quick-look text tower's by its own fingerprint. Both main search and the quick panel go through
   * here, so they share it.
   */
  private val queryCache = QueryVectorCache()
  private val quickQueryCache = QueryVectorCache()

  /** When the system last signalled memory pressure ([PolitePolicy.pressure]), SystemClock.elapsedRealtime(); 0 = never. */
  @Volatile private var memoryPressureAt = 0L

  override fun noteMemoryTrim(level: Int) {
    if (PolitePolicy.pressure(level)) memoryPressureAt = android.os.SystemClock.elapsedRealtime()
    // An idle app on a roomy phone keeps its models for the next search or run, until the system really asks for the memory back.
    if (MemoryBudget.releaseWhenIdle(level) && !workerState.running) scope.launch {
      Log.i(TAG, "the system is short of memory (trim level $level) and nothing is running: models released")
      releaseModels()
      privacy.release()
    }
  }

  /** The system signalled memory pressure since [since] (elapsedRealtime): a polite wait then gives the models back at once. */
  fun memoryPressureSince(since: Long): Boolean = memoryPressureAt >= since

  override fun clearQueryCache() {
    queryCache.clear()
    quickQueryCache.clear()
  }

  private suspend fun queryVector(q: String): FloatArray? {
    // EmbeddingGemma's query vector is only worth its ~0.5 s (plus a load) once it has understood something.
    if (q.length < 3 || vectors.size == 0 || embedder.modelFile == null) return null
    val fingerprint = embedder.textFingerprint ?: return null
    return try {
      queryCache.get(fingerprint, q) { embedder.query(it) }
    } catch (e: CancellationException) {
      throw e   // the user typed on: not a failure
    } catch (e: EngineUnhealthyException) {
      null      // the self-test failed (Embedder logged and reported it): words alone, and the screen says so (searchRepairing)
    } catch (t: Throwable) {
      Log.w(TAG, "query embedding failed", t)
      null
    }
  }

  override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> = withContext(Dispatchers.IO) {
    awaitStore()?.recent(filter.kinds, limit, includeSensitive = includeSensitive(), order = view.sort, dates = view.dates) ?: emptyList()
  }

  private suspend fun ensureVectors() {
    if (vectorsReady) return
    val st = awaitStore() ?: return   // not open: the callers have nothing to search yet either
    vectorsLoaded.withLock {
      if (vectorsReady) return
      val started = System.nanoTime()
      vectors.load(st)
      // A new quick-look model (D10) makes earlier quick vectors incomparable, even at the same size (laion's were 512-d too):
      // drop them before anything reads them.
      if (st.ensureQuickFingerprint(QuickLookPack.FINGERPRINT)) Log.i(TAG, "quick look: new model, earlier quick vectors dropped")
      quickVectors.load(st)
      vectorsReady = true
      Log.i(TAG, "loaded ${vectors.size} vectors and ${quickVectors.size} quick-look vectors in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
  }

  /** The worker calls this after saving a file, so search sees new vectors at once (and its quick-look vector, dropped by the save, goes). */
  fun onFileIndexed(fileId: Long) {
    if (vectorsReady) {
      val st = storeOrNull() ?: return
      vectors.loadFile(st, fileId)
      quickVectors.loadFile(st, fileId)
    }
  }

  /** The quick-look pass calls this after saving a file's quick vector. */
  fun onQuickIndexed(fileId: Long) {
    if (vectorsReady) storeOrNull()?.let { quickVectors.loadFile(it, fileId) }
  }

  /**
   * The quick-look pass may run: it is on, its models are in, indexing isn't paused, and the policy allows the light tier
   * (on the charger, or on battery with the battery at 30% or more and the phone not hot, unless "only while charging" is on).
   * The lite profile runs the pass only beside the words pass, before EmbeddingGemma's text model is loaded (the two passes hold
   * OCR and the quick-look image tower, nothing more); after that its worker reads pictures with the same model itself.
   */
  fun quickLookShouldRun(): Boolean =
    (profile == MemoryProfile.STANDARD || !embeddingNow) &&
      settingsFlow.value.quickLook && clip.available() && !paused && policy().lightAllowed && !polite()

  /** The scoring pass may run: its model is in the build, indexing isn't paused, and the phone is charging. Not tied to the blur settings: a switch turned on later finds the scores ready. */
  fun privacyShouldRun(): Boolean = settingsFlow.value.privateScoring && privacy.available() && !paused && isCharging() && !polite()
  // ---- indexing ----------------------------------------------------------------------------------------------

  /**
   * Crawl now (cheap: names searchable within minutes), then schedule understanding for when conditions allow. Called on
   * every app launch and after an error, so it never undoes the user's Pause (IndexPause): it used to, and a paused index
   * started again the next time the app opened. While paused the crawl only takes things out ([crawl]): a file deleted
   * during the pause leaves the index, and after "Delete everything Findr knows" a launch puts nothing back.
   */
  override fun startIndexing() {
    setPaused(IndexPause.after(IndexPause.Event.START, paused))
    filesAccessGranted()   // the model and default packs now, not whenever the charger-gated worker first runs
    // The index cannot be opened right now: the crawl below is skipped, so remember to do it when the index opens, and try at once.
    if (opener.failing) { startWhenOpen = true; opener.retryNow() }
    scope.launch { crawl(forceFull = false) }   // while the index is still opening this waits for it
    schedule()
  }

  override fun filesAccessGranted() {
    if (!Edition.MODELS) return   // Trawl Go has no models to install or fetch
    scope.launch { modelInstaller().installDefaultsFromDownloads(); modelDelivery.pollOnce() }
  }

  override fun pauseIndexing() {
    setPaused(IndexPause.after(IndexPause.Event.PAUSE, paused))
    WorkManager.getInstance(context).let { wm -> listOf(IndexWorker.NAME, IndexWorker.BULK, IndexWorker.LATER, IndexWorker.NEXT, IndexWorker.RESUME).forEach(wm::cancelUniqueWork) }
    scope.launch { refreshStatus() }
  }

  override fun resumeIndexing() {
    setPaused(IndexPause.after(IndexPause.Event.RESUME, paused))
    startIndexing()
  }

  private fun setPaused(value: Boolean) {
    if (value != paused) prefs.edit().putBoolean(KEY_PAUSED, value).apply()
  }

  val paused: Boolean get() = prefs.getBoolean(KEY_PAUSED, false)

  /** The power policy right now (IndexPolicy): what may run on this charger, battery level and heat. */
  fun policy(): IndexPolicy.Decision = settingsFlow.value.let { s ->
    IndexPolicy.decide(isCharging(), batteryPercent(), thermalStatus(), s.onlyWhileCharging, s.fullOnBattery)
  }

  /** WorkManager's own battery-not-low is about 15%; the policy's 30% is checked by [policy], in the worker too. */
  private fun lightConstraints(s: FindrSettings) =
    Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(s.onlyWhileCharging).build()

  /** The bulk backlog waits for the charger unless the person turned full indexing on battery on (and the strict setting is off). */
  private fun bulkConstraints(s: FindrSettings) =
    Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(s.onlyWhileCharging || !s.fullOnBattery).build()

  /**
   * Asks WorkManager for the index work. Coalesced ([ScheduleGate]): a burst of requests (the first crawl, every MediaStore
   * notification, a pack refresh, the charger) used to enqueue it again each time, and each run exited at once.
   */
  fun schedule() {
    if (paused) return
    when (val d = scheduleGate.request(System.currentTimeMillis())) {
      ScheduleGate.Decision.Now -> enqueueWork()
      is ScheduleGate.Decision.Later -> scope.launch {
        delay((d.atMs - System.currentTimeMillis()).coerceAtLeast(0))
        scheduleGate.trailingFired(System.currentTimeMillis())
        enqueueWork()
      }
      ScheduleGate.Decision.Coalesced -> {}
    }
  }

  private fun enqueueWork() {
    if (paused) return
    val s = settingsFlow.value
    val wm = WorkManager.getInstance(context)
    val mayRunNow = policy().lightAllowed
    scope.launch(Dispatchers.IO) {
      // A request still waiting out a retry backoff (stopped by the system, or an older build's retry) is replaced;
      // a running one is kept. KEEP alone left a request 10 attempts deep waiting hours on the Infinix.
      fun policyFor(name: String) =
        if (runCatching { wm.getWorkInfosForUniqueWork(name).get() }.getOrDefault(emptyList())
            .any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }) ExistingWorkPolicy.REPLACE
        else ExistingWorkPolicy.KEEP
      // Start now when it may run now: on the Infinix (Android 16, Transsion) JobScheduler held a job whose constraints
      // were all satisfied in its pending queue for 50 minutes ("flexible" constraints wait for an idle device). An
      // expedited request isn't delayed; the worker decides what it may do itself (IndexPolicy), and only one worker
      // runs at a time (a run that finds the phone plugged in moves on to the bulk backlog by itself).
      if (mayRunNow) {
        wm.enqueueUniqueWork(
          IndexWorker.NOW, policyFor(IndexWorker.NOW),
          OneTimeWorkRequestBuilder<IndexWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_S, TimeUnit.SECONDS).build(),
        )
      }
      // The light work (words, quick look, new files) may start on battery; the bulk backlog waits for the charger.
      wm.enqueueUniqueWork(
        IndexWorker.NAME, policyFor(IndexWorker.NAME),
        OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(lightConstraints(s))
          .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_S, TimeUnit.SECONDS).build(),
      )
      wm.enqueueUniqueWork(
        IndexWorker.BULK, policyFor(IndexWorker.BULK),
        OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(bulkConstraints(s))
          .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_S, TimeUnit.SECONDS).build(),
      )
    }
    watchForChanges()
    // An hourly light crawl (names + words, battery-safe), in case a content trigger is missed (the research notes).
    wm.enqueueUniquePeriodicWork(
      ChangeWorker.HEARTBEAT, ExistingPeriodicWorkPolicy.UPDATE,
      PeriodicWorkRequestBuilder<ChangeWorker>(1, TimeUnit.HOURS).build(),
    )
    // A safety net that picks up new photos and downloads even if nobody opens the app.
    wm.enqueueUniquePeriodicWork(
      IndexWorker.PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
      PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS).setConstraints(lightConstraints(s)).build(),
    )
  }

  /**
   * Low battery or heat held the light work back: look again in a few minutes (IndexPolicy.RETRY_LATER_MIN), so it resumes by
   * itself. Its own unique name, because the run that asks is usually the one holding NAME.
   */
  fun scheduleRetryLater() {
    if (paused) return
    WorkManager.getInstance(context).enqueueUniqueWork(
      IndexWorker.LATER, ExistingWorkPolicy.REPLACE,
      OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(lightConstraints(settingsFlow.value))
        .setInitialDelay(IndexPolicy.RETRY_LATER_MIN, TimeUnit.MINUTES).build(),
    )
  }

  /**
   * The next capped batch of new files (D12), asked for after a run that stopped at its cap, or by the hourly crawl in a Doze
   * window. Its own unique name, appended (never replacing the run that asks, which may be the previous batch). It carries the
   * light constraints and a short delay, and Android starts it in the next window it gives: no alarm, no wake lock.
   */
  fun scheduleNextBatch() {
    if (paused) return
    WorkManager.getInstance(context).enqueueUniqueWork(
      IndexWorker.NEXT, ExistingWorkPolicy.APPEND_OR_REPLACE,
      OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(lightConstraints(settingsFlow.value))
        .setInitialDelay(IndexPolicy.NEXT_BATCH_MIN, TimeUnit.MINUTES).build(),
    )
  }

  /**
   * A run left its foreground service (a long polite wait, or the system's 6-hour data-sync limit) and asks to be run again
   * (RunHandoff). The screen going off or Trawl opening starts it at once ([watchScreen], startIndexing); this timer is the
   * net under that, and only one is ever waiting. Its own unique name, appended behind the run that asks (which may be a previous
   * resume). The run that starts from it may find the phone still polite and hand off again, without a service.
   */
  suspend fun scheduleResume(delayMin: Long) {
    if (paused) return
    handOffPending = true
    withContext(Dispatchers.IO) {
      val wm = WorkManager.getInstance(context)
      val waiting = runCatching { wm.getWorkInfosForUniqueWork(IndexWorker.RESUME).get() }.getOrDefault(emptyList()).any { it.state == WorkInfo.State.ENQUEUED }
      // A delayed safety-net resume already waiting is enough for another delayed one; it is NOT enough for the immediate rerun
      // (the net sat behind 10 minutes of Hiber's hold and the rerun after the limit never came), which replaces it instead.
      if (waiting && delayMin > 0) return@withContext
      // No delay means expedited: on the Infinix a plain delayed job sat in JobScheduler's pending queue with every constraint met
      // (see enqueueWork), so the rerun after the 6-hour limit never came. The expedited run waits its turn on the worker lock.
      // (Expedited work takes no battery constraint; the worker's own policy still decides what it may do.)
      val builder = OneTimeWorkRequestBuilder<IndexWorker>().addTag(IndexWorker.RESUME)
      val request = if (delayMin <= 0) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
      else builder.setConstraints(lightConstraints(settingsFlow.value)).setInitialDelay(delayMin, TimeUnit.MINUTES).build()
      // Never let a failed request take the worker (or the app) down: the periodic work and opening Trawl still start a run.
      val policy = if (delayMin <= 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
      runCatching { wm.enqueueUniqueWork(IndexWorker.RESUME, policy, request) }
        .onFailure { Log.w("FindrEngine", "resume request failed", it) }
    }
  }

  /** A run handed itself off and the screen has not gone off yet ([scheduleResume], [watchScreen]). */
  @Volatile var handOffPending = false

  /** Something is left for the worker to do: files without their words, or waiting for the meaning pass. */
  fun workWaiting(): Boolean {
    val kinds = enabledKinds()
    val store = storeOrNull() ?: return false
    return store.nextWithoutWords(1, kinds.intersect(WORDS_PASS_KINDS)).isNotEmpty() ||
      store.nextPending(1, kinds).isNotEmpty() || store.nextPartial(1, kinds).isNotEmpty()
  }

  /**
   * A run that stopped waiting for a polite phone is asked back the moment the screen goes off (the phone is down: night,
   * charger, pocket), not when a timer notices. Like [watchPower], this lives as long as the process does: the broadcast is not
   * delivered to manifest receivers.
   */
  private fun watchScreen() {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        if (!RunHandoff.resumeOnScreenOff(handOffPending)) return
        handOffPending = false
        Log.i(TAG, "screen off: the run that waited out a polite phone is asked back")
        schedule()
      }
    }
    runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }
  }

  /** Whether files that arrived since the backlog was counted still wait for full quality (the kinds D12 gives it to on battery). */
  fun newFilesWaiting(): Boolean {
    return nextNewFiles(1).isNotEmpty()
  }

  /** The next files that arrived since the backlog was counted and are small enough for full quality on battery (see [IndexPolicy.newFileKinds]). */
  fun nextNewFiles(limit: Int): List<com.craneai.findr.index.PendingFile> {
    val t = Tuning.current
    val kinds = enabledKinds().intersect(IndexPolicy.newFileKinds(t.newMediaOnBattery))
    return store.nextNewPending(limit, kinds, store.backlogMark(), t.newVideoMaxMb * 1_048_576L, t.newAudioMaxMb * 1_048_576L)
  }

  /** D12's follow-up: asks for the next batch of new files, or a later look, when [IndexPolicy.followUp] says one is due. Returns what it did. */
  fun followUpNewFiles(): IndexPolicy.FollowUp {
    val what = runCatching { IndexPolicy.followUp(policy(), paused, newFilesWaiting()) }.getOrDefault(IndexPolicy.FollowUp.NONE)
    when (what) {
      IndexPolicy.FollowUp.NEXT_BATCH -> scheduleNextBatch()
      IndexPolicy.FollowUp.RETRY_LATER -> scheduleRetryLater()
      IndexPolicy.FollowUp.NONE -> {}
    }
    if (what != IndexPolicy.FollowUp.NONE) Log.i(TAG, "new files still wait for full quality: $what (battery ${batteryPercent()}%)")
    return what
  }

  private val crawlLock = Mutex()

  /** Returns true when files were added, changed or taken out (so there is new work to schedule). */
  suspend fun crawl(forceFull: Boolean): Boolean = withContext(Dispatchers.IO) {
    if (!hasAllFilesAccess()) return@withContext false
    if (awaitStore() == null) return@withContext false   // not open: skipped, and redone when the index opens (indexOpened)
    val changed = crawlLock.withLock {
      workerState = workerState.copy(scanning = true)
      try {
        // Once per install: rows saved before "recent files first" get the new order (the crawl only sets it for new and changed files).
        store.reprioritiseOnce().let { if (it > 0) Log.i(TAG, "queue order: $it files re-ordered, last ${Schema.RECENT_DAYS} days first") }
        // Paused: removals only (deleted files, newly skipped folders), never additions (Crawler.sync).
        var exifReads = 0; var exifDated = 0; var exifNs = 0L
        val crawlStart = System.nanoTime()
        val report = Crawler(store, MediaStoreSource(context), { settingsFlow.value }, log = { Log.i(TAG, it) },
          exifDate = {
            val t0 = System.nanoTime()
            val ms = CaptureDate.read(context.contentResolver, it.uri)
            exifNs += System.nanoTime() - t0; exifReads++; if (ms > 0) { exifDated++; Log.d(TAG) { "exif: ${it.name} -> $ms" } }
            ms
          }).sync(forceFull, pruneOnly = paused)
        if (exifReads > 0) {
          Log.i(TAG, "exif dates: $exifReads headers read, $exifDated dated, ${exifNs / 1_000_000} ms in the reader " +
            "(${exifNs / 1_000 / exifReads} us each), crawl ${(System.nanoTime() - crawlStart) / 1_000_000} ms")
        }
        // Gone from the phone, or no longer allowed (a switched-off group, an excluded folder): out of the in-memory vectors too.
        // A file whose content changed lost its segments in the database too, so its old vectors go as well (the vector files
        // would otherwise carry them across a restart; the worker loads the new ones when it has read the file again).
        val gone = report.deletedFileIds + report.newlySkippedFileIds + report.resetFileIds
        if (gone.isNotEmpty()) {
          vectors.removeFiles(gone)
          quickVectors.removeFiles(gone)
        }
        Log.i(TAG, "crawl: $report")
        saveLearnedMarks()
        store.backlogMark()   // the first finished crawl draws the line between the backlog and new files (IndexPolicy)
        report.added + report.changed + gone.size > 0
      } catch (t: Throwable) {
        Log.w(TAG, "crawl failed", t)
        false
      } finally {
        workerState = workerState.copy(scanning = false)
      }
    }
    refreshStatus()
    changed
  }

  /**
   * "Delete everything Findr knows" (Settings). Stops all indexing and leaves it paused, so nothing is read again until
   * the person taps Resume. Then deletes the index (names, folders, document and OCR text, transcripts, vectors), its
   * write-ahead log, any old corrupt copy, and the whole cache directory (temporary copies, runtime caches). Settings and
   * the AI models stay ([LocalData]). Work still running after [WIPE_WAIT_MS] can't put anything back: the new index
   * has none of the old file rows, and its foreign keys refuse words or vectors for them.
   */
  override suspend fun deleteIndex(): Forgotten = withContext(Dispatchers.IO) {
    setPaused(IndexPause.after(IndexPause.Event.PAUSE, paused))
    val wm = WorkManager.getInstance(context)
    // BULK (waiting for the charger) and LATER (waiting for the battery or heat to recover) are battery-policy requests: a pending
    // one must not outlive the delete and start reading again when the phone is plugged in. The worker also checks `paused`.
    val names = listOf(IndexWorker.NAME, IndexWorker.NOW, IndexWorker.BULK, IndexWorker.LATER, IndexWorker.NEXT, IndexWorker.RESUME, IndexWorker.PERIODIC, ChangeWorker.NAME, ChangeWorker.HEARTBEAT)
    names.forEach { wm.cancelUniqueWork(it) }
    withTimeoutOrNull(WIPE_WAIT_MS) {
      while (workerState.running || names.any { n ->
          runCatching { wm.getWorkInfosForUniqueWork(n).get() }.getOrDefault(emptyList()).any { it.state == WorkInfo.State.RUNNING }
        }) delay(100)
    }
    val gone = crawlLock.withLock {
      embedder.release()
      // Also when the index never opened (then there is nothing to close, and the wipe is what mends a file that cannot be opened).
      runCatching { opener.detach()?.close() }.onFailure { Log.w(TAG, "index not closed cleanly (${it.javaClass.simpleName})") }
      // The mapped vector files hold the vectors of every file: overwrite them before the wipe deletes them.
      runCatching { vectors.shred(); quickVectors.shred() }.onFailure { Log.w(TAG, "vector files not shredded (${it.javaClass.simpleName})") }
      LocalData.wipe(indexDir, context.cacheDir).also { opener.start() }
    }
    // The marks are settings, and the wipe keeps settings: the new index gets them as it opens (the opener's prepare step), so the
    // rebuild cannot make marked files findable. Waited for here, as the old synchronous open was.
    awaitStore()
    saveLearnedMarks()
    // The problem reports too: they hold no file names, but "everything Findr knows" means everything.
    com.craneai.findr.diag.Diag.forgetReports()
    vectors.clear()
    quickVectors.clear()
    clearQueryCache()
    vectorsReady = false
    storedVectors.reset()
    Log.i(TAG, "deleted everything: ${gone.files} files, ${gone.bytes / 1024} KB learned + ${gone.cacheBytes / 1024} KB of caches; indexing paused")
    refreshStatus()
    gone
  }

  override suspend fun folderCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
    if (!hasAllFilesAccess()) return@withContext emptyMap()
    runCatching { Crawler(store, MediaStoreSource(context), { settingsFlow.value }).countByGroup() }
      .onFailure { Log.w(TAG, "folder counts failed", it) }.getOrDefault(emptyMap())
  }

  /** Lays the person's [LearningSpeed] over the phone's tuning ([LearningProfile]). */
  private fun applySpeed(speed: com.craneai.findr.core.LearningSpeed) {
    val roomy = LearningProfile.roomy(Runtime.getRuntime().availableProcessors(), MemoryBudget.tight(device.ramMb, 0))
    Tuning.useSpeed(speed, roomy)
    Log.i(TAG, "learning speed: ${speed.key}; tuning: ${Tuning.current}")
  }

  override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {
    val before = settingsFlow.value
    val after = transform(before).forEdition()
    if (after.learningSpeed != before.learningSpeed) applySpeed(after.learningSpeed)
    settingsFlow.value = after
    saveSettings(after)
    // Folder marks change what the rows carry; a single file mark has already been written to the row by setSensitive.
    // (An index that is not open picks the marks up as it opens.)
    storeOrNull()?.let { if (rulesOf(after) != it.rules) it.applyRules(rulesOf(after)) }
    // Where to look changed: read every row again so files in a switched-off or excluded place leave the index (words
    // and vectors, on disk and in memory) and files in a switched-on place are queued. See Crawler / IndexStore.upsertFromCrawl.
    // Before the first crawl (the onboarding screen "What should Findr search?") there is nothing to remove, and reading must not
    // start before the person taps Start: the first crawl after onboarding is a full one and will use these settings.
    if ((after.excludedFolders != before.excludedFolders || after.groupOverrides != before.groupOverrides ||
        after.includedFolders != before.includedFolders) && (runCatching { store.stats().filesKnown }.getOrDefault(0) > 0)
    ) {
      crawl(forceFull = true)
      schedule()   // newly allowed files are waiting to be understood
    }
    if (after.learningSpeed != before.learningSpeed) schedule()
    refreshStatus()
  }

  /**
   * The fast pass: words only, no model. Every document's text and every image's text go into the keyword index, so
   * a file is searchable by its words within minutes of arriving; the slow pass replaces these pieces with embedded
   * ones later. Used by the indexing worker before embedding, and by [ChangeWorker] when new files appear (on battery
   * too: it costs milliseconds per document and ~0.26 s per image on the A06). Returns files read.
   * [holdWhilePolite]: wait, file by file, while polite mode is on (the long-running worker does; the short ChangeWorker skips the pass instead).
   */
  suspend fun wordsPass(processor: FileProcessor, limit: Int = Int.MAX_VALUE, stop: () -> Boolean = { false }, wait: Boolean = true, holdWhilePolite: Boolean = false, onPoliteHold: suspend () -> Unit = {}): Int {
    val kinds = enabledKinds().intersect(WORDS_PASS_KINDS)
    if (awaitStore() == null) return 0   // not open: nothing to read into
    val started = System.nanoTime()
    // One pass at a time across ChangeWorker, IndexWorker and the in-process pass while Findr is on screen (WordsPassLoop):
    // two at once OCR'd the same batches. The second caller waits, then finds only what is left.
    // Trawl Go has nothing after the words pass (no model), so a file is finished once its words are in; the kinds it cannot read
    // (videos, sound files) are finished too, findable by name (IndexStore.finishNameOnly).
    val finished = if (Edition.GO) FileState.UNDERSTOOD else FileState.SEARCHABLE
    if (Edition.GO) storeOrNull()?.finishNameOnly(WORDS_PASS_KINDS)
    val done = WordsPassLoop.run(wordsPassLock, limit, { stop() || paused }, { n -> store.nextWithoutWords(n, kinds) }, wait) { p ->
      wordsPassNow = true
      try {
        // The user is scrolling or typing: wait, as the meaning pass does. A photo's words are a 2048 px decode plus OCR
        // (~0.3-0.5 s of CPU on the A06), and this loop used to run them back to back under a moving grid.
        while (userInteracting && !stop() && !paused) delay(250)
        // Polite mode (PolitePolicy): the person is in another app. OCR is ~900% of the A06's CPU, so the background pass waits.
        // It waits a blip out, not for ever: past RunHandoff.HAND_OFF_AFTER_MS the pass ends and the worker hands its run off,
        // because a worker that only waits still holds its foreground service (Android 15 allows that 6 hours a day).
        if (holdWhilePolite && polite()) {
          val since = android.os.SystemClock.elapsedRealtime()
          Log.i(TAG, "polite: the words pass waits (screen on, Trawl not in front)")
          onPoliteHold()   // the notification stops saying a file is being read
          while (holdWhilePolite && polite() && !stop() && !paused && !RunHandoff.handOffDue(android.os.SystemClock.elapsedRealtime() - since)) delay(PolitePolicy.POLL_MS)
          if (polite() && !stop() && !paused) {
            Log.i(TAG, "polite: the words pass ends after ${(android.os.SystemClock.elapsedRealtime() - since) / 1000} s; the run is handed off")
            return@run false
          }
          Log.i(TAG, "polite: the words pass resumes after ${(android.os.SystemClock.elapsedRealtime() - since) / 1000} s")
        }
        if (stop() || paused) return@run false
        workerState = workerState.copy(currentFile = if (store.isSensitive(p.file.id)) null else p.file.name)
        val fileStarted = System.nanoTime()
        try {
          val pieces = withTimeout(if (Edition.GO) 150_000 else 60_000) { processor.words(p.file) } ?: emptyList()
          if (Edition.GO) {
            val took = (System.nanoTime() - fileStarted) / 1e9
            Pace.record(p.file.kind, took)   // Go's pace is the words pass (the meter's ETA)
            Log.i(TAG, "go words: ${p.file.kind} ${(took * 1000).toLong()} ms, ${pieces.size} pieces")   // kind and counts only: nothing private
          }
          // keepQuick: the words pass adds no vectors, so a quick-look vector saved meanwhile stays.
          store.saveSegments(p.file.id, pieces.map { it.toSegment(late = false) }, finished, IndexStore.WORDS_DONE, keepQuick = true)
          Log.i(TAG, "words ${redact(p.file.name)}: ${p.file.kind}, ${pieces.size} pieces")   // log-safe: redacted name, enum kind
        } catch (e: TimeoutCancellationException) {
          store.setState(p.file.id, finished, IndexStore.WORDS_DONE)   // the slow pass still tries (Go: the name stays searchable)
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          Log.w(TAG, "words pass failed: ${redact(p.file.name)}", t)
          store.setState(p.file.id, finished, IndexStore.WORDS_DONE)
        }
        true
      } finally {
        wordsPassNow = false
      }
    }
    if (done > 0) Log.i(TAG, "words pass: $done files in ${(System.nanoTime() - started) / 1_000_000_000} s")
    return done
  }

  /**
   * Re-arms the watcher that runs [ChangeWorker] when MediaStore changes (a new photo, a download), so new files get
   * names and words within about a minute without anyone opening the app. Content-triggered work fires once, so each
   * run re-arms the next (APPEND_OR_REPLACE queues it behind the one running now).
   */
  fun watchForChanges(fromRunningTrigger: Boolean = false) {
    if (paused) return
    val constraints = Constraints.Builder()
      .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
      .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
      .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
      .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
      .setTriggerContentMaxDelay(Duration.ofMinutes(1))
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      // From inside a firing trigger, queue the next watcher behind it; otherwise keep a watcher that is already
      // waiting (APPEND from every app start stacked one more pending watcher per launch).
      ChangeWorker.NAME, if (fromRunningTrigger) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<ChangeWorker>().setConstraints(constraints).build(),
    )
  }

  fun enabledKinds(): Set<FileKind> = settingsFlow.value.let { s ->
    buildSet {
      if (s.indexPhotos) add(FileKind.IMAGE)
      if (s.indexScreenshots) add(FileKind.SCREENSHOT)
      if (s.indexVideos) add(FileKind.VIDEO)
      if (s.indexDocuments) { add(FileKind.DOCUMENT); add(FileKind.PDF) }
      if (s.indexRecordings) add(FileKind.AUDIO)   // what is done with each one: AudioPolicy
    }
  }

  fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

  fun modelInstaller() = ModelInstaller(context, packManager::reportAutoInstall, profile)

  // ---- the meter ---------------------------------------------------------------------------------------------

  fun refreshStatus() {
    val store = storeOrNull()
    if (store == null) {
      // Still opening: the "getting ready" status stands. Cannot be opened right now: say so calmly (the screen's words are in
      // IndexCopy) while the engine keeps trying in the background.
      (opener.state.value as? StoreOpener.State.Failed)?.let { statusFlow.value = IndexStatus(phase = IndexPhase.ERROR, openProblem = it.problem, charging = isCharging()) }
      return
    }
    val stats = runCatching { store.stats() }.getOrNull() ?: return
    val kinds = enabledKinds()
    // Go waits only for what it can read (words); a video or a sound file is already as findable as it will get (by name).
    val waiting = stats.waiting(if (Edition.GO) kinds.intersect(WORDS_PASS_KINDS) else kinds)
    val w = workerState
    val byKind = FileKind.entries.filter { it != FileKind.OTHER }.associateWith { k ->
      val known = stats.byKindAndState[k]?.filter { it.key != FileState.SKIPPED }?.values?.sum() ?: 0
      KindProgress(known - stats.count(k, FileState.FAILED), stats.count(k, FileState.UNDERSTOOD))   // a file given up on is final: it leaves the count it would never reach
    }.filterValues { it.known > 0 }
    val charging = isCharging()
    // Trawl Go reads words only, which needs neither the charger nor a model: the meter never waits for a charger there.
    val decision = policy().let { if (Edition.GO && it.heldBy == PauseReason.NOT_CHARGING) it.copy(heldBy = null) else it }
    val estimate = storageEstimate(store, stats.filesKnown, kinds)
    val needsCharger = MeterRules.needsCharger(waiting, decision)
    val pause = MeterRules.pause(hasAllFilesAccess(), paused, w.pauseReason, w.running, waiting, decision)
    val phase = when {
      w.scanning -> IndexPhase.SCANNING
      w.running && pause == null -> IndexPhase.INDEXING
      pause != null -> IndexPhase.PAUSED
      stats.filesKnown == 0 -> IndexPhase.NOT_STARTED
      waiting == 0 -> IndexPhase.UP_TO_DATE
      else -> IndexPhase.INDEXING
    }
    statusFlow.value = IndexStatus(
      phase = phase,
      pauseReason = pause,
      filesKnown = stats.filesKnown,
      filesSearchable = stats.filesSearchable,
      filesUnderstood = stats.filesUnderstood,
      filesUnreadable = stats.filesUnreadable,
      byKind = byKind,
      etaSeconds = MeterRules.eta(Pace.eta(stats, kinds), needsCharger),
      charging = charging,
      needsCharger = needsCharger,
      lightOnBattery = !Edition.GO && !charging && decision.tier == IndexPolicy.Tier.LIGHT,
      ratePerHour = Pace.ratePerHour(),
      indexBytes = store.bytesOnDisk(),
      estimatedIndexBytes = estimate.indexBytes,
      searchMemoryBytes = vectors.memoryBytes() + quickVectors.memoryBytes(),
      estimatedSearchMemoryBytes = estimate.ramBytes,
      modelBytes = embedder.modelFile?.length() ?: 0,
      speechReady = asr.available(),
      currentFile = if (w.running) w.currentFile else null,
      lastError = w.lastError,
      tooLargeFiles = stats.tooLarge,
      quickLook = quickLookProgress(stats, kinds),
      reembed = runCatching { store.reembedProgress().over(kinds) }.getOrNull()?.let { ReembedMeter(it.first, it.second) },
    )
    logMeter(statusFlow.value)
  }

  private var estimateKey: Pair<Int, Set<FileKind>>? = null
  private var estimateValue = StorageEstimate.Result(0, 0, 0)

  /** The storage estimate, recomputed only when the file count or the kinds being read change (the meter refreshes often). */
  private fun storageEstimate(store: IndexStore, filesKnown: Int, kinds: Set<FileKind>): StorageEstimate.Result = synchronized(this) {
    val key = filesKnown to kinds
    if (key != estimateKey) {
      estimateValue = runCatching { StorageEstimate.estimate(store.storageTotals(), kinds, withVectors = !Edition.GO) }.getOrDefault(estimateValue)
      estimateKey = key
    }
    estimateValue
  }

  @Volatile private var meterLine = ""
  @Volatile private var meterLoggedAt = 0L

  /** The meter in the log when it moves (at most every 5 s): how long until photos are searchable, read off a real phone. */
  private fun logMeter(s: IndexStatus) {
    val q = s.quickLook
    val line = "meter: known ${s.filesKnown}, searchable ${s.filesSearchable}, understood ${s.filesUnderstood}" +
      (q?.let { "; quick look ${it.ready}/${it.known}, full ${it.full}" } ?: "") +
      (s.reembed?.let { "; re-embed ${it.done}/${it.total}" } ?: "") +
      // last, so the scripts that read this line (d2_ready.py, landing_numbers.py, rc_qa.py) keep matching what comes before
      (if (s.filesUnreadable > 0) "; unreadable ${s.filesUnreadable}" else "")
    val now = System.currentTimeMillis()
    if (line == meterLine || now - meterLoggedAt < 5_000) return
    meterLine = line
    meterLoggedAt = now
    // Storage beside the counts, to check the estimate against a real index (not part of the dedupe key: it moves with every write).
    Log.i(TAG, "$line; storage: index ${s.indexBytes / 1024} KB (wal ${(storeOrNull()?.walBytes() ?: 0) / 1024} KB), estimate ${s.estimatedIndexBytes / 1024} KB, " +
      "search memory ${s.searchMemoryBytes / 1024} KB (estimate ${s.estimatedSearchMemoryBytes / 1024} KB)")
  }

  /** "Quick look: 2,800 of 3,000 · Full detail: 300 of 3,000" over photos, screenshots and videos; null when quick look can't run. */
  private fun quickLookProgress(stats: com.craneai.findr.index.IndexStats, kinds: Set<FileKind>): QuickLookProgress? {
    if (!settingsFlow.value.quickLook || !clip.available()) return null
    return stats.quickLookProgress(kinds.intersect(IndexStore.QUICK_KINDS))   // pure, in :core, so a unit test can hold the meter to reaching its end
  }

  /** The battery level in whole percent, or -1 when the phone does not say. */
  fun batteryPercent(): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) -1 else level * 100 / scale
  }

  /** Android's thermal status (0 none … 2 moderate … 6 shutdown). The A06 under-reports (it throttles without saying so); the worker also reads the battery's own temperature. */
  fun thermalStatus(): Int = runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrDefault(0)

  fun isCharging(): Boolean {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    return plugged != 0
  }

  // ---- settings persistence ----------------------------------------------------------------------------------

  /** The lite profile starts with speech to text off (SettingsCodec.decode, the research notes); standard phones keep D3's default. */
  private fun loadSettings(): FindrSettings {
    val saved = SettingsCodec.decode(prefs.getString(KEY_SETTINGS, null), lite = profile == MemoryProfile.LITE).forEdition()
    // A profiling build (never a shipped one) lets the study script pick the learning speed from the knob file, so a level can be
    // measured without tapping through Settings.
    val knob = if (com.craneai.findr.BuildConfig.FINDR_KNOBS) runCatching { Tuning.parse(File(KNOBS_FILE).readText())["learningSpeed"] }.getOrNull() else null
    return if (knob.isNullOrEmpty()) saved else saved.copy(learningSpeed = com.craneai.findr.core.LearningSpeed.of(knob) ?: saved.learningSpeed).forEdition()
  }

  private fun saveSettings(s: FindrSettings) {
    prefs.edit().putString(KEY_SETTINGS, SettingsCodec.encode(s)).apply()
  }

  /** Last, so every property above exists before the first callback of the open can run. */
  init { opener.start() }

  companion object {
    /**
     * What the words pass reads: cheap, no model. The hourly light crawl runs it on battery, so video and audio (which need the
     * model, and the charger) are not here, however recent they are.
     */
    val WORDS_PASS_KINDS = setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.SCREENSHOT, FileKind.IMAGE)
    private const val BACKOFF_S = 30L  // linear: a stopped worker is back within minutes, not hours
    private const val KEY_SETTINGS = "settings"
    private const val VECTORS_FILE = "vectors.bin"
    private const val QUICK_VECTORS_FILE = "vectors-quick.bin"
    private const val KEY_PAUSED = "paused"
    /** Debug builds only: "lite" or "standard" forces that memory profile at the next start; absent or "auto" chooses by RAM. */
    const val KEY_PROFILE_OVERRIDE = "memory_profile_override"
    /** A profiling build ([BuildConfig.FINDR_KNOBS]) reads Tuning overrides from here. */
    const val KNOBS_FILE = "/data/local/tmp/dp-knobs.txt"
    /** A profiling build runs the queries in this file (see [startQueryProbe]). */
    const val PROBE_QUERIES_FILE = "/data/local/tmp/lv-queries.txt"
    const val PROBE_LIMIT = 20   // one logcat line holds ~4 KB: 20 hits of ~100 bytes fit
    private const val YIELD_AFTER_MS = 1_500L
    /** At most one enqueue of the index work this often (ScheduleGate). */
    private const val SCHEDULE_GAP_MS = 10_000L
    /** While Findr is visible: how long MediaStore may stay busy before the in-process crawl, and how often to look for stalled work. */
    private const val FOREGROUND_DEBOUNCE_MS = 3_000L
    private const val FOREGROUND_CHECK_MS = 10_000L
    /** How long "Delete everything" waits for running work to stop before it deletes anyway. */
    private const val WIPE_WAIT_MS = 15_000L
  }
}

/** What the worker is doing right now, for the meter. */
data class WorkerProgress(
  val running: Boolean = false,
  val scanning: Boolean = false,
  val currentFile: String? = null,
  val pauseReason: PauseReason? = null,
  val lastError: String? = null,
)
