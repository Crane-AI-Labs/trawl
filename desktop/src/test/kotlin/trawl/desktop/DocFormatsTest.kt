package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "My Word file did not show up." Every format the phone reads is read here too, through the same core extractors (DocFormats), and a
 * word inside each made-up file finds that file. The files are built in code so no binary document is committed.
 */
class DocFormatsTest {
  private fun tmp() = Files.createTempDirectory("trawl-formats").toFile().canonicalFile

  private fun zip(dir: File, rel: String, vararg entries: Pair<String, String>): File {
    val file = File(dir, rel).apply { parentFile.mkdirs() }
    ZipOutputStream(file.outputStream()).use { z ->
      for ((name, content) in entries) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
    }
    return file
  }

  private val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
  private val A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
  private val R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
  private val SS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
  private val ODF = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
    "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""

  /** One made-up file of every format, each holding a word nothing else holds. */
  private fun everyFormat(root: File): Map<String, String> {
    val words = LinkedHashMap<String, String>()   // file name -> the word inside it
    fun text(name: String, body: String, word: String) { File(root, name).apply { parentFile.mkdirs() }.writeText(body); words[name] = word }
    text("a/notes.txt", "Meeting notes about the quokkaberry harvest.", "quokkaberry")
    text("a/readme.md", "# Plan\n\nBuy a hammerstone for the garden.", "hammerstone")
    text("a/settings.json", """{"title": "Zephyrline settings", "n": 3}""", "zephyrline")
    text("a/README", "Remember to water the sunstonefern every morning.", "sunstonefern")
    text("a/server.log", "2026-10-20 12:00:01 INFO started the dumbledoreish service", "dumbledoreish")
    text("a/costs.csv", "item,cost\nturmericpowder,12\nsaffron,40\n", "turmericpowder")
    text("a/costs.tsv", "item\tcost\ncorianderseed\t8\n", "corianderseed")
    text("a/page.html", "<html><head><title>t</title><style>p{color:red}</style></head><body><h1>Welcome</h1><p>The lighthouse keeper's marzipanista.</p><script>evil()</script></body></html>", "marzipanista")
    text("a/letter.rtf", "{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Dear friend, the bluemoonrabbit arrives on Friday.\\par}", "bluemoonrabbit")
    text("a/logo.svg", """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><title>Pinwheel</title><text x="1" y="2">glimmerfox</text></svg>""", "glimmerfox")

    zip(root, "a/report.docx", "word/document.xml" to "<w:document $W><w:body><w:p><w:r><w:t>The cardamomtrader signed the lease.</w:t></w:r></w:p></w:body></w:document>")
    words["a/report.docx"] = "cardamomtrader"

    zip(
      root, "a/budget.xlsx",
      "xl/workbook.xml" to """<workbook $SS $R><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
      "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/></Relationships>""",
      "xl/sharedStrings.xml" to "<sst $SS><si><t>Item</t></si><si><t>pomegranatesyrup</t></si></sst>",
      "xl/worksheets/sheet1.xml" to """<worksheet $SS><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row><row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>30</v></c></row></sheetData></worksheet>""",
    )
    words["a/budget.xlsx"] = "pomegranatesyrup"

    fun slide(t: String) = "<p:sld $A><a:p><a:r><a:t>$t</a:t></a:r></a:p></p:sld>"
    zip(root, "a/deck.pptx", "ppt/slides/slide1.xml" to slide("Opening"), "ppt/slides/slide2.xml" to slide("Our wanderingnarwhal strategy"))
    words["a/deck.pptx"] = "wanderingnarwhal"

    zip(root, "a/essay.odt", "content.xml" to """<office:document-content $ODF><office:body><office:text><text:p>An essay on the thunderlemur.</text:p></office:text></office:body></office:document-content>""")
    words["a/essay.odt"] = "thunderlemur"

    zip(
      root, "a/sheet.ods",
      "content.xml" to """<office:document-content $ODF><office:body><office:spreadsheet><table:table table:name="T"><table:table-row><table:table-cell><text:p>velvetbadger</text:p></table:table-cell></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>""",
    )
    words["a/sheet.ods"] = "velvetbadger"

    zip(root, "a/slides.odp", "content.xml" to """<office:document-content $ODF><office:body><office:presentation><draw:page><text:p>cobaltpenguin rises</text:p></draw:page></office:presentation></office:body></office:document-content>""")
    words["a/slides.odp"] = "cobaltpenguin"

    zip(
      root, "a/book.epub",
      "mimetype" to "application/epub+zip",
      "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="x"/></rootfiles></container>""",
      "OEBPS/content.opf" to """<package><manifest><item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
      "OEBPS/ch1.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>One</h1><p>The ember-drifting saltcarillon rang.</p></body></html>""",
    )
    words["a/book.epub"] = "saltcarillon"
    return words
  }

  // ---- the readers themselves --------------------------------------------------------------------------------------

