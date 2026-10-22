package com.craneai.findr.index

import androidx.sqlite.SQLiteDriver
import com.craneai.findr.core.FindrLog
import com.craneai.findr.diag.Diag
import com.craneai.findr.diag.HandledKind
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What [IndexStore.open] does when the index file does not open cleanly. The index holds hours of learning (on a phone, days of
 * battery), so the rule is: delete it only when nothing in it can be read, and say why in one line without a file name.
 *
 *  - Busy, locked, out of memory or space, an I/O hiccup, anything that is not proof of damage: wait and try again, and if it
 *    still fails stop with the error. The data is never touched (it used to be deleted on any error at all).
 *  - The full-text index fails its own integrity check: `INSERT INTO fts(fts) VALUES('rebuild')` rebuilds it from the text FTS5
 *    stores beside it. Files, segments and vectors are not involved. Checked again afterwards.
 *  - A main table is damaged, or the rebuild itself cannot run: [salvage] copies every readable row into a new file and swaps it in.
 *    A file that lost a piece is read again, so nothing half-learned looks finished.
 *  - Only when even that yields nothing (not a database at all) or the schema is a newer app's is the file deleted.
 */
internal object IndexRecovery {
  /** The index is damaged: a main table fails its check, or the full-text index cannot be rebuilt. */
  class Damaged(message: String, cause: Throwable? = null) : Exception(message, cause)
  /** The index was made by a newer app; this one cannot read it. */
  class TooNew(message: String) : Exception(message)

  private const val CORRUPT = 11
  private const val NOTADB = 26
  private val CODE = Regex("""Error code: (\d+)""")

  /** SQLite's primary result code carried in an androidx.sqlite exception's message ("Error code: 267, message: …"), or null. */
  fun code(t: Throwable): Int? {
    var c: Throwable? = t
    while (c != null) {
      CODE.find(c.message ?: "")?.let { return it.groupValues[1].toInt() and 0xFF }
      c = c.cause
    }
    return null
  }

  /** Proof of damage: SQLite said CORRUPT or NOTADB, or one of our own checks found a fault. Nothing else counts. */
  fun isDamage(t: Throwable): Boolean = t is Damaged || code(t).let { it == CORRUPT || it == NOTADB }

  /**
   * The file has bytes but does not start with SQLite's 16-byte header: whatever the error said (its text can be missing, as in
   * the android.jar stubs of the host tests), this is not a database. An empty file is a database not yet made, so it is not this.
   */
  fun notADatabase(file: File): Boolean = runCatching {
    file.length() > 0 && java.io.RandomAccessFile(file, "r").use { f ->
      if (f.length() < 16) true else {
        val h = ByteArray(16)
        f.readFully(h)
        String(h, Charsets.ISO_8859_1) != "SQLite format 3\u0000"
      }
    }
  }.getOrDefault(false)

  // ---- the health check ---------------------------------------------------------------------------------------------------

  /**
   * Checks the main tables, then the full-text index, and repairs the full-text index in place when that is all that is wrong.
   * Throws [Damaged] (or SQLite's own CORRUPT) when a main table cannot be trusted.
   *
   * `PRAGMA quick_check` used to do both in one go: since SQLite 3.44 it also runs the FTS5 integrity check, so a full-text
   * problem looked like a damaged index and the whole file was deleted. Naming the tables keeps the two apart.
   */
  fun check(db: Db, log: (String) -> Unit) {
    val tables = ArrayList<String>()
    db.query(
      "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND name != 'fts' AND name NOT LIKE 'fts\\_%' ESCAPE '\\'",
    ) { tables += it.getText(0) }
    for (table in tables) {
      val rows = ArrayList<String>()
      db.query("PRAGMA quick_check(\"$table\")") { rows += it.getText(0) }
      if (rows.firstOrNull() != "ok") throw Damaged("table $table failed its check: ${rows.firstOrNull() ?: "no answer"}")
    }
    val problem = ftsFailure(db) ?: return
    log("full-text index is inconsistent (${FindrLog.brief(problem)}); rebuilding it from the stored words, files and vectors are kept")
    try {
      db.transaction { db.run("INSERT INTO fts(fts) VALUES('rebuild')") }
    } catch (t: Throwable) {
      if (isDamage(t)) throw Damaged("full-text rebuild failed (${FindrLog.brief(t)})", t)
      throw t
    }
    ftsFailure(db)?.let { throw Damaged("full-text index is still inconsistent after a rebuild (${FindrLog.brief(it)})", it) }
    log("full-text index rebuilt")
    Diag.handled(HandledKind.INDEX_REPAIRED, problem)
  }

