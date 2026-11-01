package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** A deck keeps one layout and changes its words: the signature sees the words change, and ignores re-encoding noise. */
class FrameSignatureTest {
  private val w = 768
  private val h = 432

  /** A white page with bars of "text": each line is a run of strokes whose widths and gaps come from [seed]. */
  private fun page(vararg seeds: Long, noise: Int = 0, noiseSeed: Long = 99): IntArray {
    val px = IntArray(w * h) { 255 }
    seeds.forEachIndexed { line, seed ->
      val rnd = Random(seed)
      var x = 50
      val top = 110 + line * 70
      while (x < 600) {
        val stroke = 2 + rnd.nextInt(5)
        val gap = 3 + rnd.nextInt(9)
        for (y in top until top + 24) for (dx in 0 until stroke) px[y * w + x + dx] = 20
        x += stroke + gap
      }
    }
    if (noise > 0) {
      val n = Random(noiseSeed)
      for (i in px.indices) px[i] = (px[i] + n.nextInt(2 * noise + 1) - noise).coerceIn(0, 255)
    }
    return px
  }

  private fun sig(px: IntArray) = FrameSignature.of(px, w, h)

  @Test fun theSameSlideReEncodedChangesNoTile() {
    val a = sig(page(1, 2, 3, 4))
    val b = sig(page(1, 2, 3, 4, noise = 3))
    assertEquals(0, a.changedTiles(b))
    assertFalse(a.mayShowOtherText(b))
    assertEquals(0, a.changedTiles(a))
  }

  @Test fun anotherSlideOfTheSameLayoutChangesManyTiles() {
    val a = sig(page(1, 2, 3, 4))
    val b = sig(page(11, 12, 13, 14))
    assertTrue("changed ${a.changedTiles(b)}", a.changedTiles(b) > 100)
    assertTrue(a.mayShowOtherText(b))
  }

  @Test fun oneChangedLineIsEnough() {
    val a = sig(page(1, 2, 3, 4))
    val b = sig(page(1, 2, 30, 4))
    assertTrue("changed ${a.changedTiles(b)}", a.mayShowOtherText(b))
    assertFalse("a line that did not change is no change", sig(page(1, 2, 3, 4)).mayShowOtherText(a))
  }

  @Test fun theGridCoversAFrameOfAnyShape() {
    // a portrait video, a tiny frame and a frame smaller than the grid all make a signature without an empty tile
    for ((fw, fh) in listOf(432 to 768, 40 to 20, 1 to 1, 64 to 36)) {
      val px = IntArray(fw * fh) { (it * 7) % 256 }
      val s = FrameSignature.of(px, fw, fh)
      assertEquals(0, s.changedTiles(FrameSignature.of(px.copyOf(), fw, fh)))
    }
  }

  @Test fun aLuminanceStepOfOneBelowTheThresholdIsNotAChange() {
    val flat = IntArray(w * h) { 100 }
    val nearly = IntArray(w * h) { 100 + FrameSignature.MEAN_STEP - 1 }
    val clearly = IntArray(w * h) { 100 + FrameSignature.MEAN_STEP }
    assertEquals(0, sig(flat).changedTiles(sig(nearly)))
    assertEquals(FrameSignature.COLS * FrameSignature.ROWS, sig(flat).changedTiles(sig(clearly)))
  }

  // ---- which merged frames get read ---------------------------------------------------------------------------------

  private fun slide(n: Long) = sig(page(n, n + 100, n + 200, n + 300))
  private val minuteMs = 60_000L

  @Test fun aMergedFrameShowingNewWordsIsReadOnceAndItsSlideCovered() {
    val reads = MergedFrameReads(seconds = 60)
    val slide1 = sig(page(1, 2, 3, 4))
    val slide2 = sig(page(11, 12, 13, 14))
    reads.covered(0, slide1)                                   // the kept frame showed words, and was read
    assertFalse("the same slide again needs no read", reads.claim(0, sig(page(1, 2, 3, 4, noise = 2)), 5_000))
    assertTrue("another slide of the same lineage is read", reads.claim(0, slide2, 10_000))
    assertFalse("its later frames are covered by that read", reads.claim(0, sig(page(11, 12, 13, 14, noise = 2)), 15_000))
    assertFalse("a return to the first slide is not read again", reads.claim(0, slide1, 20_000))
    assertEquals(1, reads.used)
  }

