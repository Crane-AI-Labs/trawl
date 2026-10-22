package com.craneai.findr.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** What a date filter is called, so the screen can write it in the person's language (the strings live in resources, the months and days come from the locale). */
enum class DateUnit { DAY, WEEK, MONTH, YEAR }
enum class DayPart { MORNING, AFTERNOON, EVENING }

sealed interface DateLabel {
  data object Today : DateLabel
  data object Yesterday : DateLabel
  data object ThisWeek : DateLabel
  data object LastWeek : DateLabel
  data object ThisMonth : DateLabel
  data object LastMonth : DateLabel
  data object ThisYear : DateLabel
  data object LastYear : DateLabel
  data object Weekend : DateLabel
  data object LastNight : DateLabel
  /** "This morning" / "Yesterday evening". */
  data class Part(val part: DayPart, val yesterday: Boolean) : DateLabel
  /** "Last 7 days" (rolling). */
  data class Past(val n: Int, val unit: DateUnit) : DateLabel
  /** "3 weeks ago" (the whole calendar day, week, month or year that long ago). */
  data class Ago(val n: Int, val unit: DateUnit) : DateLabel
  /** One calendar day: "5 December 2023", "Friday". */
  data class Day(val date: LocalDate) : DateLabel
  /** A calendar month; [year] is always known once resolved ("December 2023"). */
  data class Month(val month: YearMonth) : DateLabel
  data class Year(val year: Int) : DateLabel
  /** A range picked by hand, both days inclusive. */
  data class Custom(val from: LocalDate, val to: LocalDate) : DateLabel
}

/** A date filter: what to call it, and the span of time it keeps. */
data class DateFilter(val label: DateLabel, val range: DateRange)

/** The quick choices in the date chip's menu (the custom range comes from the picker). */
enum class DatePreset { TODAY, THIS_WEEK, THIS_MONTH, THIS_YEAR }

/** A date found in the query: [phrase] is the text it came from (with its lead word), at [start] until [end] in the original query. */
data class ParsedDate(val filter: DateFilter, val phrase: String, val start: Int, val end: Int)

/** The query with its date phrase taken out ([text], ready for the semantic search) and the date it meant, if any. */
data class DateQuery(val text: String, val date: ParsedDate?)

/**
 * Finds a date written in a query ("receipts from last week", "in December", "2024", "5 March 2023", "this morning") and turns it
 * into a [DateFilter], leaving the rest of the query for the search. Pure: the clock, zone and first day of the week come in.
 *
 * English only so far. TODO(i18n): Swahili and Luganda month and relative-day words. the translation notes holds no approved month or
 * weekday words for either language and Luganda has no ICU data, so they are not guessed here; add them with a speaker's sign-off.
 * TODO: numeric day/month/year ("12/05/2024") is left out because day-first and month-first both occur; only ISO ("2024-05-12") is read.
 *
 * It must never read an ordinary word or number as a date, so the rules lean careful (a visible chip the person can remove is the
 * safety net, not a licence to guess):
 *  - A month needs company: a lead word (in, on, from, during, within, last, this), an adjacent year or day, or, bare, to be one of the
 *    six full names that are rarely anything else (January, February, September, October, November, December). "may" and "march"
 *    additionally refuse to be a month before the words that make them verbs or auxiliaries ("may be", "march to"); abbreviations
 *    ("dec", "mar") never stand bare.
 *  - A bare year (1990 to this year) with no lead word must also be recent (see [BARE_YEAR_WINDOW]), not a round hundred, not next to
 *    a currency or unit or an identifier word ("UGX 2024", "2024 shillings", "order 2024"), and not beside another number.
 */
object DateParser {
  /** How many years back a year may be and still count with no lead word ("2024 photos" yes; "2012 toyota" is a car). */
  const val BARE_YEAR_WINDOW = 10
  private const val FIRST_YEAR = 1990

  private val STRONG_LEADS = setOf("in", "on", "from", "during", "within", "year")
  private val LEADS = STRONG_LEADS + "the"
  /** Leftover words the removal strands at the end ("photos of" after "of December"). */
  private val TRAILING = LEADS + setOf("of", "since", "at", "around")

