package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** Today is Friday 16 October 2026, 14:30 in Kampala. Weeks start on Monday unless a test says otherwise. */
class DateParserTest {
  private val zone = ZoneId.of("Africa/Kampala")
  private val now = ZonedDateTime.of(2026, 10, 16, 14, 30, 0, 0, zone)

  private fun ms(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
  private fun range(y1: Int, m1: Int, d1: Int, y2: Int, m2: Int, d2: Int) = DateRange(ms(y1, m1, d1), ms(y2, m2, d2))
  private fun parse(q: String, weekStart: DayOfWeek = DayOfWeek.MONDAY, ignored: Set<String> = emptySet()) = DateParser.parse(q, now, weekStart, ignored)

  private fun assertDate(q: String, text: String, label: DateLabel, expected: DateRange) {
    val r = parse(q)
    assertNotNull("no date in \"$q\"", r.date)
    assertEquals("remainder of \"$q\"", text, r.text)
    assertEquals("label of \"$q\"", label, r.date!!.filter.label)
    assertEquals("range of \"$q\"", expected, r.date!!.filter.range)
  }

  private fun assertNoDate(q: String) {
    val r = parse(q)
    assertNull("\"$q\" was read as a date: ${r.date?.phrase}", r.date)
    assertEquals(q, r.text)
  }

  // ---- relative days and parts of the day ----------------------------------------------------------------------------

  @Test fun today() = assertDate("receipts today", "receipts", DateLabel.Today, range(2026, 10, 16, 2026, 10, 17))
  @Test fun yesterday() = assertDate("yesterday", "", DateLabel.Yesterday, range(2026, 10, 15, 2026, 10, 16))
  @Test fun thisMorning() = assertDate("photos this morning", "photos", DateLabel.Part(DayPart.MORNING, false), DateRange(ms(2026, 10, 16, 0), ms(2026, 10, 16, 12)))
  @Test fun thisAfternoon() = assertDate("this afternoon notes", "notes", DateLabel.Part(DayPart.AFTERNOON, false), DateRange(ms(2026, 10, 16, 12), ms(2026, 10, 16, 18)))
  @Test fun thisEvening() = assertDate("this evening", "", DateLabel.Part(DayPart.EVENING, false), DateRange(ms(2026, 10, 16, 18), ms(2026, 10, 17, 0)))
  @Test fun yesterdayEvening() = assertDate("yesterday evening", "", DateLabel.Part(DayPart.EVENING, true), DateRange(ms(2026, 10, 15, 18), ms(2026, 10, 16, 0)))
  @Test fun lastNight() = assertDate("last night", "", DateLabel.LastNight, DateRange(ms(2026, 10, 15, 18), ms(2026, 10, 16, 6)))
  @Test fun daysAgo() = assertDate("3 days ago", "", DateLabel.Ago(3, DateUnit.DAY), range(2026, 10, 13, 2026, 10, 14))
  @Test fun aWeekAgoIsTheCalendarWeekBefore() = assertDate("a week ago", "", DateLabel.Ago(1, DateUnit.WEEK), range(2026, 10, 5, 2026, 10, 12))
  @Test fun monthsAgo() = assertDate("two months ago", "", DateLabel.Ago(2, DateUnit.MONTH), range(2026, 8, 1, 2026, 9, 1))

  // ---- weeks, months, years ---------------------------------------------------------------------------------------------

  @Test fun lastWeekWithTheLeadWordGoneToo() = assertDate("receipts from last week", "receipts", DateLabel.LastWeek, range(2026, 10, 5, 2026, 10, 12))
  @Test fun thisWeek() = assertDate("this week", "", DateLabel.ThisWeek, range(2026, 10, 12, 2026, 10, 19))
  @Test fun weekStartsWhereTheLocaleSays() {
    val sunday = parse("this week", DayOfWeek.SUNDAY).date!!.filter.range
    assertEquals(range(2026, 10, 11, 2026, 10, 18), sunday)
  }
  @Test fun thisMonth() = assertDate("invoices this month", "invoices", DateLabel.ThisMonth, range(2026, 10, 1, 2026, 11, 1))
  @Test fun lastMonth() = assertDate("last month", "", DateLabel.LastMonth, range(2026, 9, 1, 2026, 10, 1))
  @Test fun lastMonthAcrossNewYear() {
    val jan = ZonedDateTime.of(2027, 1, 10, 9, 0, 0, 0, zone)
    assertEquals(range(2026, 12, 1, 2027, 1, 1), DateParser.parse("last month", jan).date!!.filter.range)
  }
  @Test fun thisYear() = assertDate("this year", "", DateLabel.ThisYear, range(2026, 1, 1, 2027, 1, 1))
  @Test fun lastYear() = assertDate("tax last year", "tax", DateLabel.LastYear, range(2025, 1, 1, 2026, 1, 1))
  @Test fun lastWeekend() = assertDate("last weekend", "", DateLabel.Weekend, range(2026, 10, 10, 2026, 10, 12))
  @Test fun last7Days() = assertDate("photos last 7 days", "photos", DateLabel.Past(7, DateUnit.DAY), range(2026, 10, 9, 2026, 10, 17))
  @Test fun pastTwoWeeks() = assertDate("past two weeks", "", DateLabel.Past(2, DateUnit.WEEK), range(2026, 10, 2, 2026, 10, 17))
  @Test fun pastMonth() = assertDate("in the past month", "", DateLabel.Past(1, DateUnit.MONTH), range(2026, 9, 16, 2026, 10, 17))
  @Test fun lastFriday() = assertDate("last friday", "", DateLabel.Day(LocalDate.of(2026, 10, 9)), range(2026, 10, 9, 2026, 10, 10))
  @Test fun onMondayIsTheMostRecentMonday() = assertDate("on monday", "", DateLabel.Day(LocalDate.of(2026, 10, 12)), range(2026, 10, 12, 2026, 10, 13))
  @Test fun onFridayIsToday() = assertDate("notes on friday", "notes", DateLabel.Day(LocalDate.of(2026, 10, 16)), range(2026, 10, 16, 2026, 10, 17))

  // ---- months and years written out ------------------------------------------------------------------------------------

  @Test fun inDecemberMeansTheLastDecemberThatHappened() = assertDate("in December", "", DateLabel.Month(YearMonth.of(2025, 12)), range(2025, 12, 1, 2026, 1, 1))
  @Test fun aMonthNotYetReachedThisYearIsLastYears() = assertDate("receipts in November", "receipts", DateLabel.Month(YearMonth.of(2025, 11)), range(2025, 11, 1, 2025, 12, 1))
  @Test fun theCurrentMonthIsThisYears() = assertDate("photos in October", "photos", DateLabel.Month(YearMonth.of(2026, 10)), range(2026, 10, 1, 2026, 11, 1))
  @Test fun monthAndYear() = assertDate("December 2023", "", DateLabel.Month(YearMonth.of(2023, 12)), range(2023, 12, 1, 2024, 1, 1))
  @Test fun monthAndYearAmongWords() = assertDate("wedding photos from june 2022 kampala", "wedding photos kampala", DateLabel.Month(YearMonth.of(2022, 6)), range(2022, 6, 1, 2022, 7, 1))
  @Test fun bareUnambiguousMonth() = assertDate("december photos", "photos", DateLabel.Month(YearMonth.of(2025, 12)), range(2025, 12, 1, 2026, 1, 1))
  @Test fun lastMarch() = assertDate("last march", "", DateLabel.Month(YearMonth.of(2026, 3)), range(2026, 3, 1, 2026, 4, 1))
  @Test fun lastOctoberIsNotThisOctober() = assertDate("last october", "", DateLabel.Month(YearMonth.of(2025, 10)), range(2025, 10, 1, 2025, 11, 1))
  @Test fun thisDecemberIsThisYears() = assertDate("this december", "", DateLabel.Month(YearMonth.of(2026, 12)), range(2026, 12, 1, 2027, 1, 1))
  @Test fun aYearWithALead() = assertDate("photos in 2024", "photos", DateLabel.Year(2024), range(2024, 1, 1, 2025, 1, 1))
  @Test fun aBareRecentYear() = assertDate("2024", "", DateLabel.Year(2024), range(2024, 1, 1, 2025, 1, 1))
  @Test fun aYearAfterWords() = assertDate("tax returns 2023", "tax returns", DateLabel.Year(2023), range(2023, 1, 1, 2024, 1, 1))
  @Test fun anOldYearNeedsALeadWord() = assertDate("from 2012", "", DateLabel.Year(2012), range(2012, 1, 1, 2013, 1, 1))
  @Test fun dayAndMonth() = assertDate("5 December", "", DateLabel.Day(LocalDate.of(2025, 12, 5)), range(2025, 12, 5, 2025, 12, 6))
  @Test fun dayMonthYear() = assertDate("5th of march 2023 minutes", "minutes", DateLabel.Day(LocalDate.of(2023, 3, 5)), range(2023, 3, 5, 2023, 3, 6))
  @Test fun monthDay() = assertDate("december 25", "", DateLabel.Day(LocalDate.of(2025, 12, 25)), range(2025, 12, 25, 2025, 12, 26))
  @Test fun monthDayOrdinalYear() = assertDate("May 5th 2024", "", DateLabel.Day(LocalDate.of(2024, 5, 5)), range(2024, 5, 5, 2024, 5, 6))
  @Test fun isoDate() = assertDate("scan 2024-05-12", "scan", DateLabel.Day(LocalDate.of(2024, 5, 12)), range(2024, 5, 12, 2024, 5, 13))
  @Test fun isoMonth() = assertDate("2024-05", "", DateLabel.Month(YearMonth.of(2024, 5)), range(2024, 5, 1, 2024, 6, 1))
  @Test fun abbreviationWithALead() = assertDate("in dec", "", DateLabel.Month(YearMonth.of(2025, 12)), range(2025, 12, 1, 2026, 1, 1))
  @Test fun abbreviationWithADay() = assertDate("12 sep", "", DateLabel.Day(LocalDate.of(2026, 9, 12)), range(2026, 9, 12, 2026, 9, 13))

  // ---- what stays out of the semantic query -----------------------------------------------------------------------------

  @Test fun theLeadWordsAndTheDateGoAndTheRestStays() {
    val r = parse("Receipts  from the last week please")
    assertEquals("Receipts please", r.text)
    assertEquals("from the last week", r.date!!.phrase)
  }
  @Test fun aDanglingOfIsDropped() = assertEquals("photos", parse("photos of last week").text)
  @Test fun onlyTheFirstDateIsTaken() {
    val r = parse("last week december")
    assertEquals(DateLabel.LastWeek, r.date!!.filter.label)
    assertEquals("december", r.text)
  }
  @Test fun theSpanPointsIntoTheOriginalQuery() {
    val q = "rent receipts in december 2023 kampala"
    val d = parse(q).date!!
    assertEquals("in december 2023", q.substring(d.start, d.end))
  }
  @Test fun aDismissedPhraseIsOrdinaryTextAgain() {
    val first = parse("receipts last week").date!!
    val again = parse("receipts last week", ignored = setOf(first.phrase))
    assertNull(again.date)
    assertEquals("receipts last week", again.text)
  }
  @Test fun aDismissedPhraseLetsAnotherDateThrough() {
    val r = parse("last week 2024", ignored = setOf("last week"))
    assertEquals(DateLabel.Year(2024), r.date!!.filter.label)
    assertEquals("last week", r.text)
  }

  // ---- traps: ordinary words and numbers are not dates ------------------------------------------------------------------

  @Test fun mayAsAnAuxiliaryVerb() {
    assertNoDate("documents that may contain secrets")
    assertNoDate("this may be wrong")
    assertNoDate("you may not share")
    assertNoDate("may")
    assertNoDate("may notes")
  }
  @Test fun mayWithALeadIsStillAVerbBeforeAVerb() {
    assertNoDate("in may be the answer")
  }
  @Test fun mayAsAMonthWhenItHasCompany() {
    assertDate("photos in may", "photos", DateLabel.Month(YearMonth.of(2026, 5)), range(2026, 5, 1, 2026, 6, 1))
    assertDate("may 2023", "", DateLabel.Month(YearMonth.of(2023, 5)), range(2023, 5, 1, 2023, 6, 1))
    assertDate("5 may", "", DateLabel.Day(LocalDate.of(2026, 5, 5)), range(2026, 5, 5, 2026, 5, 6))
  }
  @Test fun marchAsAVerbOrAProtest() {
    assertNoDate("march")
    assertNoDate("women's march photos")
    assertNoDate("march on kampala")
    assertNoDate("we march to the park")
    assertNoDate("protest march for water")
    assertNoDate("in march to the sea")
  }
  @Test fun marchAsAMonthWhenItHasCompany() {
    assertDate("budget in march", "budget", DateLabel.Month(YearMonth.of(2026, 3)), range(2026, 3, 1, 2026, 4, 1))
    assertDate("march 2024", "", DateLabel.Month(YearMonth.of(2024, 3)), range(2024, 3, 1, 2024, 4, 1))
  }
  @Test fun namesAndAbbreviationsThatAreWordsStayWordsWithoutCompany() {
    assertNoDate("april")
    assertNoDate("june invoice")
    assertNoDate("august company")
    assertNoDate("dec")
    assertNoDate("mar")
    assertNoDate("sat sun")
    assertNoDate("sept report")
  }
  @Test fun pricesAreNotYears() {
    assertNoDate("2000 shillings")
    assertNoDate("UGX 2024")
    assertNoDate("ugx 2023 receipt")
    assertNoDate("price 2024")
    assertNoDate("\$2024")
    assertNoDate("2024 usd")
    assertNoDate("5000")
    assertNoDate("2000")
    assertNoDate("2,024 total")
  }
  @Test fun identifiersAndQuantitiesAreNotYears() {
    assertNoDate("order 2024")
    assertNoDate("invoice no 2025")
    assertNoDate("0772 2024 555")
    assertNoDate("256772123456")
    assertNoDate("2023 2024 2025")
    assertNoDate("img_2024")
    assertNoDate("IMG-2024-WA0001")
    assertNoDate("1500 mb")
    assertNoDate("2024 people")
    assertNoDate("room 2024")
    assertNoDate("2012 toyota")
  }
  @Test fun numbersOutsideTheYearRangeAreNotYears() {
    assertNoDate("1989")
    assertNoDate("in 3000")
    assertNoDate("2099")
    assertNoDate("12345")
  }
  @Test fun aRoundYearNeedsALead() {
    assertNoDate("2000 notes")
    assertDate("notes from 2000", "notes", DateLabel.Year(2000), range(2000, 1, 1, 2001, 1, 1))
  }
  @Test fun everydayWordsAreNotDates() {
    assertNoDate("last page of the contract")
    assertNoDate("this is the last one")
    assertNoDate("the year of the rat")
    assertNoDate("week")
    assertNoDate("a month")
    assertNoDate("pass the day")
    assertNoDate("monday meeting")
    assertNoDate("on the table")
    assertNoDate("in the morning")
    assertNoDate("sunday school")
    assertNoDate("32 march")
    assertNoDate("31 april")
  }
  @Test fun theQueryIsReturnedUntouchedWhenThereIsNoDate() {
    val r = parse("  Boda  RECEIPTS ")
    assertNull(r.date)
    assertEquals("  Boda  RECEIPTS ", r.text)
  }
  @Test fun emptyQuery() {
    assertNull(parse("").date)
    assertNull(parse("   ").date)
  }

  // ---- presets and the hand-made range ---------------------------------------------------------------------------------

  @Test fun presets() {
    assertEquals(range(2026, 10, 16, 2026, 10, 17), DateParser.preset(DatePreset.TODAY, now).range)
    assertEquals(range(2026, 10, 12, 2026, 10, 19), DateParser.preset(DatePreset.THIS_WEEK, now).range)
    assertEquals(range(2026, 10, 1, 2026, 11, 1), DateParser.preset(DatePreset.THIS_MONTH, now).range)
    assertEquals(range(2026, 1, 1, 2027, 1, 1), DateParser.preset(DatePreset.THIS_YEAR, now).range)
  }
  @Test fun aCustomRangeIncludesBothEndDays() {
    val f = DateParser.custom(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 12), zone)
    assertEquals(range(2026, 3, 10, 2026, 3, 13), f.range)
    assertTrue(f.range.contains(ms(2026, 3, 12, 23)))
    assertEquals(f.range, DateParser.custom(LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 10), zone).range)
  }
}
