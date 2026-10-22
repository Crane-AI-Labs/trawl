package com.craneai.findr.engine

import com.craneai.findr.engine.ScreenOffRecord.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOffMeterTest {
  @Test fun aPhoneThatKeepsRunningReadsAsRunning() {
    val m = ScreenOffMeter()
    for (s in 0..600) m.tick(s * 1_000L, screenOff = true)
    assertEquals(600_000L, m.observedMs)
    assertEquals(600_000L, m.ranMs)
  }

  @Test fun aFrozenPhoneShowsAsLateTicks() {
    // The KN4: one tick, then nothing for 29 minutes, then a tick: the process ran 1 s of 1740 s.
    val m = ScreenOffMeter()
    m.tick(0, true)
    m.tick(1_000, true)
    m.tick(1_741_000, true)
    assertEquals(1_741_000L, m.observedMs)
    assertEquals(2_000L, m.ranMs)
  }

  @Test fun aPhoneThatFreezesNowAndThenStillCountsAsWorking() {
    // The Infinix thaws the app every few seconds: five on-time ticks, then one 4 s stall (1 s run, 3 s frozen), over and over.
    val m = ScreenOffMeter()
    var t = 0L
    m.tick(t, true)
    repeat(100) {
      repeat(5) { t += 1_000; m.tick(t, true) }
      t += 4_000; m.tick(t, true)
    }
    assertEquals(900_000L, m.observedMs)
    assertEquals(600_000L, m.ranMs)
    assertEquals(Verdict.WORKS, ScreenOffRecord(m.observedMs, m.ranMs).verdict)
  }

  @Test fun theScreenComingOnIsNotAFreeze() {
    val m = ScreenOffMeter()
    m.tick(0, true)
    m.tick(1_000, true)
    m.tick(500_000, false)   // the person woke the phone: that stretch says nothing
    m.tick(501_000, false)
    m.tick(502_000, true)    // and the screen going off again starts a new stretch
    assertEquals(1_000L, m.observedMs)
  }

  @Test fun drainStartsAgain() {
    val m = ScreenOffMeter()
    m.tick(0, true)
    m.tick(1_000, true)
    assertEquals(ScreenOffRecord(1_000, 1_000), m.drain())
    assertEquals(0L, m.observedMs)
    m.tick(2_000, true)
    assertEquals(ScreenOffRecord(1_000, 1_000), m.drain())
  }

  @Test fun verdictNeedsTenMinutesAndHalfOfThemRunning() {
    assertEquals(Verdict.UNKNOWN, ScreenOffRecord(9 * 60_000L, 0).verdict)
    assertEquals(Verdict.PAUSED, ScreenOffRecord(30 * 60_000L, 40_000L).verdict)       // KN4: ~2%
    assertEquals(Verdict.WORKS, ScreenOffRecord(30 * 60_000L, 28 * 60_000L).verdict)   // Infinix: ~95%
    assertEquals(Verdict.WORKS, ScreenOffRecord(10 * 60_000L, 5 * 60_000L).verdict)
    assertEquals(Verdict.PAUSED, ScreenOffRecord(10 * 60_000L, 5 * 60_000L - 1).verdict)
  }

  @Test fun oldEvidenceFades() {
    val big = ScreenOffRecord(13 * 3_600_000L, 13 * 3_600_000L) + ScreenOffRecord(0, 0)
    assertEquals(ScreenOffRecord(13 * 3_600_000L / 2, 13 * 3_600_000L / 2), big)
  }

  @Test fun storedValueRoundTripsAndJunkReadsAsEmpty() {
    val r = ScreenOffRecord(123_000, 45_000)
    assertEquals(r, ScreenOffRecord.decode(r.encode()))
    assertEquals(ScreenOffRecord(), ScreenOffRecord.decode(null))
    assertEquals(ScreenOffRecord(), ScreenOffRecord.decode("abc"))
    assertEquals(ScreenOffRecord(), ScreenOffRecord.decode("10,20"))    // ran more than observed
    assertEquals(ScreenOffRecord(), ScreenOffRecord.decode("-5,1"))
  }
}
