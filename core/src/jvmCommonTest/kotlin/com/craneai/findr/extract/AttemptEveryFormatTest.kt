package com.craneai.findr.extract

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** Files the name could not place are read by their first bytes, and vector pictures give their words. */
class AttemptEveryFormatTest {
  private val parser: () -> XmlPullParser = { KXmlParser() }
  private fun svg(s: String) = SvgText.toText(s.toByteArray(), 10_000, parser)

  @Test fun svgGivesItsTitleDescriptionAndTextAndNothingElse() {
    val text = svg(
      """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" width="100"><title>Kampala sales chart</title>
         <desc>Quarterly totals</desc><style>.a{fill:red}</style><path d="M0 0L10 10"/>
         <g><text x="1" y="2">Revenue <tspan>2026</tspan></text><text>Costs</text></g><script>alert(1)</script></svg>""",
    )
    assertEquals("Kampala sales chart\nQuarterly totals\nRevenue 2026\nCosts", text)
  }

  @Test fun svgzIsInflatedAndABrokenOrCutSvgKeepsTheWordsBeforeTheBreak() {
    val bomb = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("<svg><text>zipped label</text></svg>".toByteArray()) } }.toByteArray()
    assertEquals("zipped label", SvgText.toText(bomb, 1000, parser))
    assertTrue(svg("<svg><text>before</text><text>cut off").startsWith("before"))   // truncated mid-document: no throw, earlier words kept
    assertEquals("", SvgText.toText(bomb.copyOf(12), 1000, parser))         // a gzip cut inside its header: nothing, no throw
    assertEquals("", svg("not xml at all"))
  }

  @Test fun svgOutputIsCappedByCharacters() {
    val many = "<svg>" + (1..500).joinToString("") { "<text>label number $it</text>" } + "</svg>"
    assertTrue(SvgText.toText(many.toByteArray(), 100, parser).length <= 100)
  }

  @Test fun theFormatOfAnUnnamedFileComesFromItsBytes() {
    fun f(s: String) = DocFormat.fromHeader(s.toByteArray(Charsets.ISO_8859_1))
    assertEquals(DocFormat.PDF, f("%PDF-1.4\n%âãÏÓ"))
    assertEquals(DocFormat.DOCX, f("PK\u0003\u0004\u0014\u0000\u0000\u0000"))   // looked inside by OfficeText
    assertEquals(DocFormat.RTF, f("{\\rtf1\\ansi hello"))
    assertEquals(DocFormat.SVG, f("<svg xmlns='http://www.w3.org/2000/svg'/>"))
    assertEquals(DocFormat.SVG, f("<?xml version='1.0'?><svg/>"))
    assertEquals(DocFormat.HTML, f("<!DOCTYPE html><html><body>hi</body></html>"))
    assertEquals(DocFormat.PLAIN, f("Meeting notes for Monday, bring the budget."))
    assertNull("a legacy Office file is binary", DocFormat.fromHeader(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0, 0, 0)))
    assertNull(DocFormat.fromHeader(byteArrayOf(1, 2)))
  }

  @Test fun svgIsAKnownFormatByNameAndType() {
    assertEquals(DocFormat.SVG, DocFormat.detect(null, "logo.svg"))
    assertEquals(DocFormat.SVG, DocFormat.detect(null, "logo.SVGZ"))
    assertEquals(DocFormat.SVG, DocFormat.detect("image/svg+xml", "logo"))
  }
}
