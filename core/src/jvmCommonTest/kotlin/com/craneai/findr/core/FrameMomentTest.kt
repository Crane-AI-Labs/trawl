package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The moment a picture match in a video shows: the keyframe's own time when stored, the middle of the slot's first stretch for older rows. */
class FrameMomentTest {
  @Test fun theStoredKeyframeTimeBeatsTheSlotStart() {
    // slot 12.0 to 15.5 s, but the picture is the keyframe at 14.2 s
    assertEquals(14_200L, FrameMoment.at(12_000, 15_500, FrameMoment.encode(14_200)))
  }

  @Test fun theKeyframeTimeCanSitOutsideTheSlot() {
    // a forwarded video has a keyframe every ~8 s; the one nearest a slot's middle can be before its start
    assertEquals(8_333L, FrameMoment.at(10_000, 14_500, FrameMoment.encode(8_333)))
  }

  @Test fun aRowFromBeforeFallsBackToTheMiddleNotTheStart() {
    assertEquals(13_750L, FrameMoment.at(12_000, 15_500, null))
    assertEquals(13_750L, FrameMoment.at(12_000, 15_500, ""))
  }

  @Test fun aMergedLookAlikeSlotLeadsByAtMostFiveSeconds() {
    // look-alike frames grew the slot to 40 s: the picture was asked for from the first slot's middle, not the grown one's
    assertEquals(25_000L, FrameMoment.at(20_000, 60_000, null))
  }

  @Test fun noEndOrAnEmptySlotShowsTheStart() {
    assertEquals(7_000L, FrameMoment.at(7_000, null, null))
    assertEquals(7_000L, FrameMoment.at(7_000, 7_000, null))
  }

  @Test fun marksOfOtherKindsOrGarbageAreNotATime() {
    assertNull(FrameMoment.decode("0:100:200"))
    assertNull(FrameMoment.decode("@abc"))
    assertNull(FrameMoment.decode("@-5"))
    assertNull(FrameMoment.decode(null))
    // unreadable: the old-row rule, 8_000 + half of 4_000
    assertEquals(10_000L, FrameMoment.at(8_000, 12_000, "0:100:200"))
  }

  @Test fun roundTrips() {
    assertEquals(123_456L, FrameMoment.decode(FrameMoment.encode(123_456)))
  }
}
