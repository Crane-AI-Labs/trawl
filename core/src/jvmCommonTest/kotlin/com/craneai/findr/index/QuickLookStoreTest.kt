package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The quick-look pass's rows in the index (D2): a 512-d `clip` segment per picture, until EmbeddingGemma understands it. */
class QuickLookStoreTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-quick").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind = FileKind.IMAGE, mtime: Long = 1_000, size: Long = 50_000): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, size, mtime)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  private fun quick(seed: Int = 0) = FloatArray(IndexStore.QUICK_DIM) { if (it == seed) 1f else 0f }
  private fun eg(seed: Int = 0) = FloatArray(Fp16.DIM) { if (it == seed) 1f else 0f }
  private val visual = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO, FileKind.PDF)
  private fun queue() = store.nextForQuickLook(100, visual).map { it.file.name }
  private fun clipRows(file: Long) = store.read { it.long("SELECT COUNT(*) FROM segments WHERE file = ? AND modality = 'clip'", file) }
  private fun quickVectors(): Int { var n = 0; store.forEachVector { _, _, _, m, _, _ -> if (m == Modality.CLIP) n++ }; return n }

  @Test fun photosAndScreenshotsComeNewestFirstThenVideosAndNothingElse() {
    add("old.jpg", mtime = 1); add("clip.mp4", FileKind.VIDEO, mtime = 99); add("shot.png", FileKind.SCREENSHOT, mtime = 50)
    add("new.jpg", mtime = 60); add("doc.pdf", FileKind.PDF, mtime = 70)
    assertEquals(listOf("new.jpg", "shot.png", "old.jpg", "clip.mp4"), queue())
  }

  @Test fun aSavedQuickVectorKeepsWordsAndStateAndLeavesTheQueue() {
    val a = add("a.jpg")
    store.saveSegments(a, listOf(Segment(SegmentKind.OCR, text = "fees receipt")), FileState.SEARCHABLE, IndexStore.WORDS_DONE)
    assertTrue(store.saveQuick(a, SegmentKind.IMAGE, quick(3)) > 0)
    assertEquals(FileState.SEARCHABLE to IndexStore.WORDS_DONE, store.stateOf(a))
    assertEquals(emptyList<String>(), queue())
    assertEquals(1, quickVectors())
    assertEquals(1, store.stats().quickOnly[FileKind.IMAGE])
    assertEquals(1, store.read { db -> var n = 0; db.query("SELECT rowid FROM fts WHERE fts MATCH 'receipt'") { n++ }; n })
  }

  @Test fun anUnreadablePictureIsMarkedSoItIsNotRetriedButCountsForNothing() {
    val a = add("broken.jpg")
    assertTrue(store.saveQuick(a, SegmentKind.IMAGE, null) > 0)
    assertEquals(emptyList<String>(), queue())
    assertEquals(0, quickVectors())
    assertEquals(null, store.stats().quickOnly[FileKind.IMAGE])
  }

  @Test fun theWordsPassKeepsTheQuickVector() {
    val a = add("a.jpg")
    store.saveQuick(a, SegmentKind.IMAGE, quick())
    store.saveSegments(a, listOf(Segment(SegmentKind.OCR, text = "menu")), FileState.SEARCHABLE, IndexStore.WORDS_DONE, keepQuick = true)
    assertEquals(1L, clipRows(a))
  }

  @Test fun understandingTheFileDropsItsQuickVector() {
    val a = add("a.jpg")
    store.saveQuick(a, SegmentKind.IMAGE, quick())
    store.saveSegments(a, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = eg())), FileState.UNDERSTOOD)
    assertEquals(0L, clipRows(a))
    assertEquals(0, quickVectors())
    assertEquals(null, store.stats().quickOnly[FileKind.IMAGE])
  }

  @Test fun aQuickVectorArrivingAfterEmbeddingGemmaIsRefused() {
    val a = add("a.jpg")
    store.saveSegments(a, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = eg())), FileState.UNDERSTOOD)
    assertEquals(-1L, store.saveQuick(a, SegmentKind.IMAGE, quick()))
    assertEquals(0L, clipRows(a))
  }

  @Test fun aChangedFileLosesItsQuickVectorAndIsQueuedAgain() {
    val a = add("a.jpg", size = 1)
    store.saveQuick(a, SegmentKind.IMAGE, quick())
    nextId = 1
    add("a.jpg", size = 2)
    assertEquals(0L, clipRows(a))
    assertEquals(listOf("a.jpg"), queue())
  }

  @Test fun aNewEmbeddingModelKeepsQuickVectorsButANewQuickModelDropsThem() {
    store.ensureModelFingerprint("eg-a"); store.ensureQuickFingerprint("clip-a")
    val a = add("a.jpg")
    store.saveQuick(a, SegmentKind.IMAGE, quick())
    assertTrue(store.ensureModelFingerprint("eg-b"))
    assertEquals(1L, clipRows(a))
    assertFalse(store.ensureQuickFingerprint("clip-a"))
    assertTrue(store.ensureQuickFingerprint("clip-b"))
    assertEquals(0L, clipRows(a))
    assertEquals(listOf("a.jpg"), queue())
  }

  @Test fun vectorsMustHaveTheirModelsSize() {
    val a = add("a.jpg")
    assertTrue(runCatching { store.saveQuick(a, SegmentKind.IMAGE, eg()) }.isFailure)
    assertTrue(runCatching { store.saveSegments(a, listOf(Segment(SegmentKind.IMAGE, Modality.CLIP, vector = eg())), FileState.SEARCHABLE) }.isFailure)
  }
}
