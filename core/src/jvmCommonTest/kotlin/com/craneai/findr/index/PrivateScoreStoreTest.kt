package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.PrivateLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The "looks private" score in the index: queue order, saving, the unreadable mark, change and model-change resets. */
class PrivateScoreStoreTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-private").toFile()
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

  private val visual = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO, FileKind.PDF)
  private fun queue(kinds: Set<FileKind> = visual) = store.nextForPrivateScore(100, kinds).map { it.name }
  private fun scoreOf(id: Long): Float? = store.filesByIds(listOf(id)).getValue(id).looksPrivate

  @Test fun photosAndScreenshotsComeNewestFirstThenVideosAndNothingElse() {
    add("old.jpg", mtime = 1); add("clip.mp4", FileKind.VIDEO, mtime = 99); add("shot.png", FileKind.SCREENSHOT, mtime = 50)
    add("new.jpg", mtime = 60); add("doc.pdf", FileKind.DOCUMENT, mtime = 70)
    assertEquals(listOf("new.jpg", "shot.png", "old.jpg", "clip.mp4"), queue())
  }

  @Test fun onlyTheKindsTheUserTurnedOnAreScored() {
    add("a.jpg"); add("b.mp4", FileKind.VIDEO)
    assertEquals(listOf("a.jpg"), queue(setOf(FileKind.IMAGE)))
    assertEquals(emptyList<String>(), queue(setOf(FileKind.DOCUMENT)))
  }

  @Test fun aSavedScoreLeavesTheQueueAndComesBackWithTheFile() {
    val a = add("a.jpg"); add("b.jpg")
    assertNull(scoreOf(a))
    store.saveLooksPrivate(a, 0.93f)
    assertEquals(listOf("b.jpg"), queue())
    assertEquals(0.93f, scoreOf(a)!!, 1e-6f)
    assertEquals(1 to 2, store.privateScoreCounts())
  }

  @Test fun anUnreadablePictureIsMarkedSoItIsNotRetriedAndNeverBlurs() {
    val a = add("broken.jpg")
    store.saveLooksPrivate(a, null)
    assertEquals(emptyList<String>(), queue())
    assertFalse(PrivateLook.blurs(scoreOf(a)))
  }

  @Test fun aChangedFileLosesItsScoreAndIsScoredAgain() {
    val a = add("a.jpg", mtime = 1_000, size = 50_000)
    store.saveLooksPrivate(a, 0.95f)
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/external/file/1", "DCIM/", "a.jpg", FileKind.IMAGE, null, 70_000, 2_000)))
    assertNull(scoreOf(a))
    assertEquals(listOf("a.jpg"), queue())
  }

  @Test fun anUnchangedFileKeepsItsScoreAcrossACrawl() {
    val a = add("a.jpg")
    store.saveLooksPrivate(a, 0.2f)
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/external/file/1", "DCIM/", "a.jpg", FileKind.IMAGE, null, 50_000, 1_000)))
    assertEquals(0.2f, scoreOf(a)!!, 1e-6f)
  }

  @Test fun aNewModelClearsEveryScoreOnceAndNotAgain() {
    val a = add("a.jpg"); val b = add("b.jpg")
    assertTrue(store.ensurePrivateModel("m1"))   // first time: records the model, nothing to clear
    store.saveLooksPrivate(a, 0.9f); store.saveLooksPrivate(b, null)
    assertFalse(store.ensurePrivateModel("m1"))
    assertEquals(0.9f, scoreOf(a)!!, 1e-6f)
    assertTrue(store.ensurePrivateModel("m2"))
    assertNull(scoreOf(a)); assertNull(scoreOf(b))
    assertEquals(listOf("b.jpg", "a.jpg").toSet(), queue().toSet())
    assertFalse(store.ensurePrivateModel("m2"))
  }

  @Test fun removingThePackForgetsEveryScoreSoNothingIsBlurredWithoutIt() {
    val a = add("a.jpg"); val b = add("b.jpg")
    store.ensurePrivateModel("m1")
    store.saveLooksPrivate(a, 0.97f); store.saveLooksPrivate(b, null)
    assertEquals(2, store.clearLooksPrivate())
    assertNull(scoreOf(a)); assertNull(scoreOf(b))
    assertNull(store.recent(visual, 10).first { it.name == "a.jpg" }.looksPrivate)
    assertEquals(0, store.clearLooksPrivate())
    // Adding the pack again scores the library afresh (same model, so ensurePrivateModel does not clear anything more).
    assertFalse(store.ensurePrivateModel("m1"))
    assertEquals(setOf("a.jpg", "b.jpg"), queue().toSet())
  }

  @Test fun theRecentListCarriesTheScoreToo() {
    val a = add("a.jpg")
    store.saveLooksPrivate(a, 0.91f)
    assertEquals(0.91f, store.recent(visual, 10).single().looksPrivate!!, 1e-6f)
  }

  @Test fun savingAScoreDoesNotTouchSearchState() {
    val a = add("a.jpg")
    store.saveSegments(a, listOf(Segment(SegmentKind.OCR, text = "fees receipt")), FileState.SEARCHABLE, IndexStore.WORDS_DONE)
    store.saveLooksPrivate(a, 0.99f)
    assertEquals(FileState.SEARCHABLE to IndexStore.WORDS_DONE, store.stateOf(a))
    assertEquals(1, store.read { db -> var n = 0; db.query("SELECT rowid FROM fts WHERE fts MATCH 'receipt'") { n++ }; n })
  }
}
