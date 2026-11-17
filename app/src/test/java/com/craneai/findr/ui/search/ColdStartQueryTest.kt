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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdStartQueryTest {
  private class SlowEngine(val waitForMeaning: Boolean = false, val unchanged: Boolean = false, val emptyWords: Boolean = false) : SearchEngine {
    override val status = MutableStateFlow(IndexStatus(filesKnown = 2))
    override val settings = MutableStateFlow(FindrSettings())
    override val meaningReady = MutableStateFlow(false)
    val words = IndexedFile(1, "fake://words", "notes/", "money statement.txt", FileKind.DOCUMENT, null, 10, 10)
    val meaning = IndexedFile(2, "fake://meaning", "notes/", "farming notes.txt", FileKind.DOCUMENT, null, 20, 20)
    val queries = ArrayList<String>()
    val views = ArrayList<ResultView>()

    private fun answer(query: String, withMeaning: Boolean, complete: Boolean = true): SearchResults {
      val hits = if (withMeaning && !unchanged) listOf(SearchHit(meaning, 2.0, MatchReason.MEANING), SearchHit(words, 1.0, MatchReason.WORDS))
      else if (emptyWords) emptyList() else listOf(SearchHit(words, 1.0, MatchReason.WORDS))
      return SearchResults(query, hits, hits.size, 0, indexIncomplete = false, complete = complete)
    }

    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView) = answer(query, meaningReady.value)
    override fun searchProgressive(query: String, filter: KindFilter, limit: Int, meaningDelayMs: Long, view: ResultView) = flow {
      queries += query
      views += view
      val ready = meaningReady.value
      emit(answer(query, false, complete = !ready && !waitForMeaning))
      if (waitForMeaning || ready) {
        meaningReady.first { it }
        delay(50)
        emit(answer(query, true))
      }
    }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView) = listOf(words, meaning)
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  private val dispatcher = StandardTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }
  private fun ids(state: SearchUiState) = state.feed.shownFiles().map { it.id }

  @Test fun aSettledColdWordsAnswerRefreshesOnceWhenTheEngineIsReadyWithoutAStatusChange() = runTest(dispatcher) {
    val engine = SlowEngine()
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("coffee harvest earnings"); advanceUntilIdle()
    assertEquals(listOf(engine.words.id), ids(vm.state.value))
    assertFalse(vm.state.value.searching)
    val status = engine.status.value
    vm.toggleSelected(engine.words)
    engine.meaningReady.value = true
    advanceUntilIdle()
    assertEquals(listOf(engine.meaning.id, engine.words.id), ids(vm.state.value))
    assertEquals(setOf(engine.words.id), vm.state.value.selected)
    assertTrue(vm.state.value.quietRefresh)
    assertEquals(status, engine.status.value)
    assertEquals(listOf("coffee harvest earnings", "coffee harvest earnings"), engine.queries)
    engine.meaningReady.value = true; advanceUntilIdle()
    assertEquals(2, engine.queries.size)
  }

  @Test fun anUnchangedWarmAnswerKeepsTheSameFeedAndSelectionWithoutAnInterimFlash() = runTest(dispatcher) {
    val engine = SlowEngine(unchanged = true)
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("money"); advanceUntilIdle()
    vm.toggleSelected(engine.words)
    val feed = vm.state.value.feed
    val seen = ArrayList<List<FeedItem>>()
    val observer = backgroundScope.launch(dispatcher) { vm.state.collect { seen += it.feed } }
    runCurrent()
    engine.meaningReady.value = true; advanceUntilIdle()
    assertSame(feed, vm.state.value.feed)
    assertTrue(seen.all { it === feed })
    assertEquals(setOf(engine.words.id), vm.state.value.selected)
    assertFalse(vm.state.value.searching)
    assertEquals(2, engine.queries.size)
    observer.cancel()
  }

  @Test fun aProgressiveQueryAlreadyWaitingForWarmUpFinishesWithoutADuplicateSearch() = runTest(dispatcher) {
    val engine = SlowEngine(waitForMeaning = true)
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("coffee harvest earnings"); advanceUntilIdle()
    assertEquals(listOf(engine.words.id), ids(vm.state.value))
    assertTrue(vm.state.value.searching)
    engine.meaningReady.value = true; advanceUntilIdle()
    assertEquals(listOf(engine.meaning.id, engine.words.id), ids(vm.state.value))
    assertFalse(vm.state.value.searching)
    assertEquals(1, engine.queries.size)
  }

  @Test fun clearingTheQueryBeforeReadyDoesNotBringItsResultsBack() = runTest(dispatcher) {
    val engine = SlowEngine()
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("money"); advanceUntilIdle()
    vm.setQuery("")
    engine.meaningReady.value = true; advanceUntilIdle()
    assertEquals(SearchMode.RECENT, vm.state.value.mode)
    assertEquals("", vm.state.value.query)
    assertEquals(listOf("money"), engine.queries)
  }

  @Test fun readinessRefreshesOnlyTheCurrentQuestionAndKeepsItsChips() = runTest(dispatcher) {
    val engine = SlowEngine()
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("money"); advanceUntilIdle()
    vm.setQuery("harvest"); advanceUntilIdle()
    vm.setFilter(KindFilter.DOCUMENTS); advanceUntilIdle()
    vm.setSort(SortOrder.NAME); advanceUntilIdle()
    val asked = engine.views.last()
    val queries = engine.queries.size
    engine.meaningReady.value = true; advanceUntilIdle()
    assertEquals(queries + 1, engine.queries.size)
    assertEquals("harvest", engine.queries.last())
    assertEquals(asked, engine.views.last())
    assertEquals(KindFilter.DOCUMENTS, vm.state.value.filter)
    assertEquals(SortOrder.NAME, vm.state.value.sort)
  }

  @Test fun aShortWordsQueryDoesNotRerunWhenMeaningBecomesReady() = runTest(dispatcher) {
    val engine = SlowEngine()
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("mo"); advanceUntilIdle()
    engine.meaningReady.value = true; advanceUntilIdle()
    assertEquals(listOf("mo"), engine.queries)
  }

  @Test fun typingBeforeTheHomeListLoadsStillGetsAQuietReadinessRefresh() = runTest(dispatcher) {
    val engine = SlowEngine()
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    vm.setQuery("money")
    advanceUntilIdle()
    assertEquals(SearchMode.RESULTS, vm.state.value.mode)
    engine.meaningReady.value = true; advanceUntilIdle()
    assertTrue(vm.state.value.quietRefresh)
    assertEquals(listOf(engine.meaning.id, engine.words.id), ids(vm.state.value))
  }

  @Test fun anEmptyWordsAnswerIsAlsoReplacedWhenMeaningBecomesReady() = runTest(dispatcher) {
    val engine = SlowEngine(emptyWords = true)
    val vm = SearchViewModel(engine, feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("harvest"); advanceUntilIdle()
    assertTrue(vm.state.value.nothingFound)
    engine.meaningReady.value = true; advanceUntilIdle()
    assertFalse(vm.state.value.nothingFound)
    assertEquals(listOf(engine.meaning.id, engine.words.id), ids(vm.state.value))
    assertEquals(2, engine.queries.size)
  }
}
