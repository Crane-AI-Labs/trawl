package com.craneai.findr.index

import com.craneai.findr.core.IndexOpenProblem

/** Turns the error from a failed [IndexStore.open] into a reason class for the UI, by SQLite's primary result code. */
object IndexOpenProblems {
  fun classify(t: Throwable): IndexOpenProblem {
    if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) return IndexOpenProblem.MEMORY
    return when (IndexRecovery.code(t)) {
      5, 6 -> IndexOpenProblem.BUSY            // SQLITE_BUSY, SQLITE_LOCKED
      13 -> IndexOpenProblem.STORAGE_FULL      // SQLITE_FULL
      7 -> IndexOpenProblem.MEMORY             // SQLITE_NOMEM
      8, 10, 14, 15 -> IndexOpenProblem.IO     // READONLY, IOERR, CANTOPEN, PROTOCOL
      else -> IndexOpenProblem.OTHER
    }
  }
}
