package com.craneai.findr.ui.search

import com.craneai.findr.core.DatePreset
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

/**
 * A date phrase in the query ("September", "last Friday") narrows the list to that date. When that leaves nothing, and the phrase is
 * written in a file that matches the rest of the query, it was part of the words ("your PAYE return for September has not been
 * filed"): the screen searches again with the phrase as words and drops the chip. Landing numbers 2026-10-16: two of 30 queries had
 * no answer at all for this reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DateInTheWordsViewModelTest {
  private val zone = ZoneId.of("Africa/Kampala")
  private val now = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone) // a Friday

  private val screenshot = IndexedFile(1, "fake://1", "Pictures/Screenshots/sms_ura_paye_notice.png", "sms_ura_paye_notice.png", FileKind.IMAGE, null, 10, 1)
  private val other = IndexedFile(2, "fake://2", "Documents/notes.txt", "notes.txt", FileKind.DOCUMENT, null, 10, 2)

  private class Asked(val query: String, val view: ResultView)

  /** Answers a dated search with [dated] and an undated one with [undated]; [mentions] is what the index says about the phrase. */
  private class Engine(
    val dated: List<SearchHit>,
    val undated: List<SearchHit>,
    val mentions: Boolean,
  ) : SearchEngine {
    val searches = ArrayList<Asked>()
    val mentionChecks = ArrayList<Pair<String, List<Long>>>()
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      searches += Asked(query, view)
      val hits = if (view.dates != null) dated else undated
      return SearchResults(query, hits, strongCount = hits.size, tookMs = 1, indexIncomplete = false)
    }
    override suspend fun anyMentions(phrase: String, files: List<IndexedFile>): Boolean {
      mentionChecks += phrase to files.map { it.id }
      return mentions
    }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> = emptyList()
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

  private fun hit(file: IndexedFile, reason: MatchReason) = SearchHit(file, 1.0, reason)

  private val paye = "your PAYE return for September has not been filed"

  @Test fun aPhraseWrittenInTheFileIsSearchedAsWordsWhenTheDateLeavesNothing() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = listOf(hit(screenshot, MatchReason.BOTH), hit(other, MatchReason.MEANING)), mentions = true)
    val vm = vm(e)
    vm.setQuery(paye); advanceUntilIdle()
    // The date was tried first (phrase taken out, range on), then the whole query without a date.
    assertEquals(listOf("your PAYE return for has not been filed", paye), e.searches.map { it.query }.distinct())
    assertNotNull(e.searches.first().view.dates)
    assertNull(e.searches.last().view.dates)
    assertEquals(listOf("september" to listOf(1L)), e.mentionChecks)   // only the file that matched on words was asked about
    val s = vm.state.value
    assertNull(s.date)                       // no chip: the phrase is words now
    assertFalse(s.nothingFound)
    assertEquals(paye, s.resultsQuery)
    assertEquals(listOf(1L, 2L), s.feed.shownFiles().map { it.id })
  }

  @Test fun theDismissalHoldsWhileThePhraseIsStillInTheBox() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = listOf(hit(screenshot, MatchReason.BOTH)), mentions = true)
    val vm = vm(e)
    vm.setQuery(paye); advanceUntilIdle()
    vm.setQuery("$paye yet"); advanceUntilIdle()
    assertNull(vm.state.value.date)
    assertEquals("$paye yet", e.searches.last().query)   // straight to the whole query: no date, no second round
    assertNull(e.searches.last().view.dates)
  }

  @Test fun aPhraseWithALeadWordIsDroppedWholeNotLeftAsTheBareMonth() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = listOf(hit(screenshot, MatchReason.BOTH)), mentions = true)
    val vm = vm(e)
    vm.setQuery("invoices from September"); advanceUntilIdle()
    assertNull(vm.state.value.date)   // not "from" gone and "september" still a chip
    assertEquals("invoices from September", e.searches.last().query)
    assertNull(e.searches.last().view.dates)
    assertEquals(listOf(1L), vm.state.value.feed.shownFiles().map { it.id })
  }

  @Test fun aPhraseNoMatchingFileHasStaysADateAndTheListStaysEmpty() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = listOf(hit(screenshot, MatchReason.WORDS)), mentions = false)
    val vm = vm(e)
    vm.setQuery("invoices from September"); advanceUntilIdle()
    val s = vm.state.value
    assertEquals("from september", s.date?.phrase)   // "nothing from September" is the honest answer
    assertTrue(s.nothingFound)
    assertTrue(s.feed.isEmpty())
    assertEquals(e.searches.joinToString { "${it.query}|${it.view.dates}" }, 1, e.mentionChecks.size)
  }

  @Test fun filesThatOnlyMatchedByMeaningProveNothing() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = listOf(hit(screenshot, MatchReason.MEANING)), mentions = true)
    val vm = vm(e)
    vm.setQuery(paye); advanceUntilIdle()
    assertTrue(e.mentionChecks.isEmpty())   // nobody to ask about
    assertNotNull(vm.state.value.date)
    assertTrue(vm.state.value.nothingFound)
  }

  @Test fun nothingUndatedEitherStaysEmptyWithTheChip() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = emptyList(), mentions = true)
    val vm = vm(e)
    vm.setQuery("zzz September"); advanceUntilIdle()
    assertEquals("september", vm.state.value.date?.phrase)
    assertTrue(vm.state.value.nothingFound)
  }

  @Test fun aDatedListWithResultsIsNeverTouched() = runTest(dispatcher) {
    val e = Engine(dated = listOf(hit(other, MatchReason.BOTH)), undated = listOf(hit(screenshot, MatchReason.BOTH)), mentions = true)
    val vm = vm(e)
    vm.setQuery(paye); advanceUntilIdle()
    assertEquals(1, e.searches.map { it.query }.distinct().size)   // one question, no second round
    assertTrue(e.mentionChecks.isEmpty())
    assertEquals("september", vm.state.value.date?.phrase)
    assertEquals(listOf(2L), vm.state.value.feed.shownFiles().map { it.id })
  }

  @Test fun aDateChosenOnTheChipIsNeverReadAsWords() = runTest(dispatcher) {
    val e = Engine(dated = emptyList(), undated = listOf(hit(screenshot, MatchReason.BOTH)), mentions = true)
    val vm = vm(e)
    vm.setQuery("PAYE return"); advanceUntilIdle()
    vm.setDatePreset(DatePreset.THIS_WEEK); advanceUntilIdle()
    assertTrue(e.mentionChecks.isEmpty())   // no phrase in the words: the person chose this range
    assertNotNull(vm.state.value.date)
    assertTrue(vm.state.value.nothingFound)
  }
}
