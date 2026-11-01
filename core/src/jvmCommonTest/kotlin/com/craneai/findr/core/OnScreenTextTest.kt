package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recogniser's reading of a picture keeps for the keyword index. The long rule (10 letters) is the old one and must not move;
 * the short rule lets a plain word through (a title card, a sign) and nothing else.
 */
class OnScreenTextTest {
  private fun line(
    text: String,
    confidence: Float = 0.97f,
    angle: Float = 0f,
    height: Float = 0.08f,
    minWord: Float = 0.95f,
  ) = OcrLine(text, confidence, angle, height, 0.3f, "en", minWord, text.split(" ").size)

  /** What the app did before: the whole reading, trimmed, kept with at least ten letters in it. */
  private fun old(lines: List<OcrLine>): String? = lines.joinToString("\n") { it.text }.trim().takeIf { t -> t.count { it.isLetter() } >= 10 }

  // --- the long rule is the old rule ---------------------------------------------------------------------------------------

  @Test fun theLongRuleKeepsExactlyWhatTheOldRuleKept() {
    val samples = listOf(
      listOf(line("Boarding Pass"), line("PEARL AIR EXPRESS"), line("EBB to NBO")),
      listOf(line("  GRAND OPENING  ")),
      listOf(line("1234567890 12"), line("a b c d e f g h i")),            // nine letters and a lot of digits
      listOf(line("abcdefghij")),                                        // exactly ten letters
      listOf(line("abcdefghi")),                                         // nine
      listOf(line("Ŋŋ Tusanyuse nnyo")),                                // letters outside ASCII count
      emptyList(),
    )
    for (lines in samples) {
      val kept = OnScreenText.keep(lines)
      val before = old(lines)
      if (before != null) {
        assertNotNull("long text must still be kept: $lines", kept)
        assertEquals(before, kept!!.text)
        assertTrue(kept.long)
      } else if (kept != null) {
        assertFalse("a short reading is never marked long", kept.long)
      }
      assertEquals(before, OnScreenText.legacy(lines.joinToString("\n") { it.text }))
    }
  }

  @Test fun aLongReadingKeepsEveryLineEvenTheOnesTheShortRuleWouldDrop() {
    val lines = listOf(line("Boarding Pass"), line("xx1l", confidence = 0.2f, height = 0.002f), line("T"))
    val kept = OnScreenText.keep(lines)!!
    assertEquals("Boarding Pass\nxx1l\nT", kept.text)
    assertTrue(kept.long)
  }

  @Test fun aRecogniserWithNoConfidenceIsJudgedByTheLongRuleAlone() {
    val unknown = OcrLine("Harbour", OcrLine.UNKNOWN, 0f, 0f, 0f, null)
    assertNull(OnScreenText.keep(listOf(unknown)))
    assertEquals("Harbour Lantern", OnScreenText.keep(listOf(OcrLine("Harbour Lantern", OcrLine.UNKNOWN, 0f, 0f, 0f, null)))!!.text)
  }

  // --- the short rule -----------------------------------------------------------------------------------------------------

  @Test fun aPlainSureWordIsKeptAsShortText() {
    for (word in listOf("Harbour", "STOP", "exit", "Giraffe", "Karibu", "Tusanyuse", "Pancake")) {
      val kept = OnScreenText.keep(listOf(line(word)))
      assertNotNull(word, kept)
      assertEquals(word, kept!!.text)
      assertFalse("$word has fewer than ten letters: words only, no text vector", kept.long)
    }
  }

  @Test fun onlyTheLinesThatAreWordsAreKept() {
    // nine letters in all, so the long rule does not apply and only the plain word is kept
    val kept = OnScreenText.keep(listOf(line("Volcano"), line("aX", confidence = 0.9f), line("12:45")))!!
    assertEquals("Volcano", kept.text)
    assertFalse(kept.long)
  }

  @Test fun aUnsureLineIsDropped() {
    assertNull(OnScreenText.keep(listOf(line("Harbour", confidence = OnScreenText.MIN_LINE_CONFIDENCE - 0.01f))))
    assertNotNull(OnScreenText.keep(listOf(line("Harbour", confidence = OnScreenText.MIN_LINE_CONFIDENCE))))
  }

  @Test fun aLineWithAnUnsureWordIsDropped() {
    assertNull(OnScreenText.keep(listOf(line("Harbour", minWord = OnScreenText.MIN_WORD_CONFIDENCE - 0.01f))))
    assertNotNull(OnScreenText.keep(listOf(line("Harbour", minWord = OnScreenText.MIN_WORD_CONFIDENCE))))
    // a recogniser that gives a line confidence but no word confidences is judged by the line alone
    assertNotNull(OnScreenText.keep(listOf(line("Harbour", minWord = OcrLine.UNKNOWN))))
  }

  @Test fun aTurnedOrTinyLineIsDropped() {
    assertNull(OnScreenText.keep(listOf(line("Harbour", angle = OnScreenText.MAX_ANGLE + 1f))))
    assertNull(OnScreenText.keep(listOf(line("Harbour", angle = -(OnScreenText.MAX_ANGLE + 1f)))))
    assertNotNull(OnScreenText.keep(listOf(line("Harbour", angle = -OnScreenText.MAX_ANGLE))))
    assertNull(OnScreenText.keep(listOf(line("Harbour", height = OnScreenText.MIN_HEIGHT_FRACTION - 0.001f))))
    assertNotNull(OnScreenText.keep(listOf(line("Harbour", height = OnScreenText.MIN_HEIGHT_FRACTION))))
  }

  @Test fun glyphSoupAndNumbersAreNotWords() {
    for (junk in listOf("II", "oo", "T", "1l", "aXk", "tFo", "OoOo", "oooo", "llll", "2024", "12:45", "#$%", "a1b2c3", "ab", "x")) {
      assertNull("$junk is not a word", OnScreenText.keep(listOf(line(junk))))
    }
  }

  @Test fun aWordNeedsOneLongEnoughToBeOne() {
    assertNull(OnScreenText.keep(listOf(line("of it"))))
    assertNotNull(OnScreenText.keep(listOf(line("of the"))))
    assertNotNull(OnScreenText.keep(listOf(line("O'Brien"))))
    assertNotNull(OnScreenText.keep(listOf(line("e-mail"))))
  }

  @Test fun anEmptyReadingKeepsNothing() {
    assertNull(OnScreenText.keep(emptyList()))
    assertNull(OnScreenText.keep(listOf(line("   "))))
  }
}
