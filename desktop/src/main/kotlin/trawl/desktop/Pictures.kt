package trawl.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam

/** The camera's "which way is up" flag of a JPEG (EXIF tag 0x0112), which ImageIO ignores. Phones and cameras write it on nearly every photo. */
object Exif {
  /** 1 to 8 as the EXIF standard numbers them; 1 (upright) when there is none or the file is not a JPEG. */
  fun orientation(file: File): Int = try {
    RandomAccessFile(file, "r").use { f ->
      val head = ByteArray(minOf(f.length(), 128 * 1024L).toInt())
      f.readFully(head)
      orientation(head)
    }
  } catch (_: Exception) { 1 }

  internal fun orientation(b: ByteArray): Int {
    if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return 1
    var p = 2
    while (p + 4 <= b.size) {
      if (b[p] != 0xFF.toByte()) return 1
      val marker = b[p + 1].toInt() and 0xFF
      if (marker == 0xD9 || marker == 0xDA) return 1   // end of image / start of scan: no EXIF before the pixels
      val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
      if (marker == 0xE1 && p + 10 <= b.size && String(b, p + 4, 4, Charsets.ISO_8859_1) == "Exif") return tiffOrientation(b, p + 10, minOf(b.size, p + 2 + len))
      p += 2 + len
    }
    return 1
  }

  private fun tiffOrientation(b: ByteArray, base: Int, end: Int): Int {
    if (base + 8 > end) return 1
    val little = b[base] == 'I'.code.toByte()
    fun u16(at: Int) = if (little) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    fun u32(at: Int) = if (little) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
    val ifd = base + u32(base + 4)
    if (ifd < base || ifd + 2 > end) return 1
    val n = u16(ifd)
    for (i in 0 until n) {
      val e = ifd + 2 + i * 12
      if (e + 12 > end) return 1
      if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 } ?: 1
    }
    return 1
  }

  /**
   * When a JPEG was taken, in epoch milliseconds: EXIF DateTimeOriginal (tag 0x9003 in the Exif sub-IFD that IFD0's tag 0x8769 points to),
   * read as that camera's local time, or with its OffsetTimeOriginal (0x9011) when the camera wrote one. Null when there is none, it does
   * not parse, or the file is not a JPEG: the caller then keeps the file's modified time. Reads only the first 64 KB (EXIF sits before the pixels).
   */
  fun taken(file: File, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? = try {
    RandomAccessFile(file, "r").use { f ->
      val head = ByteArray(minOf(f.length(), 64 * 1024L).toInt())
      f.readFully(head)
      taken(head, zone)
    }
  } catch (_: Exception) { null }

  internal fun taken(b: ByteArray, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
    if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
    var p = 2
    while (p + 4 <= b.size) {
      if (b[p] != 0xFF.toByte()) return null
      val marker = b[p + 1].toInt() and 0xFF
      if (marker == 0xD9 || marker == 0xDA) return null
      val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
      if (marker == 0xE1 && p + 10 <= b.size && String(b, p + 4, 4, Charsets.ISO_8859_1) == "Exif") return tiffTaken(b, p + 10, minOf(b.size, p + 2 + len), zone)
      p += 2 + len
    }
    return null
  }

  private fun tiffTaken(b: ByteArray, base: Int, end: Int, zone: java.time.ZoneId): Long? {
    if (base + 8 > end) return null
    val little = b[base] == 'I'.code.toByte()
    fun u16(at: Int) = if (little) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) else ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    fun u32(at: Int) = if (little) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
    /** The entry for [tag] in the IFD at [ifd] (its 12-byte slot), or -1. */
    fun find(ifd: Int, tag: Int): Int {
      if (ifd < base || ifd + 2 > end) return -1
      for (i in 0 until u16(ifd)) {
        val e = ifd + 2 + i * 12
        if (e + 12 > end) return -1
        if (u16(e) == tag) return e
      }
      return -1
    }
    fun ascii(e: Int): String? {
      val n = u32(e + 4)
      if (n < 2 || n > 64) return null
      val at = if (n <= 4) e + 8 else base + u32(e + 8)
      if (at < base || at + n > end) return null
      return String(b, at, n, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
    }
    val pointer = find(base + u32(base + 4), 0x8769)
    if (pointer < 0) return null
    val exif = base + u32(pointer + 8)
    val e = find(exif, 0x9003)
    if (e < 0) return null
    val text = ascii(e) ?: return null
    val local = try {
      java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss"))
    } catch (_: Exception) { return null }
    val offset = find(exif, 0x9011).takeIf { it >= 0 }?.let { ascii(it) }?.let { s -> try { java.time.ZoneOffset.of(s) } catch (_: Exception) { null } }
    val ms = local.atZone(offset ?: zone).toInstant().toEpochMilli()
    return ms.takeIf { it > 0 }   // "0000:00:00" and the like are not a date
  }

  /** [img] turned upright for [orientation]. The same picture back when it already is. */
  fun upright(img: BufferedImage, orientation: Int): BufferedImage {
    if (orientation == 1) return img
    val w = img.width; val h = img.height
    val t = when (orientation) {
      2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w.toDouble(), 0.0)
      3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w.toDouble(), h.toDouble())
      4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble())
      5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
      6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h.toDouble(), 0.0)
      7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h.toDouble(), w.toDouble())
      8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w.toDouble())
      else -> return img
    }
    val swap = orientation >= 5
    val out = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply { drawImage(img, t, null); dispose() }
    return out
  }
}

/** Small previews of pictures for the result grid: decoded at a fraction of full size (ImageIO subsampling), turned upright, kept in a small cache. */
object Thumbnails {
  private const val KEEP = 300
  private val cache = object : LinkedHashMap<String, ImageBitmap>(KEEP, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > KEEP
  }

  @Synchronized fun cached(key: String): ImageBitmap? = cache[key]

  /** The thumbnail if it is already decoded (no decoding, no waiting), so a tile that scrolls back into view draws at once instead of flashing empty. */
  fun peek(file: File, side: Int = 360): ImageBitmap? = cached("${file.absolutePath}|${file.lastModified()}|$side")

  /** The picture at about [side] px on its short side, or null when ImageIO cannot read it (a damaged file, or a HEIC on a computer with no decoder for it). Blocking: call it off the UI thread. */
  fun load(file: File, side: Int = 360): ImageBitmap? {
    val key = "${file.absolutePath}|${file.lastModified()}|$side"
    cached(key)?.let { return it }
    val img = decode(file, side) ?: return null
    val bmp = img.toComposeImageBitmap()
    synchronized(this) { cache[key] = bmp }
    return bmp
  }

  internal fun decode(file: File, side: Int): BufferedImage? = try {
    ImageIO.createImageInputStream(file)?.use { iis ->
      val readers = ImageIO.getImageReaders(iis)
      if (!readers.hasNext()) return ImageFormats.thumbnail(file, side)   // hook (Package C): HEIC/AVIF via the computer's decoder
      val r = readers.next()
      try {
        r.input = iis
        val step = maxOf(1, minOf(r.getWidth(0), r.getHeight(0)) / side)
        val param: ImageReadParam = r.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
        Exif.upright(r.read(0, param), Exif.orientation(file))
      } finally { r.dispose() }
    }
  } catch (_: Throwable) { null }
}
