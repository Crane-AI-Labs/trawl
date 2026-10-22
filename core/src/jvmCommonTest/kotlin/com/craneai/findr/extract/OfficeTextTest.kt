package com.craneai.findr.extract

import com.craneai.findr.extract.ZipFixtures.A
import com.craneai.findr.extract.ZipFixtures.ODF
import com.craneai.findr.extract.ZipFixtures.R
import com.craneai.findr.extract.ZipFixtures.SS
import com.craneai.findr.extract.ZipFixtures.W
import com.craneai.findr.extract.ZipFixtures.extract
import com.craneai.findr.extract.ZipFixtures.zip
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfficeTextTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun docxKeepsParagraphsRunsAndTabs() {
    val file = zip(
      tmp.root, "a.docx",
      "word/document.xml" to """<w:document $W><w:body>
        <w:p><w:pPr><w:tabs><w:tab w:val="left"/></w:tabs></w:pPr><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> world</w:t></w:r></w:p>
        <w:p><w:r><w:t>Second</w:t><w:tab/><w:t>para</w:t></w:r></w:p>
        <w:p><w:r><w:instrText>PAGE</w:instrText><w:delText>gone</w:delText><w:t>Third</w:t><w:br/><w:t>line</w:t></w:r></w:p>
        </w:body></w:document>""",
    )
    val result = extract(DocFormat.DOCX, file)
    assertEquals(listOf("Hello world\nSecond\tpara\nThird\nline"), result.pages)
    assertFalse(result.truncated)
  }

  @Test fun docxStopsAtMaxCharsAndSaysSo() {
    val paragraphs = (1..50).joinToString("") { "<w:p><w:r><w:t>paragraph number $it</w:t></w:r></w:p>" }
    val file = zip(tmp.root, "long.docx", "word/document.xml" to "<w:document $W><w:body>$paragraphs</w:body></w:document>")
    val result = extract(DocFormat.DOCX, file, maxChars = 40)
    assertTrue(result.truncated)
    assertTrue(result.pages.single().length <= 40)
    assertTrue(result.pages.single().startsWith("paragraph number 1\nparagraph number 2"))
  }

  @Test fun missingOrBrokenPartsGiveEmptyNotAnException() {
    val noDocument = zip(tmp.root, "x.docx", "other.xml" to "<a/>")
    assertEquals(listOf(""), extract(DocFormat.DOCX, noDocument).pages)
    val broken = zip(tmp.root, "y.docx", "word/document.xml" to "<w:document $W><w:body><w:p><w:r><w:t>kept</w:t></w:r></w:p><w:p><w:r><w:t>cut off")
    assertEquals(listOf("kept\ncut off"), extract(DocFormat.DOCX, broken).pages)
  }

  @Test fun xlsxReadsSharedStringsInlineStringsAndFollowsWorkbookSheetOrder() {
    val file = zip(
      tmp.root, "a.xlsx",
      "xl/workbook.xml" to """<workbook $SS $R><sheets>
        <sheet name="Second" sheetId="2" r:id="rId2"/><sheet name="First" sheetId="1" r:id="rId1"/></sheets></workbook>""",
      "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/>
        <Relationship Id="rId2" Type="x" Target="worksheets/sheet2.xml"/></Relationships>""",
      "xl/sharedStrings.xml" to """<sst $SS><si><t>Name</t></si><si><t>Age</t></si><si><r><t>Ali</t></r><r><t>ce</t></r></si>
        <si><t>Zed</t><rPh sb="0" eb="1"><t>PHONETIC</t></rPh></si></sst>""",
      "xl/worksheets/sheet1.xml" to """<worksheet $SS><sheetData>
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
        <row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>30</v></c><c r="C2" t="b"><v>1</v></c></row></sheetData></worksheet>""",
      "xl/worksheets/sheet2.xml" to """<worksheet $SS><sheetData>
        <row r="1"><c r="A1" t="s"><v>3</v></c><c r="B1" t="inlineStr"><is><t>inline</t></is></c><c r="D1" t="str"><v>formula</v></c></row></sheetData></worksheet>""",
    )
    val text = extract(DocFormat.XLSX, file).pages.single()
    assertEquals("Second\nZed | inline | formula\n\nFirst\nName | Age\nAlice | 30", text)
  }

  @Test fun pptxFollowsPresentationOrderNotFileOrder() {
    fun slide(vararg paragraphs: String) = "<p:sld $A><p:txBody>" +
      paragraphs.joinToString("") { "<a:p><a:pPr><a:tabLst><a:tab/></a:tabLst></a:pPr><a:r><a:t>$it</a:t></a:r></a:p>" } +
      "</p:txBody></p:sld>"
    val file = zip(
      tmp.root, "a.pptx",
      "ppt/presentation.xml" to """<p:presentation $R><p:sldIdLst><p:sldId id="256" r:id="rId7"/><p:sldId id="257" r:id="rId8"/></p:sldIdLst></p:presentation>""",
      "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="x">
        <Relationship Id="rId7" Type="t" Target="slides/slide2.xml"/>
        <Relationship Id="rId8" Type="t" Target="/ppt/slides/slide1.xml"/>
        <Relationship Id="rId9" Type="t" Target="https://example.com" TargetMode="External"/></Relationships>""",
      "ppt/slides/slide1.xml" to slide("ALPHA", "alpha body"),
      "ppt/slides/slide2.xml" to slide("BETA"),
    )
    assertEquals(listOf("Slide 1: BETA", "Slide 2: ALPHA\nalpha body"), extract(DocFormat.PPTX, file).pages)
  }

  @Test fun pptxWithoutIndexFallsBackToNaturalSlideNumberOrder() {
    fun slide(text: String) = "<p:sld $A><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:sld>"
    val file = zip(
      tmp.root, "b.pptx",
      "ppt/slides/slide10.xml" to slide("ten"), "ppt/slides/slide2.xml" to slide("two"),
      "ppt/slides/slide1.xml" to slide("one"),
    )
    assertEquals(listOf("Slide 1: one", "Slide 2: two", "Slide 3: ten"), extract(DocFormat.PPTX, file).pages)
  }

  @Test fun odtSkipsStylesAndReadsSpansSpacesAndBreaks() {
    val file = zip(
      tmp.root, "a.odt",
      "content.xml" to """<office:document-content $ODF>
        <office:automatic-styles><style:style style:name="P1"><text:p>STYLE-NOISE</text:p></style:style></office:automatic-styles>
        <office:body><office:text>
          <text:h text:outline-level="1">Title</text:h>
          <text:p>Hello <text:span>big</text:span>  <text:s text:c="2"/>world<text:line-break/>next<text:tab/>tab</text:p>
          <text:list><text:list-item><text:p>item one</text:p></text:list-item></text:list>
        </office:text></office:body></office:document-content>""",
    )
    assertEquals(listOf("Title\nHello big   world\nnext\ttab\nitem one"), extract(DocFormat.ODT, file).pages)
  }

  @Test fun odsGivesTableNameAndPipeSeparatedRows() {
    val file = zip(
      tmp.root, "a.ods",
      "content.xml" to """<office:document-content $ODF><office:body><office:spreadsheet><table:table table:name="Costs">
        <table:table-row><table:table-cell><text:p>Item</text:p></table:table-cell><table:table-cell><text:p>Cost</text:p></table:table-cell></table:table-row>
        <table:table-row><table:table-cell><text:p>Tea</text:p></table:table-cell><table:table-cell/><table:table-cell><text:p>2</text:p><text:p>000</text:p></table:table-cell></table:table-row>
        </table:table></office:spreadsheet></office:body></office:document-content>""",
    )
    assertEquals(listOf("Costs\nItem | Cost\nTea | 2 000"), extract(DocFormat.ODS, file).pages)
  }

  @Test fun odpGivesOnePagePerSlide() {
    val file = zip(
      tmp.root, "a.odp",
      "content.xml" to """<office:document-content $ODF><office:body><office:presentation>
        <draw:page><text:p>Slide one</text:p><text:p>more</text:p></draw:page>
        <draw:page><text:p>Slide two</text:p></draw:page></office:presentation></office:body></office:document-content>""",
    )
    assertEquals(listOf("Slide 1: Slide one\nmore", "Slide 2: Slide two"), extract(DocFormat.ODP, file).pages)
  }

  @Test fun epubReadsChaptersInSpineOrderAndDecodesTheirHtml() {
    fun chapter(body: String) = """<?xml version="1.0"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title>
      <style>p { color: red }</style></head><body>$body</body></html>"""
    val file = zip(
      tmp.root, "a.epub",
      "mimetype" to "application/epub+zip",
      "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="x"/></rootfiles></container>""",
      "OEBPS/content.opf" to """<package><manifest>
        <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
        <item id="c2" href="Text/ch%202.xhtml" media-type="application/xhtml+xml"/>
        <item id="css" href="style.css" media-type="text/css"/></manifest>
        <spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>""",
      "OEBPS/Text/ch1.xhtml" to chapter("<h1>Chapter One</h1><p>It was a caf&eacute; &amp; bar.</p><script>evil()</script>"),
      "OEBPS/Text/ch 2.xhtml" to chapter("<p>Prologue text</p>"),
    )
    val text = extract(DocFormat.EPUB, file).pages.single()
    assertEquals(listOf("t", "Prologue text", "t", "Chapter One", "It was a café & bar."), text.lines().map { it.trim() }.filter { it.isNotEmpty() })
    assertFalse(text.contains("evil"))
  }

  @Test fun zipBombEntryIsCutAtTheLimitAndKeepsEarlierText() {
    val file = tmp.newFile("bomb.docx")
    ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("word/document.xml"))
      zip.write("<w:document $W><w:body><w:p><w:r><w:t>kept</w:t></w:r></w:p>".toByteArray())
      val chunk = "<w:x/>".repeat(100_000).toByteArray()   // 600 KB of text-free markup, compresses to almost nothing
      repeat((ExtractLimits.MAX_ENTRY_BYTES / chunk.size + 2).toInt()) { zip.write(chunk) }
      zip.write("</w:body></w:document>".toByteArray())
      zip.closeEntry()
    }
    assertTrue("test file stays small", file.length() < 2_000_000)
    val result = extract(DocFormat.DOCX, file)
    assertEquals(listOf("kept"), result.pages)
    assertTrue(result.truncated)
  }

  @Test fun resolvePathFoldsDotsAndAbsolutePaths() {
    assertEquals("ppt/slides/slide1.xml", OfficeText.resolvePath("ppt", "slides/slide1.xml"))
    assertEquals("ppt/slides/slide1.xml", OfficeText.resolvePath("ppt/other", "../slides/./slide1.xml"))
    assertEquals("ppt/slides/slide1.xml", OfficeText.resolvePath("xl", "/ppt/slides/slide1.xml"))
    assertEquals("a/b.xhtml", OfficeText.resolvePath("a", "b.xhtml#frag"))
    assertEquals("b.xhtml", OfficeText.resolvePath("", "b.xhtml"))
  }
}
