package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.StorageEstimate
import com.craneai.findr.engine.MediaShare
import com.craneai.findr.engine.QueryLanguage
import com.craneai.findr.engine.SpeechLanguageGate
import com.craneai.findr.engine.StoreNotReadyException
import com.craneai.findr.engine.StoreOpener
import com.craneai.findr.engine.WordWeightPolicy
import com.craneai.findr.engine.WordsPassLoop
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexOpenProblems
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.PendingFile
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

enum class Phase { NOT_STARTED, SCANNING, INDEXING, UP_TO_DATE, PAUSED, ERROR }

/**
 * Why reading is on hold: the person pressed Pause, the Gentle speed is waiting for the charger, the disk is nearly full
 * ([DiskSpace]), or the system will not let Trawl list the chosen folders ([FolderAccess]).
 */
enum class Hold { USER, BATTERY, STORAGE_LOW, NO_PERMISSION }

/** What the indexing meter shows. Plain numbers; the window turns them into words. */
data class IndexUi(
  val phase: Phase = Phase.NOT_STARTED,
  /** Files found so far by the walk in progress. */
  val scanned: Int = 0,
  /** Files kept (documents, PDFs, photos, screenshots, and the videos and recordings when this computer can decode them) and how many of them are read already. */
  val total: Int = 0,
  val understood: Int = 0,
  val photos: Int = 0,
  val documents: Int = 0,
  val videos: Int = 0,
  val recordings: Int = 0,
  val current: String? = null,
  val etaMs: Long? = null,
  val modelReady: Boolean = false,
  val modelLoading: Boolean = false,
  val error: String? = null,
  /** Goes up whenever files were added, read or removed: the window runs the open query again. */
  val generation: Int = 0,
  /** Set while [Phase.PAUSED]. */
  val hold: Hold? = null,
  /** Files in the index per kind (not counting the ones left out), whatever the "What to read" switches say. */
  val byKind: Map<FileKind, Int> = emptyMap(),
  /** Files read per minute this session, measured over the time spent reading (not idle time). Null until a few files are done. */
  val ratePerMin: Double? = null,
  /** Files that could not be read at all (damaged, or a format with no reader). */
  val unreadable: Int = 0,
  /**
   * True while the words are being read with no model (documents' text, the text in pictures): [understood] and [total] then count
   * files whose words are in. Search by name and words works as they arrive; the meaning starts when the models are installed.
   */
  val readingWords: Boolean = false,
  /**
   * What this computer reads videos and sound with: "avfoundation", "media-foundation", "ffmpeg", or "none" when it has no decoder (Linux
   * without ffmpeg). Null until the first check. With "none" the videos and recordings are left out of the meter, not counted as waiting.
   */
  val mediaDecoder: String? = null,
  /** Videos and recordings that wait only because this computer has no decoder (Linux without ffmpeg): the meter then says so instead of "All caught up". */
  val mediaNeedDecoder: Int = 0,
  /** The speech pack (Parakeet and the voice detector) is installed, so recordings and soundtracks give words. */
  val speechPack: Boolean = false,
  /** Files whose words are in (the pale level of the meter): every file kept, less the ones the words pass has not reached. */
  val wordsIn: Int = 0,
  /** The index could not be opened (busy, disk full, unreadable...) and Trawl is trying again by itself; null when it is open. */
  val openProblem: IndexOpenProblem? = null,
  /** The index is being opened for the first time this run (not a failure). */
  val opening: Boolean = true,
  /** Files read with an older model that are being read again: (done, total), or null. */
  val reembed: Pair<Int, Int>? = null,
  /** How much room is left for the index: see [DiskSpace]. [freeBytes] is null when the system would not say. */
  val disk: DiskSpace.Level = DiskSpace.Level.OK,
  val freeBytes: Long? = null,
  /** A20: the engine failed its self-test even with a fresh cache, so meaning search is off for a few minutes and words and names still work. */
  val repairing: Boolean = false,
  /** Folders the last walks could not look inside (counts and kinds of place only, never names); nothing under them is deleted. */
  val blocked: BlockedSummary = BlockedSummary.NONE,
)

/**
 * The index of the desktop app, in one object: the phone's IndexStore, VectorIndex and Searcher, the walk that fills them, the
 * reader that understands the files, and the answers to a typed query. One worker thread does all the writing, so the window never
 * waits on it. Everything arrives as a job: set the folders, reconcile a directory the watcher named, attach the models.
 *
 * Deleting is the careful part. A file is dropped from the index only when a walk of its folder finished without an error and the
 * folder itself is still there; an unplugged drive or a folder the person has no access to makes nothing disappear.
 */
