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
