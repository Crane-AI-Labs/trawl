package com.craneai.findr.extract

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavTest {
  @Test fun headerIsCorrectRiffPcm16Mono16k() {
    val wav = Wav.encode(shortArrayOf(1, -2, 300))
    assertEquals(44 + 6, wav.size)
    assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
    assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
    assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(36 + 6, b.getInt(4))   // RIFF size = file size - 8
    assertEquals(16, b.getInt(16))
    assertEquals(1, b.getShort(20).toInt())   // PCM
    assertEquals(1, b.getShort(22).toInt())   // mono
    assertEquals(16_000, b.getInt(24))
    assertEquals(32_000, b.getInt(28))        // byte rate
    assertEquals(2, b.getShort(32).toInt())   // block align
    assertEquals(16, b.getShort(34).toInt())
    assertEquals(6, b.getInt(40))
    assertEquals(1, b.getShort(44).toInt())
    assertEquals(-2, b.getShort(46).toInt())
    assertEquals(300, b.getShort(48).toInt())
  }

  @Test fun emptyAudioIsJustAHeader() {
    assertEquals(44, Wav.encode(ShortArray(0)).size)
  }
}

class ResamplerTest {
  private fun sine(freq: Double, rate: Int, seconds: Double, amp: Double = 10_000.0) =
    FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

  private fun run(input: FloatArray, inRate: Int, chunk: Int): ShortArray {
    val r = Resampler(inRate, 16_000)
    val out = ArrayList<Short>()
    var i = 0
    while (i < input.size) {
      val n = minOf(chunk, input.size - i)
      r.process(input.copyOfRange(i, i + n), n).forEach { out.add(it) }
      i += n
    }
    r.finish().forEach { out.add(it) }
    return out.toShortArray()
  }

  /** Magnitude of the DFT at `freq`, normalised so a full-scale sine of amplitude A gives about A. */
  private fun magnitude(x: ShortArray, rate: Int, freq: Double, from: Int, to: Int): Double {
    var re = 0.0
    var im = 0.0
    for (n in from until to) {
      val w = 0.5 - 0.5 * cos(2 * PI * (n - from) / (to - from))   // Hann, to keep leakage out of the comparison
      re += w * x[n] * cos(2 * PI * freq * n / rate)
      im -= w * x[n] * sin(2 * PI * freq * n / rate)
    }
    return 2 * hypot(re, im) / (0.5 * (to - from))
  }

  @Test fun a1kHzSineFrom44100IsStill1kHzAt16k() {
    val out = run(sine(1000.0, 44_100, 1.0), 44_100, chunk = 4096)
    assertEquals("one second of audio in, one second out", 16_000.0, out.size.toDouble(), 2.0)
    val from = 800
    val to = out.size - 800   // skip the filter's edge
    var best = 0.0
    var bestFreq = 0.0
    var f = 100.0
    while (f <= 7900.0) {
      val m = magnitude(out, 16_000, f, from, to)
      if (m > best) { best = m; bestFreq = f }
      f += 50.0
    }
    assertEquals("dominant frequency", 1000.0, bestFreq, 50.0)
    assertEquals("amplitude preserved", 10_000.0, best, 500.0)
  }

  @Test fun toneAbove8kHzIsFilteredOutNotFoldedIn() {
    // 10 kHz at 44.1k would alias to 6 kHz at 16k if decimated without a filter.
    val out = run(sine(10_000.0, 44_100, 1.0), 44_100, chunk = 1000)
    val m = magnitude(out, 16_000, 6000.0, 800, out.size - 800)
    assertTrue("aliased energy $m should be under 1% of the input", m < 100.0)
  }

  @Test fun chunkSizeDoesNotChangeTheOutput() {
    val input = sine(440.0, 48_000, 0.5)
    val whole = run(input, 48_000, chunk = input.size)
    val small = run(input, 48_000, chunk = 333)
    val tiny = run(input, 48_000, chunk = 7)
    assertArrayEquals(whole, small)
    assertArrayEquals(whole, tiny)
  }

  @Test fun outputLengthMatchesTheRateRatio() {
    for ((rate, secs) in listOf(8_000 to 2.0, 22_050 to 1.5, 44_100 to 3.0, 48_000 to 2.5)) {
      val out = run(FloatArray((rate * secs).toInt()), rate, chunk = 5000)
      assertEquals("rate $rate", 16_000 * secs, out.size.toDouble(), 2.0)
    }
  }

