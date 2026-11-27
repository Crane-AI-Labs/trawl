package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import trawl.desktop.ui.FileDragHost
import trawl.desktop.ui.FileListTransferable
import trawl.desktop.ui.FileTypeVisual
import trawl.desktop.ui.FocusZone
import trawl.desktop.ui.K
import trawl.desktop.ui.KeyAction
import trawl.desktop.ui.KeyContext
import trawl.desktop.ui.KeyMap
import trawl.desktop.ui.Keystroke

class Wave2UxTest {
  @Test fun fileSubTypesCoverDocumentsPhotosVideosAndAudioCleanly() {
    assertTrue(FileSubType.forFilter(KindFilter.ALL).isEmpty())
    val docTypes = FileSubType.forFilter(KindFilter.DOCUMENTS)
    assertTrue(FileSubType.PDF in docTypes && FileSubType.WORD in docTypes && FileSubType.SPREADSHEET in docTypes && FileSubType.PRESENTATION in docTypes)
    assertTrue(FileSubType.PDF.matches("Quarterly_Report.PDF"))
    assertFalse(FileSubType.PDF.matches("Quarterly_Report.docx"))
    assertTrue(FileSubType.WORD.matches("Contract.docx"))
    assertTrue(FileSubType.SPREADSHEET.matches("Budget_2026.xlsx"))
    assertTrue(FileSubType.SPREADSHEET.matches("export.csv"))
    assertTrue(FileSubType.PRESENTATION.matches("PitchDeck.pptx"))
    assertTrue(FileSubType.WAV.matches("voice_note.wav"))
    assertTrue(FileSubType.MP4.matches("clip.mp4"))
    assertTrue(FileSubType.PNG.matches("screenshot.png"))
  }

  @Test fun fileTypeVisualAssignsDistinctColorsAndAlignedTags() {
    val pdf = FileTypeVisual.of("invoice.pdf", FileKind.PDF)
    val docx = FileTypeVisual.of("transfer.docx", FileKind.DOCUMENT)
    val xlsx = FileTypeVisual.of("ledger.xlsx", FileKind.DOCUMENT)
    val pptx = FileTypeVisual.of("slides.pptx", FileKind.DOCUMENT)
    val wav = FileTypeVisual.of("output.wav", FileKind.AUDIO)
    val mp4 = FileTypeVisual.of("demo.mp4", FileKind.VIDEO)

    assertEquals("PDF", pdf?.tag)
    assertEquals("DOCX", docx?.tag)
    assertEquals("XLSX", xlsx?.tag)
    assertEquals("PPTX", pptx?.tag)
    assertEquals("WAV", wav?.tag)
    assertEquals("MP4", mp4?.tag)

    val lightColors = setOf(pdf!!.lightAccent, docx!!.lightAccent, xlsx!!.lightAccent, pptx!!.lightAccent, wav!!.lightAccent, mp4!!.lightAccent)
    assertEquals(6, lightColors.size, "each major file format family has a distinct accent color")
    val darkColors = setOf(pdf.darkAccent, docx.darkAccent, xlsx.darkAccent, pptx.darkAccent, wav.darkAccent, mp4.darkAccent)
    assertEquals(6, darkColors.size, "dark mode accent colors are distinct too")
  }

  @Test fun backspaceAndEscapeExitFindSimilarWhenQueryIsBlank() {
    val ctxSearch = KeyContext(Screen.MAIN, FocusZone.SEARCH, quickLook = false, queryBlank = true, hasResults = true, isMac = true, similar = true)
    val ctxList = ctxSearch.copy(zone = FocusZone.LIST)
    assertEquals(KeyAction.ClearSimilar, KeyMap.map(Keystroke(K.BACKSPACE), ctxSearch))
    assertEquals(KeyAction.ClearSimilar, KeyMap.map(Keystroke(K.BACKSPACE), ctxList))
    assertEquals(KeyAction.ClearSimilar, KeyMap.map(Keystroke(K.ESCAPE), ctxSearch))
    assertEquals(KeyAction.ClearSimilar, KeyMap.map(Keystroke(K.ESCAPE), ctxList))

    val normal = ctxSearch.copy(similar = false)
    assertNull(KeyMap.map(Keystroke(K.BACKSPACE), normal), "outside Find similar, Backspace stays with the search field")
  }

  @Test fun fileDragHostAndTransferableCarryRealFilesAndPaths() {
    val tmp = Files.createTempFile("trawl-drag-", ".txt").toFile()
    try {
      val host = FileDragHost()
      assertNull(host.takeArmed())
      host.arm(listOf(tmp))
      val taken = host.takeArmed()
      assertEquals(listOf(tmp), taken)
      assertNull(host.takeArmed(), "taking armed files consumes the arming")

      val xfer = FileListTransferable(listOf(tmp))
      assertTrue(xfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
      assertTrue(xfer.isDataFlavorSupported(DataFlavor.stringFlavor))
      assertEquals(listOf(tmp), xfer.getTransferData(DataFlavor.javaFileListFlavor))
      assertEquals(tmp.absolutePath, xfer.getTransferData(DataFlavor.stringFlavor))

      val singleGhost = trawl.desktop.ui.renderDragGhost(listOf(tmp))
      assertTrue(singleGhost.width > 0 && singleGhost.height > 0)
      val multiGhost = trawl.desktop.ui.renderDragGhost(listOf(tmp, tmp, tmp))
      assertTrue(multiGhost.width > 0 && multiGhost.height > 0)
    } finally {
      tmp.delete()
    }
  }

  @Test fun pdfThumbsRendersAndCachesFirstPageOfRealPdf() {
    val tmp = Files.createTempFile("trawl-pdf-", ".pdf").toFile()
    try {
      PDDocument().use { doc ->
        doc.addPage(PDPage())
        doc.save(tmp)
      }
      assertNull(PdfThumbs.peek(tmp, page = 1, side = 320))
      val bmp = PdfThumbs.load(tmp, page = 1, side = 320)
      assertNotNull(bmp, "PDFBox renders page 1 of a real PDF")
      assertTrue(bmp.width > 0 && bmp.height > 0)
      assertNotNull(PdfThumbs.peek(tmp, page = 1, side = 320), "rendered PDF page is cached for instant revisit")
    } finally {
      tmp.delete()
    }
  }
}
