package com.craneai.findr.ui.search

import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Forgotten
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

/** The sort, date and selection state of the search screen, against an engine that records what it was asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class SortDateViewModelTest {
  private val zone = ZoneId.of("Africa/Kampala")
  private val now = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone) // a Friday

  private class Asked(val query: String, val view: ResultView)

  private class RecordingEngine : SearchEngine {
    val searches = ArrayList<Asked>()
    val recents = ArrayList<ResultView>()
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    val files = (1L..4L).map {
      IndexedFile(it, "fake://$it", "x/$it", "f$it", if (it % 2 == 0L) FileKind.IMAGE else FileKind.PDF, null, it * 10, it)
    }
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      searches += Asked(query, view)
      return SearchResults(query, files.map { SearchHit(it, 1.0, MatchReason.BOTH) }, strongCount = files.size, tookMs = 1, indexIncomplete = false)
    }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> { recents += view; return files }
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  private val dispatcher = StandardTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun TestScope.vm(engine: RecordingEngine) =
    SearchViewModel(engine, clock = { now }, weekStart = { DayOfWeek.MONDAY }, feedDispatcher = dispatcher).also { advanceUntilIdle() }

  @Test fun browseStartsNewestAndAChosenSortIsRemembered() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    assertEquals(SortOrder.NEWEST, vm.state.value.sort)
    vm.setSort(SortOrder.LARGEST); advanceUntilIdle()
    assertEquals(SortOrder.LARGEST, vm.state.value.sort)
    assertEquals(SortOrder.LARGEST, e.recents.last().sort)
  }

  @Test fun resultsStartAtBestMatchAndTheTwoListsKeepTheirOwnSort() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    vm.setSort(SortOrder.OLDEST); advanceUntilIdle()           // browse
    vm.setQuery("invoice"); advanceUntilIdle()
    assertEquals(SortOrder.BEST, vm.state.value.sort)
    vm.setSort(SortOrder.NAME); advanceUntilIdle()             // results
    assertEquals(SortOrder.NAME, e.searches.last().view.sort)
    vm.setQuery(""); advanceUntilIdle()
    assertEquals(SortOrder.OLDEST, vm.state.value.sort)
  }

  @Test fun aDateInTheQueryBecomesAChipAndLeavesTheSemanticQuery() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    vm.setQuery("invoices last week"); advanceUntilIdle()
    val date = vm.state.value.date
    assertNotNull(date)
    assertEquals("last week", date!!.phrase)
    assertEquals("invoices", e.searches.last().query)
    assertNotNull(e.searches.last().view.dates)
    assertEquals("invoices", vm.state.value.resultsQuery)
  }

  @Test fun aDateAloneIsBrowsingWithThatDate() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    vm.setQuery("yesterday"); advanceUntilIdle()
    assertEquals(SearchMode.RECENT, vm.state.value.mode)
    assertNotNull(e.recents.last().dates)
    assertTrue(e.searches.isEmpty())
  }

  @Test fun dismissingTheTypedDateSearchesItsWordsAsWordsAndLeavesTheBoxAlone() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    vm.setQuery("invoices last week"); advanceUntilIdle()
    vm.clearDate(); advanceUntilIdle()
    assertNull(vm.state.value.date)
    assertEquals("invoices last week", e.searches.last().query)
    assertNull(e.searches.last().view.dates)
    assertEquals("invoices last week", vm.state.value.query)
    // taking the phrase out of the box and typing it again reads it as a date once more
    vm.setQuery("invoices"); advanceUntilIdle()
    vm.setQuery("invoices last week"); advanceUntilIdle()
    assertNotNull(vm.state.value.date)
  }

  @Test fun aChosenPresetFiltersAndTheCrossClearsIt() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    vm.setDatePreset(DatePreset.THIS_MONTH); advanceUntilIdle()
    val range: DateRange = e.recents.last().dates!!
    assertEquals(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), range.fromMs)
    vm.setQuery("invoice"); advanceUntilIdle()
    assertEquals(range, e.searches.last().view.dates)
    vm.clearDate(); advanceUntilIdle()
    assertNull(vm.state.value.date)
    assertNull(e.searches.last().view.dates)
  }

  @Test fun aTypedDateOverridesAChosenOne() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    vm.setDatePreset(DatePreset.TODAY); advanceUntilIdle()
    vm.setQuery("photos last year"); advanceUntilIdle()
    assertEquals("last year", vm.state.value.date!!.phrase)
  }

  @Test fun selectionTicksUnticksAndSelectAllClearsWhenFull() = runTest(dispatcher) {
    val e = RecordingEngine()
    val vm = vm(e)
    val f = e.files
    vm.toggleSelected(f[0]); vm.toggleSelected(f[1])
    assertEquals(setOf(f[0].id, f[1].id), vm.state.value.selected)
    vm.toggleSelected(f[1])
    assertEquals(setOf(f[0].id), vm.state.value.selected)
    vm.selectAll()
    assertEquals(f.map { it.id }.toSet(), vm.state.value.selected)
    assertEquals(4, vm.selectedFiles().size)
    vm.selectAll()
    assertTrue(vm.state.value.selected.isEmpty())
    vm.toggleSelected(f[2]); vm.clearSelection()
    assertFalse(vm.state.value.selected.isNotEmpty())
  }
}
