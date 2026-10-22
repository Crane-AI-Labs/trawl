package com.craneai.findr.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.craneai.findr.core.FileKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * What IndexStore.open does with an index that does not open cleanly (the "index wipe": a whole learned index was deleted after a
 * force-stop because the open path deleted it on any error at all). One test per recovery path; the rule under test is that learned
 * data is never deleted for a problem that can be mended, and that the reason is always logged without a file name.
 */
class IndexRecoveryTest {
  private lateinit var dir: File
  private val logs = ArrayList<String>()
  private val driver = BundledSQLiteDriver()
  private val words = listOf("invoice", "receipt", "lecture", "holiday", "budget", "contract")

  /**
   * SQLite's result code travels in the exception's text. The android.jar stubs of the Android host tests drop every exception text, so
   * the tests that rest on a code run on the jvm target (and on a phone, see the write-up), and are skipped here.
   */
  private fun needsRealMessages() = Assume.assumeTrue("exception text survives", SQLiteException("probe").message == "probe")

  @Before fun setUp() { dir = Files.createTempDirectory("findr-recovery").toFile() }
  @After fun tearDown() { dir.deleteRecursively() }

  private val dbFile get() = File(dir, IndexStore.FILE_NAME)
  private fun open(retries: Int = 0, backoffMs: Long = 1, d: SQLiteDriver = driver): IndexStore =
    IndexStore.open(dir, driver = d, log = { logs += it }, retries = retries, backoffMs = backoffMs)

  private fun vec(seed: Int) = FloatArray(Fp16.DIM) { if (it == seed % Fp16.DIM) 1f else 0f }

  /** [n] understood documents, each with two text segments (a vector and words), enough to span many pages. */
  private fun fill(store: IndexStore, n: Int) {
    val records = (1..n).map {
      CrawlRecord("external_primary", it.toLong(), "content://media/external/file/$it", "Documents/", "doc$it.pdf", FileKind.PDF, null, 10_000, 1_000L + it)
    }
    store.upsertFromCrawl(records)
    val ids = store.knownMediaStoreIds("external_primary")
    for (i in 1..n) {
      val id = ids[i.toLong()]!!
      val segs = (0 until 2).map { j ->
        Segment(SegmentKind.CHUNK, Modality.TEXT, j.toLong(), j + 1L, text = "${words[(i + j) % words.size]} number $i part $j of the file", vector = vec(i * 3 + j))
      }
      store.saveSegments(id, segs, FileState.UNDERSTOOD, IndexStore.WORDS_DONE)
    }
  }

  /** The names of the files whose words match [query]: what a keyword search shows. */
  private fun hits(store: IndexStore, query: String): List<String> = store.read { db ->
    val out = ArrayList<String>()
    db.query("SELECT DISTINCT f.name FROM fts x JOIN segments s ON s.id = x.rowid JOIN files f ON f.id = s.file WHERE fts MATCH ? ORDER BY f.name", query) { out += it.getText(0) }
    out
  }

  private fun queries() = words + listOf("number", "part", "doc7", "lectures", "holiday budget")
  private fun allHits(store: IndexStore) = queries().associateWith { hits(store, it) }
  private fun count(store: IndexStore, sql: String) = store.read { it.long(sql) } ?: -1L

  /** A raw connection to the closed index, to damage it the way a bad moment would. */
  private fun raw(block: (Db) -> Unit) {
    val db = Db(driver.open(dbFile.path))
    try { block(db); runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)") { } } } finally { db.close() }
  }

  private fun ftsIntegrityFails(): Boolean {
    var failed = false
    raw { db ->
      try { db.run("INSERT INTO fts(fts, rank) VALUES('integrity-check', 1)") } catch (t: SQLiteException) { failed = true }
    }
    return failed
  }

  private fun leftovers() = dir.listFiles()!!.map { it.name }.filter { it != IndexStore.FILE_NAME && !it.endsWith("-wal") && !it.endsWith("-shm") }

  // ---- the full-text index alone is wrong: rebuild it, lose nothing --------------------------------------------------------

