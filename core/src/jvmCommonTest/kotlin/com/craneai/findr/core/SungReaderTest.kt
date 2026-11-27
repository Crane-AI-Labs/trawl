package com.craneai.findr.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SungReaderTest {
  private val rate = 100   // 100 samples a second keeps the arrays small
  private val read = ArrayList<Pair<Long, Int>>()

  /** Fixed cuts at the cap (the engines pass SpeechSplit.ranges, which looks for a quiet spot). */
  private fun reader() = SungReader(rate, maxSamples = 15 * rate, minSamples = rate / 3,
    split = { _, n, cap -> (0 until n step cap).map { it until minOf(n, it + cap) } }) { x, from ->
    read += from to x.size
    SpeechSegment(from * 10, (from + x.size) * 10, "la", sung = true)
  }

  private fun win(s: Int) = FloatArray(s * rate)

  @Test fun windowsWithoutSingingReadNothing() {
    val r = reader()
    r.window(0, win(10), listOf("Speech", "Music"))
    r.window(1000, win(10), listOf("Music", "Guitar"))
    r.finish()
    assertTrue(read.isEmpty())
    assertFalse(r.covers(500))
    val vad = listOf(SpeechSegment(1000, 2000, "hello"))
    assertEquals(vad, SungReader.merge(vad, r, 0, rate))
  }

  @Test fun aSungRunIsReadInPiecesAndReplacesTheVadPiecesInsideIt() {
    val r = reader()
    r.window(0, win(10), listOf("Speech"))
    r.window(1000, win(10), listOf("Music", "Singing"))
    r.window(2000, win(10), listOf("Choir"))
    r.window(3000, win(10), listOf("Male singing"))
    r.window(4000, win(10), listOf("Speech"))   // ends the run
    r.finish()
    assertEquals(3000, read.sumOf { it.second })   // the 30 s run, read once
    assertTrue(read.all { it.second <= 15 * rate })
    assertEquals(1000L, read.first().first)
    assertTrue(r.covers(1000) && r.covers(3999))
    assertFalse(r.covers(999) || r.covers(4000))
    // Times in ms: 100 samples a second means sample s is at s * 10 ms.
    val vad = listOf(SpeechSegment(500, 900, "spoken"), SpeechSegment(15000, 16000, "fragment"), SpeechSegment(41000, 42000, "after"))
    val merged = SungReader.merge(vad, r, 0, rate)
    assertEquals(listOf("spoken", "la", "la", "after"), merged.map { it.text })
    assertTrue(merged.filter { it.text == "la" }.all { it.sung })
  }

  @Test fun theLastRunIsReadAtFinish() {
    val r = reader()
    r.window(0, win(10), listOf("Vocal music"))
    assertTrue(read.isEmpty())   // waits: the run may go on
    r.finish()
    assertEquals(listOf(0L to 1000), read)
    assertEquals(1000L, r.readSamples)
  }
}
