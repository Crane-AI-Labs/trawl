package com.craneai.findr.index

import java.io.IOException

/**
 * Did this failure happen because the phone's storage is full? A write to the index (SQLITE_FULL, result code 13), or a file that
 * cannot be written (ENOSPC). Told apart from every other failure because the fix is not about the file being read: the file is
 * fine and must not be marked failed, and trying the next file only fails the same way. The run stops, the person is told, and
 * it carries on after they free some space.
 */
object StorageFull {
  fun isFull(t: Throwable): Boolean =
    generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8).any { c ->
      val m = c.message.orEmpty()
      c.javaClass.simpleName == "SQLiteFullException" ||
        IndexRecovery.code(c) == FULL ||
        m.contains("database or disk is full", ignoreCase = true) ||
        (c is IOException && (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)))
    }

  /** SQLITE_FULL. */
  private const val FULL = 13
}
