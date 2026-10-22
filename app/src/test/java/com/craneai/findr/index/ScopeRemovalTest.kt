package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.search.VectorIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Turning a folder group off must take its files out of search at once: the vectors in memory, the vectors and words
 * in the database. Turning it on queues them again. This runs the real crawler, store and vector index against an
 * in-memory phone.
 */
class ScopeRemovalTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var phone: FakePhone
  private lateinit var vectors: VectorIndex
  private var settings = FindrSettings()
  private lateinit var crawler: Crawler

  private val whatsapp = "Android/media/com.whatsapp/WhatsApp/Media/"

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-scope").toFile()
    store = IndexStore.open(dir)
    vectors = VectorIndex(parallelism = 1)
    phone = FakePhone()
    crawler = Crawler(store, phone, settings = { settings }, hasNoMedia = { false }, batchSize = 2)
    phone.put(1, "IMG_0001.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    phone.put(2, "IMG_0002.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    phone.put(3, "IMG-2026.jpg", "${whatsapp}WhatsApp Images/", "image/jpeg", w = 1600, h = 1200)
    phone.put(4, "note.pdf", "${whatsapp}WhatsApp Documents/", "application/pdf")
    phone.put(5, "invoice.pdf", "Download/", "application/pdf")
    phone.put(6, "plan.pdf", "Documents/", "application/pdf")
    phone.put(7, "status.jpg", "${whatsapp}.Statuses/", "image/jpeg", w = 1080, h = 1920)
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private fun fileId(mediaId: Long) = store.knownMediaStoreIds("external_primary")[mediaId]
  private fun state(mediaId: Long) = fileId(mediaId)?.let { store.stateOf(it) }
  private fun vec(seed: Int) = FloatArray(Fp16.DIM) { if (it == seed) 1f else 0f }
  private fun ftsHits(match: String): List<Long> = store.read { db ->
    val out = ArrayList<Long>()
    db.query("SELECT rowid FROM fts WHERE fts MATCH ?", match) { out += it.getLong(0) }
    out
  }

  /** Reads every searchable file: one text segment with a distinct word and a vector, as the real worker would save. */
  private fun readEverything() {
    for (mediaId in 1L..7L) {
      val id = fileId(mediaId) ?: continue
      if (store.stateOf(id)?.first == FileState.SKIPPED) continue
      store.saveSegments(id, listOf(Segment(SegmentKind.PAGE, Modality.TEXT, 1, text = "zebra$mediaId crossing", vector = vec(mediaId.toInt()))), FileState.UNDERSTOOD)
    }
    vectors.load(store)
  }

  @Test fun switchingAGroupOffTakesItsFilesOutOfEveryPlaceTheyLive() {
    crawler.sync()
    readEverything()
    assertEquals(6, vectors.size)                       // the status file is off by default, so it was never read
    assertEquals(1, ftsHits("zebra1").size)
    val camera = listOf(fileId(1)!!, fileId(2)!!)

    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.CAMERA to false))
    val report = crawler.sync(forceFull = true)
    assertEquals(camera.toSet(), report.newlySkippedFileIds.toSet())
    vectors.removeFiles(report.deletedFileIds + report.newlySkippedFileIds)   // what FindrEngine.crawl does

    assertEquals(4, vectors.size)
    assertEquals(0, ftsHits("zebra1").size)
    assertEquals(0, ftsHits("zebra2").size)
    for (id in 1L..2L) {
      assertEquals(FileState.SKIPPED, state(id)!!.first)
      assertEquals("turned off: camera", state(id)!!.second)
    }
    // The row stays (with its reason) so turning the group back on is a cheap flip; the other groups are untouched.
    for (id in 3L..6L) assertEquals(FileState.UNDERSTOOD, state(id)!!.first)
    assertEquals(1, ftsHits("zebra5").size)
    assertEquals(4, store.stats().vectors)
  }

  @Test fun aReloadAfterwardsDoesNotBringThemBack() {
    crawler.sync()
    readEverything()
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.CAMERA to false))
    crawler.sync(forceFull = true)
    val reloaded = VectorIndex(parallelism = 1)
    reloaded.load(store)
    assertEquals(4, reloaded.size)
  }

  @Test fun switchingItBackOnQueuesTheFilesAgainWithTheirNamesSearchable() {
    crawler.sync()
    readEverything()
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.CAMERA to false))
    crawler.sync(forceFull = true)

    settings = FindrSettings()
    val report = crawler.sync(forceFull = true)
    assertTrue(report.newlySkippedFileIds.isEmpty())
    assertEquals(FileState.SEARCHABLE, state(1)!!.first)       // waiting for the worker, not read yet
    assertEquals(FileState.SEARCHABLE, state(2)!!.first)
    assertEquals(FileState.UNDERSTOOD, state(3)!!.first)
  }

  @Test fun aWorkerThatWasMidFileCannotBringBackAFileThatWasSwitchedOff() {
    crawler.sync()
    val id = fileId(1)!!
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.CAMERA to false))
    crawler.sync(forceFull = true)
    assertEquals(FileState.SKIPPED, state(1)!!.first)

    // The worker finishes reading the file it had open and tries to save it.
    store.saveSegments(id, listOf(Segment(SegmentKind.PAGE, Modality.TEXT, 1, text = "late zebra", vector = vec(9))), FileState.UNDERSTOOD)
    store.setState(id, FileState.UNDERSTOOD)
    store.setState(id, FileState.SEARCHABLE)

    assertEquals(FileState.SKIPPED, state(1)!!.first)
    assertEquals(0, ftsHits("zebra").size)
    val reloaded = VectorIndex(parallelism = 1)
    reloaded.load(store)
    assertEquals(0, reloaded.size)
  }

  @Test fun anAddedFolderKeepsItsFilesWhileTheRestOfTheGroupGoes() {
    phone.put(8, "receipt.pdf", "Download/Receipts/", "application/pdf")
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.DOWNLOADS to false), includedFolders = setOf("Download/Receipts"))
    crawler.sync()
    assertEquals("turned off: downloads", state(5)!!.second)
    assertEquals(FileState.SEARCHABLE, state(8)!!.first)
  }

  @Test fun whatsAppStatusOnQueuesTheStatusFileAndOffTakesItOut() {
    crawler.sync()
    assertEquals("WhatsApp status", state(7)!!.second)
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.WHATSAPP_STATUS to true))
    crawler.sync(forceFull = true)
    assertEquals(FileState.SEARCHABLE, state(7)!!.first)
    settings = FindrSettings()
    val r = crawler.sync(forceFull = true)
    assertEquals(setOf(fileId(7)!!), r.newlySkippedFileIds.toSet())
    assertFalse(state(7)!!.first == FileState.SEARCHABLE)
  }

  @Test fun countsPerGroupSeeWhatIsThereWithoutChangingTheIndex() {
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.CAMERA to false))   // the screen counts files whatever the switches say
    val counts = crawler.countByGroup()
    assertEquals(2, counts[FolderGroups.CAMERA])
    assertEquals(1, counts[FolderGroups.WHATSAPP_IMAGES])
    assertEquals(1, counts[FolderGroups.WHATSAPP_DOCUMENTS])
    assertEquals(1, counts[FolderGroups.DOWNLOADS])         // downloads is checked before documents, so Download/ stays there
    assertEquals(1, counts[FolderGroups.DOCUMENTS])
    assertEquals(1, counts[FolderGroups.WHATSAPP_STATUS])      // counted even though it is off, so the screen can offer it
    assertEquals(null, counts[FolderGroups.MUSIC])
    assertEquals(0, store.knownMediaStoreIds("external_primary").size)   // read only: nothing was written
  }
}
