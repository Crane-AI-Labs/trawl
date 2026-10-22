package com.craneai.findr.ui.common

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/** Pure formatting helpers (no Android types) so they can be unit-tested on the JVM. */

/** 42_000 -> "0:42", 3_725_000 -> "1:02:05". */
fun formatDuration(ms: Long): String {
  val totalSeconds = max(0L, ms) / 1000
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** Decimal units, because that is how phone storage screens count: 388_000_000 -> "388 MB". */
fun formatBytes(bytes: Long): String {
  if (bytes < 1_000) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unit = -1
  while (value >= 1_000 && unit < units.lastIndex) {
    value /= 1_000
    unit++
  }
  return when {
    value >= 100 || unit == 0 -> "%.0f %s".format(Locale.ROOT, value, units[unit])
    value >= 10 -> "%.0f %s".format(Locale.ROOT, value, units[unit])
    else -> "%.1f %s".format(Locale.ROOT, value, units[unit])
  }
}

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** What the ETA says, as parts the UI turns into words in the right language. */
sealed interface Eta {
  data object UnderAMinute : Eta
  data class Minutes(val minutes: Int) : Eta
  /** A range, because the rate swings with heat: "5-8 h". `low == high` collapses to a single value. */
  data class Hours(val low: Int, val high: Int) : Eta
}

/**
 * Never a falsely precise countdown: under ~90 minutes we say "about N min", beyond that a range of hours that
 * widens with distance, since the measured rate moves with temperature and use.
 */
fun etaOf(seconds: Long): Eta {
  val s = max(0L, seconds)
  return when {
    s < 60 -> Eta.UnderAMinute
    s < 90 * 60 -> Eta.Minutes(((s + 30) / 60).toInt())
    else -> {
      val hours = s / 3600.0
      val low = max(1, (hours * 0.8).roundToLong().toInt())
      val high = max(low, (hours * 1.25).roundToLong().toInt())
      Eta.Hours(low, high)
    }
  }
}

/** Fraction in 0..1, safe for empty totals. */
fun fraction(part: Int, whole: Int): Float = if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

enum class DayBucket { TODAY, YESTERDAY, THIS_WEEK, EARLIER }

/** Coarse recency bucket for the "Recent" list. Uses the device's calendar days, passed in as epoch-day numbers. */
fun dayBucket(epochDayOfFile: Long, epochDayNow: Long): DayBucket = when (epochDayNow - epochDayOfFile) {
  in Long.MIN_VALUE..0L -> DayBucket.TODAY
  1L -> DayBucket.YESTERDAY
  in 2L..6L -> DayBucket.THIS_WEEK
  else -> DayBucket.EARLIER
}

/** A folder path from the picker's document id: "primary:DCIM/Private" -> "DCIM/Private". Null for non-primary volumes. */
fun relativeFolderFromDocumentId(documentId: String): String? {
  val parts = documentId.split(":", limit = 2)
  if (parts.size != 2 || !parts[0].equals("primary", ignoreCase = true)) return null
  return parts[1].trim('/').takeIf { it.isNotEmpty() }
}
