package com.craneai.findr.search

import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.SortOrder
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/** Sort and date range through the real store and searcher: the order of what was found, and what the date keeps. */
class SortAndDateSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(7)
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-sortdate").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun tearDown() { vectors.close(); store.close(); dir.deleteRecursively() }

  private fun unit(raw: FloatArray): FloatArray { val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(raw.size) { raw[it] / n } }
  private fun noise() = FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() }
  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }
  private fun about(k: Int) = unit(FloatArray(Fp16.DIM) { (if (it == k) 1f else 0f) + noise()[it] * 0.03f })

  private val day = 86_400_000L
  private val t0 = 1_700_000_000_000L

  private fun add(name: String, date: Long, size: Long = 1000, kind: FileKind = FileKind.DOCUMENT, vararg segs: Segment): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, kind, null, size, date, dateMillis = date)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    if (segs.isNotEmpty()) {
      val ids = store.saveSegments(fileId, segs.toList(), FileState.UNDERSTOOD)
      segs.forEachIndexed { i, s -> s.vector?.let { vectors.add(ids[i], fileId, kind, s.modality, s.late, it) } }
    }
    return fileId
  }

  private fun text(v: FloatArray, t: String) = Segment(SegmentKind.CHUNK, Modality.TEXT, text = t, vector = v)
  private fun names(r: SearchResults) = r.hits.map { it.file.name }
  private fun search(q: String, view: ResultView, vector: FloatArray? = null, limit: Int = 50) = searcher.search(q, vector, KindFilter.ALL, limit, view = view)

  // ---- sort ----------------------------------------------------------------------------------------------------------

  private fun five() {
    add("report c.txt", t0 + 2 * day, 300)
    add("Report a.txt", t0 + 4 * day, 100)
    add("report e.txt", t0 + 1 * day, 500)
    add("report b.txt", t0 + 5 * day, 200)
    add("report d.txt", t0 + 3 * day, 400)
  }

  @Test fun everySortOrdersTheMatchesItself() {
    five()
    assertEquals(listOf("report b.txt", "Report a.txt", "report d.txt", "report c.txt", "report e.txt"), names(search("report", ResultView(SortOrder.NEWEST))))
    assertEquals(listOf("report e.txt", "report c.txt", "report d.txt", "Report a.txt", "report b.txt"), names(search("report", ResultView(SortOrder.OLDEST))))
    assertEquals(listOf("report e.txt", "report d.txt", "report c.txt", "report b.txt", "Report a.txt"), names(search("report", ResultView(SortOrder.LARGEST))))
    assertEquals(listOf("Report a.txt", "report b.txt", "report c.txt", "report d.txt", "report e.txt"), names(search("report", ResultView(SortOrder.NAME))))
  }

  @Test fun bestMatchIsTheOrderItAlwaysWas() {
    five()
    val plain = searcher.search("report", null, KindFilter.ALL, 50)
    val best = search("report", ResultView(SortOrder.BEST))
    assertEquals(names(plain), names(best))
    assertEquals(plain.strongCount, best.strongCount)
  }

  @Test fun aSortNeverLiftsAWeakMatchAboveAStrongOne() {
    // Both words (strong) in the old files; one word only (weak, newer) in the new ones.
    add("alpha report old1.txt", t0 + 1 * day)
    add("alpha report old2.txt", t0 + 2 * day)
    add("report only new.txt", t0 + 9 * day)
    add("report junk newest.txt", t0 + 10 * day)
    val best = search("alpha report", ResultView(SortOrder.BEST))
    assertEquals(2, best.strongCount)
    val newest = search("alpha report", ResultView(SortOrder.NEWEST))
    assertEquals(listOf("alpha report old2.txt", "alpha report old1.txt", "report junk newest.txt", "report only new.txt"), names(newest))
    assertEquals(2, newest.strongCount)
    val oldest = search("alpha report", ResultView(SortOrder.OLDEST))
    assertEquals(listOf("alpha report old1.txt", "alpha report old2.txt", "report only new.txt", "report junk newest.txt"), names(oldest))
  }

  @Test fun theLimitCutsAfterTheSort() {
    five()
    assertEquals(listOf("report b.txt", "Report a.txt"), names(search("report", ResultView(SortOrder.NEWEST), limit = 2)))
  }

  @Test fun aSortDoesNotSurfaceJunk() {
    // Nothing in the index matches: a sort by newest must not list the newest files anyway.
    five()
    assertTrue(search("zebra", ResultView(SortOrder.NEWEST)).hits.isEmpty())
  }

  // ---- date range ---------------------------------------------------------------------------------------------------

  @Test fun theDateRangeKeepsOnlyFilesInsideItAndIsExclusiveAtTheEnd() {
    five()
    val r = search("report", ResultView(SortOrder.NEWEST, DateRange(t0 + 2 * day, t0 + 4 * day)))
    assertEquals(listOf("report d.txt", "report c.txt"), names(r))   // day 2 in, day 4 out
  }

  @Test fun anOpenEndedRangeWorks() {
    five()
    assertEquals(setOf("report b.txt", "Report a.txt"), names(search("report", ResultView(dates = DateRange(fromMs = t0 + 4 * day))).also { }).toSet())
    assertEquals(setOf("report e.txt"), names(search("report", ResultView(dates = DateRange(untilMs = t0 + 2 * day)))).toSet())
  }

  @Test fun theRangeIsAppliedBeforeTheWordLimit() {
    // 150 files match the word, far more than the word step keeps (100): the three in range must still be found.
    repeat(150) { add("memo $it.txt", t0 + (it % 50) * day) }
    val late = t0 + 400 * day
    add("memo late1.txt", late); add("memo late2.txt", late + 2 * day); add("memo late3.txt", late + 4 * day)
    val r = search("memo", ResultView(SortOrder.NEWEST, DateRange(late, late + day)), limit = 10)
    assertEquals(listOf("memo late1.txt"), names(r))
    val all = search("memo", ResultView(SortOrder.NEWEST, DateRange(late, late + 10 * day)), limit = 10)
    assertEquals(listOf("memo late3.txt", "memo late2.txt", "memo late1.txt"), names(all))
  }

  @Test fun meaningMatchesAreFilteredByDateButTheRelevanceFloorStaysPut() {
    repeat(300) { add("filler $it.txt", t0 + (it % 100) * day, 100, FileKind.DOCUMENT, text(unit(noise()), "background filler $it")) }
    val old = add("lease old.pdf", t0 + 5 * day, 100, FileKind.PDF, text(about(7), "tenancy agreement landlord"))
    val fresh = add("lease new.pdf", t0 + 200 * day, 100, FileKind.PDF, text(about(7), "tenancy agreement landlord"))
    val query = topic(7)
    assertEquals(setOf("lease old.pdf", "lease new.pdf"), names(search("rental contract", ResultView(), query)).toSet())
    assertEquals(listOf("lease new.pdf"), names(search("rental contract", ResultView(dates = DateRange(t0 + 150 * day, null)), query)))
    assertEquals(listOf("lease old.pdf"), names(search("rental contract", ResultView(dates = DateRange(null, t0 + 10 * day)), query)))
    // a range that holds only filler: filler is not above the floor, so nothing is returned just because it is in range
    assertTrue(search("rental contract", ResultView(dates = DateRange(t0 + 20 * day, t0 + 30 * day)), query).hits.isEmpty())
    assertTrue(old != fresh)
  }

  @Test fun theDateOfAPhotoIsItsCaptureDateNotItsFileTime() {
    val id = nextId++
    val taken = t0 + 3 * day
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://x/$id", "DCIM/", "holiday.jpg", FileKind.IMAGE, null, 10, modifiedMillis = t0 + 300 * day, dateMillis = taken)))
    assertEquals(listOf("holiday.jpg"), names(search("holiday", ResultView(dates = DateRange(taken, taken + day)))))
    assertTrue(search("holiday", ResultView(dates = DateRange(t0 + 300 * day, t0 + 301 * day))).hits.isEmpty())
    assertEquals(taken, search("holiday", ResultView()).hits[0].file.dateMillis)
    assertEquals(t0 + 300 * day, search("holiday", ResultView()).hits[0].file.modifiedMillis)
  }
}
