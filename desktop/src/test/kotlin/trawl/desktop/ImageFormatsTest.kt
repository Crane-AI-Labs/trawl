package trawl.desktop

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pictures the JDK cannot open. The in-app readers (WebP, TGA, PNM) are tested with real files; the computer's own HEIC
 * decoder cannot be run on a build machine, so the route is tested with a fake converter and the real commands as plain text.
 */
class ImageFormatsTest {
  private val saved = ImageFormats.converters
  private val dir: File = Files.createTempDirectory("trawl-img-test").toFile()

  @AfterTest fun restore() {
    ImageFormats.converters = saved
    dir.deleteRecursively()
  }

  private fun leftovers() = (File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("trawl-img-") && !f.isDirectory }?.size ?: 0)

  private fun jpeg(file: File, w: Int, h: Int) {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    img.createGraphics().apply { color = java.awt.Color(200, 40, 40); fillRect(0, 0, w, h); dispose() }
    assertTrue(ImageIO.write(img, "jpeg", file))
  }

  // ---- in-app readers ---------------------------------------------------------------------------------------------------------

  @Test fun aWebpPictureIsReadByTheBundledReader() {
    // A one-pixel lossless WebP.
    val f = File(dir, "tiny.webp").apply { writeBytes(Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")) }
    val img = assertNotNull(ImageFormats.readUpright(f), "WebP reader is not on the classpath")
    assertEquals(1, img.width); assertEquals(1, img.height)
    assertNotNull(Thumbnails.decode(f, 360))
    assertNotNull(Extract.phoneJpeg(f), "the embedder's JPEG could not be made from a WebP")
  }

  @Test fun tgaAndPnmPicturesAreRead() {
    val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
    for ((fmt, ext) in listOf("tga" to "tga", "pnm" to "ppm")) {
      val f = File(dir, "a.$ext")
      assertTrue(ImageIO.write(img, fmt, f), "no $fmt writer: the plugin is missing")
      val back = assertNotNull(ImageFormats.readUpright(f), "$ext was not read")
      assertEquals(40, back.width, ext); assertEquals(20, back.height, ext)
    }
  }

  @Test fun anOrdinaryJpegStillGoesThroughTheJdkAndIsNeverConverted() {
    ImageFormats.converters = listOf(ImageFormats.Converter { _, _ -> error("a JPEG must not be sent to a converter") })
    val f = File(dir, "p.jpg").also { jpeg(it, 64, 32) }
    val img = assertNotNull(ImageFormats.readUpright(f))
    assertEquals(64, img.width)
  }

  // ---- the computer's decoder -------------------------------------------------------------------------------------------------

  @Test fun aHeicIsConvertedToATemporaryJpegReadAndTheCopyDeleted() {
    val before = leftovers()
    var calls = 0
    ImageFormats.converters = listOf(ImageFormats.Converter { _, dst -> calls++; jpeg(dst, 48, 24); true })
    val f = File(dir, "IMG_0001.HEIC").apply { writeBytes(ByteArray(64) { 7 }) }
    val img = assertNotNull(ImageFormats.readUpright(f))
    assertEquals(48, img.width); assertEquals(24, img.height)
    assertEquals(1, calls)
    val thumb = assertNotNull(ImageFormats.thumbnail(f, 360))
    assertEquals(48, thumb.width)
    assertNotNull(Thumbnails.decode(f, 360), "the result grid goes through Thumbnails.decode")
    assertNotNull(Extract.phoneJpeg(f), "the embedder goes through Extract.phoneJpeg")
    assertEquals(before, leftovers(), "a converted copy was left in the temp folder")
  }

  @Test fun theNextConverterIsTriedWhenOneFailsOrThrows() {
    ImageFormats.converters = listOf(
      ImageFormats.Converter { _, _ -> false },
      ImageFormats.Converter { _, _ -> error("not installed") },
      ImageFormats.Converter { _, dst -> jpeg(dst, 10, 10); true },
    )
    val f = File(dir, "x.avif").apply { writeBytes(ByteArray(8)) }
    assertEquals(10, assertNotNull(ImageFormats.readUpright(f)).width)
  }

  @Test fun aHeicNobodyCanOpenGivesNullAndLeavesNothingBehind() {
    val before = leftovers()
    ImageFormats.converters = listOf(ImageFormats.Converter { _, _ -> false })
    val f = File(dir, "x.heic").apply { writeBytes(ByteArray(8)) }
    assertNull(ImageFormats.readUpright(f)); assertNull(ImageFormats.thumbnail(f, 100)); assertNull(Thumbnails.decode(f, 100)); assertNull(Extract.phoneJpeg(f))
    assertEquals(before, leftovers())
  }

  @Test fun aConverterThatWritesAnEmptyFileIsNotTrusted() {
    ImageFormats.converters = listOf(ImageFormats.Converter { _, _ -> true })   // says yes, wrote nothing
    assertNull(ImageFormats.readUpright(File(dir, "e.heif").apply { writeBytes(ByteArray(8)) }))
  }

  @Test fun aPictureWithAnUnknownExtensionIsNotHandedToTheOsDecoder() {
    ImageFormats.converters = listOf(ImageFormats.Converter { _, _ -> error("only the HEIC family goes to the computer") })
    assertNull(ImageFormats.readUpright(File(dir, "x.xyz").apply { writeBytes(ByteArray(8)) }))
    assertNull(ImageFormats.readUpright(File(dir, "broken.png").apply { writeBytes(ByteArray(8)) }))
  }

  // ---- the commands, as plain words -------------------------------------------------------------------------------------------

  @Test fun theCommandsNameTheRightProgramsAndKeepPathsOutOfScripts() {
    val s = File("/a b/in.heic"); val d = File("/t/out.jpg")
    assertEquals(listOf("sips", "-s", "format", "jpeg", s.absolutePath, "--out", d.absolutePath), ImageFormats.sipsCommand(s, d))
    assertEquals(listOf("heif-convert", "-q", "92", s.absolutePath, d.absolutePath), ImageFormats.heifConvertCommand(s, d))
    assertEquals(s.absolutePath + "[0]", ImageFormats.magickCommand(s, d)[1])
    val ps = ImageFormats.windowsCommand()
    assertEquals("powershell.exe", ps[0])
    val script = ps.last()
    assertTrue("TRAWL_SRC" in script && "TRAWL_DST" in script)
    assertFalse("in.heic" in script, "a path must never be spliced into the script")
  }

  @Test fun everyComputerHasAtLeastOneRoute() {
    for (os in Os.values()) assertTrue(ImageFormats.defaultConverters(os).isNotEmpty(), "$os")
    assertEquals(1, ImageFormats.defaultConverters(Os.MAC).size)
    assertTrue("heic" in ImageFormats.OS_ROUTE && "avif" in ImageFormats.OS_ROUTE)
  }
}
