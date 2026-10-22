package com.craneai.findr.engine

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SoundTags
import com.craneai.findr.core.SpeechSegment
import com.craneai.findr.extract.SherpaAsrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPiecesTest {
  private fun seg(start: Long, end: Long, text: String) = SpeechSegment(start, end, text)

  @Test fun shortSpeechJoinsIntoOnePiece() {
    val t = AudioTranscript(listOf(seg(1000, 3000, "hello there"), seg(4000, 6000, "how are you")), emptyList(), 10.0)
    val p = TranscriptPieces.speech(t)
    assertEquals(1, p.size)
    assertEquals("hello there how are you", p[0].text)
    assertEquals(1000L, p[0].startMs)
    assertEquals(6000L, p[0].endMs)
  }

  @Test fun longSpeechSplitsAtSegmentBoundaries() {
    val words = "word ".repeat(30).trim()   // 149 chars
    val t = AudioTranscript((0 until 6).map { seg(it * 10_000L, it * 10_000L + 9_000, words) }, emptyList(), 60.0)
    val p = TranscriptPieces.speech(t)
    assertTrue(p.size >= 2)
    assertTrue(p.all { it.text.length <= TranscriptPieces.MAX_CHARS })
    assertEquals(6, p.sumOf { piece -> piece.text.split(words).size - 1 })   // nothing lost or cut mid-segment
    assertEquals(0L, p.first().startMs)
    assertEquals(59_000L, p.last().endMs)
  }

  @Test fun blankSegmentsAndNoSpeechGiveNothing() {
    assertTrue(TranscriptPieces.speech(AudioTranscript(listOf(seg(0, 1000, "  ")), emptyList(), 5.0)).isEmpty())
    assertTrue(TranscriptPieces.speech(AudioTranscript(emptyList(), emptyList(), 5.0)).isEmpty())
  }

  @Test fun soundsListEachLabelOnceInOrder() {
    val t = AudioTranscript(emptyList(), listOf(
      SoundTags(0, 10_000, listOf("Dog", "Bark")), SoundTags(10_000, 20_000, listOf("Dog", "Speech"))), 20.0)
    val s = TranscriptPieces.sounds(t)!!
    assertEquals("Sounds: Dog, Bark, Speech", s.text)
    assertNull(s.startMs)   // the line stands for the whole file, so it points at no moment
    assertNull(s.endMs)
    assertNull(TranscriptPieces.sounds(AudioTranscript(emptyList(), emptyList(), 1.0)))
  }

  @Test fun pcmReadsLittleEndianSigned16() {
    val wav = ByteArray(44) + byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x7F)
    val x = SherpaAsrEngine.pcm(wav)
    assertEquals(3, x.size)
    assertEquals(0.5f, x[0], 1e-6f)
    assertEquals(-0.5f, x[1], 1e-6f)
    assertEquals(32767 / 32768f, x[2], 1e-6f)
  }

  @Test fun hallucinationGuard() {
    assertEquals("", SherpaAsrEngine.clean("the the the the the the the the the the the the", 1.0))   // 12 words/s
    assertEquals("a b b b b c", SherpaAsrEngine.clean("a b b b b b b b c", 5.0))                       // a word run is cut to 4
    assertEquals("hello world", SherpaAsrEngine.clean("  hello world ", 2.0))
  }
}
