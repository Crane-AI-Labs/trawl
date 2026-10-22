package com.craneai.findr.index

import androidx.sqlite.SQLiteException
import com.craneai.findr.core.IndexOpenProblem
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/** The reason class shown to the user when the index cannot be opened: by SQLite's primary result code, never by file name. */
class IndexOpenProblemsTest {
  /** The android.jar stubs of the Android host tests drop exception text (the code travels in it): those tests run on the jvm target. */
  private fun needsRealMessages() = Assume.assumeTrue("exception text survives", SQLiteException("probe").message == "probe")

  private fun err(code: Int) = SQLiteException("Error code: $code, message: whatever /data/user/0/x/index.db")

  @Test fun busyAndLockedAreBusy() {
    needsRealMessages()
    assertEquals(IndexOpenProblem.BUSY, IndexOpenProblems.classify(err(5)))
    assertEquals(IndexOpenProblem.BUSY, IndexOpenProblems.classify(err(6)))
  }

  @Test fun aFullDiskIsStorageFull() {
    needsRealMessages()
    assertEquals(IndexOpenProblem.STORAGE_FULL, IndexOpenProblems.classify(err(13)))
  }

  @Test fun ioAndCannotOpenAreIo() {
    needsRealMessages()
    for (c in listOf(8, 10, 14, 15)) assertEquals("code $c", IndexOpenProblem.IO, IndexOpenProblems.classify(err(c)))
  }

  @Test fun outOfMemoryIsMemory() {
    if (SQLiteException("probe").message == "probe") assertEquals(IndexOpenProblem.MEMORY, IndexOpenProblems.classify(err(7)))
    assertEquals(IndexOpenProblem.MEMORY, IndexOpenProblems.classify(OutOfMemoryError()))
    assertEquals(IndexOpenProblem.MEMORY, IndexOpenProblems.classify(RuntimeException("wrapped", OutOfMemoryError())))
  }

  @Test fun extendedCodesUseThePrimaryCode() {
    needsRealMessages()
    // SQLITE_BUSY_SNAPSHOT = 5 | (2 << 8), SQLITE_IOERR_WRITE = 10 | (3 << 8), SQLITE_FULL = 13
    assertEquals(IndexOpenProblem.BUSY, IndexOpenProblems.classify(err(5 or (2 shl 8))))
    assertEquals(IndexOpenProblem.IO, IndexOpenProblems.classify(err(10 or (3 shl 8))))
  }

  @Test fun anythingElseIsOther() {
    if (SQLiteException("probe").message == "probe") assertEquals(IndexOpenProblem.OTHER, IndexOpenProblems.classify(err(1)))
    assertEquals(IndexOpenProblem.OTHER, IndexOpenProblems.classify(IllegalStateException("no code at all")))
  }
}
