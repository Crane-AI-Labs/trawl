package com.craneai.findr.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.craneai.findr.core.FileKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Track F regression gate:
 *  1. `EXPLAIN QUERY PLAN` gate on every hot Schema v8 query — asserts zero `SCAN files` / `SCAN segments` regressions.
 *  2. SQLite PRAGMA verification on writer and reader connections.
 *  3. Non-blocking `PASSIVE` WAL checkpoints vs `TRUNCATE` escalation.
 *  4. Cold-start `fastOpen` clean-close decoupling + O(1) `applyRules` fingerprint short-circuit.
 *  5. Microbenchmarks (`BENCH:` lines) comparing v7 vs v8 plans, `fastOpen`, and `applyRules`.
 */
class QueryPlanTest {
  private lateinit var dir: File

  @Before fun setUp() { dir = Files.createTempDirectory("findr-qplan").toFile() }
  @After fun tearDown() { dir.deleteRecursively() }

  private fun explain(store: IndexStore, sql: String, vararg args: Any?): List<String> = store.read { db ->
    val lines = ArrayList<String>()
    db.query("EXPLAIN QUERY PLAN $sql", *args) { lines += it.getText(3) }
    lines
  }

  /** True when any EXPLAIN line does a raw table scan (`SCAN files` or `SCAN segments` NOT backed by an index). */
  private fun hasTableScan(plan: List<String>, table: String): Boolean {
    val exact = Regex("""\bSCAN $table(\s+AS\s+\w+)?(\s|$)""")
    return plan.any { line -> exact.containsMatchIn(line) && !line.contains("USING INDEX") && !line.contains("USING COVERING INDEX") }
  }

  private fun assertUsesIndex(name: String, plan: List<String>, expectedIndex: String, forbiddenTables: List<String> = listOf("files", "segments")) {
    val joined = plan.joinToString(" | ")
    println("PLAN: $name -> $joined")
    assertTrue("$name should use index $expectedIndex, got: $joined", plan.any { it.contains(expectedIndex) })
    for (t in forbiddenTables) {
      assertFalse("$name must not table-scan $t, got: $joined", hasTableScan(plan, t))
    }
  }

  private fun populateCorpus(store: IndexStore, n: Int = 2_000) {
    val records = (1..n).map { i ->
      val kind = when (i % 5) {
        0 -> FileKind.IMAGE
        1 -> FileKind.SCREENSHOT
        2 -> FileKind.PDF
        3 -> FileKind.DOCUMENT
        else -> FileKind.VIDEO
      }
      val folder = "Folder${i % 40}/Sub${i % 5}/"
      CrawlRecord(
        volume = "desktop",
        mediaStoreId = i.toLong(),
        uri = "file:///Users/test/${folder}file_$i.dat",
        relativePath = folder,
        name = "file_$i.dat",
        kind = kind,
        mimeType = null,
        sizeBytes = 50_000L + i,
        modifiedMillis = 1_700_000_000_000L + i * 1_000L,
        dateMillis = 1_700_000_000_000L + i * 1_000L,
      )
    }
    store.upsertFromCrawl(records)
    val clipVec = FloatArray(IndexStore.QUICK_DIM) { if (it == 0) 1f else 0f }
    val mainVec = FloatArray(Fp16.DIM) { if (it == 0) 1f else 0f }
    store.write { db ->
      db.transaction {
        // Realistic mid-backlog corpus: 50% understood, 49% words-done (searchable, detail = 'words') waiting for embeddings, 1% words-pending.
        db.run("UPDATE files SET state = 'understood', detail = ?, looks_private = 0.05 WHERE id <= ?", IndexStore.WORDS_DONE, n / 2)
        db.run("UPDATE files SET state = 'searchable', detail = ?, looks_private = 0.05 WHERE id > ? AND id <= ?", IndexStore.WORDS_DONE, n / 2, (n * 99) / 100)
        // Mark 1% sensitive.
        db.run("UPDATE files SET sensitive = 1 WHERE id % 100 = 0")
        // Insert segments: main vectors for understood files, quick-look clip vectors for a slice of pending visual files.
        val blobMain = Fp16.toBlob(mainVec)
        val blobClip = Fp16.toBlob(clipVec)
        for (i in 1..n) {
          if (i <= n / 2) {
            db.run("INSERT INTO segments(file, kind, modality, late, vec) VALUES(?, 'image', 'image', 0, ?)", i.toLong(), blobMain)
          } else if (i > (n * 95) / 100 && i % 5 in listOf(0, 1, 4)) {
            if (i % 2 == 0) db.run("INSERT INTO segments(file, kind, modality, late, vec) VALUES(?, 'image', 'clip', 0, ?)", i.toLong(), blobClip)
            else if (i % 3 == 0) db.run("INSERT INTO segments(file, kind, modality, late, vec) VALUES(?, 'image', 'clip', 0, NULL)", i.toLong())
          }
        }
      }
    }
  }

