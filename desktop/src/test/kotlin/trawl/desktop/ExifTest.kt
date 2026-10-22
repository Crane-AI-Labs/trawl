package trawl.desktop

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExifTest {
  /** SOI, an APP1 "Exif" segment with one IFD entry (tag 0x0112 = [o]), then end of image. */
  private fun jpeg(o: Int, little: Boolean): ByteArray {
    fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
    fun u32(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte(), 0, 0) else byteArrayOf(0, 0, (v shr 8).toByte(), v.toByte())
    val tiff = (if (little) "II".toByteArray() else "MM".toByteArray()) + u16(42) + u32(8) + u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(o) + byteArrayOf(0, 0) + u32(0)
    val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
    val len = body.size + 2
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
  }

  @Test fun readsOrientationInBothByteOrders() {
    for (o in 1..8) { assertEquals(o, Exif.orientation(jpeg(o, true))); assertEquals(o, Exif.orientation(jpeg(o, false))) }
  }

  @Test fun noExifMeansUpright() {
    assertEquals(1, Exif.orientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    assertEquals(1, Exif.orientation(byteArrayOf(1, 2, 3, 4, 5)))
  }

  /** A JPEG whose IFD0 points (tag 0x8769) at an Exif IFD holding DateTimeOriginal [original] and, when given, OffsetTimeOriginal [offset]. */
  private fun jpegWithDate(original: String?, offset: String? = null, little: Boolean = true): ByteArray {
    fun u16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
    fun u32(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), 0) else byteArrayOf(0, (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
    fun entry(tag: Int, type: Int, count: Int, value: ByteArray) = u16(tag) + u16(type) + u32(count) + value
    val ifd0At = 8
    val exifAt = ifd0At + 2 + 12 + 4
    val entries = (if (original != null) 1 else 0) + (if (offset != null) 1 else 0)
    val stringsAt = exifAt + 2 + entries * 12 + 4
    val o = original?.let { ascii(it) }
    val z = offset?.let { ascii(it) }
    val exifEntries = ArrayList<ByteArray>()
    var at = stringsAt
    if (o != null) { exifEntries += entry(0x9003, 2, o.size, u32(at)); at += o.size }
    if (z != null) exifEntries += entry(0x9011, 2, z.size, u32(at))
    val tiff = (if (little) "II".toByteArray() else "MM".toByteArray()) + u16(42) + u32(ifd0At) +
      u16(1) + entry(0x8769, 4, 1, u32(exifAt)) + u32(0) +
      u16(entries) + exifEntries.fold(ByteArray(0)) { a, b -> a + b } + u32(0) + (o ?: ByteArray(0)) + (z ?: ByteArray(0))
    val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
    val len = body.size + 2
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
  }

  private val utc = java.time.ZoneId.of("UTC")
  private fun ms(s: String, zone: java.time.ZoneId = utc) = java.time.LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

  @Test fun theCaptureDateIsDateTimeOriginalInBothByteOrders() {
    for (little in listOf(true, false)) {
      assertEquals(ms("2024-07-09T14:03:22"), Exif.taken(jpegWithDate("2024:07:09 14:03:22", little = little), utc), "little=$little")
    }
  }

  @Test fun withoutAnOffsetTheCamerasLocalTimeIsReadInTheComputersZone() {
    val b = jpegWithDate("2024:07:09 14:03:22")
    assertEquals(ms("2024-07-09T14:03:22", java.time.ZoneId.of("Africa/Kampala")), Exif.taken(b, java.time.ZoneId.of("Africa/Kampala")))
    assertEquals(ms("2024-07-09T14:03:22", java.time.ZoneId.of("America/New_York")), Exif.taken(b, java.time.ZoneId.of("America/New_York")))
  }

  @Test fun anOffsetTimeOriginalWinsOverTheComputersZone() {
    val b = jpegWithDate("2024:07:09 14:03:22", "+03:00")
    assertEquals(ms("2024-07-09T11:03:22"), Exif.taken(b, utc))
    assertEquals(Exif.taken(b, utc), Exif.taken(b, java.time.ZoneId.of("Pacific/Auckland")))
  }

  @Test fun aMissingOrBrokenDateIsNullSoTheModifiedTimeStays() {
    assertNull(Exif.taken(jpegWithDate(null), utc), "an Exif block with no date")
    assertNull(Exif.taken(jpegWithDate("0000:00:00 00:00:00"), utc), "the placeholder some cameras write")
    assertNull(Exif.taken(jpegWithDate("yesterday at noon!!!!"), utc))
    assertNull(Exif.taken(jpeg(6, true), utc), "an orientation-only Exif block")
    assertNull(Exif.taken(byteArrayOf(1, 2, 3, 4, 5), utc), "not a JPEG")
    assertNull(Exif.taken(jpegWithDate("2024:07:09 14:03:22").copyOf(30), utc), "cut short")
  }

  @Test fun aPhotoOnDiskGetsItsCaptureDateFromTheCrawler() {
    val d = java.nio.file.Files.createTempDirectory("trawl-exif").toFile()
    val f = java.io.File(d, "IMG_1.jpg").apply { writeBytes(jpegWithDate("2021:02:03 04:05:06", "+00:00")) }
    val png = java.io.File(d, "shot.png").apply { writeBytes(jpegWithDate("2021:02:03 04:05:06", "+00:00")) }
    val doc = java.io.File(d, "a.txt").apply { writeText("x") }
    assertEquals(ms("2021-02-03T04:05:06"), Crawler.takenOf(f.toPath(), f.name, com.craneai.findr.core.FileKind.IMAGE))
    assertEquals(0L, Crawler.takenOf(png.toPath(), png.name, com.craneai.findr.core.FileKind.IMAGE), "only JPEGs are read")
    assertEquals(0L, Crawler.takenOf(doc.toPath(), doc.name, com.craneai.findr.core.FileKind.DOCUMENT))
    // The walk hands the date on, and the record carries it as the shown date while the file's own modified time stays what it is.
    val found = ArrayList<Found>()
    Crawler.walk(d.toPath(), d.toPath(), true) { found += it }
    val photo = found.first { it.name == "IMG_1.jpg" }
    assertEquals(ms("2021-02-03T04:05:06"), photo.record().dateMillis)
    assertEquals(f.lastModified(), photo.record().modifiedMillis)
    assertEquals(0L, found.first { it.name == "a.txt" }.record().dateMillis, "0 means: the modified time, as for every other file")
    d.deleteRecursively()
  }

  @Test fun rotationSwapsTheSides() {
    val img = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
    val r = Exif.upright(img, 6)
    assertEquals(20, r.width); assertEquals(40, r.height)
    assertEquals(40, Exif.upright(img, 3).width)
  }
}
