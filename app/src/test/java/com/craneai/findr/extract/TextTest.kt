package com.craneai.findr.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocFormatTest {
  @Test fun extensionWinsOverAWrongMimeType() {
    assertEquals(DocFormat.DOCX, DocFormat.detect("application/octet-stream", "Report.DOCX"))
    assertEquals(DocFormat.PLAIN, DocFormat.detect("application/pdf", "notes.txt"))
  }

  @Test fun mimeTypeIsTheFallbackWhenThereIsNoKnownExtension() {
    assertEquals(DocFormat.PDF, DocFormat.detect("application/pdf; charset=binary", "download"))
    assertEquals(DocFormat.EPUB, DocFormat.detect("application/epub+zip", "book.bin"))
  }

  @Test fun everyListedExtensionIsRecognised() {
    val expected = mapOf(
      "txt" to DocFormat.PLAIN, "md" to DocFormat.PLAIN, "csv" to DocFormat.DELIMITED, "tsv" to DocFormat.DELIMITED,
      "json" to DocFormat.PLAIN, "log" to DocFormat.PLAIN, "html" to DocFormat.HTML, "htm" to DocFormat.HTML,
      "xhtml" to DocFormat.HTML, "rtf" to DocFormat.RTF, "docx" to DocFormat.DOCX, "xlsx" to DocFormat.XLSX,
      "pptx" to DocFormat.PPTX, "odt" to DocFormat.ODT, "ods" to DocFormat.ODS, "odp" to DocFormat.ODP,
      "epub" to DocFormat.EPUB, "pdf" to DocFormat.PDF,
    )
    for ((ext, format) in expected) assertEquals(ext, format, DocFormat.detect(null, "x.$ext"))
  }

  @Test fun unknownTypesAreNotClaimed() {
    assertNull(DocFormat.detect("image/jpeg", "photo.jpg"))
    assertNull(DocFormat.detect(null, "legacy.doc"))
    assertNull(DocFormat.detect(null, "noextension"))
  }
}

class TextDecodingTest {
  private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

  @Test fun plainUtf8() {
    assertEquals("café – ok", TextDecoding.decode("café – ok".toByteArray(Charsets.UTF_8)))
  }

  @Test fun utf8BomIsDropped() {
    assertEquals("hi", TextDecoding.decode(bytes(0xEF, 0xBB, 0xBF) + "hi".toByteArray()))
  }

  @Test fun utf16WithBomEitherEndian() {
    assertEquals("héllo", TextDecoding.decode(bytes(0xFF, 0xFE) + "héllo".toByteArray(Charsets.UTF_16LE)))
    assertEquals("héllo", TextDecoding.decode(bytes(0xFE, 0xFF) + "héllo".toByteArray(Charsets.UTF_16BE)))
  }

  @Test fun utf16WithoutBomIsRecognisedFromItsNuls() {
    val text = "This is a longer line of ordinary English text."
    assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16LE)))
    assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16BE)))
  }

  @Test fun invalidUtf8FallsBackToWindows1252() {
    // 0x93 0x94 are curly quotes and 0xE9 is e-acute in windows-1252, none of it valid UTF-8.
    assertEquals("“quoted” café", TextDecoding.decode(bytes(0x93) + "quoted".toByteArray() + bytes(0x94, 0x20) + "caf".toByteArray() + bytes(0xE9)))
  }

  @Test fun aMultiByteCharacterSplitAtTheCutIsNotAnError() {
    val full = "abc€".toByteArray(Charsets.UTF_8)   // the euro sign is three bytes
    val head = full.copyOf(full.size - 1)
    assertEquals("abc", TextDecoding.decode(head, head.size, cut = true))
    assertTrue("without cut it counts as invalid and goes to cp1252", TextDecoding.decode(head, head.size, cut = false).startsWith("abc"))
  }

  @Test fun emptyInputIsEmpty() {
    assertEquals("", TextDecoding.decode(ByteArray(0)))
  }
}

