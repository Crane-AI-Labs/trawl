package com.craneai.findr.engine

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The privacy scorer's picture preprocessing against Pillow (the model's own evaluation transform). */
class PrivateImageTest {
  /** The same formula as scripts/build_private_image_golden.py. */
  private fun picture(w: Int, h: Int) = IntArray(w * h) { i ->
    val x = i % w
    val y = i / w
    val r = (x * 255 / maxOf(w - 1, 1)) % 256
    val g = ((x / 3 + y / 3) % 2) * 200 + (y * 55 / maxOf(h - 1, 1))
    val b = (x xor y) % 256
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }

  private fun golden(name: String) = GZIPInputStream(File("src/test/resources/$name").inputStream()).use { it.readBytes() }

  /** Every 8th row and column of Pillow's resize((384, 384), BICUBIC), within one level. */
  private fun matchesPillow(w: Int, h: Int) {
    val ours = ClipImage.resize(picture(w, h), w, h, PrivateImage.SIZE)
    val theirs = golden("private_resize_${w}x$h.rgb.gz")
    var worst = 0
    var off = 0
    for (y in 0 until 48) for (x in 0 until 48) for (c in 0 until 3) {
      val a = ours[((y * 8) * PrivateImage.SIZE + x * 8) * 3 + c]
      val b = theirs[(y * 48 + x) * 3 + c].toInt() and 0xFF
      worst = maxOf(worst, Math.abs(a - b))
      if (a != b) off++
    }
    assertTrue("${w}x$h: worst difference $worst levels", worst <= 1)
    assertTrue("${w}x$h: $off of ${48 * 48 * 3} values off by one", off < 48 * 48 * 3 / 20)
  }

  @Test fun shrinkingMatchesPillowBicubic() = matchesPillow(640, 480)

  @Test fun enlargingMatchesPillowBicubic() = matchesPillow(150, 100)

  @Test fun theClipSizeIsUnchangedByTheGeneralisedResize() {
    assertEquals(224 * 224 * 3, ClipImage.resize(picture(300, 200), 300, 200).size)
    assertEquals(384 * 384 * 3, ClipImage.resize(picture(300, 200), 300, 200, 384).size)
  }

  /** D10's parity rule: the shared resize and decode factor give the quick-look model byte-identical inputs whether or not the size is passed. */
  @Test fun tinyClipInputsAreIdenticalThroughTheSharedPath() {
    val p = picture(640, 480)
    assertTrue(ClipImage.resize(p, 640, 480).contentEquals(ClipImage.resize(p, 640, 480, ClipImage.SIZE)))
    for ((w, h) in listOf(4000 to 3000, 4096 to 4096, 1080 to 2400, 800 to 600, 100 to 100)) {
      assertEquals(ClipImage.sampleSize(w, h), ClipImage.sampleSize(w, h, ClipImage.SIZE))
    }
  }

  @Test fun inputsAreScaledToMinusOneToOneChannelsFirst() {
    val black = IntArray(120 * 80) { 0xFF shl 24 }
    val white = IntArray(120 * 80) { -1 }
    val red = IntArray(120 * 80) { (0xFF shl 24) or (255 shl 16) }
    val plane = PrivateImage.SIZE * PrivateImage.SIZE
    val k = PrivateImage.prepare(black, 120, 80)
    assertEquals(3 * plane, k.size)
    assertEquals(-1f, k[0], 1e-6f)
    assertEquals(-1f, k[3 * plane - 1], 1e-6f)
    assertEquals(1f, PrivateImage.prepare(white, 120, 80)[plane + 5], 1e-6f)
    val r = PrivateImage.prepare(red, 120, 80)
    assertEquals(1f, r[0], 1e-6f)           // red plane first
    assertEquals(-1f, r[plane], 1e-6f)      // then green
    assertEquals(-1f, r[2 * plane], 1e-6f)  // then blue
  }

  @Test fun normaliseMatchesPrepareOnAnAlreadySizedPicture() {
    val n = PrivateImage.SIZE
    val px = IntArray(n * n) { i -> (0xFF shl 24) or ((i * 7 and 0xFF) shl 16) or ((i * 13 and 0xFF) shl 8) or (i * 29 and 0xFF) }
    val a = PrivateImage.normalise(px)
    val b = PrivateImage.prepare(px, n, n)
    assertEquals(b.size, a.size)
    for (i in a.indices step 997) assertEquals(b[i], a[i], 1e-5f)
    assertEquals(-1f, PrivateImage.normalise(IntArray(n * n) { 0xFF shl 24 })[0], 1e-6f)
  }

  @Test fun theDecoderShrinksAsFarAsTwiceTheModelInput() {
    assertEquals(2, PrivateImage.sampleSize(4000, 3000))   // 2000x1500; at 1/4 the short side would be 750, under 768
    assertEquals(4, PrivateImage.sampleSize(8000, 6000))   // 2000x1500
    assertEquals(1, PrivateImage.sampleSize(1000, 700))
    assertEquals(1, PrivateImage.sampleSize(100, 100))
  }
}