  // ---- 1. EXPLAIN QUERY PLAN Gate ---------------------------------------------------------------------------------------

  @Test fun explainQueryPlanUsesV8IndexesWithZeroTableScans() {
    val store = IndexStore.open(File(dir, "plan"), log = {})
    try {
      populateCorpus(store, n = 1_000)

      // 1a. countUnder (desktop)
      val prefix = "file:///Users/test/Folder7/"
      assertUsesIndex(
        "countUnder",
        explain(store, "SELECT COUNT(*) FROM files WHERE volume = ? AND state != 'skipped' AND uri >= ? AND uri < ?", "desktop", prefix, prefix + "\uFFFD"),
        "files_volume_uri",
      )

      // 1b. removeUnder (desktop)
      assertUsesIndex(
        "removeUnder",
        explain(store, "SELECT id, uri FROM files WHERE volume = ? AND uri >= ? AND uri < ?", "desktop", prefix, prefix + "\uFFFD"),
        "COVERING INDEX files_volume_uri",
      )

      // 1c. dropUnder (desktop)
      assertUsesIndex(
        "dropUnder",
        explain(store, "SELECT id FROM files WHERE volume = ? AND uri >= ? AND uri < ?", "desktop", prefix, prefix + "\uFFFD"),
        "COVERING INDEX files_volume_uri",
      )

      // 2a. recentHiddenCount (no date range)
      assertUsesIndex(
        "recentHiddenCount",
        explain(store, "SELECT COUNT(*) FROM files WHERE state != 'skipped' AND sensitive != 0 AND kind IN ('IMAGE','SCREENSHOT','PDF')"),
        "files_sensitive",
      )

      // 2b. recentHiddenCount (with date range)
      assertUsesIndex(
        "recentHiddenCountWithDates",
        explain(
          store,
          "SELECT COUNT(*) FROM files WHERE state != 'skipped' AND sensitive != 0 AND kind IN ('IMAGE','SCREENSHOT') AND date_ms >= 1700000000000 AND date_ms < 1700001000000",
        ),
        "files_sensitive",
      )

      // 2c. sensitiveCount
      assertUsesIndex(
        "sensitiveCount",
        explain(store, "SELECT COUNT(*) FROM files WHERE sensitive != 0 AND state != 'skipped'"),
        "files_sensitive",
      )

      // 3. nextForPrivateScore
      assertUsesIndex(
        "nextForPrivateScore",
        explain(
          store,
          "SELECT ${IndexStore.FILE_COLUMNS} FROM files WHERE looks_private IS NULL AND state != 'skipped' AND kind IN ('IMAGE','SCREENSHOT','VIDEO') " +
            "ORDER BY CASE WHEN kind = 'VIDEO' THEN 1 ELSE 0 END, mtime DESC, id LIMIT 20",
        ),
        "files_unscored_private",
      )

      // 4a. wordsLeft (desktop)
      assertUsesIndex(
        "wordsLeft",
        explain(
          store,
          "SELECT COUNT(*) FROM files WHERE +volume = ? AND state IN ('new','searchable') AND detail IS NULL AND kind IN ('DOCUMENT','PDF','IMAGE','SCREENSHOT')",
          "desktop",
        ),
        "files_words_pending",
      )

      // 4b. nextWithoutWords
      assertUsesIndex(
        "nextWithoutWords",
        explain(
          store,
          "SELECT ${IndexStore.FILE_COLUMNS}, state, detail FROM files WHERE state IN ('new','searchable') AND detail IS NULL " +
            "AND kind IN ('DOCUMENT','PDF','IMAGE','SCREENSHOT') ORDER BY ${Schema.WORDS_ORDER}, mtime DESC, id LIMIT 8",
        ),
        "files_words_pending",
      )

      // 5a. nextForQuickLook (correlated NOT EXISTS subquery must hit covering segments_clip)
      val quickPlan = explain(
        store,
        "SELECT ${IndexStore.FILE_COLUMNS}, state, detail FROM files f WHERE f.state IN ('new','searchable','failed') AND f.kind IN ('IMAGE','SCREENSHOT') " +
          "AND NOT EXISTS (SELECT 1 FROM segments s WHERE s.file = f.id AND s.modality = 'clip') ORDER BY f.mtime DESC, f.id LIMIT 10",
      )
      assertUsesIndex("nextForQuickLook", quickPlan, "COVERING INDEX segments_clip", forbiddenTables = listOf("files", "segments", "f", "s"))

      // 5b. hasQuickVector
      assertUsesIndex(
        "hasQuickVector",
        explain(store, "SELECT COUNT(*) FROM segments WHERE file = ? AND modality = 'clip' AND vec IS NOT NULL", 1L),
        "segments_clip",
      )

      // 5c. segmentCounts combined clip query
      val segCountsPlan = explain(
        store,
        "SELECT f.kind, (s.vec IS NOT NULL), COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file " +
          "WHERE s.modality = 'clip' AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind, (s.vec IS NOT NULL)",
      )
      assertUsesIndex("segmentCounts", segCountsPlan, "segments_clip", forbiddenTables = listOf("files", "segments", "f", "s"))
    } finally {
      store.close()
    }
  }

