package com.craneai.findr.extract

import com.craneai.findr.engine.Chunker
import com.craneai.findr.extract.ZipFixtures.SS
import com.craneai.findr.extract.ZipFixtures.R
import com.craneai.findr.extract.ZipFixtures.W
import com.craneai.findr.extract.ZipFixtures.extract
import com.craneai.findr.extract.ZipFixtures.parser
import com.craneai.findr.extract.ZipFixtures.zip
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** B4: a corrupt or hostile file ends as partial or unreadable, with a reason, in bounded time. */
class ReadLimitsTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun deadlineThrowsOnlyOncePastTheBudget() {
    var now = 0L
    val deadline = ExtractDeadline(budgetMs = 40_000, now = { now })
    deadline.check()
    now = 39_999L * 1_000_000
    deadline.check()
    now = 40_001L * 1_000_000
    try {
      deadline.check()
      fail("past the budget")
    } catch (e: ExtractTimeout) {
      assertEquals("took too long", e.message)
    }
  }

  /**
   * The reported file: a docx whose one text node is 20 MB of letters (21 KB zipped, it inflates about 1000 to 1), no spaces.
   * The entry fits the inflate limit, so the extractor keeps the first 2 million characters and says the file was cut; the
   * chunking that followed it used to run for minutes. Both steps are timed together here.
   */
  @Test fun aTwentyMegabyteTextNodeWithNoSpacesReadsAsPartialInBoundedTime() {
    val file = tmp.newFile("bomb4.docx")
    ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("word/document.xml"))
      zip.write("<w:document $W><w:body><w:p><w:r><w:t>".toByteArray())
      val block = "A".repeat(1_000_000).toByteArray()
      repeat(20) { zip.write(block) }
      zip.write("</w:t></w:r></w:p></w:body></w:document>".toByteArray())
      zip.closeEntry()
    }
    assertTrue("test file stays small: ${file.length()}", file.length() < 200_000)
    val t0 = System.nanoTime()
    val result = extract(DocFormat.DOCX, file, maxChars = 2_000_000)
    val chunks = result.pages.flatMap { Chunker.chunks(it) }
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertTrue("took $ms ms", ms < 20_000)
    assertTrue(result.truncated)
    assertEquals(2_000_000, result.pages.sumOf { it.length })
    assertTrue(chunks.size in 5_000..6_000)
  }

  @Test fun aHugeTextWithNoSpacesChunksQuickly() {
    val text = "x".repeat(2_000_000)
    val t0 = System.nanoTime()
    val chunks = Chunker.chunks(text)
    assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 10_000)
    assertTrue(chunks.size in 5_000..6_000)
  }

  /** A document whose time is up keeps the text read so far, is marked cut, and says why. */
  @Test fun docxOutOfTimeKeepsTheTextReadSoFarAndSaysWhy() {
    val body = (1..20_000).joinToString("") { "<w:p><w:r><w:t>paragraph number $it</w:t></w:r></w:p>" }
    val file = zip(tmp.root, "long.docx", "word/document.xml" to "<w:document $W><w:body>$body</w:body></w:document>")
    var ticks = 0
    val result = OfficeText.extract(DocFormat.DOCX, file, 2_000_000, parser) { if (++ticks > 3) throw ExtractTimeout() }
    val text = result.pages.joinToString("\n")
    assertTrue("partial text kept", text.startsWith("paragraph number 1"))
    assertFalse("the end was not reached", text.contains("paragraph number 20000"))
    assertTrue(result.truncated)
    assertEquals("took too long", result.stopped)
  }

  /** Time up where no partial text is held (between the sheets of a workbook): unreadable, with the same reason, never an exception. */
  @Test fun xlsxOutOfTimeIsUnreadableWithAReason() {
    val file = zip(
      tmp.root, "a.xlsx",
      "xl/workbook.xml" to """<workbook $SS $R><sheets><sheet name="One" sheetId="1" r:id="rId1"/></sheets></workbook>""",
      "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/></Relationships>""",
      "xl/worksheets/sheet1.xml" to """<worksheet $SS><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>x</t></is></c></row></sheetData></worksheet>""",
    )
    val result = OfficeText.extract(DocFormat.XLSX, file, 1_000_000, parser) { throw ExtractTimeout() }
    assertTrue(result.pages.isEmpty())
    assertTrue(result.truncated)
    assertEquals("took too long", result.stopped)
  }

  /** The zip-bomb limit already in place keeps working next to the new one: reason empty (it is the byte cap, not the clock). */
  @Test fun aFileThatIsReadInTimeHasNoStopReason() {
    val file = zip(tmp.root, "ok.docx", "word/document.xml" to "<w:document $W><w:body><w:p><w:r><w:t>fine</w:t></w:r></w:p></w:body></w:document>")
    val result = extract(DocFormat.DOCX, file)
    assertEquals(listOf("fine"), result.pages)
    assertNull(result.stopped)
    assertNotNull(result)
  }
}
