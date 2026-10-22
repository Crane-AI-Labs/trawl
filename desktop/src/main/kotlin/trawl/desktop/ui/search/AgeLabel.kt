package trawl.desktop.ui.search

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import trawl.desktop.Strings
import trawl.desktop.ageBasis
import trawl.desktop.t
import trawl.desktop.tn

/**
 * "2 min. ago" for a result row (A17, A18). Two rules from the phone:
 *  - It never points at the future. A file newer than [now] (the computer's clock behind its own files, see ClockSense) reads as "now", not
 *    "in 3 days": the age is measured from [ageBasis].
 *  - Where the language has no words for it, a date and time (which every language can format) is shown instead of English words in a
 *    translated screen. The phone does the same for Luganda.
 */
object Age {
  const val MINUTE = 60_000L
  const val HOUR = 60 * MINUTE
  const val DAY = 24 * HOUR
  /** Older than this a relative age stops being useful ("23 days ago") and the date is shown. */
  const val RELATIVE_DAYS = 7

  fun label(millis: Long, now: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val delta = now - ageBasis(millis, now)
    fun stamp() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(millis))
    fun date() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(millis))
    return when {
      delta < MINUTE -> if (Strings.readsInLanguage("age_now")) t("age_now") else stamp()
      delta < HOUR -> (delta / MINUTE).toInt().let { n -> if (Strings.readsInLanguage("age_minutes")) tn("age_minutes", n, n.toString()) else stamp() }
      delta < DAY -> (delta / HOUR).toInt().let { n -> if (Strings.readsInLanguage("age_hours")) tn("age_hours", n, n.toString()) else stamp() }
      delta < RELATIVE_DAYS * DAY -> (delta / DAY).toInt().let { n -> if (Strings.readsInLanguage("date_ago_days")) tn("date_ago_days", n, n.toString()) else date() }
      else -> date()
    }
  }
}
