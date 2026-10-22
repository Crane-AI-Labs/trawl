package com.craneai.findr.ui.fake

import android.app.Application
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.fileOrder
import com.craneai.findr.core.sortRanked
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stand-in engine so the UI can be built and demoed before the real one exists: sample files in a Ugandan context,
 * a simple fake search over them, and a simulated indexing run (see [FakeIndexingSim]). The lead swaps in the real
 * engine in `createEngine`. Nothing here touches the disk or the network.
 */
class FakeSearchEngine(@Suppress("unused") private val app: Application) : SearchEngine {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val entries: List<FakeEntry> = FakeData.build(System.currentTimeMillis())

  private val settingsFlow = MutableStateFlow(FindrSettings())
  override val settings: StateFlow<FindrSettings> = settingsFlow

  private val sim = FakeIndexingSim(
    scope = scope,
    onlyWhileCharging = { settingsFlow.value.onlyWhileCharging },
    sampleNames = entries.groupBy({ it.file.kind }, { it.file.name }),
  )
  override val status: StateFlow<IndexStatus> = sim.status

  override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
    delay(120) // a real search takes a moment; keep the loading path honest
    val s = sim.status.value
    val meaningReady = s.phase != IndexPhase.NOT_STARTED && s.phase != IndexPhase.SCANNING && s.filesUnderstood > 0
    val (ranked, strong) = FakeSearch.run(entries, query, filter, Int.MAX_VALUE, useMeaning = meaningReady)
    val dated = ranked.filter { view.dates?.contains(it.file.dateMillis) != false }
    val strongKept = ranked.take(strong).count { view.dates?.contains(it.file.dateMillis) != false }
    val hits = sortRanked(dated, strongKept, view.sort) { it.file }.take(limit)
    return SearchResults(
      query = query,
      hits = hits,
      strongCount = strongKept.coerceAtMost(hits.size),
      tookMs = 120,
      indexIncomplete = s.phase != IndexPhase.UP_TO_DATE,
    )
  }

  override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> =
    entries.map { it.file }
      .filter { it.kind in filter.kinds && it.kind != FileKind.OTHER && view.dates?.contains(it.dateMillis) != false }
      .sortedWith(fileOrder(view.sort))
      .take(limit)

  override suspend fun folderCounts(): Map<String, Int> = mapOf(
    "camera" to 4210, "screenshots" to 868, "whatsapp_images" to 5120, "whatsapp_video" to 214, "whatsapp_documents" to 96,
    "whatsapp_voice" to 1450, "downloads" to 331, "documents" to 58, "music" to 402, "other" to 777,
  )

  override fun startIndexing() = sim.start()
  override fun pauseIndexing() = sim.pause()
  override fun resumeIndexing() = sim.resume()

  override suspend fun deleteIndex(): Forgotten {
    sim.reset()
    delay(400)
    sim.pause()   // like the real engine: paused until the person resumes
    return Forgotten(files = 3, bytes = 12_400_000)
  }

  override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {
    settingsFlow.value = transform(settingsFlow.value)
  }
}
