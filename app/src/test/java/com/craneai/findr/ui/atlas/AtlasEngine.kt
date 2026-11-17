package com.craneai.findr.ui.atlas

import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.DeliveryService
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.KindProgress
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.core.ModelPresence
import com.craneai.findr.core.ModelUpdate
import com.craneai.findr.core.PackIds
import com.craneai.findr.core.PackManager
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackState
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.QuickLookProgress
import com.craneai.findr.core.Refusal
import com.craneai.findr.core.RemovableFile
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.UpdateKind
import com.craneai.findr.core.fileOrder
import com.craneai.findr.core.sortRanked
import com.craneai.findr.ui.fake.FakeData
import com.craneai.findr.ui.fake.FakeEntry
import com.craneai.findr.ui.fake.FakeSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** A fixed "now" (Tuesday 20 October 2026, 10:00 UTC) so every run draws the same dates. */
val ATLAS_NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneOffset.UTC)
val ATLAS_NOW_MS: Long = ATLAS_NOW.toInstant().toEpochMilli()

/** Index states the atlas draws. Numbers are a plausible 5,400-file phone. */
object Status {
  private fun kinds(frac: Double) = mapOf(
    FileKind.IMAGE to KindProgress(3200, (3200 * frac).toInt()),
    FileKind.SCREENSHOT to KindProgress(870, (870 * frac).toInt()),
    FileKind.VIDEO to KindProgress(214, (214 * frac).toInt()),
    FileKind.DOCUMENT to KindProgress(96, (96 * frac).toInt()),
    FileKind.AUDIO to KindProgress(1020, (1020 * frac).toInt()),
  )

  // Each state says whether the model is on the phone, as the real engine does from its first status (ModelPresence): 0 bytes is MISSING.
  val notStarted = IndexStatus(phase = IndexPhase.NOT_STARTED, models = ModelPresence.MISSING)
  val scanning = IndexStatus(phase = IndexPhase.SCANNING, filesKnown = 1832, modelBytes = 310_000_000, models = ModelPresence.INSTALLED, modelReady = true)
  val upToDate = IndexStatus(
    phase = IndexPhase.UP_TO_DATE, filesKnown = 5400, filesSearchable = 5400, filesUnderstood = 5400, byKind = kinds(1.0),
    modelBytes = 310_000_000, models = ModelPresence.INSTALLED, indexBytes = 96_000_000, searchMemoryBytes = 31_000_000, estimatedSearchMemoryBytes = 31_000_000,
    quickLook = QuickLookProgress(known = 4300, ready = 4300, full = 4300),
  )
  val indexing = IndexStatus(
    phase = IndexPhase.INDEXING, filesKnown = 5400, filesSearchable = 5400, filesUnderstood = 1800, byKind = kinds(0.33), etaSeconds = 3 * 3600 + 1200,
    ratePerHour = 900.0, modelBytes = 310_000_000, models = ModelPresence.INSTALLED, indexBytes = 52_000_000, estimatedIndexBytes = 96_000_000, currentFile = "IMG_20261012_1832.jpg",
    searchMemoryBytes = 10_000_000, estimatedSearchMemoryBytes = 31_000_000, quickLook = QuickLookProgress(known = 4300, ready = 3900, full = 1700),
  )
  val indexingNoModel = indexing.copy(modelBytes = 0, models = ModelPresence.MISSING, modelReady = false, filesUnderstood = 0, byKind = kinds(0.0))
  val waitingForModels = IndexStatus(phase = IndexPhase.INDEXING, filesKnown = 5400, filesSearchable = 2100, filesUnderstood = 0, modelReady = false, modelBytes = 0, models = ModelPresence.MISSING)
  fun paused(reason: PauseReason) = indexing.copy(
    phase = IndexPhase.PAUSED, pauseReason = reason, charging = reason != PauseReason.NOT_CHARGING,
    needsCharger = reason == PauseReason.NOT_CHARGING, etaSeconds = null,
  )
  val error = IndexStatus(phase = IndexPhase.ERROR, filesKnown = 5400, lastError = "storage", openProblem = IndexOpenProblem.STORAGE_FULL, models = ModelPresence.MISSING)
  val errorBusy = error.copy(openProblem = IndexOpenProblem.BUSY)
  val reembedding = indexing.copy(reembed = com.craneai.findr.core.ReembedMeter(done = 1200, total = 5400))
}

