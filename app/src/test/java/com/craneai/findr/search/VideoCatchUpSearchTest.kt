package com.craneai.findr.search

import com.craneai.findr.core.CaptionTimeline
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.engine.Piece
import com.craneai.findr.engine.PieceKind
import com.craneai.findr.engine.PieceModality
import com.craneai.findr.engine.toSegment
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.index.VideoCatchUp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * An old video caught up (VideoCatchUp), seen through the real Searcher: its new words are found with the moment they are on screen,
 * its picture match moves to the keyframe the picture came from, and every search that does not touch its new words returns the
 * same files with the same scores as before. The vector index is not told anything: no vector changed.
 */
class VideoCatchUpSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(11)
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-catchup-search").toFile()
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
  private fun background() = unit(FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() })
  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }
  private fun about(k: Int) = unit(FloatArray(Fp16.DIM) { (if (it == k) 1f else 0f) + (random.nextDouble() * 2 - 1).toFloat() * 0.03f })

  private fun addFile(name: String, kind: FileKind, segments: List<Segment>, durationMs: Long? = null): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/Camera/", name, kind, null, 50_000, 1_000, durationMs = durationMs)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    val ids = store.saveSegments(fileId, segments, FileState.UNDERSTOOD)
    segments.forEachIndexed { i, s -> s.vector?.let { vectors.add(ids[i], fileId, kind, s.modality, s.late, it) } }
    return fileId
  }

  private fun results(query: String, vector: FloatArray?) =
    searcher.search(query, vector, KindFilter.ALL, 20).hits.map { it.file.name to it.score }

  private fun hitOf(query: String, vector: FloatArray?, name: String) = searcher.search(query, vector, KindFilter.ALL, 20).hits.firstOrNull { it.file.name == name }

  @Test fun anOldVideoGainsItsNewWordsAndTimesAndNothingElseMoves() {
    repeat(60) { addFile("note$it.txt", FileKind.DOCUMENT, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, "background filler page $it", background()))) }
    repeat(30) { addFile("pic$it.jpg", FileKind.IMAGE, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = background()))) }
    addFile("beach.jpg", FileKind.IMAGE, listOf(Segment(SegmentKind.OCR, text = "STOP"), Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = about(5))))
    addFile("harbour.txt", FileKind.DOCUMENT, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, "ferry timetable at the harbour", about(7))))
    // As a build before 1.0.4 left it: no keyframe times, words without times, and no short words.
    val video = addFile("promo.mp4", FileKind.VIDEO, listOf(
      Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 15_000, vector = about(20)),
      Segment(SegmentKind.FRAME, Modality.IMAGE, 15_000, 20_000, vector = about(21)),
      Segment(SegmentKind.FRAME, Modality.IMAGE, 20_000, 45_000, vector = about(22)),
      Segment(SegmentKind.OCR, text = "WELCOME TO KAMPALA\nGRAND OPENING"),
    ), durationMs = 45_000)
    // Searches that do not touch the video's words. A word search's scores can move in the last digits (the keyword index's average row
    // length takes in the new words, as it does for any file added); its order does not. A search by meaning alone keeps every score.
    val untouched = listOf("ferry timetable" to topic(7), "beach" to topic(5), "filler page 12" to null, "background" to null)
    val byMeaning = listOf(topic(7), topic(5), topic(30))
    val before = untouched.map { (q, v) -> results(q, v).map { it.first } }
    val meaningBefore = byMeaning.map { results("", it) }
    assertNull("no short word before", hitOf("harley", null, "promo.mp4"))
    assertEquals(25_000L, (hitOf("", topic(22), "promo.mp4")?.locator as Locator.Time).startMs)   // the slot's middle, capped
    assertNull("old words have no time", hitOf("grand opening", null, "promo.mp4")?.locator)

    // The catch-up: the frame loop met the slots' keyframes at 0, 16 and 31 s and read "Harley" on the second picture.
    val captions = CaptionTimeline().apply { add("WELCOME TO KAMPALA", 0); add("GRAND OPENING", 0); add("Harley", 16_000) }
    val words = Piece(PieceKind.OCR, PieceModality.TEXT, captions.startMs, captions.endMs, captions.text(), marks = captions.marks()).toSegment(false)
    val old = store.videoRows(video)
    val marks = VideoCatchUp.retime(old.frames, mapOf(0L to 0L, 5_000L to 0L, 10_000L to 16_000L, 15_000L to 16_000L, 20_000L to 31_000L))
    assertTrue(store.saveVideoCatchUp(video, marks, listOf(words), VideoCatchUp.keepOld(old.ocrLines, captions.lines())))

    assertEquals(16_000L, (hitOf("harley", null, "promo.mp4")?.locator as Locator.Time).startMs)
    assertEquals(31_000L, (hitOf("", topic(22), "promo.mp4")?.locator as Locator.Time).startMs)
    assertEquals(0L, (hitOf("grand opening", null, "promo.mp4")?.locator as Locator.Time).startMs)
    assertEquals("searches that do not touch the new words: same files in the same order", before, untouched.map { (q, v) -> results(q, v).map { it.first } })
    assertEquals("searches by meaning: same files, same scores", meaningBefore, byMeaning.map { results("", it) })
  }
}
