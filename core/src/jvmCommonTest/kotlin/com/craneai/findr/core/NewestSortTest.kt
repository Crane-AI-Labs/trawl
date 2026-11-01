package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8 (UX bugs 1.0.5): a file stamped in 2099 must not sit above every real file in "Newest". */
class NewestSortTest {
  private val now = 1_790_000_000_000L          // the (injected) clock
  private val day = 86_400_000L
  private val far = now + 73L * 366 * day        // "2099"

  private fun file(id: Long, name: String, date: Long) =
    IndexedFile(id, "content://m/$id", "Download/", name, FileKind.DOCUMENT, null, 10, date, dateMillis = date)

  private val real = file(1, "today.txt", now - day)
  private val older = file(2, "last_year.txt", now - 365 * day)
  private val junk = file(3, "stamped_2099.txt", far)
  private val junkLater = file(4, "stamped_2100.txt", far + 366 * day)

  @Test fun junkTestFollowsTheClockCeiling() {
    assertTrue(ClockSense.isJunkFuture(far, now))
    assertFalse(ClockSense.isJunkFuture(now + day, now))
    assertFalse(ClockSense.isJunkFuture(now + ClockSense.MAX_AHEAD_MS, now))
    assertTrue(ClockSense.isJunkFuture(now + ClockSense.MAX_AHEAD_MS + 1, now))
  }

  @Test fun newestPutsJunkFutureDatesLast() {
    val ordered = listOf(junk, older, real, junkLater).sortedWith(fileOrder(SortOrder.NEWEST, now))
    assertEquals(listOf("today.txt", "last_year.txt", "stamped_2100.txt", "stamped_2099.txt"), ordered.map { it.name })
  }

  @Test fun bestFallsBackToTheSameNewestOrder() {
    val ordered = listOf(junk, older, real).sortedWith(fileOrder(SortOrder.BEST, now))
    assertEquals(listOf("today.txt", "last_year.txt", "stamped_2099.txt"), ordered.map { it.name })
  }

  @Test fun aFileAheadOfAWrongClockStillSortsAsNewest() {
    // A phone whose clock is 108 days behind (the A06): its newest real files are months "ahead" of the clock, and stay first.
    val ahead = file(5, "made_after_clock_reset.txt", now + 108 * day)
    val ordered = listOf(real, junk, ahead).sortedWith(fileOrder(SortOrder.NEWEST, now))
    assertEquals(listOf("made_after_clock_reset.txt", "today.txt", "stamped_2099.txt"), ordered.map { it.name })
  }

  @Test fun oldestKeepsJunkAtTheEnd() {
    val ordered = listOf(junk, real, older).sortedWith(fileOrder(SortOrder.OLDEST, now))
    assertEquals(listOf("last_year.txt", "today.txt", "stamped_2099.txt"), ordered.map { it.name })
  }

  @Test fun searchResultsSortedByNewestKeepJunkBelowRealWithinStrongAndWeak() {
    val hits = listOf(junk, older, real, file(6, "weak_new.txt", now - 2 * day), file(7, "weak_junk.txt", far))
    val ordered = sortRanked(hits, strong = 3, order = SortOrder.NEWEST, nowMs = now) { it }
    assertEquals(listOf("today.txt", "last_year.txt", "stamped_2099.txt", "weak_new.txt", "weak_junk.txt"), ordered.map { it.name })
  }
}
