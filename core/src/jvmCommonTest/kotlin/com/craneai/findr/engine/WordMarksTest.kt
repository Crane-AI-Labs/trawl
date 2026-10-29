package com.craneai.findr.engine

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SpeechSegment
import com.craneai.findr.core.SpokenWord
import com.craneai.findr.core.TranscriptMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word marks: a recogniser that times its words (Parakeet) gets one mark per two words in the stored transcript piece, so "at 0:40"
 * lands within a word or so of where it was said; one that doesn't (Moonshine) and rows from before keep the one mark per speech segment, read by character share.
 */
class WordMarksTest {
  private fun w(text: String, s: Long, e: Long) = SpokenWord(text, s, e)

  /** Said at an even pace for 2.6 s, a 4.4 s pause, then 2.7 s more, all inside one 15 s segment's span. */
  private val words = listOf(
    w("the", 40_000, 40_200), w("quarterly", 40_300, 40_900), w("budget", 41_000, 41_400), w("meeting", 41_500, 41_900),
    w("is", 42_000, 42_100), w("moved", 42_200, 42_600),
    w("to", 47_000, 47_100), w("Thursday", 47_200, 47_800), w("because", 48_000, 48_400), w("the", 48_500, 48_600),
    w("room", 48_700, 49_000), w("flooded", 49_100, 49_700),
  )
  private val text = words.joinToString(" ") { it.text }
  private val segment = SpeechSegment(40_000, 55_000, text, words)

  private fun speech(vararg s: SpeechSegment) = TranscriptPieces.speech(AudioTranscript(s.toList(), emptyList(), 0.0))

  private fun momentOf(piece: TranscriptText, word: String): Long? {
    val pos = piece.text.indexOf(word)
    return TranscriptMarks.timeAt(TranscriptMarks.decode(piece.marks), piece.text.length, piece.startMs, piece.endMs, pos)
  }

  @Test fun everyTwoWordsGetAMarkFromTheFirstWordsStartToTheSecondsEnd() {
    val piece = speech(segment).single()
    val marks = TranscriptMarks.decode(piece.marks)
    assertEquals(words.size / 2, marks.size)
    var at = 0
    for ((i, m) in marks.withIndex()) {
      val pair = words.subList(2 * i, 2 * i + 2)
      assertEquals(at, m.offset)
      assertEquals(pair.first().startMs, m.startMs)
      assertEquals(pair.last().endMs, m.endMs)
      at += pair.sumOf { it.text.length + 1 }
    }
  }

  @Test fun anOddWordCountLeavesTheLastMarkOnOneWord() {
    val three = words.take(3)
    val seg = SpeechSegment(40_000, 42_000, three.joinToString(" ") { it.text }, three)
    val marks = TranscriptMarks.decode(speech(seg).single().marks)
    assertEquals(2, marks.size)
    assertEquals(41_000L, marks.last().startMs)
    assertEquals(41_400L, marks.last().endMs)
  }

  @Test fun aHitOnTheFirstWordOfAPairIsPlacedAtThatWord() {
    val piece = speech(segment).single()
    assertEquals(41_000L, momentOf(piece, "budget"))
    assertEquals(48_000L, momentOf(piece, "because"))
    assertEquals(48_700L, momentOf(piece, "room"))
  }

  @Test fun aHitOnTheSecondWordIsPlacedWithinAWordOfIt() {
    val piece = speech(segment).single()
    // "to Thursday" spans 47 000..47 800 and Thursday is said at 47 200: its character share lands within a word's length of it
    assertTrue(kotlin.math.abs(momentOf(piece, "Thursday")!! - 47_200) < 300)
    assertTrue(kotlin.math.abs(momentOf(piece, "quarterly")!! - 40_300) < 300)
  }

  @Test fun theSameHitOnTheSegmentMarkSitsAtTheCharacterShare() {
    // Moonshine, or a row from before: no words, one mark for the whole 15 s stretch, so a word is placed by its share of the text
    val piece = speech(SpeechSegment(40_000, 55_000, text)).single()
    assertEquals(1, TranscriptMarks.decode(piece.marks).size)
    val at = momentOf(piece, "Thursday")!!
    val share = piece.text.indexOf("Thursday").toDouble() / piece.text.length
    assertEquals(40_000 + (15_000 * share).toLong(), at)
    // ...which is seconds off the word: the speech filled only 9.7 s of the 15 s stretch
    assertTrue("segment placement is not on the word: $at", kotlin.math.abs(at - 47_200) > 1_000)
  }

  @Test fun wordsThatDoNotSpellTheTextFallBackToTheSegmentMark() {
    val broken = SpeechSegment(40_000, 55_000, text, words.dropLast(1))
    val marks = TranscriptMarks.decode(speech(broken).single().marks)
    assertEquals(1, marks.size)
    assertEquals(55_000L, marks.single().endMs)
  }

  @Test fun segmentsWithAndWithoutWordsMixInOnePiece() {
    val plain = SpeechSegment(60_000, 64_000, "no times for this one")
    val piece = speech(segment, plain).single()
    val marks = TranscriptMarks.decode(piece.marks)
    assertEquals(words.size / 2 + 1, marks.size)
    assertEquals(60_000L, marks.last().startMs)
    assertEquals(piece.text.indexOf("no times"), marks.last().offset)
    assertEquals(41_000L, momentOf(piece, "budget"))
  }

  @Test fun offsetsStayRightAcrossSegmentsInTheSamePiece() {
    val second = SpeechSegment(
      60_000, 63_000, "alpha beta gamma delta",
      listOf(w("alpha", 60_100, 60_500), w("beta", 60_600, 61_000), w("gamma", 62_000, 62_400), w("delta", 62_500, 62_900)),
    )
    val piece = speech(segment, second).single()
    assertEquals(60_100L, momentOf(piece, "alpha"))
    assertEquals(62_000L, momentOf(piece, "gamma"))
    assertEquals(40_000L, momentOf(piece, "the"))
  }

  @Test fun theMarksFormatIsTheOldDecodersFormat() {
    val encoded = speech(segment).single().marks!!
    // offset:startMs:endMs joined by commas, nothing else: what every build since the marks column reads
    assertTrue(encoded.split(',').all { e -> e.split(':').let { f -> f.size == 3 && f.all { it.toLongOrNull() != null } } })
    assertTrue(encoded.startsWith("0:40000:40900,14:41000:41900"))
  }

  @Test fun aFullPiecesMarksStaySmall() {
    val long = (0 until 70).map { w("word$it", 1_000L * it, 1_000L * it + 300) }
    val seg = SpeechSegment(0, 70_000, long.joinToString(" ") { it.text }, long)
    // ~400 characters of speech is about 70 words: about 10 bytes a word
    assertTrue(speech(seg).single().marks!!.length < 1_000)
  }

  @Test fun noMarkAndNoStartMeansNoTime() {
    assertNull(TranscriptMarks.timeAt(emptyList(), 10, null, null, 3))
  }
}
