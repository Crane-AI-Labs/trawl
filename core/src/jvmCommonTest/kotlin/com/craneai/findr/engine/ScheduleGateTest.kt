package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleGateTest {
  /** Replays requests (ms) through the gate; returns when an enqueue actually happens (now or as the trailing one). */
  private fun replay(requests: List<Long>, gapMs: Long = 10_000): List<Long> {
    val gate = ScheduleGate(gapMs)
    val enqueues = mutableListOf<Long>()
    val pending = sortedSetOf<Long>()
    for (t in requests.sorted()) {
      while (pending.isNotEmpty() && pending.first() <= t) { val at = pending.pollFirst()!!; gate.trailingFired(at); enqueues += at }
      when (val d = gate.request(t)) {
        ScheduleGate.Decision.Now -> enqueues += t
        is ScheduleGate.Decision.Later -> pending += d.atMs
        ScheduleGate.Decision.Coalesced -> {}
      }
    }
    while (pending.isNotEmpty()) { val at = pending.pollFirst()!!; gate.trailingFired(at); enqueues += at }
    return enqueues
  }

  @Test fun theOnboardingBurstEnqueuesAtMostTwice() {
    // startIndexing at onboarding, a pack-manager refresh, the first crawl's ChangeWorker runs (13 in the first minute on the
    // TECNO, 2 of them finding new files) and the power-connected broadcast: all inside 40 s.
    val requests = listOf(0L, 150, 2_000, 3_100, 5_000, 9_000, 38_000, 38_500, 40_000)
    val enqueues = replay(requests)
    assertTrue("enqueues $enqueues", enqueues.size <= 4)
    assertEquals(listOf(0L, 10_000L, 38_000L, 48_000L), enqueues)
  }

  @Test fun aRequestInsideTheWindowIsNeverLost() {
    val enqueues = replay(listOf(0L, 9_999))
    assertEquals(listOf(0L, 10_000L), enqueues)   // the trailing enqueue carries the second request
  }

  @Test fun requestsFarApartEachEnqueue() = assertEquals(listOf(0L, 20_000L, 60_000L), replay(listOf(0L, 20_000, 60_000)))
}
