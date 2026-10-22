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

  fun exec(sql: String) = connection.execSQL(sql)

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
    val statement = connection.prepare(sql)
    try {
      bind(statement, args)
      statement.step()
    } finally {
      statement.close()
    }
  }

  /** Runs a query, calling [row] for each result row. */
  fun query(sql: String, vararg args: Any?, row: (SQLiteStatement) -> Unit) {
    val statement = connection.prepare(sql)
    try {
      bind(statement, args)
      while (statement.step()) row(statement)
    } finally {
      statement.close()
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

  fun close() = connection.close()
}

fun SQLiteStatement.textOrNull(column: Int): String? = if (isNull(column)) null else getText(column)
fun SQLiteStatement.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
fun SQLiteStatement.intOrNull(column: Int): Int? = if (isNull(column)) null else getLong(column).toInt()
