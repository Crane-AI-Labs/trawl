package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FrameMoment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The catch-up of videos an earlier build read (VideoCatchUp): which videos it takes, what it writes, and that it writes nothing else.
 * The frame loop itself (sampler, OCR) needs a phone; it is the same code a fresh read runs, so its output is what a fresh read stores.
 */
class VideoCatchUpTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-catchup").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind, mtime: Long = 1_000, size: Long = 50_000, durationMs: Long? = null): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, size, mtime, durationMs = durationMs)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  private fun vec(seed: Int) = FloatArray(Fp16.DIM) { if (it == seed % Fp16.DIM) 1f else 0f }

  /** A video as a build before 1.0.4 left it: picture rows with no keyframe time, and words with no times (the long rule's only). */
  private fun oldVideo(name: String = "clip.mp4", words: String? = "WELCOME TO KAMPALA\nGRAND OPENING", info: String? = null): Long {
    val id = add(name, FileKind.VIDEO, durationMs = 45_000)
    val segs = listOf(
      Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 15_000, vector = vec(1)),        // a look-alike slot grown to 15 s
      Segment(SegmentKind.FRAME, Modality.IMAGE, 15_000, 20_000, vector = vec(2)),
      Segment(SegmentKind.FRAME, Modality.IMAGE, 20_000, 45_000, vector = vec(3)),
      Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "hello and welcome everyone", vec(4), marks = "0:0:9000"),
    ) + listOfNotNull(words?.let { Segment(SegmentKind.OCR, text = it) })
    store.saveSegments(id, segs, FileState.UNDERSTOOD, "9 frames, 3 distinct (sample 1.0 s embed 9.0 s ocr 0.5 s)", info = info)
    return id
  }

  private fun photo(name: String = "beach.jpg"): Long {
    val id = add(name, FileKind.IMAGE)
    store.saveSegments(id, listOf(Segment(SegmentKind.OCR, text = "STOP"), Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(9))), FileState.UNDERSTOOD)
    return id
  }

  private fun doc(name: String = "notes.txt"): Long {
    val id = add(name, FileKind.DOCUMENT)
    store.saveSegments(id, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, "welcome to the harbour", vec(11))), FileState.UNDERSTOOD)
    return id
  }

  /** Every stored byte of a file: its row, each segment (vector blob included) and each keyword row, hashed. */
  private fun rowsHash(fileId: Long): String = store.read { db ->
    val sb = StringBuilder()
    db.query("SELECT id, uri, rel_path, name, kind, mime, size, mtime, duration_ms, state, detail, fingerprint, priority, info, indexed_at, sensitive, date_ms FROM files WHERE id = ?", fileId) {
      for (c in 0 until 17) sb.append(it.textOrNull(c)).append('|')
    }
    db.query("SELECT s.id, s.kind, s.modality, s.loc_start, s.loc_end, s.late, hex(s.vec), s.marks, x.name, x.body FROM segments s LEFT JOIN fts x ON x.rowid = s.id WHERE s.file = ? ORDER BY s.id", fileId) {
      for (c in 0 until 10) sb.append(it.textOrNull(c)).append('|')
      sb.append('\n')
    }
    MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray()).joinToString("") { "%02x".format(it) }
  }

  private fun segments(fileId: Long, kind: SegmentKind): List<Triple<Long?, String?, String?>> = store.read { db ->
    val out = ArrayList<Triple<Long?, String?, String?>>()
    db.query("SELECT s.loc_start, s.marks, x.body FROM segments s LEFT JOIN fts x ON x.rowid = s.id WHERE s.file = ? AND s.kind = ? ORDER BY s.id", fileId, kind.db) {
      out += Triple(it.longOrNull(0), it.textOrNull(1), it.textOrNull(2))
    }
    out
  }

  private fun info(fileId: Long) = store.read { it.text("SELECT info FROM files WHERE id = ?", fileId) }
  private fun queue() = store.nextOldVideos(10).map { it.file.id }

  // ---- the plain rules -------------------------------------------------------------------------------------------------------

  @Test fun picturesWithoutATimeGetTheKeyframeTheirSlotWasSampledAt() {
    val rows = listOf(
      VideoCatchUp.FrameRow(1, 0, 15_000, null),
      VideoCatchUp.FrameRow(2, 15_000, 20_000, "@16667"),   // 1.0.4 or later: right already
      VideoCatchUp.FrameRow(3, 20_000, 45_000, null),
      VideoCatchUp.FrameRow(4, 7_500, null, null),          // a quick-look row: the moment it asked for, not the sampler's
      VideoCatchUp.FrameRow(5, 30_000, 35_000, null),       // a slot the sampler did not meet again
    )
    val sampled = mapOf(0L to 0L, 5_000L to 8_333L, 15_000L to 16_667L, 20_000L to 25_000L, 7_500L to 8_000L)
    assertEquals(mapOf(1L to "@0", 3L to "@25000"), VideoCatchUp.retime(rows, sampled))
    assertEquals(listOf(1L, 3L, 5L), VideoCatchUp.untimed(rows).map { it.segmentId })
    assertEquals(25_000L, FrameMoment.at(20_000, 45_000, "@25000"))
  }

  @Test fun theOldWordsStayOnlyWhenTheNewReadingLacksOneOfTheirLines() {
    assertFalse(VideoCatchUp.keepOld(listOf("GRAND OPENING"), listOf("GRAND OPENING", "STOP")))
    assertFalse(VideoCatchUp.keepOld(emptyList(), listOf("Harley")))
    assertFalse(VideoCatchUp.keepOld(listOf(" GRAND OPENING ", ""), listOf("GRAND OPENING")))
    assertTrue(VideoCatchUp.keepOld(listOf("GRAND OPENING", "Kampala Road"), listOf("GRAND OPENING")))
    assertTrue("the pictures could not be opened this time", VideoCatchUp.keepOld(listOf("GRAND OPENING"), emptyList()))
  }

  // ---- the queue -------------------------------------------------------------------------------------------------------------

  @Test fun theQueueIsTheUnderstoodVideosAnEarlierBuildRead() {
    val old = oldVideo("old.mp4")
    val today = oldVideo("today.mp4", info = VideoCatchUp.STAMP)     // read by this build: IndexWorker stamps a video's first step
    val waiting = add("new.mp4", FileKind.VIDEO)                       // not read yet: today's read will stamp it
    val partial = add("long.mp4", FileKind.VIDEO, durationMs = 600_000)
    store.saveSegments(partial, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 6_667, vector = vec(5))), FileState.PARTIAL, "next=1")
    val failed = add("broken.mp4", FileKind.VIDEO)
    store.setState(failed, FileState.FAILED, IndexStore.CANT_OPEN)
    photo(); doc()
    assertEquals(listOf(old), queue())
    assertEquals(1 to 45_000L, store.oldVideosLeft())
    assertFalse(today == waiting)
  }

  @Test fun aLongVideoBegunByAnEarlierBuildIsCaughtUpOnceItIsFinished() {
    val long = add("service.mp4", FileKind.VIDEO, durationMs = 300_000)
    store.saveSegments(long, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 6_667, vector = vec(5))), FileState.PARTIAL, "next=1")   // window 0, old build
    // this build reads windows 1.. : later steps append and pass no stamp (IndexWorker stamps only the first step)
    store.saveSegments(long, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 60_000, 66_667, vector = vec(6), late = true)), FileState.UNDERSTOOD, "done", replace = false)
    assertEquals(listOf(long), queue())
  }

  @Test fun aChangedFileOrAModelChangeReadsTheVideoAgainAndThatReadIsTodays() {
    val id = oldVideo("clip.mp4")
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/external/file/1", "DCIM/", "clip.mp4", FileKind.VIDEO, null, 60_000, 2_000, durationMs = 45_000)))
    assertEquals("a changed video waits for a fresh read, not a catch-up", emptyList<Long>(), queue())
    store.saveSegments(id, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(1), marks = "@2500")), FileState.UNDERSTOOD, info = VideoCatchUp.STAMP)
    assertEquals(emptyList<Long>(), queue())
  }

  // ---- what a catch-up writes --------------------------------------------------------------------------------------------------

  @Test fun aCatchUpTimesThePicturesReplacesTheWordsAndTouchesNothingElse() {
    val video = oldVideo()
    val p = photo(); val d = doc()
    val other = oldVideo("other.mp4")
    val before = listOf(p, d, other).associateWith(::rowsHash)
    val frameVecs = store.read { db -> val out = ArrayList<String>(); db.query("SELECT hex(vec) FROM segments WHERE file = ? AND kind = 'frame' ORDER BY id", video) { out += it.getText(0) }; out }
    val speech = segments(video, SegmentKind.TRANSCRIPT)
    val gen = store.vectorGeneration()
    val state = store.stateOf(video)
    val rows = store.videoRows(video)
    assertEquals(listOf("WELCOME TO KAMPALA", "GRAND OPENING"), rows.ocrLines)
    assertFalse(rows.ocrVectors)

    val marks = VideoCatchUp.retime(rows.frames, mapOf(0L to 0L, 5_000L to 8_333L, 10_000L to 8_333L, 15_000L to 16_667L, 20_000L to 25_000L))
    val words = Segment(SegmentKind.OCR, locStart = 0, locEnd = 25_000, text = "WELCOME TO KAMPALA\nGRAND OPENING\nSTOP", marks = "0:0:0,19:0:0,33:25000:25000")
    assertTrue(store.saveVideoCatchUp(video, marks, listOf(words), keepOld = VideoCatchUp.keepOld(rows.ocrLines, words.text!!.lines())))

    assertEquals(listOf("@0", "@16667", "@25000"), segments(video, SegmentKind.FRAME).map { it.second })
    assertEquals("picture vectors untouched", frameVecs, store.read { db -> val out = ArrayList<String>(); db.query("SELECT hex(vec) FROM segments WHERE file = ? AND kind = 'frame' ORDER BY id", video) { out += it.getText(0) }; out })
    assertEquals("speech untouched", speech, segments(video, SegmentKind.TRANSCRIPT))
    assertEquals(listOf(Triple(0L, "0:0:0,19:0:0,33:25000:25000", "WELCOME TO KAMPALA\nGRAND OPENING\nSTOP")), segments(video, SegmentKind.OCR))
    assertEquals("state and detail untouched", state, store.stateOf(video))
    assertEquals(VideoCatchUp.STAMP, info(video))
    assertEquals("no vector changed, so the vector file stays valid", gen, store.vectorGeneration())
    assertEquals("every other file, byte for byte", before, listOf(p, d, other).associateWith(::rowsHash))
    assertEquals(listOf(other), queue())
  }

  @Test fun aCatchUpIsDoneOnceAndAFileThatMovedOnIsLeftAlone() {
    val video = oldVideo()
    val words = listOf(Segment(SegmentKind.OCR, locStart = 0, locEnd = 0, text = "WELCOME TO KAMPALA\nGRAND OPENING", marks = "0:0:0,19:0:0"))
    assertTrue(store.saveVideoCatchUp(video, emptyMap(), words, keepOld = false))
    val after = rowsHash(video)
    assertFalse("stamped: a second save writes nothing", store.saveVideoCatchUp(video, emptyMap(), listOf(Segment(SegmentKind.OCR, text = "other")), keepOld = false))
    assertEquals(after, rowsHash(video))

    val changed = oldVideo("changed.mp4")
    store.setState(changed, FileState.SEARCHABLE)   // changed, switched off or queued again after it was picked
    val held = rowsHash(changed)
    assertFalse(store.saveVideoCatchUp(changed, mapOf(1L to "@0"), words, keepOld = false))
    assertEquals(held, rowsHash(changed))
  }

  @Test fun whenTheNewReadingLacksAnOldLineTheOldWordsStayBesideIt() {
    val video = oldVideo(words = "WELCOME TO KAMPALA\nGRAND OPENING")
    val rows = store.videoRows(video)
    val new = listOf(Segment(SegmentKind.OCR, locStart = 0, locEnd = 0, text = "GRAND OPENING\nSTOP", marks = "0:0:0,14:0:0"))
    val keep = VideoCatchUp.keepOld(rows.ocrLines, new.single().text!!.lines())
    assertTrue(keep)
    assertTrue(store.saveVideoCatchUp(video, emptyMap(), new, keepOld = keep))
    assertEquals(listOf("WELCOME TO KAMPALA\nGRAND OPENING", "GRAND OPENING\nSTOP"), segments(video, SegmentKind.OCR).map { it.third })
  }

  @Test fun aVideoWithNoWordsBeforeGainsThemAndOneWithNoneNowStaysWithout() {
    val shortWords = oldVideo("title.mp4", words = null)
    assertTrue(store.saveVideoCatchUp(shortWords, emptyMap(), listOf(Segment(SegmentKind.OCR, locStart = 3_000, locEnd = 3_000, text = "Harley", marks = "0:3000:3000")), keepOld = false))
    assertEquals(listOf("Harley"), segments(shortWords, SegmentKind.OCR).map { it.third })
    val camera = oldVideo("choir.mp4", words = null)
    val held = segments(camera, SegmentKind.FRAME)
    assertTrue(store.saveVideoCatchUp(camera, emptyMap(), emptyList(), keepOld = false))
    assertEquals(emptyList<Triple<Long?, String?, String?>>(), segments(camera, SegmentKind.OCR))
    assertEquals(held, segments(camera, SegmentKind.FRAME))
    assertEquals(emptyList<Long>(), queue())
  }

  @Test fun theLiteProfilesWordVectorsAreReplacedWithTheirWords() {
    val id = add("lite.mp4", FileKind.VIDEO, durationMs = 30_000)
    store.saveSegments(id, listOf(
      Segment(SegmentKind.FRAME, Modality.CLIP, 0, 30_000, vector = FloatArray(IndexStore.QUICK_DIM) { if (it == 1) 1f else 0f }),
      Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "GRAND OPENING TODAY", vector = vec(3)),
    ), FileState.UNDERSTOOD)
    val rows = store.videoRows(id)
    assertTrue(rows.ocrVectors)
    val gen = store.vectorGeneration()
    assertTrue(store.saveVideoCatchUp(id, emptyMap(), listOf(Segment(SegmentKind.OCR, Modality.OCR_TEXT, 0, 0, "GRAND OPENING TODAY", vec(4), marks = "0:0:0")), keepOld = false))
    assertTrue("the word vectors changed, so the vector file is told", store.vectorGeneration() > gen)
    var n = 0; store.forEachVectorOfFile(id) { _, _, _, m, _, _ -> if (m == Modality.OCR_TEXT) n++ }
    assertEquals(1, n)
  }

  @Test fun aVideoWhoseCatchUpKilledTheProcessTwiceKeepsItsReadingAndLeavesTheQueue() {
    val video = oldVideo()
    val held = rowsHash(video)
    assertTrue(store.beginVideoCatchUp(video))    // dies inside
    assertTrue(store.beginVideoCatchUp(video))    // dies again
    assertFalse(store.beginVideoCatchUp(video))
    assertEquals(VideoCatchUp.STAMP, info(video))
    assertEquals(emptyList<Long>(), queue())
    assertNull(store.meta("video_catchup_inflight"))
    // only the stamp moved
    assertEquals(segments(video, SegmentKind.OCR).size, 1)
    assertFalse(held == rowsHash(video))
  }

  @Test fun aCatchUpThatEndsNormallyCountsNoDeath() {
    val a = oldVideo("a.mp4"); oldVideo("b.mp4")
    assertTrue(store.beginVideoCatchUp(a)); store.endVideoCatchUp()
    assertTrue(store.beginVideoCatchUp(a)); store.endVideoCatchUp()
    assertTrue(store.beginVideoCatchUp(a)); store.endVideoCatchUp()
    assertEquals(2, queue().size)
  }
}
