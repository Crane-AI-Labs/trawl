package com.craneai.findr.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * A few conveniences over `SQLiteConnection` so the store reads like SQL, not like cursor plumbing.
 * Binds are `Long`, `Int`, `Double`, `String`, `ByteArray` or null. A connection is not thread-safe: the store
 * guards each one (one writer, a small pool of readers).
 */
class Db(val connection: SQLiteConnection) {

  private val statements = LinkedHashMap<String, SQLiteStatement>(CACHE_CAPACITY, 0.75f, true)
  private var closed = false

  /** Visible for tests: number of prepared statements currently held idle in this connection's LRU cache. */
  internal val cachedStatementCount: Int get() = statements.size

  fun exec(sql: String) {
    // DETACH DATABASE fails with SQLITE_BUSY if any cached statement still references the attached DB.
    if (sql.trimStart().startsWith("DETACH", ignoreCase = true)) clearStatementCache()
    connection.execSQL(sql)
  }

  private fun checkout(sql: String): SQLiteStatement {
    if (sql.length <= MAX_CACHED_SQL_LEN) {
      // Remove on checkout so re-entrant calls with identical SQL never collide on an in-flight statement.
      statements.remove(sql)?.let { return it }
    }
    return connection.prepare(sql)
  }

  private fun release(sql: String, statement: SQLiteStatement, failed: Boolean) {
    if (failed || closed || sql.length > MAX_CACHED_SQL_LEN) {
      runCatching { statement.close() }
      return
    }
    val resetOk = runCatching {
      statement.reset()
      statement.clearBindings()
    }.isSuccess
    if (!resetOk) {
      runCatching { statement.close() }
      return
    }
    val displaced = statements.put(sql, statement)
    if (displaced != null && displaced !== statement) runCatching { displaced.close() }
    while (statements.size > MAX_CACHED_STATEMENTS) {
      val eldest = statements.entries.iterator()
      if (!eldest.hasNext()) break
      val evicted = eldest.next().value
      eldest.remove()
      runCatching { evicted.close() }
    }
  }

  private fun clearStatementCache() {
    val iter = statements.values.iterator()
    while (iter.hasNext()) {
      val stmt = iter.next()
      iter.remove()
      runCatching { stmt.close() }
    }
  }

  private fun bind(statement: SQLiteStatement, args: Array<out Any?>) {
    args.forEachIndexed { i, arg ->
      val index = i + 1
      when (arg) {
        null -> statement.bindNull(index)
        is Long -> statement.bindLong(index, arg)
        is Int -> statement.bindLong(index, arg.toLong())
        is Boolean -> statement.bindLong(index, if (arg) 1 else 0)
        is Double -> statement.bindDouble(index, arg)
        is String -> statement.bindText(index, arg)
        is ByteArray -> statement.bindBlob(index, arg)
        else -> error("cannot bind ${arg::class}")
      }
    }
  }

  /** Runs a statement that returns no rows. */
  fun run(sql: String, vararg args: Any?) {
    val statement = checkout(sql)
    var failed = false
    try {
      bind(statement, args)
      statement.step()
    } catch (t: Throwable) {
      failed = true
      throw t
    } finally {
      release(sql, statement, failed)
    }
  }

  /** Runs a query, calling [row] for each result row. */
  fun query(sql: String, vararg args: Any?, row: (SQLiteStatement) -> Unit) {
    val statement = checkout(sql)
    var failed = false
    try {
      bind(statement, args)
      while (statement.step()) row(statement)
    } catch (t: Throwable) {
      failed = true
      throw t
    } finally {
      release(sql, statement, failed)
    }
  }

  fun long(sql: String, vararg args: Any?): Long? {
    var out: Long? = null
    query(sql, *args) { if (!it.isNull(0)) out = it.getLong(0) }
    return out
  }

  fun text(sql: String, vararg args: Any?): String? {
    var out: String? = null
    query(sql, *args) { if (!it.isNull(0)) out = it.getText(0) }
    return out
  }

  fun lastInsertId(): Long = long("SELECT last_insert_rowid()") ?: -1

  /** One write transaction: all or nothing. Nested calls join the outer one. */
  fun <T> transaction(block: () -> T): T {
    if (inTransaction) return block()
    exec("BEGIN IMMEDIATE")
    inTransaction = true
    try {
      val result = block()
      exec("COMMIT")
      return result
    } catch (t: Throwable) {
      runCatching { exec("ROLLBACK") }
      throw t
    } finally {
      inTransaction = false
    }
  }

  private var inTransaction = false

  fun close() {
    closed = true
    clearStatementCache()
    connection.close()
  }

  internal companion object {
    const val MAX_CACHED_STATEMENTS = 32
    const val MAX_CACHED_SQL_LEN = 512
    private const val CACHE_CAPACITY = 48
  }
}

fun SQLiteStatement.textOrNull(column: Int): String? = if (isNull(column)) null else getText(column)
fun SQLiteStatement.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
fun SQLiteStatement.intOrNull(column: Int): Int? = if (isNull(column)) null else getLong(column).toInt()
