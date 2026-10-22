package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SearchResults
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
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The words step must not wait for the vector matrix (a cold process), and the final answer must be exactly what the
 * old order (load vectors, then words, then meaning) produced. Real IndexStore and Searcher on the JVM, synthetic vectors.
 */
class ProgressiveSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private val random = Random(7)
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-progressive").toFile()
    store = IndexStore.open(dir, log = {})
    fill()
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private fun unit(raw: FloatArray): FloatArray {
    val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(raw.size) { raw[it] / n }
  }

  private fun background() = unit(FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() })
  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }
  private fun about(k: Int) = unit(FloatArray(Fp16.DIM) { (if (it == k) 1f else 0f) + (random.nextDouble() * 2 - 1).toFloat() * 0.03f })

  private fun addTo(target: IndexStore, name: String, kind: FileKind, path: String, state: FileState, vararg segments: Segment) {
    val id = nextId++
    target.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", path, name, kind, null, 50_000, 1_000)))
    val fileId = target.knownMediaStoreIds("external_primary").getValue(id)
    if (segments.isNotEmpty()) target.saveSegments(fileId, segments.toList(), state)
  }

  private fun add(name: String, kind: FileKind, path: String, state: FileState, vararg segments: Segment) =
    addTo(store, name, kind, path, state, *segments)

  private fun text(v: FloatArray, words: String) = Segment(SegmentKind.CHUNK, Modality.TEXT, text = words, vector = v)
  private fun image(v: FloatArray, ocr: String? = null) = Segment(SegmentKind.IMAGE, Modality.IMAGE, text = ocr, vector = v)

  /** A phone-like mix: background documents and pictures, planted matches by meaning, by words, by both, a name-only file. */
  private fun fill() {
    repeat(120) { add("doc$it.txt", FileKind.DOCUMENT, "Documents/", FileState.UNDERSTOOD, text(background(), "background filler number $it about nothing")) }
    repeat(40) { add("IMG_$it.jpg", FileKind.IMAGE, "DCIM/Camera/", FileState.UNDERSTOOD, image(background())) }
    add("lease.pdf", FileKind.PDF, "Download/", FileState.UNDERSTOOD, text(about(7), "tenancy agreement between landlord and tenant"))
    add("electricity.pdf", FileKind.PDF, "Download/", FileState.UNDERSTOOD, text(about(4), "electricity bill for october"))
    add("kilowatts.pdf", FileKind.PDF, "Download/", FileState.UNDERSTOOD, text(about(4), "kilowatt usage statement"))
    add("bill-template.txt", FileKind.DOCUMENT, "Documents/", FileState.UNDERSTOOD, text(background(), "electricity bill reminder template"))
    add("board-manual.pdf", FileKind.PDF, "Download/", FileState.UNDERSTOOD, text(background(), "Motherboard model TRX50 installation manual"))
    add("sunset.jpg", FileKind.IMAGE, "DCIM/Camera/", FileState.UNDERSTOOD, image(about(9)))
    add("funny_cat_meme.jpg", FileKind.IMAGE, "Pictures/", FileState.UNDERSTOOD, image(about(11), "when the monday hits"))
    add("Receipt_Shoprite_2026.pdf", FileKind.PDF, "Download/WhatsApp Documents/", FileState.SEARCHABLE)   // name only, no vector yet
    add("trip.mp4", FileKind.VIDEO, "DCIM/Camera/", FileState.UNDERSTOOD,
      Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = 4_000, locEnd = 9_000, vector = background()),
      Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = 12_000, locEnd = 15_500, vector = about(2)))
  }

  /** Query text to the vector a model would give it. Anything not listed gets an unrelated vector, like a real query would. */
  private val modelVectors = mapOf(
    "rental contract" to topic(7), "electricity bill" to topic(4), "sunset on the beach" to topic(9),
    "funny monday meme" to topic(11), "waterfall" to topic(2), "zebra crossing sign" to topic(30),
  )

  private fun embed(q: String): FloatArray =
    modelVectors[q] ?: Random(q.hashCode()).let { r -> unit(FloatArray(Fp16.DIM) { r.nextFloat() - 0.5f }) }

  private val queries = listOf(
    "rental contract", "electricity bill", "sunset on the beach", "funny monday meme", "waterfall", "zebra crossing sign",
    "TRX50", "trx", "shoprite receipts", "whatsapp documents", "monday", "photo of the beach", "pdf", "ab", "a", "  lease  ",
    "cats AND dogs", "\"lease", "nothing matches this phrase", "kilowatt",
  )
  private val filters = listOf(KindFilter.ALL, KindFilter.PHOTOS, KindFilter.DOCUMENTS, KindFilter.VIDEOS)
  private val kinds = { FileKind.entries.toSet() - FileKind.OTHER }

  /** The pipeline as it ran before this change: load the matrix first, then words, then meaning. */
  private fun oldOrder(q: String, filter: KindFilter, vectors: VectorIndex, hasModel: Boolean): List<SearchResults> {
    val searcher = Searcher(store, vectors, kinds)
    val t = q.trim()
    if (t.isEmpty()) return listOf(SearchResults(q, emptyList(), 0, 0, indexIncomplete = false))
    vectors.load(store)
    val wants = vectors.size > 0 && t.length >= 3 && hasModel
    val out = ArrayList<SearchResults>()
    out += searcher.search(t, null, filter, 20, prefixLast = true).copy(complete = !wants)
    if (wants) out += searcher.search(t, embed(t), filter, 20, prefixLast = true)
    return out
  }

  private class Run(val emissions: List<SearchResults>, val emissionsWhenLoadStarted: Int?)

  private fun newOrder(q: String, filter: KindFilter, vectors: VectorIndex, hasModel: Boolean): Run = runBlocking {
    val searcher = Searcher(store, vectors, kinds)
    val seen = ArrayList<SearchResults>()
    var whenLoadStarted: Int? = null
    val progressive = ProgressiveSearch(
      searcher = { searcher },
      meaningPossible = { hasModel },
      storedVectors = { store.hasVectors() },
      ensureVectors = { whenLoadStarted = seen.size; vectors.load(store) },
      wantsMeaning = { it.length >= 3 && vectors.size > 0 && hasModel },
      embed = { QueryVectors(embed(it), null) },
    )
    // Collected one by one, so `seen` holds what the caller has received at the moment the load starts.
    progressive.run(q, filter, 20).collect { seen += it }
    Run(seen, whenLoadStarted)
  }

  /** Everything except how long it took. */
  private fun same(a: SearchResults, b: SearchResults, label: String) {
    assertEquals("$label query", a.query, b.query)
    assertEquals("$label complete", a.complete, b.complete)
    assertEquals("$label indexIncomplete", a.indexIncomplete, b.indexIncomplete)
    assertEquals("$label strongCount", a.strongCount, b.strongCount)
    assertEquals("$label hits", a.hits, b.hits)   // file, score, reason, snippet and locator, in order
  }

  @Test fun theFinalResultsAreTheOnesTheOldOrderGaveOnAFixedQuerySet() {
    var withMeaning = 0
    var withHits = 0
    for (q in queries) for (filter in filters) {
      val old = oldOrder(q, filter, VectorIndex(parallelism = 1), hasModel = true)
      val new = newOrder(q, filter, VectorIndex(parallelism = 1), hasModel = true).emissions
      assertEquals("emissions for \"$q\" $filter", old.size, new.size)
      old.zip(new).forEachIndexed { i, (a, b) -> same(a, b, "\"$q\" $filter step $i") }
      assertTrue("the last emission is complete", new.last().complete)
      if (new.size == 2) withMeaning++
      if (new.last().hits.isNotEmpty()) withHits++
    }
    assertTrue("the set must include queries that reach the model ($withMeaning)", withMeaning > 30)
    assertTrue("and queries that find something ($withHits)", withHits > 30)
  }

  @Test fun theWordsAnswerWeighsFromTheTextAndTheMeaningAnswerFromItsOwnVectorAfterTheEmbed() = runBlocking {
    val vectors = VectorIndex(parallelism = 1)
    val searcher = Searcher(store, vectors, kinds)
    val events = ArrayList<String>()
    var made: FloatArray? = null
    val progressive = ProgressiveSearch(
      searcher = { searcher },
      meaningPossible = { true },
      storedVectors = { store.hasVectors() },
      ensureVectors = { vectors.load(store) },
      wantsMeaning = { true },
      wordsWeight = { events += "words"; 2.0 },
      embed = { made = embed(it); events += "embed"; QueryVectors(made, null) },
      meaningWeight = { _, v -> events += "meaning with the embed's own vector: ${v === made}"; 4.0 },
    )
    val seen = progressive.run("rental contract", KindFilter.ALL, 20).toList()
    assertEquals(listOf("words", "embed", "meaning with the embed's own vector: true"), events)
    assertEquals("two answers", 2, seen.size)
    val direct = searcher.search("rental contract", embed("rental contract"), KindFilter.ALL, 20, prefixLast = true, wordWeight = 4.0)
    same(direct, seen.last(), "the meaning answer ranks with the weight the policy returned")
  }

  @Test fun withoutAModelWordsAreTheCompleteAnswerAsBefore() {
    for (q in queries) {
      val old = oldOrder(q, KindFilter.ALL, VectorIndex(parallelism = 1), hasModel = false)
      val new = newOrder(q, KindFilter.ALL, VectorIndex(parallelism = 1), hasModel = false).emissions
      assertEquals(old.size, new.size)
      old.zip(new).forEachIndexed { i, (a, b) -> same(a, b, "\"$q\" step $i") }
      assertTrue(new.all { it.complete })
    }
  }

  @Test fun theWordsAnswerIsOutBeforeTheVectorsStartLoading() {
    for (q in listOf("TRX50", "rental contract", "shoprite receipts", "sunset on the beach")) {
      val vectors = VectorIndex(parallelism = 1)
      val run = newOrder(q, KindFilter.ALL, vectors, hasModel = true)
      assertEquals("\"$q\": one answer was already out when the load began", 1, run.emissionsWhenLoadStarted)
      assertFalse("\"$q\": the first answer is words only", run.emissions.first().complete)
      assertTrue(vectors.size > 0)
    }
  }

  @Test fun theWordsAnswerNeedsNoLoadedMatrixAtAll() {
    val cold = VectorIndex(parallelism = 1)
    val searcher = Searcher(store, cold, kinds)
    assertEquals(0, cold.size)
    val r = searcher.search("TRX50", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals(listOf("board-manual.pdf"), r.hits.map { it.file.name })
    assertEquals(0, cold.size)   // searching by words did not load anything
  }

  @Test fun anEmptyQueryEmitsOnceAndNeverLoadsVectors() {
    val vectors = VectorIndex(parallelism = 1)
    val run = newOrder("   ", KindFilter.ALL, vectors, hasModel = true)
    assertEquals(1, run.emissions.size)
    assertTrue(run.emissions[0].hits.isEmpty())
    assertNull(run.emissionsWhenLoadStarted)
    assertEquals(0, vectors.size)
  }

  @Test fun anIndexWithNoVectorsIsCompleteAtOnce() {
    val empty = Files.createTempDirectory("findr-progressive-empty").toFile()
    val bare = IndexStore.open(empty, log = {})
    try {
      addTo(bare, "notes.txt", FileKind.DOCUMENT, "Download/", FileState.SEARCHABLE)
      assertFalse(bare.hasVectors())
      val vectors = VectorIndex(parallelism = 1)
      val searcher = Searcher(bare, vectors)
      val seen = runBlocking {
        ProgressiveSearch({ searcher }, { true }, { bare.hasVectors() }, { vectors.load(bare) }, { it.length >= 3 && vectors.size > 0 }, { QueryVectors(embed(it), null) })
          .run("notes", KindFilter.ALL, 20).toList()
      }
      assertEquals(1, seen.size)
      assertTrue(seen[0].complete)
      assertEquals(listOf("notes.txt"), seen[0].hits.map { it.file.name })
    } finally {
      bare.close()
      empty.deleteRecursively()
    }
  }

  @Test fun storedVectorsTheMatrixCannotUseStillEndInACompleteAnswer() {
    // The store says there are vectors, but the matrix loads none (another dimension): the first answer promised meaning.
    val searcher = Searcher(store, VectorIndex(parallelism = 1), kinds)
    val seen = runBlocking {
      ProgressiveSearch({ searcher }, { true }, { true }, { }, { false }, { QueryVectors(null, null) }).run("TRX50", KindFilter.ALL, 20).toList()
    }
    assertEquals(listOf(false, true), seen.map { it.complete })
    assertEquals(seen[0].hits, seen[1].hits)
  }

  // ---- D2 quick look: only quick vectors exist (the first photos are still waiting for EmbeddingGemma) ----

  private fun quickUnit(k: Int) = unit(FloatArray(IndexStore.QUICK_DIM) { (if (it == k) 1f else 0f) + (random.nextDouble() * 2 - 1).toFloat() * 0.03f })

  /** A fresh index in which every photo is only quick-looked: no EmbeddingGemma vector anywhere. */
  private fun withQuickOnly(block: (store: IndexStore, quick: VectorIndex, eg: VectorIndex) -> Unit) {
    val quickDir = Files.createTempDirectory("findr-progressive-quick").toFile()
    val only = IndexStore.open(quickDir, log = {})
    val quick = VectorIndex(dim = IndexStore.QUICK_DIM, parallelism = 1, holds = VectorIndex.QUICK_LOOK)
    val eg = VectorIndex(parallelism = 1)
    try {
      repeat(40) { i ->
        addTo(only, "new$i.jpg", FileKind.IMAGE, "DCIM/Camera/", FileState.NEW)
      }
      val plantedMediaId = nextId
      addTo(only, "IMG_0042.jpg", FileKind.IMAGE, "DCIM/Camera/", FileState.NEW)
      val ids = only.knownMediaStoreIds("external_primary")
      for ((mediaId, fileId) in ids) {
        val v = if (mediaId == plantedMediaId) quickUnit(9) else unit(FloatArray(IndexStore.QUICK_DIM) { (random.nextDouble() * 2 - 1).toFloat() })
        val seg = only.saveQuick(fileId, SegmentKind.IMAGE, v)
        quick.add(seg, fileId, FileKind.IMAGE, Modality.CLIP, false, v)
      }
      block(only, quick, eg)
    } finally {
      quick.close(); eg.close(); only.close()
      quickDir.deleteRecursively()
    }
  }

  @Test fun withOnlyQuickVectorsTheWordsAnswerIsNotCompleteAndTheSecondOneHasQuickLookHits() = withQuickOnly { only, quick, eg ->
    assertTrue("the store lists the quick vectors", only.hasVectors())
    val searcher = Searcher(only, eg, quick = quick)
    val goat = unit(FloatArray(IndexStore.QUICK_DIM) { if (it == 9) 1f else 0f })
    val seen = runBlocking {
      ProgressiveSearch(
        searcher = { searcher },
        meaningPossible = { true },                       // quick look is on
        storedVectors = { only.hasVectors() },
        ensureVectors = { eg.load(only); quick.load(only) },
        wantsMeaning = { it.length >= 3 && quick.size > 0 },   // no EmbeddingGemma vectors, quick look on
        embed = { QueryVectors(null, goat, "test") },
      ).run("goat", KindFilter.ALL, 20).toList()
    }
    assertEquals(0, eg.size)
    assertEquals(listOf(false, true), seen.map { it.complete })
    assertFalse("the words answer has no hit: the name says nothing", seen[0].hits.any { it.file.name == "IMG_0042.jpg" })
    assertEquals("IMG_0042.jpg", seen[1].hits.first().file.name)
    assertEquals(com.craneai.findr.core.MatchReason.MEANING, seen[1].hits.first().reason)
  }

  @Test fun withOnlyQuickVectorsAndQuickLookOffWordsAreTheCompleteAnswer() = withQuickOnly { only, quick, eg ->
    val searcher = Searcher(only, eg, quick = quick)
    val seen = runBlocking {
      ProgressiveSearch(
        searcher = { searcher },
        meaningPossible = { false },                      // no EmbeddingGemma model, quick look off
        storedVectors = { only.hasVectors() },
        ensureVectors = { eg.load(only); quick.load(only) },
        wantsMeaning = { false },
        embed = { QueryVectors(null, null) },
      ).run("goat", KindFilter.ALL, 20).toList()
    }
    assertEquals(listOf(true), seen.map { it.complete })
  }

  /** Both steps (words, then meaning) read the same policy: a marked file is out of both unless sensitive results are on. */
  private fun leaseRun(includeSensitive: Boolean): List<SearchResults> {
    val id = store.filesByIds(store.recent(kinds(), 500, includeSensitive = true).map { it.id }).values.first { it.name == "lease.pdf" }.id
    store.setMarked(id, true)
    val vectors = VectorIndex(parallelism = 1)
    try {
      val searcher = Searcher(store, vectors, kinds)
      return runBlocking {
        ProgressiveSearch(
          searcher = { searcher },
          meaningPossible = { true },
          storedVectors = { store.hasVectors() },
          ensureVectors = { vectors.load(store) },
          wantsMeaning = { it.length >= 3 && vectors.size > 0 },
          embed = { QueryVectors(embed(it), null) },
          includeSensitive = { includeSensitive },
        ).run("rental contract", KindFilter.ALL, 20).toList()
      }
    } finally {
      vectors.close()
    }
  }

  @Test fun aMarkedFileIsOutOfBothAnswersAndCounted() {
    val seen = leaseRun(includeSensitive = false)
    assertTrue(seen.isNotEmpty())
    for (r in seen) assertFalse("hidden in every emission", r.hits.any { it.file.name == "lease.pdf" })
    assertEquals(1, seen.last().hiddenCount)
  }

  @Test fun aMarkedFileComesBackWhenSensitiveResultsAreOn() {
    val seen = leaseRun(includeSensitive = true)
    assertEquals("lease.pdf", seen.last().hits.first().file.name)
    assertEquals(0, seen.last().hiddenCount)
  }

  // ---- type-to-filter: words on every keystroke, the meaning step only after a pause --------------------------------

  private fun typing(vectors: VectorIndex, embeds: MutableList<String>) = ProgressiveSearch(
    searcher = { Searcher(store, vectors, kinds) },
    meaningPossible = { true },
    storedVectors = { store.hasVectors() },
    ensureVectors = { vectors.load(store) },
    wantsMeaning = { it.length >= 3 && vectors.size > 0 },
    embed = { embeds += it; QueryVectors(embed(it), null) },
  )

  @Test fun aKeystrokeDuringThePauseCancelsTheMeaningStepBeforeAnyEmbed() = runBlocking {
    val embeds = ArrayList<String>()
    val vectors = VectorIndex(parallelism = 1)
    val seen = ArrayList<SearchResults>()
    // "rental contrac" is a word still being typed: its words answer arrives, then the next keystroke cancels the search.
    val job = launch { typing(vectors, embeds).run("rental contrac", KindFilter.ALL, 20, meaningDelayMs = 10_000).collect { seen += it } }
    withTimeout(5_000) { while (seen.isEmpty()) delay(5) }
    job.cancelAndJoin()
    assertEquals("the words answer was shown", 1, seen.size)
    assertFalse(seen.single().complete)
    assertTrue("no query embed for a half-typed word", embeds.isEmpty())
    assertEquals("the vector matrix was not even loaded", 0, vectors.size)
  }

  @Test fun afterThePauseTheAnswersAreExactlyTheUndelayedOnes() = runBlocking {
    for (q in listOf("rental contract", "electricity bill", "TRX50", "ab", "nothing matches this phrase")) {
      val plain = typing(VectorIndex(parallelism = 1), ArrayList()).run(q, KindFilter.ALL, 20).toList()
      val delayed = typing(VectorIndex(parallelism = 1), ArrayList()).run(q, KindFilter.ALL, 20, meaningDelayMs = 30).toList()
      assertEquals("emissions for \"$q\"", plain.size, delayed.size)
      plain.zip(delayed).forEachIndexed { i, (a, b) -> same(a, b, "\"$q\" step $i") }
    }
  }

  @Test fun aWordsOnlyAnswerDoesNotWaitForThePause() = runBlocking {
    // Too short for meaning: the words answer is complete, and the delay must not hold the flow open.
    val t0 = System.nanoTime()
    val out = typing(VectorIndex(parallelism = 1), ArrayList()).run("ab", KindFilter.ALL, 20, meaningDelayMs = 10_000).toList()
    assertTrue(out.first().complete)
    assertTrue("finished without the 10 s pause", (System.nanoTime() - t0) / 1_000_000 < 5_000)
  }
}