  /** The FTS5 integrity check (it compares the inverted index with the stored text). Null: fine. A busy database is thrown, not reported. */
  private fun ftsFailure(db: Db): Throwable? = try {
    db.run("INSERT INTO fts(fts, rank) VALUES('integrity-check', 1)"); null
  } catch (t: Throwable) {
    if (isDamage(t)) t else throw t
  }

  // ---- salvage ----------------------------------------------------------------------------------------------------------

  const val SALVAGE_SUFFIX = ".salvage"

  class Salvaged(val files: Long, val segments: Long, val unreadable: Long)

  /**
   * Copies whatever [file] still lets us read into a fresh index, checks that, and swaps it in. Returns null (and leaves [file]
   * alone, unless the swap itself failed) when nothing could be read or the new file does not pass its own check. Rows are copied
   * in id ranges; a range that fails is halved until the single unreadable rows are known. If any segment or word row was
   * unreadable, the files around it go back to the start of the queue (their segments are replaced when they are read again), so
   * no file is left looking done with a piece missing. Files the damage hid entirely are found again by the next crawl.
   */
  fun salvage(file: File, driver: SQLiteDriver, log: (String) -> Unit): Salvaged? {
    val tmp = File(file.path + SALVAGE_SUFFIX)
    deleteWithSidecars(tmp)
    val db = Db(driver.open(tmp.path))
    var closed = false
    var oldGone = false
    var done = false
    try {
      db.exec("PRAGMA journal_mode = DELETE")
      db.exec("PRAGMA synchronous = OFF")
      db.exec("PRAGMA foreign_keys = ON")
      db.exec("PRAGMA secure_delete = ON")
      db.transaction {
        Schema.CREATE.forEach { db.exec(it) }
        db.run("INSERT INTO meta(key, value) VALUES(?, ?)", Schema.Meta.SCHEMA, Schema.VERSION.toString())
        db.exec("PRAGMA user_version = ${Schema.VERSION}")
      }
      db.exec("ATTACH DATABASE '${file.path.replace("'", "''")}' AS old")
      val files = copyRanges(db, "SELECT MIN(id), MAX(id) FROM old.files", columnsOf(db, "files")?.let { cols ->
        "INSERT OR IGNORE INTO main.files($cols) SELECT $cols FROM old.files WHERE id BETWEEN ? AND ?"
      })
      val segments = copyRanges(db, "SELECT MIN(id), MAX(id) FROM old.segments", columnsOf(db, "segments")?.let { cols ->
        "INSERT OR IGNORE INTO main.segments($cols) SELECT $cols FROM old.segments WHERE id BETWEEN ? AND ? AND file IN (SELECT id FROM main.files)"
      })
      // The words: the text lives only in fts_content (name and body as c0 and c1), so a word row that cannot be read is a segment without words.
      val words = copyRanges(db, "SELECT MIN(id), MAX(id) FROM old.fts_content",
        "INSERT OR IGNORE INTO main.fts(rowid, name, body) SELECT id, c0, c1 FROM old.fts_content WHERE id BETWEEN ? AND ? AND id IN (SELECT id FROM main.segments)")
      runCatching {
        db.exec("INSERT OR REPLACE INTO meta(key, value) SELECT key, value FROM old.meta WHERE key NOT IN ('${Schema.Meta.SCHEMA}', '${Schema.Meta.VEC_GEN}', '${Schema.Meta.VEC_GEN_QUICK}')")
      }
      // The vector files trust a generation they settled at; a number past anything the old index reached makes them reload.
      for (key in listOf(Schema.Meta.VEC_GEN, Schema.Meta.VEC_GEN_QUICK)) {
        val old = runCatching { db.long("SELECT CAST(value AS INTEGER) FROM old.meta WHERE key = ?", key) }.getOrNull() ?: 0L
        db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, (old + 1_000_000L).toString())
      }
      db.exec("UPDATE files SET date_ms = mtime WHERE date_ms = 0")
      val lost = segments.lostIds + words.lostIds
      if (segments.blind || words.blind) db.exec("UPDATE files SET state = 'new', detail = NULL WHERE $READ_STATES")
      else if (lost.isNotEmpty()) requeueAround(db, lost)
      restoreNames(db)
      db.exec("DETACH DATABASE old")
      if (files.copied == 0L) { log("nothing in the damaged index could be read"); return null }
      check(db, log)
      val out = Salvaged(files.copied, segments.copied, files.lostIds.size + lost.size.toLong())
      db.close(); closed = true
      // Only now does the old file go: its write-ahead log and shared memory belong to it and must not pair with the new file.
      deleteWithSidecars(file); oldGone = true
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
      done = true
      return out
    } catch (t: Throwable) {
      log("salvage failed (${FindrLog.brief(t)})")
      return null
    } finally {
      if (!closed) runCatching { db.close() }
      if (!done && !oldGone) deleteWithSidecars(tmp)
    }
  }

