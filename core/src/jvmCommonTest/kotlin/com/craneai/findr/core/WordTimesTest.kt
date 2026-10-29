package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word times from Parakeet's per-token output: where a word starts, where it ends, and the rule that keeps the words equal to the
 * cleaned text (so a mark's offset is a place in the stored text). Times are multiples of 1/8 s so the float arithmetic is exact.
 */
class WordTimesTest {
  // "the quarterly budget" as Parakeet writes it: a word-initial token carries the "▁", the rest of a word follows without.
  private val tokens = listOf("▁the", "▁quarter", "ly", "▁bud", "get")
  private val stamps = floatArrayOf(0.25f, 0.5f, 0.875f, 1.25f, 1.5f)

  // The model's times run late (LATE_BIAS_MS, 130 ms): every time below is the token's second less that.
  @Test fun aWordStartsAtItsFirstTokenAndEndsAFrameAfterItsLast() {
    val w = WordTimes.fromTokens(tokens, stamps, seconds = 2.0, baseMs = 0)
    assertEquals(listOf("the", "quarterly", "budget"), w.map { it.text })
    assertEquals(listOf(120L, 370L, 1120L), w.map { it.startMs })
    // "the": its token at 250 - 130 = 120 ms + one frame = 200; "quarterly": last token at 875 - 130 + 80 = 825; "budget": 1500 - 130 + 80 = 1450
    assertEquals(listOf(200L, 825L, 1450L), w.map { it.endMs })
  }

  @Test fun theBaseOffsetPlacesWordsInTheWholeFilesTime() {
    val w = WordTimes.fromTokens(tokens, stamps, seconds = 2.0, baseMs = 40_000)
    assertEquals(40_120L, w[0].startMs)
    assertEquals(41_450L, w[2].endMs)
  }

  @Test fun aWordNeverEndsPastTheNextWordOrThePiece() {
    // a quick word: the next one starts 57 ms after it, so it ends there, not a whole frame (80 ms) later; the first is held at 0
    val w = WordTimes.fromTokens(listOf("▁go", "▁on"), floatArrayOf(0.125f, 0.1875f), seconds = 0.1, baseMs = 0)
    assertEquals(0L, w[0].startMs)
    assertEquals(57L, w[0].endMs)
    assertEquals(100L, w[1].endMs)   // the last word is held to the piece's length
  }

  @Test fun noTimesWhenTheModelGaveNoneOrTheCountsDiffer() {
    assertTrue(WordTimes.fromTokens(tokens, FloatArray(0), 2.0, 0).isEmpty())
    assertTrue(WordTimes.fromTokens(tokens, floatArrayOf(0.125f), 2.0, 0).isEmpty())
    assertTrue(WordTimes.fromTokens(emptyList(), FloatArray(0), 2.0, 0).isEmpty())
  }

  @Test fun forTextKeepsTheWordsOnlyWhenTheySpellTheText() {
    assertEquals(3, WordTimes.forText("the quarterly budget", tokens, stamps, 2.0, 0).size)
    // the cleaned text differs from what the tokens spell: no words, the caller keeps the segment's one time
    assertTrue(WordTimes.forText("the quarterly budgets", tokens, stamps, 2.0, 0).isEmpty())
    assertTrue(WordTimes.forText("", tokens, stamps, 2.0, 0).isEmpty())
  }

  @Test fun theSameCleaningRuleCutsTheTextAndTheWords() {
    // "no" said six times: the text is cut to four, so the words must be too, or the offsets would drift off the text
    val toks = listOf("▁so", "▁no", "▁no", "▁no", "▁no", "▁no", "▁no", "▁way")
    val ts = FloatArray(toks.size) { 0.25f * (it + 1) }
    val text = WordTimes.clean("so no no no no no no way", seconds = 3.0)
    assertEquals("so no no no no way", text)
    val words = WordTimes.forText(text, toks, ts, 3.0, 0)
    assertEquals(text, words.joinToString(" ") { it.text })
    // "way" is the eighth token: its own time is kept, not the sixth word's
    assertEquals(1_870L, words.last().startMs)
  }

  /**
   * What Parakeet (sherpa-onnx 1.13.8, the shipped int8 model) returned for the first speech piece of a Kokoro TTS clip, captured on
   * Crane: the piece began at 1 258 ms in the file; the model punctuates, the first token is the tail of a word the VAD cut into
   * ("ly" for "quarterly"), and "R", "d" are one word. The times asserted are what this code gives for it (the 130 ms bias already taken
   * off); the error against the TTS truth over whole clips is in internal/research/video-times.md.
   */
  @Test fun aRealParakeetResultBecomesTimedWords() {
    val toks = listOf(
      "ly", " b", "ud", "get", " me", "et", "ing", " start", "s", " at", " n", "ine", ",", " and", " the", " inv", "o", "ice", " for",
      " the", " R", "d", " tr", "act", "or", " was", " p", "a", "id", " on", " F", "r", "id", "ay",
    )
    val ts = floatArrayOf(
      0.48f, 0.56f, 0.64f, 0.80f, 0.96f, 1.04f, 1.20f, 1.36f, 1.52f, 1.60f, 1.76f, 1.84f, 2.08f, 2.24f, 2.40f, 2.48f, 2.64f, 2.80f,
      2.88f, 3.04f, 3.12f, 3.28f, 3.36f, 3.52f, 3.68f, 3.84f, 4.00f, 4.08f, 4.24f, 4.40f, 4.56f, 4.64f, 4.80f, 4.88f,
    )
    val text = "ly budget meeting starts at nine, and the invoice for the Rd tractor was paid on Friday"
    val words = WordTimes.forText(text, toks, ts, seconds = 5.83, baseMs = 1_258)
    assertEquals(17, words.size)
    assertEquals(text, words.joinToString(" ") { it.text })
    val at = words.associateBy({ it.text }, { it.startMs })
    assertNear(1_688L, at.getValue("budget"))
    assertNear(2_088L, at.getValue("meeting"))
    assertNear(2_888L, at.getValue("nine,"))
    assertNear(5_688L, at.getValue("Friday"))
    // every word ends after it starts and before the next one starts
    for (i in 0 until words.size - 1) {
      assertTrue(words[i].endMs > words[i].startMs)
      assertTrue(words[i].endMs <= words[i + 1].startMs)
    }
  }

  /** Float seconds to whole ms can land a millisecond either side of the exact value. */
  private fun assertNear(expected: Long, actual: Long) = assertTrue("expected ~$expected, was $actual", kotlin.math.abs(expected - actual) <= 1)

  @Test fun fasterThanAnyoneSpeaksIsDropped() {
    assertEquals("", WordTimes.clean("a b c d e f g h", seconds = 1.0))
    assertEquals("a b c d e f g h", WordTimes.clean("a b c d e f g h", seconds = 2.0))
  }

  @Test fun aRepeatedWordIsCutToFourCaseInsensitively() {
    assertEquals("the The the THE", WordTimes.clean("the The the THE the the", seconds = 5.0))
  }
}