class IndexService(
  private val dataDir: File,
  private val log: (String) -> Unit = {},
  private val ocrChoice: () -> Ocr? = { null },
  /** The decoder for videos and sound; null when this computer has none. Asked again every half minute while it is null (ffmpeg may get installed). */
  private val decoderChoice: () -> MediaDecoder? = { MediaDecoders.choose(dataDir) },
  /** Opens the index (blocking). Tests pass one that fails first. It calls its argument at each transient failure so the window can say so at once. */
  private val openStore: (onTransient: (Throwable) -> Unit) -> IndexStore = { onRetry -> IndexStore.open(File(dataDir, "index"), log = log, onRetry = onRetry) },
  /** The wait before an index that would not open is tried again, doubling up to a minute. [retryOpen] skips the wait. */
  private val openBackoffMs: Long = StoreOpener.FIRST_BACKOFF_MS,
  /** Free bytes on the drive that holds the index; tests pass numbers. */
  private val freeSpace: (File) -> Long? = DiskSpace::freeBytes,
  private val diskCheckMs: Long = DISK_CHECK_MS,
  /** Reading steps aside while a query is being asked ([SearchYield]). */
  private val searchYield: SearchYield = SearchYield(),
  /** Whether the system lets Trawl list a chosen folder; tests pass a fake. */
  private val canList: (Path) -> Boolean = FolderAccess::canList,
  /** Called with each index that opens (the first, and any reopen), before anyone can search it: the privacy code binds its marks and scoring to it. */
  private val onStoreOpened: (IndexStore) -> Unit = {},
  /**
   * Whether results include the files marked sensitive: true only while the person has unlocked and chosen to show them. Asked at every
   * search, so locking takes effect on the next query. Default false: a sensitive file is never in a result unless something says so.
   */
  private val includeSensitive: () -> Boolean = { false },
) : AutoCloseable {
  /**
   * The index opens in the background and is tried again until it opens ([StoreOpener], the phone's own state machine). A busy or
   * unreadable file used to end the app at start; now the window shows a calm card ([IndexUi.openProblem]) and the worker waits.
   */
  private val openScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val opener = StoreOpener<IndexStore>(
    scope = openScope, ioDispatcher = Dispatchers.IO,
    open = openStore,
    classify = IndexOpenProblems::classify,
    prepare = ::prepareStore,
    onFailed = { problem, n -> log("the index is not open ($problem, failed opens: $n); trying again") },
    close = { runCatching { it.close() } },
    log = log,
    firstBackoffMs = openBackoffMs, maxBackoffMs = maxOf(openBackoffMs, StoreOpener.MAX_BACKOFF_MS),
  )
  /** The open index; throws [StoreNotReadyException] while it is opening or failing. The worker waits for it; the window's calls use [opener] and answer "nothing yet". */
  private val store: IndexStore get() = opener.current ?: throw StoreNotReadyException()
  private val vectors = VectorIndex(parallelism = 4)
  /** The kinds the person has switched on, the folders they skip, how hard to work and whether they paused. Set by [configure]. */
  @Volatile private var kinds: Set<FileKind> = Crawler.KINDS
  @Volatile private var skipped: List<Path> = emptyList()
  @Volatile private var paused = false
  @Volatile private var speed = LearningSpeed.BALANCED
  private var appliedSkipped: List<Path> = emptyList()
  private val battery = BatteryWatch()
  @Volatile private var searcher: Searcher? = null
  private val ctx = ClasspathAssets
  private val speechGate = SpeechLanguageGate.fromAssets(ctx)
  private val weights = WordWeightPolicy.fromAssets(ctx, QueryLanguage(speechGate.words)) { modelName }

  @Volatile private var embedder: DesktopEmbedder? = null
  @Volatile private var modelName: String? = null
  @Volatile private var modelBytes: Long? = null
  private var indexer: Indexer? = null
  private var ocr: Ocr? = null
  private var ocrTried = false
  /** The core's words pass, run on the worker with no model: one at a time, as on the phone. */
  private val wordsLock = Mutex()
  @Volatile private var wordsReader: WordsReader? = null
  private var wordsSessionDone = 0
  private var wordsNs = 0L

  // ---- videos and recordings (worker thread; the @Volatile flags are read by [publish] from other threads) ----
  @Volatile private var decoder: MediaDecoder? = null
  @Volatile private var decoderCheckedAt = 0L
  private val asr = DesktopAsr(File(File(dataDir, "models"), ModelSetup.ASR_DIR), threads = { asrThreads(speed) })
  private var speechWas: Boolean? = null
  @Volatile private var speechInstalled = false
  private var mediaIndexer: MediaIndexer? = null
  /** The phone's fair share: videos and recordings get about a fifth of the busy time while photos and documents are waiting. */
  private val mediaShare = MediaShare(MediaSchedule.MEDIA_SHARE)
  private var mediaDone = 0
  private var mediaNs = 0L

  private val _ui = MutableStateFlow(IndexUi())
  val ui: StateFlow<IndexUi> = _ui.asStateFlow()

  private sealed interface Job
  private class SetRoots(val roots: List<File>) : Job
  /** "Look at what the watcher named": the folders themselves wait in [dirtyPending], so any number of reports make at most one job. */
  private data object Reconcile : Job
  private class Attach(val model: File) : Job
  private data object Rescan : Job
  private class Skip(val skipped: List<Path>) : Job
  private class Speed(val speed: LearningSpeed) : Job

  private val queue = LinkedBlockingQueue<Job>()
  @Volatile private var closed = false
  @Volatile private var roots: List<Path> = emptyList()
  @Volatile private var scanning = false
  @Volatile private var scannedSoFar = 0
  private var sessionDone = 0
  private var sessionStarted = 0L
  private var readNs = 0L
  private var generation = 0
  private val worker = Thread(::loop, "trawl-index").apply { isDaemon = true }

  init {
    opener.start()
    worker.start()
  }

  /** Runs on the opener's thread before the index is shown to anyone: the vectors, the clean-up after an update, the readers that need it. */
  private fun prepareStore(st: IndexStore) {
    vectors.load(st)
    // A video the last run died inside twice is given up on (or its window skipped); the first death is only counted.
    st.recoverInflight()?.let { log("recovered from a stop inside a file: step ${it.step}${if (it.skippedWindow) ", window skipped" else ""}") }
    IndexAdmin.rereadNewFormats(st, log)   // files the first build knew by name only go back in the queue, once
    IndexAdmin.dropFalseTransportStreams(st, log, onDropped = { vectors.removeFiles(it) })   // TypeScript an older build took for videos leaves the index, once
    searcher = Searcher(st, vectors, indexedKinds = { kinds })
    wordsReader = WordsReader(st) { ensureOcr() }
    onStoreOpened(st)
  }

  /** Try opening the index again now rather than at the end of the wait (the calm card's Retry button). */
  fun retryOpen() = opener.retryNow()

  // ---- what the window asks ------------------------------------------------------------------------------------

  fun setFolders(folders: List<File>) { queue.add(SetRoots(folders)) }
  private val dirtyPending = java.util.concurrent.ConcurrentHashMap<Path, Boolean>()
  private val reconcileQueued = java.util.concurrent.atomic.AtomicBoolean(false)

  /** The earliest time the next watcher-driven walk may start: a walk that took long leaves the worker alone for a while after it ([ReconcilePacing]). */
  @Volatile private var reconcileNotBefore = 0L
  /** A [Reconcile] job came while the pacing said wait: the loop runs it when the time is up (the folders keep merging into [dirtyPending] meanwhile). */
  @Volatile private var reconcileDeferred = false

  /**
   * The watcher names folders that changed. They are noted at once and walked later: while paused they are only noted (a Pause that went
   * on walking a whole tree every few seconds was the problem it was meant to end), and after a long walk the next one waits a while.
   */
  fun reconcile(dirs: List<Dirty>) {
    if (dirs.isEmpty()) return
    // Folders named again while one job waits are merged into it (a folder named twice is walked once; a deep one is told apart from a shallow one).
    for (d in dirs) dirtyPending.merge(d.dir, d.recursive) { a, b -> a || b }
    if (!paused) wakeReconcile()
  }

  /** Queues one [Reconcile] for what is noted, if there is something noted and no job waiting already. */
  private fun wakeReconcile() {
    if (dirtyPending.isNotEmpty() && reconcileQueued.compareAndSet(false, true)) queue.add(Reconcile)
  }
  fun rescan() { decoderCheckedAt = 0L; queue.add(Rescan) }
  fun attachModel(file: File) { queue.add(Attach(file)) }

  /**
   * What the person chose in Settings. The kinds and the pause apply at once (the next file, the next query); a change to the skipped
   * folders removes what is now skipped from the index, or walks the folders that are readable again; a change of speed rebuilds the
   * engine with its new thread count. Call it before [setFolders] at start-up.
   */
  fun configure(kinds: Set<FileKind>, skipped: List<Path>, speed: LearningSpeed, paused: Boolean) {
    val kindsChanged = this.kinds != kinds
    this.kinds = kinds.intersect(Crawler.KINDS)
    val resumed = this.paused && !paused
    this.paused = paused
    this.skipped = skipped
    if (this.speed != speed || !speedApplied) { this.speed = speed; queue.add(Speed(speed)) }
    queue.add(Skip(skipped))
    if (kindsChanged) bump()
    if (resumed) wakeReconcile()   // what changed while paused is looked at now, in one walk
  }

  @Volatile private var speedApplied = false
  @Volatile private var lastAsked: Pair<String, KindFilter>? = null

  /** What the person typed, through the phone's own ranking. With no model the query has no vector and only the words count. */
  fun search(query: String, filter: KindFilter, limit: Int = 80, withSensitive: Boolean = includeSensitive()): SearchResults {
    // Reading steps aside for the length of a search the person asked for. The window asks the same question again each time the index
    // grows (at most once a second); that is a refresh, not the person, and must not hold the reading back.
    val asked = query to filter
    if (asked != lastAsked) { lastAsked = asked; searchYield.touch() }
    val searcher = searcher ?: return SearchResults(query, emptyList(), 0, 0L, indexIncomplete = true)   // the index is still opening
    val e = embedder
    val qv = if (e != null && query.isNotBlank()) e.query(query) else null
    val on = kinds
    // A kind that is switched off is out of results even though its files stay in the index (so switching it on again is instant).
    // Ask for more than [limit] when something is off, so the hits that are dropped do not leave the list short.
    val ask = if (on.size < Crawler.KINDS.size) limit * 3 else limit
    val r = searcher.search(query, qv, filter, ask, prefixLast = true, includeSensitive = withSensitive, wordWeight = weights.meaningWeight(query, qv))
    if (on.size >= Crawler.KINDS.size) return r
    val kept = r.hits.withIndex().filter { it.value.file.kind in on }
    val strong = kept.count { it.index < r.strongCount }
    return r.copy(hits = kept.map { it.value }.take(limit), strongCount = strong.coerceAtMost(limit))
  }

  // ---- SEARCH PACKAGE (A): the one hook the progressive search, sort, dates and similar read the service through ------------------
  /** What [SearchApi] needs from this service, as functions (no ranking here: that is the core's). */
  val searchHooks: SearchHooks by lazy {
    SearchHooks(
      store = { opener.current }, searcher = { searcher }, modelReady = { embedder != null }, vectorCount = { vectors.size },
      embed = { q -> embedder?.query(q) }, wordsWeight = { q -> weights.wordsWeight(q) }, meaningWeight = { q, v -> weights.meaningWeight(q, v) },
      kinds = { kinds }, includeSensitive = includeSensitive,
      touch ={ q, f -> val asked = q to f; if (asked != lastAsked) { lastAsked = asked; searchYield.touch() } },
    )
  }
  // ---- end SEARCH PACKAGE (A) ------------------------------------------------------------------------------------------------------

  /** Newest files, for the window before anything is typed. */
  fun recent(filter: KindFilter, limit: Int = 60, withSensitive: Boolean = includeSensitive()): List<IndexedFile> =
    opener.current?.recent(filter.kinds.intersect(kinds), limit, includeSensitive = withSensitive) ?: emptyList()

  /** How many files a folder holds in the index, for Settings. */
  fun countUnder(folder: File): Int {
    val store = opener.current ?: return 0
    val prefix = prefixOf(SafePath.of(folder) ?: return 0)
    return store.read { db -> db.long("SELECT COUNT(*) FROM files WHERE volume = ? AND state != 'skipped' AND uri >= ? AND uri < ?", Crawler.VOLUME, prefix, prefix + "￿") ?: 0L }.toInt()
  }

  fun indexBytes(): Long = opener.current?.bytesOnDisk() ?: 0L

  /** The detail panel's numbers (counts per kind, what waits, what failed and why, sizes), or null while the index is not open. Cheap enough to ask when the panel opens and when [IndexUi.generation] moves. */
  fun detail(): IndexAdmin.Detail? =
    opener.current?.let { IndexAdmin.detail(it, kinds, modelBytes, embedder != null) }

  /** Reads again the files that could not be read (damaged ones stay damaged and fail again quickly). Returns how many went back in the queue. */
  fun retryUnreadable(): Int {
    val n = opener.current?.retryFailed() ?: 0
    if (n > 0) bump()
    return n
  }

  /** For the problem report: the picture engine in use ("gpu" or "cpu"), the text thread count (null = the engine's own) and the OCR engine. */
  val visionBackend: String? get() = embedder?.visionUsed
  val textThreads: Int? get() = embedder?.threadCount
  val ocrName: String? get() = ocr?.name

  /** Forgets everything learned (the index file is emptied); the folders stay and are walked again. */
  fun forgetAll() {
    if (opener.current == null) return
    val all = ArrayList<Long>()
    store.read { db -> db.query("SELECT id FROM files WHERE volume = ?", Crawler.VOLUME) { all += it.getLong(0) } }
    drop(all)
    rescan()
  }

  override fun close() {
    closed = true
    worker.interrupt()
    runCatching { worker.join(5000) }
    runCatching { asr.close() }
    runCatching { decoder?.close() }
    runCatching { ocr?.close() }
    runCatching { embedder?.close() }
    runCatching { runBlocking { opener.detach() }?.close() }   // waits for an open in flight, so a store that opened is never left behind
    openScope.cancel()
  }

  // ---- the worker ----------------------------------------------------------------------------------------------

  private fun loop() {
    while (!closed) {
      try {
        if (opener.current == null) { showOpening(); Thread.sleep(OPEN_POLL_MS); continue }   // jobs wait in the queue until there is an index to put them in
        if (_ui.value.openProblem != null || _ui.value.opening) bump()   // it just opened: take the card down, and let an open query run again
        refreshAccess()
        refreshDisk()
        val job = queue.poll()
        if (job != null) { handle(job); continue }
        if (reconcileDeferred && !paused && System.currentTimeMillis() >= reconcileNotBefore) { reconcileDeferred = false; handle(Reconcile); continue }
        if (scans.isNotEmpty()) { stepScans(); continue }   // a walk that gave way carries on where it stopped
        retryRepair()
        if (readSome()) continue
        scanning = false
        publish(current = null)
        queue.poll(1, TimeUnit.SECONDS)?.let { handle(it) }
      } catch (_: InterruptedException) {
        return
      } catch (t: Throwable) {
        log("index worker: ${t.javaClass.simpleName} ${t.message}")
        _ui.update { it.copy(phase = Phase.ERROR, error = t.message ?: t.javaClass.simpleName) }
        try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }
  }

  private fun handle(job: Job) {
    when (job) {
      is SetRoots -> {
        roots = job.roots.mapNotNull { SafePath.of(it) }
        dropOutsideRoots()
        if (blocked.keys.removeAll { b -> roots.none { b.startsWith(it) } }) { blockedSummary = BlockedSummary.of(blocked, home) }
        scanAll()
      }
      is Rescan -> scanAll()
      is Skip -> applySkipped(job.skipped)
      is Speed -> applySpeed(job.speed)
      is Reconcile -> {
        if (paused) {   // on hold: the folders stay noted in dirtyPending and are walked once, when the person resumes
          reconcileDeferred = false; reconcileQueued.set(false)
          return
        }
        if (System.currentTimeMillis() < reconcileNotBefore) { reconcileDeferred = true; return }   // too soon after a long walk; reconcileQueued stays set, so reports just merge
        reconcileDeferred = false
        reconcileQueued.set(false)   // anything named from now on makes a new job
        val startedAt = System.currentTimeMillis()
        val named = dirtyPending.entries.map { it.key to it.value }
        named.forEach { dirtyPending.remove(it.first) }
        // A folder walked all the way down already covers the folders named under it.
        val deep = named.filter { it.second }.map { it.first }
        for ((dir, recursive) in named) {
          if (deep.any { it != dir && dir.startsWith(it) }) continue
          val root = roots.firstOrNull { dir.startsWith(it) } ?: continue
          if (Scope.isSkipped(dir, skipped)) continue   // inside a skipped folder: nothing there is read
          if (Crawler.insideIgnored(root, dir)) continue  // a ".git", a ".cache" or a "node_modules" is never read, however busy it is
          reconcile(root, dir, recursive)
        }
        val now = System.currentTimeMillis()
        reconcileNotBefore = ReconcilePacing.notBefore(now, now - startedAt)
      }
      is Attach -> attach(job.model)
    }
  }

  /** Takes what is newly skipped out of the index and walks what is readable again. Runs on the worker, in the order the person clicked. */
  private fun applySkipped(now: List<Path>) {
    val before = appliedSkipped
    appliedSkipped = now
    val added = now.filter { n -> before.none { n.startsWith(it) } }
    for (a in added) dropUnder(a)
    val freed = before.filter { o -> now.none { o.startsWith(it) } }
    for (f in freed) {
      val root = roots.firstOrNull { f.startsWith(it) } ?: continue
      if (Files.isDirectory(f)) walkNow(root, f, recursive = true) { }
    }
    if (added.isNotEmpty() || freed.isNotEmpty()) bump()
  }

  private fun applySpeed(s: LearningSpeed) {
    speedApplied = true
    val e = embedder ?: return
    if (e.useThreads(WorkSpeed.threads(s))) {
      runCatching { e.warm() }.onFailure { log("speed ${s.key}: engine did not restart: ${it.javaClass.simpleName} ${it.message}") }
      log("work speed ${s.key}: ${WorkSpeed.threads(s) ?: "default"} threads")
    }
    sessionDone = 0; sessionStarted = System.currentTimeMillis(); readNs = 0L
  }

  /** A20: when meaning search may be tried again after a failed self-test (0 = not waiting), and the model it was for. */
  @Volatile private var repairAt = 0L
  @Volatile private var repairModel: File? = null
  private val engineProbes by lazy { EngineProbe.fromClasspath() }

  /** The worker's idle turn: a model that failed its self-test is tried again once the wait is over. */
  private fun retryRepair() {
    val f = repairModel ?: return
    if (repairAt == 0L || System.currentTimeMillis() < repairAt) return
    repairAt = 0L
    attach(f)
  }

  private fun attach(file: File) {
    if (modelName == file.name && embedder != null) return
    _ui.update { it.copy(modelLoading = true, error = null) }
    val cache = File(dataDir, "engine-cache")
    val loaded = try {
      EngineGuard.load(
        file.name, engineProbes,
        create = { DesktopEmbedder(file, cache).also { it.useThreads(WorkSpeed.threads(speed)); it.warm() } },
        embed = { e, text -> e.raw(text) }, close = { it.close() },
        clearCache = { EngineGuard.clearCache(cache, file.name) }, log = { log(it) },
      )
    } catch (t: Throwable) {
      log("model ${file.name}: ${t.javaClass.simpleName} ${t.message}")
      _ui.update { it.copy(modelLoading = false, error = "model: ${t.message ?: t.javaClass.simpleName}") }
      return
    }
    val e = when (loaded) {
      is EngineLoad.Ready -> loaded.engine
      is EngineLoad.Repaired -> loaded.engine
      is EngineLoad.Unhealthy -> {
        // Not used: no vectors are made or searched with a bad engine. Words and names carry on, and it is tried again later.
        repairModel = file; repairAt = System.currentTimeMillis() + EngineGuard.RETRY_MS
        log("model ${file.name}: the engine failed its self-test (${loaded.reason}); words only, trying again in ${EngineGuard.RETRY_MS / 60_000} min")
        _ui.update { it.copy(modelLoading = false, repairing = true) }
        bump()
        return
      }
    }
    repairModel = null; repairAt = 0L
    if (_ui.value.repairing) _ui.update { it.copy(repairing = false) }
    runCatching { embedder?.close() }
    ensureOcr()
    embedder = e
    modelName = file.name
    modelBytes = file.length()
    // The same rule as the phone: a changed model file means the old vectors are another space and are read again.
    if (store.ensureModelFingerprint("desktop:${file.name}:${file.length()}")) vectors.load(store)
    indexer = Indexer(store, vectors, e, ocr)
    buildMedia()
    sessionDone = 0; sessionStarted = System.currentTimeMillis(); readNs = 0L
    log("model ${file.name} ready, init ${e.initMs} ms, vision ${e.visionUsed}, ocr ${ocr?.name ?: "none"}")
    _ui.update { it.copy(modelLoading = false, modelReady = true) }
    bump()
  }

  /** The text reader of this computer, started when first needed (by the words pass or the models) and kept: it is the same engine either way. */
  @Synchronized private fun ensureOcr(): Ocr? {
    if (!ocrTried) {
      ocrTried = true
      ocr = runCatching { ocrChoice() }.onFailure { log("ocr: ${it.javaClass.simpleName}") }.getOrNull()
    }
    return ocr
  }

  /** Walks that have started and not finished: the chosen folders first, in order, then any bigger reconcile that had to give way. Worker thread only. */
  private val scans = java.util.ArrayDeque<Scan>()

  /** Starts the walk of every chosen folder (a walk already half done is replaced: the person asked for a new one). */
  private fun scanAll() {
    scans.clear()
    scanning = true; scannedSoFar = 0
    for (r in roots) {
      if (!Files.isDirectory(r)) { log("scan: a folder is not there; keeping what is known about it"); continue }
      scans.add(Scan(r, r, recursive = true) { n -> scannedSoFar += n; publish(current = null) })
    }
    stepScans()
  }

  /**
   * Runs the waiting walks one after the other. A walk gives way to a more urgent job (a new folder, a click in Settings, the models
   * arriving) between two folders and is kept as it is, so it carries on from the next folder, not from the start.
   */
  private fun stepScans() {
    while (!closed) {
      val s = scans.peekFirst() ?: break
      if (!s.run()) return
      scans.pollFirst()
      s.finish()
    }
    if (scans.isEmpty() && scanning) { scanning = false; bump() }
  }

  /** A job other than a watcher report is waiting, or the app is closing: a walk stops before its next folder. */
  private fun mustYield(): Boolean = closed || (yieldHook?.invoke() ?: queue.any { it !is Reconcile })

  /** Tests only: replaces the question "is a more urgent job waiting?", to make a walk give way as often as they like. */
  @Volatile internal var yieldHook: (() -> Boolean)? = null

  private fun reconcile(root: Path, dir: Path, recursive: Boolean) {
    if (!Files.isDirectory(root)) return   // the whole folder is unreachable (an unplugged drive): conclude nothing
    if (!Files.isDirectory(dir)) {
      // The directory is gone: so is everything under it.
      removeUnder(dir, recursive = true, present = emptySet())
      bump(); return
    }
    walkNow(root, dir, recursive) { }
    bump()
  }

  /** Walks [dir] now; if a more urgent job asks for the worker half-way, the rest waits in [scans] and the loop carries on with it later. */
  private fun walkNow(root: Path, dir: Path, recursive: Boolean, progress: (Int) -> Unit) {
    val s = Scan(root, dir, recursive, progress)
    if (s.run()) s.finish() else { scans.add(s); scanning = true }
  }

  /**
   * One walk of a folder, kept as a value so that it can stop between two folders and carry on later. It remembers the files it has
   * seen ([present]) and the places it could not look at ([unreadable]); the folders still to enter are [todo].
   */
  private inner class Scan(val root: Path, val dir: Path, val recursive: Boolean, val progress: (Int) -> Unit) {
    val present = HashSet<String>()
    val unreadable = ArrayList<UnreadablePlace>()
    var vanished = 0; private set
    private val todo = java.util.ArrayDeque<Path>().also { it.add(dir) }
    private val batch = ArrayList<CrawlRecord>()

    private fun flush() {
      if (batch.isEmpty()) return
      val r = store.upsertFromCrawl(batch)
      vectors.removeFiles(r.reset + r.newlySkipped)
      progress(batch.size)
      batch.clear()
    }

    /** Walks until nothing is left (true), or until a more urgent job is waiting (false; call again to carry on from the next folder). */
    fun run(): Boolean {
      var first = true
      while (todo.isNotEmpty()) {
        if (!first && mustYield()) break   // the first folder of every call is entered whatever happens, so a call always makes progress
        val d = todo.pollFirst()
        first = false
        val result = Crawler.walk(root, d, recursive || d != dir, skipped, stop = ::mustYield) { f ->
          val rec = f.record()
          present += rec.uri
          batch += rec
          if (batch.size >= 500) flush()
        }
        unreadable += result.unreadable; vanished += result.vanished
        result.pending.asReversed().forEach { todo.addFirst(it) }
        if (result.pending.isNotEmpty()) break
      }
      flush()
      return todo.isEmpty()
    }

    /**
     * Concludes from a walk that has seen everything it could. Only a folder that is still there may say a file is gone, and never for
     * a place the system would not let the walk look at: the rows under it stay as they are until it can be read again.
     */
    fun finish() {
      if (Files.isDirectory(dir) && Files.isDirectory(root)) removeUnder(dir, recursive, present, protect = unreadable.map { it.path })
      noteUnreadable(this)
    }
  }

  /** The places a walk was refused, by path (worker thread). Only counts and kinds of place leave this class: see [BlockedSummary]. */
  private val blocked = HashMap<Path, Block>()
  @Volatile private var blockedSummary = BlockedSummary.NONE
  private val home: Path by lazy { Paths.get(System.getProperty("user.home")) }

  /** Takes in what [scan] found unreadable: what it covers is replaced by what it saw now (a folder that opens again is forgotten). Logged when it changes. */
  private fun noteUnreadable(scan: Scan) {
    val covers: (Path) -> Boolean = if (scan.recursive) { p -> p.startsWith(scan.dir) } else { p -> p == scan.dir || p.parent == scan.dir }
    val before = HashMap(blocked)
    blocked.keys.removeAll(blocked.keys.filter(covers).toSet())
    for (u in scan.unreadable) blocked[u.path] = u.block
    if (blocked == before) return
    blockedSummary = BlockedSummary.of(blocked, home)
    log("walk: " + blockedSummary.logLine())
    publish(current = null)
  }

  /**
   * Removes the indexed files under [dir] that [present] does not list (a non-recursive walk only judges the directory's own files, and its
   * subfolders that no longer exist). Rows under a [protect]ed path stay: the walk could not look there, so their absence says nothing.
   */
  private fun removeUnder(dir: Path, recursive: Boolean, present: Set<String>, protect: List<Path> = emptyList()) {
    val prefix = prefixOf(dir)
    val stored = ArrayList<Pair<Long, String>>()
    store.read { db ->
      db.query("SELECT id, uri FROM files WHERE volume = ? AND uri >= ? AND uri < ?", Crawler.VOLUME, prefix, prefix + "￿") { stored += it.getLong(0) to it.getText(1) }
    }
    val gone = ArrayList<Long>()
    val subfolders = HashMap<String, Boolean>()   // first path segment under dir -> still a directory?
    val shielded = protect.map { prefixOf(it) to it.toUri().toString() }
    for ((id, uri) in stored) {
      if (shielded.any { (under, exact) -> uri.startsWith(under) || uri == exact }) continue
      val rest = uri.substring(prefix.length)
      val slash = rest.indexOf('/')
      if (slash < 0) { if (uri !in present) gone += id; continue }
      if (recursive) { if (uri !in present) gone += id; continue }
      val seg = rest.substring(0, slash)
      val still = subfolders.getOrPut(seg) { runCatching { Files.isDirectory(Paths.get(URI(prefix + seg + "/"))) }.getOrDefault(true) }
      if (!still) gone += id
    }
    drop(gone)
  }

  /** Drops every indexed file under [dir]: the folder is skipped from now on. */
  private fun dropUnder(dir: Path) {
    val prefix = prefixOf(dir)
    val ids = ArrayList<Long>()
    store.read { db -> db.query("SELECT id FROM files WHERE volume = ? AND uri >= ? AND uri < ?", Crawler.VOLUME, prefix, prefix + "￿") { ids += it.getLong(0) } }
    drop(ids)
  }

  private fun dropOutsideRoots() {
    val prefixes = roots.map(::prefixOf)
    val gone = ArrayList<Long>()
    store.read { db -> db.query("SELECT id, uri FROM files WHERE volume = ?", Crawler.VOLUME) { s -> val u = s.getText(1); if (prefixes.none { u.startsWith(it) }) gone += s.getLong(0) } }
    drop(gone)
  }

  private fun drop(ids: List<Long>) {
    if (ids.isEmpty()) return
    ids.chunked(500).forEach { vectors.removeFiles(it); store.markDeleted(it) }
    log("dropped ${ids.size} files that are gone")
  }

  // ---- reading -------------------------------------------------------------------------------------------------

  /**
   * Reads a few waiting files. With no model that is the words pass: the text of documents and of pictures goes into the keyword index
   * (the core's WordsPassLoop and queue, as on the phone). With the models it is the whole reading, which takes the stored words
   * as they are. False when there is nothing to read.
   */
  private fun readSome(): Boolean {
    if (kinds.any { it in Crawler.MEDIA_KINDS }) { ensureDecoder(); checkSpeechPack() }   // Settings says so at once when this computer has none
    if (scanning) return false
    if (hold() != null) return false
    val ix = indexer ?: return readWords()
    val others = kinds - Crawler.MEDIA_KINDS
    val batch = store.nextPending(8, others)
    val media = mediaCandidate()
    // The phone's turns: a video or recording when one is owed its fifth of the busy time (or nothing else waits), else a few photos and documents.
    if (media != null && mediaShare.mediaTurn(true, batch.isNotEmpty())) {
      readMedia(media)
      bump()
      return true
    }
    if (batch.isEmpty()) return false
    for (p in batch) {
      searchYield.awaitQuiet { closed || queue.isNotEmpty() || paused }   // the person is searching: that goes first
      if (closed || queue.isNotEmpty() || paused) break   // a job (a new folder, a change) goes first; so does the Pause button
      val f = p.file
      publish(current = f.name)
      val began = System.nanoTime()
      try {
        ix.processStored(f, wordsDone = p.detail == IndexStore.WORDS_DONE)
        // Whatever the reader did, the file must leave the queue, or it would be picked again for ever.
        val state = store.stateOf(f.id)?.first
        if (state == FileState.NEW || state == FileState.SEARCHABLE) store.setState(f.id, FileState.FAILED, "not read")
      } catch (t: Throwable) {
        if (t is InterruptedException) throw t
        log("failed a ${f.kind.name.lowercase()}: ${t.javaClass.simpleName}")
        store.setState(f.id, FileState.FAILED, t.message ?: t.javaClass.simpleName)
      }
      sessionDone++
      val ns = System.nanoTime() - began
      readNs += ns
      mediaShare.record(false, ns / 1_000_000)
    }
    bump()
    return true
  }

  // ---- videos and recordings -----------------------------------------------------------------------------------

  /** The speech engine's threads per speed: Gentle leaves the computer free, Fast uses most of it. */
  private fun asrThreads(s: LearningSpeed): Int {
    val cores = Runtime.getRuntime().availableProcessors()
    return when (s) {
      LearningSpeed.GENTLE -> 2
      LearningSpeed.BALANCED -> 3
      LearningSpeed.FAST -> 4
    }.coerceAtMost(cores.coerceAtLeast(1))
  }

  /** The decoder, found when first needed and looked for again every half minute while there is none. Worker thread only. */
  private fun ensureDecoder(): MediaDecoder? {
    decoder?.let { return it }
    val now = System.currentTimeMillis()
    if (now - decoderCheckedAt < DECODER_RECHECK_MS) return null
    decoderCheckedAt = now
    val d = runCatching { decoderChoice() }.onFailure { log("media decoder: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
    if (d != null) {
      decoder = d
      log("media decoder: ${d.name}")
      buildMedia()
      bump()
    } else if (_ui.value.mediaDecoder != "none") {
      _ui.update { it.copy(mediaDecoder = "none") }
      bump()
    }
    return d
  }

  /** Videos and recordings are read when the decoder and the models are both there. */
  private fun buildMedia() {
    val d = decoder ?: return
    val e = embedder ?: return
    val models = object : MediaEmbedder {
      override val vision = !e.textOnly
      override fun image(jpeg: ByteArray) = e.image(jpeg)
      override fun document(text: String) = e.document(text)
    }
    val processor = MediaProcessor(d, models, asr, speechGate, { ensureOcr() }, File(dataDir, "tmp"), stop = { closed || paused })
    mediaIndexer = MediaIndexer(store, vectors, d, processor, log)
  }

  /** Looks at the speech pack: recordings read before it was installed (noted "speech models missing") go back in the queue once it is. */
  private fun checkSpeechPack() {
    val now = asr.installed()
    speechInstalled = now
    if (now && speechWas != true) {
      val n = store.retryMissingSpeech()
      if (n > 0) { log("speech pack installed: $n files read again for their words"); bump() }
    }
    speechWas = now
  }

  /** The next video or recording to read, or null: the two kinds take turns, a file already begun (PARTIAL) is picked up where it stopped. */
  private fun mediaCandidate(): PendingFile? {
    val wanted = kinds.intersect(Crawler.MEDIA_KINDS)
    if (wanted.isEmpty()) return null
    if (ensureDecoder() == null || mediaIndexer == null) return null
    for (k in mediaShare.mediaOrder(wanted)) store.nextPending(1, setOf(k), includePartial = true).firstOrNull()?.let { return it }
    return null
  }

  /** Reads [p] a window at a time. After each window it hands the floor back when a photo or document is owed time, or the person paused. */
  private fun readMedia(p: PendingFile) {
    val mi = mediaIndexer ?: return
    val f = p.file
    publish(current = f.name)
    try {
      mi.read(p) { ms ->
        mediaShare.record(true, ms, f.kind)
        mediaNs += ms
        publish(current = f.name)
        searchYield.awaitQuiet { closed || queue.isNotEmpty() || paused }
        !closed && !paused && queue.isEmpty() && hold() == null &&
          mediaShare.mediaTurn(true, store.nextPending(1, kinds - Crawler.MEDIA_KINDS).isNotEmpty())
      }
    } catch (_: java.util.concurrent.CancellationException) {
      return   // paused or closing in the middle of a window: the file stays as it was and is read again
    }
    val state = store.stateOf(f.id)?.first
    if (state != FileState.PARTIAL) mediaDone++
  }

  /** The words pass for a few files. True when it read any. Pause, a new job and closing stop it between files. */
  private fun readWords(): Boolean {
    val on = kinds.intersect(WordsReader.KINDS)
    val done = runBlocking {
      WordsPassLoop.run(wordsLock, limit = WordsPassLoop.BATCH, stop = { closed || queue.isNotEmpty() || paused }, next = { n -> store.nextWithoutWords(n, on) }) { p ->
        searchYield.awaitQuiet { closed || queue.isNotEmpty() || paused }
        publish(current = p.file.name)
        val began = System.nanoTime()
        (wordsReader ?: throw StoreNotReadyException()).read(p.file)
        wordsSessionDone++
        wordsNs += System.nanoTime() - began
        true
      }
    }
    if (done > 0) bump()
    return done > 0
  }

  /** Files whose words the words pass has not read yet (kinds that are on, not skipped). */
  private fun wordsLeft(on: Set<FileKind>): Int = IndexAdmin.wordsLeft(store, on)

  /**
   * Why reading is on hold right now, or null. Gentle waits for the charger where the OS reports a battery. A full disk and a
   * folder the system will not let Trawl list come before that: neither gets better by waiting for the charger.
   */
  private fun hold(): Hold? = when {
    paused -> Hold.USER
    noAccess -> Hold.NO_PERMISSION
    diskLevel == DiskSpace.Level.LOW -> Hold.STORAGE_LOW
    WorkSpeed.waitsForCharger(speed) && battery.onBattery() == true -> Hold.BATTERY
    else -> null
  }

  // ---- the disk and the folders' permission (worker thread; read by [publish] through the @Volatile fields) ----

  @Volatile private var diskLevel = DiskSpace.Level.OK
  @Volatile private var freeBytes: Long? = null
  private var diskCheckedAt = 0L

  /** Looks at the free space now and then, with the room the rest of the index still needs. A change is published at once. */
  private fun refreshDisk() {
    val now = System.currentTimeMillis()
    if (diskCheckedAt != 0L && now - diskCheckedAt < diskCheckMs) return
    diskCheckedAt = now
    val free = freeSpace(File(dataDir, "index"))
    freeBytes = free
    val before = diskLevel
    if (free == null) { diskLevel = DiskSpace.Level.OK; return }   // the system would not say: do not stop reading on a guess
    // The growth to come matters only when the disk is fairly full; a scan of the file sizes every few seconds is not worth it otherwise.
    val growth = if (free < GROWTH_CHECK_BELOW) opener.current?.let { st ->
      runCatching { StorageEstimate.estimate(st.storageTotals(), kinds, withVectors = embedder != null).indexBytes - st.bytesOnDisk() }.getOrDefault(0L).coerceAtLeast(0L)
    } ?: 0L else 0L
    diskLevel = DiskSpace.level(free, before, growth)
    if (diskLevel != before) { log("disk space: $before -> $diskLevel (${free / DiskSpace.MB} MB free)"); publish(current = null) }
  }

  @Volatile private var noAccess = false
  private var accessCheckedAt = 0L

  /** Asks the system whether the chosen folders can be listed (a macOS folder prompt that was refused makes a folder look empty), every few seconds. */
  private fun refreshAccess() {
    val now = System.currentTimeMillis()
    if (now - accessCheckedAt < ACCESS_CHECK_MS) return
    accessCheckedAt = now
    val before = noAccess
    noAccess = FolderAccess.denied(roots, canList)
    if (noAccess != before) { log(if (noAccess) "the system will not let Trawl list the chosen folders" else "the chosen folders can be listed again"); publish(current = null); if (!noAccess) queue.add(Rescan) }
  }

  /** While the index opens or cannot open: tell the window which, in the phone's classes, so it can show the calm card. */
  private fun showOpening() {
    val st = opener.state.value
    val problem = (st as? StoreOpener.State.Failed)?.problem
    _ui.update { it.copy(opening = st is StoreOpener.State.Opening, openProblem = problem) }
  }

  // ---- status --------------------------------------------------------------------------------------------------

  private fun bump() { generation++; publish(current = null) }

  private fun publish(current: String?) {
    val store = opener.current ?: return   // the window can ask for a refresh before the index has opened: nothing to count yet
    val s = store.stats()
    val ready = embedder != null
    // Videos and recordings are part of the meter only when they can be read: the models are in and the computer has a decoder.
    val on = if (ready && decoder != null) kinds else kinds - Crawler.MEDIA_KINDS
    var total = 0; var photos = 0; var docs = 0; var videos = 0; var recordings = 0; var unreadable = 0
    val byKind = HashMap<FileKind, Int>()
    for (k in Crawler.KINDS) {
      val n = s.byKindAndState[k]?.filterKeys { it != FileState.SKIPPED }?.values?.sum() ?: 0
      byKind[k] = n
      if (k !in on) continue   // a kind that is switched off is not part of the meter
      total += n
      unreadable += s.count(k, FileState.FAILED)
      when (k) {
        FileKind.IMAGE, FileKind.SCREENSHOT -> photos += n
        FileKind.VIDEO -> videos += n
        FileKind.AUDIO -> recordings += n
        else -> docs += n
      }
    }
    val waiting = s.waiting(on)
    val noDecoder = decoder == null && _ui.value.mediaDecoder == "none"
    val mediaNeedDecoder = if (ready && noDecoder) s.waiting(kinds.intersect(Crawler.MEDIA_KINDS)) else 0
    val mediaWaiting = s.waiting(on.intersect(Crawler.MEDIA_KINDS))
    val hold = hold()
    // With no model the work is the words pass; with one, the whole reading.
    val wordsLeftAll = wordsLeft(on)
    val wordsLeft = if (ready) 0 else wordsLeftAll
    val work = if (ready) waiting else wordsLeft
    val phase = when {
      hold == Hold.NO_PERMISSION && roots.isNotEmpty() -> Phase.PAUSED   // nothing is known yet, so there is no "work" to wait on
      scanning -> Phase.SCANNING
      roots.isEmpty() -> Phase.NOT_STARTED
      hold != null && work > 0 -> Phase.PAUSED
      work > 0 -> Phase.INDEXING
      else -> Phase.UP_TO_DATE
    }
    val elapsed = System.currentTimeMillis() - sessionStarted
    // The finish time and the rate come from the time spent reading, so a pause or an idle hour does not slow them down.
    val perFile = if (sessionDone >= 4 && readNs > 0) readNs / 1e6 / sessionDone else null
    val wordsPerFile = if (wordsSessionDone >= 4 && wordsNs > 0) wordsNs / 1e6 / wordsSessionDone else null
    // Videos and recordings cost far more than a photo each, so they are timed on their own; with none finished yet there is no honest finish time.
    val perMedia = if (mediaDone >= 2 && mediaNs > 0) mediaNs.toDouble() / mediaDone else null
    val eta = when {
      ready && mediaWaiting > 0 && perMedia == null -> null
      ready && waiting > 0 && (perFile != null || waiting == mediaWaiting) && elapsed > 0 -> ((waiting - mediaWaiting) * (perFile ?: 0.0) + mediaWaiting * (perMedia ?: 0.0)).toLong()
      !ready && wordsLeft > 0 && wordsPerFile != null -> (wordsLeft * wordsPerFile).toLong()
      else -> null
    }
    val rate = if (sessionDone >= 4 && readNs > 0) sessionDone / (readNs / 6e10) else null
    _ui.update {
      it.copy(
        phase = phase, scanned = scannedSoFar, total = total, understood = if (ready) total - waiting else total - wordsLeft, photos = photos, documents = docs, videos = videos, recordings = recordings,
        current = current, etaMs = eta, modelReady = ready, generation = generation,
        hold = if (phase == Phase.PAUSED) hold else null, byKind = byKind, ratePerMin = rate ?: it.ratePerMin, unreadable = unreadable, readingWords = !ready && wordsLeft > 0,
        mediaDecoder = decoder?.name ?: it.mediaDecoder, mediaNeedDecoder = mediaNeedDecoder, speechPack = speechInstalled,
        wordsIn = total - wordsLeftAll, openProblem = null, opening = false, reembed = reembedNow(store, on),
        disk = diskLevel, freeBytes = freeBytes, blocked = blockedSummary,
      )
    }
  }

  private var reembedAt = 0L
  private var reembedLast: Pair<Int, Int>? = null

  /** "Updating for the new model: n of m" (the phone's line). The count is a join over the vectors, so it is taken at most every few seconds. */
  private fun reembedNow(store: IndexStore, on: Set<FileKind>): Pair<Int, Int>? {
    if (embedder == null) return null
    val now = System.currentTimeMillis()
    if (now - reembedAt >= REEMBED_EVERY_MS) {
      reembedAt = now
      reembedLast = runCatching { store.reembedProgress().over(on) }.getOrNull()
    }
    return reembedLast
  }

  companion object {
    const val DISK_CHECK_MS = 10_000L
    private const val ACCESS_CHECK_MS = 3_000L
    private const val OPEN_POLL_MS = 200L
    private const val REEMBED_EVERY_MS = 4_000L
    /** Above this much free space the growth of the index is not worth working out. */
    private const val GROWTH_CHECK_BELOW = 20L * 1024 * 1024 * 1024
    private const val DECODER_RECHECK_MS = 30_000L

    /** The URI prefix every file of [dir] shares: `file:///<your folder>/Pictures/`. Taken the way the crawler takes each file's URI, so they always compare. */
    fun prefixOf(dir: Path): String {
      val s = dir.toUri().toString()
      return if (s.endsWith("/")) s else "$s/"
    }
  }
}

/**
 * How long the watcher-driven walks leave the worker alone after a long one, so that a tree which changes all the time (a sync tool, a
 * build) costs a fraction of a core instead of all of one: after a walk of `t` the next waits `4 t`, at most a minute. A walk under a
 * quarter of a second (the usual one, now that dependency folders are not entered) is not slowed at all. The folders named meanwhile are
 * merged into one walk, so nothing is lost, only delayed.
 */
object ReconcilePacing {
  const val FREE_MS = 250L
  const val FACTOR = 4L
  const val MAX_WAIT_MS = 60_000L

  /** When the next walk may start, given that one ended at [now] after taking [tookMs]. */
  fun notBefore(now: Long, tookMs: Long): Long = if (tookMs <= FREE_MS) 0L else now + minOf(tookMs * FACTOR, MAX_WAIT_MS)
}