  @Test fun anInconsistentFullTextIndexIsRebuiltAndNothingIsLost() {
    needsRealMessages()
    var before: Map<String, List<String>>
    var files: Long; var segments: Long; var vectors: Long
    open().let { s ->
      fill(s, 40)
      before = allHits(s); files = count(s, "SELECT COUNT(*) FROM files"); segments = count(s, "SELECT COUNT(*) FROM segments")
      vectors = count(s, "SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL")
      s.close()
    }
    assertTrue(before.getValue("invoice").isNotEmpty())
    // Make the shadow tables disagree with the stored text, as a write that stopped half way would: a third of the documents lose their size records.
    raw { it.exec("DELETE FROM fts_docsize WHERE id % 3 = 0") }
    assertTrue("the damage is real: SQLite's own check sees it", ftsIntegrityFails())

    open().let { s ->
      assertTrue(logs.toString(), logs.any { it.contains("full-text index rebuilt") })
      assertFalse(logs.toString(), logs.any { it.contains("deleting it") || it.contains("damaged") })
      assertEquals(files, count(s, "SELECT COUNT(*) FROM files"))
      assertEquals(segments, count(s, "SELECT COUNT(*) FROM segments"))
      assertEquals(vectors, count(s, "SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL"))
      assertEquals("every query finds what it found before", before, allHits(s))
      assertEquals(40L, count(s, "SELECT COUNT(*) FROM files WHERE state = 'understood'"))
      s.close()
    }
    assertFalse(ftsIntegrityFails())
    assertEquals("no copy of the text is left beside the index", emptyList<String>(), leftovers())
  }

  @Test fun aWipedFullTextStructureIsRebuiltToo() {
    needsRealMessages()
    var before: Map<String, List<String>>
    open().let { s -> fill(s, 25); before = allHits(s); s.close() }
    raw { it.exec("DELETE FROM fts_data") }   // the whole inverted index gone, the stored text still there
    open().let { s ->
      assertFalse(logs.toString(), logs.any { it.contains("deleting it") })
      assertEquals(before, allHits(s))
      assertEquals(25L, count(s, "SELECT COUNT(*) FROM files WHERE state = 'understood'"))
      s.close()
    }
  }

  // ---- busy, locked, anything that is not proof of damage: retry, never delete ----------------------------------------------

  private class FlakyDriver(val real: SQLiteDriver, var failures: Int, val code: Int) : SQLiteDriver {
    var calls = 0
    override fun open(fileName: String): SQLiteConnection {
      calls++
      if (failures > 0) { failures--; throw SQLiteException("Error code: $code, message: simulated") }
      return real.open(fileName)
    }
  }

  @Test fun aBusyIndexIsRetriedWithoutDeletingAnything() {
    open().let { s -> fill(s, 20); s.close() }
    val flaky = FlakyDriver(driver, failures = 2, code = 5)
    val s = open(retries = 3, d = flaky)
    assertEquals(20L, count(s, "SELECT COUNT(*) FROM files WHERE state = 'understood'"))
    assertEquals(logs.toString(), 2, logs.count { it.contains("keeping it") })
    assertFalse(logs.toString(), logs.any { it.contains("deleting it") })
    s.close()
  }

  @Test fun anIndexThatStaysBusyIsLeftUntouchedAndTheErrorIsThrown() {
    needsRealMessages()
    open().let { s -> fill(s, 20); s.close() }
    raw { it.query("PRAGMA wal_checkpoint(TRUNCATE)") { } }
    val before = Files.readAllBytes(dbFile.toPath()).contentHashCode()
    for (code in listOf(5, 6, 10, 13, 14)) {   // busy, locked, I/O, full, cannot open
      val flaky = FlakyDriver(driver, failures = 100, code = code)
      try { open(retries = 2, d = flaky); fail("should have thrown for code $code") } catch (e: SQLiteException) { assertTrue(e.message!!.contains("Error code: $code")) }
      assertEquals("tried once and twice more", 3, flaky.calls)
    }
    assertEquals("the file is byte for byte as it was", before, Files.readAllBytes(dbFile.toPath()).contentHashCode())
    assertFalse(logs.toString(), logs.any { it.contains("deleting it") })
    open().let { s -> assertEquals(20L, count(s, "SELECT COUNT(*) FROM files WHERE state = 'understood'")); s.close() }
  }

