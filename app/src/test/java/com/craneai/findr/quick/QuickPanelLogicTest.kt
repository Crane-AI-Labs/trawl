package com.craneai.findr.quick

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchHit
import com.craneai.findr.core.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickPanelLogicTest {
  private val dispatcher = StandardTestDispatcher()

  @Before fun setupMain() { Dispatchers.setMain(dispatcher) }
  @After fun tearDownMain() { Dispatchers.resetMain() }

  private fun hit(id: Long, kind: FileKind) = SearchHit(
    file = IndexedFile(id, "fake://$id", "dir/$id", "f$id", kind, null, 100L, id * 1000L),
    score = 10.0 - id * 0.01,
    reason = MatchReason.WORDS,
  )

  private fun stubEngine(allHits: List<SearchHit>, strongCount: Int = allHits.size): SearchEngine = object : SearchEngine {
    override val status = MutableStateFlow(IndexStatus(filesKnown = allHits.size, filesSearchable = allHits.size))
    override val settings = MutableStateFlow(FindrSettings())
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView) =
      SearchResults(query, allHits.take(limit), strongCount.coerceAtMost(limit), 1L, indexIncomplete = false)
    override fun searchProgressive(query: String, filter: KindFilter, limit: Int, meaningDelayMs: Long, view: ResultView): Flow<SearchResults> =
      flowOf(SearchResults(query, allHits.take(limit), strongCount.coerceAtMost(limit), 1L, indexIncomplete = false))
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView) = emptyList<IndexedFile>()
    override fun startIndexing() = Unit
    override fun pauseIndexing() = Unit
    override fun resumeIndexing() = Unit
    override suspend fun deleteIndex() = Forgotten(0, 0)
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) = Unit
  }

  @Test fun `tile is offered once to a new person whose search found something`() {
    assertTrue(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 36, hits = 3, cameFromTile = false))
  }

  @Test fun `never offered after any answer`() {
    for (s in listOf(TileOfferState.OFFERED, TileOfferState.ADDED, TileOfferState.DECLINED)) {
      assertFalse("state $s", TileOffer.shouldOffer(s, sdk = 36, hits = 3, cameFromTile = false))
    }
  }

  @Test fun `not offered before there is a result, below Android 13, or to someone who came by the tile`() {
    assertFalse(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 36, hits = 0, cameFromTile = false))
    assertFalse(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 32, hits = 3, cameFromTile = false))
    assertFalse(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 36, hits = 3, cameFromTile = true))
  }

  @Test fun `quick search does not truncate at 30 and returns all scrollable matches`() = runTest(dispatcher) {
    assertTrue("QuickSearchViewModel.LIMIT should allow scrolling all matches", QuickSearchViewModel.LIMIT >= 500)
    val eighty = (1L..80L).map { hit(it, if (it % 2L == 0L) FileKind.IMAGE else FileKind.PDF) }
    val vm = QuickSearchViewModel(stubEngine(eighty))
    vm.setQuery("receipt")
    advanceUntilIdle()
    assertEquals(80, vm.state.value.hits.size)
  }

  @Test fun `quick search puts images and documents first unless video or audio is queried`() = runTest(dispatcher) {
    val mixed = listOf(
      hit(1, FileKind.VIDEO),
      hit(2, FileKind.AUDIO),
      hit(3, FileKind.IMAGE),
      hit(4, FileKind.DOCUMENT),
    )
    val vm = QuickSearchViewModel(stubEngine(mixed))
    vm.setQuery("invoice")
    advanceUntilIdle()
    assertEquals(listOf(3L, 4L, 1L, 2L), vm.state.value.hits.map { it.file.id })

    vm.setQuery("voice recording")
    advanceUntilIdle()
    assertEquals(listOf(1L, 2L, 3L, 4L), vm.state.value.hits.map { it.file.id })
  }

  // Copy's path resolution (formerly QuickActions.pathFromIndex) now lives in, and is tested by,
  // com.craneai.findr.ui.common.FileActionsTest: the panel's Copy delegates to FileActions.clipboardUri.
}
