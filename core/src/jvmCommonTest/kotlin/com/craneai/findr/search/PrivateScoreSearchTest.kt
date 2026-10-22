package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.index.FileState
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
 * The "looks private" score may blur a thumbnail and nothing else: with every picture scored as private, a search returns the
 * same files, in the same order, with the same scores and reasons, as with no score at all.
 */
class PrivateScoreSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(7)
  private var nextMediaId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-private-search").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun tearDown() {
    vectors.close()
    store.close()
    dir.deleteRecursively()
  }

  private fun unit(raw: FloatArray): FloatArray {
    val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(raw.size) { raw[it] / n }
  }

  private fun noise() = FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() }
  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }
  private fun about(k: Int) = unit(FloatArray(Fp16.DIM) { (if (it == k) 1f else 0f) + noise()[it] * 0.03f })

  private fun addPicture(name: String, vector: FloatArray, ocr: String? = null): Long {
    val id = nextMediaId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/Camera/", name, FileKind.IMAGE, null, 50_000, 1_000)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    val segments = buildList {
      add(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vector))
      if (ocr != null) add(Segment(SegmentKind.OCR, text = ocr))
    }
    val ids = store.saveSegments(fileId, segments, FileState.UNDERSTOOD)
    vectors.add(ids[0], fileId, FileKind.IMAGE, Modality.IMAGE, false, vector)
    return fileId
  }

  @Test fun scoringEveryPictureAsPrivateChangesNeitherTheResultsNorTheirOrder() {
    repeat(120) { addPicture("bg$it.jpg", unit(noise())) }
    val planted = (1..6).map { addPicture("beach$it.jpg", about(9), ocr = if (it % 2 == 0) "beach holiday $it" else null) }
    val all = (1L..nextMediaId).toList()

    fun run() = searcher.search("beach holiday", topic(9), KindFilter.ALL, 20)
    val before = run()
    assertTrue("the planted pictures should be found", before.hits.size >= planted.size)

    all.forEach { store.saveLooksPrivate(it, 0.95f) }
    val after = run()

    assertEquals(before.hits.map { it.file.id }, after.hits.map { it.file.id })
    assertEquals(before.hits.map { it.score }, after.hits.map { it.score })
    assertEquals(before.hits.map { it.reason }, after.hits.map { it.reason })
    assertEquals(before.strongCount, after.strongCount)
    // The score does arrive with the hit, so the thumbnail code can blur it.
    assertTrue(after.hits.all { it.file.looksPrivate == 0.95f })
    assertTrue(before.hits.all { it.file.looksPrivate == null })
  }
}