/**
 * A deterministic engine for the atlas: the app's own sample files and search (FakeSearch), but with no delays and no timers, and with every
 * state a person can meet set by hand (index phase, model delivery, add-on packs, settings). It is the engine behind the REAL screens.
 */
class AtlasEngine(
  status: IndexStatus = Status.upToDate,
  settings: FindrSettings = FindrSettings(),
  delivery: DeliveryState = DeliveryState(),
  packs: Map<String, PackState> = PackIds.ALL.associateWith { PackState.NotInstalled },
  corpus: Corpus = Corpus.FULL,
  var hidden: Int = 0,
  var meaning: Boolean = true,
) : SearchEngine {
  private val entries: MutableList<FakeEntry> = FakeData.build(ATLAS_NOW_MS).filter {
    when (corpus) {
      Corpus.FULL -> true
      Corpus.MEDIA_ONLY -> it.file.kind in setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)
      Corpus.DOCS_ONLY -> it.file.kind in setOf(FileKind.DOCUMENT, FileKind.PDF)
      Corpus.EMPTY -> false
    }
  }.toMutableList()

  /** A new photo arrives (the camera, a download) between two looks at the list: it is the newest file. */
  fun arrive(name: String = "IMG_ARRIVED.jpg") {
    val src = entries.first { it.file.kind == FileKind.IMAGE }
    val id = entries.maxOf { it.file.id } + 1
    entries.add(FakeEntry(src.file.copy(id = id, uri = "fake://media/$id", name = name, relativePath = "DCIM/Camera/$name", modifiedMillis = ATLAS_NOW_MS + 1000)))
  }
  val files: List<IndexedFile> get() = entries.map { it.file }

  val statusFlow = MutableStateFlow(status)
  val settingsFlow = MutableStateFlow(settings)
  val deliveryFlow = MutableStateFlow(delivery)
  val packFlow = MutableStateFlow(packs)
  val repairingFlow = MutableStateFlow(false)
  var searches = 0

  override val status: StateFlow<IndexStatus> = statusFlow
  override val settings: StateFlow<FindrSettings> = settingsFlow
  override val searchRepairing: StateFlow<Boolean> = repairingFlow

  override val delivery: DeliveryService = object : DeliveryService {
    override val state: StateFlow<DeliveryState> = deliveryFlow
    override fun downloadUrl() = "https://example.invalid/models"
    override fun setChoice(choice: ModelChoice) { deliveryFlow.value = deliveryFlow.value.copy(choice = choice) }
    override fun watch(on: Boolean) {}
    override fun installFrom(uri: String) {}
    override fun cancel() {}
    override fun acknowledge() { deliveryFlow.value = deliveryFlow.value.copy(phase = DeliveryPhase.IDLE, refusal = null) }
    override fun removeDownloaded() {}
    override fun keepDownloaded() {}
    override fun dismissUpdate() { deliveryFlow.value = deliveryFlow.value.copy(update = null) }
  }

  override val packs: PackManager = object : PackManager {
    override val states: StateFlow<Map<String, PackState>> = packFlow
    override fun install(id: String) {}
    override fun remove(id: String) { packFlow.value = packFlow.value + (id to PackState.NotInstalled) }
    override fun decline(id: String) {}
    override fun howToAdd(id: String): PackCopy? = null
  }

  override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
    searches++
    val (ranked, strong) = FakeSearch.run(entries, query, filter, Int.MAX_VALUE, useMeaning = meaning)
    val dated = ranked.filter { view.dates?.contains(it.file.dateMillis) != false }
    val strongKept = ranked.take(strong).count { view.dates?.contains(it.file.dateMillis) != false }
    val hits = sortRanked(dated, strongKept, view.sort) { it.file }.take(limit)
    return SearchResults(
      query = query, hits = hits, strongCount = strongKept.coerceAtMost(hits.size), tookMs = 12,
      indexIncomplete = statusFlow.value.phase != IndexPhase.UP_TO_DATE, hiddenCount = hidden,
    )
  }

  /** A test holds the home list's reload here (frame-stepping tests: the real engine takes 0.1 to 1 s to answer `recent`). */
  @Volatile var recentGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

  override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> {
    recentGate?.await()
    return recentNow(filter, limit, view)
  }

  private fun recentNow(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> =
    entries.map { it.file }
      .filter { it.kind in filter.kinds && it.kind != FileKind.OTHER && view.dates?.contains(it.dateMillis) != false }
      .sortedWith(fileOrder(view.sort))
      .take(limit)

  override suspend fun recentHiddenCount(filter: KindFilter, dates: com.craneai.findr.core.DateRange?): Int = hidden

  override suspend fun folderCounts(): Map<String, Int> = mapOf(
    "camera" to 4210, "screenshots" to 868, "whatsapp_images" to 5120, "whatsapp_video" to 214, "whatsapp_documents" to 96,
    "whatsapp_voice" to 1450, "downloads" to 331, "documents" to 58, "music" to 402, "other" to 777,
  )

  override fun startIndexing() {}
  override fun pauseIndexing() {}
  override fun resumeIndexing() {}
  override suspend fun deleteIndex(): Forgotten = Forgotten(files = 3, bytes = 12_400_000)
  override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) { settingsFlow.value = transform(settingsFlow.value) }
}

