package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
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
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Search with the quick-look modality (D2): photos EmbeddingGemma hasn't understood carry a 512-d CLIP vector in their own
 * index, scored against the query's CLIP vector. Synthetic vectors as in SearcherTest: a topic is an axis.
 */
class QuickLookSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var quick: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(7)
  private var nextMediaId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-quick-search").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    quick = VectorIndex(dim = IndexStore.QUICK_DIM, parallelism = 1, holds = VectorIndex.QUICK_LOOK)
    searcher = Searcher(store, vectors, quick = quick)
  }

  @After fun tearDown() {
    vectors.close(); quick.close(); store.close()
    dir.deleteRecursively()
  }

  private fun unit(raw: FloatArray): FloatArray {
    val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(raw.size) { raw[it] / n }
  }
  private fun background(dim: Int) = unit(FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() })
  private fun topic(k: Int, dim: Int) = FloatArray(dim) { if (it == k) 1f else 0f }
  private fun about(k: Int, dim: Int) = unit(FloatArray(dim) { (if (it == k) 1f else 0f) + (random.nextDouble() * 2 - 1).toFloat() * 0.03f })

  private fun file(name: String, kind: FileKind): Long {
    val id = nextMediaId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, 50_000, 1_000)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  /** A file EmbeddingGemma understood, with one vector. */
  private fun understood(name: String, kind: FileKind, v: FloatArray, modality: Modality, segment: SegmentKind, text: String? = null): Long {
    val f = file(name, kind)
    val ids = store.saveSegments(f, listOf(Segment(segment, modality, text = text, vector = v)), FileState.UNDERSTOOD)
    vectors.add(ids[0], f, kind, modality, false, v)
    return f
  }

  /** A photo only the quick-look pass has seen. */
  private fun quickOnly(name: String, v: FloatArray, kind: FileKind = FileKind.IMAGE): Long {
    val f = file(name, kind)
    val id = store.saveQuick(f, SegmentKind.IMAGE, v)
    quick.add(id, f, kind, Modality.CLIP, false, v)
    return f
  }

  /** Documents and photos EmbeddingGemma understood, and 60 photos only quick look has seen: a realistic half-done index. */
  private fun world() {
    repeat(200) { understood("doc$it.txt", FileKind.DOCUMENT, background(Fp16.DIM), Modality.TEXT, SegmentKind.CHUNK, "filler text number $it") }
    repeat(60) { understood("pic$it.jpg", FileKind.IMAGE, background(Fp16.DIM), Modality.IMAGE, SegmentKind.IMAGE) }
    repeat(60) { quickOnly("new$it.jpg", background(IndexStore.QUICK_DIM)) }
  }

  @Test fun aPhotoOnlyQuickLookHasSeenIsFoundByMeaning() {
    world()
    quickOnly("IMG_0042.jpg", about(9, IndexStore.QUICK_DIM))
    val r = searcher.search("goat in the market", topic(30, Fp16.DIM), KindFilter.ALL, 20, quickVector = topic(9, IndexStore.QUICK_DIM))
    assertEquals("IMG_0042.jpg", r.hits.first().file.name)
    assertEquals(MatchReason.MEANING, r.hits.first().reason)
  }

  @Test fun withoutAQuickQueryVectorQuickRowsSitOut() {
    world()
    quickOnly("IMG_0042.jpg", about(9, IndexStore.QUICK_DIM))
    val r = searcher.search("embuzi mu katale", topic(30, Fp16.DIM), KindFilter.ALL, 20, quickVector = null)   // not English: no quick vector
    assertFalse(r.hits.any { it.file.name == "IMG_0042.jpg" })
  }

  @Test fun quickRowsLeaveEmbeddingGemmasScoresExactlyAsTheyWere() {
    world()
    understood("lease.pdf", FileKind.PDF, about(7, Fp16.DIM), Modality.TEXT, SegmentKind.CHUNK, "tenancy agreement")
    val query = topic(7, Fp16.DIM)
    val without = Searcher(store, vectors).search("rental contract", query, KindFilter.ALL, 20)
    val with = searcher.search("rental contract", query, KindFilter.ALL, 20, quickVector = topic(3, IndexStore.QUICK_DIM))
    assertEquals("lease.pdf", with.hits.first().file.name)
    assertEquals(without.hits.first().score, with.hits.first().score, 0.0)   // bit-identical z for EmbeddingGemma's rows
    val withoutQuickLane = searcher.search("rental contract", query, KindFilter.ALL, 20, quickVector = null)
    assertEquals(without.hits.map { it.file.id to it.score }, withoutQuickLane.hits.map { it.file.id to it.score })
  }

  @Test fun embeddingGemmasVectorsSupersedeALingeringQuickRow() {
    world()
    val f = understood("IMG_0042.jpg", FileKind.IMAGE, background(Fp16.DIM), Modality.IMAGE, SegmentKind.IMAGE)
    quick.add(999_999, f, FileKind.IMAGE, Modality.CLIP, false, about(9, IndexStore.QUICK_DIM))   // as if a drop were missed
    val r = searcher.search("goat", topic(30, Fp16.DIM), KindFilter.ALL, 20, quickVector = topic(9, IndexStore.QUICK_DIM))
    assertFalse(r.hits.any { it.file.name == "IMG_0042.jpg" })
  }

  @Test fun quickLookAloneAnswersBeforeEmbeddingGemmaHasUnderstoodAnything() {
    repeat(60) { quickOnly("new$it.jpg", background(IndexStore.QUICK_DIM)) }
    quickOnly("IMG_0042.jpg", about(9, IndexStore.QUICK_DIM))
    val r = searcher.search("goat", null, KindFilter.ALL, 20, quickVector = topic(9, IndexStore.QUICK_DIM))
    assertEquals(listOf("IMG_0042.jpg"), r.hits.map { it.file.name })
  }

  @Test fun theKindFilterAppliesToQuickRows() {
    world()
    quickOnly("IMG_0042.jpg", about(9, IndexStore.QUICK_DIM))
    val docs = searcher.search("goat", topic(30, Fp16.DIM), KindFilter.DOCUMENTS, 20, quickVector = topic(9, IndexStore.QUICK_DIM))
    assertTrue(docs.hits.none { it.file.name == "IMG_0042.jpg" })
  }

  @Test fun theQuickIndexHoldsOnlyQuickVectorsAndTheMainIndexNone() {
    world()
    val main = VectorIndex(parallelism = 1).apply { load(store) }
    val q = VectorIndex(dim = IndexStore.QUICK_DIM, parallelism = 1, holds = VectorIndex.QUICK_LOOK).apply { load(store) }
    assertEquals(260, main.size)
    assertEquals(60, q.size)
    main.close(); q.close()
  }
}
