package trawl.desktop.shell

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import trawl.desktop.AppController
import trawl.desktop.Platform
import trawl.desktop.SearchApi

class QuickPanelCompletenessTest {
  private fun waitFor(what: String, timeoutMs: Long = 20_000L, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      if (cond()) return
      Thread.sleep(40)
    }
    throw AssertionError("Timed out waiting for: $what")
  }

  private fun hit(id: Long, kind: FileKind, name: String) = QuickHit(
    IndexedFile(id, "file:///$name", name, name, kind, null, 100L, 1_000L),
    snippet = null,
  )

  @Test fun controllerQuickSearchReturnsAllMatchingItemsWithoutArbitrary30Cap() = runBlocking {
    val data = Files.createTempDirectory("trawl-quick-all-data").toFile()
    val docs = Files.createTempDirectory("trawl-quick-all-docs").toFile()
    val totalFiles = 45
    for (i in 1..totalFiles) {
      File(docs, "quarterly-budget-%02d.txt".format(i)).writeText("quarterly budget allocation item $i")
    }
    val c = AppController(
      dataDir = data,
      userFolders = Platform.UserFolders(data, data, data),
      logSink = {},
    )
    try {
      c.readOnBattery()
      c.addFolder(docs)
      waitFor("all $totalFiles files indexed") { c.service.recent(KindFilter.ALL, 100).size >= totalFiles }

      val quick = ControllerQuickSearch(c)
      val hits = quick.search("quarterly").first { it.size >= totalFiles }
      assertEquals(totalFiles, hits.size, "Quick Panel must return ALL $totalFiles matching items without 30-item truncation")
      assertTrue(SearchApi.LIMIT >= 1000, "SearchApi.LIMIT must stay >= 1000")

      // QuickPanelState exposes all 45 hits and navigates all the way to the last hit
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val fakeApi = object : QuickSearchApi {
          override fun search(query: String): Flow<List<QuickHit>> = flowOf(hits)
          override fun open(file: IndexedFile) {}
          override fun showInFolder(file: IndexedFile) {}
        }
        val state = QuickPanelState(fakeApi, scope, onClose = {}, debounceMs = 0L)
        state.setQuery("quarterly")
        waitFor("QuickPanelState receives all $totalFiles hits") { state.hits.value.size == totalFiles }
        assertEquals(totalFiles, state.hits.value.size)
        state.onKey(QuickKey.LAST)
        assertEquals(totalFiles - 1, state.selected.value)
      } finally {
        scope.cancel()
      }
    } finally {
      c.close()
      data.deleteRecursively()
      docs.deleteRecursively()
    }
  }

  @Test fun quickPanelPrioritizesImagesAndDocumentsBeforeVideosAndAudioUnlessMediaQuery() {
    val hits = listOf(
      hit(1, FileKind.VIDEO, "clip.mp4"),
      hit(2, FileKind.AUDIO, "memo.m4a"),
      hit(3, FileKind.IMAGE, "receipt.jpg"),
      hit(4, FileKind.DOCUMENT, "notes.md"),
      hit(5, FileKind.SCREENSHOT, "screen.png"),
      hit(6, FileKind.PDF, "contract.pdf"),
    )

    // General query: Images & Documents appear first (preserving relative relevance), Videos & Audio follow
    val general = ControllerQuickSearch.prioritizeHits(hits, "kampala")
    assertEquals(listOf(3L, 4L, 5L, 6L, 1L, 2L), general.map { it.file.id })

    // Media-specific queries keep top video/audio match first
    val videoQuery = ControllerQuickSearch.prioritizeHits(hits, "clip video")
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), videoQuery.map { it.file.id })

    val audioQuery = ControllerQuickSearch.prioritizeHits(hits, "voice recording")
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), audioQuery.map { it.file.id })
  }
}