  // ---- 2. SQLite Performance & Safety PRAGMAs ---------------------------------------------------------------------------

  @Test fun sqlitePragmasAreConfiguredOnWriterAndReaders() {
    val store = IndexStore.open(File(dir, "pragmas"), log = {})
    try {
      store.write { db ->
        assertEquals("wal", db.text("PRAGMA journal_mode")?.lowercase())
        assertEquals(1L, db.long("PRAGMA synchronous"))     // NORMAL
        assertEquals(1L, db.long("PRAGMA foreign_keys"))    // ON
        assertEquals(1L, db.long("PRAGMA secure_delete"))   // ON
        assertEquals(2L, db.long("PRAGMA temp_store"))      // MEMORY
        assertEquals(-8_000L, db.long("PRAGMA cache_size")) // 8 MB writer cache
      }
      store.read { db ->
        assertEquals(1L, db.long("PRAGMA query_only"))      // ON
        assertEquals(2L, db.long("PRAGMA temp_store"))      // MEMORY
        assertEquals(-4_000L, db.long("PRAGMA cache_size")) // 4 MB reader cache
      }
    } finally {
      store.close()
    }
  }

  // ---- 3. Non-Blocking PASSIVE WAL Checkpoints --------------------------------------------------------------------------

  @Test fun trimWalUsesPassiveMidRunWithoutBlockingReadersAndTruncatesWhenExplicit() {
    val store = IndexStore.open(File(dir, "wal"), log = {})
    try {
      store.upsertFromCrawl((1L..300L).map {
        CrawlRecord("external_primary", it, "content://media/external/file/$it", "DCIM/", "p$it.jpg", FileKind.IMAGE, null, 10_000, 1_000L + it)
      })
      val before = store.walBytes()
      assertTrue("WAL grew after writes", before > 0)

      // Hold a reader open mid-query while running a mid-run PASSIVE trimWal: must never block!
      val readerInside = CountDownLatch(1)
      val releaseReader = CountDownLatch(1)
      val readerThread = Thread {
        store.read { db ->
          db.query("SELECT id FROM files LIMIT 5") {
            readerInside.countDown()
            releaseReader.await(5, TimeUnit.SECONDS)
          }
        }
      }
      readerThread.start()
      assertTrue("reader entered query", readerInside.await(5, TimeUnit.SECONDS))

      val t0 = System.nanoTime()
      store.trimWal(aboveBytes = 1L) // aboveBytes > 0 & below 16 MB -> PASSIVE, non-blocking
      val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
      releaseReader.countDown()
      readerThread.join(5_000)

      assertTrue("PASSIVE trimWal must not stall on an active reader (took $elapsedMs ms)", elapsedMs < 2_000.0)
      assertTrue("PASSIVE leaves WAL file allocated for reuse", store.walBytes() > 0L)

      // Explicit truncate (default when aboveBytes == 0, or truncate = true) shrinks WAL to 0.
      assertTrue(store.trimWal(aboveBytes = 1L, truncate = true) > 0L)
      assertEquals(0L, store.walBytes())
      assertEquals(300, store.stats().filesKnown)
    } finally {
      store.close()
    }
  }

