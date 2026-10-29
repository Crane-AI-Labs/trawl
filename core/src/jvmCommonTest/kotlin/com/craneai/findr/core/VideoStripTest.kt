package com.craneai.findr.core

import com.craneai.findr.core.VideoStrip.KeyframeList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStripTest {
  private fun every(stepMs: Long, durationMs: Long) = KeyframeList((0L..durationMs step stepMs).toList())

  @Test fun fiveEvenlySpacedSpotsAroundTheMoment() {
    assertEquals(listOf(8_000L, 10_000L, 12_000L, 14_000L, 16_000L), VideoStrip.targets(12_000, 60_000))
  }

  @Test fun aMomentNearTheStartSlidesTheWindowSoItStaysEightSecondsWide() {
    assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L), VideoStrip.targets(1_000, 60_000))
  }

  @Test fun aMomentNearTheEndSlidesTheWindowBackFromTheLastFrame() {
    val t = VideoStrip.targets(59_500, 60_000)
    assertEquals(5, t.size)
    assertEquals(59_900L, t.last())          // 100 ms guard before the end
    assertEquals(51_900L, t.first())
    assertTrue(t.zipWithNext().all { (a, b) -> b - a == 2_000L })
  }

  @Test fun aMomentPastTheEndIsPulledBackInside() {
    val t = VideoStrip.targets(70_000, 60_000)
    assertEquals(59_900L, t.last())
  }

  @Test fun aVideoShorterThanTheWindowIsCoveredWhole() {
    val t = VideoStrip.targets(3_000, 6_100)
    assertEquals(0L, t.first())
    assertEquals(6_000L, t.last())
    assertEquals(5, t.size)
  }

  @Test fun aVeryShortVideoGetsFewerPictures() {
    // 1.1 s: 1000 ms of video after the guard, 500 ms minimum step -> 3 pictures.
    assertEquals(listOf(0L, 500L, 1_000L), VideoStrip.targets(400, 1_100))
    // Under half a second there is one moment to show, and no strip.
    val plan = VideoStrip.plan(200, 400, every(1_000, 400))
    assertEquals(1, plan.slots.size)
    assertFalse(plan.worthShowing)
  }

  @Test fun anUnknownLengthLeavesTheFarEndOpen() {
    assertEquals(listOf(0L, 1_500L, 3_000L, 4_500L, 6_000L), VideoStrip.targets(2_000, null))
    assertEquals(34_000L, VideoStrip.targets(30_000, 0).last())
  }

  @Test fun aCameraVideoGetsFiveDistinctExactPictures() {
    val plan = VideoStrip.plan(12_400, 60_000, every(1_000, 60_000))
    assertEquals(5, plan.slots.size)
    assertTrue(plan.slots.all { it.exact })
    assertEquals(plan.slots.map { it.atMs }.distinct(), plan.slots.map { it.atMs })
    assertTrue(plan.worthShowing)
    // Modelled A06 time: about a second on two workers (an exact frame here is at most 30 frames past its key frame).
    assertTrue("wall ${plan.wallMs(2)}", plan.wallMs(2) <= 1_300)
  }

  @Test fun aLongKeyframeGapSharesOnePictureBetweenNeighbouringSpots() {
    // Key frames every 8.33 s, as ffmpeg's default makes them. Spots 8..16 s fall in two gaps.
    val keys = KeyframeList(listOf(0L, 8_330L, 16_660L, 24_990L))
    val plan = VideoStrip.plan(12_000, 60_000, keys, tune = VideoStrip.Tuning(exactMaxFrames = 0))
    // Every picture is a key frame, none repeated: spots at 8, 10, 12, 14, 16 s round to 8.33 s (4 of them) and 16.66 s.
    assertTrue(plan.slots.none { it.exact })
    assertEquals(plan.slots.map { it.atMs }.distinct(), plan.slots.map { it.atMs })
    assertEquals(listOf(8_330L, 16_660L), plan.slots.map { it.atMs })
    assertEquals(2 * VideoStrip.keyCostMs(), plan.workMs)
  }

  @Test fun aKeyFrameFetchTakesTheNearerKeyFrameNotAlwaysTheEarlierOne() {
    val keys = KeyframeList(listOf(0L, 10_000L))
    val plan = VideoStrip.plan(9_000, 60_000, keys, tune = VideoStrip.Tuning(exactMaxFrames = 0))
    // 5, 7, 9, 11, 13 s -> nearest key frames 0, 10, 10, 10, 10 s: the 5 s spot is a tie and takes the earlier one.
    assertEquals(listOf(0L, 10_000L), plan.slots.map { it.atMs })
  }

  @Test fun anExactFrameBeatsTheKeyFrameThatLandsRightNextToIt() {
    // Key frames at 0 and 10 s; the spot at 10.2 s is 6 frames past its key frame (cheap -> exact) and the key frame at 10 s is
    // within half a second of it, so it is not added a second time.
    val keys = KeyframeList(listOf(0L, 10_000L, 30_000L))
    val plan = VideoStrip.plan(10_200, 60_000, keys)
    assertEquals(plan.slots.map { it.atMs }.distinct(), plan.slots.map { it.atMs })
    assertTrue(plan.slots.zipWithNext().all { (a, b) -> b.atMs - a.atMs >= VideoStrip.SAME_PICTURE_MS })
  }

  @Test fun theBudgetTurnsTheCostliestExactFramesIntoKeyFrames() {
    val keys = every(2_000, 60_000)   // every spot but those on a key frame is up to 60 frames past
    val loose = VideoStrip.plan(13_300, 60_000, keys, tune = VideoStrip.Tuning(exactMaxFrames = 60, budgetMs = 100_000))
    val tight = VideoStrip.plan(13_300, 60_000, keys, tune = VideoStrip.Tuning(exactMaxFrames = 60, budgetMs = 1_400))
    assertTrue(loose.workMs > tight.workMs)
    assertTrue("work ${tight.workMs}", tight.workMs <= 1_400 || tight.slots.none { it.exact })
    assertTrue(tight.slots.count { it.exact } < loose.slots.count { it.exact })
  }

  @Test fun theMatchedMomentIsFetchedFirstAndTheStripFillsOutwards() {
    val plan = VideoStrip.plan(12_000, 60_000, every(1_000, 60_000))
    val order = plan.fetchOrder.map { plan.slots[it].atMs }
    assertEquals(12_000L, order.first())
    assertEquals(plan.slots[plan.anchor].atMs, 12_000L)
    val dist = order.map { kotlin.math.abs(it - 12_000L) }
    assertEquals(dist.sorted(), dist)
  }

  @Test fun noKeyFrameInformationStillGivesFiveKeyFrameFetches() {
    val plan = VideoStrip.plan(12_000, 60_000, null)
    assertEquals(5, plan.slots.size)
    assertTrue(plan.slots.none { it.exact })
  }

  @Test fun theCostModelIsTheA06Fit() {
    assertEquals(255L, VideoStrip.keyCostMs())        // 0.245 + 0.010
    assertEquals(245L + 310L, VideoStrip.exactCostMs(30))   // 31 frames decoded
  }

  @Test fun wallTimeSplitsTheWorkAcrossWorkers() {
    val plan = VideoStrip.plan(12_000, 60_000, every(1_000, 60_000))
    assertEquals(plan.workMs, plan.wallMs(1))
    assertTrue(plan.wallMs(2) < plan.wallMs(1))
    assertTrue(plan.wallMs(2) >= plan.workMs / 2)
  }
}