class HtmlTextTest {
  @Test fun scriptsStylesAndCommentsAreDropped() {
    val html = "<html><head><style>p{x:y}</style><script>var a='<b>';</script></head><!-- hidden --><body>Visible</body></html>"
    assertEquals("Visible", HtmlText.toText(html).trim())
  }

  @Test fun entitiesAreDecodedNumericAndNamed() {
    assertEquals("Tom & Jerry <3 café “q” © x €", HtmlText.toText("Tom &amp; Jerry &lt;3 caf&eacute; &ldquo;q&rdquo; &#169; &nbsp;x &#x20AC;").trim())
  }

  @Test fun unknownEntitiesAndBareAmpersandsSurvive() {
    val text = HtmlText.toText("<p>AT&T &bogus; 5 < 6</p>").trim()
    assertTrue(text, text.contains("AT&T"))
    assertTrue(text, text.contains("&bogus;"))
  }

  @Test fun blockTagsBecomeLinesAndInlineTagsDoNot() {
    val text = HtmlText.toText("<h1>Title</h1><p>one <b>two</b> three</p><div>four</div><ul><li>a</li><li>b</li></ul>")
    assertEquals(listOf("Title", "one two three", "four", "a", "b"), text.lines().map { it.trim() }.filter { it.isNotEmpty() })
  }

  @Test fun brIsANewlineAndTableCellsAreSpaced() {
    val text = HtmlText.toText("<p>a<br>b</p><table><tr><td>x</td><td>y</td></tr></table>")
    assertEquals(listOf("a", "b", "x y"), text.lines().map { it.trim() }.filter { it.isNotEmpty() })
  }

  @Test fun attributesWithAngleBracketsDoNotLeakIntoText() {
    assertEquals("link", HtmlText.toText("<a href=\"x?a>b\" title='1 > 0'>link</a>").trim())
  }

  @Test fun anUnterminatedTagOrCommentDoesNotThrow() {
    assertFalse(HtmlText.toText("text <div").contains("<div"))
    HtmlText.toText("text <!-- never closed")
    HtmlText.toText("<script>never closed")
  }
}

class RtfTextTest {
  @Test fun controlWordsAreStrippedAndParagraphsKept() {
    val rtf = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times;}}{\\colortbl;\\red0\\green0\\blue0;}\\pard Hello \\b world\\b0.\\par Second line\\par}"
    val lines = RtfText.toText(rtf).lines().map { it.trim() }.filter { it.isNotEmpty() }
    assertEquals(listOf("Hello world.", "Second line"), lines)
  }

  @Test fun hexAndUnicodeEscapesAreDecoded() {
    // \'e9 is e-acute in cp1252; 荤? is the euro sign with ? as its ANSI fallback to skip.
    val text = RtfText.toText("{\\rtf1\\ansi caf\\'e9 \\u8364? and \\u233?}")
    assertEquals("café € and é", text.trim())
  }

  @Test fun escapedBracesAndBackslashesAreLiteral() {
    assertEquals("a{b}c\\d", RtfText.toText("{\\rtf1 a\\{b\\}c\\\\d}").trim())
  }

  @Test fun skippedDestinationsLeaveNoText() {
    val text = RtfText.toText("{\\rtf1{\\*\\generator Hidden 1.0;}{\\info{\\title Secret}}Body{\\pict\\wmetafile8 0102abcd}}")
    assertEquals("Body", text.trim())
  }

  @Test fun truncatedInputDoesNotThrow() {
    RtfText.toText("{\\rtf1\\ansi Hello {\\b bold")
    RtfText.toText("{\\rtf1 \\u")
    RtfText.toText("{\\rtf1 \\'")
  }
}

class PageCollectorTest {
  @Test fun eachPageIsKeptAndAScanStaysAnEmptyString() {
    val c = PageCollector(1000)
    assertTrue(c.add("  page one \r\n line  "))
    assertTrue(c.add(""))
    assertTrue(c.add("page three"))
    val r = c.result()
    assertEquals(listOf("page one \n line", "", "page three"), r.pages)
    assertFalse(r.truncated)
  }

