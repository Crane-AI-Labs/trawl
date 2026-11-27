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
import com.craneai.findr.index.Schema
import com.craneai.findr.search.LocalKeywords
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.TwoStage
import com.craneai.findr.search.VectorFile
import com.craneai.findr.search.VectorFormat
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
 * Why reading is on hold: the person pressed Pause, the Gentle speed is waiting for the charger ([BATTERY]), the battery is under the
 * floor at any speed ([LOW_BATTERY], [LowBatteryRule]), the disk is nearly full ([DiskSpace]), the system will not let Trawl list the chosen
 * folders ([FolderAccess]), the computer is hot or asking programs to save power ([SoftHolds]; the last two can be overridden with "run anyway").
 */
enum class Hold { USER, BATTERY, STORAGE_LOW, NO_PERMISSION, THERMAL, LOW_POWER, LOW_BATTERY }

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
  /** The same reason came up several times in a row, so Trawl stopped trying on a timer: the card says what to do instead of "trying again shortly". */
  val openStopped: Boolean = false,
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
  private val openStore: (onTransient: (Throwable) -> Unit) -> IndexStore = { onRetry -> IndexStore.open(File(dataDir, "index"), readers = DESKTOP_READERS, log = log, onRetry = onRetry, fastOpen = true) },
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
  internal val access: IndexAccess = IndexAccess(),
  private val shredFile: (File) -> Long = VectorFile::shred,
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
    // Out of memory does not mend by waiting (the heap is the same on the next try), so the loop stops after 3 and waits for Try again or a
    // restart; the other reasons may (a second Trawl closing, space freed), so they get 8 quick tries and then one try every 15 minutes.
    settleAfter = { openSettleAfter(it) }, settledWaitMs = { openSettledWaitMs(it) },
  )
  /** The open index; throws [StoreNotReadyException] while it is opening or failing. The worker waits for it; the window's calls use [opener] and answer "nothing yet". */
  private val store: IndexStore get() = opener.current ?: throw StoreNotReadyException()
  // Off-heap memory-mapped SQ8 v3 vector store: cuts vectors.bin ~49% vs v2 fp16, accelerates with ARM64 NEON SDOT when present
  // (up to 8 threads on >=8-core desktop CPUs to saturate DRAM bandwidth), and keeps multi-million-vector indexes off the Java heap.
  private val vectors = VectorIndex(
    parallelism = VectorIndex.defaultParallelism(),
    backing = File(File(dataDir, "index"), VECTORS_FILE),
    quiet = { !wasBusy && !scanning && queue.isEmpty() },
    twoStage = TwoStage.DEFAULT,
    format = VectorFormat.SQ8,
  )
  /** The kinds the person has switched on, the folders/files they skip, how hard to work and whether they paused. Set by [configure]. */
  @Volatile private var kinds: Set<FileKind> = Crawler.KINDS
  @Volatile private var skipped: List<Path> = emptyList()
  @Volatile private var skippedFiles: Set<Path> = emptySet()
  @Volatile private var paused = false
  @Volatile private var speed = LearningSpeed.BALANCED
  private var appliedSkipped: List<Path> = emptyList()
  private var appliedSkippedFiles: Set<Path> = emptySet()
  private val battery = BatteryWatch()
  private val lowBattery = LowBatteryRule(onBattery = { battery.onBattery() })
  private val softHolds = SoftHolds()
  /** How much of the computer the reader may take right now, by speed, whether the person is there, thermal state, and the power (see [Politeness]; off with `governor=0`). */
  private val politeness = Politeness(
    sensors = SystemSensors(onBattery = { battery.onBattery() }),
    speed = { speed },
    thermalLevel = { softHolds.currentLevel() },
  )
  /** The whole app behind the person's programs while no window shows (macOS process class / away-on-AC Utility QoS, Linux scope weight); see [BackgroundMode]. */
  private val background = BackgroundMode(
    Throttle.forThisSystem(),
    speed = { speed },
    log = { log(it) },
    awayOnAc = { politeness.lastReading?.let { it.user == UserState.AWAY && it.onBattery != true } == true },
    allowUtility = Platform.os == Os.MAC && BackgroundTuning.macUtilityAwayAc != 0,
    onTierChanged = { LowPriority.activeTier = it },
    opening = { opener.current == null },
  )
  private var workerTier: BackgroundTier? = null
  @Volatile private var searcher: Searcher? = null
  private val ctx = ClasspathAssets
  private val speechGate = SpeechLanguageGate.fromAssets(ctx)
  private val weights = WordWeightPolicy.fromAssets(ctx, QueryLanguage(speechGate.words)) { modelName }
  /** The Luganda/Swahili -> English keyword table, read on the first Luganda or Swahili query, as on the phone. */
  private val localKeywords = LocalKeywords.fromAssets(ctx)

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
  private val asr = DesktopAsr(
    File(File(dataDir, "models"), ModelSetup.ASR_DIR),
    threads = { asrThreads(speed) },
    onReleased = { log("asr: released idle speech models") },
  )
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
  private class SetRoots(val roots: List<File>, val addedFiles: List<File> = emptyList()) : Job
  /** "Look at what the watcher named": the folders themselves wait in [dirtyPending], so any number of reports make at most one job. */
  private data object Reconcile : Job
  private class Attach(val model: File) : Job
  private data object Rescan : Job
  private class Skip(val skipped: List<Path>, val skippedFiles: Set<Path>) : Job
  private class RemoveFiles(val ids: List<Long>) : Job
  private class Speed(val speed: LearningSpeed) : Job
  private data object Wake : Job

  private val queue = LinkedBlockingQueue<Job>()
  @Volatile private var closed = false
  @Volatile private var roots: List<Path> = emptyList()
  @Volatile private var addedFiles: List<Path> = emptyList()
  @Volatile private var scanning = false
  @Volatile private var wasBusy = false
  @Volatile private var scannedSoFar = 0
  private var sessionDone = 0
  private var sessionStarted = 0L
  private var readNs = 0L
  private var generation = 0
  private val lifecycleLock = Any()
  private var disposed = false
  private var pendingForgot: Forgot? = null
  private var worker = newWorker()

  private fun newWorker() = Thread(::loop, "trawl-index").apply { isDaemon = true }

  init {
    OwnerOnlyPermissions.hardenDataDir(dataDir)
    opener.start()
    worker.start()
  }

  private var vectorLoadFailures = 0

  /** Runs on the opener's thread before the index is shown to anyone: the vectors, the clean-up after an update, the readers that need it. */
  private fun prepareStore(st: IndexStore) {
    try { vectors.load(st) } catch (t: Throwable) {
      // The opener is told (and the card shown); the vector file, a cache of the database, is dropped from the second failure in a row so
      // the next try rebuilds it from SQLite rather than adopting or extending whatever this one choked on. The database is never touched.
      if (++vectorLoadFailures >= 2) { log("the vectors could not be loaded $vectorLoadFailures times in a row (${t.javaClass.simpleName}); the vector file is dropped and rebuilt from the database"); runCatching { vectors.discardFile() } }
      throw t
    }
    vectorLoadFailures = 0
    OwnerOnlyPermissions.hardenDataDir(dataDir)
    // A video the last run died inside twice is given up on (or its window skipped); the first death is only counted.
    st.recoverInflight()?.let { log("recovered from a stop inside a file: step ${it.step}${if (it.skippedWindow) ", window skipped" else ""}") }
    IndexAdmin.rereadNewFormats(st, log)   // files the first build knew by name only go back in the queue, once
    IndexAdmin.rereadLegacyWorkbooks(st, log)   // .xls files, known by name only before 1.1.0, are read once
    IndexAdmin.dropFalseTransportStreams(st, log, onDropped = { vectors.removeFiles(it) })   // TypeScript an older build took for videos leaves the index, once
    searcher = Searcher(st, vectors, indexedKinds = { kinds }, keywords = localKeywords)
    wordsReader = WordsReader(st) { ensureOcr() }
    onStoreOpened(st)
  }

  /** Try opening the index again now rather than at the end of the wait (the calm card's Retry button). */
  fun retryOpen() = access.read(Unit) { opener.retryNow() }

  // ---- what the window asks ------------------------------------------------------------------------------------

  fun setFolders(folders: List<File>, addedFiles: List<File> = emptyList()) { queue.add(SetRoots(folders, addedFiles)) }
  private val dirtyPending = java.util.concurrent.ConcurrentHashMap<Path, Boolean>()
  private val reconcileQueued = java.util.concurrent.atomic.AtomicBoolean(false)

  /** The earliest time the next watcher-driven walk may start: a walk that took long leaves the worker alone for a while after it ([ReconcilePacing]). */
  @Volatile private var reconcileNotBefore = 0L
  /** Told after each watcher-driven walk which folder it was and how long it took (the watcher paces its polling by it, see [PollPacing]). */
  @Volatile var onWalked: (Path, Long) -> Unit = { _, _ -> }
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
  fun configure(kinds: Set<FileKind>, skipped: List<Path>, speed: LearningSpeed, paused: Boolean, skippedFiles: Set<Path> = emptySet()) {
    val kindsChanged = this.kinds != kinds
    this.kinds = kinds.intersect(Crawler.KINDS)
    val resumed = this.paused && !paused
    if (this.paused != paused) stateDirty.set(true)   // pause and resume show at once, however busy the worker is
    this.paused = paused
    this.skipped = skipped
    this.skippedFiles = skippedFiles
    if (this.speed != speed || !speedApplied) { this.speed = speed; queue.add(Speed(speed)); background.refresh() }
    queue.add(Skip(skipped, skippedFiles))
    if (kindsChanged) { generation++; stateDirty.set(true) }   // the worker publishes it on its next turn (the Skip job below wakes it)
    if (resumed) wakeReconcile()   // what changed while paused is looked at now, in one walk
  }

  /**
   * Immediately removes specific indexed files from Trawl's index (rows, FTS words, and vectors) without ever touching
   * the user's files on disk. Pair with persisting the paths in `Settings.excludedFiles` so future scans skip them.
   */
  fun removeFromIndex(ids: List<Long>) {
    if (ids.isEmpty()) return
    queue.add(RemoveFiles(ids))
  }

  @Volatile private var speedApplied = false

  /**
   * What the shell knows about the windows: whether the main window shows (and is not minimised), whether it has the focus, whether the
   * quick panel is open. A null leaves that one as it was. Called before a window paints, so a throttled process is cleared in time.
   */
  fun windows(mainVisible: Boolean? = null, mainFocused: Boolean? = null, panelOpen: Boolean? = null) {
    mainVisible?.let { background.mainVisible = it }
    mainFocused?.let { background.mainFocused = it }
    panelOpen?.let { background.panelOpen = it }
    background.refresh()
  }
  @Volatile private var lastAsked: Pair<String, KindFilter>? = null

  /** What the person typed, through the phone's own ranking. With no model the query has no vector and only the words count. */
  fun search(query: String, filter: KindFilter, limit: Int = 80, withSensitive: Boolean = includeSensitive()): SearchResults =
    access.read(SearchResults(query, emptyList(), 0, 0L, indexIncomplete = true)) { searchReady(query, filter, limit, withSensitive) }

  private fun searchReady(query: String, filter: KindFilter, limit: Int, withSensitive: Boolean): SearchResults {
    // Reading steps aside for the length of a search the person asked for. The window asks the same question again each time the index
    // grows (at most once a second); that is a refresh, not the person, and must not hold the reading back.
    val asked = query to filter
    if (asked != lastAsked) { lastAsked = asked; searchYield.touch() }
    val searcher = searcher ?: return SearchResults(query, emptyList(), 0, 0L, indexIncomplete = true)   // the index is still opening
    val e = embedder
    val qv = if (e != null && query.isNotBlank()) e.query(LocalKeywords.embedText(query, localKeywords)) else null
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
      embed = { q -> embedder?.query(LocalKeywords.embedText(q, localKeywords)) }, wordsWeight = { q -> weights.wordsWeight(q) }, meaningWeight = { q, v -> weights.meaningWeight(q, v) },
      kinds = { kinds }, includeSensitive = includeSensitive,
      modelFingerprint = { "${modelName ?: ""}:$modelBytes" },
      touch = { q, f -> val asked = q to f; if (asked != lastAsked) { lastAsked = asked; searchYield.touch() } },
      access = access,
    )
  }
  // ---- end SEARCH PACKAGE (A) ------------------------------------------------------------------------------------------------------

  /** Newest files, for the window before anything is typed. */
  fun recent(filter: KindFilter, limit: Int = 60, withSensitive: Boolean = includeSensitive()): List<IndexedFile> =
    access.read(emptyList()) { opener.current?.recent(filter.kinds.intersect(kinds), limit, includeSensitive = withSensitive) ?: emptyList() }

  /** How many files a folder holds in the index, for Settings. */
  fun countUnder(folder: File): Int = access.read(0) {
    val store = opener.current ?: return@read 0
    val prefix = prefixOf(SafePath.of(folder) ?: return@read 0)
    store.read { db -> db.long("SELECT COUNT(*) FROM files WHERE volume = ? AND state != 'skipped' AND uri >= ? AND uri < ?", Crawler.VOLUME, prefix, prefix + "￿") ?: 0L }.toInt()
  }

  /** The benchmark and tests: how far the index has got with the file at [f] (its state and detail), or null when there is no row for it yet. */
  internal fun progressOf(f: File): Pair<String, String?>? {
    val store = opener.current ?: return null
    val uri = (SafePath.of(f) ?: return null).toUri().toString()
    var found: Pair<String, String?>? = null
    store.read { db -> db.query("SELECT state, detail FROM files WHERE volume = ? AND uri = ?", Crawler.VOLUME, uri) { found = it.getText(0) to (if (it.isNull(1)) null else it.getText(1)) } }
    return found
  }

  /** The benchmark and tests: the files under [folder] counted by state ("understood", "failed", "new"...). */
  internal fun statesUnder(folder: File): Map<String, Int> {
    val store = opener.current ?: return emptyMap()
    val prefix = prefixOf(SafePath.of(folder) ?: return emptyMap())
    val out = HashMap<String, Int>()
    store.read { db -> db.query("SELECT state, COUNT(*) FROM files WHERE volume = ? AND uri >= ? AND uri < ? GROUP BY state", Crawler.VOLUME, prefix, prefix + "￿") { out[it.getText(0)] = it.getLong(1).toInt() } }
    return out
  }

  fun indexBytes(): Long = access.read(0L) { opener.current?.bytesOnDisk() ?: 0L }

  /** The detail panel's numbers (counts per kind, what waits, what failed and why, sizes), or null while the index is not open. Cheap enough to ask when the panel opens and when [IndexUi.generation] moves. */
  fun detail(): IndexAdmin.Detail? =
    access.read(null) { opener.current?.let { IndexAdmin.detail(it, kinds, modelBytes, embedder != null) } }

  /** Reads again the files that could not be read (damaged ones stay damaged and fail again quickly). Returns how many went back in the queue. */
  fun retryUnreadable(): Int = access.read(0) {
    val n = opener.current?.retryFailed() ?: return@read 0
    if (n > 0) bump()
    n
  }

  /** For the problem report: the picture engine in use ("gpu" or "cpu"), the text thread count (null = the engine's own) and the OCR engine. */
  val visionBackend: String? get() = embedder?.visionUsed
  val textThreads: Int? get() = embedder?.threadCount
  val ocrName: String? get() = ocr?.name

  /** Forgets everything learned (shreds and deletes the index files off the UI thread, then reopens fresh); the folders stay and are walked again. */
  fun forgetAll(): Forgot = synchronized(lifecycleLock) {
    check(!disposed)
    access.pause()
    closed = true
    queue.add(Wake)
    worker.join()
    val old = runBlocking { opener.detach() }
    val before = pendingForgot ?: Forgot(
      old?.fileStats()?.byKindAndState?.values?.sumOf { states -> states.filterKeys { it != FileState.SKIPPED }.values.sum() } ?: 0,
      old?.bytesOnDisk() ?: 0L,
    )
    pendingForgot = before
    var restarted = false
    try {
      old?.close()
      searcher = null
      wordsReader = null
      indexer = null
      mediaIndexer = null
      vectors.shred()
      val directory = File(dataDir, "index").toPath()
      check(!Files.isSymbolicLink(directory)) { "the index directory is a symbolic link" }
      if (Files.exists(directory)) Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path ->
          if (path != directory) {
            if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) runCatching { shredFile(path.toFile()) }
            try {
              Files.deleteIfExists(path)
            } catch (_: java.io.IOException) {
              // Windows may hold an un-GC'd mmap section on a freshly shredded vectors.bin; mark for deleteOnExit so recovery never wedges.
              path.toFile().deleteOnExit()
            }
          }
        }
      }
      scans.clear()
      scanning = false
      sessionDone = 0
      wordsSessionDone = 0
      mediaDone = 0
      generation++
      _ui.value = IndexUi(generation = generation)
      opener.start()
      runBlocking { opener.await() } ?: error("the fresh index could not open")
      OwnerOnlyPermissions.hardenDataDir(dataDir)
      embedder?.let { engine ->
        store.ensureModelFingerprint("desktop:${modelName}:${modelBytes}")
        indexer = Indexer(store, vectors, engine, ocr)
        buildMedia()
      }
      rescan()
      pendingForgot = null
      restarted = true
      before
    } finally {
      if (!restarted) runCatching { opener.start() }
      closed = false
      access.resume()
      if (!worker.isAlive) worker = newWorker().also { it.start() }
    }
  }

  override fun close(): Unit = synchronized(lifecycleLock) {
    if (disposed) return
    disposed = true
    access.pause()
    closed = true
    runCatching { background.close() }   // never leave the process throttled behind us
    queue.add(Wake)
    worker.join()
    runCatching { asr.close() }
    runCatching { decoder?.close() }
    runCatching { ocr?.close() }
    runCatching { embedder?.close() }
    runCatching { vectors.close() }   // settles vectors.bin cleanly while store is still open so the next start attaches off-heap
    runCatching { runBlocking { opener.detach() }?.close() }   // waits for an open in flight, so a store that opened is never left behind
    openScope.cancel()
  }

  // ---- the worker ----------------------------------------------------------------------------------------------

  private fun loop() {
    // The reader's own thread (and the model's threads and helper programs it starts) runs at the system's lowest priority when asked to.
    LowPriority.applyToThisThread()?.let { log("reading priority: $it") }
    while (!closed) {
      try {
        if (opener.current == null) { showOpening(); Thread.sleep(OPEN_POLL_MS); continue }   // jobs wait in the queue until there is an index to put them in
        syncWorkerQos()
        if (_ui.value.openProblem != null || _ui.value.opening) bump()   // it just opened: take the card down, and let an open query run again
        refreshAccess()
        refreshDisk()
        flushStatus()
        val job = queue.poll()
        if (closed) return
        if (job != null) { handle(job); continue }
        if (reconcileDeferred && !paused && System.currentTimeMillis() >= reconcileNotBefore) { reconcileDeferred = false; handle(Reconcile); continue }
        if (scans.isNotEmpty()) { stepScans(); continue }   // a walk that gave way carries on where it stopped
        if (retryPurges()) continue   // a skipped folder purge that failed is tried again, 5 s then longer
        retryRepair()
        if (readSome()) { wasBusy = true; continue }
        scanning = false
        if (wasBusy) {
          wasBusy = false
          stateDirty.set(true)
          if (paused) asr.release()   // on explicit pause reclaim 300-750 MB immediately; otherwise DesktopAsr's 60 s idle timer frees it without thrashing back-to-back media batches
        } else if (paused && asr.isLoaded) {
          if (paused) asr.release()   // user clicked Pause while ASR was waiting on its 60 s idle timer
        }   // the work just ran out: "caught up" shows at once, not a second late
        idleStatus()
        maybeMergeFtsIdle()
        queue.poll(idleWaitMs(), TimeUnit.MILLISECONDS)?.let { if (!closed) handle(it) }
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
      is Wake -> Unit
      is SetRoots -> {
        roots = job.roots.mapNotNull { SafePath.of(it) }
        addedFiles = job.addedFiles.mapNotNull { SafePath.of(it) }.distinct()
        dropOutsideRoots()
        if (blocked.keys.removeAll { b -> roots.none { b.startsWith(it) } }) { blockedSummary = BlockedSummary.of(blocked, home) }
        scanAll()
      }
      is Rescan -> scanAll()
      is Skip -> { applySkipped(job.skipped); applySkippedFiles(job.skippedFiles) }
      is RemoveFiles -> {
        if (drop(job.ids) > 0) bump()
      }
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
          val walkStart = System.currentTimeMillis()
          reconcile(root, dir, recursive)
          runCatching { onWalked(dir, System.currentTimeMillis() - walkStart) }
        }
        val now = System.currentTimeMillis()
        reconcileNotBefore = ReconcilePacing.notBefore(now, now - startedAt)
      }
      is Attach -> attach(job.model)
    }
  }

  /**
   * Takes what is newly skipped out of the index and walks what is readable again. Runs on the worker, in the order the person clicked.
   * A folder counts as applied ([appliedSkipped]) only once its files are out: a drop that fails (the index not open, out of memory, a broken
   * file) leaves the folder pending, and [retryPurges] tries it again on a later turn. It used to be marked first, so one failure meant the
   * folder stayed in the index for the rest of the run (a skipped folder that still held 87% of one Mac's 729,000 vectors).
   */
  private fun applySkipped(now: List<Path>) {
    val applied = appliedSkipped.toMutableList()
    val freed = applied.filter { o -> now.none { o.startsWith(it) } }
    applied.removeAll(freed.toSet())
    appliedSkipped = applied
    val added = now.filter { n -> applied.none { n.startsWith(it) } }
    var removed = 0
    var failed = false
    for (a in added) {
      try {
        removed += dropUnder(a)
        if (closed) return   // stopped half way: not applied, the next run does it
        appliedSkipped = appliedSkipped + listOf(a)   // not `+ a`: a Path is itself a list of its name parts
      } catch (e: InterruptedException) { throw e } catch (t: Throwable) {
        failed = true
        log("skipped folder not yet taken out of the index (${t.javaClass.simpleName}); trying again")
      }
    }
    for (f in freed) {
      val root = roots.firstOrNull { f.startsWith(it) } ?: continue
      if (Files.isDirectory(f)) walkNow(root, f, recursive = true) { }
    }
    purgeFailed(failed)
    if (removed > 0) runCatching { onPurged(removed, false) }
    if (added.isNotEmpty() || freed.isNotEmpty()) bump()
  }

  /** Drops individually excluded files from the index and re-walks parent dirs of files restored in Settings. */
  private fun applySkippedFiles(now: Set<Path>) {
    val freed = appliedSkippedFiles.filter { it !in now }
    val added = now.filter { it !in appliedSkippedFiles }
    appliedSkippedFiles = now
    if (added.isNotEmpty()) {
      val ids = ArrayList<Long>()
      store.read { db ->
        for (p in added) {
          val uri = p.toAbsolutePath().normalize().toUri().toString()
          db.query("SELECT id FROM files WHERE volume = ? AND uri = ?", Crawler.VOLUME, uri) { ids += it.getLong(0) }
        }
      }
      if (drop(ids) > 0) bump()
    }
    if (freed.isNotEmpty()) {
      val parents = freed.mapNotNull { it.parent }.distinct()
      for (dir in parents) {
        val root = roots.firstOrNull { dir.startsWith(it) } ?: continue
        if (Files.isDirectory(dir)) walkNow(root, dir, recursive = false) { }
      }
      if (freed.any { it in addedFiles }) scanAddedFiles()
      bump()
    }
  }

  /** The wait before a failed purge is tried again (5 s, doubling to 5 minutes), and when that is. */
  private var purgeRetryMs = PURGE_RETRY_FIRST_MS
  @Volatile private var purgeRetryAt = 0L
  private fun purgeFailed(failed: Boolean) {
    if (!failed) { purgeRetryMs = PURGE_RETRY_FIRST_MS; return }
    purgeRetryAt = System.currentTimeMillis() + purgeRetryMs
    purgeRetryMs = (purgeRetryMs * 2).coerceAtMost(PURGE_RETRY_MAX_MS)
  }

  /** True when a skipped folder purge is still waiting to be done, and it is time to try. */
  private fun retryPurges(): Boolean {
    if (System.currentTimeMillis() < purgeRetryAt) return false
    val skips = skipped.any { s -> appliedSkipped.none { s.startsWith(it) } }
    if (!skips) return false
    applySkipped(skipped)
    return true
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
    reembedCount?.reset()
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

  /** Starts the walk of every chosen folder and inspects any individually added files without crawling their parent directories. */
  private fun scanAll() {
    scans.clear()
    scanning = true; scannedSoFar = 0
    for (r in roots) {
      if (!Files.isDirectory(r)) { log("scan: a folder is not there; keeping what is known about it"); continue }
      scans.add(Scan(r, r, recursive = true) { n -> scannedSoFar += n; publish(current = null) })
    }
    scanAddedFiles()
    stepScans()
  }

  /** Indexes explicitly added individual files only (never touches sibling files in the same parent folder). */
  private fun scanAddedFiles() {
    if (addedFiles.isEmpty()) return
    val batch = ArrayList<CrawlRecord>()
    val vanishedUris = ArrayList<String>()
    for (p in addedFiles) {
      if (Scope.isSkippedFile(p, skippedFiles)) continue
      if (!Files.isRegularFile(p)) {
        // Only drop a vanished single file if its parent folder still exists (an unplugged drive drops nothing).
        if (p.parent?.let { Files.isDirectory(it) } == true) vanishedUris += p.toUri().toString()
        continue
      }
      val found = Crawler.inspectFile(p) ?: continue
      batch += found.record()
    }
    if (vanishedUris.isNotEmpty()) {
      val ids = ArrayList<Long>()
      store.read { db ->
        for (uri in vanishedUris) {
          db.query("SELECT id FROM files WHERE volume = ? AND uri = ?", Crawler.VOLUME, uri) { ids += it.getLong(0) }
        }
      }
      if (ids.isNotEmpty()) drop(ids)
    }
    if (batch.isNotEmpty()) {
      val r = store.upsertFromCrawl(batch)
      vectors.removeFiles(r.reset + r.newlySkipped)
      scannedSoFar += batch.size
      publish(current = null)
    }
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

  /** Called on the worker thread when a scan encounters an unusually large subfolder (count/bytes threshold crossed). */
  @Volatile var onLargeFolder: ((LargeFolderAlert) -> Unit)? = null

  /** Tests only: replaces the question "is a more urgent job waiting?", to make a walk give way as often as they like. */
  @Volatile internal var yieldHook: (() -> Boolean)? = null

  private fun reconcile(root: Path, dir: Path, recursive: Boolean) {
    if (!Files.isDirectory(root)) return   // the whole folder is unreachable (an unplugged drive): conclude nothing
    if (!Files.isDirectory(dir)) {
      // The directory is gone: so is everything under it.
      removeUnder(dir, recursive = true, present = emptySet())
      bumpProgress(); return
    }
    walkNow(root, dir, recursive) { }
    bumpProgress()
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
    private val largeFolders = LargeFolderDetector()

    private var stepBegan = System.nanoTime()
    private var stepCpu = cpuMark()

    private fun reportLarge(alert: LargeFolderAlert) {
      val mb = (alert.totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
      log("large folder detected: ${alert.fileCount} files, $mb MB")
      runCatching { onLargeFolder?.invoke(alert) }
    }

    private fun flush() {
      if (batch.isEmpty()) return
      val r = store.upsertFromCrawl(batch)
      vectors.removeFiles(r.reset + r.newlySkipped)
      progress(batch.size)
      batch.clear()
      pace(stepBegan, stepCpu)   // paused, or the computer busy: a walk rests between its steps
      stepBegan = System.nanoTime()
      stepCpu = cpuMark()
    }

    /** Walks until nothing is left (true), or until a more urgent job is waiting (false; call again to carry on from the next folder). */
    fun run(): Boolean {
      var first = true
      while (todo.isNotEmpty()) {
        if (!first && mustYield()) break   // the first folder of every call is entered whatever happens, so a call always makes progress
        val d = todo.pollFirst()
        first = false
        val result = Crawler.walk(root, d, recursive || d != dir, skipped, stop = ::mustYield, skippedFiles = skippedFiles) { f ->
          largeFolders.observe(root, f)?.let(::reportLarge)
          val rec = f.record()
          present += rec.uri
          batch += rec
          if (batch.size >= BackgroundTuning.scanFlushBatch) flush()
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
      for (alert in largeFolders.finalizeSubtrees(root)) reportLarge(alert)
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
    publishNow(current = null)
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
  private fun dropUnder(dir: Path): Int {
    val prefix = prefixOf(dir)
    val ids = ArrayList<Long>()
    store.read { db -> db.query("SELECT id FROM files WHERE volume = ? AND uri >= ? AND uri < ?", Crawler.VOLUME, prefix, prefix + "￿") { ids += it.getLong(0) } }
    return drop(ids)
  }

  private fun dropOutsideRoots() {
    val prefixes = roots.map(::prefixOf)
    val pinnedUris = addedFiles.map { it.toUri().toString() }.toHashSet()
    val gone = ArrayList<Long>()
    store.read { db ->
      db.query("SELECT id, uri FROM files WHERE volume = ?", Crawler.VOLUME) { s ->
        val u = s.getText(1)
        if (u !in pinnedUris && prefixes.none { u.startsWith(it) }) gone += s.getLong(0)
      }
    }
    drop(gone)
  }

  /**
   * Takes files out of the index (rows, words and vectors) in small committed batches, so a search is never kept waiting behind one long
   * write and the pass makes steady progress whatever else asks for the worker. Between batches it steps aside for a search, paces itself
   * while paused, and stops when the app closes: what is left is found again by the next walk, which drops it the same way.
   * The write-ahead log is emptied now and then (the deleted words must not linger in it), not after every batch.
   */
  private fun drop(ids: List<Long>): Int {
    if (ids.isEmpty()) return 0
    beforeDrop()
    val rowsBefore = vectors.size
    var done = 0
    var sinceCheckpoint = 0
    var at = 0
    while (at < ids.size && !closed) {
      // Paused, the batches are small so the rest between them (nine times the work, at most PAUSED_REST_MAX_MS) keeps the pass near a tenth of a core.
      val batch = ids.subList(at, minOf(ids.size, at + if (paused) FORGET_BATCH_PAUSED else FORGET_BATCH))
      at += batch.size
      val began = System.nanoTime()
      val beganCpu = cpuMark()
      vectors.removeFiles(batch)
      done += store.markDeleted(batch, checkpoint = false)
      sinceCheckpoint += batch.size
      if (sinceCheckpoint >= FORGET_CHECKPOINT_EVERY) { store.trimWal(0); sinceCheckpoint = 0 }
      forgetCount.forgotten(batch.size)
      searchYield.awaitQuiet { closed || queue.any { it !is Reconcile } }
      pace(began, beganCpu)
    }
    store.trimWal(0)
    bumpProgress()
    log("dropped $done files that are gone")
    if (!closed && (done >= COMPACT_AFTER_FILES || (rowsBefore > 0 && done.toLong() * 10 >= rowsBefore))) compactVectors()
    return done
  }

  /**
   * After a big purge the vector file still holds the chunks the removed rows left empty (rows stay dense: the last row moves into each hole).
   * Cut it back to what the rows need, on the worker, with the vectors briefly locked for the truncation (no data moves). A system that will
   * not truncate a mapped file (Windows) keeps the file as it is until the next start, when it is read afresh.
   */
  private fun compactVectors() {
    val before = vectors.mappedBytes()
    val freed = runCatching { vectors.compact() }.getOrElse { log("the vector file could not be made smaller (${it.javaClass.simpleName})"); 0L }
    if (freed > 0) log("vector file made smaller: ${before / (1024 * 1024)} MB to ${(before - freed) / (1024 * 1024)} MB")
  }

  /** Test hook: runs at the start of every drop (a test makes it throw to see the skip purge retry). */
  @Volatile internal var beforeDrop: () -> Unit = {}

  /** Told how many files a skip purge took out of the index, once it is done: the window says so ("Removed 4,213 files from skipped folders"). */
  @Volatile var onPurged: (files: Int, codeProjects: Boolean) -> Unit = { _, _ -> }

  /** What the forget pass reports as it goes (tests and the harness watch it); the files dropped so far in this run. */
  private val forgetCount = ForgetCount()
  internal class ForgetCount { @Volatile var total = 0L; fun forgotten(n: Int) { total += n } }
  internal val forgottenSoFar: Long get() = forgetCount.total

  /**
   * While paused, background work that still has to run (the first walk after an upgrade, a forget pass) is paced: after a step that took
   * `t` it rests `9 t`, so it uses a tenth of one core, in slices that notice the end of the pause or a closing app. Not paused, it is
   * [Politeness] that decides (when it is on): the step rests until its processor time averages no more than the computer can spare.
   */
  private fun pace(beganNanos: Long, beganCpu: Long = 0L) {
    val wallMs = (System.nanoTime() - beganNanos) / 1_000_000L
    if (paused) {
      restFor((wallMs * PAUSED_REST_FACTOR).coerceAtMost(PAUSED_REST_MAX_MS)) { !paused || closed || queue.any { it !is Reconcile } }
      return
    }
    if (!politeness.enabled) return
    val rest = politeness.restMs(Politeness.Cpu.nowNs() - beganCpu, wallMs)
    syncWorkerQos()
    restFor(rest) { paused || closed || queue.any { it !is Reconcile } }
  }

  /** Keeps [background] and this worker thread's macOS QoS tier synced with the latest user/power reading. */
  private fun syncWorkerQos() {
    background.refresh()
    val t = background.tier
    if (t != workerTier) {
      workerTier = t
      LowPriority.applyTierToThisThread(t)
    }
  }

  /** The processor time now, to compare with later in [pace] and [restAfter]; 0 (and no look at the system) when [Politeness] is off. */
  private fun cpuMark(): Long = if (politeness.enabled) Politeness.Cpu.nowNs() else 0L

  /** Sleeps [ms] in slices of at most 100 ms, and stops early when [stop] says so. */
  private fun restFor(ms: Long, stop: () -> Boolean) {
    var left = ms
    while (left > 0 && !stop()) {
      val step = minOf(left, 100L)
      Thread.sleep(step); left -= step
    }
  }

  /** The reader's turn to give way: after a file that began at [beganNanos] with [beganCpu], rest if the computer has less to spare than the file took. */
  private fun restAfter(beganNanos: Long, beganCpu: Long) {
    if (!politeness.enabled) return
    val wallMs = (System.nanoTime() - beganNanos) / 1_000_000L
    val rest = politeness.restMs(Politeness.Cpu.nowNs() - beganCpu, wallMs)
    syncWorkerQos()
    restFor(rest) { closed || paused || queue.isNotEmpty() }
  }

  // ---- reading -------------------------------------------------------------------------------------------------

  /**
   * Reads a few waiting files. With no model that is the words pass: the text of documents and of pictures goes into the keyword index
   * (the core's WordsPassLoop and queue, as on the phone). With the models it is the whole reading, which takes the stored words
   * as they are. False when there is nothing to read.
   */
  private fun readSome(): Boolean {
    if (paused) return false   // paused is quiet: no decoder search, no speech-pack look, nothing read
    if (kinds.any { it in Crawler.MEDIA_KINDS }) { ensureDecoder(); checkSpeechPack() }   // Settings says so at once when this computer has none
    if (scanning) return false
    if (hold(soft = false) != null) return false
    val ix = indexer ?: return readWords()
    val others = kinds - Crawler.MEDIA_KINDS
    val batch = store.nextPending(BackgroundTuning.readBatch, others)
    val media = mediaCandidate()
    if ((batch.isNotEmpty() || media != null) && hold() != null) return false   // heat and power saving are asked about only when there is something to read
    // The phone's turns: a video or recording when one is owed its fifth of the busy time (or nothing else waits), else a few photos and documents.
    if (media != null && mediaShare.mediaTurn(true, batch.isNotEmpty())) {
      readMedia(media)
      bumpProgress()
      return true
    }
    if (batch.isEmpty()) return false
    for (p in batch) {
      searchYield.awaitQuiet { closed || queue.isNotEmpty() || paused }   // the person is searching: that goes first
      if (closed || queue.isNotEmpty() || paused || hold() != null) break   // a job, Pause, or thermal/battery hold stops mid-batch immediately
      val f = p.file
      publish(current = f.name)
      val began = System.nanoTime()
      val beganCpu = cpuMark()
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
      restAfter(began, beganCpu)   // the computer is busy with something else: the rest counts as part of this file's time, for the finish time shown
      val ns = System.nanoTime() - began
      readNs += ns
      mediaShare.record(false, ns / 1_000_000)
    }
    bumpProgress()
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
    var windowBegan = System.nanoTime()
    var windowCpu = cpuMark()
    try {
      mi.read(p) { ms ->
        mediaShare.record(true, ms, f.kind)
        mediaNs += ms
        publish(current = f.name)
        restAfter(windowBegan, windowCpu)   // a window of speech or frames takes cores for a while: rest as long as the computer's other work asks
        searchYield.awaitQuiet { closed || queue.isNotEmpty() || paused }
        windowBegan = System.nanoTime(); windowCpu = cpuMark()
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
      WordsPassLoop.run(wordsLock, limit = WordsPassLoop.BATCH, stop = { closed || queue.isNotEmpty() || paused }, next = { n -> store.nextWithoutWords(n, on).let { b -> if (b.isNotEmpty() && hold() != null) emptyList() else b } }) { p ->
        searchYield.awaitQuiet { closed || queue.isNotEmpty() || paused }
        publish(current = p.file.name)
        val began = System.nanoTime()
        val beganCpu = cpuMark()
        (wordsReader ?: throw StoreNotReadyException()).read(p.file)
        wordsSessionDone++
        restAfter(began, beganCpu)
        wordsNs += System.nanoTime() - began
        true
      }
    }
    if (done > 0) bumpProgress()
    return done > 0
  }

  /** Files whose words the words pass has not read yet (kinds that are on, not skipped). */
  private fun wordsLeft(on: Set<FileKind>): Int = IndexAdmin.wordsLeft(store, on)

  /**
   * Why reading is on hold right now, or null. Gentle waits for the charger where the OS reports a battery. A full disk and a
   * folder the system will not let Trawl list come before that: neither gets better by waiting for the charger.
   */
  private fun hold(soft: Boolean = true): Hold? = when {
    paused -> Hold.USER
    noAccess -> Hold.NO_PERMISSION
    diskLevel == DiskSpace.Level.LOW -> Hold.STORAGE_LOW
    WorkSpeed.waitsForCharger(speed) && battery.onBattery() == true -> Hold.BATTERY
    lowBattery.holds() -> Hold.LOW_BATTERY   // any speed: the charge is under the floor and the person has not said "read on battery anyway"
    soft && softHolds.thermalHolds() -> Hold.THERMAL
    soft && softHolds.lowPowerHolds() -> Hold.LOW_POWER
    else -> null
  }

  /** "Read on battery anyway": the low-battery wait ends for this stay on the battery (it comes back only after the charger has been plugged in). */
  fun readOnBattery() { lowBattery.override(); stateDirty.set(true) }

  /** "Run anyway" for a heat or power-saving wait: it ends for the rest of this run of the app. */
  fun runAnyway(hold: Hold) { softHolds.runAnyway(hold); stateDirty.set(true) }

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
    if (diskLevel != before) { log("disk space: $before -> $diskLevel (${free / DiskSpace.MB} MB free)"); publishNow(current = null) }
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
    if (noAccess != before) { log(if (noAccess) "the system will not let Trawl list the chosen folders" else "the chosen folders can be listed again"); publishNow(current = null); if (!noAccess) queue.add(Rescan) }
  }

  /** While the index opens or cannot open: tell the window which, in the phone's classes, so it can show the calm card. */
  private fun showOpening() {
    val st = opener.state.value
    val failed = st as? StoreOpener.State.Failed
    _ui.update { it.copy(opening = st is StoreOpener.State.Opening, openProblem = failed?.problem, openStopped = failed?.settled == true) }
  }

  // ---- status --------------------------------------------------------------------------------------------------

  /** The index changed in a way the person should see now (a model attached, a folder skipped, a scan done): published at once. */
  private fun bump() { generation++; publishNow(current = null) }

  /** The index grew a little (a file read, a folder walked): the window hears of it at most about once a second ([throttle]). */
  private fun bumpProgress() { generation++; publish(current = null) }

  /** Decides when the status is worked out again: state changes at once, progress at most once a second, with a final publish when it goes quiet. */
  private val throttle = PublishThrottle()
  @Volatile private var pendingCurrent: String? = null
  /** Set from other threads (Settings: pause, resume): the worker publishes it at once on its next turn. */
  private val stateDirty = java.util.concurrent.atomic.AtomicBoolean(false)
  private var idleRefreshAt = 0L

  /** Progress: the file being read, a walk's tick. Held back when the status went out less than a second ago; [flushStatus] sends it later. */
  private fun publish(current: String?) {
    if (stateDirty.getAndSet(false) || throttle.progress()) publishNow(current) else pendingCurrent = current
  }

  /** A state change: the status goes out now, whatever went out a moment ago. */
  private fun publishNow(current: String?) {
    throttle.immediate()
    pendingCurrent = null
    work(current)
  }

  /** Sends what [publish] held back once its second is over, and a state change someone else asked for. Cheap when there is nothing to send. */
  private fun flushStatus() {
    if (stateDirty.getAndSet(false)) { publishNow(null); return }
    if (throttle.flushDue()) { val c = pendingCurrent; pendingCurrent = null; work(c) }
  }

  /** The idle turn: nothing is being read. Whatever was held back goes out, and the status is looked at again every few seconds (a charger, the disk). */
  private fun idleStatus() {
    val now = System.currentTimeMillis()
    if (stateDirty.getAndSet(false) || throttle.hasPending() || now - idleRefreshAt >= (if (paused) PAUSED_REFRESH_MS else IDLE_REFRESH_MS)) {
      if (throttle.hasPending() && !throttle.flushDue() && !stateDirty.get()) return   // held back; the wait below is cut to when it is due
      idleRefreshAt = now
      publishNow(null)
    }
  }

  private var ftsMergeChangeSeen = -1L
  private var ftsMergeBudget = FTS_MERGE_MAX_SLICES
  private var ftsMergeLastNs = 0L

  /**
   * Incrementally merges fragmented FTS5 LSM b-tree segments (64 pages/slice) while completely idle so prefix queries
   * do not fan out across 15+ unmerged segments. Bounded by [ftsMergeBudget] per write epoch so idle disk I/O stays 0 Hz.
   */
  private fun maybeMergeFtsIdle() {
    if (paused || scanning || queue.isNotEmpty() || searchYield.searching()) return
    val st = opener.current ?: return
    val rev = st.changeCount
    if (rev != ftsMergeChangeSeen) {
      ftsMergeChangeSeen = rev
      ftsMergeBudget = FTS_MERGE_MAX_SLICES
    }
    if (ftsMergeBudget <= 0) return
    val nowNs = System.nanoTime()
    if (ftsMergeLastNs != 0L && (nowNs - ftsMergeLastNs) < FTS_MERGE_INTERVAL_NS) return
    ftsMergeLastNs = nowNs
    if (st.mergeFts(FTS_MERGE_PAGES)) ftsMergeBudget--
  }

  /** How long the idle wait may be: short while an update is held back, long when paused (nothing is going to change but a click, which wakes the worker). */
  private fun idleWaitMs(): Long = throttle.msUntilDue()?.coerceAtLeast(20L) ?: if (paused) PAUSED_REFRESH_MS else BackgroundTuning.idleWaitMs

  /** How many times the status was worked out (a full pass over the counts each). For the CPU harness and the tests, never for decisions. */
  internal val publishCount = java.util.concurrent.atomic.AtomicLong()

  private fun work(current: String?) {
    val store = opener.current ?: return   // the window can ask for a refresh before the index has opened: nothing to count yet
    publishCount.incrementAndGet()
    val s = store.fileStats()   // the files' counts only, and the same answer until something is written; the segment counts are the detail sheet's
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
    // With no model the work is the words pass; with one, the whole reading.
    val wordsLeftAll = wordsLeft(on)
    val wordsLeft = if (ready) 0 else wordsLeftAll
    val work = if (ready) waiting else wordsLeft
    val hold = hold(soft = work > 0)   // heat and power saving only matter when there is something to read: an up-to-date index asks the system nothing
    val phase = when {
      hold == Hold.NO_PERMISSION && roots.isNotEmpty() -> Phase.PAUSED   // nothing is known yet, so there is no "work" to wait on
      scanning -> Phase.SCANNING
      roots.isEmpty() && addedFiles.isEmpty() -> Phase.NOT_STARTED
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
        wordsIn = total - wordsLeftAll, openProblem = null, openStopped = false, opening = false, reembed = reembedNow(store, on),
        disk = diskLevel, freeBytes = freeBytes, blocked = blockedSummary,
      )
    }
  }

  private var reembedFor: IndexStore? = null
  private var reembedCount: ReembedCount? = null

  /** The model-upgrade line of the meter; see [ReembedCount] for why it is not a plain query on every publish. */
  private fun reembedNow(store: IndexStore, on: Set<FileKind>): Pair<Int, Int>? {
    if (embedder == null) return null
    if (reembedFor !== store) {
      reembedFor = store
      reembedCount = ReembedCount(REEMBED_EVERY_MS, { store.meta(Schema.Meta.REEMBED_TOTAL)?.toIntOrNull() ?: 0 }, { store.reembedProgress() })
    }
    return reembedCount?.of(on)
  }

  companion object {
    /** The vectors' own file, next to the database (the phone's name for it). */
    const val VECTORS_FILE = "vectors.bin"

    /** Failed opens in a row with one reason before the quick retries stop: out of memory will not mend by waiting, the rest might. */
    fun openSettleAfter(p: IndexOpenProblem): Int = if (p == IndexOpenProblem.MEMORY) 3 else 8
    /** After that: no timer for out of memory (Try again, or restart), one try every 15 minutes for the rest. */
    fun openSettledWaitMs(p: IndexOpenProblem): Long? = if (p == IndexOpenProblem.MEMORY) null else 15 * 60_000L

    const val DISK_CHECK_MS = 10_000L
    /** Concurrent SQLite reader connections on desktop so interactive typing never queues behind background status/folder counts. */
    private const val DESKTOP_READERS = 4
    /** An idle worker looks at the status again this often (a charger plugged in, the disk filling); paused, less often. */
    private val IDLE_REFRESH_MS = BackgroundTuning.idleRefreshMs
    private val PAUSED_REFRESH_MS = BackgroundTuning.pausedRefreshMs
    private const val ACCESS_CHECK_MS = 3_000L
    private const val OPEN_POLL_MS = 200L
    private const val REEMBED_EVERY_MS = 4_000L
    /** Above this much free space the growth of the index is not worth working out. */
    private const val GROWTH_CHECK_BELOW = 20L * 1024 * 1024 * 1024
    private const val DECODER_RECHECK_MS = 30_000L
    private const val FTS_MERGE_PAGES = 64
    private const val FTS_MERGE_MAX_SLICES = 8
    private const val FTS_MERGE_INTERVAL_NS = 30_000_000_000L
    /** A purge of at least this many files (or a tenth of the vector rows) is followed by cutting the vector file back. */
    internal const val COMPACT_AFTER_FILES = 10_000
    private const val PURGE_RETRY_FIRST_MS = 5_000L
    private const val PURGE_RETRY_MAX_MS = 300_000L
    /** Files dropped per committed batch of the forget pass, and how many are dropped between two emptyings of the write-ahead log. */
    internal val FORGET_BATCH = BackgroundTuning.forgetBatch
    internal val FORGET_BATCH_PAUSED = BackgroundTuning.forgetBatchPaused
    internal val FORGET_CHECKPOINT_EVERY = BackgroundTuning.forgetCheckpointEvery
    private val PAUSED_REST_FACTOR = BackgroundTuning.pausedRestFactor
    private val PAUSED_REST_MAX_MS = BackgroundTuning.pausedRestMaxMs

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
  val FREE_MS: Long = BackgroundTuning.reconcileFreeMs
  val FACTOR: Long = BackgroundTuning.reconcileFactor
  val MAX_WAIT_MS: Long = BackgroundTuning.reconcileMaxWaitMs

  /** When the next walk may start, given that one ended at [now] after taking [tookMs]. */
  fun notBefore(now: Long, tookMs: Long): Long = if (tookMs <= FREE_MS) 0L else now + minOf(tookMs * FACTOR, MAX_WAIT_MS)
}
