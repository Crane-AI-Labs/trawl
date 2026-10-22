package com.craneai.findr.extract

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotDetectorTest {
  private val size = 64 * 64

  /** A frame with a fixed texture (so pixels differ from each other) shifted in brightness and with sensor noise. */
  private fun frame(base: Int, texture: Long = 1, noise: Int = 2, seed: Long = 0, spread: Int = 60): IntArray {
    val tex = Random(texture)
    val jitter = Random(seed)
    return IntArray(size) { (base + tex.nextInt(spread) - spread / 2 + (if (noise > 0) jitter.nextInt(2 * noise + 1) - noise else 0)).coerceIn(0, 255) }
  }

  private fun still(n: Int, base: Int, texture: Long, seed0: Long = 0) = List(n) { frame(base, texture, 2, seed0 + it) }

  @Test fun identicalFramesAreDistanceZeroAndOppositesAreNearOne() {
    val a = frame(40, noise = 0)
    assertEquals(0.0, ShotDetector.distance(a, a), 0.0)
    assertTrue(ShotDetector.distance(IntArray(size), IntArray(size) { 255 }) > 0.99)
  }

  @Test fun aStaticVideoIsOneShot() {
    assertEquals(listOf(0), ShotDetector.boundaries(still(30, 100, texture = 5)))
  }

  @Test fun aHardCutIsFoundAtTheRightFrame() {
    val frames = still(10, 60, texture = 1) + still(10, 190, texture = 2, seed0 = 100)
    assertEquals(listOf(0, 10), ShotDetector.boundaries(frames))
  }

  @Test fun aCutBetweenTwoDifferentSceneOfSimilarBrightnessIsStillFound() {
    // Same brightness histogram, different layout: only the pixel difference notices.
    val frames = List(8) { frame(128, 1, 2, it.toLong(), spread = 120) } + List(8) { frame(128, 99, 2, 50L + it, spread = 120) }
    assertEquals(listOf(0, 8), ShotDetector.boundaries(frames))
  }

  @Test fun aSlowFadeIsOneBoundaryNotNoneAndNotMany() {
    // 20 frames easing from dark to bright over the same texture: every step is small, the drift is large.
    val frames = List(6) { frame(60, 3, 2, it.toLong(), spread = 100) } +
      List(20) { frame(60 + it * 7, 3, 2, 200L + it, spread = 100) } + List(6) { frame(200, 3, 2, 400L + it, spread = 100) }
    val b = ShotDetector.boundaries(frames)
    assertTrue("fade should produce at least one boundary: $b", b.size >= 2)
    assertTrue("but not one per frame: $b", b.size <= 4)
  }

  @Test fun staticThenCutThenFade() {
    val frames = still(8, 50, 1) + still(8, 200, 2, 100) + List(16) { frame(200 - it * 9, 2, 2, 300L + it) }
    val b = ShotDetector.boundaries(frames)
    assertEquals(0, b[0])
    assertEquals(8, b[1])
    assertTrue("fade tail adds a boundary after the cut: $b", b.size >= 3 && b[2] > 16)
  }

  @Test fun anAdaptiveThresholdIgnoresAShakyVideoButStillFindsItsCut() {
    // Camera shake: big random steps all the time; a real cut has to stand clear of that.
    val rnd = Random(7)
    val shaky = List(20) { IntArray(size) { i -> (110 + (i * 31 % 40) + rnd.nextInt(40)).coerceIn(0, 255) } }
    val boundaries = ShotDetector.boundaries(shaky)
    assertTrue("noise alone should not cut every frame: $boundaries", boundaries.size <= 3)
  }

  @Test fun noFramesNoBoundaries() {
    assertTrue(ShotDetector.boundaries(emptyList()).isEmpty())
  }

  @Test fun spansRunFromOneBoundaryToTheNextAndTheLastToTheEnd() {
    val times = longArrayOf(500, 1500, 2500, 3500, 4500)
    val spans = ShotDetector.spans(listOf(0, 3), times, 5000)
    assertEquals(listOf(ShotSpan(0, 3500), ShotSpan(3500, 5000)), spans)
    assertEquals(1750, spans[0].midMs)
  }

  @Test fun pickKeepsTheLongestShotsInTimeOrder() {
    val shots = listOf(ShotSpan(0, 1000), ShotSpan(1000, 5000), ShotSpan(5000, 5500), ShotSpan(5500, 9000))
    assertEquals(listOf(ShotSpan(1000, 5000), ShotSpan(5500, 9000)), ShotDetector.pick(shots, 2))
    assertEquals(shots, ShotDetector.pick(shots, 10))
  }

  @Test fun sampleTimesAreAboutOnePerSecondOffsetByHalfAStep() {
    val t = ShotDetector.sampleTimes(10_000)
    assertEquals(10, t.size)
    assertEquals(500L, t[0])
    assertEquals(1000L, t[1] - t[0])
    assertTrue(t.all { it < 10_000 })
  }

  @Test fun aShortVideoGetsOneMiddleSample() {
    assertEquals(listOf(1200L), ShotDetector.sampleTimes(2400).toList())
    assertEquals(listOf(0L), ShotDetector.sampleTimes(0).toList())
  }

  @Test fun aVeryLongVideoIsCappedAtMaxSamples() {
    val t = ShotDetector.sampleTimes(3 * 3600 * 1000L, maxSamples = 600)
    assertTrue("${t.size} samples", t.size in 590..601)
  }
}
