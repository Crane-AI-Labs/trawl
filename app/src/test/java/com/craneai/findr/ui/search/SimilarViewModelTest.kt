package com.craneai.findr.ui.search

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
import com.craneai.findr.core.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** The "Find similar" state of the search screen: the chip, the empty query, and what takes it away. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimilarViewModelTest {
  private val now = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZoneId.of("Africa/Kampala"))

  private class Asked(val seed: IndexedFile, val filter: KindFilter, val view: ResultView)

  private class Engine : SearchEngine {
    val similars = ArrayList<Asked>()
    val searches = ArrayList<String>()
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    val files = (1L..4L).map { IndexedFile(it, "fake://$it", "x/$it", "IMG_$it.jpg", FileKind.IMAGE, null, it * 10, it) }
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      searches += query
      return SearchResults(query, files.map { SearchHit(it, 1.0, MatchReason.MEANING) }, strongCount = files.size, tookMs = 1, indexIncomplete = false)
    }
    /** More than the four files: how many hits `similar` returns (0 = the default of every file but the seed), and whether the index is unfinished. */
    var similarHits = 0
    var unfinished = false
    override suspend fun similar(file: IndexedFile, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      similars += Asked(file, filter, view)
      val pool = if (similarHits == 0) files else (1L..similarHits + 1L).map { IndexedFile(it, "fake://$it", "x/$it", "IMG_$it.jpg", FileKind.IMAGE, null, it * 10, it) }
      val hits = pool.filter { it.id != file.id }.map { SearchHit(it, 3.0, MatchReason.MEANING) }
      return SearchResults("", hits, strongCount = hits.size, tookMs = 1, indexIncomplete = unfinished)
    }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> = files
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  private val dispatcher = StandardTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun TestScope.vm(engine: Engine) =
    SearchViewModel(engine, clock = { now }, weekStart = { DayOfWeek.MONDAY }, feedDispatcher = dispatcher).also { advanceUntilIdle() }

  @Test fun findSimilarShowsTheChipTheResultsAndLeavesTheBoxEmpty() = runTest(dispatcher) {
    val e = Engine(); val vm = vm(e)
    vm.setQuery("beach"); advanceUntilIdle()
    vm.findSimilar(e.files[0]); advanceUntilIdle()
    val s = vm.state.value
    assertEquals(SimilarTo(1L, "IMG_1.jpg"), s.similar)
    assertEquals("", s.query)
    assertEquals(SearchMode.RESULTS, s.mode)
    assertEquals(SortOrder.BEST, s.sort)
    assertEquals(1, e.similars.size)
    assertEquals(3, s.feed.count { it is FeedItem.Cell || it is FeedItem.Row })   // the seed is not in the list the engine returned
  }

  @Test fun theStillLearningNoteShowsOnlyForThinSimilarResults() = runTest(dispatcher) {
    val e = Engine().apply { unfinished = true }; val vm = vm(e)
    vm.findSimilar(e.files[0]); advanceUntilIdle()
    assertTrue(vm.state.value.indexIncomplete)                       // 3 hits while the index is unfinished: thin
    e.similarHits = 15
    vm.clearSimilar(); advanceUntilIdle()
    vm.findSimilar(e.files[0]); advanceUntilIdle()
    assertFalse(vm.state.value.indexIncomplete)                      // a full list reads the same as on a finished index
    assertEquals(15, vm.state.value.feed.count { it is FeedItem.Cell || it is FeedItem.Row })
  }

  @Test fun theChipsCrossGoesBackHome() = runTest(dispatcher) {
    val e = Engine(); val vm = vm(e)
    vm.findSimilar(e.files[1]); advanceUntilIdle()
    vm.clearSimilar(); advanceUntilIdle()
    assertNull(vm.state.value.similar)
    assertEquals(SearchMode.RECENT, vm.state.value.mode)
    vm.clearSimilar(); advanceUntilIdle()   // already off: nothing happens
    assertNull(vm.state.value.similar)
  }

  @Test fun typingAWordLeavesSimilarAndSearchesTheWord() = runTest(dispatcher) {
    val e = Engine(); val vm = vm(e)
    vm.findSimilar(e.files[0]); advanceUntilIdle()
    vm.setQuery("receipt"); advanceUntilIdle()
    assertNull(vm.state.value.similar)
    assertEquals("receipt", e.searches.last())
    assertEquals(1, e.similars.size)
  }

  @Test fun theKindFilterAndTheSortApplyToSimilarToo() = runTest(dispatcher) {
    val e = Engine(); val vm = vm(e)
    vm.findSimilar(e.files[0]); advanceUntilIdle()
    vm.setFilter(KindFilter.PHOTOS); advanceUntilIdle()
    assertEquals(KindFilter.PHOTOS, e.similars.last().filter)
    assertNotNull(vm.state.value.similar)
    vm.setSort(SortOrder.OLDEST); advanceUntilIdle()
    assertEquals(SortOrder.OLDEST, e.similars.last().view.sort)
    assertEquals(1L, e.similars.last().seed.id)
    assertFalse(vm.state.value.nothingFound)
  }
}
