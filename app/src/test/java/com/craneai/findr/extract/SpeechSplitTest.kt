package com.craneai.findr.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSplitTest {
  private val rate = 16_000

  @Test fun aSegmentWithinTheCapIsLeftAlone() {
    val s = FloatArray(10 * rate) { 0.3f }
    assertEquals(listOf(0 until 10 * rate), SpeechSplit.ranges(s, s.size, 15 * rate))
    assertEquals(listOf(0 until 15 * rate), SpeechSplit.ranges(FloatArray(15 * rate), 15 * rate, 15 * rate))
  }

  @Test fun aMinuteIsCutIntoPiecesNoLongerThanTheCapThatCoverEverySampleOnce() {
    val n = 59 * rate
    val s = FloatArray(n) { 0.3f }
    val r = SpeechSplit.ranges(s, n, 15 * rate)
    assertTrue(r.size >= 4)
    assertEquals(0, r.first().first)
    assertEquals(n - 1, r.last().last)
    for (i in 1 until r.size) assertEquals(r[i - 1].last + 1, r[i].first)
    for (x in r) assertTrue("piece too long: ${x.last + 1 - x.first}", x.last + 1 - x.first <= 15 * rate)
    assertTrue("the last piece is at least a second", r.last().last + 1 - r.last().first >= rate)
  }

  @Test fun theCutLandsInTheQuietStretchNearTheCap() {
    val n = 40 * rate
    val s = FloatArray(n) { 0.5f }
    val quiet = 13 * rate + 8_000          // a 50 ms gap at 13.5 s, inside the 3 s before the 15 s cap
    for (i in quiet until quiet + 800) s[i] = 0f
    val first = SpeechSplit.ranges(s, n, 15 * rate).first()
    assertTrue("cut at ${first.last + 1}, quiet gap $quiet", first.last + 1 in quiet..quiet + 800)
  }

  @Test fun aShortRemainderIsNeverLeftAsATinyPiece() {
    val n = 15 * rate + 3_000
    val r = SpeechSplit.ranges(FloatArray(n) { 0.2f }, n, 15 * rate)
    assertEquals(2, r.size)
    assertTrue(r.last().last + 1 - r.last().first >= rate)
  }
}
