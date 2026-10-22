package com.craneai.findr.ui.search

import com.craneai.findr.core.DateRange
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The phone-clock rule on the search screen (ClockSense): the TECNO KN3 read 31 August with files dated up to 19 October, so
 * "in October" must still mean this October, and a right clock must change nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockSkewViewModelTest {
  private val zone = ZoneId.of("Africa/Kampala")
  private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone)
  private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()
  private fun range(y1: Int, m1: Int, y2: Int, m2: Int) = DateRange(ms(at(y1, m1, 1, 0)), ms(at(y2, m2, 1, 0)))

  private class Engine(val evidence: List<Long>) : SearchEngine {
    val searches = ArrayList<ResultView>()
    val recents = ArrayList<ResultView>()
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    private val files = (1L..3L).map { IndexedFile(it, "fake://$it", "x/$it", "f$it", FileKind.IMAGE, null, it * 10, it) }
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      searches += view
      return SearchResults(query, files.map { SearchHit(it, 1.0, MatchReason.BOTH) }, strongCount = files.size, tookMs = 1, indexIncomplete = false)
    }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> { recents += view; return files }
    override suspend fun newestDates(limit: Int, notAfterMs: Long): List<Long> = evidence.filter { it <= notAfterMs }.sortedDescending().take(limit)
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  private val dispatcher = StandardTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun TestScope.vm(engine: Engine, clock: ZonedDateTime) =
    SearchViewModel(engine, clock = { clock }, weekStart = { DayOfWeek.MONDAY }, feedDispatcher = dispatcher).also { advanceUntilIdle() }

  private val kn3Files = listOf(at(2026, 10, 19, 11), at(2026, 10, 19, 9), at(2026, 10, 18, 20), at(2026, 10, 12), at(2026, 9, 3)).map(::ms)

  @Test fun aClockBehindItsFilesStillUnderstandsOctober() = runTest(dispatcher) {
    val e = Engine(kn3Files)
    val vm = vm(e, at(2026, 8, 31, 13))
    assertTrue("skew shown", vm.state.value.clockSkewMs > 0)
    vm.setQuery("photos in October"); advanceUntilIdle()
    assertEquals(range(2026, 10, 2026, 11), e.searches.last().dates)
    vm.setQuery("photos in September"); advanceUntilIdle()
    assertEquals(range(2026, 9, 2026, 10), e.searches.last().dates)
  }

  @Test fun aClockBehindItsFilesCountsThisWeekFromTheFiles() = runTest(dispatcher) {
    val e = Engine(kn3Files)
    val vm = vm(e, at(2026, 8, 31, 13))
    vm.setQuery("this week"); advanceUntilIdle()
    assertNotNull(e.recents.last().dates)
    assertEquals(DateRange(ms(at(2026, 10, 12, 0)), ms(at(2026, 10, 19, 0))), e.recents.last().dates)
  }

  @Test fun aRightClockChangesNothing() = runTest(dispatcher) {
    val right = at(2026, 10, 19, 12)
    val e = Engine(listOf(ms(right) - 3_600_000, ms(right) - 7_200_000, ms(right) - 86_400_000))
    val vm = vm(e, right)
    assertEquals(0L, vm.state.value.clockSkewMs)
    vm.setQuery("photos in September"); advanceUntilIdle()
    assertEquals(range(2026, 9, 2026, 10), e.searches.last().dates)
    vm.setQuery("photos in October"); advanceUntilIdle()
    assertEquals(range(2026, 10, 2026, 11), e.searches.last().dates)
  }

  @Test fun anEngineWithNoEvidenceKeepsTheClock() = runTest(dispatcher) {
    val e = Engine(emptyList())
    val vm = vm(e, at(2026, 8, 31, 13))
    assertEquals(0L, vm.state.value.clockSkewMs)
    vm.setQuery("photos in October"); advanceUntilIdle()
    assertEquals(range(2025, 10, 2025, 11), e.searches.last().dates)   // the old behaviour, as it was
  }
}
