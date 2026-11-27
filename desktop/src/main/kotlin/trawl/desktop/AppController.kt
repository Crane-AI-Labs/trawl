package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.core.Locator
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.core.ModelPlan
import com.craneai.findr.engine.BundleClass
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SortOrder
import com.craneai.findr.core.DatePreset
import com.craneai.findr.engine.BundleResult
import com.craneai.findr.engine.IndexPause
import com.craneai.findr.index.IndexStore
import com.craneai.findr.core.PackIds
import java.util.concurrent.atomic.AtomicBoolean
import trawl.desktop.privacy.OrtPrivateScorer
import trawl.desktop.privacy.OsUnlocks
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.privacy.PrivacyIndex
import trawl.desktop.privacy.PrivacyPackFiles
import trawl.desktop.privacy.PrivacyPass
import trawl.desktop.privacy.PrivacyRuntime
import trawl.desktop.privacy.PrivacyStore
import trawl.desktop.privacy.StorePrivacyIndex
import java.io.File
import java.time.LocalDate
import java.net.URI
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { FIRST_RUN, MAIN, SETTINGS, LICENCES }

/** One line of the first-run folder list. */
data class FolderChoice(val path: String, val checked: Boolean, val exists: Boolean)

/** One result row, flattened from the phone's SearchHit so the window holds no engine types. */
data class ResultRow(
  val file: IndexedFile,
  val snippet: String?,
  val reason: MatchReason?,
  val page: Int?,
  /** False for the weaker matches that sit under the fold. */
  val strong: Boolean,
  /** Where in a video or recording the match is (ms), for the "at 1:23" badge. Null for every other kind. */
  val startMs: Long? = null,
)

data class ResultsUi(
  val query: String = "",
  val rows: List<ResultRow> = emptyList(),
  val tookMs: Long = 0,
  val indexIncomplete: Boolean = false,
  // ---- SEARCH PACKAGE (A): what the list was made for ----
  /** False while the words-only answer is shown and the meaning answer is still coming (the list may grow; the selection does not move). */
  val complete: Boolean = true,
  /** Sensitive files left out of Recent (the count the lock would open). */
  val hiddenCount: Int = 0,
  /** The order the list is in, and the date it is limited to. */
  val sort: SortOrder = SortOrder.BEST,
  val date: ActiveDate? = null,
  /** "Find similar" is showing this file's neighbours. */
  val similar: SimilarTo? = null,
  /** True for the Recent list (no words, no "Find similar"); a date phrase on its own is still a Recent list. */
  val browse: Boolean = query.isBlank() && similar == null,
  /** How far the computer's clock is behind its own files (ms), so an age never counts from a wrong "now". */
  val skewMs: Long = 0,
  /** The words searched (the query without a date phrase). */
  val words: String = query,
) {
  val browsing: Boolean get() = browse
}

/** What the filter bar shows about the question, ahead of the answer: sort, date, "Find similar". Changes the instant a key is typed or a chip is clicked. */
data class ViewUi(
  val sort: SortOrder = SortOrder.NEWEST,
  val date: ActiveDate? = null,
  val similar: SimilarTo? = null,
  val browse: Boolean = true,
)

/**
 * Snapshot captured right before entering "Find similar" so clearing the chip or pressing Backspace
 * restores the exact prior query, filter, subtype, selected file, and list scroll position.
 */
data class SimilarReturnState(
  val query: String,
  val filter: KindFilter,
  val subType: FileSubType?,
  val selectedId: Long?,
  val scrollIndex: Int,
  val scrollOffset: Int,
)

/** What Trawl keeps on disk and how many files of each kind it knows, for Settings. */
data class StorageUi(
  val indexBytes: Long = 0,
  val modelBytes: Long = 0,
  val cacheBytes: Long = 0,
  val photos: Int = 0,
  val screenshots: Int = 0,
  val documents: Int = 0,
  val videos: Int = 0,
  val recordings: Int = 0,
)

data class ModelUi(
  val installed: String? = null,
  /** A trawl-models zip found in Downloads, waiting to be installed. */
  val found: File? = null,
  val installing: Boolean = false,
  val done: Long = 0,
  val total: Long = 0,
  /** Already in the person's words: why it was refused, or that it worked. */
  val message: String? = null,
  val failed: Boolean = false,
  /** Name and modified time of [found], so an old refusal is cleared when a different file turns up. */
  val foundStamp: String? = null,
  /** F3: the zip that was just installed, still in the person's folder. Set only after a good install; cleared by "Delete it", "Keep it" or a new install. */
  val zipOffer: File? = null,
  /** The pack choice is asked on a computer that can hold either pack; a small one is always on Save data and is not asked. */
  val offered: Boolean = false,
  /** The pack in force: the person's answer, else what is installed, else Standard. */
  val choice: ModelChoice = ModelChoice.STANDARD,
  /** What the models on this computer are (null: none installed). */
  val installedChoice: ModelChoice? = null,
  /** The file the person is told to get for [choice]. */
  val fileName: String = "trawl-models.zip",
)

/**
 * Everything the window shows and does, as state and functions, with no UI types in it (so the same controller drives the window, the
 * demo and the tests). It owns the index service, the watcher and the settings file, and it is the only thing that talks to the OS
 * to open a file.
 */
