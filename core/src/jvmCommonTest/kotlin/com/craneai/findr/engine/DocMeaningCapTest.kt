package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocMeaningCapTest {
  @Test fun ordinaryDocumentsCapAtSixtyChunks() {
    for (name in listOf("report.pdf", "notes.txt", "thesis.docx", "README.md", "book.epub", "budget.xlsx", "saved_posts.json", "notes.csv")) {
      assertFalse(name, DocMeaningCap.isMachineData(name))
      assertEquals(name, DocMeaningCap.DOC_MAX_CHUNKS, DocMeaningCap.maxEmbeddedChunks(name, FileKind.DOCUMENT))
    }
    assertEquals(DocMeaningCap.DOC_MAX_CHUNKS, DocMeaningCap.maxEmbeddedChunks("scan.pdf", FileKind.PDF))
  }

  @Test fun machineDataFormatsCapAtSmallOpeningHead() {
    for (name in listOf("server.log", "AUTH.LOG", "diagram.svg", "icon.svgz")) {
      assertTrue(name, DocMeaningCap.isMachineData(name))
      assertEquals(name, DocMeaningCap.MACHINE_DATA_MAX_CHUNKS, DocMeaningCap.maxEmbeddedChunks(name, FileKind.DOCUMENT))
    }
  }

  @Test fun nonDocumentKindsAreNotCappedByDocumentRule() {
    assertEquals(Int.MAX_VALUE, DocMeaningCap.maxEmbeddedChunks("photo.jpg", FileKind.IMAGE))
    assertEquals(Int.MAX_VALUE, DocMeaningCap.maxEmbeddedChunks("clip.mp4", FileKind.VIDEO))
    assertEquals(Int.MAX_VALUE, DocMeaningCap.maxEmbeddedChunks("voice.m4a", FileKind.AUDIO))
  }
}
