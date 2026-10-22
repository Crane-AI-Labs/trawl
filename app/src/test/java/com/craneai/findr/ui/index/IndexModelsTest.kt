package com.craneai.findr.ui.index

import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.KindProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexModelsTest {
  @Test fun documentsAndPdfsReadAsOneRow() {
    val status = IndexStatus(
      filesKnown = 30,
      byKind = mapOf(
        FileKind.DOCUMENT to KindProgress(known = 10, understood = 4),
        FileKind.PDF to KindProgress(known = 20, understood = 6),
      ),
    )
    val row = status.kindRows().single()
    assertEquals(R.string.kind_plural_documents, row.label)
    assertEquals(30, row.known)
    assertEquals(10, row.understood)
  }

  @Test fun rowsFollowReadingOrderAndSkipEmptyKinds() {
    val status = IndexStatus(
      byKind = mapOf(
        FileKind.VIDEO to KindProgress(2, 0),
        FileKind.SCREENSHOT to KindProgress(5, 5),
        FileKind.IMAGE to KindProgress(9, 1),
        FileKind.AUDIO to KindProgress(0, 0),
      ),
    )
    assertEquals(
      listOf(R.string.kind_plural_screenshots, R.string.kind_plural_photos, R.string.kind_plural_videos),
      status.kindRows().map { it.label },
    )
  }

  @Test fun fractionsNeverExceedOne() {
    val status = IndexStatus(filesKnown = 10, filesSearchable = 10, filesUnderstood = 4)
    assertEquals(1f, status.searchableFraction, 0f)
    assertEquals(0.4f, status.understoodFraction, 0.0001f)
    assertEquals(0f, IndexStatus().understoodFraction, 0f)
  }

  @Test fun filesThatCouldNotBeReadAreFinalSoTheBarEndsAtAHundredPercent() {
    val status = IndexStatus(filesKnown = 100, filesSearchable = 100, filesUnderstood = 90, filesUnreadable = 10)
    assertEquals(90, status.filesReadable)
    assertEquals(1f, status.understoodFraction, 0f)
    assertEquals(100, status.percentUnderstood())
    assertEquals(1f, status.searchableFraction, 0f)
  }
}