  /** [blind]: not even the table's lowest and highest id could be read, so nothing is known about what it held. */
  private class Copied(val copied: Long, val lostIds: List<Long>, val blind: Boolean = false)

  /** The columns old.[table] and main.[table] share, as a comma list; null when they share none. */
  private fun columnsOf(db: Db, table: String): String? {
    val have = HashSet<String>()
    runCatching { db.query("PRAGMA old.table_info($table)") { have += it.getText(1) } }
    val shared = ArrayList<String>()
    db.query("PRAGMA main.table_info($table)") { if (it.getText(1) in have) shared += it.getText(1) }
    return shared.takeIf { "id" in it }?.joinToString(", ")
  }

  /**
   * Runs [insert] (two parameters: the lowest and highest id) over every id range of a table, halving a range that fails until
   * the single unreadable ids are known. [bounds] reads the table's lowest and highest id; if even that fails, nothing is copied.
   */
  private fun copyRanges(db: Db, bounds: String, insert: String?): Copied {
    if (insert == null) return Copied(0, emptyList())
    var lo = 0L; var hi = -1L
    try {
      db.query(bounds) { if (!it.isNull(0)) { lo = it.getLong(0); hi = it.getLong(1) } }
    } catch (t: Throwable) { return Copied(0, emptyList(), blind = true) }
    var copied = 0L
    val lost = ArrayList<Long>()
    fun attempt(a: Long, b: Long) {
      try {
        db.run(insert, a, b)
        copied += db.long("SELECT changes()") ?: 0
      } catch (t: Throwable) {
        if (a >= b) lost += a else { val mid = a + (b - a) / 2; attempt(a, mid); attempt(mid + 1, b) }
      }
    }
    var a = lo
    while (a <= hi) { attempt(a, minOf(hi, a + RANGE - 1)); a += RANGE }
    return Copied(copied, lost)
  }

  /**
   * Every file that is not turned off keeps a name segment with its name and folder words (as [IndexStore.upsertFromCrawl] makes
   * them), so a file whose own segments were unreadable is still found by its name while it waits to be read again.
   */
  private fun restoreNames(db: Db) {
    db.exec(
      "INSERT INTO segments(file, kind, modality) SELECT id, 'name', 'none' FROM files WHERE state != 'skipped' " +
        "AND NOT EXISTS (SELECT 1 FROM segments s WHERE s.file = files.id AND s.kind = 'name')",
    )
    db.exec(
      "INSERT INTO fts(rowid, name, body) SELECT s.id, f.name, REPLACE(TRIM(f.rel_path, '/'), '/', ' ') FROM segments s " +
        "JOIN files f ON f.id = s.file WHERE s.kind = 'name' AND s.id NOT IN (SELECT id FROM fts_content)",
    )
  }

  /** Sends the files that own the segments next to each unreadable id back to the start of the queue. */
  private fun requeueAround(db: Db, lost: List<Long>) {
    if (lost.size > MAX_LOST_TO_TRACE) {
      db.exec("UPDATE files SET state = 'new', detail = NULL WHERE $READ_STATES")
      return
    }
    for (id in lost) {
      db.run(
        "UPDATE files SET state = 'new', detail = NULL WHERE $READ_STATES AND id IN (" +
          "SELECT file FROM (SELECT file FROM segments WHERE id <= ?1 ORDER BY id DESC LIMIT 1) UNION " +
          "SELECT file FROM (SELECT file FROM segments WHERE id >= ?1 ORDER BY id LIMIT 1))",
        id,
      )
    }
    // A file whose every segment was on the lost pages has no neighbour left to point at it: it shows as a read file with nothing
    // but its name (a file that is findable by name only, [IndexStore.NAME_ONLY], really is like that and stays done).
    db.exec(
      "UPDATE files SET state = 'new', detail = NULL WHERE $READ_STATES AND COALESCE(detail, '') != '${IndexStore.NAME_ONLY}' " +
        "AND NOT EXISTS (SELECT 1 FROM segments s WHERE s.file = files.id AND s.kind != 'name')",
    )
  }

  /** The files that count as read (all or in part): the ones a lost piece can make wrong, and so the ones that go back in the queue. */
  private const val READ_STATES = "state IN ('understood', 'partial')"
  private const val RANGE = 512L
  private const val MAX_LOST_TO_TRACE = 4000

  fun deleteWithSidecars(file: File) {
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
  }
}
