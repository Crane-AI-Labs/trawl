package trawl.desktop.ui.search

import com.craneai.findr.core.DateLabel
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.DayPart
import com.craneai.findr.core.DateUnit
import com.craneai.findr.core.SortOrder
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import trawl.desktop.t
import trawl.desktop.tn

/**
 * A date filter's name in the person's language: the phone's rules (A9), with the words from the desktop's strings (the phone's own
 * translations where it has them) and the month and day names from the locale. "This weekend", "3 days ago", "March 2025".
 */
fun DateLabel.text(locale: Locale = Locale.getDefault()): String {
  fun plural(past: Boolean, n: Int, unit: DateUnit): String {
    val key = when (unit) {
      DateUnit.DAY -> if (past) "date_past_days" else "date_ago_days"
      DateUnit.WEEK -> if (past) "date_past_weeks" else "date_ago_weeks"
      DateUnit.MONTH -> if (past) "date_past_months" else "date_ago_months"
      DateUnit.YEAR -> if (past) "date_past_years" else "date_ago_years"
    }
    return tn(key, n, n.toString())
  }
  fun day(d: java.time.LocalDate) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(d)
  return when (this) {
    DateLabel.Today -> t("date_today")
    DateLabel.Yesterday -> t("date_yesterday")
    DateLabel.ThisWeek -> t("date_this_week")
    DateLabel.LastWeek -> t("date_last_week")
    DateLabel.ThisMonth -> t("date_this_month")
    DateLabel.LastMonth -> t("date_last_month")
    DateLabel.ThisYear -> t("date_this_year")
    DateLabel.LastYear -> t("date_last_year")
    DateLabel.Weekend -> t("date_weekend")
    DateLabel.LastNight -> t("date_last_night")
    is DateLabel.Part -> t(
      when (part) {
        DayPart.MORNING -> if (yesterday) "date_yesterday_morning" else "date_this_morning"
        DayPart.AFTERNOON -> if (yesterday) "date_yesterday_afternoon" else "date_this_afternoon"
        DayPart.EVENING -> if (yesterday) "date_yesterday_evening" else "date_this_evening"
      },
    )
    is DateLabel.Past -> plural(true, n, unit)
    is DateLabel.Ago -> plural(false, n, unit)
    is DateLabel.Day -> day(date)
    is DateLabel.Month -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)
    is DateLabel.Year -> String.format(locale, "%d", year)
    is DateLabel.Custom -> if (from == to) day(from) else t("date_range", day(from), day(to))
  }
}

/** The label a quick choice in the date menu stands for, so the menu can tick the one that is on. */
fun DatePreset.label(): DateLabel = when (this) {
  DatePreset.TODAY -> DateLabel.Today
  DatePreset.THIS_WEEK -> DateLabel.ThisWeek
  DatePreset.THIS_MONTH -> DateLabel.ThisMonth
  DatePreset.THIS_YEAR -> DateLabel.ThisYear
}

fun DatePreset.menuText(): String = label().text()

fun SortOrder.text(): String = when (this) {
  SortOrder.BEST -> t("sort_best")
  SortOrder.NEWEST -> t("sort_newest")
  SortOrder.OLDEST -> t("sort_oldest")
  SortOrder.LARGEST -> t("sort_largest")
  SortOrder.NAME -> t("sort_name")
}

/** The sorts the menu offers: Best match only where there is something to be best for (a search, or Find similar). */
fun sortChoices(searching: Boolean): List<SortOrder> =
  if (searching) listOf(SortOrder.BEST, SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.LARGEST, SortOrder.NAME)
  else listOf(SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.LARGEST, SortOrder.NAME)
