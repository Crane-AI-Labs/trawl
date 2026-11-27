package com.craneai.findr.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DbStatementCacheTest {
  private lateinit var dir: File
  private lateinit var tracking: TrackingConnection
  private lateinit var db: Db

  private class TrackingConnection(private val inner: SQLiteConnection) : SQLiteConnection by inner {
    var prepares = 0
    var resets = 0
    var clearBindingsCount = 0
    var stmtCloses = 0
    var openStmts = 0
    val preparedSqls = ArrayList<String>()

    fun resetCounters() {
      prepares = 0
      resets = 0
      clearBindingsCount = 0
      stmtCloses = 0
      preparedSqls.clear()
    }

    override fun prepare(sql: String): SQLiteStatement {
      prepares++
      openStmts++
      preparedSqls += sql
      val s = inner.prepare(sql)
      return object : SQLiteStatement by s {
        private var closed = false
        override fun reset() {
          resets++
          s.reset()
        }
        override fun clearBindings() {
          clearBindingsCount++
          s.clearBindings()
        }
        override fun close() {
          if (!closed) {
            closed = true
            stmtCloses++
            openStmts--
          }
          s.close()
        }
      }
    }
  }

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-db-cache").toFile()
    val raw = BundledSQLiteDriver().open(File(dir, "test.db").path)
    tracking = TrackingConnection(raw)
    db = Db(tracking)
    db.exec("CREATE TABLE items (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
    tracking.resetCounters()
  }

  @After fun tearDown() {
    db.close()
    dir.deleteRecursively()
  }

  @Test fun repeatedShortQueriesReusePreparedStatementAndResetBindings() {
    val insert = "INSERT INTO items(id, label) VALUES(?, ?)"
    db.run(insert, 1L, "one")
    db.run(insert, 2L, "two")
    db.run(insert, 3L, "three")
    assertEquals("insert should only be prepared once", 1, tracking.prepares)

    val select = "SELECT label FROM items WHERE id = ?"
    assertEquals("one", db.text(select, 1L))
    assertEquals("two", db.text(select, 2L))
    assertEquals("three", db.text(select, 3L))
    assertEquals(null, db.text(select, 99L))

    assertEquals("1 insert + 1 select prepared", 2, tracking.prepares)
    assertEquals(2, db.cachedStatementCount)
    assertEquals("3 runs + 4 queries = 7 resets", 7, tracking.resets)
    assertEquals("3 runs + 4 queries = 7 clearBindings", 7, tracking.clearBindingsCount)
  }

  @Test fun nestedReentrantIdenticalQueriesDoNotCollideOrLeak() {
    db.exec("CREATE TABLE tree (id INTEGER PRIMARY KEY, parent INTEGER)")
    db.run("INSERT INTO tree(id, parent) VALUES(1, 0)")
    db.run("INSERT INTO tree(id, parent) VALUES(2, 1)")
    db.run("INSERT INTO tree(id, parent) VALUES(3, 1)")
    db.run("INSERT INTO tree(id, parent) VALUES(4, 2)")
    db.run("INSERT INTO tree(id, parent) VALUES(5, 2)")

    val sql = "SELECT id FROM tree WHERE parent = ? ORDER BY id"
    // Prime cache with 1 statement for `sql`.
    val roots = ArrayList<Long>()
    db.query(sql, 0L) { roots += it.getLong(0) }
    assertEquals(listOf(1L), roots)

    val preparesBefore = tracking.prepares
    val visited = ArrayList<Long>()
    fun walk(parent: Long) {
      db.query(sql, parent) { row ->
        val child = row.getLong(0)
        visited += child
        walk(child)
      }
    }
    walk(0L)

    assertEquals(listOf(1L, 2L, 4L, 5L, 3L), visited)
    // Depth reached 4 concurrent frames -> needed 3 extra prepares beyond the cached one.
    assertTrue("re-entrant calls must prepare fresh statements", tracking.prepares > preparesBefore)
    // After unwinding, only 1 statement stays cached for `sql`; all displaced ones were closed cleanly!
    assertEquals(1, tracking.openStmts - (db.cachedStatementCount - 1))
    val cachedBeforeClose = db.cachedStatementCount
    db.close()
    assertEquals(0, db.cachedStatementCount)
    assertEquals("every prepared statement must be closed on Db.close()", 0, tracking.openStmts)
    assertTrue(tracking.stmtCloses >= cachedBeforeClose)
  }

  @Test fun longSqlStringsAreNotCached() {
    val padding = " ".repeat(Db.MAX_CACHED_SQL_LEN + 20)
    val longSql = "SELECT 1$padding"
    assertTrue(longSql.length > Db.MAX_CACHED_SQL_LEN)

    assertEquals(1L, db.long(longSql))
    assertEquals(1L, db.long(longSql))
    assertEquals("long SQL must never be cached", 0, db.cachedStatementCount)
    assertEquals("each call prepares and closes immediately", 2, tracking.prepares)
    assertEquals(0, tracking.openStmts)
  }

  @Test fun lruEvictionCapsCacheAt32AndClosesEvictedStatements() {
    // Prepare 40 distinct short queries.
    for (i in 1..40) {
      assertEquals(i.toLong(), db.long("SELECT $i"))
    }
    assertEquals(Db.MAX_CACHED_STATEMENTS, db.cachedStatementCount)
    assertEquals("8 eldest statements must have been evicted and closed", 8, tracking.stmtCloses)
    assertEquals(Db.MAX_CACHED_STATEMENTS, tracking.openStmts)

    // Touch query #9 (currently the eldest in cache: 9..40) to promote it to MRU.
    val preparesBeforeTouch = tracking.prepares
    assertEquals(9L, db.long("SELECT 9"))
    assertEquals("query #9 was still cached", preparesBeforeTouch, tracking.prepares)

    // Insert query #41 -> must evict #10, NOT #9!
    assertEquals(41L, db.long("SELECT 41"))
    assertEquals(Db.MAX_CACHED_STATEMENTS, db.cachedStatementCount)

    val preparesBeforeCheck = tracking.prepares
    assertEquals(9L, db.long("SELECT 9"))
    assertEquals("#9 must still be a cache hit after promoting it", preparesBeforeCheck, tracking.prepares)
    assertEquals(10L, db.long("SELECT 10"))
    assertEquals("#10 must have been evicted", preparesBeforeCheck + 1, tracking.prepares)
  }

  @Test fun failedStatementIsClosedAndDoesNotPoisonCache() {
    val insert = "INSERT INTO items(id, label) VALUES(?, ?)"
    db.run(insert, 1L, "first")
    assertEquals(1, db.cachedStatementCount)

    try {
      db.run(insert, 1L, "duplicate-pk")
      fail("expected constraint failure")
    } catch (_: androidx.sqlite.SQLiteException) {
      // expected
    }
    // Failed statement was closed and removed from cache; next valid insert succeeds cleanly.
    assertEquals(0, db.cachedStatementCount)
    assertEquals(0, tracking.openStmts)

    db.run(insert, 2L, "second")
    assertEquals("second", db.text("SELECT label FROM items WHERE id = ?", 2L))
  }

  @Test fun closeReleasesAllCachedStatements() {
    for (i in 1..10) db.long("SELECT $i")
    assertEquals(10, db.cachedStatementCount)
    assertEquals(10, tracking.openStmts)

    db.close()
    assertEquals(0, db.cachedStatementCount)
    assertEquals(0, tracking.openStmts)
    assertEquals(10, tracking.stmtCloses)
  }

  @Test fun detachDatabaseClearsStatementCacheCleanly() {
    val other = File(dir, "other.db")
    val otherConn = BundledSQLiteDriver().open(other.path)
    otherConn.close()

    db.exec("ATTACH DATABASE '${other.path}' AS aux")
    db.exec("CREATE TABLE aux.t (x INTEGER)")
    db.run("INSERT INTO aux.t(x) VALUES(?)", 7L)
    assertEquals(7L, db.long("SELECT x FROM aux.t"))
    assertTrue(db.cachedStatementCount > 0)

    // Must not fail with SQLITE_BUSY even though cached statements referenced `aux`.
    db.exec("DETACH DATABASE aux")
    assertEquals(0, db.cachedStatementCount)
    assertEquals(0, tracking.openStmts)
  }

  @Test fun whitespacePrefixedDetachClearsStatementCacheCleanly() {
    val other = File(dir, "other_ws.db")
    val otherConn = BundledSQLiteDriver().open(other.path)
    otherConn.close()

    db.exec("ATTACH DATABASE '${other.path}' AS aux_ws")
    db.exec("CREATE TABLE aux_ws.t (x INTEGER)")
    db.run("INSERT INTO aux_ws.t(x) VALUES(?)", 42L)
    assertEquals(42L, db.long("SELECT x FROM aux_ws.t"))
    assertTrue(db.cachedStatementCount > 0)

    // Leading spaces/tabs/newlines before DETACH (any case) must still clear cached statements.
    db.exec("  \n\t detach DATABASE aux_ws")
    assertEquals(0, db.cachedStatementCount)
    assertEquals(0, tracking.openStmts)
  }
}
