package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The TECNO KN3 case: a phone whose clock reads 31 August 2026 while its files are dated up to 19 October. A right clock must
 * behave exactly as before; a wrong one must let the date words ("in October", "this week") mean what the person means.
 */
class ClockSenseTest {
  private val zone = ZoneId.of("Africa/Kampala")
  private val hour = 60L * 60 * 1000
  private val day = 24 * hour
  private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone)
  private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

  private val wrong = at(2026, 8, 31, 13)   // the KN3's clock
  private val right = at(2026, 10, 19, 12)
  private val files = listOf(at(2026, 10, 19, 11), at(2026, 10, 19, 9), at(2026, 10, 18, 20), at(2026, 10, 12), at(2026, 9, 3)).map(::ms)

  // ---- the clock stands ------------------------------------------------------------------------------------------------

  @Test fun noEvidenceMeansTheClock() {
    assertEquals(ms(wrong), ClockSense.nowMs(ms(wrong), emptyList()))
    assertSame(wrong, ClockSense.now(wrong, emptyList()))
    assertEquals(0L, ClockSense.skewMs(ms(wrong), emptyList()))
  }

  @Test fun aRightClockIsReturnedUnchangedToTheMillisecond() {
    val dates = listOf(ms(right) - 2 * hour, ms(right) - day, ms(right) - 5 * day)
    assertEquals(ms(right), ClockSense.nowMs(ms(right), dates))
    assertSame(right, ClockSense.now(right, dates))
    assertEquals(0L, ClockSense.skewMs(ms(right), dates))
  }

  @Test fun filesJustAheadOfTheClockWithinAdayAreTheClocksBusiness() {
    val dates = listOf(ms(right) + 20 * hour, ms(right) + 10 * hour, ms(right) + hour)
    assertEquals(ms(right), ClockSense.nowMs(ms(right), dates))
  }

  @Test fun oneOrTwoFutureFilesDoNotMoveNow() {
    // a download stamped by its server, a camera set wrong: not proof
    assertEquals(ms(wrong), ClockSense.nowMs(ms(wrong), listOf(ms(wrong) + 40 * day, ms(wrong) - day, ms(wrong) - 2 * day)))
    assertEquals(ms(wrong), ClockSense.nowMs(ms(wrong), listOf(ms(wrong) + 40 * day, ms(wrong) + 30 * day, ms(wrong) - 2 * day)))
    assertSame(wrong, ClockSense.now(wrong, listOf(ms(wrong) + 40 * day, ms(wrong) + 30 * day)))
  }

  @Test fun junkDatesFarInTheFutureNeverCount() {
    val junk = listOf(ms(wrong) + 20 * 365 * day, ms(wrong) + 19 * 365 * day, ms(wrong) + 18 * 365 * day)
    assertEquals(ms(wrong), ClockSense.nowMs(ms(wrong), junk))
    // three junk dates and two honest ones: only two usable, so no proof
    assertEquals(ms(wrong), ClockSense.nowMs(ms(wrong), junk + listOf(ms(wrong) + 30 * day, ms(wrong) + 20 * day)))
  }

  // ---- the clock is behind its files ----------------------------------------------------------------------------------

  @Test fun theKn3ClockIsProvenBehindByItsThreeNewestFiles() {
    val now = ClockSense.nowMs(ms(wrong), files)
    assertEquals(ms(at(2026, 10, 18, 20)), now)   // the 3rd newest, not the newest
    assertEquals(now - ms(wrong), ClockSense.skewMs(ms(wrong), files))
  }

  @Test fun theOrderOfTheEvidenceDoesNotMatter() {
    assertEquals(ClockSense.nowMs(ms(wrong), files), ClockSense.nowMs(ms(wrong), files.reversed()))
  }

  @Test fun aJunkDateAmongTheThreeNewestIsSkipped() {
    val junk = ms(wrong) + 30 * 365 * day
    assertEquals(ms(at(2026, 10, 18, 20)), ClockSense.nowMs(ms(wrong), listOf(junk) + files))
  }

  @Test fun ceilingIsAboutFiveYearsAhead() {
    assertEquals(ms(wrong) + ClockSense.MAX_AHEAD_MS, ClockSense.ceiling(ms(wrong)))
  }

  // ---- the date words under a wrong clock ---------------------------------------------------------------------------

  private fun parse(q: String, clock: ZonedDateTime, evidence: List<Long>) =
    DateParser.parse(q, ClockSense.now(clock, evidence), DayOfWeek.MONDAY, emptySet())

  private fun range(y1: Int, m1: Int, d1: Int, y2: Int, m2: Int, d2: Int) = DateRange(ms(at(y1, m1, d1, 0)), ms(at(y2, m2, d2, 0)))

  @Test fun monthNamesAfterTheWrongClockResolveToThisYear() {
    // On the bare wrong clock "in October" is last year's October and "in September" last year's too: the bug.
    val bare = DateParser.parse("photos in October", wrong, DayOfWeek.MONDAY, emptySet())
    assertEquals(range(2025, 10, 1, 2025, 11, 1), bare.date!!.filter.range)

    val oct = parse("photos in October", wrong, files)
    assertEquals("photos", oct.text)
    assertEquals(range(2026, 10, 1, 2026, 11, 1), oct.date!!.filter.range)
    assertEquals(range(2026, 9, 1, 2026, 10, 1), parse("in September", wrong, files).date!!.filter.range)
    assertEquals(range(2026, 8, 1, 2026, 9, 1), parse("in August", wrong, files).date!!.filter.range)
  }

  @Test fun theCurrentYearIsAcceptedUnderAWrongClock() {
    // a bare "2026" is the year of the files; with the clock in 2025 it would not even parse as a year
    val behind = ZonedDateTime.of(2025, 12, 30, 12, 0, 0, 0, zone)
    val dated = listOf(at(2026, 1, 5, 10), at(2026, 1, 4, 10), at(2026, 1, 3, 10)).map(::ms)
    val r = parse("invoices 2026", behind, dated)
    assertNotNull(r.date)
    assertEquals(range(2026, 1, 1, 2027, 1, 1), r.date!!.filter.range)
    assertNull(DateParser.parse("invoices 2026", behind, DayOfWeek.MONDAY, emptySet()).date?.takeIf { it.filter.range == range(2026, 1, 1, 2027, 1, 1) })
  }

  @Test fun relativeWordsCountBackFromTheFilesNotTheClock() {
    assertEquals(range(2026, 10, 12, 2026, 10, 19), parse("this week", wrong, files).date!!.filter.range)
    assertEquals(range(2026, 10, 5, 2026, 10, 12), parse("last week", wrong, files).date!!.filter.range)
    assertEquals(range(2026, 10, 1, 2026, 11, 1), parse("photos this month", wrong, files).date!!.filter.range)
    assertEquals(range(2026, 9, 1, 2026, 10, 1), parse("last month", wrong, files).date!!.filter.range)
  }

  @Test fun aRightClockGivesExactlyTheOldAnswers() {
    val evidence = listOf(ms(right) - hour, ms(right) - 3 * hour, ms(right) - day, ms(right) - 30 * day)
    for (q in listOf("photos in October", "in September", "this week", "last week", "2 weeks ago", "photos this month", "yesterday", "2026", "in March")) {
      val old = DateParser.parse(q, right, DayOfWeek.MONDAY, emptySet())
      val sensed = parse(q, right, evidence)
      assertEquals(q, old.text, sensed.text)
      assertEquals(q, old.date?.filter, sensed.date?.filter)
    }
  }
}
