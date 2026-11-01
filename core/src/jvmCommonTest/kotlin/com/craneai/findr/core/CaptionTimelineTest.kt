package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words read off a video's frames keep the moment of the frame each line first appeared in. */
class CaptionTimelineTest {
  @Test fun aRepeatedLineIsKeptOnceAtTheEarliestFrame() {
    val t = CaptionTimeline()
    t.add("GRAND OPENING", 20_000)
    t.add("SALE ENDS FRIDAY", 20_000)
    t.add("GRAND OPENING", 5_000)    // the same caption, read in an earlier frame: that is when it appeared
    t.add("GRAND OPENING", 45_000)   // and again later: no change
    assertEquals(listOf("GRAND OPENING", "SALE ENDS FRIDAY"), t.lines())
    assertEquals("GRAND OPENING\nSALE ENDS FRIDAY", t.text())
    assertEquals(5_000L, t.startMs)
    assertEquals(20_000L, t.endMs)
  }

  @Test fun marksNameEachLinesOffsetAndFrameTime() {
    val t = CaptionTimeline()
    t.add("  one  ", 1_000)
    t.add("", 2_000)          // blank lines are not captions
    t.add("   ", 2_000)
    t.add("second line", 9_000)
    t.add("third", 30_000)
    assertEquals("one\nsecond line\nthird", t.text())
    val encoded = t.marks()
    assertEquals("0:1000:1000,4:9000:9000,16:30000:30000", encoded)
    val marks = TranscriptMarks.decode(encoded)
    assertEquals(listOf(0, 4, 16), marks.map { it.offset })
    // every character of a line is placed at its line's frame, the newline joining it to the next included
    val text = t.text()
    assertEquals(1_000L, TranscriptMarks.timeAt(marks, text.length, t.startMs, t.endMs, text.indexOf("one")))
    assertEquals(9_000L, TranscriptMarks.timeAt(marks, text.length, t.startMs, t.endMs, text.indexOf("line")))
    assertEquals(9_000L, TranscriptMarks.timeAt(marks, text.length, t.startMs, t.endMs, text.indexOf("second")))
    assertEquals(30_000L, TranscriptMarks.timeAt(marks, text.length, t.startMs, t.endMs, text.indexOf("third")))
  }

  @Test fun noLinesMeansNothingToStore() {
    val t = CaptionTimeline()
    assertTrue(t.isEmpty())
    t.add("\n", 100)
    assertTrue(t.isEmpty())
    assertNull(t.startMs)
    assertNull(t.endMs)
    assertNull(t.marks())
    assertEquals("", t.text())
  }

  @Test fun linesKeepTheOrderTheyWereFirstSeenIn() {
    val t = CaptionTimeline()
    t.add("b", 3_000); t.add("a", 1_000); t.add("c", 2_000)
    assertEquals(listOf("b", "a", "c"), t.lines())   // the order of reading, which is what the offsets in the marks follow
    assertEquals("0:3000:3000,2:1000:1000,4:2000:2000", t.marks())
  }
}