  private val FULL_MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
  private val SHORT_MONTHS = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12)
  private val BARE_MONTHS = setOf(1, 2, 9, 10, 11, 12)

  /**
   * Whether [phrase] (the words a date was read from) names a day, month or year ("oct 2026", "12 sept", "march") rather than a
   * stretch counted back from today ("last week", "yesterday"). Only the first can be a date that is written in a file: a file
   * called "Minutes 12 Sept.docx" carries it, while "last week" in a name says nothing about the week before today.
   */
  fun namesCalendarDate(phrase: String): Boolean {
    val words = phrase.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    return when {
      words.any { it in FULL_MONTHS || it in SHORT_MONTHS } -> true
      words.any { it in RELATIVE_WORDS } -> false
      else -> phrase.any(Char::isDigit)   // a bare year or day: "2026", "12/10"
    }
  }
  private val RELATIVE_WORDS = setOf(
    "last", "past", "this", "next", "ago", "yesterday", "today", "tonight", "week", "weeks", "weekend", "day", "days", "month", "months",
    "year", "years", "recent", "recently", "earlier", "lately",
  )
  private val MAY_DENY = setOf("be", "not", "have", "also", "contain", "include", "need", "apply", "vary", "i", "we", "you", "he", "she", "they", "it", "get", "want", "take", "come", "go", "use", "see")
  private val MARCH_DENY = setOf("to", "on", "through", "past", "against", "with", "for", "in", "into", "up", "down", "out", "along", "across", "towards", "toward", "of", "and", "the")

  private val WEEKDAYS = DayOfWeek.entries.associateBy { it.name.lowercase() }
  private val WORD_NUMBERS = mapOf(
    "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
  )
  private val UNITS = mapOf(
    "day" to DateUnit.DAY, "days" to DateUnit.DAY, "week" to DateUnit.WEEK, "weeks" to DateUnit.WEEK,
    "month" to DateUnit.MONTH, "months" to DateUnit.MONTH, "year" to DateUnit.YEAR, "years" to DateUnit.YEAR,
  )
  private val DAY_PARTS = mapOf("morning" to DayPart.MORNING, "afternoon" to DayPart.AFTERNOON, "evening" to DayPart.EVENING)

  /** A year next to one of these is a price, an amount, a quantity or an identifier. */
  private val YEAR_BEFORE_DENY = setOf(
    "ugx", "usd", "kes", "tzs", "rwf", "eur", "gbp", "shs", "sh", "ush", "price", "cost", "costs", "total", "amount", "fee", "fees", "pay", "paid", "balance",
    "id", "no", "number", "nr", "ref", "code", "pin", "order", "room", "page", "p", "chapter", "ticket", "plot", "block", "flat", "unit", "line", "item", "table",
    "version", "v", "model", "iso", "rfc", "x",
  )
  private val YEAR_AFTER_DENY = setOf(
    "ugx", "usd", "kes", "tzs", "shillings", "shilling", "shs", "sh", "ush", "dollars", "dollar", "euros", "pounds", "naira", "mb", "gb", "kb", "tb", "mah", "mp", "px", "hz", "kg", "g", "km", "m", "cm", "mm",
    "ml", "l", "w", "kw", "v", "kwh", "rpm", "people", "items", "pcs", "pieces", "units", "views", "likes", "followers", "pages", "words", "calories", "kcal", "percent", "years", "days", "hours", "minutes", "am", "pm",
  )

  private class Tok(val text: String, val start: Int, val end: Int)
  private class Hit(val filter: DateFilter, val next: Int)

  private val TOKEN = Regex("""[\p{L}\p{N}_]+(?:['’][\p{L}]+)?(?:[-/.][\p{L}\p{N}_]+)*""")
  private val DAY_NUMBER = Regex("""^(\d{1,2})(st|nd|rd|th)?$""")
  private val YEAR = Regex("""^\d{4}$""")
  private val ISO_DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
  private val ISO_MONTH = Regex("""^(\d{4})-(\d{2})$""")

  fun preset(preset: DatePreset, now: ZonedDateTime, weekStart: DayOfWeek = DayOfWeek.MONDAY): DateFilter {
    val c = Ctx(now, weekStart)
    val today = c.today
    return when (preset) {
      DatePreset.TODAY -> DateFilter(DateLabel.Today, c.days(today, today))
      DatePreset.THIS_WEEK -> DateFilter(DateLabel.ThisWeek, c.week(0))
      DatePreset.THIS_MONTH -> DateFilter(DateLabel.ThisMonth, c.month(YearMonth.from(today)))
      DatePreset.THIS_YEAR -> DateFilter(DateLabel.ThisYear, c.year(today.year))
    }
  }

  /** A range picked by hand: both days inclusive. */
  fun custom(from: LocalDate, to: LocalDate, zone: ZoneId): DateFilter {
    val (a, b) = if (from <= to) from to to else to to from
    return DateFilter(DateLabel.Custom(a, b), DateRange(a.atStartOfDay(zone).toInstant().toEpochMilli(), b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()))
  }

  /**
   * @param ignored phrases (as [ParsedDate.phrase], lower-case) the person dismissed with the chip's cross: they are read as ordinary
   *   words from then on, and the scan moves on to any other date in the query.
   */
  fun parse(query: String, now: ZonedDateTime, weekStart: DayOfWeek = DayOfWeek.MONDAY, ignored: Set<String> = emptySet()): DateQuery {
    val toks = TOKEN.findAll(query).map { Tok(it.value.lowercase(), it.range.first, it.range.last + 1) }.toList()
    val c = Ctx(now, weekStart)
    for (i in toks.indices) {
      var leads = 0
      while (leads < 2 && i + leads < toks.size - 1 && toks[i + leads].text in LEADS) leads++
      // Longest lead first, then fewer: "in the 2024" fails with two leads only if nothing follows, so try each.
      for (n in leads downTo 0) {
        val p = i + n
        if (p >= toks.size) continue
        val lead = toks.subList(i, p).map { it.text }
        val hit = matchAt(toks, p, lead, query, c) ?: continue
        val start = toks[i].start
        val end = toks[hit.next - 1].end
        val phrase = query.substring(start, end).lowercase().replace(Regex("\\s+"), " ")
        if (phrase in ignored) continue
        return DateQuery(remove(query, start, end), ParsedDate(hit.filter, phrase, start, end))
      }
    }
    return DateQuery(query, null)
  }

  /** The query without [start, end), spaces tidied and any lead word the removal stranded at the end dropped. */
  private fun remove(query: String, start: Int, end: Int): String {
    var rest = (query.substring(0, start) + " " + query.substring(end)).replace(Regex("\\s+"), " ").trim()
    while (true) {
      val last = rest.substringAfterLast(' ', rest)
      if (last.lowercase() in TRAILING && rest.length > last.length) rest = rest.dropLast(last.length).trimEnd() else break
    }
    return rest
  }

  // ---- the calendar ------------------------------------------------------------------------------------------------

  private class Ctx(val now: ZonedDateTime, val weekStart: DayOfWeek) {
    val zone: ZoneId = now.zone
    val today: LocalDate = now.toLocalDate()
    fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    /** [from, to] whole days, inclusive. */
    fun days(from: LocalDate, to: LocalDate) = DateRange(ms(from), ms(to.plusDays(1)))
    fun week(back: Long): DateRange {
      val start = today.with(TemporalAdjusters.previousOrSame(weekStart)).minusWeeks(back)
      return days(start, start.plusDays(6))
    }
    fun month(m: YearMonth) = days(m.atDay(1), m.atEndOfMonth())
    fun year(y: Int) = days(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
    /** The hours [from, until) of [day] (until may be 24). */
    fun hours(day: LocalDate, from: Int, until: Int) =
      DateRange(day.atStartOfDay(zone).plusHours(from.toLong()).toInstant().toEpochMilli(), day.atStartOfDay(zone).plusHours(until.toLong()).toInstant().toEpochMilli())
  }

  private fun hit(label: DateLabel, range: DateRange, next: Int) = Hit(DateFilter(label, range), next)

  private fun matchAt(t: List<Tok>, p: Int, lead: List<String>, q: String, c: Ctx): Hit? {
    val strong = lead.any { it in STRONG_LEADS }
    val w = t[p].text
    // "last May", "this December": the word before the month is part of the phrase and decides which year.
    if (w == "last" || w == "this") {
      val next = t.getOrNull(p + 1)?.text
      if (next != null && monthWord(next) != null) monthFirst(t, p + 1, lead + w, strong, c)?.let { return it }
    }
    return relative(t, p, c)
      ?: dayMonth(t, p, c)
      ?: monthFirst(t, p, lead, strong, c)
      ?: iso(t, p, c)
      ?: bareYear(t, p, q, strong, c)
      ?: weekday(t, p, lead, c, w)
  }

  // ---- today, last week, 3 days ago ---------------------------------------------------------------------------------

  private fun relative(t: List<Tok>, p: Int, c: Ctx): Hit? {
    val w = t[p].text
    val n1 = t.getOrNull(p + 1)?.text
    val n2 = t.getOrNull(p + 2)?.text
    fun unitOf(s: String?) = s?.let { UNITS[it] }
    when (w) {
      "today" -> return hit(DateLabel.Today, c.days(c.today, c.today), p + 1)
      "yesterday" -> {
        val y = c.today.minusDays(1)
        DAY_PARTS[n1]?.let { return hit(DateLabel.Part(it, true), part(c, y, it), p + 2) }
        return hit(DateLabel.Yesterday, c.days(y, y), p + 1)
      }
      "this" -> {
        DAY_PARTS[n1]?.let { return hit(DateLabel.Part(it, false), part(c, c.today, it), p + 2) }
        when (n1) {
          "week" -> return hit(DateLabel.ThisWeek, c.week(0), p + 2)
          "month" -> return hit(DateLabel.ThisMonth, c.month(YearMonth.from(c.today)), p + 2)
          "year" -> return hit(DateLabel.ThisYear, c.year(c.today.year), p + 2)
          "weekend" -> return hit(DateLabel.Weekend, weekend(c), p + 2)
        }
      }
      "last", "previous", "prev" -> {
        when (n1) {
          "week" -> return hit(DateLabel.LastWeek, c.week(1), p + 2)
          "month" -> return hit(DateLabel.LastMonth, c.month(YearMonth.from(c.today).minusMonths(1)), p + 2)
          "year" -> return hit(DateLabel.LastYear, c.year(c.today.year - 1), p + 2)
          "weekend" -> return hit(DateLabel.Weekend, weekend(c), p + 2)
          "night" -> if (w == "last") {
            val y = c.today.minusDays(1)
            return hit(DateLabel.LastNight, DateRange(c.hours(y, 18, 24).fromMs, c.hours(c.today, 0, 6).untilMs), p + 2)
          }
        }
        if (w == "last") {
          WEEKDAYS[n1]?.let {
            val d = c.today.with(TemporalAdjusters.previous(it))
            return hit(DateLabel.Day(d), c.days(d, d), p + 2)
          }
          return rolling(t, p + 1, c)
        }
      }
      "past" -> return rolling(t, p + 1, c)
    }
    // "3 days ago", "a week ago"
    val n = count(w)
    val unit = unitOf(n1)
    if (n != null && unit != null && n2 == "ago") {
      val max = when (unit) { DateUnit.DAY -> 365; DateUnit.WEEK -> 104; DateUnit.MONTH -> 60; DateUnit.YEAR -> 30 }
      if (n in 1..max) {
        val range = when (unit) {
          DateUnit.DAY -> c.today.minusDays(n.toLong()).let { c.days(it, it) }
          DateUnit.WEEK -> c.week(n.toLong())
          DateUnit.MONTH -> c.month(YearMonth.from(c.today).minusMonths(n.toLong()))
          DateUnit.YEAR -> c.year(c.today.year - n)
        }
        return hit(DateLabel.Ago(n, unit), range, p + 3)
      }
    }
    return null
  }

  private fun count(s: String): Int? = WORD_NUMBERS[s] ?: s.takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toIntOrNull()

  /** "last 7 days", "past 2 weeks", "past month": the days up to and including today, from the start of the day that far back. */
  private fun rolling(t: List<Tok>, at: Int, c: Ctx): Hit? {
    val first = t.getOrNull(at)?.text ?: return null
    val (n, unitAt) = UNITS[first]?.let { 1 to at } ?: ((count(first) ?: return null) to at + 1)
    val unit = UNITS[t.getOrNull(unitAt)?.text ?: return null] ?: return null
    val max = when (unit) { DateUnit.DAY -> 365; DateUnit.WEEK -> 104; DateUnit.MONTH -> 60; DateUnit.YEAR -> 30 }
    if (n !in 1..max) return null
    val from = when (unit) {
      DateUnit.DAY -> c.today.minusDays(n.toLong())
      DateUnit.WEEK -> c.today.minusWeeks(n.toLong())
      DateUnit.MONTH -> c.today.minusMonths(n.toLong())
      DateUnit.YEAR -> c.today.minusYears(n.toLong())
    }
    return hit(DateLabel.Past(n, unit), c.days(from, c.today), unitAt + 1)
  }

  private fun part(c: Ctx, day: LocalDate, part: DayPart) = when (part) {
    DayPart.MORNING -> c.hours(day, 0, 12)
    DayPart.AFTERNOON -> c.hours(day, 12, 18)
    DayPart.EVENING -> c.hours(day, 18, 24)
  }

  /** The most recent Saturday and Sunday (the current ones on a weekend): files cannot come from a weekend that has not happened. */
  private fun weekend(c: Ctx): DateRange {
    val sat = c.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
    return c.days(sat, sat.plusDays(1))
  }

  private fun weekday(t: List<Tok>, p: Int, lead: List<String>, c: Ctx, w: String): Hit? {
    if ("on" !in lead) return null
    val dow = WEEKDAYS[w] ?: return null
    val d = c.today.with(TemporalAdjusters.previousOrSame(dow))
    return hit(DateLabel.Day(d), c.days(d, d), p + 1)
  }

  // ---- months and days ------------------------------------------------------------------------------------------------

  private class MonthWord(val number: Int, val full: Boolean)

  private fun monthWord(s: String): MonthWord? {
    val full = FULL_MONTHS.indexOf(s)
    if (full >= 0) return MonthWord(full + 1, true)
    return SHORT_MONTHS[s]?.let { MonthWord(it, false) }
  }

  private fun yearOf(s: String?, c: Ctx): Int? = s?.takeIf { YEAR.matches(it) }?.toInt()?.takeIf { it in FIRST_YEAR..c.today.year }

  /** Whether "may" / "march" at [p] is followed by a word that makes it an ordinary word. */
  private fun ordinaryWord(m: MonthWord, next: String?): Boolean = when (m.number) {
    5 -> next in MAY_DENY
    3 -> next in MARCH_DENY
    else -> false
  }

  private fun dayOf(s: String?): Pair<Int, Boolean>? {
    val m = DAY_NUMBER.matchEntire(s ?: return null) ?: return null
    val d = m.groupValues[1].toInt()
    return if (d in 1..31) d to m.groupValues[2].isNotEmpty() else null
  }

  /** "5 December", "5th of December 2023", "the 5th of March". */
  private fun dayMonth(t: List<Tok>, p: Int, c: Ctx): Hit? {
    val (day, _) = dayOf(t[p].text) ?: return null
    var at = p + 1
    if (t.getOrNull(at)?.text == "of") at++
    val m = monthWord(t.getOrNull(at)?.text ?: return null) ?: return null
    val year = yearOf(t.getOrNull(at + 1)?.text, c)
    if (year == null && ordinaryWord(m, t.getOrNull(at + 1)?.text)) return null
    val next = at + 1 + (if (year != null) 1 else 0)
    val resolved = resolveDay(day, m.number, year, c) ?: return null
    return hit(DateLabel.Day(resolved), c.days(resolved, resolved), next)
  }

  private fun resolveDay(day: Int, month: Int, year: Int?, c: Ctx): LocalDate? {
    if (year != null) return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    val thisYear = runCatching { LocalDate.of(c.today.year, month, day) }.getOrNull()
    return when {
      thisYear == null -> null
      thisYear <= c.today -> thisYear
      else -> runCatching { LocalDate.of(c.today.year - 1, month, day) }.getOrNull()
    }
  }

  /** "December", "in December", "December 2023", "December 5th", "last May", "this December". */
  private fun monthFirst(t: List<Tok>, p: Int, lead: List<String>, strong: Boolean, c: Ctx): Hit? {
    val m = monthWord(t[p].text) ?: return null
    val after = t.getOrNull(p + 1)?.text
    val year = yearOf(after, c)
    val dayAfter = if (year == null) dayOf(after) else null
    val relLead = lead.firstOrNull { it == "last" || it == "this" }
    val hasLead = strong || relLead != null
    // May and March keep to the company that makes them months: a lead, a year, or a dated day ("May 5th"); and never before a verb-ish word.
    val ambiguous = m.number == 5 || m.number == 3
    val dayCounts = dayAfter != null && (!ambiguous || dayAfter.second)
    val ok = year != null || dayCounts || (hasLead && !ordinaryWord(m, after)) || (m.full && m.number in BARE_MONTHS)
    if (!ok) return null
    if (year != null) {
      val next = p + 2
      // "December 5 2023": a year after the day
      return hit(DateLabel.Month(YearMonth.of(year, m.number)), c.month(YearMonth.of(year, m.number)), next)
    }
    if (dayCounts) {
      val yearAfterDay = yearOf(t.getOrNull(p + 2)?.text, c)
      val d = resolveDay(dayAfter!!.first, m.number, yearAfterDay, c) ?: return null
      return hit(DateLabel.Day(d), c.days(d, d), p + 2 + (if (yearAfterDay != null) 1 else 0))
    }
    val ym = when {
      relLead == "this" -> YearMonth.of(c.today.year, m.number)
      relLead == "last" -> YearMonth.of(if (m.number < c.today.monthValue) c.today.year else c.today.year - 1, m.number)
      else -> YearMonth.of(if (m.number <= c.today.monthValue) c.today.year else c.today.year - 1, m.number)
    }
    return hit(DateLabel.Month(ym), c.month(ym), p + 1)
  }

  // ---- years and ISO dates ---------------------------------------------------------------------------------------------------

  private fun iso(t: List<Tok>, p: Int, c: Ctx): Hit? {
    val s = t[p].text
    ISO_DAY.matchEntire(s)?.let {
      val (y, m, d) = it.destructured
      val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() ?: return null
      if (date.year < FIRST_YEAR) return null
      return hit(DateLabel.Day(date), c.days(date, date), p + 1)
    }
    ISO_MONTH.matchEntire(s)?.let {
      val (y, m) = it.destructured
      val ym = runCatching { YearMonth.of(y.toInt(), m.toInt()) }.getOrNull() ?: return null
      if (ym.year < FIRST_YEAR) return null
      return hit(DateLabel.Month(ym), c.month(ym), p + 1)
    }
    return null
  }

  private fun bareYear(t: List<Tok>, p: Int, q: String, strong: Boolean, c: Ctx): Hit? {
    val y = yearOf(t[p].text, c) ?: return null
    val range = hit(DateLabel.Year(y), c.year(y), p + 1)
    if (strong) return range
    // No lead word: only a recent, plain year standing alone among words.
    if (y % 100 == 0 || y < c.today.year - BARE_YEAR_WINDOW) return null
    val before = t.getOrNull(p - 1)
    val after = t.getOrNull(p + 1)
    if (before != null && (before.text in YEAR_BEFORE_DENY || before.text.first().isDigit())) return null
    if (after != null && (after.text in YEAR_AFTER_DENY || after.text.first().isDigit())) return null
    val symbol = q.getOrNull(t[p].start - 1)
    if (symbol != null && symbol in "$£€#+@") return null
    return range
  }
}
