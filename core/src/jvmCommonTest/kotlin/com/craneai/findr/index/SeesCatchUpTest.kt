package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SeesCatchUpTest {
  private lateinit var directory: File
  private lateinit var store: IndexStore
  private var nextId = 0L

  @Before fun setup() {
    directory = Files.createTempDirectory("sees-catchup").toFile()
    store = IndexStore.open(directory, log = {})
    store.ensureModelFingerprint("shipped")
  }

  @After fun cleanup() {
    store.close()
    directory.deleteRecursively()
  }

  private fun add(kind: FileKind = FileKind.VIDEO, state: FileState = FileState.UNDERSTOOD, info: String? = null, segments: List<Segment> = emptyList()): Long {
    val mediaId = ++nextId
    store.upsertFromCrawl(listOf(CrawlRecord("test", mediaId, "content://test/$mediaId", "Movies/", "file$mediaId", kind, null, 100, 1000)))
    val id = store.knownMediaStoreIds("test").getValue(mediaId)
    store.saveSegments(id, segments, state, "old detail", info = info)
    return id
  }

  private fun frame(component: Int = 0, late: Boolean = false) = Segment(SegmentKind.FRAME, Modality.IMAGE,
    vector = FloatArray(Fp16.DIM) { if (it == component) 1f else 0f }, locStart = 1000, locEnd = 2000, marks = "@1234", late = late)

  @Test fun queueIncludesOnlyUnstampedUnderstoodVideos() {
    val old = add(info = "video-reading=2 soundtrack-reading=1")
    add(info = "sees-reading=1")
    add(kind = FileKind.AUDIO)
    add(state = FileState.PARTIAL)
    add(state = FileState.FAILED)
    add(state = FileState.SKIPPED)
    assertEquals(listOf(old), store.nextOldSees(100).map { it.file.id })
  }

  @Test fun wordsAndStampAreAtomicAndEveryOtherRowAndDateStays() {
    val id = add(info = "video-reading=2 soundtrack-reading=1", segments = listOf(frame(), frame(1, true), Segment(SegmentKind.OCR, text = "existing caption")))
    val beforeFile = store.fileById(id)
    val beforeRows = rows(id)
    val generation = store.vectorGeneration()
    val frames = store.seesFrames(id)!!
    assertEquals(2, frames.vectors.size)
    assertTrue(store.saveSeesCatchUp(frames, "Sees: church, trees"))
    assertEquals(beforeFile, store.fileById(id))
    assertEquals(generation, store.vectorGeneration())
    assertEquals(beforeRows, rows(id).filter { !it.contains("sees") })
    assertTrue(store.nextOldSees(1).isEmpty())
    assertEquals("video-reading=2 soundtrack-reading=1 sees-reading=1", store.read { it.text("SELECT info FROM files WHERE id = ?", id) })
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM fts WHERE body LIKE 'Sees:%'") })
    assertEquals("Sees: church, trees", store.read { it.text("SELECT body FROM sees_fts") })
    store.read { database ->
      database.query("SELECT modality, vec, loc_start, loc_end, marks FROM segments WHERE kind = 'sees'") {
        assertEquals("none", it.getText(0))
        for (column in 1..4) assertTrue(it.isNull(column))
      }
    }
  }

  private fun rows(id: Long): List<String> = store.read { database ->
    val rows = ArrayList<String>()
    database.query("SELECT id, kind, modality, hex(vec), loc_start, loc_end, marks, late FROM segments WHERE file = ? ORDER BY id", id) { row ->
      rows += (0..7).joinToString("|") { if (row.isNull(it)) "NULL" else row.getText(it) }
    }
    rows
  }

  @Test fun onlyStoredImageFrameVectorsAreUsed() {
    val id = add(segments = listOf(frame(), frame(1, true), Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = FloatArray(Fp16.DIM)),
      Segment(SegmentKind.FRAME, Modality.CLIP, vector = FloatArray(512)), Segment(SegmentKind.FRAME, text = "old words")))
    val frames = store.seesFrames(id)!!.vectors
    assertEquals(2, frames.size)
    assertArrayEquals(frame().vector, frames[0], 0f)
    assertArrayEquals(frame(1).vector, frames[1], 0f)
  }

  @Test fun emptyReadingIsStampedWithoutAddingWords() {
    val id = add()
    assertTrue(store.saveSeesCatchUp(store.seesFrames(id)!!, null))
    assertTrue(store.nextOldSees(1).isEmpty())
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM sees_fts") })
  }

  @Test fun stopBeforeSavingRetriesAfterReopening() {
    val id = add(segments = listOf(frame()))
    store.seesFrames(id)!!
    store.close()
    store = IndexStore.open(directory, log = {})
    assertEquals(id, store.nextOldSees(1).single().file.id)
    assertTrue(store.saveSeesCatchUp(store.seesFrames(id)!!, "Sees: trees"))
    store.close()
    store = IndexStore.open(directory, log = {})
    assertTrue(store.nextOldSees(1).isEmpty())
  }

  @Test fun changedReadingOrDisabledFileRejectsInFlightWords() {
    val id = add(segments = listOf(frame()))
    val old = store.seesFrames(id)!!
    store.saveSegments(id, listOf(frame(1)), FileState.UNDERSTOOD)
    assertFalse(store.saveSeesCatchUp(old, "Sees: old"))
    val fresh = store.seesFrames(id)!!
    store.setState(id, FileState.SKIPPED, "off")
    assertFalse(store.saveSeesCatchUp(fresh, "Sees: old"))
  }

  @Test fun repeatedSaveDoesNotDuplicateWords() {
    val id = add(segments = listOf(frame()))
    val frames = store.seesFrames(id)!!
    assertTrue(store.saveSeesCatchUp(frames, "Sees: trees"))
    assertFalse(store.saveSeesCatchUp(frames, "Sees: church"))
    assertEquals(1L, store.read { it.long("SELECT COUNT(*) FROM sees_fts") })
  }

  @Test fun changedModelDropsOldHeadWordsAndStaleFramesWait() {
    val id = add(segments = listOf(frame()))
    store.saveSeesCatchUp(store.seesFrames(id)!!, "Sees: trees")
    store.ensureModelFingerprint("changed", keepStale = true)
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM sees_fts") })
    assertNull(store.seesFrames(id))
    assertTrue(store.nextOldSees(1).isEmpty())
  }

  @Test fun deletingFileCascadesIntoSeparateWordsTable() {
    val id = add(segments = listOf(frame()))
    store.saveSeesCatchUp(store.seesFrames(id)!!, "Sees: trees")
    store.saveSegments(id, emptyList(), FileState.UNDERSTOOD)
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM sees_fts") })
  }

  @Test fun catchUpAlwaysRequiresChargerAndCompatibleEnabledVideos() {
    assertTrue(SeesCatchUp.allowed(true, true, true))
    assertFalse(SeesCatchUp.allowed(true, true, false))
    assertFalse(SeesCatchUp.allowed(true, false, true))
    assertFalse(SeesCatchUp.allowed(false, true, true))
  }
}
