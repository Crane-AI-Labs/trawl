package com.craneai.findr.ui.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrLog
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The FindrPerf marks (words_ms / meaning_ms) measure keystroke to pixels. The screen calls feedDrawn() one frame after each NEW feed is
 * composed; an answer whose list equals the one already on screen composes nothing, so the view model must close its mark itself.
 * (Regression, 2026-10: with the D19c words-first weight fixed, consecutive prefixes returned equal lists and their marks went missing,
 * which looked like a drop from 28 to 18 of 40 typed prefixes answered.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerfMarkTest {
  private val lines = ArrayList<String>()
  private val dispatcher = StandardTestDispatcher()

  @Before fun setUp() {
    Dispatchers.setMain(dispatcher)
    FindrLog.detail = false
    FindrLog.tap = { _, tag, line -> if (tag == "FindrPerf") lines += line }
  }

  @After fun tearDown() { Dispatchers.resetMain(); FindrLog.tap = null }

  private fun file(id: Long) = IndexedFile(id, "fake://$id", "x/$id", "f$id", FileKind.PDF, null, id, id)

  /** Every query returns the same two files; the words answer is incomplete, then the meaning answer completes (like ProgressiveSearch). */
  private class SameListEngine(val files: List<IndexedFile>) : SearchEngine {
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    private fun results(q: String, complete: Boolean) =
      SearchResults(q, files.map { SearchHit(it, 1.0, MatchReason.WORDS) }, strongCount = files.size, tookMs = 1, indexIncomplete = false, complete = complete)
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView) = results(query, true)
    override fun searchProgressive(query: String, filter: KindFilter, limit: Int, meaningDelayMs: Long, view: ResultView): Flow<SearchResults> =
      flow { emit(results(query, false)) }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> = files
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  @Test fun anAnswerThatEqualsTheListOnScreenStillCloses() = runTest(dispatcher) {
    val vm = SearchViewModel(SameListEngine(listOf(file(1), file(2))), feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("recei"); advanceUntilIdle()
    assertTrue("a new list waits for the screen", lines.isEmpty())
    vm.feedDrawn()                                              // the screen drew the first list
    assertEquals(1, lines.size)
    assertTrue(lines[0], lines[0].startsWith("words_ms="))
    vm.setQuery("receip"); advanceUntilIdle()                    // the same two files: nothing new to compose, so no draw hook
    assertEquals(2, lines.size)
    assertTrue(lines[1], lines[1].startsWith("words_ms="))
    vm.setQuery("receipt"); advanceUntilIdle()
    assertEquals(3, lines.size)
  }

  @Test fun aListStillWaitingForItsDrawIsClosedByThatDrawNotTwice() = runTest(dispatcher) {
    val vm = SearchViewModel(SameListEngine(listOf(file(1), file(2))), feedDispatcher = dispatcher)
    advanceUntilIdle()
    vm.setQuery("recei"); advanceUntilIdle()                     // list A on its way to the screen, not drawn yet
    vm.setQuery("receip"); advanceUntilIdle()                    // the same list again: the pending draw will close this mark
    assertTrue(lines.isEmpty())
    vm.feedDrawn()
    assertEquals(1, lines.size)
    assertTrue(lines[0], lines[0].contains("q=\"<private>\""))
    vm.feedDrawn()                                              // a second call has nothing to close
    assertEquals(1, lines.size)
  }
}
