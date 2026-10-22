package com.craneai.findr.core

import java.time.Instant
import java.time.ZonedDateTime

/**
 * What "now" is, when the phone's clock is behind its own files.
 *
 * A budget phone can have a wrong clock (a dead clock battery, a reset, a person who set it back): a TECNO read 31 August while
 * its files were dated up to 19 October. Everything that counts back from "now" then goes wrong at once: "in September" and "in
 * October" name months that have not happened yet, so they find nothing; "this week" is a week without files; the 14-day
 * recent-first queue priority ([com.craneai.findr.index.Schema.priority]) takes every file after the clock as recent; and the
 * Recent list shows "In 28 min." for files that are 7 weeks old.
 *
 * The rule, small on purpose: the clock stands unless the files themselves prove it behind. A file cannot be from the future, so
 * when at least [EVIDENCE_FILES] files are dated more than [TOLERANCE_MS] after the clock, the clock is wrong, and "now" is the date
 * of the [EVIDENCE_FILES]-th newest file (never the newest: one or two files with a junk date, a download stamped by its server,
 * must not move "now"). Dates more than [MAX_AHEAD_MS] past the clock are junk and never count. When the clock is right (no, or too
 * few, files dated ahead of it) the answer is the clock itself, to the millisecond, so nothing changes for a phone with a right clock.
 *
 * Pure: the evidence (the newest files' dates, from `IndexStore.newestDates`) comes in.
 */
object ClockSense {
  /** How many files must be dated ahead of the clock before it is called wrong. Also how many newest dates the evidence holds. */
  const val EVIDENCE_FILES = 3

  /** A file dated this far past the clock is still the clock's business (time zones, a camera set a few hours off, sync lag). */
  const val TOLERANCE_MS = 24L * 60 * 60 * 1000

  /** Past this the date is junk (a 2038 stamp, a counter read as a time), whatever the clock says. About five years. */
  const val MAX_AHEAD_MS = 5L * 366 * 24 * 60 * 60 * 1000

  /** The latest date that may count as evidence against a clock reading [clockMs]: ask the store for newest dates not after it. */
  fun ceiling(clockMs: Long): Long = clockMs + MAX_AHEAD_MS

  /**
   * "Now" in epoch milliseconds: [clockMs], or the date of the [EVIDENCE_FILES]-th newest of [newestDates] when that is more than
   * [TOLERANCE_MS] after it. [newestDates] may hold any dates in any order (more than [EVIDENCE_FILES] are fine, dates past the
   * [ceiling] are ignored); fewer than [EVIDENCE_FILES] usable dates prove nothing.
   */
  fun nowMs(clockMs: Long, newestDates: Collection<Long>): Long {
    if (newestDates.size < EVIDENCE_FILES) return clockMs
    val limit = ceiling(clockMs)
    val usable = newestDates.filter { it <= limit }
    if (usable.size < EVIDENCE_FILES) return clockMs
    val third = usable.sortedDescending()[EVIDENCE_FILES - 1]
    return if (third > clockMs + TOLERANCE_MS) third else clockMs
  }

  /** How far ahead of the clock "now" is: 0 for a right clock, else the amount the files prove it behind. */
  fun skewMs(clockMs: Long, newestDates: Collection<Long>): Long = nowMs(clockMs, newestDates) - clockMs

  /** [clock] as [nowMs] sees it: the very same value when the clock stands, else the proven "now" in the clock's own zone. */
  fun now(clock: ZonedDateTime, newestDates: Collection<Long>): ZonedDateTime {
    val clockMs = clock.toInstant().toEpochMilli()
    val ms = nowMs(clockMs, newestDates)
    return if (ms == clockMs) clock else Instant.ofEpochMilli(ms).atZone(clock.zone)
  }
}
