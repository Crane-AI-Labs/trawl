package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A phone in memory: rows, a generation counter that every change bumps, and a MediaStore version. */
internal class FakePhone : MediaSource {
  var version = "v1"
  var generation = 100L
  var mounted = listOf("external_primary")
  val rows = LinkedHashMap<Long, Pair<MediaRow, Long>>()   // id -> (row, generation it was last modified at)
  var rowsQueried = 0

  fun put(
    id: Long, name: String, path: String, mime: String?, size: Long = 200_000, mtimeSec: Long = 1_700_000_000,
    abs: String? = null, w: Int? = null, h: Int? = null, takenMs: Long = 0,
  ) {
    generation++
    rows[id] = MediaRow("external_primary", id, "content://media/external/file/$id", name, path, mime, size, mtimeSec, w, h, null, abs, takenMs) to generation
  }

  fun remove(id: Long) {
    rows.remove(id)
    generation++
  }

  override fun volumes() = mounted
  override fun version(volume: String) = version
  override fun generation(volume: String) = generation
  override fun forEachChanged(volume: String, sinceGeneration: Long, sink: (MediaRow) -> Unit) {
    for ((row, gen) in rows.values.toList()) if (sinceGeneration < 0 || gen > sinceGeneration) { rowsQueried++; sink(row) }
  }
  override fun allIds(volume: String) = rows.keys.toLongArray()
}

class CrawlerTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var phone: FakePhone
  private var settings = FindrSettings()
  private var noMediaDirs = emptySet<String>()
  private lateinit var crawler: Crawler

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-crawl").toFile()
    store = IndexStore.open(dir)
    phone = FakePhone()
    crawler = Crawler(store, phone, settings = { settings }, hasNoMedia = { it in noMediaDirs }, batchSize = 2)
    phone.put(1, "IMG_0001.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    phone.put(2, "Screenshot_20261001.png", "Pictures/Screenshots/", "image/png", w = 1080, h = 2400)
    phone.put(3, "receipt.pdf", "Download/", "application/pdf")
    phone.put(4, "DCIM", "", null, size = 0)   // a directory row
    phone.put(5, "icon.png", "Pictures/", "image/png", size = 1_500, w = 48, h = 48)
    phone.put(6, "secret.jpg", "Android/data/com.x/files/", "image/jpeg", w = 800, h = 600)
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private fun fileId(mediaId: Long) = store.knownMediaStoreIds("external_primary")[mediaId]
  private fun state(mediaId: Long) = fileId(mediaId)?.let { store.stateOf(it) }

  @Test fun firstSyncReadsEverythingAndRecordsSkipReasons() {
    val r = crawler.sync()
    assertTrue(r.fullResync)
    assertEquals(6, r.scanned)
    assertEquals(3, r.added)       // IMG_0001, screenshot, receipt
    assertEquals(2, r.skipped)     // icon, Android/data
    assertNull("directory rows are not files", fileId(4))
    assertEquals(FileState.SEARCHABLE, state(1)!!.first)
    assertEquals(FileKind.SCREENSHOT, store.fileById(fileId(2)!!)!!.kind)
    assertEquals(FileKind.PDF, store.fileById(fileId(3)!!)!!.kind)
    val (icon, why) = state(5)!!
    assertEquals(FileState.SKIPPED, icon)
    assertTrue(why!!, why.startsWith("tiny image"))
    assertTrue(state(6)!!.second!!.contains("app-private"))
  }

  @Test fun filesWithNoUsefulNameAreRecognisedByTheirBytes() {
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
    val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)
    val heads = mapOf("/sdcard/Download/IMG_0042" to jpeg, "/sdcard/Download/clip.dat1" to mkv, "/sdcard/Download/blob" to ByteArray(300) { (it % 7).toByte() })
    val opened = ArrayList<String>()
    val sniffing = Crawler(store, phone, settings = { settings }, hasNoMedia = { false }, batchSize = 2, header = { opened += it; heads[it] })
    phone.put(10, "IMG_0042", "Download/", null, abs = "/sdcard/Download/IMG_0042")             // no extension, no type
    phone.put(11, "clip.dat1", "Download/", "application/octet-stream", abs = "/sdcard/Download/clip.dat1")
    phone.put(12, "blob", "Download/", null, abs = "/sdcard/Download/blob")                       // nothing recognisable
    phone.put(13, "backup.zip", "Download/", "application/zip", abs = "/sdcard/Download/backup.zip")   // never opened
    phone.put(14, "Documents", "", null, abs = "/sdcard/Documents")                                // a folder: unreadable as a file
    sniffing.sync()
    assertEquals(FileKind.IMAGE, store.fileById(fileId(10)!!)!!.kind)
    assertEquals(FileKind.VIDEO, store.fileById(fileId(11)!!)!!.kind)
    assertEquals(FileKind.OTHER, store.fileById(fileId(12)!!)!!.kind)
    assertEquals(FileKind.OTHER, store.fileById(fileId(13)!!)!!.kind)
    assertNull("a folder is still not a file", fileId(14))
    assertFalse("an archive is not opened", opened.contains("/sdcard/Download/backup.zip"))
    assertFalse("files already placed by name are not opened", opened.contains("/sdcard/Download/IMG_0001.jpg"))
  }

  @Test fun nothingChangedCostsNoRowReads() {
    crawler.sync()
    val before = phone.rowsQueried
    val r = crawler.sync()
    assertEquals(0, r.scanned)
    assertFalse(r.fullResync)
    assertEquals(before, phone.rowsQueried)
  }

  @Test fun deltaVisitsOnlyRowsPastTheStoredGeneration() {
    crawler.sync()
    phone.put(7, "IMG_0002.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    phone.put(1, "IMG_0001.jpg", "DCIM/Camera/", "image/jpeg", size = 999_999, w = 4000, h = 3000)   // edited in place
    val before = phone.rowsQueried
    val r = crawler.sync()
    assertFalse(r.fullResync)
    assertEquals(2, phone.rowsQueried - before)
    assertEquals(1, r.added)
    assertEquals(1, r.changed)
    assertEquals("the edited file's old vectors are dropped from memory too", listOf(fileId(1)), r.resetFileIds)
    assertNotNull(fileId(7))
  }

  @Test fun versionChangeMeansFullResync() {
    crawler.sync()
    phone.version = "v2"   // MediaStore rebuilt its database: generations may have restarted
    val before = phone.rowsQueried
    val r = crawler.sync()
    assertTrue(r.fullResync)
    assertEquals(6, phone.rowsQueried - before)
    assertEquals(0, r.added)
    assertEquals(5, r.unchanged)   // skipped rows count too
  }

  @Test fun deletesAreFoundByIdDiff() {
    crawler.sync()
    val doomed = fileId(3)!!
    phone.remove(3)
    val r = crawler.sync()
    assertEquals(listOf(doomed), r.deletedFileIds)
    assertNull(fileId(3))
    assertNull(store.fileById(doomed))
    assertNotNull(fileId(1))
  }

  @Test fun anEmptyListingDoesNotWipeTheIndex() {
    crawler.sync()
    phone.rows.keys.toList().forEach { phone.remove(it) }   // as if the volume vanished or the permission was lost
    val r = crawler.sync()
    assertTrue(r.deletedFileIds.isEmpty())
    assertNotNull(fileId(1))
  }

  @Test fun excludingAFolderSkipsItsFilesOnTheNextFullSync() {
    crawler.sync()
    assertEquals(FileState.SEARCHABLE, state(1)!!.first)
    settings = FindrSettings(excludedFolders = setOf("DCIM/Camera"))
    // Nothing changed on the phone, so a plain sync doesn't notice; the settings change asks for a full one.
    assertEquals(0, crawler.sync().scanned)
    val r = crawler.sync(forceFull = true)
    assertTrue(r.fullResync)
    assertEquals(FileState.SKIPPED, state(1)!!.first)
    assertEquals("excluded folder", state(1)!!.second)
    // And flipping it back brings the file back.
    settings = FindrSettings()
    crawler.sync(forceFull = true)
    assertEquals(FileState.SEARCHABLE, state(1)!!.first)
  }

  @Test fun nomediaFoldersAreSkippedIncludingAncestors() {
    noMediaDirs = setOf("/storage/emulated/0/Pictures/Hidden")
    phone.put(10, "a.jpg", "Pictures/Hidden/", "image/jpeg", w = 800, h = 600, abs = "/storage/emulated/0/Pictures/Hidden/a.jpg")
    phone.put(11, "b.jpg", "Pictures/Hidden/2026/", "image/jpeg", w = 800, h = 600, abs = "/storage/emulated/0/Pictures/Hidden/2026/b.jpg")
    phone.put(12, "c.jpg", "Pictures/Visible/", "image/jpeg", w = 800, h = 600, abs = "/storage/emulated/0/Pictures/Visible/c.jpg")
    crawler.sync()
    assertEquals(".nomedia folder", state(10)!!.second)
    assertEquals(".nomedia folder", state(11)!!.second)
    assertEquals(FileState.SEARCHABLE, state(12)!!.first)
  }

  @Test fun batchesFlushEverythingEvenWhenTheLastIsPartial() {
    // batchSize is 2 and there are 3 indexable rows plus 2 skipped: no record may be lost at a batch edge.
    val r = crawler.sync()
    assertEquals(5, r.added + r.skipped)
  }

  // ---- paused: the crawl only takes things out (the research notes) ---------------------------------

  private fun words(match: String): Int = store.read { db -> var n = 0; db.query("SELECT rowid FROM fts WHERE fts MATCH ?", match) { n++ }; n }

  private fun readWords(mediaId: Long, text: String) =
    store.saveSegments(fileId(mediaId)!!, listOf(Segment(SegmentKind.OCR, Modality.NONE, text = text)), FileState.SEARCHABLE)

  @Test fun aPausedPassAfterDeleteEverythingAddsNothing() {
    // The index was just deleted (a fresh store) and Findr is paused: launches and media changes must not refill it.
    val r = crawler.sync(pruneOnly = true)
    assertEquals(0, r.added + r.changed + r.skipped)
    assertEquals(0, store.stats().filesKnown)
    phone.put(7, "IMG_0007.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    crawler.sync(forceFull = true, pruneOnly = true)
    assertEquals(0, store.stats().filesKnown)
    assertEquals(0, words("img_0007"))
    assertNull(store.meta(Schema.Meta.mediaGeneration("external_primary")))
    // Resume: the normal pass reads everything.
    assertEquals(4, crawler.sync().added)
  }

  @Test fun aPausedPassRemovesAFileDeletedDuringThePause() {
    crawler.sync()
    readWords(2, "mobile money pin quokkapin4471")
    assertEquals(1, words("quokkapin4471"))
    val generationBefore = store.meta(Schema.Meta.mediaGeneration("external_primary"))
    // Paused, the person deletes the screenshot of their PIN; a new photo arrives and another file is renamed.
    phone.remove(2)
    phone.put(7, "IMG_0007.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    phone.put(1, "IMG_0001_renamed.jpg", "DCIM/Camera/", "image/jpeg", w = 4000, h = 3000)
    val r = crawler.sync(pruneOnly = true)
    assertEquals(1, r.deletedFileIds.size)
    assertNull(fileId(2))
    assertEquals("its words left the index at once", 0, words("quokkapin4471"))
    assertNull("nothing added while paused", fileId(7))
    assertEquals("nothing renamed while paused", 0, words("img_0001_renamed"))
    assertEquals("the journal position stays for Resume", generationBefore, store.meta(Schema.Meta.mediaGeneration("external_primary")))
    // Resume: what changed during the pause is picked up.
    val after = crawler.sync()
    assertEquals(1, after.added)
    assertEquals(1, words("img_0001_renamed"))
  }

  @Test fun aPausedPassForgetsWhatWasReadFromANewlySkippedFolder() {
    crawler.sync()
    readWords(3, "invoice for quokkadoc5512")
    readWords(1, "a photo with quokkakeep99")
    settings = FindrSettings(excludedFolders = setOf("Download"))
    crawler.sync(forceFull = true, pruneOnly = true)   // what changing skipped folders does while paused
    assertEquals(FileState.SKIPPED, state(3)!!.first)
    assertEquals(0, words("quokkadoc5512"))
    assertEquals("other folders untouched", 1, words("quokkakeep99"))
  }

  @Test fun aPausedPassUsesTheFolderChoicesToo() {
    // The same rule set as a normal pass: a folder group switched off while paused is taken out of search at once,
    // through the same step (its ids come back as newly skipped, so the engine drops their vectors from memory).
    crawler.sync()
    readWords(1, "a camera photo with quokkacam77")
    readWords(3, "invoice for quokkadoc5512")
    settings = FindrSettings(groupOverrides = mapOf(FolderGroups.CAMERA to false))
    val r = crawler.sync(forceFull = true, pruneOnly = true)
    assertEquals(listOf(fileId(1)), r.newlySkippedFileIds)
    assertEquals(0, words("quokkacam77"))
    assertEquals(1, words("quokkadoc5512"))
    // Switching it back on while paused adds nothing back; Resume does.
    settings = FindrSettings()
    crawler.sync(forceFull = true, pruneOnly = true)
    assertEquals(FileState.SKIPPED, state(1)!!.first)
    crawler.sync(forceFull = true)
    assertEquals(FileState.SEARCHABLE, state(1)!!.first)
  }
}