/** Which of the sample files the phone holds. MEDIA_ONLY is a phone with photos, screenshots and videos but no documents. */
enum class Corpus { FULL, MEDIA_ONLY, DOCS_ONLY, EMPTY }

/** Model delivery states (the first-run card and the update notice). */
object Deliveries {
  val idle = DeliveryState(sizeMb = 670)
  val idleWithChoice = DeliveryState(sizeMb = 670, choiceOffered = true, standardMb = 670, saveDataMb = 330, choice = ModelChoice.STANDARD)
  val downloading = DeliveryState(phase = DeliveryPhase.DOWNLOADING, doneBytes = 312_000_000, totalBytes = 670_000_000, sizeMb = 670)
  val installing = DeliveryState(phase = DeliveryPhase.INSTALLING, doneBytes = 410_000_000, totalBytes = 670_000_000, sizeMb = 670)
  val installingUnknown = installing.copy(totalBytes = 0, doneBytes = 0)
  val done = DeliveryState(phase = DeliveryPhase.DONE, sizeMb = 670)
  val doneRemovable = done.copy(removable = RemovableFile("trawl-models-v3.zip", 670_000_000))
  fun refused(r: Refusal, doneBytes: Long = 0, total: Long = 0) = DeliveryState(phase = DeliveryPhase.REFUSED, refusal = r, sizeMb = 670, doneBytes = doneBytes, totalBytes = total)
  val update = DeliveryState(sizeMb = 670, installedChoice = ModelChoice.STANDARD, update = ModelUpdate(latestVersion = 4, installedVersion = 3, sizeMb = 120, kind = UpdateKind.PACK_ONLY))
  val updateReread = update.copy(update = ModelUpdate(4, 3, 640, UpdateKind.FULL_REEMBED))
  val saveDataInstalled = DeliveryState(sizeMb = 330, choiceOffered = true, installedChoice = ModelChoice.SAVE_DATA, standardMb = 670, saveDataMb = 330)
  val saveDataDownloading = saveDataInstalled.copy(phase = DeliveryPhase.DOWNLOADING, doneBytes = 200_000_000, totalBytes = 670_000_000)
  val saveDataUpgraded = saveDataInstalled.copy(upgradedToStandard = true, phase = DeliveryPhase.DONE)
  val standardInstalled = DeliveryState(sizeMb = 670, choiceOffered = true, installedChoice = ModelChoice.STANDARD, standardMb = 670, saveDataMb = 330)
}
