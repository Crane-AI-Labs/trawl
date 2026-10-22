package com.craneai.findr.engine

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The quick-look pass's pure parts (D2, D10): picture preprocessing against Pillow, the query language gate, the model pack. */
class QuickLookTest {
  @get:Rule val tmp = TemporaryFolder()

  // ---- preprocessing ---------------------------------------------------------------------------------------------

  /** The same formula as scripts/build_clip_image_golden.py. */
  private fun picture(w: Int, h: Int) = IntArray(w * h) { i ->
    val x = i % w
    val y = i / w
    val r = (x * 255 / maxOf(w - 1, 1)) % 256
    val g = ((x / 3 + y / 3) % 2) * 200 + (y * 55 / maxOf(h - 1, 1))
    val b = (x xor y) % 256
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }

  private fun golden(name: String) = GZIPInputStream(File("src/test/resources/$name").inputStream()).use { it.readBytes() }

  /** Every 4th row and column of Pillow's resize((SIZE, SIZE), BICUBIC), within one level (Pillow rounds fixed-point sums). */
  private fun matchesPillow(w: Int, h: Int) {
    val ours = ClipImage.resize(picture(w, h), w, h)
    val theirs = golden("clip_resize_${w}x$h.rgb.gz")
    val q = ClipImage.SIZE / 4
    assertEquals(q * q * 3, theirs.size)
    var worst = 0
    var off = 0
    for (y in 0 until q) for (x in 0 until q) for (c in 0 until 3) {
      val a = ours[((y * 4) * ClipImage.SIZE + x * 4) * 3 + c]
      val b = theirs[(y * q + x) * 3 + c].toInt() and 0xFF
      worst = maxOf(worst, Math.abs(a - b))
      if (a != b) off++
    }
    assertTrue("${w}x$h: worst difference $worst levels", worst <= 1)
    assertTrue("${w}x$h: $off of ${q * q * 3} values off by one", off < q * q * 3 / 20)
  }

  @Test fun shrinkingMatchesPillowBicubic() = matchesPillow(640, 480)

  @Test fun enlargingMatchesPillowBicubic() = matchesPillow(150, 100)

  @Test fun theDecoderShrinksAsFarAsTwiceTheModelInput() {
    assertEquals(224, ClipImage.SIZE)
    assertEquals(4, ClipImage.sampleSize(4000, 3000))   // 1000×750
    assertEquals(8, ClipImage.sampleSize(4096, 4096))   // 512×512
    assertEquals(2, ClipImage.sampleSize(1080, 2400))   // 540×1200: the short side stays ≥ 448
    assertEquals(1, ClipImage.sampleSize(800, 600))
    assertEquals(1, ClipImage.sampleSize(100, 100))
  }

  @Test fun inputsAreNormalisedChannelsFirst() {
    val grey = IntArray(300 * 200) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
    val x = ClipImage.prepare(grey, 300, 200)
    assertEquals(3 * 224 * 224, x.size)
    val plane = 224 * 224
    assertEquals((128 / 255f - 0.48145466f) / 0.26862954f, x[0], 1e-5f)            // red plane: CLIP's mean and deviation
    assertEquals((128 / 255f - 0.4578275f) / 0.26130258f, x[plane + 1000], 1e-5f)  // green plane
    assertEquals((128 / 255f - 0.40821073f) / 0.27577711f, x[2 * plane + 50_000], 1e-5f)
  }

  // ---- the query gate --------------------------------------------------------------------------------------------

  private val gate = SpeechLanguageGate(lazyOf(File("src/main/assets/${SpeechLanguageGate.ASSET}").inputStream().use { EnglishWords.fromGzip(it) }))

  @Test fun englishQueriesUseTheQuickLookModel() {
    listOf("dog on the beach", "receipt from the supermarket", "wedding photos", "Nakato birthday cake", "a red car parked outside")
      .forEach { assertTrue(it, gate.isEnglishQuery(it)) }
  }

  @Test fun lugandaSwahiliAndCodesDoNot() {
    listOf(
      "embwa ku lubalama", "omusajja agenze mu katale", "embuzi", "ente",          // Luganda
      "mbwa ufukweni", "mbwa mweusi", "mbuzi", "kitanda",                          // Swahili (~3% of FLORES sentences pass, the research notes)
      "KDC-260914-0072", "4815162342",                                              // codes: meaning search adds nothing
    ).forEach { assertFalse(it, gate.isEnglishQuery(it)) }
  }

  @Test fun withoutTheWordListEveryQueryIsTreatedAsEnglish() {
    assertTrue(SpeechLanguageGate(lazyOf(null)).isEnglishQuery("embwa ku lubalama"))
  }

  // ---- the model pack --------------------------------------------------------------------------------------------

  @Test fun thePackListsBothTowersWithSizesAndHashes() {
    assertEquals(setOf(QuickLookPack.IMAGE, QuickLookPack.TEXT), QuickLookPack.files.map { it.path }.toSet())
    assertTrue(QuickLookPack.files.all { it.required && it.size > 50_000_000 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
    assertEquals(62_346_674L + 54_817_350L, QuickLookPack.files.sumOf { it.size })
    assertTrue("earlier models' files are cleaned up", QuickLookPack.RETIRED.none { r -> QuickLookPack.files.any { it.path == r } })
    assertEquals(QuickLookPack.DIM, com.craneai.findr.index.IndexStore.QUICK_DIM)
    assertEquals("findr-quicklook-models", QuickLookPack.FOLDER_NAME)
  }
}
