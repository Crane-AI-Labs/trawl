package com.craneai.findr.extract

import com.craneai.findr.extract.ZipFixtures.A
import com.craneai.findr.extract.ZipFixtures.ODF
import com.craneai.findr.extract.ZipFixtures.R
import com.craneai.findr.extract.ZipFixtures.SS
import com.craneai.findr.extract.ZipFixtures.extract
import com.craneai.findr.extract.ZipFixtures.zip
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** XLSX, PPTX, ODS, ODP, EPUB and CSV as search text: names, order, caps, and files that are not what they say. */
class OfficeReadersTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun workbook(sheetXml: Map<String, String>, shared: List<String> = emptyList()): File {
    val names = sheetXml.keys.toList()
    return zip(
      tmp.newFolder().also { }, "wb.xlsx",
      "xl/workbook.xml" to "<workbook $SS $R><sheets>" + names.mapIndexed { i, n -> """<sheet name="$n" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>",
      "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"x\">" + names.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="t" Target="worksheets/sheet${it + 1}.xml"/>""" } + "</Relationships>",
      "xl/sharedStrings.xml" to "<sst $SS>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>",
      *names.mapIndexed { i, n -> "xl/worksheets/sheet${i + 1}.xml" to "<worksheet $SS><sheetData>${sheetXml[n]}</sheetData></worksheet>" }.toTypedArray(),
    )
  }

  @Test fun xlsxGivesSheetNamesThenPipeRowsWithSharedStringsResolvedAndEmptyCellsSkipped() {
    val file = workbook(
      linkedMapOf(
        "Budget 2026" to """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
          <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"/><c r="C2"><v>12500.5</v></c></row>
          <row r="3"/><row r="4"><c r="A4" t="s"><v>0</v></c><c r="B4"><v>7</v></c></row>""",
        "Staff" to """<row r="1"><c r="A1" t="s"><v>4</v></c></row>""",
      ),
      shared = listOf("Item", "Qty", "Cost", "Mosquito nets", "Nakato Grace"),
    )
    val result = extract(DocFormat.XLSX, file)
    assertEquals("Budget 2026\nItem | Qty | Cost\nMosquito nets | 12500.5\nItem | 7\n\nStaff\nNakato Grace", result.pages.single())
    assertFalse(result.truncated)
  }

  @Test fun xlsxStopsAtTheCellCapAndSaysSo() {
    val rows = (1..300).joinToString("") { r -> """<row r="$r">""" + (1..10).joinToString("") { c -> """<c r="X$c"><v>${r * 100 + c}</v></c>""" } + "</row>" }
    val file = workbook(linkedMapOf("Big" to rows))
    // 3000 cells in the file; the real cap is 200k, so check the mechanism through the character budget and a tiny file at the cap.
    val capped = extract(DocFormat.XLSX, file, maxChars = 500)
    assertTrue(capped.truncated)
    assertTrue(capped.pages.single().length <= 500 + "Big\n".length)
    assertTrue(capped.pages.single().startsWith("Big\n101 | 102 | 103"))
  }

  @Test fun xlsxCellCapIsHonoured() {
    val cells = ExtractLimits.MAX_CELLS + 50
    val file = tmp.newFile("many.xlsx")
    java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
      fun put(n: String, s: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
      put("xl/workbook.xml", "<workbook $SS $R><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
      put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"x\"><Relationship Id=\"rId1\" Type=\"t\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
      z.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
      z.write("<worksheet $SS><sheetData>".toByteArray())
      var i = 0
      while (i < cells) {
        val sb = StringBuilder("<row>")
        repeat(100) { if (i < cells) { sb.append("<c><v>").append(i % 7).append("</v></c>"); i++ } }
        z.write(sb.append("</row>").toString().toByteArray())
      }
      z.write("</sheetData></worksheet>".toByteArray())
      z.closeEntry()
    }
    val result = extract(DocFormat.XLSX, file, maxChars = 10_000_000)
    assertTrue(result.truncated)
    // "S" title line plus exactly MAX_CELLS values and (cells per row - 1) separators per row.
    assertEquals(ExtractLimits.MAX_CELLS, result.pages.single().count { it.isDigit() })
  }

  @Test fun xlsxWithSharedStringsPastTheTableLimitKeepsNumbersAndEarlierText() {
    val file = workbook(
      linkedMapOf("S" to """<row><c t="s"><v>0</v></c><c t="s"><v>9999</v></c><c><v>42</v></c></row>"""),
      shared = listOf("first"),
    )
    assertEquals("S\nfirst | 42", extract(DocFormat.XLSX, file).pages.single())
  }

  @Test fun xlsxWithoutAWorkbookIndexStillReadsItsSheets() {
    val file = zip(
      tmp.newFolder(), "n.xlsx",
      "xl/worksheets/sheet2.xml" to """<worksheet $SS><sheetData><row><c t="inlineStr"><is><t>second</t></is></c></row></sheetData></worksheet>""",
      "xl/worksheets/sheet1.xml" to """<worksheet $SS><sheetData><row><c t="inlineStr"><is><t>first</t></is></c></row></sheetData></worksheet>""",
    )
    assertEquals("first\n\nsecond", extract(DocFormat.XLSX, file).pages.single())
  }

  @Test fun pptxLabelsSlidesAndAddsSpeakerNotes() {
    fun slide(text: String) = "<p:sld $A><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sld>"
    val notes = "<p:notes $A><a:p><a:r><a:t>Remember the Kampala numbers</a:t></a:r></a:p><a:p><a:fld type=\"slidenum\"><a:t>‹#›</a:t></a:fld></a:p></p:notes>"
    val file = zip(
      tmp.newFolder(), "n.pptx",
      "ppt/presentation.xml" to """<p:presentation $R><p:sldIdLst><p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/><p:sldId id="258" r:id="rId3"/></p:sldIdLst></p:presentation>""",
      "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="x"><Relationship Id="rId1" Type="t" Target="slides/slide1.xml"/>
        <Relationship Id="rId2" Type="t" Target="slides/slide2.xml"/><Relationship Id="rId3" Type="t" Target="slides/slide3.xml"/></Relationships>""",
      "ppt/slides/slide1.xml" to slide("Quarterly malaria review"),
      "ppt/slides/slide2.xml" to "<p:sld $A></p:sld>",
      "ppt/slides/slide3.xml" to slide("Next steps"),
      "ppt/slides/_rels/slide1.xml.rels" to """<Relationships xmlns="x"><Relationship Id="rId1" Type="t" Target="../notesSlides/notesSlide1.xml"/></Relationships>""",
      "ppt/notesSlides/notesSlide1.xml" to notes,
    )
    val pages = extract(DocFormat.PPTX, file).pages
    assertEquals(listOf("Slide 1: Quarterly malaria review\nNotes: Remember the Kampala numbers", "", "Slide 3: Next steps"), pages)
  }

  @Test fun odsNamesTablesAndSkipsCellComments() {
    val file = zip(
      tmp.newFolder(), "n.ods",
      "content.xml" to """<office:document-content $ODF><office:body><office:spreadsheet>
        <table:table table:name="Clinics"><table:table-row>
          <table:table-cell><text:p>Mulago</text:p><office:annotation><text:p>COMMENT-NOISE</text:p></office:annotation></table:table-cell>
          <table:table-cell office:value-type="float" office:value="12"><text:p>12</text:p></table:table-cell></table:table-row></table:table>
        <table:table table:name="Notes"><table:table-row><table:table-cell><text:p>second sheet</text:p></table:table-cell></table:table-row></table:table>
        </office:spreadsheet></office:body></office:document-content>""",
    )
    assertEquals("Clinics\nMulago | 12\n\nNotes\nsecond sheet", extract(DocFormat.ODS, file).pages.single())
  }

  @Test fun epubKeepsTitleAuthorAndChapterTitlesAndSurvivesABrokenChapter() {
    val file = zip(
      tmp.newFolder(), "n.epub",
      "mimetype" to "application/epub+zip",
      "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf" media-type="x"/></rootfiles></container>""",
      "c.opf" to """<package xmlns:dc="d"><metadata><dc:title>The Lantern Years</dc:title><dc:creator>A. Author</dc:creator></metadata><manifest>
        <item id="a" href="a.xhtml"/><item id="b" href="missing.xhtml"/><item id="c" href="c.xhtml"/></manifest>
        <spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>""",
      "a.xhtml" to "<html><body><h1>Chapter 1: The Giraffe</h1><p>First text.</p></body></html>",
      "c.xhtml" to "<html><body><h2>Chapter 3: Rain</h2><p>Last text.</p></body></html>",
    )
    val lines = extract(DocFormat.EPUB, file).pages.single().lines().map { it.trim() }.filter { it.isNotEmpty() }
    assertEquals(listOf("The Lantern Years by A. Author", "Chapter 1: The Giraffe", "First text.", "Chapter 3: Rain", "Last text."), lines)
  }

  @Test fun epubWithEncryptedChaptersGivesNothingButFontObfuscationIsFine() {
    fun epub(name: String, algorithm: String, uri: String) = zip(
      tmp.newFolder(), name,
      "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf" media-type="x"/></rootfiles></container>""",
      "c.opf" to """<package><manifest><item id="a" href="a.xhtml"/></manifest><spine><itemref idref="a"/></spine></package>""",
      "META-INF/encryption.xml" to """<encryption xmlns:enc="e"><enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>
        <enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData></enc:EncryptedData></encryption>""",
      "a.xhtml" to "<html><body><p>readable</p></body></html>",
    )
    assertEquals("", extract(DocFormat.EPUB, epub("drm.epub", "http://www.w3.org/2001/04/xmlenc#aes128-cbc", "a.xhtml")).pages.single())
    assertEquals("readable", extract(DocFormat.EPUB, epub("font.epub", "http://www.idpf.org/2008/embedding", "fonts/x.otf")).pages.single())
  }

  @Test fun aFileNamedForTheWrongOfficeFormatIsReadAsWhatItIs() {
    val sheet = workbook(linkedMapOf("Real" to """<row><c t="inlineStr"><is><t>hidden in a docx</t></is></c></row>"""))
    assertEquals("Real\nhidden in a docx", extract(DocFormat.DOCX, sheet).pages.single())
    val ods = zip(
      tmp.newFolder(), "x.odt", "mimetype" to "application/vnd.oasis.opendocument.spreadsheet",
      "content.xml" to """<office:document-content $ODF><office:body><office:spreadsheet><table:table table:name="T"><table:table-row><table:table-cell><text:p>cell</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>""",
    )
    assertEquals("T\ncell", extract(DocFormat.ODT, ods).pages.single())
  }

  @Test fun aDamagedZipThrowsAnIOExceptionTheExtractorCatches() {
    val good = workbook(linkedMapOf("S" to """<row><c><v>1</v></c></row>"""))
    val bytes = good.readBytes()
    val cut = tmp.newFile("cut.xlsx").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
    try {
      extract(DocFormat.XLSX, cut)
      fail("a zip with no end header should not open")
    } catch (e: IOException) {
      // DocumentTextExtractor turns this into "no text" and the queue moves on.
    }
    val garbage = tmp.newFile("garbage.pptx").apply { writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
    try {
      extract(DocFormat.PPTX, garbage)
      fail("garbage is not a zip")
    } catch (e: IOException) {
    }
  }

  @Test fun aPasswordProtectedOfficeFileIsNotAZipAndFailsTheSameWay() {
    // Encrypted OOXML is an OLE compound file (D0 CF 11 E0 ...), not a zip.
    val cfb = tmp.newFile("locked.xlsx").apply {
      writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(4096))
    }
    try {
      extract(DocFormat.XLSX, cfb)
      fail("an encrypted workbook must not read")
    } catch (e: IOException) {
    }
  }

  @Test fun anOpenDocumentWithEncryptedContentGivesNoText() {
    val file = zip(
      tmp.newFolder(), "enc.ods", "mimetype" to "application/vnd.oasis.opendocument.spreadsheet",
      "content.xml" to String(ByteArray(600) { (it % 200 + 14).toByte() }, Charsets.ISO_8859_1),
    )
    assertEquals("", extract(DocFormat.ODS, file).pages.single())
  }

  // ---- CSV / TSV -------------------------------------------------------------------------------------------------

  @Test fun csvBecomesPipeRowsWithQuotesAndEmptyCellsHandled() {
    val csv = "Name,Village,Notes\nGrace,\"Kira, Wakiso\",\"said \"\"yes\"\"\"\nJohn,,\n\"Multi\nline\",Entebbe,ok\n"
    val r = DelimitedText.toText(csv, 100_000)
    assertEquals("Name | Village | Notes\nGrace | Kira, Wakiso | said \"yes\"\nJohn\nMulti line | Entebbe | ok", r.text)
    assertFalse(r.truncated)
  }

  @Test fun theDelimiterIsDetected() {
    assertEquals('\t', DelimitedText.detectDelimiter("a\tb\tc\n1\t2\t3\n"))
    assertEquals(';', DelimitedText.detectDelimiter("a;b;c\n1,5;2,5;3\n4;5;6\n"))
    assertEquals('|', DelimitedText.detectDelimiter("a|b\nc|d\n"))
    assertEquals(',', DelimitedText.detectDelimiter("\"x;y\",b\n\"p;q\",d\n"))
    // Prose with a comma is one column, not a table.
    assertEquals(null, DelimitedText.detectDelimiter("Dear Grace, thank you.\nSee you soon.\nBest wishes\n"))
  }

  @Test fun tsvAndSemicolonFilesAreReadWithTheirOwnDelimiter() {
    assertEquals("a | b\n1 | 2", DelimitedText.toText("a\tb\n1\t2\n", 1000).text)
    assertEquals("a | b\n1,5 | 2", DelimitedText.toText("﻿a;b\r\n1,5;2\r\n", 1000).text)
  }

  @Test fun csvStopsAtTheCharacterCapAndTheCellCap() {
    val csv = (1..1000).joinToString("\n") { "row$it,value$it,more$it" }
    val chars = DelimitedText.toText(csv, 200)
    assertTrue(chars.truncated)
    assertTrue(chars.text.length <= 200)
    val cells = DelimitedText.toText(csv, 1_000_000, maxCells = 10)
    assertTrue(cells.truncated)
    assertEquals(10, cells.text.split(" | ", "\n").size)
  }
}