class AppController(
  val dataDir: File,
  private val userFolders: Platform.UserFolders = Platform.userFolders(),
  logSink: (String) -> Unit = { System.err.println("[trawl] $it") },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  shredFile: (File) -> Long = com.craneai.findr.search.VectorFile::shred,
) : AutoCloseable {
  /** Every line the app writes about itself goes through here: made safe (no names, no paths), kept in logs/trawl.log, passed to [logSink]. */
  val appLog = AppLog(File(dataDir, "logs"), sink = logSink)
  private val log: (String) -> Unit = appLog::log
  private val settingsStore = SettingsStore(dataDir)
  private val modelSetup = ModelSetup(dataDir)
  val modelsDir = File(dataDir, "models")

  /** The still frame on a video's result row (B9): its own decoder process, so the list never waits behind the index worker. */
  val frames = FrameThumbs({ MediaDecoders.choose(dataDir) })

  /** Plays a stretch of a recording or video from a moment, for the previews (the main window's and the quick panel's share it, so only one thing sounds at a time). */
  val previewPlayer = PreviewPlayer({ MediaDecoders.choose(dataDir) })

  /**
   * Privacy (package B): the app lock, the sensitive marks and the blur. Built before the index so the index can ask it, at every search,
   * whether sensitive files may be included, and can hand it each index it opens.
   */
  val privacy = PrivacyController(PrivacyStore(dataDir), OsUnlocks.choose(dataDir))
  @Volatile private var privacyIndex: PrivacyIndex? = null
  @Volatile private var privacyStore: IndexStore? = null
  private val indexAccess = IndexAccess()
  private val _forgetting = MutableStateFlow(false)
  val forgetting: StateFlow<Boolean> = _forgetting.asStateFlow()
  private val passRunning = AtomicBoolean(false)
  @Volatile private var passStop = false
  @Volatile private var passThread: Thread? = null
  private var stopPrivacyWatch: () -> Unit = {}

  /** Goes up when something other than the query, the filter or the index changed what the list should show (a lock, a reveal, a mark, the pack). */
  private val refresh = MutableStateFlow(0)
  private fun refreshResults() { refresh.update { it + 1 } }

  /**
   * Add-ons (the Privacy pack): added from a file the person has, never downloaded. Removing the pack stops the scoring pass and clears
   * its scores first, so no stale blur remains; adding it (or an index that grows) starts the pass.
   */
  val addOns = AddOnManager(
    modelsDir = modelsDir,
    downloads = { listOf(userFolders.downloads, userFolders.documents) },
    choices = privacy,
    host = AddOnHost(
      releaseForRemoval = { id -> if (id == PackIds.PRIVACY) { stopPass(); privacyIndex?.clearLooksPrivate(); refreshResults() } },
      changed = { refreshResults(); startPass() },
    ),
    log = log,
  )

  val service = IndexService(
    dataDir, log, ocrChoice = { Ocrs.choose(dataDir) },
    onStoreOpened = ::bindPrivacy,
    includeSensitive = { privacy.revealed.value && !privacy.locked.value },
    access = indexAccess, shredFile = shredFile,
  )
  @Volatile private var skippedPaths: List<java.nio.file.Path> = emptyList()
  @Volatile private var skippedFilePaths: Set<java.nio.file.Path> = emptySet()
  private val watcher = FolderWatcher(log = log, ignore = { Scope.isSkipped(it, skippedPaths) || Scope.isSkippedFile(it, skippedFilePaths) }, onDirty = { service.reconcile(it) })
    .also { w -> service.onWalked = { dir, ms -> w.walked(dir, ms) } }
  init {
    // "Removed 4,213 files from skipped folders": the line the window shows when a skipped folder has taken files out of the index.
    service.onPurged = { n, _ -> say(tn("skipped_removed", n, java.text.NumberFormat.getIntegerInstance().format(n)), good = true) }
    service.onLargeFolder = ::onLargeFolderDetected
  }
  val ocrKind: String = Ocrs.kind()

  /** Crash reports and the problem report: counts and error classes, never names or contents. */
  val reporter: Reporter = Reporter.forDataDir(dataDir, appLog, ::facts, ::privacyFlags)

  /** "Start Trawl when I sign in": offered only from an installed copy (a plain `java` run has no launcher to register). Off until switched on; the system is the record. */
  val autostartAvailable: Boolean = Autostart.launcher() != null
  private val _autostart = MutableStateFlow(autostartAvailable && Autostart.isOn())
  val autostart: StateFlow<Boolean> = _autostart.asStateFlow()

  fun setAutostart(on: Boolean) {
    if (!autostartAvailable) return
    scope.launch(Dispatchers.IO) {
      val ok = Autostart.apply(on, Autostart.launcher())
      _autostart.value = Autostart.isOn()
      if (!ok) { log("start at sign-in: could not switch ${if (on) "on" else "off"}"); say(t("settings_startup_failed")) }
    }
  }

  private val _settings = MutableStateFlow(settingsStore.load())
  val settings: StateFlow<Settings> = _settings.asStateFlow()

  private val _screen = MutableStateFlow(if (_settings.value.onboarded) Screen.MAIN else Screen.FIRST_RUN)
  val screen: StateFlow<Screen> = _screen.asStateFlow()

  private val _choices = MutableStateFlow(userFolders.all.map { FolderChoice(it.absolutePath, it.isDirectory, it.isDirectory) })
  val choices: StateFlow<List<FolderChoice>> = _choices.asStateFlow()

  private val _query = MutableStateFlow("")
  val query: StateFlow<String> = _query.asStateFlow()
  private val _filter = MutableStateFlow(KindFilter.ALL)
  val filter: StateFlow<KindFilter> = _filter.asStateFlow()
  private val _subType = MutableStateFlow<FileSubType?>(null)
  val subType: StateFlow<FileSubType?> = _subType.asStateFlow()
  private val _results = MutableStateFlow(ResultsUi())
  val results: StateFlow<ResultsUi> = _results.asStateFlow()

  val index: StateFlow<IndexUi> = service.ui

  private val _model = MutableStateFlow(ModelUi())
  val model: StateFlow<ModelUi> = _model.asStateFlow()

  private val _watch = MutableStateFlow(WatchInfo())
  val watch: StateFlow<WatchInfo> = _watch.asStateFlow()

  private val largeFolderLock = Any()
  private val largeFolderQueue = ArrayDeque<LargeFolderAlert>()
  private val largeFolderSeenThisRun = HashSet<String>()
  private val _largeFolderAlert = MutableStateFlow<LargeFolderAlert?>(null)
  /** Active large-folder warning banner for the main window (one at a time, persisted once answered so we never nag twice). */
  val largeFolderAlert: StateFlow<LargeFolderAlert?> = _largeFolderAlert.asStateFlow()
  @Volatile internal var notifyLargeFolder: (title: String, body: String) -> Unit = { title, body ->
    scope.launch(Dispatchers.IO) { OsNotifier.notify(title, body) }
  }

  private val _notice = MutableStateFlow<String?>(null)
  val notice: StateFlow<String?> = _notice.asStateFlow()
  /** True when the notice is good news ("Copied"), so it is drawn with the check and not the warning. */
  private val _noticeGood = MutableStateFlow(false)
  val noticeGood: StateFlow<Boolean> = _noticeGood.asStateFlow()

  private val _folderCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
  val folderCounts: StateFlow<Map<String, Int>> = _folderCounts.asStateFlow()

  private val _storage = MutableStateFlow(StorageUi())
  val storage: StateFlow<StorageUi> = _storage.asStateFlow()

  // ---- SEARCH PACKAGE (A): the search state. It sits above `init` because `init` starts the query loop on another thread, which reads these. ----
  /** The one search the window and the quick panel both ask (the panel passes `withSensitive = false`: it can never list sensitive files). */
  val searchApi = SearchApi(service.searchHooks)
  /** The opening text of [f] for the preview when no match is quoted (Recent); null when it has none. Runs off the UI thread. */
  suspend fun leadText(f: com.craneai.findr.core.IndexedFile): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { searchApi.leadText(f.id) }.getOrNull() }
  /** The phone's rules for sort and date, run by the same core code (see [SearchSession]); the window only sets the chips. */
  val session = SearchSession(searchApi)
  private val _view = MutableStateFlow(ViewUi())
  val view: StateFlow<ViewUi> = _view.asStateFlow()
  /** Bumped by every chip change, so the question is asked again with the new view. */
  private val viewTick = MutableStateFlow(0)

  init {
    applyLanguage(_settings.value.language)
    refreshModel()
    if (_settings.value.onboarded) startIndexing()
    scope.launch { runQueries() }
    scope.launch { privacy.locked.collect { if (it) { previewPlayer.stop(); searchApi.clearQueryCache() } } }
    scope.launch { keepFolderCounts() }
    scope.launch { keepStorage() }
    scope.launch { watchModels() }
    scope.launch { keepPrivacyFresh() }
    scope.launch(Dispatchers.IO) { runCatching { privacy.refreshOs() } }
    stopPrivacyWatch = privacy.startWatcher()
  }

  // ---- privacy -------------------------------------------------------------------------------------------------

  /** Runs on the index's opener thread with each index it opens: the saved marks are applied to it, and the scoring pass looks for work. */
  private fun bindPrivacy(store: IndexStore) {
    store.applyRules(privacy.settings.value.rules)
    privacyStore = store
    val ix = StorePrivacyIndex(store, indexAccess, available = { privacyStore === store })
    privacyIndex = ix
    privacy.bind(ix)
    startPass()
  }

  /** The Privacy pack is on this computer and its engine can run here. */
  fun privacyPackReady(): Boolean = addOns.states.value[PackIds.PRIVACY] is AddOnState.Installed && PrivacyRuntime.usable()

  /** The "looks private" pass (picture scores for the blur): one background thread at a time, only with the pack, and only where its engine runs. */
  private fun startPass() {
    if (_forgetting.value) return
    val ix = privacyIndex ?: return
    if (!privacyPackReady() || !passRunning.compareAndSet(false, true)) return
    passStop = false
    val pass = PrivacyPass(ix, OrtPrivateScorer(PrivacyPackFiles.modelFile(modelsDir)), stopped = { passStop })
    passThread = pass.runInBackground { n ->
      if (pass.modelFailed) log("privacy pass: the model would not run")
      else if (n > 0) log("privacy pass: scored $n pictures")
      passRunning.set(false)
      if (n > 0) refreshResults()
    }
  }

  private fun stopPass(waitForExit: Boolean = false) {
    passStop = true
    passThread?.let { if (waitForExit) it.join() else runCatching { it.join(5_000) } }
  }

  /** A new batch of files gives the scoring pass something to look at. */
  private suspend fun keepPrivacyFresh() {
    service.ui.map { it.generation }.distinctUntilChanged().collectLatest { delay(3_000); startPass() }
  }

  /**
   * For the screenshot driver only (Demo.kt, switched on by TRAWL_DEMO_DIR): gives the picture called [name] a made-up "looks private" score, so the
   * blur can be photographed without any private picture. The scorer's real scores come from [startPass]; this never runs for a person.
   */
  fun demoLooksPrivate(name: String, score: Float): Boolean {
    val f = service.search(name, KindFilter.ALL, 5, withSensitive = true).hits.firstOrNull { it.file.name == name }?.file ?: return false
    privacyIndex?.saveLooksPrivate(f.id, score) ?: return false
    refreshResults()
    return true
  }

  /** Mark or unmark files as sensitive and bring the list up to date. */
  fun markSensitive(ids: List<Long>, on: Boolean): Int = privacy.mark(ids, on).also { refreshResults() }

  /** What a report says about privacy: settings as on/off words and counts, never names. */
  private fun privacyFlags(): List<String> {
    val s = privacy.settings.value
    return listOf(
      "app lock: ${if (s.lockEnabled) "on" else "off"}", "blur: ${if (s.blurEnabled) "on" else "off"}",
      "privacy pack: ${if (addOns.states.value[PackIds.PRIVACY] is AddOnState.Installed) "added" else "not added"}${if (PrivacyRuntime.usable()) "" else " (cannot run here)"}",
      "marked files: ${s.rules.markedFiles.size}", "private folders: ${s.rules.privateFolders.size}",
    )
  }

  // ---- search --------------------------------------------------------------------------------------------------

  /** Last monotonic time the query text changed: background generation refreshes wait out active typing so indexing never interrupts keystrokes. */
  @Volatile private var lastTypedAtNs: Long = System.nanoTime() - TYPING_QUIET_MS * 1_000_000L

  fun setQuery(q: String) {
    lastTypedAtNs = System.nanoTime()
    if (q.isNotBlank() && session.similar != null) {
      beforeSimilar = null
      _pendingSimilarRestore.value = null
    }
    session.queryChanged(q)
    _query.value = q
    refreshView(q)
  }

  fun setFilter(f: KindFilter) {
    val next = if (f.kinds.any { it in enabledKinds() } || f == KindFilter.ALL) f else KindFilter.ALL
    if (next != _filter.value) _subType.value = _subType.value?.takeIf { next in it.filters }
    _filter.value = next
  }

  fun setSubType(sub: FileSubType?) {
    _subType.value = sub?.takeIf { _filter.value in it.filters }
    viewTick.value++
  }

  /** The kinds Trawl reads now, per "What to read". */
  fun enabledKinds(): Set<FileKind> = Scope.enabledKinds(_settings.value)

  // ---- SEARCH PACKAGE (A): sort, date, find similar -------------------------------------------------------------


  private fun refreshView(q: String = _query.value) {
    val d = session.derive(q.trim())
    _view.value = ViewUi(d.sort, d.date, session.similar, d.browse)
  }

  private fun viewChanged() { refreshView(); viewTick.value++ }

  fun setSort(order: SortOrder) { session.setSort(order, _query.value.trim()); viewChanged() }
  fun setDatePreset(p: DatePreset) { session.setDatePreset(p); viewChanged() }
  fun setDateRange(from: LocalDate, to: LocalDate) { session.setDateRange(from, to); viewChanged() }
  fun clearDate() { session.clearDate(_query.value.trim()); viewChanged() }

  /** Lists the files whose meaning vectors sit closest to [f]'s across all indexed modalities. */
  fun canFindSimilar(f: IndexedFile): Boolean = f.kind in SearchApi.SIMILAR_KINDS

  /** True when the picture "Find similar" started from is blurred or sensitive: its name is then not written on the chip or the heading (privacy). */
  @Volatile private var similarNameHidden = false
  @Volatile private var beforeSimilar: SimilarReturnState? = null
  private val _pendingSimilarRestore = MutableStateFlow<SimilarReturnState?>(null)
  /** Non-null after [clearSimilar] restores prior query/filter until the UI restores selection and scroll position. */
  val pendingSimilarRestore: StateFlow<SimilarReturnState?> = _pendingSimilarRestore.asStateFlow()
  /** Registered by [MainScreen] to snapshot `(selectedId, firstVisibleItemIndex, firstVisibleItemScrollOffset)` right before entering Find similar. */
  @Volatile internal var captureViewport: () -> Triple<Long?, Int, Int> = { Triple(null, 0, 0) }

  fun findSimilar(
    f: IndexedFile,
    nameHidden: Boolean = false,
    selectedId: Long? = null,
    scrollIndex: Int? = null,
    scrollOffset: Int? = null,
  ) {
    if (!canFindSimilar(f)) return
    if (session.similar == null) {
      val (capSel, capIdx, capOff) = runCatching { captureViewport() }.getOrDefault(Triple(null, 0, 0))
      beforeSimilar = SimilarReturnState(
        query = _query.value,
        filter = _filter.value,
        subType = _subType.value,
        selectedId = selectedId ?: capSel ?: f.id,
        scrollIndex = (scrollIndex ?: capIdx).coerceAtLeast(0),
        scrollOffset = (scrollOffset ?: capOff).coerceAtLeast(0),
      )
    }
    _pendingSimilarRestore.value = null
    similarNameHidden = nameHidden || f.sensitive
    session.findSimilar(f)
    _query.value = ""
    viewChanged()
  }

  fun clearSimilar() {
    val wasSimilar = session.similar != null
    val snap = beforeSimilar
    beforeSimilar = null
    session.clearSimilar()
    if (wasSimilar && snap != null) {
      setFilter(snap.filter)
      _subType.value = snap.subType?.takeIf { _filter.value in it.filters }
      session.queryChanged(snap.query)
      _query.value = snap.query
      _pendingSimilarRestore.value = snap
    }
    viewChanged()
  }

  fun consumeSimilarRestore(state: SimilarReturnState) {
    if (_pendingSimilarRestore.value == state) _pendingSimilarRestore.value = null
  }

  /** A request for the index, without the generation: the same request asked again after the index grew is a refresh (it must not flash). */
  private data class Asked(val query: String, val filter: KindFilter, val tick: Int, val privacy: Triple<Boolean, Boolean, Int>)

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  private suspend fun runQueries() {
    // Typing is answered after a short pause. While browsing Recent, index updates refresh at most every 900 ms; while a non-blank
    // query is open, background refreshes throttle to 2.5 s and wait out active typing so indexing never cancels a search or spins the fan.
    var firstGen = true
    val generation = service.ui.map { it.generation }.distinctUntilChanged().conflate().transform { g ->
      if (!firstGen) {
        while (_query.value.isNotBlank()) {
          val elapsedMs = ((System.nanoTime() - lastTypedAtNs).coerceAtLeast(0L)) / 1_000_000L
          val wait = (TYPING_QUIET_MS - elapsedMs).coerceIn(0L, TYPING_QUIET_MS)
          if (wait <= 0L) break
          delay(wait)
        }
      }
      firstGen = false
      emit(g)
      delay(if (_query.value.isBlank()) GEN_BROWSE_REFRESH_MS else GEN_QUERY_REFRESH_MS)
    }
    var last: Asked? = null
    // Locking, showing the sensitive files and marking one all re-ask the same question (privacy package): they are part of the request.
    val privacyView = combine(privacy.locked, privacy.revealed, refresh) { l, r, n -> Triple(l, r, n) }
    combine(_query.debounce(120), _filter, viewTick, generation, privacyView) { q, f, tick, _, pv -> Asked(q.trim(), f, tick, pv) }.collectLatest { asked ->
      // Locked: no results at all. The window shows only the lock screen, and nothing is looked up behind it (the shell's quick panel asks the same rule).
      if (privacy.locked.value) { searchApi.clearQueryCache(); last = asked; _results.value = ResultsUi(asked.query); return@collectLatest }
      val previous = last
      last = asked
      val again = asked == previous
      // A word being typed holds the meaning answer back for a moment (the next key cancels it); a click answers at once.
      val typing = previous != null && previous.query != asked.query
      // (The screenshot run can hold the meaning back for seconds so the words-first state can be photographed: TRAWL_DEMO_MEANING_DELAY_MS.)
      val delayMs = DEMO_MEANING_DELAY_MS?.takeIf { asked.query.isNotEmpty() } ?: if (typing) MEANING_DELAY_MS else 0L
      try {
        session.load(asked.query, asked.filter, delayMs, quiet = again) { a -> _results.value = toUi(a) }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        // Never let a single query failure kill the search collector coroutine permanently.
        log("search failed: ${t.javaClass.simpleName}")
      }
    }
  }

  private fun toUi(a: SessionAnswer): ResultsUi {
    val d = a.derived
    val sub = _subType.value
    val notReady = !service.ui.value.let { it.phase == Phase.UP_TO_DATE || it.phase == Phase.NOT_STARTED }
    a.recent?.let { r ->
      val files = if (sub != null) r.files.filter { sub.matches(it.name) } else r.files
      return ResultsUi(
        query = _query.value.trim(), rows = files.map { ResultRow(it, null, null, null, true) }, tookMs = 0, indexIncomplete = notReady,
        hiddenCount = r.hidden, sort = d.sort, date = d.date, browse = true, skewMs = a.skewMs, words = "",
      )
    }
    val r = a.hits ?: return ResultsUi()
    val allRows = r.hits.mapIndexed { i, h ->
      ResultRow(h.file, h.snippet, h.reason, (h.locator as? Locator.Page)?.page, i < r.strongCount, (h.locator as? Locator.Time)?.startMs)
    }
    val rows = if (sub != null) allRows.filter { sub.matches(it.file.name) } else allRows
    val seed = a.similar
    return ResultsUi(
      query = if (seed != null) "" else _query.value.trim(), rows = rows, tookMs = r.tookMs, indexIncomplete = r.indexIncomplete, complete = r.complete,
      hiddenCount = r.hiddenCount, sort = d.sort, date = d.date, similar = seed?.let { if (similarNameHidden) it.copy(name = t("hidden_tile")) else it }, browse = false, skewMs = a.skewMs, words = d.text,
    )
  }

  // ---- folders and first run -----------------------------------------------------------------------------------

  fun toggleChoice(path: String) = _choices.update { l -> l.map { if (it.path == path && it.exists) it.copy(checked = !it.checked) else it } }

  fun addChoice(dir: File) = _choices.update { l ->
    val p = dir.absolutePath
    if (l.any { it.path == p }) l.map { if (it.path == p) it.copy(checked = true) else it } else l + FolderChoice(p, true, dir.isDirectory)
  }

  fun start() {
    val picked = _choices.value.filter { it.checked && it.exists }.map { it.path }
    if (picked.isEmpty()) return
    save(_settings.value.copy(onboarded = true, folders = picked))
    _screen.value = Screen.MAIN
    startIndexing()
  }

  fun addFolder(dir: File) {
    val p = dir.absoluteFile.normalize().path
    if (!dir.isDirectory || _settings.value.folders.any { sameFolder(it, p) }) return
    save(_settings.value.copy(folders = _settings.value.folders + p))
    startIndexing()
  }

  /**
   * Adds individual [files] to Trawl's index without crawling sibling files in their parent folders.
   * Re-adding a previously excluded file cleanly removes it from [Settings.excludedFiles].
   */
  fun addFiles(files: List<File>) {
    val valid = files.filter { it.isFile }.mapNotNull { SafePath.of(it)?.toString() }.distinct()
    if (valid.isEmpty()) return
    val s = _settings.value
    val nextExcluded = s.excludedFiles.filterNot { ex -> valid.any { sameFolder(ex, it) } }
    val nextAdded = Scope.withAddedFiles(s.addedFiles, valid)
    if (nextExcluded == s.excludedFiles && nextAdded == s.addedFiles) return
    save(s.copy(addedFiles = nextAdded, excludedFiles = nextExcluded))
    startIndexing()
  }

  /** Removes an individually added file from Settings and Trawl's index without ever touching the file on disk. */
  fun removeAddedFile(path: String) {
    val norm = SafePath.of(path)?.toString() ?: path
    val s = _settings.value
    val nextAdded = s.addedFiles.filterNot { sameFolder(it, norm) }
    if (nextAdded == s.addedFiles) return
    save(s.copy(addedFiles = nextAdded))
    startIndexing()
  }

  fun removeFolder(path: String) {
    val folders = _settings.value.folders.filterNot { it == path }
    save(
      _settings.value.copy(
        folders = folders,
        excludedFolders = Scope.pruneSkipped(_settings.value.excludedFolders, folders),
        excludedFiles = Scope.pruneSkippedFiles(_settings.value.excludedFiles, folders),
      ),
    )
    startIndexing()
  }

  // ---- what to read, skipped folders, speed, pause --------------------------------------------------------------

  fun setIndexPhotos(on: Boolean) = changeScope { it.copy(indexPhotos = on) }
  fun setIndexScreenshots(on: Boolean) = changeScope { it.copy(indexScreenshots = on) }
  fun setIndexDocuments(on: Boolean) = changeScope { it.copy(indexDocuments = on) }
  fun setIndexVideos(on: Boolean) = changeScope { it.copy(indexVideos = on) }
  fun setIndexAudio(on: Boolean) = changeScope { it.copy(indexAudio = on) }

  /** Stops reading [dir] (inside a chosen folder): its files leave the index now and the watcher ignores it. Returns why not, or null when done. */
  fun skipFolder(dir: File): Scope.SkipProblem? {
    val problem = Scope.skipProblem(dir, _settings.value.folders)
    if (problem != null) return problem
    changeScope { it.copy(excludedFolders = Scope.withSkipped(it.excludedFolders, dir.absolutePath)) }
    watcher.watch(_settings.value.folders.map(::File))   // folders that are skipped now need no watch of their own
    _watch.value = watcher.info
    return null
  }

  fun unskipFolder(path: String) {
    changeScope { it.copy(excludedFolders = it.excludedFolders.filterNot { p -> p == path }) }
    watcher.watch(_settings.value.folders.map(::File))
    _watch.value = watcher.info
  }

  internal fun onLargeFolderDetected(alert: LargeFolderAlert) {
    val norm = SafePath.of(alert.path)?.toString() ?: alert.path
    val s = _settings.value
    if (s.excludedFolders.any { sameFolder(it, norm) } || s.dismissedLargeFolders.any { sameFolder(it, norm) }) return
    val p = SafePath.of(norm)
    if (p != null && Scope.isSkipped(p, skippedPaths)) return
    val clean = alert.copy(path = norm)
    val shouldNotify = synchronized(largeFolderLock) {
      if (!largeFolderSeenThisRun.add(norm)) return@synchronized false
      if (_largeFolderAlert.value == null) _largeFolderAlert.value = clean else largeFolderQueue.addLast(clean)
      true
    }
    if (shouldNotify) {
      val body = t(
        "large_folder_notify",
        clean.name,
        com.craneai.findr.ui.common.formatCount(clean.fileCount),
        com.craneai.findr.ui.common.formatBytes(clean.totalBytes),
      )
      runCatching { notifyLargeFolder(t("app_name"), body) }
    }
  }

  /** One-click "Exclude folder" from the large-folder banner: excludes & purges the folder and remembers not to nag again. */
  fun excludeLargeFolder(alert: LargeFolderAlert) {
    rememberDismissedLargeFolder(alert.path)
    skipFolder(File(alert.path))
    advanceLargeFolderAlert(alert.path)
  }

  /** "Keep reading" from the large-folder banner: dismisses and persists so Trawl never nags about this folder again. */
  fun keepReadingLargeFolder(alert: LargeFolderAlert) {
    rememberDismissedLargeFolder(alert.path)
    advanceLargeFolderAlert(alert.path)
  }

  private fun rememberDismissedLargeFolder(path: String) {
    val norm = SafePath.of(path)?.toString() ?: path
    if (_settings.value.dismissedLargeFolders.any { sameFolder(it, norm) }) return
    save(_settings.value.copy(dismissedLargeFolders = _settings.value.dismissedLargeFolders + norm))
  }

  private fun advanceLargeFolderAlert(path: String) {
    val norm = SafePath.of(path)?.toString() ?: path
    synchronized(largeFolderLock) {
      largeFolderQueue.removeAll { sameFolder(it.path, norm) }
      if (_largeFolderAlert.value?.let { sameFolder(it.path, norm) } == true) {
        _largeFolderAlert.value = largeFolderQueue.removeFirstOrNull()
      }
    }
  }

  fun setSpeed(s: LearningSpeed) = changeScope { it.copy(learningSpeed = s) }

  /**
   * "Read on battery anyway": lifts the low-battery wait for this stay on the battery (every speed), and moves Gentle, which waits for
   * the charger whatever the charge, to Balanced as the button has always done.
   */
  fun readOnBattery() {
    service.readOnBattery()
    if (_settings.value.learningSpeed == LearningSpeed.GENTLE) setSpeed(LearningSpeed.BALANCED)
  }

  /** "Run anyway" on a heat or power-saving wait: reading goes on for the rest of this run of the app, at the speed the person chose. */
  fun runAnyway(hold: Hold) = service.runAnyway(hold)

  /** The shell tells the index what the windows are doing (see [IndexService.windows]). */
  fun windowShown(visible: Boolean) = service.windows(mainVisible = visible)
  fun windowFocused(focused: Boolean) = service.windows(mainFocused = focused)
  fun panelShown(open: Boolean) = service.windows(panelOpen = open)

  /** The Pause / Resume button. Only Resume clears the pause; a restart keeps it (the phone's rule, [IndexPause]). */
  fun setPaused(paused: Boolean) = changeScope { it.copy(indexPaused = IndexPause.after(if (paused) IndexPause.Event.PAUSE else IndexPause.Event.RESUME, it.indexPaused)) }

  private fun changeScope(change: (Settings) -> Settings) {
    save(change(_settings.value))
    pushConfig()
    if (!enabledKinds().let { on -> _filter.value.kinds.any { it in on } || _filter.value == KindFilter.ALL }) _filter.value = KindFilter.ALL
  }

  /**
   * Removes [files] from Trawl's local index (rows, FTS words, and vectors) and adds their paths to
   * [Settings.excludedFiles] so future scans never re-index them. NEVER deletes or modifies any user file on disk.
   */
  fun removeFromIndex(files: List<IndexedFile>) {
    if (files.isEmpty()) return
    previewPlayer.stop()
    val ids = files.map { it.id }.distinct()
    val paths = files.mapNotNull { runCatching { fileOf(it).absolutePath }.getOrNull() }.distinct()
    var addedChanged = false
    if (paths.isNotEmpty()) {
      val s0 = _settings.value
      val nextAdded = s0.addedFiles.filterNot { af -> paths.any { sameFolder(af, it) } }
      addedChanged = nextAdded != s0.addedFiles
      val updatedExcluded = paths.fold(s0.excludedFiles) { acc, p -> Scope.withSkippedFile(acc, p) }
      save(s0.copy(addedFiles = nextAdded, excludedFiles = updatedExcluded))
    }
    val s = _settings.value
    skippedFilePaths = Scope.paths(s.excludedFiles).toSet()
    service.removeFromIndex(ids)
    pushConfig()
    if (addedChanged) service.setFolders(s.folders.map(::File), s.addedFiles.map(::File))
    refreshResults()
    say(tn("removed_from_index", ids.size, com.craneai.findr.ui.common.formatCount(ids.size)), good = true)
  }

  /** Re-includes a previously removed file so Trawl indexes it again (Settings -> Skipped files). */
  fun restoreExcludedFile(path: String) {
    val norm = SafePath.of(path)?.toString() ?: path
    changeScope { it.copy(excludedFiles = it.excludedFiles.filterNot { p -> sameFolder(p, norm) }) }
    refreshResults()
  }

  private fun pushConfig() {
    val s = _settings.value
    skippedPaths = Scope.paths(s.excludedFolders)
    skippedFilePaths = Scope.paths(s.excludedFiles).toSet()
    service.configure(Scope.enabledKinds(s), skippedPaths, s.learningSpeed, s.indexPaused, skippedFilePaths)
  }

  private fun sameFolder(a: String, b: String) = (SafePath.of(a) ?: a) == (SafePath.of(b) ?: b)

  fun rescan() = service.rescan()

  private val _forgot = MutableStateFlow<Forgot?>(null)
  /** E21: what the last "Forget everything" let go of, for the note under the button. Not kept across runs. */
  val forgot: StateFlow<Forgot?> = _forgot.asStateFlow()

  fun forgetEverything() {
    if (!_forgetting.compareAndSet(false, true)) return
    _forgot.value = null
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      withContext(Dispatchers.IO + NonCancellable) {
        try {
          stopPass(waitForExit = true)
          privacyStore = null
          privacyIndex = null
          searchApi.clearQueryCache()
          _results.value = ResultsUi(_query.value, indexIncomplete = true)
          val before = service.forgetAll()
          runCatching { reporter.wipe() }
          _forgot.value = before
        } catch (failure: Exception) {
          log("forgetting failed: ${failure.javaClass.simpleName}")
          say(t("settings_forget_failed"))
        } finally {
          _forgetting.value = false
          startPass()
          refreshResults()
        }
      }
    }
  }

  private fun startIndexing() {
    pushConfig()
    val folders = _settings.value.folders.map(::File)
    val added = _settings.value.addedFiles.map(::File)
    service.setFolders(folders, added)
    watcher.watch(folders)
    _watch.value = watcher.info
    modelSetup.installedModel()?.let { service.attachModel(it) }
  }

  // ---- models --------------------------------------------------------------------------------------------------

  fun refreshModel() {
    val choice = modelSetup.effectiveChoice(_settings.value.modelChoice)
    val installedChoice = modelSetup.installedChoice()
    // A bundle in Downloads is offered while no model is installed, and, on Save data, for the way up (a Standard file only).
    val found = when {
      modelSetup.installedModel() == null -> modelSetup.findBundle(listOf(userFolders.downloads), prefer = choice)
      modelSetup.choiceOffered && installedChoice == ModelChoice.SAVE_DATA -> modelSetup.findBundle(listOf(userFolders.downloads), standardOnly = true)
      else -> null
    }
    val label = modelSetup.installedLabel()
    val stamp = found?.let { "${it.path}@${it.lastModified()}" }
    _model.update { cur ->
      // A refusal belongs to the file that was refused. When the models change (a different bundle, none, or one installed), the old
      // line is stale and goes; while an install runs, its own progress is the line.
      val stale = cur.failed && !cur.installing && (stamp != cur.foundStamp || label != cur.installed)
      cur.copy(
        installed = label, found = found, foundStamp = stamp, message = if (stale) null else cur.message, failed = cur.failed && !stale,
        offered = modelSetup.choiceOffered, choice = choice, installedChoice = installedChoice, fileName = ModelSetup.archiveName(modelSetup.hardware, choice),
      )
    }
  }

  /**
   * The person's pack choice (first run, and Settings before anything is installed). It only decides which file they are told to
   * get and which one is picked up from Downloads; the models are checked and installed exactly as before. A small computer is not asked.
   */
  fun setModelChoice(choice: ModelChoice) {
    if (!modelSetup.choiceOffered || _model.value.installing) return
    save(_settings.value.copy(modelChoice = choice))
    refreshModel()
  }

  /** While a screen that shows the models is open, notice a bundle that is added or taken away (Downloads is looked at every two seconds). */
  private suspend fun watchModels() {
    while (true) {
      delay(2_000)
      if (_screen.value == Screen.SETTINGS || _screen.value == Screen.FIRST_RUN) {
        if (!_model.value.installing) withContext(Dispatchers.IO) { refreshModel() }
      }
    }
  }

  /** Verifies [zip] with the phone's installer (signature first, then every file) and installs it; the engine picks the model up when it is in. */
  fun installModels(zip: File) {
    if (_model.value.installing) return
    _model.update { it.copy(installing = true, done = 0, total = zip.length(), message = null, failed = false, zipOffer = null) }
    scope.launch(Dispatchers.IO) {
      var last = 0L
      val before = modelSetup.installedClass()
      val result = try {
        modelSetup.install(zip, progress = { d, tot ->
          val now = System.currentTimeMillis()
          if (now - last > 200 || d == tot) { last = now; _model.update { it.copy(done = d, total = tot) } }
        })
      } catch (t: Throwable) {
        log("install failed: ${t.javaClass.simpleName} ${t.message}")
        null
      }
      when (result) {
        is BundleResult.Installed -> {
          // What is installed is what the person has: it becomes their answer, so the file on the computer and the choice agree.
          modelSetup.installedChoice()?.let { save(_settings.value.copy(modelChoice = it)) }
          markInstalled(zip)
          // Save data to Standard: the better model reads the files again (the index stays; words inside documents and screenshots
          // come back as each file is read). Say so.
          if (ModelPlan.upgradesStack(before, modelSetup.installedClass() ?: BundleClass.LITE)) _model.update { it.copy(message = t("models_upgraded")) }
          modelSetup.installedModel()?.let { service.attachModel(it) }
          refreshModel()
        }
        is BundleResult.Refused -> _model.update { it.copy(installing = false, message = t(ModelSetup.refusalKey(result.reason, modelSetup.profile)), failed = true) }
        null -> _model.update { it.copy(installing = false, message = t("refusal_damaged"), failed = true) }
      }
    }
  }

  fun declineModels() = save(_settings.value.copy(modelsDeclined = true))

  /** A good install of [zip]: the panel says so and asks whether to delete the zip (F3). */
  internal fun markInstalled(zip: File) {
    _model.update { it.copy(installing = false, installed = modelSetup.installedLabel(), found = null, foundStamp = null, message = t("first_models_ready"), failed = false, zipOffer = ModelZipOffer.forZip(zip, modelsDir)) }
  }

  /** F3, "Keep it": the zip stays where it is and the question goes away. */
  fun keepModelZip() = _model.update { it.copy(zipOffer = null) }

  /** F3, "Delete it": removes the installed zip the person was asked about (their own file, in their own folder), and says what was freed. */
  fun deleteModelZip() {
    val zip = _model.value.zipOffer ?: return
    scope.launch(Dispatchers.IO) {
      val msg = ModelZipOffer.delete(zip)
      _model.update { it.copy(zipOffer = null, message = msg.first, failed = !msg.second) }
    }
  }

  // ---- settings ------------------------------------------------------------------------------------------------

  fun openSettings() {
    _screen.value = Screen.SETTINGS
    refreshModel()
    scope.launch(Dispatchers.IO) { addOns.refresh() }
  }
  fun closeSettings() { _screen.value = Screen.MAIN }
  fun openLicences() { _screen.value = Screen.LICENCES }
  fun closeLicences() { _screen.value = Screen.SETTINGS }
  fun setTheme(c: ThemeChoice) = save(_settings.value.copy(theme = c))
  fun setTextSize(size: TextSize) = save(_settings.value.copy(textSize = size))
  fun setContrast(c: ContrastChoice) = save(_settings.value.copy(contrast = c))

  /** Cmd/Ctrl + Plus, Minus and 0: one step bigger or smaller (or back to Default), then a notice that says where it landed, since the keys are silent. */
  fun zoom(step: Int) {
    val now = _settings.value.textSize
    val next = when { step > 0 -> now.bigger(); step < 0 -> now.smaller(); else -> TextSize.DEFAULT }
    if (next != now) setTextSize(next)
    say(t("text_size_now", t("text_size_${next.id}")), good = true)
  }

  /** A language tag from [Languages], or null to follow the computer. Takes effect at once: the window is drawn again with the new words. */
  fun setLanguage(tag: String?) {
    save(_settings.value.copy(language = tag))
    applyLanguage(tag)
  }

  private fun applyLanguage(tag: String?) = Strings.use(if (tag == null) java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(tag))

  private fun save(s: Settings) {
    _settings.value = s
    runCatching { settingsStore.save(s) }.onFailure { log("settings not saved: ${it.message}") }
  }

  private suspend fun keepFolderCounts() {
    combine(_settings, service.ui.map { it.generation }.distinctUntilChanged()) { s, _ -> s.folders }.collectLatest { folders ->
      delay(700)
      _folderCounts.value = withContext(Dispatchers.Default) { folders.associateWith { service.countUnder(File(it)) } }
    }
  }

  private suspend fun keepStorage() {
    combine(service.ui.map { it.generation }.distinctUntilChanged(), _screen, _model.map { it.installed }.distinctUntilChanged()) { _, s, _ -> s }.collectLatest { screen ->
      if (screen != Screen.SETTINGS) return@collectLatest
      delay(300)
      _storage.value = withContext(Dispatchers.IO) { readStorage() }
    }
  }

  private fun readStorage(): StorageUi {
    val by = service.ui.value.byKind
    fun size(f: File) = runCatching { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    return StorageUi(
      indexBytes = service.indexBytes(), modelBytes = modelSetup.bytesOnDisk(), cacheBytes = size(File(dataDir, "engine-cache")),
      photos = by[FileKind.IMAGE] ?: 0, screenshots = by[FileKind.SCREENSHOT] ?: 0, documents = (by[FileKind.DOCUMENT] ?: 0) + (by[FileKind.PDF] ?: 0),
      videos = by[FileKind.VIDEO] ?: 0, recordings = by[FileKind.AUDIO] ?: 0,
    )
  }

  // ---- report a problem ----------------------------------------------------------------------------------------

  /** The text behind "Copy diagnostics". See [Diagnostics]: counts, sizes, versions and error classes; no names, paths or contents. */
  fun diagnostics(): String = Diagnostics.report(facts())

  private fun facts(): Diagnostics.Facts {
    val s = _settings.value
    val ix = service.ui.value
    val by = ix.byKind
    return run {
      Diagnostics.Facts(
        os = System.getProperty("os.name") ?: "unknown", osVersion = System.getProperty("os.version") ?: "", arch = System.getProperty("os.arch") ?: "",
        cores = Runtime.getRuntime().availableProcessors(), ramMb = Platform.totalRamMb(), ocr = service.ocrName ?: ocrKind, vision = service.visionBackend,
        textThreads = service.textThreads, speed = s.learningSpeed.key, language = s.language ?: "system (${Strings.language})",
        folders = s.folders.size, skippedFolders = s.excludedFolders.size, photosOn = s.indexPhotos, screenshotsOn = s.indexScreenshots, documentsOn = s.indexDocuments,
        videosOn = s.indexVideos, audioOn = s.indexAudio, videos = by[FileKind.VIDEO] ?: 0, recordings = by[FileKind.AUDIO] ?: 0,
        decoder = ix.mediaDecoder, speechPack = ix.speechPack,
        photos = by[FileKind.IMAGE] ?: 0, screenshots = by[FileKind.SCREENSHOT] ?: 0, documents = (by[FileKind.DOCUMENT] ?: 0) + (by[FileKind.PDF] ?: 0),
        unreadable = ix.unreadable, indexBytes = service.indexBytes(), modelBytes = modelSetup.bytesOnDisk(), ratePerMin = ix.ratePerMin,
        models = modelSetup.installedLabel(), errors = appLog.recentErrors(),
      )
    }
  }

  /** Puts [diagnostics] on the clipboard. False where the computer has no clipboard to reach (a server with no screen). */
  fun copyDiagnostics(): Boolean {
    val ok = runCatching {
      val text = java.awt.datatransfer.StringSelection(diagnostics())
      java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(text, null)
    }.isSuccess
    say(if (ok) t("settings_report_copied") else t("settings_report_copy_failed"), good = ok)
    return ok
  }

  fun openLogFolder() {
    scope.launch(Dispatchers.IO) {
      appLog.dir.mkdirs()
      if (!Platform.open(appLog.dir)) say(t("settings_report_logs_failed"))
    }
  }

  /** Manual zero-network update check: opens Trawl's website in the user's external browser (zero sockets inside Trawl, D43). */
  fun checkForUpdates() {
    scope.launch(Dispatchers.IO) {
      if (!Platform.openBrowser(AppInfo.UPDATES_URL)) say(t("settings_check_updates_failed", AppInfo.UPDATES_URL))
    }
  }

  // ---- files ---------------------------------------------------------------------------------------------------

  fun fileOf(f: IndexedFile): File = Paths.get(URI(f.uri)).toFile()

  fun open(f: IndexedFile) {
    previewPlayer.stop()
    scope.launch(Dispatchers.IO) { if (!Platform.open(fileOf(f))) say(t("open_failed", f.name)) }
  }

  fun showInFolder(f: IndexedFile) {
    scope.launch(Dispatchers.IO) { if (!Platform.showInFolder(fileOf(f))) say(t("show_failed", f.name)) }
  }

  /** Puts the file's full path on the clipboard. False where the computer has no clipboard to reach (a server with no screen). */
  fun copyPath(f: IndexedFile): Boolean {
    val ok = Clip.copyText(fileOf(f).path)
    say(if (ok) t("path_copied") else t("path_copy_failed"), good = ok)
    return ok
  }

  // ---- SEARCH PACKAGE (A): copy the files, share, show several ---------------------------------------------------
  // Every way to copy or share a result goes through [copyFiles] and [share], so one hook (the privacy package's "this one is sensitive,
  // really copy it?" question, audit B7) covers the menu, the button, Ctrl+C and the batch bar alike.

  /** Copies the files themselves to the clipboard (paste into a mail, a chat, a folder); their paths as text where the computer cannot, and the notice says which. */
  fun copyFiles(files: List<IndexedFile>) {
    val some = files.take(Clip.LIMIT)
    if (some.isEmpty()) return
    scope.launch(Dispatchers.IO) {
      val n = some.size
      when (Clip.copyFiles(some.map(::fileOf))) {
        CopyOutcome.FILES -> say(tn("copied_files", n, n.toString()), good = true)
        CopyOutcome.PATHS -> say(tn("copied_paths", n, n.toString()), good = true)
        CopyOutcome.FAILED -> say(t("path_copy_failed"))
      }
      if (files.size > Clip.LIMIT) say(t("select_copy_limit", Clip.LIMIT))
    }
  }

  /** Whether this computer has a share sheet to offer; the Share entries are not drawn where it is false. */
  val canShare: Boolean get() = Share.available

  fun share(files: List<IndexedFile>) {
    if (!Share.available || files.isEmpty()) return
    scope.launch(Dispatchers.IO) { if (!Share.share(files.map(::fileOf))) say(t("share_failed")) }
  }

  /** Shows the files in their folders: one window per folder (the first chosen file in it is selected), at most [SHOW_FOLDERS_MAX] so a big selection cannot open a wall of windows. */
  fun showInFolders(files: List<IndexedFile>) {
    val byFolder = files.map(::fileOf).groupBy { it.parentFile?.path.orEmpty() }
    scope.launch(Dispatchers.IO) {
      byFolder.values.take(SHOW_FOLDERS_MAX).forEach { group -> if (!Platform.showInFolder(group.first())) say(t("show_failed", group.first().name)) }
      if (byFolder.size > SHOW_FOLDERS_MAX) say(t("select_show_limit", SHOW_FOLDERS_MAX))
    }
  }

  fun say(message: String, good: Boolean = false) {
    _noticeGood.value = good
    _notice.value = message
    scope.launch { delay(6000); _notice.compareAndSet(message, null) }
  }

  fun dismissNotice() { _notice.value = null }

  val dataFolder: String get() = dataDir.absolutePath

  private companion object {
    /** How long a word being typed holds back the meaning answer; the words answer is on screen before it. */
    const val MEANING_DELAY_MS = 180L
    /** Background index generation refresh cadence while browsing Recent (no embedding) vs. while a search query is open. */
    const val GEN_BROWSE_REFRESH_MS = 900L
    const val GEN_QUERY_REFRESH_MS = 2_500L
    /** Wait this long after the last keystroke before letting a background indexing tick re-run an open query. */
    const val TYPING_QUIET_MS = 1_500L
    private val DEMO_MEANING_DELAY_MS: Long? = System.getenv("TRAWL_DEMO_MEANING_DELAY_MS")?.toLongOrNull()
    const val SHOW_FOLDERS_MAX = 6
  }

  override fun close() {
    passStop = true
    runCatching { previewPlayer.stop() }
    stopPrivacyWatch()
    scope.cancel()
    watcher.close()
    runCatching { frames.close() }
    service.close()
  }
}
