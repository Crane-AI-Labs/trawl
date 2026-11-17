package com.craneai.findr.engine

import com.craneai.findr.engine.PrivacyPace.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPaceTest {
  private var now = 0L
  private var work = true
  private var hot = true
  private var workReads = 0

  private fun pace(enabled: Boolean = true, catchUp: Boolean = true, maxMs: Long = PrivacyPace.MAX_PACE_MS) =
    PrivacyPace(clock = { now }, searchableWorkLeft = { workReads++; work }, warm = { hot }, enabled = enabled, catchUp = catchUp, maxPaceMs = maxMs)

  @Test fun pacesWhileWarmAndSearchableWorkIsLeft() {
    val p = pace()
    assertEquals(Mode.PACE, p.mode())
    // The measured privB arm: 4x the work (duty 0.2, at most 10 s), on top of the usual share while EmbeddingGemma embeds.
    assertEquals(4_000L, p.restMs(Mode.PACE, 1_000, embedding = false, busyDuty = 0.2))
    assertEquals(4_000L + 4_000L, p.restMs(Mode.PACE, 1_000, embedding = true, busyDuty = 0.2))
    assertEquals(HeatPace.MAX_REST_MS, p.restMs(Mode.PACE, 60_000, embedding = false, busyDuty = 0.2))
  }

  @Test fun aCoolPhoneRunsAs107() {
    hot = false
    val p = pace()
    assertEquals(Mode.FLAT, p.mode())
    assertEquals(0L, p.restMs(Mode.FLAT, 1_000, embedding = false, busyDuty = 0.2))
    assertEquals("the 1.0.7 share while EmbeddingGemma embeds stays", 4_000L, p.restMs(Mode.FLAT, 1_000, embedding = true, busyDuty = 0.2))
    assertEquals(0L, p.pacedMs())
  }

  @Test fun switchesToCatchUpTheMomentQuickLookAndWordsAreDone() {
    val p = pace()
    assertEquals(Mode.PACE, p.mode())
    now += 60_000
    work = false
    assertEquals(Mode.CATCH_UP, p.mode())
    assertEquals("no rest at all, not even while EmbeddingGemma embeds", 0L, p.restMs(Mode.CATCH_UP, 1_000, embedding = true, busyDuty = 0.2))
    assertEquals(0L, p.restMs(Mode.CATCH_UP, 1_000, embedding = false, busyDuty = 0.2))
    assertEquals(Mode.CATCH_UP, p.mode())
  }

  @Test fun catchUpStaysCoolOrWarm() {
    work = false
    val p = pace()
    hot = true; assertEquals(Mode.CATCH_UP, p.mode())
    now += 10_000; hot = false; assertEquals(Mode.CATCH_UP, p.mode())
  }

  @Test fun gentleKeepsTheUsualShareWhileCatchingUp() {
    work = false
    val p = pace(catchUp = false)
    assertEquals(Mode.CATCH_UP, p.mode())
    assertEquals(4_000L, p.restMs(Mode.CATCH_UP, 1_000, embedding = true, busyDuty = 0.2))
    assertEquals(0L, p.restMs(Mode.CATCH_UP, 1_000, embedding = false, busyDuty = 0.2))
  }

  @Test fun theCapEndsPacingEvenWithWorkLeft() {
    val p = pace()
    assertEquals(Mode.PACE, p.mode())
    now += PrivacyPace.MAX_PACE_MS - 1
    assertEquals(Mode.PACE, p.mode())
    now += 1
    assertEquals(Mode.FLAT, p.mode())
    // Capped for the rest of the episode: cooling and warming again does not restart the clock.
    hot = false; now += 10_000; assertEquals(Mode.FLAT, p.mode())
    hot = true; now += 10_000; assertEquals(Mode.FLAT, p.mode())
  }

  @Test fun aNewBacklogGetsAFreshEpisode() {
    val p = pace(maxMs = 60_000)
    assertEquals(Mode.PACE, p.mode())
    now += 60_000
    assertEquals(Mode.FLAT, p.mode())
    work = false; now += 5_000
    assertEquals(Mode.CATCH_UP, p.mode())
    work = true; now += 5_000
    assertEquals(Mode.PACE, p.mode())
    now += 30_000
    assertEquals(30_000L, p.pacedMs())
  }

  @Test fun theEpisodeClockStartsAtTheFirstPaceNotAtTheFirstLook() {
    hot = false
    val p = pace(maxMs = 60_000)
    assertEquals(Mode.FLAT, p.mode())
    now += 120_000; hot = true
    assertEquals(Mode.PACE, p.mode())
    now += 59_000
    assertEquals(Mode.PACE, p.mode())
  }

  @Test fun theStoreIsReadAtMostEveryFiveSeconds() {
    val p = pace()
    repeat(10) { p.mode(); now += 400 }
    assertEquals(1, workReads)
    // A finished backlog is seen within WORK_READ_MS.
    work = false
    now += PrivacyPace.WORK_READ_MS
    assertEquals(Mode.CATCH_UP, p.mode())
    assertEquals(2, workReads)
  }

  @Test fun aZeroCapNeverPacesButStillCatchesUp() {
    val p = pace(maxMs = 0)
    assertEquals(Mode.FLAT, p.mode())
    now += 60_000
    assertEquals(Mode.FLAT, p.mode())
    work = false; now += PrivacyPace.WORK_READ_MS
    assertEquals(Mode.CATCH_UP, p.mode())
    assertEquals(0L, p.restMs(Mode.CATCH_UP, 1_000, embedding = true, busyDuty = 0.2))
  }

  @Test fun offIsExactly107() {
    work = false
    val p = pace(enabled = false)
    assertEquals(Mode.FLAT, p.mode())
    assertEquals(4_000L, p.restMs(Mode.FLAT, 1_000, embedding = true, busyDuty = 0.2))
    work = true
    assertEquals(Mode.FLAT, p.mode())
    assertEquals(0, workReads)
  }

  @Test fun warmIsTheStudysTriggerOnAnyPhone() {
    assertTrue(PrivacyPace.warm(38.0, capKhz = 2_000_000, maxKhz = 2_000_000))
    assertFalse(PrivacyPace.warm(37.9, capKhz = 2_000_000, maxKhz = 2_000_000))
    assertTrue("A06: capped under 2.0 GHz", PrivacyPace.warm(34.0, capKhz = 1_443_000, maxKhz = 2_000_000))
    assertFalse("a slower phone at its own maximum is not warm", PrivacyPace.warm(34.0, capKhz = 1_600_000, maxKhz = 1_600_000))
    assertFalse("an unreadable cap is not warm", PrivacyPace.warm(34.0, capKhz = null, maxKhz = 2_000_000))
    assertFalse(PrivacyPace.warm(34.0, capKhz = 1_000_000, maxKhz = null))
  }
}
