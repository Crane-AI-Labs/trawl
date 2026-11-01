package com.craneai.findr.index

import androidx.sqlite.SQLiteException
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** B5 (UX bugs 1.0.5): a full disk has to be told apart from a bad file, so it can be said plainly and not retried in a loop. */
class StorageFullTest {
  /** On the Android host target the platform stub of the exception drops its text: the message cases only mean something where it is kept. */
  private fun needsMessages() = assumeTrue(SQLiteException("probe").message == "probe")

  @Test fun sqliteFullByCode() {
    needsMessages()
    assertTrue(StorageFull.isFull(SQLiteException("Error code: 13, message: database or disk is full")))
    assertTrue(StorageFull.isFull(SQLiteException("Error code: 13")))
  }

  @Test fun sqliteFullByWordsAlone() {
    assertTrue(StorageFull.isFull(RuntimeException("database or disk is full (code 13 SQLITE_FULL)")))
  }

  @Test fun noSpaceWhileWritingAFile() {
    assertTrue(StorageFull.isFull(IOException("write failed: ENOSPC (No space left on device)")))
    assertTrue(StorageFull.isFull(FileNotFoundException("/data/x (No space left on device)")))
  }

  @Test fun foundThroughTheCauseChain() {
    needsMessages()
    assertTrue(StorageFull.isFull(IllegalStateException("could not save", RuntimeException("wrapped", SQLiteException("Error code: 13")))))
  }

  @Test fun otherFailuresAreNotAFullDisk() {
    assertFalse(StorageFull.isFull(SQLiteException("Error code: 5, message: database is locked")))   // busy
    assertFalse(StorageFull.isFull(SQLiteException("Error code: 11, message: database disk image is malformed")))   // corrupt
    assertFalse(StorageFull.isFull(IOException("Permission denied")))
    assertFalse(StorageFull.isFull(IllegalArgumentException("bad file")))
    assertFalse(StorageFull.isFull(OutOfMemoryError()))
  }

  @Test fun aCauseLoopDoesNotHang() {
    val a = RuntimeException("a")
    assertFalse(StorageFull.isFull(a.also { it.initCause(null) }))
  }
}
