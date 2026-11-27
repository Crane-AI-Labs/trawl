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

  @Test fun dateModifiedAndDateCreatedSortIndependently() {
    val editedOldPhoto = IndexedFile(10, "content://m/10", "DCIM/", "edited_old.jpg", FileKind.IMAGE, null, 10, modifiedMillis = now - day, dateMillis = now - 500 * day)
    val freshDoc = IndexedFile(11, "content://m/11", "Docs/", "fresh_doc.txt", FileKind.DOCUMENT, null, 10, modifiedMillis = now - 10 * day, dateMillis = now - 10 * day)
    val junkCreated = IndexedFile(12, "content://m/12", "Docs/", "junk_created.txt", FileKind.DOCUMENT, null, 10, modifiedMillis = now - 20 * day, dateMillis = far)
    val all = listOf(freshDoc, editedOldPhoto, junkCreated)

    assertEquals(listOf("edited_old.jpg", "fresh_doc.txt", "junk_created.txt"), all.sortedWith(fileOrder(SortOrder.NEWEST, now)).map { it.name })
    assertEquals(listOf("junk_created.txt", "fresh_doc.txt", "edited_old.jpg"), all.sortedWith(fileOrder(SortOrder.OLDEST, now)).map { it.name })
    assertEquals(listOf("fresh_doc.txt", "edited_old.jpg", "junk_created.txt"), all.sortedWith(fileOrder(SortOrder.CREATED_NEWEST, now)).map { it.name })
    assertEquals(listOf("edited_old.jpg", "fresh_doc.txt", "junk_created.txt"), all.sortedWith(fileOrder(SortOrder.CREATED_OLDEST, now)).map { it.name })
  }

  @Test fun largestAndSmallestOrderBySizeWithDeterministicTieBreak() {
    val tiny = IndexedFile(1, "content://m/1", "Docs/", "tiny.txt", FileKind.DOCUMENT, null, sizeBytes = 50, modifiedMillis = now)
    val sameA = IndexedFile(2, "content://m/2", "Docs/", "same_a.txt", FileKind.DOCUMENT, null, sizeBytes = 500, modifiedMillis = now)
    val sameB = IndexedFile(3, "content://m/3", "Docs/", "same_b.txt", FileKind.DOCUMENT, null, sizeBytes = 500, modifiedMillis = now)
    val huge = IndexedFile(4, "content://m/4", "Docs/", "huge.mp4", FileKind.VIDEO, null, sizeBytes = 5_000, modifiedMillis = now)
    val all = listOf(sameA, huge, tiny, sameB)

    assertEquals(listOf("huge.mp4", "same_b.txt", "same_a.txt", "tiny.txt"), all.sortedWith(fileOrder(SortOrder.LARGEST, now)).map { it.name })
    assertEquals(listOf("tiny.txt", "same_a.txt", "same_b.txt", "huge.mp4"), all.sortedWith(fileOrder(SortOrder.SMALLEST, now)).map { it.name })
  }
}