  @Test fun everyFormatGivesItsWordsToTheReader() {
    val root = tmp()
    for ((name, word) in everyFormat(root)) {
      val read = DocFormats.read(File(root, name))
      assertFalse(read.empty, "$name gave no text")
      assertTrue(read.pages.joinToString(" ").lowercase().contains(word), "$name: '$word' is not in ${read.pages}")
      assertNull(read.why, name)
    }
    root.deleteRecursively()
  }

  @Test fun htmlScriptsAndStylesAreNotWords() {
    val root = tmp()
    everyFormat(root)
    val text = DocFormats.read(File(root, "a/page.html")).pages.joinToString(" ")
    assertFalse(text.contains("evil"), text)
    assertFalse(text.contains("color"), text)
    root.deleteRecursively()
  }

  @Test fun aSlideDeckGivesOnePagePerSlideSoAResultCanSayWhichSlide() {
    val root = tmp()
    everyFormat(root)
    assertEquals(2, DocFormats.pages(File(root, "a/deck.pptx"), false).chunks.size)
    assertEquals(1, DocFormats.pages(File(root, "a/essay.odt"), false).chunks.size)
    root.deleteRecursively()
  }

  @Test fun aFileWhoseNameLiesIsReadAsWhatItIs() {
    val root = tmp()
    everyFormat(root)
    // A spreadsheet saved as .docx, a web page with no extension: the core judges by the parts and by the first bytes.
    File(root, "a/budget.xlsx").copyTo(File(root, "b/was-budget.docx"))
    assertTrue(DocFormats.read(File(root, "b/was-budget.docx")).pages.joinToString(" ").contains("pomegranatesyrup"))
    File(root, "a/page.html").copyTo(File(root, "b/webpage"))
    assertTrue(DocFormats.read(File(root, "b/webpage")).pages.joinToString(" ").contains("marzipanista"))
    root.deleteRecursively()
  }

  @Test fun anUtf16AndALatin1FileAreDecodedByTheSharedDecoder() {
    val root = tmp()
    File(root, "utf16.txt").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "nightingale café".toByteArray(Charsets.UTF_16LE))
    File(root, "latin1.txt").writeBytes("the café of the gentleman".toByteArray(Charsets.ISO_8859_1))
    assertTrue(DocFormats.read(File(root, "utf16.txt")).pages.single().contains("nightingale"))
    assertTrue(DocFormats.read(File(root, "latin1.txt")).pages.single().contains("café"))
    root.deleteRecursively()
  }

  @Test fun aPdfUnderATextNameIsHandedToPdfBox() {
    val root = tmp()
    File(root, "speech.pdf.txt").writeBytes("%PDF-1.4\n1 0 obj\n<<>>\nendobj\n".toByteArray())
    val r = DocFormats.read(File(root, "speech.pdf.txt"))
    assertTrue(r.pdf, "the bytes are a PDF whatever the name says")
    root.deleteRecursively()
  }

  @Test fun binaryUnderATextNameAndABrokenZipFailWithAReasonAndNeverThrow() {
    val root = tmp()
    File(root, "junk.txt").writeBytes(ByteArray(4000) { (it % 7).toByte() })
    File(root, "broken.docx").writeBytes("PK\u0003\u0004 this is not really a zip".toByteArray())
    assertEquals(DocFormats.Why.BINARY, DocFormats.read(File(root, "junk.txt")).why)
    assertEquals(DocFormats.Why.DAMAGED, DocFormats.read(File(root, "broken.docx")).why)
    assertEquals("not text", DocFormats.pages(File(root, "junk.txt"), false).failure)
    root.deleteRecursively()
  }

  @Test fun formatsNobodyReadsHaveNoReaderAndStayFindableByName() {
    val root = tmp()
    File(root, "old.doc").writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(2048))
    File(root, "keynote.key").writeBytes(ByteArray(64))
    assertFalse(DocFormats.canRead(File(root, "old.doc")))
    assertFalse(DocFormats.canRead(File(root, "keynote.key")))
    root.deleteRecursively()
  }

  // ---- through the whole index ----------------------------------------------------------------------------------------

  private fun waitFor(what: String, ms: Long = 30_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  @Test fun aWordInsideEveryFormatFindsItsFileWithNoModel() {
    val root = tmp(); val data = tmp()
    val words = everyFormat(root)
    File(root, "a/broken.docx").writeBytes("PK\u0003\u0004 not a zip at all".toByteArray())
    IndexService(data).use { ix ->
      ix.configure(setOf(FileKind.DOCUMENT, FileKind.PDF), emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(root))
      for ((name, word) in words) {
        waitFor("'$word' in $name") { ix.search(word, KindFilter.ALL).hits.any { it.file.name == File(name).name } }
        assertEquals(listOf(File(name).name), ix.search(word, KindFilter.ALL).hits.map { it.file.name }, "only $name says '$word'")
      }
      // The damaged file is not a hole in the index: it is findable by name, and counted as failed with the reason.
      waitFor("the damaged file to be marked") { ix.ui.value.unreadable >= 1 }
      assertTrue(ix.search("broken", KindFilter.ALL).hits.any { it.file.name == "broken.docx" }, "findable by name")
    }
    root.deleteRecursively(); data.deleteRecursively()
  }
}
