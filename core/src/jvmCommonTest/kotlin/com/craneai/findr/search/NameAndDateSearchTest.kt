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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two things found by the real-use simulation on a phone (20 Oct 2026), through the real store and searcher:
 *
 *  A. A date typed in the query ("oct 2026") is a hard filter on the file date. A file that is NAMED "... Oct 2026" but was last
 *     modified in September was therefore hidden whenever any other October file matched (the app's only rescue, "read the date as
 *     words", fires when the dated list is empty).
 *  B. A document whose name holds every word of the query ("Minutes of PTA meeting 12 Sept.docx") sat under a screen of photos
 *     while its meaning was still being learned: its score (words only) lost to the photos' quick-look meaning.
 */
class NameAndDateSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var quick: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(11)
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-namedate").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    quick = VectorIndex(dim = IndexStore.QUICK_DIM, parallelism = 1, holds = VectorIndex.QUICK_LOOK)
    searcher = Searcher(store, vectors, quick = quick)
  }

  @After fun tearDown() { vectors.close(); quick.close(); store.close(); dir.deleteRecursively() }

  private fun unit(raw: FloatArray): FloatArray { val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(raw.size) { raw[it] / n } }
  private fun noise(dim: Int) = unit(FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() })
  private fun topic(k: Int, dim: Int) = FloatArray(dim) { if (it == k) 1f else 0f }
  /** A vector at about [cosine] from topic [k]: the rest is noise. */
  private fun near(k: Int, dim: Int, cosine: Double): FloatArray {
    val n = noise(dim); val rest = sqrt(1 - cosine * cosine).toFloat()
    return unit(FloatArray(dim) { (if (it == k) cosine.toFloat() else 0f) + n[it] * rest })
  }

  private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
  private val october = DateRange(ms(2026, 10, 1), ms(2026, 11, 1))
  private fun chip(phrase: String, range: DateRange) = ResultView(SortOrder.BEST, range, phrase)

  private fun add(name: String, date: Long, kind: FileKind = FileKind.DOCUMENT): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, kind, null, 1000, date, dateMillis = date)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  /** A document read to the end: its text, and a meaning vector. */
  private fun read(name: String, date: Long, text: String, v: FloatArray, kind: FileKind = FileKind.DOCUMENT): Long {
    val f = add(name, date, kind)
    val ids = store.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = text, vector = v)), FileState.UNDERSTOOD)
    vectors.add(ids[0], f, kind, Modality.TEXT, false, v)
    return f
  }

  private fun quickOnly(name: String, date: Long, v: FloatArray): Long {
    val f = add(name, date, FileKind.IMAGE)
    val id = store.saveQuick(f, SegmentKind.IMAGE, v)
    quick.add(id, f, FileKind.IMAGE, Modality.CLIP, false, v)
    return f
  }

  private fun names(r: SearchResults) = r.hits.map { it.file.name }
  private fun top(r: SearchResults) = r.hits.take(6).joinToString { "${it.file.name} %.2f".format(it.score) }
  private fun search(q: String, view: ResultView = ResultView.DEFAULT, vector: FloatArray? = null, quickVector: FloatArray? = null) =
    searcher.search(q, vector, KindFilter.ALL, 50, view = view, quickVector = quickVector)

  // ---- A: the date chip and a date that is part of the name ----------------------------------------------------------

  @Test fun aFileNamedWithTheDateIsNotHiddenByTheDateChip() {
    add("Church choir schedule Oct 2026.docx", ms(2026, 9, 18))   // modified in September, named October
    add("church choir schedule october.txt", ms(2026, 10, 5))     // an October file that matches on its words: the list is not empty
    val r = search("church choir schedule", chip("oct 2026", october))
    assertTrue(names(r).toString(), "Church choir schedule Oct 2026.docx" in names(r))
    assertTrue(names(r).toString(), "church choir schedule october.txt" in names(r))
  }

  @Test fun aFileWithTheDateInItsTextIsNotHiddenByTheDateChip() {
    read("choir plans.docx", ms(2026, 9, 18), "Church choir schedule for Oct 2026: rehearsals on Tuesdays", noise(Fp16.DIM))
    add("church choir schedule october.txt", ms(2026, 10, 5))
    val r = search("church choir schedule", chip("oct 2026", october))
    assertTrue(names(r).toString(), "choir plans.docx" in names(r))
  }

  @Test fun theChipStillFiltersEveryFileThatDoesNotCarryTheDate() {
    add("Church choir schedule.docx", ms(2026, 9, 18))           // no date words anywhere: the chip hides it, as ever
    add("church choir schedule october.txt", ms(2026, 10, 5))
    val r = search("church choir schedule", chip("oct 2026", october))
    assertEquals(listOf("church choir schedule october.txt"), names(r))
  }

  @Test fun theWholePhraseIsNeeded() {
    add("Church choir schedule 2026.docx", ms(2026, 9, 18))        // "2026" alone is not "oct 2026"
    add("church choir schedule october.txt", ms(2026, 10, 5))
    val r = search("church choir schedule", chip("oct 2026", october))
    assertFalse(names(r).toString(), "Church choir schedule 2026.docx" in names(r))
  }

  @Test fun aRelativeDateIsNeverReadAsContent() {
    add("Notes from last week.docx", ms(2026, 9, 1))               // "last week" in a name is not "the week before today"
    add("notes tuesday.txt", ms(2026, 10, 14))
    val r = search("notes", chip("last week", DateRange(ms(2026, 10, 12), ms(2026, 10, 19))))
    assertEquals(listOf("notes tuesday.txt"), names(r))
  }

  @Test fun theMeaningRowsOfAFileThatCarriesTheDateAreOffered() {
    repeat(30) { read("filler$it.txt", ms(2026, 10, 2), "filler $it", noise(Fp16.DIM)) }
    val target = read("minutes.docx", ms(2026, 9, 18), "Oct 2026 parent meeting", near(5, Fp16.DIM, 0.8))
    val r = search("parent teacher gathering", chip("oct 2026", october), vector = topic(5, Fp16.DIM))
    assertTrue(names(r).toString(), r.hits.any { it.file.id == target })
  }

  // ---- B: a name that holds every word of the query ------------------------------------------------------------------

  /** The state of the phone: pictures seen by quick look only, documents read for their words but not yet for their meaning. */
  private fun learningWorld(photoCosine: Double) {
    repeat(120) { quickOnly("IMG_$it.jpg", ms(2026, 8, 1) + it, noise(IndexStore.QUICK_DIM)) }
    repeat(8) { quickOnly("Screenshot_$it.png", ms(2026, 8, 21) + it, near(9, IndexStore.QUICK_DIM, photoCosine)) }
    repeat(6) { add("Minutes of the school board $it.docx", ms(2026, 9, 1) + it) }   // two of the five words: weaker
  }

  @Test fun aNameThatHoldsEveryWordOfTheQueryLeadsWhileMeaningIsStillBeingLearned() {
    learningWorld(photoCosine = 0.30)
    val target = add("Minutes of PTA meeting 12 Sept.docx", ms(2026, 9, 18))
    val r = search("minutes of pta meeting 12 sept", quickVector = topic(9, IndexStore.QUICK_DIM))
    val photos = r.hits.count { it.file.kind == FileKind.IMAGE }
    assertTrue("photos with a real quick-look match are in the list: $photos", photos >= 1)
    assertEquals(top(r), target, r.hits.first().file.id)
    assertTrue("it is a strong match", r.strongCount >= 1)
  }

  @Test fun theSameWithTheModelFreeAndTheDocumentUnderstood() {
    learningWorld(photoCosine = 0.30)
    val target = read("Minutes of PTA meeting 12 Sept.docx", ms(2026, 9, 18), "Agenda. Apologies. Matters arising.", near(3, Fp16.DIM, 0.15))
    val r = search("minutes of pta meeting 12 sept", vector = topic(3, Fp16.DIM), quickVector = topic(9, IndexStore.QUICK_DIM))
    assertEquals(top(r), target, r.hits.first().file.id)
  }

  @Test fun aPartialNameMatchIsNotLiftedByIt() {
    learningWorld(photoCosine = 0.30)
    val r = search("minutes of pta meeting 12 sept", quickVector = topic(9, IndexStore.QUICK_DIM))
    // "Minutes of the school board 3.docx" has one or two of the five words: it must not lead the pictures that match in meaning
    assertTrue(names(r).toString(), r.hits.first().file.kind == FileKind.IMAGE)
  }

  @Test fun oneWordQueriesAreLeftAlone() {
    // a single word is no "every word of the name": the bonus is for 2+ words, as the phrase bonus is
    val a = add("Receipt.docx", ms(2026, 9, 1))
    val b = read("lunch.docx", ms(2026, 9, 2), "a receipt for lunch receipt", near(4, Fp16.DIM, 0.5))
    val r = search("receipt", vector = topic(4, Fp16.DIM))
    val scoreOf = r.hits.associate { it.file.id to it.score }
    assertEquals(4.0, scoreOf.getValue(a), 1e-9)   // 2.0 coverage + 2.0 pending, nothing more
  }
}
