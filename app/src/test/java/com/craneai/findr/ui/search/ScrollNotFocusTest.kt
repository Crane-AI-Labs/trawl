package com.craneai.findr.ui.search

import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Blur until scored: the search box's focus hold keeps the index worker waiting (setUserInteracting), but the on-demand privacy score
 * of the pictures on screen waits only for a scroll (setUserScrolling). Found on the A06: with the hold, results stayed blurred for the
 * whole 30 s the box kept focus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollNotFocusTest {
  private class Engine : SearchEngine {
    val interacting = ArrayList<Boolean>()
    val scrolling = ArrayList<Boolean>()
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView) = SearchResults(query, emptyList(), 0, 0, indexIncomplete = false)
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> = emptyList()
    override fun setUserInteracting(active: Boolean) { interacting += active }
    override fun setUserScrolling(active: Boolean) { scrolling += active }
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  private val dispatcher = StandardTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  @Test fun aScrollThatStopsReleasesTheOnDemandScoreEvenWhileTheBoxHoldsFocus() = runTest(dispatcher) {
    val e = Engine()
    val vm = SearchViewModel(e, feedDispatcher = dispatcher)
    vm.setSearchFocus(true)
    vm.setInteracting(true)
    vm.setInteracting(false)
    assertEquals("the worker still waits for the focused box", true, e.interacting.last())
    assertEquals("but the scroll has stopped", false, e.scrolling.last())
    assertEquals(listOf(true, false), e.scrolling)
  }
}