  @Test fun upsamplingKeepsTheTone() {
    val out = run(sine(500.0, 8_000, 1.0), 8_000, chunk = 999)
    assertEquals(16_000.0, out.size.toDouble(), 2.0)
    assertEquals(10_000.0, magnitude(out, 16_000, 500.0, 800, out.size - 800), 500.0)
  }

  @Test fun silenceStaysSilent() {
    val out = run(FloatArray(44_100), 44_100, chunk = 4096)
    assertTrue(out.all { it.toInt() == 0 })
  }

  @Test fun rmsOfAFullScaleSineIsPreserved() {
    val out = run(sine(1500.0, 44_100, 1.0, amp = 20_000.0), 44_100, 4096)
    val mid = out.copyOfRange(1000, out.size - 1000)
    val rms = sqrt(mid.sumOf { it.toDouble() * it } / mid.size)
    assertEquals(20_000.0 / sqrt(2.0), rms, 400.0)
    assertTrue(mid.all { abs(it.toInt()) <= 20_500 })
  }
}

class WindowCutterTest {
  private fun cut(total: Int, window: Int, hop: Int, max: Int, minTail: Int, chunk: Int = 7): List<Pair<Long, ShortArray>> {
    val out = ArrayList<Pair<Long, ShortArray>>()
    val c = WindowCutter(window, hop, max, minTail) { start, s -> out.add(start to s) }
    val all = ShortArray(total) { it.toShort() }
    var i = 0
    while (i < total) {
      val n = minOf(chunk, total - i)
      c.push(all.copyOfRange(i, i + n))
      i += n
    }
    c.finish()
    return out
  }

  @Test fun overlappingWindowsStartEveryHopAndCarryTheRightSamples() {
    val w = cut(total = 100, window = 40, hop = 20, max = 99, minTail = 10)
    assertEquals(listOf(0L, 20L, 40L), w.take(3).map { it.first })
    for ((start, samples) in w.take(3)) {
      assertEquals(40, samples.size)
      assertEquals(start.toInt(), samples[0].toInt())
      assertEquals(start.toInt() + 39, samples[39].toInt())
    }
  }

  @Test fun maxWindowsStopsTheCutting() {
    val w = cut(total = 1000, window = 40, hop = 20, max = 3, minTail = 10)
    assertEquals(3, w.size)
  }

  @Test fun aTailWithEnoughNewAudioBecomesAShortWindowAndATinyOneDoesNot() {
    val withTail = cut(total = 70, window = 40, hop = 40, max = 99, minTail = 10)
    assertEquals(listOf(0L, 40L), withTail.map { it.first })
    assertEquals(30, withTail.last().second.size)
    val tiny = cut(total = 45, window = 40, hop = 40, max = 99, minTail = 10)
    assertEquals(1, tiny.size)
  }

  @Test fun aTailAlreadyCoveredByTheOverlapIsNotRepeated() {
    // 50 samples: the window at 0 covers 0..39, the leftover 20..49 holds only 10 new samples.
    assertEquals(1, cut(total = 50, window = 40, hop = 20, max = 99, minTail = 11).size)
    assertEquals(2, cut(total = 50, window = 40, hop = 20, max = 99, minTail = 10).size)
  }

  @Test fun aShortClipGivesOneShortWindow() {
    val w = cut(total = 25, window = 40, hop = 20, max = 99, minTail = 10)
    assertEquals(1, w.size)
    assertEquals(25, w[0].second.size)
  }

  @Test fun aHopLongerThanTheWindowSkipsTheGap() {
    val w = cut(total = 200, window = 20, hop = 50, max = 99, minTail = 10)
    assertEquals(listOf(0L, 50L, 100L, 150L), w.map { it.first })
    assertEquals(50, w[1].second[0].toInt())
  }

  @Test fun chunkingDoesNotChangeTheWindows() {
    val a = cut(333, 40, 15, 99, 10, chunk = 1)
    val b = cut(333, 40, 15, 99, 10, chunk = 333)
    assertEquals(a.size, b.size)
    for (i in a.indices) {
      assertEquals(a[i].first, b[i].first)
      assertArrayEquals(a[i].second, b[i].second)
    }
  }
}
