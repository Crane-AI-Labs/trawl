package com.craneai.findr.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FileActions.pathFromIndex] is the pure half of Copy's fix: the real on-disk path Copy wraps with Findr's own
 * FileProvider ([FileActions.clipboardUri]), instead of putting the raw MediaStore content URI on the clipboard (not
 * reliably grantable to the app that pastes it, found on Android 16). The rest of [FileActions.clipboardUri] and
 * [FileActions.copy] need a real `Context`/`ContentResolver`, so they are exercised on the phone, not here (same
 * split as the quick panel's copy of this logic before the fix unified them: see `QuickPanelLogicTest`'s history).
 */
class FileActionsTest {
  @Test fun `path from the index joins root folder and name`() {
    assertEquals(
      "/storage/emulated/0/Pictures/FindrTest/cat.jpg",
      FileActions.pathFromIndex("/storage/emulated/0", "Pictures/FindrTest/", "cat.jpg"),
    )
  }

  @Test fun `path from the index accepts a relative path that already ends in the name`() {
    assertEquals(
      "/storage/emulated/0/Download/receipt.pdf",
      FileActions.pathFromIndex("/storage/emulated/0/", "Download/receipt.pdf", "receipt.pdf"),
    )
  }

  @Test fun `path from the index at the volume root`() {
    assertEquals("/storage/emulated/0/note.txt", FileActions.pathFromIndex("/storage/emulated/0", "", "note.txt"))
  }
}

/** [FileActions.startPosition]: when a result's moment is passed to the player as its start position. */
class StartPositionTest {
  @Test fun `a video result starts a second before its moment`() {
    assertEquals(39_000, FileActions.startPosition(com.craneai.findr.core.FileKind.VIDEO, 40_000L))
  }

  @Test fun `a recording starts a second before its moment too`() {
    assertEquals(94_500, FileActions.startPosition(com.craneai.findr.core.FileKind.AUDIO, 95_500L))
  }

  @Test fun `no moment, the start, or a moment past an Int passes nothing`() {
    assertEquals(null, FileActions.startPosition(com.craneai.findr.core.FileKind.VIDEO, null))
    assertEquals(null, FileActions.startPosition(com.craneai.findr.core.FileKind.VIDEO, 0L))
    assertEquals(null, FileActions.startPosition(com.craneai.findr.core.FileKind.VIDEO, 1_000L))   // a second in: the lead reaches 0, so play from the start
    assertEquals(1, FileActions.startPosition(com.craneai.findr.core.FileKind.VIDEO, 1_001L))
    assertEquals(null, FileActions.startPosition(com.craneai.findr.core.FileKind.VIDEO, Int.MAX_VALUE + 1_001L))
  }

  @Test fun `a photo or document never gets a start position`() {
    assertEquals(null, FileActions.startPosition(com.craneai.findr.core.FileKind.IMAGE, 5_000L))
    assertEquals(null, FileActions.startPosition(com.craneai.findr.core.FileKind.PDF, 5_000L))
  }
}
