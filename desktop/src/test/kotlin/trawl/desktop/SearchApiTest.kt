package trawl.desktop

import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SortOrder
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** The desktop's one search entry point against a real temporary index: the sorts, the date range, sensitive files, and the words-then-meaning order. */
class SearchApiTest {
  private val dir: File = Files.createTempDirectory("trawl-searchapi").toFile()
  private val store = IndexStore.open(dir, log = {})
  private val vectors = VectorIndex(parallelism = 1)
  private val searcher = Searcher(store, vectors)
  private var next = 1L
  private val day = 86_400_000L
  private val t0 = 1_700_000_000_000L

  @AfterTest fun close() { vectors.close(); store.close(); dir.deleteRecursively() }

  private fun add(name: String, date: Long, size: Long = 1000, kind: FileKind = FileKind.DOCUMENT): Long {
    val n = next++
    store.upsertFromCrawl(listOf(CrawlRecord("desktop", n, "file:///t/$name", "t/", name, kind, null, size, date, dateMillis = date)))
    return store.knownMediaStoreIds("desktop").getValue(n)
  }

  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }

  private fun hooks(model: Boolean = false, on: Set<FileKind> = Crawler.KINDS, sensitive: Boolean = false) = SearchHooks(
    store = { store }, searcher = { searcher }, modelReady = { model }, vectorCount = { vectors.size },
    embed = { topic(3) }, wordsWeight = { 1.0 }, meaningWeight = { _, _ -> 1.0 }, kinds = { on }, includeSensitive = { sensitive },
  )

  private fun five() {
    add("report c.txt", t0 + 2 * day, 300)
    add("Report a.txt", t0 + 4 * day, 100)
    add("report e.txt", t0 + 1 * day, 500)
    add("report b.txt", t0 + 5 * day, 200)
    add("report d.txt", t0 + 3 * day, 400)
  }

  private fun last(api: SearchApi, q: String, view: ResultView = ResultView.DEFAULT, filter: KindFilter = KindFilter.ALL) =
    runBlocking { api.search(q, filter, view).toList().last() }

  private fun names(r: com.craneai.findr.core.SearchResults) = r.hits.map { it.file.name }

  @Test fun everySortOrdersTheMatchesItself() {
    five()
    val api = SearchApi(hooks())
    assertEquals(listOf("report b.txt", "Report a.txt", "report d.txt", "report c.txt", "report e.txt"), names(last(api, "report", ResultView(SortOrder.NEWEST))))
    assertEquals(listOf("report e.txt", "report c.txt", "report d.txt", "Report a.txt", "report b.txt"), names(last(api, "report", ResultView(SortOrder.OLDEST))))
    assertEquals(listOf("report e.txt", "report d.txt", "report c.txt", "report b.txt", "Report a.txt"), names(last(api, "report", ResultView(SortOrder.LARGEST))))
    assertEquals(listOf("Report a.txt", "report b.txt", "report c.txt", "report d.txt", "report e.txt"), names(last(api, "report", ResultView(SortOrder.NAME))))
  }

  @Test fun aDateRangeKeepsOnlyTheFilesInsideIt() {
    five()
    val api = SearchApi(hooks())
    val range = DateRange(t0 + 2 * day, t0 + 4 * day)   // from day 2 up to (not including) day 4: c and d
    assertEquals(listOf("report d.txt", "report c.txt"), names(last(api, "report", ResultView(SortOrder.NEWEST, range))))
    assertEquals(listOf("report d.txt", "report c.txt"), api.recent(KindFilter.ALL, ResultView(SortOrder.NEWEST, range)).files.map { it.name })
  }

  @Test fun recentFollowsTheSortToo() {
    five()
    val api = SearchApi(hooks())
    assertEquals(listOf("report b.txt", "Report a.txt", "report d.txt", "report c.txt", "report e.txt"), api.recent(KindFilter.ALL).files.map { it.name })
    assertEquals(listOf("report e.txt", "report c.txt", "report d.txt", "Report a.txt", "report b.txt"), api.recent(KindFilter.ALL, ResultView(SortOrder.OLDEST)).files.map { it.name })
  }

  @Test fun theDateShownIsTheCaptureDateNotTheModifiedTime() {
    val n = next++
    store.upsertFromCrawl(listOf(CrawlRecord("desktop", n, "file:///t/holiday.jpg", "t/", "holiday.jpg", FileKind.IMAGE, null, 10, modifiedMillis = t0 + 300 * day, dateMillis = t0)))
    val f = SearchApi(hooks()).recent(KindFilter.ALL).files.single()
    assertEquals(t0, f.dateMillis)
    assertEquals(t0 + 300 * day, f.modifiedMillis)
  }

  @Test fun aSwitchedOffKindIsOutOfTheResultsAndTheListIsNotLeftShort() {
    for (i in 1..6) add("note $i.txt", t0 + i * day, kind = FileKind.DOCUMENT)
    for (i in 1..6) add("note photo $i.jpg", t0 + (10 + i) * day, kind = FileKind.IMAGE)
    val api = SearchApi(hooks(on = setOf(FileKind.DOCUMENT)))
    val r = runBlocking { api.search("note", KindFilter.ALL, ResultView.DEFAULT, limit = 6).toList().last() }
    assertEquals(6, r.hits.size)
    assertTrue(r.hits.all { it.file.kind == FileKind.DOCUMENT })
    assertTrue(api.recent(KindFilter.ALL).files.all { it.kind == FileKind.DOCUMENT })
  }

  @Test fun onlyKindsKeepsTheStrongCountInStep() {
    five()
    val r = last(SearchApi(hooks()), "report")
    val cut = SearchApi.onlyKinds(r, setOf(FileKind.IMAGE), 10)
    assertTrue(cut.hits.isEmpty()); assertEquals(0, cut.strongCount)
    val few = SearchApi.onlyKinds(r, Crawler.KINDS, 2)
    assertEquals(2, few.hits.size); assertTrue(few.strongCount <= 2)
  }

  @Test fun theQuickPanelsAskNeverSeesSensitiveFilesEvenWhenTheWindowDoes() {
    val a = add("diary report.txt", t0 + day)
    add("plain report.txt", t0)
    store.setMarked(a, true)
    val api = SearchApi(hooks(sensitive = true))
    assertEquals(setOf("diary report.txt", "plain report.txt"), names(last(api, "report")).toSet(), "the window has them shown")
    val panel = runBlocking { api.search("report", KindFilter.ALL, ResultView.DEFAULT, withSensitive = false).toList().last() }
    assertEquals(listOf("plain report.txt"), names(panel))
    assertEquals(listOf("plain report.txt"), api.recent(KindFilter.ALL, withSensitive = false).files.map { it.name })
    assertEquals(1, api.recent(KindFilter.ALL, withSensitive = false).hidden, "and the count of what is out of sight")
    assertEquals(2, api.recent(KindFilter.ALL).files.size)
  }

  @Test fun wordsComeFirstThenTheMeaningAnswerAndOnlyTheLastIsComplete() {
    val id = add("quarterly invoice summary.txt", t0)
    val ids = store.saveSegments(id, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "invoice totals", vector = topic(3))), FileState.UNDERSTOOD)
    vectors.add(ids[0], id, FileKind.DOCUMENT, Modality.TEXT, false, topic(3))
    add("vacation plans.txt", t0 + day)
    val steps = runBlocking { SearchApi(hooks(model = true)).search("invoice summary", KindFilter.ALL).toList() }
    assertEquals(2, steps.size, "words, then words and meaning")
    assertFalse(steps.first().complete, "the first answer says a meaning answer is coming")
    assertTrue(steps.last().complete)
    assertEquals("quarterly invoice summary.txt", steps.first().hits.first().file.name)
    assertEquals("quarterly invoice summary.txt", steps.last().hits.first().file.name)
    // No model: one answer, already complete.
    val plain = runBlocking { SearchApi(hooks(model = false)).search("invoice summary", KindFilter.ALL).toList() }
    assertEquals(1, plain.size); assertTrue(plain.single().complete)
  }

  @Test fun aClosedIndexAnswersIncompleteInsteadOfFailing() {
    val api = SearchApi(SearchHooks({ null }, { null }, { false }, { 0 }, { null }, { 1.0 }, { _, _ -> 1.0 }, { Crawler.KINDS }))
    val r = runBlocking { api.search("anything", KindFilter.ALL).toList().single() }
    assertTrue(r.indexIncomplete); assertTrue(r.hits.isEmpty())
    assertTrue(api.recent(KindFilter.ALL).files.isEmpty())
  }

  @Test fun findSimilarSeedKindsAreImagesScreenshotsAndVideosOnly() {
    assertEquals(setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO), SearchApi.SIMILAR_KINDS)
  }

  // ---- SearchSession: the chips and the date words on top ----------------------------------------------------------------------------

  private val zone = ZoneId.of("UTC")
  private val now = ZonedDateTime.of(2026, 10, 21, 12, 0, 0, 0, zone)
  private fun session() = SearchSession(SearchApi(hooks()), clock = { now }, weekStart = { java.time.DayOfWeek.MONDAY })

  @Test fun aDateWordInTheQueryBecomesTheDateChipAndLeavesTheRestAsWords() {
    val d = session().derive("report last week")
    assertEquals("report", d.text)
    assertNotNull(d.date)
    assertEquals("last week", d.date.phrase)
    assertFalse(d.browse)
  }

  @Test fun theSortIsRememberedPerKindOfList() {
    val s = session()
    assertEquals(SortOrder.NEWEST, s.derive("").sort, "browsing starts at Newest")
    assertEquals(SortOrder.BEST, s.derive("report").sort, "results start at Best match")
    s.setSort(SortOrder.LARGEST, "report")
    assertEquals(SortOrder.LARGEST, s.derive("report").sort)
    assertEquals(SortOrder.NEWEST, s.derive("").sort, "browsing keeps its own")
    s.setSort(SortOrder.OLDEST, "")
    assertEquals(SortOrder.OLDEST, s.derive("").sort)
    assertEquals(SortOrder.LARGEST, s.derive("report").sort)
  }

  @Test fun theCrossOnATypedDateDismissesItAndOnAChosenDateClearsIt() {
    val s = session()
    s.clearDate("report last week")
    val d = s.derive("report last week")
    assertNull(d.date, "the phrase is ordinary words from now on")
    assertEquals("report last week", d.text)
    val t = session()
    t.setDatePreset(com.craneai.findr.core.DatePreset.THIS_MONTH)
    assertNotNull(t.derive("report").date)
    t.clearDate("report")
    assertNull(t.derive("report").date)
  }

  @Test fun aDatePhraseThatFindsNothingButIsWrittenInAFileIsSearchedAsWords() {
    add("notes from last week of school.txt", t0)   // far outside any "last week" of the test clock, but it holds the words
    val s = session()
    val seen = ArrayList<SessionAnswer>()
    runBlocking { s.load("notes last week", KindFilter.ALL, 0L, quiet = false) { seen += it } }
    val final = seen.last()
    assertEquals(listOf("notes from last week of school.txt"), final.hits!!.hits.map { it.file.name })
    assertNull(final.derived.date, "kept as words once a file proved it is content")
  }

  @Test fun findSimilarIsAListOfItsOwnThatTypingEnds() {
    val s = session()
    val id = add("beach.jpg", t0, kind = FileKind.IMAGE)
    val f = store.fileById(id)!!
    s.findSimilar(f)
    assertNotNull(s.similar)
    assertTrue(s.derive("").similar)
    s.queryChanged("sun")
    assertNull(s.similar, "typing leaves Find similar")
  }
}
