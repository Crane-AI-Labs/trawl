package com.craneai.findr.search

import com.craneai.findr.core.CaptionTimeline
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A word read from a video frame carries the moment of that frame: a caption search shows "at m:ss" (and so the strip and open-at-moment
 * work for it), where the OCR row of a video used to have no time at all.
 */
class VideoCaptionMomentTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(7)
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-caption-moment").toFile()
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

  private fun addFile(name: String, kind: FileKind, segments: List<Segment>): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/Camera/", name, kind, null, 50_000, 1_000)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    val ids = store.saveSegments(fileId, segments, FileState.UNDERSTOOD)
    segments.forEachIndexed { i, s -> s.vector?.let { vectors.add(ids[i], fileId, kind, s.modality, s.late, it) } }
    return fileId
  }

  /** The OCR piece a video makes from its frames' lines: what the video loop stores. */
  private fun captionPiece(lines: List<Pair<String, Long>>, timed: Boolean = true): Segment {
    val t = CaptionTimeline()
    lines.forEach { (line, at) -> t.add(line, at) }
    return if (timed) Piece(PieceKind.OCR, PieceModality.TEXT, t.startMs, t.endMs, t.text(), marks = t.marks()).toSegment(false)
    else Piece(PieceKind.OCR, PieceModality.TEXT, text = t.text()).toSegment(false)
  }

  private fun locatorOf(query: String, file: String? = null): Locator? {
    val hits = searcher.search(query, null, KindFilter.ALL, 10).hits
    return (if (file == null) hits.single() else hits.single { it.file.name == file }).locator
  }

  @Test fun aCaptionHitShowsTheMomentOfTheFrameItWasReadIn() {
    val lines = listOf("GRAND OPENING" to 5_000L, "Kampala Road branch" to 5_000L, "SALE ENDS FRIDAY" to 41_000L, "grand opening" to 70_000L)
    addFile("promo.mp4", FileKind.VIDEO, listOf(captionPiece(lines)))
    assertEquals(5_000L, (locatorOf("grand opening") as Locator.Time).startMs)
    assertEquals(5_000L, (locatorOf("kampala") as Locator.Time).startMs)
    val sale = locatorOf("friday") as Locator.Time
    assertEquals(41_000L, sale.startMs)
    assertEquals(70_000L, sale.endMs)   // the piece spans the first to the last line read
  }

  @Test fun theBadgeMomentFollowsTheMatchedLineNotThePieceStart() {
    val lines = listOf("harbour sunrise" to 10_000L, "lantern festival tonight" to 20_000L, "giraffe park entrance" to 30_000L, "zebra crossing ahead" to 95_000L)
    addFile("signs.mp4", FileKind.VIDEO, listOf(captionPiece(lines)))
    assertEquals(95_000L, (locatorOf("zebra") as Locator.Time).startMs)
    assertEquals(30_000L, (locatorOf("giraffe") as Locator.Time).startMs)
    assertEquals(20_000L, (locatorOf("lantern") as Locator.Time).startMs)
  }

  @Test fun aRowIndexedBeforeShowsNoMomentAsItNeverDid() {
    addFile("old.mp4", FileKind.VIDEO, listOf(captionPiece(listOf("GRAND OPENING" to 5_000L), timed = false)))
    val hit = searcher.search("grand opening", null, KindFilter.ALL, 10).hits.single()
    assertNull(hit.locator)
    assertNotNull(hit.snippet)
  }

  @Test fun theTextReadFromAPhotoOrAScreenshotStillHasNoMoment() {
    addFile("receipt.png", FileKind.SCREENSHOT, listOf(Piece(PieceKind.OCR, PieceModality.TEXT, text = "Total UGX 14,500 paid with cash").toSegment(false)))
    assertNull(locatorOf("total paid"))
  }

  /** The lite profile's first OCR piece holds the words and their times; a hit by meaning alone points at the first line. */
  @Test fun aMeaningHitOnTheTimedPieceStartsAtItsFirstLine() {
    repeat(120) { addFile("doc$it.txt", FileKind.DOCUMENT, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "background filler $it", vector = background()))) }
    val t = CaptionTimeline()
    t.add("welcome to the harbour", 12_000); t.add("ferry timetable", 30_000)
    val piece = Segment(SegmentKind.OCR, Modality.OCR_TEXT, locStart = t.startMs, locEnd = t.endMs, text = t.text(), vector = about(3), marks = t.marks())
    addFile("ferry.mp4", FileKind.VIDEO, listOf(piece))
    val hit = searcher.search("when do boats leave", topic(3), KindFilter.ALL, 10).hits.first { it.file.name == "ferry.mp4" }
    assertEquals(MatchReason.MEANING, hit.reason)
    assertEquals(12_000L, (hit.locator as Locator.Time).startMs)
  }

  /**
   * A meaning hit on the soundtrack's "Sounds:" line (which stands for no moment) used to hide the moment the typed words are on screen.
   * The moment is display only: the same search over the same file with no times stored must give the same hit in every other way.
   */
  @Test fun aSoundLabelMeaningHitStillShowsWhenTheTypedWordsAreOnScreen() {
    repeat(120) { addFile("clip$it.m4a", FileKind.AUDIO, listOf(Segment(SegmentKind.SOUND, Modality.SOUND, text = "Sounds: filler $it", vector = background()))) }
    val sounds = about(9)   // one vector for both files: the two scores can then differ only by what this test is about
    fun video(name: String, timed: Boolean) = addFile(
      name, FileKind.VIDEO,
      listOf(
        captionPiece(listOf("opening hours" to 8_000L, "harbour market" to 33_000L), timed),
        Segment(SegmentKind.SOUND, Modality.SOUND, text = "Sounds: Dog, Bark", vector = sounds),
      ),
    )
    video("with-times.mp4", timed = true)
    video("no-times.mp4", timed = false)
    val hits = searcher.search("harbour market", topic(9), KindFilter.ALL, 10).hits
    val withTimes = hits.single { it.file.name == "with-times.mp4" }
    val noTimes = hits.single { it.file.name == "no-times.mp4" }
    assertEquals("the sound label must be what the hit points at, or this proves nothing", MatchReason.BOTH, withTimes.reason)
    assertEquals(33_000L, (withTimes.locator as Locator.Time).startMs)
    assertNull(noTimes.locator)
    // nothing but the moment differs: score, reason and snippet are the same, so the order cannot move
    assertEquals(noTimes.score, withTimes.score, 1e-12)
    assertEquals(noTimes.reason, withTimes.reason)
    assertEquals(noTimes.snippet, withTimes.snippet)
    assertEquals(setOf("no-times.mp4", "with-times.mp4"), hits.map { it.file.name }.toSet())
  }
}