  @Test fun everyTransientFailureIsReportedToTheCallerBeforeItsWait() {
    val seen = ArrayList<Throwable>()
    val flaky = FlakyDriver(driver, failures = 2, code = 13)
    val s = IndexStore.open(dir, driver = flaky, log = { logs += it }, retries = 3, backoffMs = 1, onRetry = { seen += it })
    assertEquals("one call per retry, two failures", 2, seen.size)
    s.close()
    // a callback that throws must not break the open
    val flaky2 = FlakyDriver(driver, failures = 1, code = 5)
    IndexStore.open(dir, driver = flaky2, log = { logs += it }, retries = 3, backoffMs = 1, onRetry = { error("boom") }).close()
  }

  // ---- damage to a main table: keep everything that can be read ------------------------------------------------------------

  /** Overwrites the page [page] (1-based) of the closed index with 0xFF bytes. */
  private fun smash(page: Long, pageSize: Int) {
    RandomAccessFile(dbFile, "rw").use { f ->
      f.seek((page - 1) * pageSize)
      f.write(ByteArray(pageSize) { 0xFF.toByte() })
    }
  }

  private fun rootPage(table: String): Long { var p = 0L; raw { db -> p = db.long("SELECT rootpage FROM sqlite_master WHERE name = ?", table)!! }; return p }
  private fun pageSize(): Int { var p = 0; raw { db -> p = db.long("PRAGMA page_size")!!.toInt() }; return p }

  /** The right-most child of an interior table page: a leaf, when the table is two levels deep. */
  private fun lastLeafOf(table: String): Long {
    val size = pageSize()
    RandomAccessFile(dbFile, "r").use { f ->
      f.seek((rootPage(table) - 1) * size)
      val head = ByteArray(12); f.readFully(head)
      assertEquals("an interior page", 0x05, head[0].toInt())
      return ((head[8].toLong() and 0xFF) shl 24) or ((head[9].toLong() and 0xFF) shl 16) or ((head[10].toLong() and 0xFF) shl 8) or (head[11].toLong() and 0xFF)
    }
  }

  @Test fun aDamagedPageOfOneTableKeepsEverythingElseAndRequeuesTheFilesAroundIt() {
    needsRealMessages()
    var filesBefore: Long; var segsBefore: Long
    open().let { s -> fill(s, 80); filesBefore = count(s, "SELECT COUNT(*) FROM files"); segsBefore = count(s, "SELECT COUNT(*) FROM segments"); s.close() }
    val leaf = lastLeafOf("segments")
    smash(leaf, pageSize())

    val s = open()
    assertTrue(logs.toString(), logs.any { it.contains("is damaged") })
    assertTrue(logs.toString(), logs.any { it.startsWith("kept ") })
    assertFalse("a partial loss is not a reset: ${logs}", logs.any { it.contains("deleting it") })
    assertEquals("every file is still known", filesBefore, count(s, "SELECT COUNT(*) FROM files"))
    val lost = segsBefore - count(s, "SELECT COUNT(*) FROM segments")   // the name segments of the lost files are made again, so this is the floor
    assertTrue("only the rows on the smashed page are gone (lost $lost of $segsBefore)", lost in 1..30)
    val waiting = count(s, "SELECT COUNT(*) FROM files WHERE state = 'new'")
    assertTrue("the files around the loss are read again ($waiting)", waiting in 1..10)
    assertTrue("most files stay understood", count(s, "SELECT COUNT(*) FROM files WHERE state = 'understood'") >= 70)
    assertEquals("every file keeps a name segment, so it is still found by name", 80L, count(s, "SELECT COUNT(DISTINCT file) FROM segments WHERE kind = 'name'"))
    assertEquals(1, hits(s, "doc7").size)
    s.close()
    assertEquals("the damaged file and its copy are gone, nothing of the text stays behind", emptyList<String>(), leftovers())

    logs.clear()
    open().let { again -> assertEquals(filesBefore, count(again, "SELECT COUNT(*) FROM files")); again.close() }
    assertFalse("a repaired index opens quietly: $logs", logs.any { it.contains("damaged") || it.contains("rebuilt") || it.contains("deleting") })
  }

  @Test fun anUnreadableSegmentsTableRequeuesEveryFileButKeepsThemAll() {
    needsRealMessages()
    open().let { s -> fill(s, 15); s.close() }
    smash(rootPage("segments"), pageSize())
    val s = open()
    assertFalse(logs.toString(), logs.any { it.contains("deleting it") })
    assertEquals(15L, count(s, "SELECT COUNT(*) FROM files"))
    assertEquals("nothing is left looking done without its segments", 15L, count(s, "SELECT COUNT(*) FROM files WHERE state = 'new'"))
    assertEquals(1, hits(s, "doc7").size)
    s.close()
  }

