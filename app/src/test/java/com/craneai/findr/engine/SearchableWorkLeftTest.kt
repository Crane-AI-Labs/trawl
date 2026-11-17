package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The signal that ends privacy pacing ([PrivacyPace]): quick look or the words pass still has work. A real store. The case found on the
 * A06 warm-up: the phone's own videos and recordings have no words until the full reading (the words pass never reads them), and
 * counting them kept the pass paced with quick look and words long done.
 */
class SearchableWorkLeftTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private val all = FileKind.entries.toSet()

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-swl").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind) {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, 50_000, 1_000 + id)))
  }

  private fun idOf(name: String): Long = store.read { db -> db.long("SELECT id FROM files WHERE name = ?", name)!! }

  private fun wordsDone(name: String) = store.setState(idOf(name), FileState.SEARCHABLE, IndexStore.WORDS_DONE)
  private fun quickDone(name: String) = store.saveQuick(idOf(name), SegmentKind.IMAGE, null)   // a "tried" row is done too

  @Test fun videosAndRecordingsWithoutWordsAreNotSearchableWork() {
    add("clip.mp4", FileKind.VIDEO); add("song.m4a", FileKind.AUDIO); add("photo.jpg", FileKind.IMAGE)
    store.saveQuick(idOf("clip.mp4"), SegmentKind.FRAME, null)
    wordsDone("photo.jpg"); quickDone("photo.jpg")
    assertFalse("quick look and words are done; the video and the recording wait for the full reading only",
      FindrEngine.searchableWorkLeft(store, all, quickOn = true))
  }

  @Test fun aPhotoWithoutWordsOrQuickLookIsWork() {
    add("photo.jpg", FileKind.IMAGE)
    assertTrue(FindrEngine.searchableWorkLeft(store, all, quickOn = true))
    wordsDone("photo.jpg")
    assertTrue("words in, quick look not yet", FindrEngine.searchableWorkLeft(store, all, quickOn = true))
    assertFalse("quick look may not run (off, or not allowed now): words alone decide", FindrEngine.searchableWorkLeft(store, all, quickOn = false))
    quickDone("photo.jpg")
    assertFalse(FindrEngine.searchableWorkLeft(store, all, quickOn = true))
  }

  @Test fun aDocumentWithoutWordsIsWorkAndOnlyEnabledKindsCount() {
    add("notes.pdf", FileKind.PDF)
    assertTrue(FindrEngine.searchableWorkLeft(store, all, quickOn = true))
    assertFalse("documents switched off", FindrEngine.searchableWorkLeft(store, all - FileKind.PDF, quickOn = true))
    wordsDone("notes.pdf")
    assertFalse(FindrEngine.searchableWorkLeft(store, all, quickOn = true))
  }

  @Test fun aVideoWaitingForItsQuickLookIsWork() {
    add("clip.mp4", FileKind.VIDEO)
    assertTrue(FindrEngine.searchableWorkLeft(store, all, quickOn = true))
    assertFalse(FindrEngine.searchableWorkLeft(store, all, quickOn = false))
  }
}