  // ---- 4. Cold-Start fastOpen & O(1) applyRules -------------------------------------------------------------------------

  @Test fun cleanCloseEnablesFastOpenAndFirstWriteClearsMarker() {
    val sub = File(dir, "fast")
    val s1 = IndexStore.open(sub, log = {})
    s1.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "u1", "DCIM/", "a.jpg", FileKind.IMAGE, null, 100, 1_000)))
    s1.close()

    // Clean close wrote meta.clean_close = '1' and truncated WAL to 0.
    val rawCheck = Db(BundledSQLiteDriver().open(File(sub, IndexStore.FILE_NAME).path))
    try {
      assertEquals("1", rawCheck.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.CLEAN_CLOSE))
    } finally {
      rawCheck.close()
    }
    assertEquals(0L, File(sub, IndexStore.FILE_NAME + "-wal").let { if (it.exists()) it.length() else 0L })

    // Reopen with fastOpen = true: read-only session leaves clean_close = '1' untouched.
    val s2 = IndexStore.open(sub, log = {}, fastOpen = true)
    assertEquals("1", s2.meta(Schema.Meta.CLEAN_CLOSE))
    assertEquals(1, s2.stats().filesKnown)
    s2.verifyIntegrity() // on-demand check works cleanly
    // First write clears clean_close to '0'.
    s2.setMeta("probe", "ok")
    assertEquals("0", s2.meta(Schema.Meta.CLEAN_CLOSE))
    s2.close()

    // Simulated unclean crash: set clean_close = '0' and damage FTS; even fastOpen = true MUST run IndexRecovery.check() and repair it!
    val rawDamage = Db(BundledSQLiteDriver().open(File(sub, IndexStore.FILE_NAME).path))
    try {
      rawDamage.run("INSERT INTO meta(key, value) VALUES(?, '0') ON CONFLICT(key) DO UPDATE SET value = '0'", Schema.Meta.CLEAN_CLOSE)
      rawDamage.exec("DELETE FROM fts_data")
      rawDamage.query("PRAGMA wal_checkpoint(TRUNCATE)") { }
    } finally {
      rawDamage.close()
    }
    val logs = ArrayList<String>()
    val s3 = IndexStore.open(sub, log = { logs += it }, fastOpen = true)
    try {
      assertTrue("unclean marker must trigger recovery even with fastOpen=true: $logs", logs.any { it.contains("full-text index rebuilt") })
      assertEquals(1, s3.stats().filesKnown)
    } finally {
      s3.close()
    }
  }

  @Test fun applyRulesFingerprintShortCircuitsWithZeroWritesAndStaysConsistent() {
    val sub = File(dir, "rules")
    val s1 = IndexStore.open(sub, log = {})
    s1.upsertFromCrawl(listOf(
      CrawlRecord("external_primary", 1, "u1", "Secret/", "a.jpg", FileKind.IMAGE, null, 100, 1_000),
      CrawlRecord("external_primary", 2, "u2", "Public/", "b.jpg", FileKind.IMAGE, null, 100, 2_000),
    ))
    val rules = PrivacyRules(privateFolders = setOf("Secret"))
    assertEquals(1, s1.applyRules(rules))
    assertEquals(1, s1.sensitiveCount())

    // Same rules (even with different casing/slashes) short-circuit without bumping changeCount.
    val beforeChanges = s1.changeCount
    assertEquals(0, s1.applyRules(PrivacyRules(privateFolders = setOf("/secret/"))))
    assertEquals("short-circuit must not bump changeCount", beforeChanges, s1.changeCount)
    s1.close()

    // Cold reopen with fastOpen: applyRules with same rules must not clear clean_close or write!
    val s2 = IndexStore.open(sub, log = {}, fastOpen = true)
    assertEquals(0, s2.applyRules(rules))
    assertEquals("1", s2.meta(Schema.Meta.CLEAN_CLOSE))
    assertEquals(0L, s2.changeCount)
    assertEquals(1, s2.sensitiveCount())

    // setMarked updates fingerprint in lockstep, so re-applying returned rules also short-circuits.
    val pubId = s2.knownMediaStoreIds("external_primary").getValue(2L)
    val markedRules = s2.setMarked(pubId, true)!!
    assertEquals(2, s2.sensitiveCount())
    val cAfterMark = s2.changeCount
    assertEquals(0, s2.applyRules(markedRules))
    assertEquals(cAfterMark, s2.changeCount)

    // Changing rules busts the fingerprint and updates rows.
    assertNotEquals(IndexStore.rulesFingerprint(markedRules), IndexStore.rulesFingerprint(PrivacyRules.NONE))
    assertEquals(2, s2.applyRules(PrivacyRules.NONE))
    assertEquals(0, s2.sensitiveCount())
    s2.close()
  }

  @Test fun markDirtyRetriesAfterTransientBusyAndNeverClearsFlagPrematurely() {
    val sub = File(dir, "dirty-retry")
    val s1 = IndexStore.open(sub, log = {})
    s1.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "u1", "DCIM/", "a.jpg", FileKind.IMAGE, null, 100, 1_000)))
    s1.close()

    val failNextCleanClose = AtomicBoolean(false)
    val base = BundledSQLiteDriver()
    val flakyDriver = object : SQLiteDriver {
      override fun open(fileName: String): SQLiteConnection {
        val inner = base.open(fileName)
        return object : SQLiteConnection by inner {
          override fun prepare(sql: String): SQLiteStatement {
            val stmt = inner.prepare(sql)
            if (!sql.contains("INSERT INTO meta")) return stmt
            return object : SQLiteStatement by stmt {
              override fun step(): Boolean {
                if (failNextCleanClose.compareAndSet(true, false)) {
                  throw SQLiteException("Error code: 5, message: database is locked")
                }
                return stmt.step()
              }
            }
          }
        }
      }
    }

    val s2 = IndexStore.open(sub, driver = flakyDriver, log = {}, fastOpen = true)
    try {
      assertEquals("1", s2.meta(Schema.Meta.CLEAN_CLOSE))
      failNextCleanClose.set(true)
      try {
        s2.setMeta("probe", "v1")
        fail("expected transient SQLITE_BUSY from markDirty()")
      } catch (_: SQLiteException) {
        // expected
      }
      // Failure must NOT flip cleanCloseMarked to false or let the write run; disk still has '1'.
      assertEquals("1", s2.meta(Schema.Meta.CLEAN_CLOSE))
      assertNull(s2.meta("probe"))

      // Next write retries markDirty(), clears clean_close on disk to '0', and applies the mutation.
      s2.setMeta("probe", "v2")
      assertEquals("0", s2.meta(Schema.Meta.CLEAN_CLOSE))
      assertEquals("v2", s2.meta("probe"))
    } finally {
      s2.close()
    }
  }

  @Test fun fastOpenSecureDeleteVerifyRepairAndNonEmptyWalSafety() {
    val sub = File(dir, "fast-safety")
    val s1 = IndexStore.open(sub, log = {})
    s1.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "u1", "DCIM/", "report.pdf", FileKind.PDF, null, 100, 1_000)))
    s1.close()

    // 1. fts_config secure-delete upgrade path must clear CLEAN_CLOSE='0' on disk even when fastOpen=true.
    val rawStrip = Db(BundledSQLiteDriver().open(File(sub, IndexStore.FILE_NAME).path))
    try {
      rawStrip.exec("DELETE FROM fts_config WHERE k = 'secure-delete'")
      rawStrip.run("INSERT INTO meta(key, value) VALUES(?, '1') ON CONFLICT(key) DO UPDATE SET value = '1'", Schema.Meta.CLEAN_CLOSE)
      rawStrip.query("PRAGMA wal_checkpoint(TRUNCATE)") { }
    } finally {
      rawStrip.close()
    }
    val s2 = IndexStore.open(sub, log = {}, fastOpen = true)
    try {
      assertEquals("secure-delete upgrade must clear clean_close on disk", "0", s2.meta(Schema.Meta.CLEAN_CLOSE))
      assertEquals(1L, s2.read { it.long("SELECT v FROM fts_config WHERE k = 'secure-delete'") })
    } finally {
      s2.close()
    }

    // 2. verifyIntegrity() repairing FTS must clear CLEAN_CLOSE='0' and bump changeCount.
    val repairLogs = ArrayList<String>()
    val s3 = IndexStore.open(sub, log = { repairLogs += it }, fastOpen = true)
    try {
      assertEquals("1", s3.meta(Schema.Meta.CLEAN_CLOSE))
      val beforeChanges = s3.changeCount
      val rawCorrupt = Db(BundledSQLiteDriver().open(File(sub, IndexStore.FILE_NAME).path))
      try {
        rawCorrupt.exec("DELETE FROM fts_data")
      } finally {
        rawCorrupt.close()
      }
      s3.verifyIntegrity()
      assertTrue("verifyIntegrity must rebuild damaged FTS: $repairLogs", repairLogs.any { it.contains("full-text index rebuilt") })
      assertEquals("verifyIntegrity repair must clear clean_close on disk", "0", s3.meta(Schema.Meta.CLEAN_CLOSE))
      assertTrue("verifyIntegrity repair must bump changeCount", s3.changeCount > beforeChanges)
    } finally {
      s3.close()
    }

    // 3. Unclean crash with non-empty WAL (even while meta.clean_close == '1') must trigger full recovery on fastOpen=true.
    val rawWalHolder = Db(BundledSQLiteDriver().open(File(sub, IndexStore.FILE_NAME).path))
    try {
      rawWalHolder.query("PRAGMA journal_mode = WAL") { }
      rawWalHolder.exec("DELETE FROM fts_data")
      // Keep rawWalHolder open so SQLite does NOT auto-checkpoint the WAL before IndexStore.open inspects it.
      val walSize = File(sub, IndexStore.FILE_NAME + "-wal").let { if (it.exists()) it.length() else 0L }
      assertTrue("WAL must be non-empty", walSize > 0L)
      assertEquals("1", rawWalHolder.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.CLEAN_CLOSE))

      val walLogs = ArrayList<String>()
      val s4 = IndexStore.open(sub, log = { walLogs += it }, fastOpen = true)
      try {
        assertTrue("non-empty WAL must trigger full check even with clean_close='1': $walLogs", walLogs.any { it.contains("full-text index rebuilt") })
        assertEquals("0", s4.meta(Schema.Meta.CLEAN_CLOSE))
      } finally {
        s4.close()
      }
    } finally {
      rawWalHolder.close()
    }
  }

  @Test fun readConcurrentWithCloseClosesReturnedReaderWithoutLeaking() {
    val openConns = AtomicInteger(0)
    val base = BundledSQLiteDriver()
    val trackingDriver = object : SQLiteDriver {
      override fun open(fileName: String): SQLiteConnection {
        val inner = base.open(fileName)
        openConns.incrementAndGet()
        return object : SQLiteConnection by inner {
          private val closed = AtomicBoolean(false)
          override fun close() {
            if (closed.compareAndSet(false, true)) openConns.decrementAndGet()
            inner.close()
          }
        }
      }
    }

    val store = IndexStore.open(File(dir, "close-race"), driver = trackingDriver, readers = 2, log = {})
    assertEquals("1 writer + 2 readers open", 3, openConns.get())

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val readerError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val t = Thread {
      try {
        store.read { db ->
          entered.countDown()
          assertTrue(release.await(5, TimeUnit.SECONDS))
          assertEquals(1L, db.long("SELECT 1"))
        }
      } catch (e: Throwable) {
        readerError.set(e)
      }
    }
    t.start()
    assertTrue("reader entered block", entered.await(5, TimeUnit.SECONDS))

    // Close store while reader is in-flight: writer + idle reader close immediately, in-flight reader closes on exit.
    store.close()
    assertEquals("only the in-flight reader remains open", 1, openConns.get())

    release.countDown()
    t.join(5_000)
    assertNull("in-flight reader should finish cleanly", readerError.get())
    assertEquals("in-flight reader must be closed instead of leaked into dead pool", 0, openConns.get())

    // Subsequent read on closed store fails immediately instead of blocking.
    try {
      store.read { it.long("SELECT 1") }
      fail("expected IllegalStateException on closed IndexStore")
    } catch (_: IllegalStateException) {
      // expected
    }
  }

  @Test fun applyRulesAndSetMarkedRollbackPreserveInMemoryRulesAndSerializeTakeLearnedRules() {
    val store = IndexStore.open(File(dir, "rules-rollback"), log = {})
    try {
      store.upsertFromCrawl(listOf(
        CrawlRecord("external_primary", 1, "u1", "DCIM/", "a.jpg", FileKind.IMAGE, null, 100, 1_000),
      ))
      val fileId = store.knownMediaStoreIds("external_primary").getValue(1L)
      val initial = PrivacyRules(privateFolders = setOf("Secret"))
      store.applyRules(initial)
      assertEquals(initial, store.rules)
      assertNull(store.takeLearnedRules())

      // Inject a trigger that aborts any UPDATE of files.sensitive to force transaction rollback.
      store.write { db ->
        db.exec("CREATE TEMP TRIGGER fail_sensitive BEFORE UPDATE OF sensitive ON files BEGIN SELECT RAISE(ABORT, 'injected'); END")
      }

      // 1. applyRules rollback must preserve previous in-memory rules and rulesDirty=false.
      val proposed = PrivacyRules(privateFolders = setOf("DCIM"))
      try {
        store.applyRules(proposed)
        fail("expected trigger abort on applyRules")
      } catch (_: SQLiteException) {
        // expected
      }
      assertEquals("applyRules rollback must preserve old in-memory rules", initial, store.rules)
      assertNull("applyRules rollback must not mark rulesDirty", store.takeLearnedRules())

      // 2. setMarked rollback must preserve previous in-memory rules.
      try {
        store.setMarked(fileId, true)
        fail("expected trigger abort on setMarked")
      } catch (_: SQLiteException) {
        // expected
      }
      assertEquals("setMarked rollback must preserve old in-memory rules", initial, store.rules)

      // Drop trigger and verify normal applyRules learning + takeLearnedRules() once-only delivery.
      store.write { db -> db.exec("DROP TRIGGER fail_sensitive") }
      val pathOnly = PrivacyRules(markedFiles = setOf(PrivacyRules.pathKey("external_primary", "DCIM/", "a.jpg")))
      assertEquals(1, store.applyRules(pathOnly))
      val learned = store.takeLearnedRules()
      assertNotNull("applyRules should report newly learned MediaStore id key", learned)
      assertTrue(PrivacyRules.key("external_primary", 1L) in learned!!.markedFiles)
      assertNull("second takeLearnedRules() must return null", store.takeLearnedRules())
    } finally {
      store.close()
    }
  }

  // ---- 5. Microbenchmarks (BENCH: lines) --------------------------------------------------------------------------------

  @Test fun benchmarkV7VsV8IndexesAndColdStart() {
    val n = 10_000
    val sub = File(dir, "bench")
    val store = IndexStore.open(sub, log = {})
    populateCorpus(store, n = n)
    val rules = PrivacyRules(privateFolders = setOf("Folder3"))
    store.applyRules(rules)
    store.close()

    fun measureUs(iters: Int, block: () -> Unit): Double {
      repeat(3) { block() } // warmup
      val t0 = System.nanoTime()
      repeat(iters) { block() }
      return (System.nanoTime() - t0) / (iters * 1_000.0)
    }

    // 1. Cold-start open: default full check vs fastOpen
    val fullOpenUs = measureUs(5) { IndexStore.open(sub, log = {}, fastOpen = false).close() }
    val fastOpenUs = measureUs(5) { IndexStore.open(sub, log = {}, fastOpen = true).close() }

    val v8 = IndexStore.open(sub, log = {}, fastOpen = true)
    val prefix = "file:///Users/test/Folder7/"
    val visual = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)
    val wordsKinds = setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.IMAGE, FileKind.SCREENSHOT)
    val wordsIn = wordsKinds.joinToString(",") { "'${it.name}'" }

    // 2. applyRules cold scan (clear fingerprint) vs O(1) short-circuit
    val applyScanUs = measureUs(10) {
      v8.setMeta(Schema.Meta.RULES_FINGERPRINT, null)
      v8.applyRules(rules)
    }
    val applyFastUs = measureUs(50) { v8.applyRules(rules) }

    // 3. Hot queries WITH v8 indexes
    val countUnderV8 = measureUs(50) {
      v8.read { it.long("SELECT COUNT(*) FROM files WHERE volume = ? AND state != 'skipped' AND uri >= ? AND uri < ?", "desktop", prefix, prefix + "\uFFFD") }
    }
    val hiddenV8 = measureUs(50) { v8.recentHiddenCount(visual) }
    val privateQ8 = measureUs(50) { v8.nextForPrivateScore(20, visual) }
    val wordsLeftV8 = measureUs(50) {
      v8.read { it.long("SELECT COUNT(*) FROM files WHERE +volume = ? AND state IN ('new','searchable') AND detail IS NULL AND kind IN ($wordsIn)", "desktop") }
    }
    val segCountsV8 = measureUs(50) {
      v8.read { db ->
        db.query(
          "SELECT f.kind, (s.vec IS NOT NULL), COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file " +
            "WHERE s.modality = 'clip' AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind, (s.vec IS NOT NULL)",
        ) { }
      }
    }

    // Drop ONLY the 5 v8 indexes to measure v7 baseline on the exact same DB!
    v8.write { db ->
      for (idx in listOf("files_volume_uri", "files_sensitive", "files_unscored_private", "files_words_pending", "segments_clip")) {
        db.exec("DROP INDEX $idx")
      }
    }

    val countUnderV7 = measureUs(20) {
      v8.read { it.long("SELECT COUNT(*) FROM files WHERE volume = ? AND state != 'skipped' AND uri >= ? AND uri < ?", "desktop", prefix, prefix + "\uFFFD") }
    }
    val hiddenV7 = measureUs(20) { v8.recentHiddenCount(visual) }
    val privateQ7 = measureUs(20) { v8.nextForPrivateScore(20, visual) }
    val wordsLeftV7 = measureUs(20) {
      v8.read { it.long("SELECT COUNT(*) FROM files WHERE +volume = ? AND state IN ('new','searchable') AND detail IS NULL AND kind IN ($wordsIn)", "desktop") }
    }
    val segCountsV7 = measureUs(20) {
      v8.read { db ->
        db.query(
          "SELECT f.kind, COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file " +
            "WHERE s.modality = 'clip' AND s.vec IS NOT NULL AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind",
        ) { }
        db.query(
          "SELECT f.kind, COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file " +
            "WHERE s.modality = 'clip' AND s.vec IS NULL AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind",
        ) { }
      }
    }
    v8.close()

    fun fmt(name: String, before: Double, after: Double) =
      "BENCH: $name (${n} files) v7=%.1f us -> v8=%.1f us (%.1fx)".format(before, after, before / after.coerceAtLeast(0.1))

    println("BENCH: open() (${n} files) full=%.1f us -> fastOpen=%.1f us (%.1fx)".format(fullOpenUs, fastOpenUs, fullOpenUs / fastOpenUs.coerceAtLeast(0.1)))
    println("BENCH: applyRules() (${n} files) scan=%.1f us -> shortCircuit=%.1f us (%.1fx)".format(applyScanUs, applyFastUs, applyScanUs / applyFastUs.coerceAtLeast(0.1)))
    println(fmt("countUnder", countUnderV7, countUnderV8))
    println(fmt("recentHiddenCount", hiddenV7, hiddenV8))
    println(fmt("nextForPrivateScore", privateQ7, privateQ8))
    println(fmt("wordsLeft", wordsLeftV7, wordsLeftV8))
    println(fmt("segmentCounts(clip)", segCountsV7, segCountsV8))

    assertTrue("fastOpen should be faster than full open", fastOpenUs < fullOpenUs)
    assertTrue("applyRules short-circuit should be much faster than scan", applyFastUs < applyScanUs)
    assertTrue("countUnder should be faster with files_volume_uri", countUnderV8 < countUnderV7)
    assertTrue("recentHiddenCount should be faster with files_sensitive", hiddenV8 < hiddenV7)
    assertTrue("nextForPrivateScore should be faster with files_unscored_private", privateQ8 < privateQ7)
    assertTrue("segmentCounts(clip) should be faster with segments_clip", segCountsV8 < segCountsV7)
  }
}