  @Test fun theBudgetCutsTheLastPageAndRefusesTheRest() {
    val c = PageCollector(10)
    assertTrue(c.add("12345"))
    assertFalse(c.add("6789012345"))
    val r = c.result()
    assertEquals(listOf("12345", "67890"), r.pages)
    assertTrue(r.truncated)
  }

  @Test fun anExactFitStopsWithoutBeingMarkedTruncated() {
    val c = PageCollector(5)
    assertFalse("budget spent exactly", c.add("12345"))
    assertFalse(c.result().truncated)
    assertTrue(c.result(truncatedByCap = true).truncated)
  }
}

class GeometryHelpersTest {
  @Test fun pdfTargetSizeKeepsAspectAndCapsTallPages() {
    assertEquals(1600 to 2264, PdfPageRenderer.targetSize(595, 842, 1600))   // A4
    val (w, h) = PdfPageRenderer.targetSize(200, 2000, 1600)                 // a long receipt
    assertEquals(4096, h)
    assertEquals(410, w)
    assertEquals(1 to 1, PdfPageRenderer.targetSize(0, 0, 0).let { it.first.coerceAtLeast(1) to it.second.coerceAtLeast(1) })
  }

  @Test fun ocrDownscaleOnlyShrinksTheLongSide() {
    assertEquals(1000 to 800, MlKitOcrEngine.scaledSize(1000, 800, 2048))
    assertEquals(2048 to 1024, MlKitOcrEngine.scaledSize(4096, 2048, 2048))
    assertEquals(1024 to 2048, MlKitOcrEngine.scaledSize(2000, 4000, 2048))
  }

  @Test fun videoSizeHelpers() {
    assertEquals(1080 to 1920, MediaVideoSampler.displaySize(1920, 1080, 90))
    assertEquals(1920 to 1080, MediaVideoSampler.displaySize(1920, 1080, 0))
    assertNull(MediaVideoSampler.displaySize(0, 1080, 0))
    assertEquals(768 to 432, MediaVideoSampler.fitLongSide(1920, 1080, 768))
    assertEquals(320 to 240, MediaVideoSampler.fitLongSide(320, 240, 768))
    // A phone video coded landscape with a 90 degree tag shows portrait; a frame that comes back landscape is on its side.
    assertTrue(MediaVideoSampler.needsRotation(768, 432, 1080, 1920, 90))
    assertFalse(MediaVideoSampler.needsRotation(432, 768, 1080, 1920, 90))
    assertFalse(MediaVideoSampler.needsRotation(768, 432, 1920, 1080, 0))
  }
}

class BinarySniffTest {
  private val random = java.util.Random(7).let { r -> ByteArray(4096).also { r.nextBytes(it) } }

  @Test fun randomBytesAndPdfsAreNotText() {
    assertTrue(TextDecoding.looksBinary(random))
    val pdf = "%PDF-1.7\n%\u00e2\u00e3\n1 0 obj\n<< /Length 5 >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 1, 2, 3)
    assertTrue(TextDecoding.isPdf(pdf))
    assertTrue(TextDecoding.looksBinary(pdf))
  }

  @Test fun realTextInAnyEncodingIsText() {
    assertFalse(TextDecoding.looksBinary("Ssebo, olunaku lwa leero lulungi.\r\n\tTaba\n".toByteArray()))
    assertFalse(TextDecoding.looksBinary("Café déjà vu, naïve façade".toByteArray(Charsets.ISO_8859_1)))
    assertFalse(TextDecoding.looksBinary(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Habari ya asubuhi".toByteArray(Charsets.UTF_16LE)))
    assertFalse(TextDecoding.looksBinary("Plain UTF-16 with no byte order mark at all".toByteArray(Charsets.UTF_16LE)))
    assertFalse(TextDecoding.looksBinary(ByteArray(0)))
    assertFalse(TextDecoding.isPdf("notes about %PDF-1.7".toByteArray()))
  }
}