  @Test fun aLineageWhoseFrameShowedNoWordsNeverClaims() {
    // A static camera clip: its light and subject move (170 to 205 tiles change on a choir clip) but it has nothing to read.
    val reads = MergedFrameReads(seconds = 60)
    assertFalse("a lineage no text frame was recorded for", reads.mayClaim(0, 5_000))
    val claimed = (1L..40L).count { reads.claim(0, slide(it), it * 1_000) }
    assertEquals(0, claimed)
    assertEquals(0, reads.used)
    reads.covered(1, sig(page(1, 2, 3, 4)))
    assertTrue("a lineage with words does", reads.mayClaim(1, 5_000))
  }

  @Test fun lineagesAreIndependent() {
    val reads = MergedFrameReads(seconds = 60)
    val a = sig(page(1, 2, 3, 4))
    reads.covered(0, a)
    reads.covered(1, slide(50))
    assertFalse("lineage 0 has read it", reads.claim(0, a, 1_000))
    assertTrue("a picture read for lineage 0 says nothing about lineage 1", reads.claim(1, a, 2_000))
  }

  @Test fun theAllowanceIsPerMinuteSoItCannotBeSpentUpFront() {
    val reads = MergedFrameReads(seconds = 180)
    reads.covered(0, slide(1))
    val first = (10L..30L).count { reads.claim(0, slide(it), (it - 10) * 1_000) }   // 21 changing frames in minute 0
    assertEquals(MergedFrameReads.MAX_PER_MINUTE, first)
    assertFalse("minute 0 is spent", reads.mayClaim(0, 59_000))
    assertTrue("minute 1 has its own allowance", reads.mayClaim(0, minuteMs + 1_000))
    reads.covered(1, slide(500))   // (a second lineage, so that the lineage cap of 12 reads is not what ends minute 1)
    val second = (100L..130L).count { reads.claim(1, slide(it), minuteMs + (it - 100) * 1_000) }
    assertEquals(MergedFrameReads.MAX_PER_MINUTE, second)
    assertTrue(reads.mayClaim(0, 2 * minuteMs))
    assertEquals(2 * MergedFrameReads.MAX_PER_MINUTE, reads.used)
  }

  @Test fun theLastWindowOfAVideoGetsOnlyWhatItsLengthIsWorth() {
    // 90 s: a full first minute and a 30 s tail that is worth 3 reads, and no more than that however many frames it has
    val reads = MergedFrameReads(seconds = 90)
    reads.covered(0, slide(1))
    val tail = (10L..40L).count { reads.claim(0, slide(it), minuteMs + (it - 10) * 500) }
    assertEquals(3, tail)
    assertFalse(reads.mayClaim(0, minuteMs + 29_000))
  }

  @Test fun aShortClipHasNoAllowanceAndNeedsNoLook() {
    for (seconds in listOf(0, 1, 5, 9)) {
      val reads = MergedFrameReads(seconds)
      assertFalse("$seconds s has no allowance, so no frame needs a signature", reads.enabled)
      reads.covered(0, slide(1))
      assertFalse(reads.mayClaim(0, 1_000))
      assertFalse(reads.claim(0, slide(2), 1_000))
      assertEquals(0, reads.used)
    }
    val ten = MergedFrameReads(seconds = 10)
    assertTrue(ten.enabled)
    ten.covered(0, slide(1))
    assertTrue("ten seconds is worth one read", ten.claim(0, slide(2), 5_000))
    assertFalse("and no second", ten.claim(0, slide(3), 6_000))
  }

  @Test fun aLineageStopsClaimingOnceItHasReadItsShare() {
    val reads = MergedFrameReads(seconds = 24 * 60)   // minutes enough that the lineage cap, not the allowance, is what stops it
    reads.covered(0, slide(1))
    var claimed = 0
    for (n in 2L..60L) if (reads.claim(0, slide(n), (n - 2) * 20_000)) claimed++   // minute buckets never run out: 3 changing frames each
    assertEquals(MergedFrameReads.MAX_READS_PER_LINEAGE - 1, claimed)   // the kept frame's read is the first of its 12
    assertFalse(reads.mayClaim(0, 59 * 60_000L))
    assertEquals(claimed, reads.used)
  }

  @Test fun allowanceIsSixAMinuteInWholeReads() {
    assertEquals(0, MergedFrameReads.allowanceFor(0))
    assertEquals(0, MergedFrameReads.allowanceFor(9))
    assertEquals(1, MergedFrameReads.allowanceFor(10))
    assertEquals(2, MergedFrameReads.allowanceFor(25))
    assertEquals(6, MergedFrameReads.allowanceFor(60))
    assertEquals(6, MergedFrameReads.allowanceFor(600))   // a span is at most a minute: the rest is the next minute's
  }
}
