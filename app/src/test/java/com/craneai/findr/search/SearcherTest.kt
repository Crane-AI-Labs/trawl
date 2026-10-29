package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchResults
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * End to end on the JVM: a real IndexStore (bundled SQLite with FTS5), a real VectorIndex, synthetic vectors.
 * A "topic" is a unit axis; a file about it has that axis plus a little noise, and the query is the pure axis, so a
 * planted match is far past the gate while the random background sits near zero like real unrelated text.
 */
class SearcherTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(42)
  private var nextMediaId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-search").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun tearDown() {
    vectors.close()
    store.close()
    dir.deleteRecursively()
  }

  // ---- fixtures --------------------------------------------------------------------------------------------------

  private fun unit(raw: FloatArray): FloatArray {
    val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(raw.size) { raw[it] / n }
  }

  private fun noise() = FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() }
  private fun background() = unit(noise())

  /** The pure direction of a topic: what a query about it embeds to. */
  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }

  /** A vector close to a topic: the axis plus a little noise. */
  private fun about(k: Int): FloatArray = unit(FloatArray(Fp16.DIM) { (if (it == k) 1f else 0f) + noise()[it] * 0.03f })

  private fun add(
    name: String, kind: FileKind = FileKind.PDF, path: String = "Download/", mtime: Long = 1_000,
    vararg segments: Segment, state: FileState = FileState.UNDERSTOOD,
  ): Long {
    val id = nextMediaId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", path, name, kind, null, 50_000, mtime)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    if (segments.isNotEmpty()) {
      val ids = store.saveSegments(fileId, segments.toList(), state)
      segments.forEachIndexed { i, s ->
        s.vector?.let { vectors.add(ids[i], fileId, kind, s.modality, s.late, it) }
      }
    }
    return fileId
  }

  private fun vecSegment(v: FloatArray, modality: Modality = Modality.TEXT, kind: SegmentKind = SegmentKind.CHUNK, text: String? = null, late: Boolean = false) =
    Segment(kind, modality, text = text, vector = v, late = late)

  /** Unrelated documents and pictures, so the per-query scale is a realistic cloud and not three points. */
  private fun addBackground(texts: Int = 250, images: Int = 60) {
    repeat(texts) { add("doc$it.txt", FileKind.DOCUMENT, "Documents/", 100, vecSegment(background(), text = "background filler number $it about nothing in particular")) }
    repeat(images) { add("pic$it.jpg", FileKind.IMAGE, "DCIM/Camera/", 100, vecSegment(background(), Modality.IMAGE, SegmentKind.IMAGE)) }
  }

  private fun names(r: SearchResults) = r.hits.map { it.file.name }
  private fun search(q: String, vector: FloatArray? = null, filter: KindFilter = KindFilter.ALL, limit: Int = 20) =
    searcher.search(q, vector, filter, limit)

  // ---- meaning ---------------------------------------------------------------------------------------------------

  @Test fun aPlantedMeaningMatchIsFoundFirstAndLabelledMeaning() {
    addBackground()
    add("lease.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(7), text = "tenancy agreement between landlord and tenant"))
    val r = search("where is my rental contract", topic(7))
    assertEquals(listOf("lease.pdf"), names(r))   // only what stands out: nothing else passes the gate
    assertEquals(MatchReason.MEANING, r.hits[0].reason)
    assertEquals(1, r.strongCount)
    assertNotNull(r.hits[0].snippet)   // a passage found by meaning shows what it says
    assertTrue(r.hits[0].snippet!!.startsWith("tenancy agreement"))
  }

  @Test fun nothingPlantedMeansNothingFound() {
    addBackground()
    val r = search("zebra crossing sign", topic(11))   // no file is about topic 11, and no file contains these words
    assertTrue(names(r).toString(), r.hits.isEmpty())
    assertEquals(0, r.strongCount)
  }

  @Test fun textAndImageMatchesShareOneScale() {
    addBackground()
    // Text-to-image cosines run lower. Scale the image match down so its raw cosine is far below the text one's;
    // per-modality centring must still let it past the gate.
    val dimmer = unit(FloatArray(Fp16.DIM) { (if (it == 5) 0.35f else 0f) + background()[it] * 0.9f })
    add("whiteboard.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(dimmer, Modality.IMAGE, SegmentKind.IMAGE))
    add("notes.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(about(5), text = "meeting minutes"))
    val r = search("planning meeting", topic(5))
    assertTrue(names(r).toString(), "notes.txt" in names(r))
    assertEquals("notes.txt", names(r).first())
  }

  @Test fun theKindFilterNarrowsMeaningMatches() {
    addBackground()
    add("a.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(3), text = "alpha"))
    add("b.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(about(3), Modality.IMAGE, SegmentKind.IMAGE))
    assertEquals(setOf("a.pdf", "b.jpg"), names(search("anything", topic(3))).toSet())
    assertEquals(listOf("b.jpg"), names(search("anything", topic(3), KindFilter.PHOTOS)))
    assertEquals(listOf("a.pdf"), names(search("anything", topic(3), KindFilter.DOCUMENTS)))
    assertTrue(search("anything", topic(3), KindFilter.VIDEOS).hits.isEmpty())
  }

  @Test fun theBestSegmentOfAFileRepresentsIt() {
    addBackground()
    // A long PDF: 4 pages, only page 3 is about the topic.
    val pages = (1..4).map { p ->
      Segment(SegmentKind.PAGE, Modality.TEXT, locStart = p.toLong(), text = "page $p text", vector = if (p == 3) about(9) else background())
    }
    add("report.pdf", FileKind.PDF, "Download/", 1_000, *pages.toTypedArray())
    val r = search("quarterly figures", topic(9))
    assertEquals(listOf("report.pdf"), names(r))
    assertEquals(Locator.Page(3), r.hits[0].locator)
  }

  @Test fun aVideoFrameHitPointsAtTheMoment() {
    addBackground()
    add("trip.mp4", FileKind.VIDEO, "DCIM/Camera/", 1_000,
      Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = 4_000, locEnd = 9_000, vector = background()),
      Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = 12_000, locEnd = 15_500, vector = about(2)))
    val r = search("waterfall", topic(2))
    assertEquals(listOf("trip.mp4"), names(r))
    // a row from before the keyframe time was kept: the middle of its slot (12_000 + 3_500 / 2), not its start
    assertEquals(Locator.Time(13_750, 15_500), r.hits[0].locator)
  }

  @Test fun aVideoFrameHitShowsTheKeyframesOwnTime() {
    addBackground()
    // the slot is 12.0 to 15.5 s, but the picture is the keyframe at 14.2 s (marks "@14200"): that is the time shown
    add("fwd.mp4", FileKind.VIDEO, "Download/", 1_000,
      Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = 4_000, locEnd = 9_000, vector = background(), marks = "@8333"),
      Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = 12_000, locEnd = 15_500, vector = about(2), marks = "@14200"))
    val r = search("waterfall", topic(2))
    assertEquals(Locator.Time(14_200, 15_500), r.hits[0].locator)
  }

  // ---- words -----------------------------------------------------------------------------------------------------

  @Test fun anExactCodeIsFoundByWordsEvenWhenMeaningMissesIt() {
    addBackground()
    add("board-manual.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(background(), text = "Motherboard model TRX50 installation manual"))
    val r = search("TRX50", topic(20))   // no file is about topic 20, so meaning has nothing to say
    assertEquals(listOf("board-manual.pdf"), names(r))
    assertEquals(MatchReason.WORDS, r.hits[0].reason)
    assertTrue(r.hits[0].snippet!!.contains("«TRX50»"))
  }

  @Test fun wordsAndMeaningTogetherOutrankEitherAlone() {
    addBackground()
    add("both.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(4), text = "electricity bill for october"))
    add("meaning-only.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(4), text = "kilowatt usage statement"))
    add("words-only.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(background(), text = "electricity bill reminder template"))
    val r = search("electricity bill", topic(4))
    assertEquals(listOf("both.pdf", "meaning-only.pdf", "words-only.pdf").sorted(), names(r).sorted())
    assertEquals("both.pdf", names(r)[0])
    assertEquals(MatchReason.BOTH, r.hits[0].reason)
    assertEquals(MatchReason.WORDS, r.hits.first { it.file.name == "words-only.pdf" }.reason)
    assertEquals(MatchReason.MEANING, r.hits.first { it.file.name == "meaning-only.pdf" }.reason)
  }

  @Test fun aPendingDocumentWithAStrongWordMatchIsNotBuriedByAWeakerMeaningOnlyScreenshot() {
    // the research notes, item 2: while a phone is still indexing, the worker reads pictures and screenshots before
    // documents, so a document can sit with only its words for a while after a screenshot already has a meaning
    // vector. A weak, barely-qualifying screenshot match must not then outrank a document with a strong word match.
    addBackground(texts = 40, images = 40)
    // The same "dimmer" construction as textAndImageMatchesShareOneScale: understood, and clears the gate, but weakly.
    val weakMatch = unit(FloatArray(Fp16.DIM) { (if (it == 6) 0.15f else 0f) + background()[it] * 0.9f })
    add("weak.jpg", FileKind.SCREENSHOT, "Pictures/Screenshots/", 1_000, vecSegment(weakMatch, Modality.IMAGE, SegmentKind.IMAGE))
    // The right file: every one of the query's words, but still waiting for its own meaning pass (no vector yet).
    add(
      "electricity-bill.pdf", FileKind.DOCUMENT, "Documents/", 1_000,
      Segment(SegmentKind.CHUNK, Modality.NONE, text = "october electricity bill payment confirmation"),
      state = FileState.SEARCHABLE,
    )
    val r = search("october electricity bill", topic(6))
    assertEquals("the pending document must not be buried by the weaker, meaning-only screenshot", "electricity-bill.pdf", names(r).first())
    assertEquals(MatchReason.WORDS, r.hits.first { it.file.name == "electricity-bill.pdf" }.reason)
    assertTrue("weak.jpg" in names(r))   // still found -- this is about order, not hiding it
  }

  @Test fun aFileNeedsHalfTheQueryWordsToQualifyByWordsAlone() {
    addBackground(texts = 20, images = 0)
    add("one-of-four.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "invoice"))
    add("two-of-four.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "invoice from the garage"))
    val r = search("invoice garage repair brakes")
    assertEquals(listOf("two-of-four.txt"), names(r))
    assertEquals(MatchReason.WORDS, r.hits[0].reason)
  }

  @Test fun fileNamesAreSearchableBeforeAnythingIsUnderstood() {
    addBackground(texts = 5, images = 0)
    add("Receipt_Shoprite_2026.pdf", FileKind.PDF, "Download/WhatsApp Documents/", 1_000, state = FileState.SEARCHABLE)
    val r = search("shoprite receipts")   // stemming: receipts -> receipt; folder and name words both count
    assertEquals(listOf("Receipt_Shoprite_2026.pdf"), names(r))
    assertEquals(MatchReason.WORDS, r.hits[0].reason)
    val folder = search("whatsapp documents")
    assertEquals(listOf("Receipt_Shoprite_2026.pdf"), names(folder))
  }

  @Test fun aWordInNoFileMatchesAsAPrefixButAWordInTheIndexStaysWhole() {
    add("a.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "Grok4 benchmark notes"))
    add("b.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "my cat photo caption"))
    add("c.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "category list"))
    assertEquals(listOf("a.txt"), names(search("grok")))   // "grok" is in no file as typed: becomes grok*
    assertEquals(listOf("b.txt"), names(search("cat")))    // "cat" is in the index: stays whole, no "category"
  }

  @Test fun theLastWordIsAPrefixWhileTyping() {
    add("a.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(background(), text = "receipt for the laptop"))
    assertTrue(searcher.search("recei", null, KindFilter.ALL, 10, prefixLast = true).hits.isNotEmpty())
  }

  @Test fun theWordsOfAKindAreNotContentWords() {
    add("budget-summary.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(background(), text = "budget summary"))
    add("budget-notes.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "budget"))
    add("beach.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(background(), Modality.IMAGE, SegmentKind.IMAGE))
    // "pdf" and "document" are dropped as stopwords, so the text file (which has neither word) covers the query fully:
    // coverage 1.0 -> 2.0, plus the +1 kind hint both files earn for "pdf"/"document".
    val r = search("budget pdf document")
    assertEquals(setOf("budget-summary.pdf", "budget-notes.txt"), names(r).toSet())
    assertEquals(listOf(3.0, 3.0), r.hits.map { it.score })
  }

  @Test fun punctuationAndOperatorsInTheQueryCannotBreakSearch() {
    add("a.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "cats AND dogs"))
    for (q in listOf("\"cats", "cats OR", "cats* NEAR dogs", "(cats", "dogs'; DROP TABLE files;--", "", "   ", "***", "NOT cats")) {
      val r = search(q)   // must not throw
      assertTrue(q, r.hits.size <= 1)
    }
    assertEquals(listOf("a.txt"), names(search("cats AND dogs")))
  }

  // ---- fusion details ----------------------------------------------------------------------------------------------

  @Test fun theKindTheQueryNamesGetsASoftBoost() {
    addBackground()
    val v = about(6)
    add("holiday.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(v, Modality.IMAGE, SegmentKind.IMAGE))
    add("Screenshot_2026.png", FileKind.SCREENSHOT, "Pictures/Screenshots/", 1_000, vecSegment(v, Modality.IMAGE, SegmentKind.IMAGE))
    assertEquals("holiday.jpg", names(search("beach", topic(6))).first())   // equal vectors: older id first
    assertEquals("Screenshot_2026.png", names(search("screenshot of the beach", topic(6))).first())
    assertEquals("holiday.jpg", names(search("photo of the beach", topic(6))).first())
    assertEquals(2, search("screenshot of the beach", topic(6)).hits.size)   // a boost, not a filter
  }

  @Test fun weakWordOnlyMatchesTrailUnderTheStrongCount() {
    addBackground()
    add("lease.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(8), text = "lease agreement for the apartment"))
    add("template.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "lease apartment"))
    val r = search("lease agreement apartment", topic(8))
    assertEquals("lease.pdf", names(r).first())
    assertTrue(r.strongCount >= 1)
    assertTrue(r.hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    assertTrue(r.strongCount <= r.hits.size)
  }

  @Test fun lateVectorsAreSearchedButStayOutOfTheScale() {
    addBackground(texts = 100, images = 0)
    // A big book's later pages: many low-scoring text vectors flagged late.
    val pages = (1..600).map { Segment(SegmentKind.PAGE, Modality.TEXT, locStart = (it + 20).toLong(), text = null, vector = background(), late = true) }
    val book = add("book.pdf", FileKind.PDF, "Download/", 1_000, *pages.toTypedArray())
    val withBook = search("unrelated", topic(12))
    assertTrue(withBook.hits.isEmpty())
    add("target.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(12), text = "target"))
    assertEquals(listOf("target.pdf"), names(search("unrelated", topic(12))))
    assertTrue(book > 0)
  }

  /** A photo whose vector is from the previous model: in the index as stale, its file back in the queue. */
  private fun addStalePhoto(name: String, v: FloatArray, mtime: Long = 100): Long {
    val id = nextMediaId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/Camera/", name, FileKind.IMAGE, null, 50_000, mtime)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    val ids = store.saveSegments(fileId, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = v)), FileState.SEARCHABLE)
    vectors.add(ids[0], fileId, FileKind.IMAGE, Modality.IMAGE, false, v, stale = true)
    return fileId
  }

  @Test fun photosStaySearchableByMeaningWhileTheyAreWaitingToBeReEmbedded() {
    addBackground(texts = 250, images = 0)
    repeat(60) { addStalePhoto("old$it.jpg", background()) }
    addStalePhoto("whiteboard.jpg", about(5), mtime = 1_000)
    add("notes.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(about(5), text = "meeting minutes"))
    // Every photo is stale, none has been read again yet: the search still ranks by meaning (no collapse of the scale).
    val r = search("planning meeting", topic(5))
    assertTrue(names(r).toString(), "whiteboard.jpg" in names(r))
    assertTrue(names(r).toString(), "notes.txt" in names(r))
    assertTrue("only what stands out passes: ${names(r)}", names(r).size <= 3)
  }

  @Test fun staleVectorsDoNotMoveTheScaleOnceEnoughCurrentOnesExist() {
    addBackground(texts = 100, images = 60)   // 60 current photos: a scale of their own
    repeat(600) { addStalePhoto("old$it.jpg", background()) }
    assertTrue(search("unrelated", topic(12)).hits.isEmpty())
    add("target.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(about(12), Modality.IMAGE, SegmentKind.IMAGE))
    assertEquals(listOf("target.jpg"), names(search("unrelated", topic(12))))
  }

  @Test fun markingTheLoadedVectorsStaleChangesNoResult() {
    addBackground(texts = 250, images = 60)
    add("whiteboard.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(about(5), Modality.IMAGE, SegmentKind.IMAGE))
    add("notes.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(about(5), text = "meeting minutes"))
    val before = names(search("planning meeting", topic(5)))
    assertTrue("whiteboard.jpg" in before)
    assertEquals(61, vectors.markStale(setOf(Modality.IMAGE, Modality.AUDIO)))
    val after = names(search("planning meeting", topic(5)))
    assertTrue(after.toString(), "whiteboard.jpg" in after && "notes.txt" in after)
  }

  @Test fun limitAndTieOrderAreDeterministic() {
    val first = (1..8).map { add("same$it.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(background(), text = "shared phrase here")) }
    val r = search("shared phrase", limit = 3)
    assertEquals(3, r.hits.size)
    assertEquals(first.take(3), r.hits.map { it.file.id })   // equal scores: lowest file id first
    assertTrue(search("shared phrase", limit = 0).hits.isEmpty())
  }

  // ---- partial index and housekeeping --------------------------------------------------------------------------------

  @Test fun withoutAQueryVectorOnlyWordsAnswerAndTheIndexIsMarkedIncomplete() {
    add("waiting.jpg", FileKind.IMAGE, "DCIM/Camera/", 1_000, state = FileState.SEARCHABLE)
    val r = search("waiting")
    assertEquals(listOf("waiting.jpg"), names(r))
    assertTrue(r.indexIncomplete)
    store.setState(store.knownMediaStoreIds("external_primary").values.first(), FileState.UNDERSTOOD)
    assertFalse(search("waiting").indexIncomplete)
  }

  @Test fun disabledKindsDoNotKeepTheIndexIncompleteForever() {
    add("song.mp3", FileKind.AUDIO, "Music/", 1_000, state = FileState.SEARCHABLE)
    assertTrue(Searcher(store, vectors).search("song", null, KindFilter.ALL, 5).indexIncomplete)
    val audioOff = Searcher(store, vectors, indexedKinds = { FileKind.entries.toSet() - FileKind.AUDIO - FileKind.OTHER })
    assertFalse(audioOff.search("song", null, KindFilter.ALL, 5).indexIncomplete)
  }

  @Test fun aWrongSizedQueryVectorIsIgnoredNotFatal() {
    add("a.txt", FileKind.DOCUMENT, "Documents/", 1_000, vecSegment(about(1), text = "hello world"))
    val r = search("hello", FloatArray(10) { 1f })
    assertEquals(listOf("a.txt"), names(r))
  }

  @Test fun deletedAndSkippedFilesNeverAppear() {
    addBackground(texts = 50, images = 0)
    val gone = add("gone.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(10), text = "ephemeral secret plans"))
    assertEquals(listOf("gone.pdf"), names(search("ephemeral secret", topic(10))))
    store.markDeleted(listOf(gone))
    // Words are gone with the cascade. The vectors are still in memory until the engine drops them, but a hit for a
    // file the store no longer has is never shown.
    assertTrue(search("ephemeral secret", topic(10)).hits.isEmpty())
    vectors.removeFile(gone)
    assertTrue(search("ephemeral secret", topic(10)).hits.isEmpty())

    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 999, "content://x", "Android/data/x/", "private.jpg", FileKind.IMAGE, null, 90_000, 5, skipReason = "app-private folder (Android/data)")))
    assertTrue(search("private").hits.isEmpty())
  }

  @Test fun queriesAreFastOnAFullSizedIndex() {
    // 20k vectors across 5k files: search (scoring, scale, SQL) well inside an interactive budget on a desktop JVM.
    val index = VectorIndex(parallelism = 1)
    val big = Searcher(store, index)
    repeat(2000) { f ->
      val id = nextMediaId++
      store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://m/$id", "Documents/", "bulk$f.txt", FileKind.DOCUMENT, null, 1000, 1)))
    }
    val fileIds = store.knownMediaStoreIds("external_primary").values.toList()
    fileIds.forEachIndexed { i, fileId ->
      val segs = List(4) { Segment(SegmentKind.CHUNK, Modality.TEXT, text = "bulk chunk ${i}x$it lorem ipsum", vector = background()) }
      val ids = store.saveSegments(fileId, segs, FileState.UNDERSTOOD)
      segs.forEachIndexed { k, s -> index.add(ids[k], fileId, FileKind.DOCUMENT, Modality.TEXT, false, s.vector!!) }
    }
    val q = background()
    big.search("lorem ipsum", q, KindFilter.ALL, 20)   // warm
    val t0 = System.nanoTime()
    repeat(5) { big.search("lorem ipsum", q, KindFilter.ALL, 20) }
    val ms = (System.nanoTime() - t0) / 5e6
    println("BENCH full search over ${index.size} vectors / ${fileIds.size} files: %.1f ms per query".format(ms))
    assertTrue("search took $ms ms", ms < 500)
    index.close()
  }

  @Test fun reloadingFromTheStoreGivesTheSameAnswers() {
    addBackground(texts = 80, images = 10)
    add("lease.pdf", FileKind.PDF, "Download/", 1_000, vecSegment(about(7), text = "tenancy agreement"))
    val before = search("tenancy", topic(7))
    val fresh = VectorIndex(parallelism = 1)
    fresh.load(store)
    assertEquals(vectors.size, fresh.size)
    val after = Searcher(store, fresh).search("tenancy", topic(7), KindFilter.ALL, 20)
    assertEquals(names(before), names(after))
    assertEquals(before.hits.map { it.score }, after.hits.map { it.score })
    fresh.close()
  }

  // ---- literal hits are never "weaker" ---------------------------------------------------------------------------

  private fun photo(name: String, v: FloatArray) = add(name, FileKind.IMAGE, "DCIM/Camera/", 1_000, vecSegment(v, Modality.IMAGE, SegmentKind.IMAGE))

  private fun strongNames(r: SearchResults) = names(r).take(r.strongCount)

  @Test fun filesThatSayTheWordAreNotFiledUnderWeakerWhenPicturesOfItScoreHigh() {
    addBackground()
    repeat(3) { photo("IMG_20260911_$it.jpg", about(10)) }
    textFile("shop_receipt.txt", "Kids Corner receipt. Remote control toy car, 45,000 UGX. Wooden toy train, 38,000 UGX.")
    textFile("school_newsletter.txt", "Term 3 newsletter. On Fridays the nursery section may bring a toy from home for show and tell.")
    val r = searcher.search("toy", topic(10), KindFilter.ALL, 20, prefixLast = true)
    assertEquals("toy photos (z far above the word weight) stay on top", listOf("IMG_20260911_0.jpg", "IMG_20260911_1.jpg", "IMG_20260911_2.jpg"), names(r).take(3).sorted())
    // before the promotion the three photos were the whole strong group and both documents sat under "weaker matches"
    assertEquals(setOf("shop_receipt.txt", "school_newsletter.txt"), strongNames(r).filter { it.endsWith(".txt") }.toSet())
    assertEquals(5, r.strongCount)
  }

  @Test fun promotingLiteralHitsLeavesTheStrongOnesAndTheTopHitWhereTheyWere() {
    addBackground()
    photo("IMG_a.jpg", about(10))
    textFile("note.txt", "a toy for the baby")
    val r = searcher.search("toy", topic(10), KindFilter.ALL, 20, prefixLast = true)
    assertEquals("IMG_a.jpg", names(r).first())
    assertEquals(listOf("IMG_a.jpg", "note.txt"), strongNames(r))
  }

  @Test fun aWordThatOnlyStartsALongerWordIsNotPromoted() {
    addBackground()
    photo("IMG_b.jpg", about(10))
    // Porter stems "toy" to "toi", so the prefix "toi*" also reaches "toilet"
    textFile("supermarket.txt", "Toilet paper 12 rolls, soap, bread, 35,000 UGX")
    textFile("toys.txt", "church toys drive, bring a toy on Sunday")
    val r = searcher.search("toy", topic(10), KindFilter.ALL, 20, prefixLast = true)
    assertTrue("supermarket.txt" in names(r))                  // still a match, a prefix reaches it while the person types
    assertTrue("toys.txt" in strongNames(r))             // the whole word (here "toy") is
    assertFalse("supermarket.txt" in strongNames(r))
  }

  @Test fun aFileWithOnlySomeOfTheQueryWordsIsNotPromoted() {
    addBackground()
    photo("IMG_c.jpg", about(10))
    textFile("both.txt", "remote control toy car")
    textFile("half.txt", "a toy for the baby")
    val r = searcher.search("toy car", topic(10), KindFilter.ALL, 20, prefixLast = true)
    assertTrue("both.txt" in strongNames(r))
    assertFalse("half.txt" in strongNames(r))
    assertTrue("half.txt" in names(r))
  }

  @Test fun theStrongCountFollowsThePromotionUnderEverySort() {
    addBackground()
    photo("IMG_d.jpg", about(10))
    textFile("note.txt", "a toy for the baby")
    for (sort in listOf(com.craneai.findr.core.SortOrder.BEST, com.craneai.findr.core.SortOrder.NEWEST, com.craneai.findr.core.SortOrder.NAME)) {
      val r = searcher.search("toy", topic(10), KindFilter.ALL, 20, prefixLast = true, view = com.craneai.findr.core.ResultView.DEFAULT.copy(sort = sort))
      assertEquals(sort.toString(), 2, r.strongCount)
    }
  }

  // ---- the phrase bonus --------------------------------------------------------------------------------------------

  private fun textFile(name: String, text: String, kind: SegmentKind = SegmentKind.CHUNK, fileKind: FileKind = FileKind.DOCUMENT) =
    add(name, fileKind, "Documents/", 1_000, Segment(kind, Modality.NONE, text = text), state = FileState.SEARCHABLE)

  private fun scoreOf(r: SearchResults, name: String) = r.hits.first { it.file.name == name }.score

  @Test fun aPhraseHitGetsTheBonusOverScatteredWords() {
    addBackground(texts = 40, images = 0)
    textFile("phrase.txt", "notes: the quarterly budget review is on friday")
    textFile("scattered.txt", "review the old numbers again, then the budget")
    textFile("reversed.txt", "a review budget for next year")
    val r = search("budget review")
    assertEquals(listOf("phrase.txt"), names(r).take(1))
    // all three hold both words (coverage 1.0 = 2.0); only the one where they stand side by side, in order, gets +2.0
    assertEquals(2.0, scoreOf(r, "scattered.txt"), 1e-9)
    assertEquals(2.0, scoreOf(r, "reversed.txt"), 1e-9)
    assertEquals(2.0 + Fusion.PHRASE_BONUS, scoreOf(r, "phrase.txt"), 1e-9)
    assertEquals(Fusion.WORD_WEIGHT, Fusion.PHRASE_BONUS, 0.0)
  }

  @Test fun theBonusFollowsPorterStemming() {
    textFile("stemmed.txt", "the budget reviews were late")
    textFile("scattered.txt", "reviews of the budget")
    val r = search("budget review")
    assertEquals(4.0, scoreOf(r, "stemmed.txt"), 1e-9)
    assertEquals(2.0, scoreOf(r, "scattered.txt"), 1e-9)
  }

  @Test fun aOneWordQueryGetsNoBonus() {
    textFile("a.txt", "the budget is ready")
    assertEquals(2.0, scoreOf(search("budget"), "a.txt"), 1e-9)
    // stopwords are not terms ("the" is dropped), so this is a one-word query too
    assertEquals(2.0, scoreOf(search("the budget"), "a.txt"), 1e-9)
  }

  @Test fun theLastWordMayBeUnfinishedWhenTheCallerSaysSo() {
    textFile("phrase.txt", "the quarterly budget review is on friday")
    textFile("scattered.txt", "review the old numbers again, then the budget")
    val typing = searcher.search("budget rev", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals(4.0, scoreOf(typing, "phrase.txt"), 1e-9)
    assertEquals(2.0, scoreOf(typing, "scattered.txt"), 1e-9)
    // finished typing: "rev" is no word of the index, so the phrase does not match and nothing gets the bonus
    val done = searcher.search("budget rev", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals(2.0, scoreOf(done, "phrase.txt"), 1e-9)
  }

  @Test fun phrasesInOcrTextCount() {
    textFile("receipt.jpg", "total amount due 4500", SegmentKind.OCR, FileKind.IMAGE)
    textFile("scattered.jpg", "amount of 4500, total", SegmentKind.OCR, FileKind.IMAGE)
    val r = search("total amount due")
    assertEquals(4.0, scoreOf(r, "receipt.jpg"), 1e-9)
    assertEquals(2.0 * Math.pow(2.0 / 3.0, 1.5), scoreOf(r, "scattered.jpg"), 1e-9)   // 2 of 3 words: coverage only
  }

  @Test fun aFileNameOrFolderThatSpellsThePhraseIsNotAPhraseHit() {
    add("budget-review.pdf", FileKind.PDF, "Budget Review/", 1_000)
    textFile("other.txt", "review the budget")
    val r = search("budget review")
    // Words in the name and folder are not a PHRASE hit (PHRASE_BONUS stays with text), but a name that holds every word of the
    // query gets its own NAME_BONUS (the same size): 2.0 coverage + 2.0. A file whose text merely has the words keeps coverage only.
    assertEquals(2.0 + Fusion.NAME_BONUS, scoreOf(r, "budget-review.pdf"), 1e-9)
    assertEquals(2.0, scoreOf(r, "other.txt"), 1e-9)
    // the same folder with a name that does NOT hold the words: no bonus of either kind
    add("q3.pdf", FileKind.PDF, "Budget Review/", 1_000)
    assertEquals(2.0, scoreOf(search("budget review"), "q3.pdf"), 1e-9)
  }

  @Test fun wordsSplitAcrossTwoChunksAreNotAPhrase() {
    add("split.txt", FileKind.DOCUMENT, "Documents/", 1_000,
      Segment(SegmentKind.CHUNK, Modality.NONE, text = "the meeting covered the quarterly budget"),
      Segment(SegmentKind.CHUNK, Modality.NONE, text = "review and then lunch"), state = FileState.SEARCHABLE)
    assertEquals(2.0, scoreOf(search("budget review"), "split.txt"), 1e-9)
  }

  @Test fun stopwordsBetweenTheQueryWordsBreakTheContiguity() {
    // The rule is on the query's content words (Fusion.terms, which drops stopwords), so "report of the year" asks for
    // "report year" side by side. This pins that behaviour: text with "of the" between them gets no bonus.
    textFile("gap.txt", "the report of the year")
    textFile("tight.txt", "a report year summary")
    val r = search("report of the year")
    assertEquals(2.0, scoreOf(r, "gap.txt"), 1e-9)
    assertEquals(4.0, scoreOf(r, "tight.txt"), 1e-9)
  }

  @Test fun phrasesInNonLatinScriptsWork() {
    textFile("ru-phrase.txt", "утверждён годовой бюджет проекта на следующий год")
    textFile("ru-scattered.txt", "проекта нового года, и годовой бюджет")
    val ru = search("годовой бюджет проекта")
    assertEquals(4.0, scoreOf(ru, "ru-phrase.txt"), 1e-9)
    assertEquals(2.0, scoreOf(ru, "ru-scattered.txt"), 1e-9)
    textFile("ar-phrase.txt", "تم اعتماد الميزانية السنوية للمشروع اليوم")
    textFile("ar-scattered.txt", "للمشروع اليوم تم اعتماد الميزانية و السنوية")
    val ar = search("الميزانية السنوية للمشروع")
    assertEquals(4.0, scoreOf(ar, "ar-phrase.txt"), 1e-9)
    assertEquals(4.0 - Fusion.PHRASE_BONUS, scoreOf(ar, "ar-scattered.txt"), 1e-9)
  }

  @Test fun theBonusRespectsTheKindFilter() {
    textFile("a.txt", "the quarterly budget review")
    textFile("b.jpg", "quarterly budget review", SegmentKind.OCR, FileKind.IMAGE)
    val r = search("budget review", filter = KindFilter.PHOTOS)
    assertEquals(listOf("b.jpg"), names(r))
    assertEquals(4.0, scoreOf(r, "b.jpg"), 1e-9)
  }
  // ---- mentioning: a date typed in a query that is written in the files ------------------------------------------------

  @Test fun mentioningFindsTheFilesThatHaveEveryWordOfThePhrase() {
    val paye = textFile("sms.png", "URA notice: your PAYE return for September has not been filed", SegmentKind.OCR, FileKind.IMAGE)
    val chat = textFile("chat.png", "Ritah: you said that last Friday", SegmentKind.OCR, FileKind.IMAGE)
    val other = textFile("plain.txt", "the quarterly budget, nothing about months")
    val all = listOf(paye, chat, other)
    assertEquals(setOf(paye), searcher.mentioning("september", all))
    assertEquals(setOf(chat), searcher.mentioning("last friday", all))
    assertEquals(emptySet<Long>(), searcher.mentioning("last september", all))   // every word, not any word
    assertEquals(emptySet<Long>(), searcher.mentioning("october", all))
  }

  @Test fun mentioningOnlyLooksAtTheFilesItIsGivenAndReadsTheNameToo() {
    val named = add("statement_august_2026.txt", FileKind.DOCUMENT, "Documents/", 1_000, Segment(SegmentKind.CHUNK, Modality.NONE, text = "opening balance", vector = null), state = FileState.SEARCHABLE)
    val written = textFile("note.txt", "due in august")
    assertEquals(setOf(named, written), searcher.mentioning("in august", listOf(named, written)))   // "in" is no content word
    assertEquals(setOf(written), searcher.mentioning("august", listOf(written)))
    assertEquals(emptySet<Long>(), searcher.mentioning("august", emptyList()))
    assertEquals(emptySet<Long>(), searcher.mentioning("the in on", listOf(named, written)))   // no content word: nothing is proven
  }
}
