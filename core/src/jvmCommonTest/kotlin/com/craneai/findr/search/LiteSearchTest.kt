package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
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

/**
 * Search in the lite profile (2 GB phones): a picture is understood with a quick-look vector (CLIP, its own index) and, when it
 * has text, EmbeddingGemma's text vector of that text (OCR_TEXT, its own modality group). Both kinds of row must count.
 */
class LiteSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var quick: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(11)
  private var nextMediaId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-lite-search").toFile()
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

  /** Saves a file's segments as the lite worker does, and loads them into both indexes as the engine does after a save. */
  private fun save(name: String, kind: FileKind, segments: List<Segment>): Long {
    val f = file(name, kind)
    store.saveSegments(f, segments, FileState.UNDERSTOOD)
    vectors.loadFile(store, f)
    quick.loadFile(store, f)
    return f
  }

  /** A lite library: documents, photos with only a quick vector, screenshots with a quick vector and their text's vector. */
  private fun world() {
    repeat(200) { save("doc$it.txt", FileKind.DOCUMENT, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "filler $it", vector = background(Fp16.DIM)))) }
    repeat(60) { save("pic$it.jpg", FileKind.IMAGE, listOf(Segment(SegmentKind.IMAGE, Modality.CLIP, vector = background(IndexStore.QUICK_DIM)))) }
    repeat(40) {
      save("shot$it.png", FileKind.SCREENSHOT, listOf(
        Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "message number $it", vector = background(Fp16.DIM)),
        Segment(SegmentKind.IMAGE, Modality.CLIP, vector = background(IndexStore.QUICK_DIM)),
      ))
    }
  }

  @Test fun aScreenshotsTextVectorAndItsPictureVectorLiveInTheirOwnIndexes() {
    world()
    assertEquals(200 + 40, vectors.size)   // documents and the screenshots' text
    assertEquals(60 + 40, quick.size)      // every picture's quick-look vector
  }

  @Test fun aPictureWithTextIsFoundByItsPictureVectorToo() {
    world()
    // The text says nothing about the query; the picture does. Before the lite profile, any EmbeddingGemma row of a file hid its
    // quick row, so this screenshot could not be found by what it shows.
    save("IMG_shot_goat.png", FileKind.SCREENSHOT, listOf(
      Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "unrelated words", vector = background(Fp16.DIM)),
      Segment(SegmentKind.IMAGE, Modality.CLIP, vector = about(9, IndexStore.QUICK_DIM)),
    ))
    val r = searcher.search("goat in the market", topic(30, Fp16.DIM), KindFilter.ALL, 20, quickVector = topic(9, IndexStore.QUICK_DIM))
    assertEquals("IMG_shot_goat.png", r.hits.first().file.name)
  }

  @Test fun aScreenshotIsFoundByTheMeaningOfItsText() {
    world()
    save("Screenshot_mtn.png", FileKind.SCREENSHOT, listOf(
      Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "You have received UGX 50,000", vector = about(12, Fp16.DIM)),
      Segment(SegmentKind.IMAGE, Modality.CLIP, vector = background(IndexStore.QUICK_DIM)),
    ))
    // A non-English query: no quick-look vector at all, and the text's meaning still finds it.
    val r = searcher.search("ssente ze nfunye", topic(12, Fp16.DIM), KindFilter.ALL, 20, quickVector = null)
    assertEquals("Screenshot_mtn.png", r.hits.first().file.name)
    assertTrue(r.hits.first().snippet?.contains("UGX") == true)   // the hit shows the text it was found by
  }

  @Test fun theTextOfPicturesDoesNotMoveDocumentsScores() {
    world()
    save("lease.pdf", FileKind.PDF, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "tenancy agreement", vector = about(7, Fp16.DIM))))
    val query = topic(7, Fp16.DIM)
    val before = searcher.search("rental contract", query, KindFilter.ALL, 20, quickVector = null).hits.first { it.file.name == "lease.pdf" }.score
    // Many more pictures with text: their own modality group, so the documents' centring doesn't move. (The shared deviation may.)
    repeat(100) {
      save("more$it.png", FileKind.SCREENSHOT, listOf(Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "x $it", vector = about(40 + it % 5, Fp16.DIM))))
    }
    val after = searcher.search("rental contract", query, KindFilter.ALL, 20, quickVector = null)
    assertEquals("lease.pdf", after.hits.first().file.name)
    assertTrue(after.hits.first().score >= before * 0.8)
  }
}