  @Test fun anUnreadableFilesTableLeavesNothingToKeepSoTheIndexStartsFresh() {
    needsRealMessages()
    open().let { s -> fill(s, 15); s.close() }
    smash(rootPage("files"), pageSize())
    val s = open()
    assertTrue(logs.toString(), logs.any { it.contains("nothing readable") && it.contains("deleting it and starting fresh") })
    assertEquals(0L, count(s, "SELECT COUNT(*) FROM files"))
    assertEquals(Schema.VERSION, s.read { it.long("PRAGMA user_version") })
    assertEquals(1, s.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "u", "A/", "new.pdf", FileKind.PDF, null, 1, 1))).added)
    s.close()
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test fun aFileThatIsNotADatabaseIsStartedFresh() {
    open().let { s -> fill(s, 5); s.close() }
    dbFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
    File(dir, IndexStore.FILE_NAME + "-wal").delete()
    val s = open()
    assertTrue(logs.toString(), logs.any { it.contains("deleting it and starting fresh") })
    assertEquals(0L, count(s, "SELECT COUNT(*) FROM files"))
    s.close()
  }

  @Test fun anIndexFromANewerAppIsStartedFreshWithItsOwnReason() {
    open().let { s -> fill(s, 5); s.close() }
    raw { it.exec("PRAGMA user_version = ${Schema.VERSION + 1}") }
    val s = open()
    assertTrue(logs.toString(), logs.any { it.contains("newer Findr") && it.contains("deleting it and starting fresh") })
    assertEquals(0L, count(s, "SELECT COUNT(*) FROM files"))
    s.close()
  }

  @Test fun leftoversFromEarlierBuildsAndHalfMadeSalvagesAreRemovedAtOpen() {
    open().let { s -> fill(s, 5); s.close() }
    File(dir, IndexStore.FILE_NAME + ".corrupt-1700000000000").writeBytes(ByteArray(10))
    File(dir, IndexStore.FILE_NAME + IndexRecovery.SALVAGE_SUFFIX).writeBytes(ByteArray(10))
    File(dir, IndexStore.FILE_NAME + IndexRecovery.SALVAGE_SUFFIX + "-journal").writeBytes(ByteArray(10))
    val s = open()
    assertEquals(5L, count(s, "SELECT COUNT(*) FROM files"))
    s.close()
    assertEquals(emptyList<String>(), leftovers())
  }

  @Test fun aFileIsNotADatabaseByItsHeaderAloneWhateverTheErrorSaid() {
    val f = File(dir, "x.db")
    f.writeBytes(ByteArray(0)); assertFalse("empty: a database not yet made", IndexRecovery.notADatabase(f))
    f.writeBytes(ByteArray(8192) { (it * 31).toByte() }); assertTrue(IndexRecovery.notADatabase(f))
    f.writeBytes(ByteArray(5) { 1 }); assertTrue("shorter than a header", IndexRecovery.notADatabase(f))
    open().let { fill(it, 3); it.close() }
    assertFalse("a real index", IndexRecovery.notADatabase(dbFile))
    assertFalse("a missing file", IndexRecovery.notADatabase(File(dir, "nope.db")))
  }

  // ---- reading the error code ----------------------------------------------------------------------------------------------

  @Test fun onlyCorruptAndNotADatabaseCountAsDamage() {
    needsRealMessages()
    fun e(code: Int) = SQLiteException("Error code: $code, message: x")
    assertTrue(IndexRecovery.isDamage(e(11)))
    assertTrue(IndexRecovery.isDamage(e(267)))   // CORRUPT_VTAB, the one a bad FTS5 index raises
    assertTrue(IndexRecovery.isDamage(e(26)))
    assertTrue(IndexRecovery.isDamage(IndexRecovery.Damaged("x")))
    for (code in listOf(5, 6, 7, 9, 10, 13, 14, 15, 261, 773, 1)) assertFalse("code $code is not damage", IndexRecovery.isDamage(e(code)))
    assertFalse(IndexRecovery.isDamage(IllegalStateException("integrity check failed")))
    assertTrue("found through a cause", IndexRecovery.isDamage(RuntimeException("wrapped", e(267))))
  }
}
