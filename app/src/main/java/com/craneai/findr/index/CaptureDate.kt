package com.craneai.findr.index

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The capture date inside a picture (EXIF DateTimeOriginal), for the files whose MediaStore row has none.
 *
 * Some phones (Transsion, among them TECNO) leave MediaStore's DATE_TAKEN empty for every picture, and a photo received over
 * Xender or Bluetooth gets a new modified time while keeping its EXIF. Without this, both would sort and filter as "new".
 * WhatsApp strips EXIF, so those pictures correctly keep their modified time.
 */
object CaptureDate {
  /** EXIF writes local wall-clock time with colons in the date: "2024:12:25 14:03:09". */
  private val FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

  /** Before this a date is a camera with an unset clock (EXIF zeroes, 1970, 1980 defaults), not a capture date. */
  const val MIN_PLAUSIBLE_MS = 631_152_000_000L   // 1990-01-01

  /** Pictures that can carry EXIF. PNG, WebP and HEIC do in androidx.exifinterface 1.3+; GIF and BMP do not. */
  fun mayHaveExif(mime: String?): Boolean = when (mime) {
    "image/jpeg", "image/jpg", "image/heic", "image/heif", "image/webp", "image/png", "image/avif", "image/dng", "image/x-adobe-dng" -> true
    else -> false
  }

  /**
   * EXIF's date text to epoch milliseconds, or 0 when it is missing, malformed ("0000:00:00 00:00:00") or implausible (before
   * 1990, or more than a day in the future). [offset] is OffsetTimeOriginal ("+03:00") when the camera wrote one; without it
   * the date is read in [zone], the phone's own zone, which is where nearly every photo on it was taken.
   */
  fun parse(dateTimeOriginal: String?, offset: String? = null, zone: ZoneId = ZoneId.systemDefault(), nowMs: Long = System.currentTimeMillis()): Long {
    val text = dateTimeOriginal?.trim()?.takeIf { it.length >= 19 }?.substring(0, 19) ?: return 0
    val local = runCatching { LocalDateTime.parse(text, FORMAT) }.getOrNull() ?: return 0
    val zoneOffset = offset?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
    val ms = (zoneOffset?.let { local.toInstant(it) } ?: local.atZone(zone).toInstant()).toEpochMilli()
    return if (ms > MIN_PLAUSIBLE_MS && ms <= nowMs + 86_400_000L) ms else 0
  }

  /** Reads only the header of the picture behind [uri] (ExifInterface stops at the end of the metadata). 0 when there is none. */
  fun read(resolver: ContentResolver, uri: String): Long = runCatching {
    resolver.openInputStream(Uri.parse(uri))?.use { input ->
      val exif = ExifInterface(input)
      parse(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
        exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
    } ?: 0L
  }.getOrDefault(0L)
}
