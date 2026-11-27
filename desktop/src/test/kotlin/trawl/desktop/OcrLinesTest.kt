package trawl.desktop

import com.craneai.findr.core.OcrLine
import com.craneai.findr.core.OnScreenText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.json.JSONObject

class TesseractLinesTest {
  private fun parse(tsv: String, slope: Float = 0f) = TesseractLines.parse(tsv, "<span class='ocr_line' title='bbox 100 200 320 248; baseline $slope 0'>")
  private fun tsv(text: String, confidence: String = "96.4", height: Int = 48): String = listOf(
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext",
    "1\t1\t0\t0\t0\t0\t0\t0\t768\t512\t-1\t",
    "4\t1\t1\t1\t1\t0\t100\t200\t220\t$height\t-1\t",
    "5\t1\t1\t1\t1\t1\t100\t200\t220\t$height\t$confidence\t$text",
  ).joinToString("\n")

  @Test fun aCaptionHasRealConfidenceAndPictureRelativeGeometry() {
    val line = parse(tsv("Harbour")).single()
    assertEquals("Harbour", line.text)
    assertEquals(0.964f, line.confidence, 0.00001f)
    assertEquals(0.964f, line.minWordConfidence, 0.00001f)
    assertEquals(48f / 512f, line.heightFraction)
    assertEquals(220f / 768f, line.widthFraction)
    val kept = OnScreenText.keep(listOf(line))!!
    assertEquals("Harbour", kept.text)
    assertFalse(kept.long)
  }

  @Test fun weakTinyMissingOrInvalidConfidenceDoesNotInventEvidence() {
    for ((confidence, height) in listOf("70" to 48, "96" to 2, "-1" to 48, "NaN" to 48, "101" to 48)) {
      assertNull(OnScreenText.keep(parse(tsv("EXIT", confidence, height))))
    }
    assertNull(OnScreenText.keep(parse("not TSV")))
    assertNull(OnScreenText.keep(TesseractLines.parse(tsv("EXIT"), "")))
  }

  @Test fun everyWordContributesItsConfidence() {
    val reading = tsv("Go", "98") + "\n5\t1\t1\t1\t1\t2\t180\t200\t140\t48\t60\thome"
    val line = parse(reading).single()
    assertEquals("Go home", line.text)
    assertEquals(0.79f, line.confidence, 0.00001f)
    assertEquals(0.6f, line.minWordConfidence)
    assertNull(OnScreenText.keep(listOf(line)))
  }

  @Test fun theBaselineKeepsLevelCaptionsAndRejectsTiltedOnes() {
    assertEquals("EXIT", Indexer.readable(parse(tsv("EXIT"), 0.1f)))
    assertNull(Indexer.readable(parse(tsv("EXIT"), 0.5f)))
    assertNull(Indexer.readable(parse(tsv("EXIT"), -0.5f)))
    assertNull(Indexer.readable(TesseractLines.parse(tsv("EXIT"), "<span class='ocr_line' title='bbox 100 200 320 248; textangle 90'>")))
  }

  @Test fun nonTextBoxesDoNotShiftTheCaptionGeometry() {
    val reading = tsv("EXIT") + "\n4\t1\t1\t1\t2\t0\t0\t0\t500\t500\t-1\t" +
      "\n4\t1\t1\t1\t3\t0\t100\t300\t220\t48\t-1\t" +
      "\n5\t1\t1\t1\t3\t1\t100\t300\t220\t48\t96\tHELP"
    val hocr = "<span class='ocr_header' title='bbox 100 200 320 248; baseline 0 0'>EXIT</span>" +
      "<span class='ocr_caption' title='bbox 100 300 320 348; baseline 0 0'>HELP</span>"
    assertEquals("EXIT\nHELP", Indexer.readable(TesseractLines.parse(reading, hocr)))
  }
}

class OcrJsonTest {
  @Test fun visionMetadataReachesTheSharedRule() {
    val reading = JSONObject("""{"text":"EXIT", "lines":[{"text":"EXIT", "confidence":0.97, "angle":4, "height":0.08, "width":0.3}]}""")
    assertEquals("EXIT", Indexer.readable(OcrJson.lines(reading)))
    reading.getJSONArray("lines").getJSONObject(0).put("angle", 20)
    assertNull(Indexer.readable(OcrJson.lines(reading)))
  }

  @Test fun aTextOnlyHelperCannotClaimConfidentShortWords() {
    assertNull(Indexer.readable(OcrJson.lines(JSONObject("""{"text":"EXIT"}"""))))
    assertEquals("GRAND OPENING", Indexer.readable(OcrJson.lines(JSONObject("""{"text":"GRAND OPENING"}"""))))
  }
}

class IndexerOcrTest {
  private fun lines(text: String, confidence: Float = 0.97f) = text.lines()
    .map { OcrLine(it, confidence, 0f, 0.08f, 0.3f, "en", confidence) }

  @Test fun oneWordCaptionsSignsAndShortPhrasesStayFindable() {
    for (caption in listOf("Harbour", "EXIT", "SALE", "Wow!", "Go home", "don't", "co-op", "Café")) {
      assertEquals(caption, Indexer.readable(lines(caption)), caption)
    }
  }

  @Test fun glyphNoiseDigitsAndMixedCaseTextureAreDropped() {
    for (noise in listOf("|l1", "12345", "50%", "lIl", "OoOo", "llll", "tFo", "R2D2", "A I", "EXIT 2")) {
      assertNull(Indexer.readable(lines(noise)), noise)
    }
  }

  @Test fun mixedReadingsKeepTheRealLineNotTheNoise() {
    assertEquals("SALE", Indexer.readable(lines("SALE\n50%\n|l1")))
    assertEquals("EXIT", Indexer.readable(lines("EXIT\n123")))
  }

  @Test fun theLongRuleStillKeepsTheEntireReadingWithoutConfidence() {
    val reading = "  GRAND OPENING\n|l1\n123  "
    assertEquals(reading.trim(), Indexer.readable(lines(reading, OcrLine.UNKNOWN)))
    assertEquals("abcdefghij", Indexer.readable(lines("abcdefghij", 0f)))
    assertNull(Indexer.readable(lines("EXIT", OcrLine.UNKNOWN)))
  }

  @Test fun anExistingTextOnlyOcrUsesTheConservativeDefault() {
    val reader = object : Ocr {
      override val name = "text-only"
      override fun read(image: File) = "EXIT"
      override fun close() = Unit
    }
    assertNull(Indexer.readable(reader.readLines(File("unused"))))
  }
}
