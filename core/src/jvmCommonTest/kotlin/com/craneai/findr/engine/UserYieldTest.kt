package com.craneai.findr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserYieldTest {
  /** A fake clock and a "user" who is busy until [busyUntilMs] on that clock; pauseForUser waits on the fake clock. */
  private class Scene(var busyUntilMs: Long, val gpuQuietMs: Long = 0) {
    var nowMs = 0L
    fun busy(gpu: Boolean) = nowMs < busyUntilMs + (if (gpu) gpuQuietMs else 0)
    val yieldToUser = UserYield(
      capMs = 60_000,
      pauseForUser = { maxMs, gpu -> val until = nowMs + maxMs; while (busy(gpu) && nowMs < until) nowMs += 250 },
      userBusy = ::busy,
      nowNs = { nowMs * 1_000_000 },
    )
  }

  @Test fun theUserStopsBeforeTheBudget_theFileCarriesOn() = runBlocking {
    val s = Scene(busyUntilMs = 10_000)
    s.yieldToUser.letUserFirst()   // no exception
    assertEquals(10_000, s.yieldToUser.waitedMs)
  }

  @Test fun theUserKeepsScrollingPastTheBudget_theFileIsDeferred() = runBlocking {
    val s = Scene(busyUntilMs = 300_000)
    try { s.yieldToUser.letUserFirst(); fail("expected a deferral") } catch (e: UserYield.DeferredForUser) { }
    assertEquals(60_000, s.yieldToUser.waitedMs)
  }

  @Test fun aDeferredFileRunsAsSoonAsInteractionStops() = runBlocking {
    val s = Scene(busyUntilMs = 300_000)
    try { s.yieldToUser.letUserFirst() } catch (e: UserYield.DeferredForUser) { }
    // The worker waits between files while the user is busy (no budget there), then tries the file again:
    s.nowMs = s.busyUntilMs
    s.yieldToUser.reset()
    s.yieldToUser.letUserFirst()   // proceeds at once: a fresh budget and nobody scrolling
    assertEquals(0, s.yieldToUser.waitedMs)
  }

  @Test fun aGpuStepWaitsForTheLongerQuiet_aCpuStepDoesNot() = runBlocking {
    val cpu = Scene(busyUntilMs = 5_000, gpuQuietMs = 6_500)
    cpu.yieldToUser.letUserFirst(gpu = false)
    assertEquals(5_000, cpu.yieldToUser.waitedMs)
    val gpu = Scene(busyUntilMs = 5_000, gpuQuietMs = 6_500)
    gpu.yieldToUser.letUserFirst(gpu = true)
    assertEquals(11_500, gpu.yieldToUser.waitedMs)   // the encode starts only once the GPU quiet has passed too
  }

  @Test fun theBudgetIsPerAttemptNotPerStep() = runBlocking {
    val s = Scene(busyUntilMs = 40_000)
    s.yieldToUser.letUserFirst()          // waits 40 s, user stops
    s.busyUntilMs = s.nowMs + 30_000      // the user scrolls again before the next step
    try { s.yieldToUser.letUserFirst(); fail("expected a deferral") } catch (e: UserYield.DeferredForUser) { }
    assertTrue(s.yieldToUser.waitedMs >= 60_000)
  }
}
