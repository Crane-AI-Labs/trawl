package com.craneai.findr.engine

import com.craneai.findr.core.IndexOpenProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index-open state machine (StoreOpener): opening, ready, and failed -> retrying -> ready, on virtual time and without a database.
 * A fake store stands in for IndexStore; failures are exceptions whose text names the reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreOpenerTest {
  private class Fake(val id: Int) { var closed = false }
  private class Boom(val problem: IndexOpenProblem) : RuntimeException("open failed")

  private class Rig(scope: TestScope, failWith: (Int) -> IndexOpenProblem?) {
    val opens = ArrayList<Long>()          // virtual time of every open attempt
    val failed = ArrayList<Pair<IndexOpenProblem, Int>>()
    val ready = ArrayList<Boolean>()       // the `recovered` flag of every onReady
    val prepared = ArrayList<Fake>()
    val made = ArrayList<Fake>()
    var prepareFails = 0
    var readyThrows = false
    val opener: StoreOpener<Fake>

    init {
      val io = StandardTestDispatcher(scope.testScheduler)
      opener = StoreOpener(
        scope = scope.backgroundScope,
        ioDispatcher = io,
        open = { _ ->
          opens += scope.currentTime
          val n = opens.size
          failWith(n)?.let { p ->
            throw Boom(p)
          }
          Fake(n).also { made += it }
        },
        classify = { (it as? Boom)?.problem ?: IndexOpenProblem.OTHER },
        prepare = { s -> if (prepareFails > 0) { prepareFails--; throw Boom(IndexOpenProblem.IO) }; prepared += s },
        onReady = { _, recovered -> ready += recovered; if (readyThrows) error("late work failed") },
        onFailed = { p, n -> failed += p to n },
        close = { it.closed = true },
      )
    }
  }

  @Test fun startsOpeningThenReadyWithoutBlockingTheCaller() = runTest {
    val r = Rig(this) { null }
    assertTrue(r.opener.state.value is StoreOpener.State.Opening)
    assertNull("nothing is ready before start", r.opener.current)
    r.opener.start()
    assertTrue("start() returns before the open has run", r.opens.isEmpty())
    runCurrent()
    val store = r.opener.current
    assertEquals(1, r.opens.size)
    assertSame(r.made[0], store)
    assertEquals("rules etc. ran before the store was published", listOf(r.made[0]), r.prepared)
    assertEquals("a first-time open is not a recovery", listOf(false), r.ready)
    assertSame(store, r.opener.await())
  }

  @Test fun awaitWaitsWhileOpening() = runTest {
    val r = Rig(this) { null }
    var got: Fake? = null
    var done = false
    backgroundScope.launch { got = r.opener.await(); done = true }
    runCurrent()
    assertFalse("nothing started yet: still waiting", done)
    r.opener.start()
    runCurrent()
    assertTrue(done)
    assertSame(r.made[0], got)
  }

  @Test fun aFailingOpenNeverThrowsAndShowsFailedWithTheReason() = runTest {
    val r = Rig(this) { IndexOpenProblem.STORAGE_FULL }
    r.opener.start()
    runCurrent()
    val s = r.opener.state.value
    assertEquals(StoreOpener.State.Failed(IndexOpenProblem.STORAGE_FULL, 1), s)
    assertNull(r.opener.current)
    assertNull("a waiter is told at once, not left hanging", r.opener.await())
  }

  @Test fun failedThenRetryingThenReady() = runTest {
    // fail twice (disk full, then busy), then open
    val r = Rig(this) { n -> when (n) { 1 -> IndexOpenProblem.STORAGE_FULL; 2 -> IndexOpenProblem.BUSY; else -> null } }
    r.opener.start()
    runCurrent()
    assertEquals(StoreOpener.State.Failed(IndexOpenProblem.STORAGE_FULL, 1), r.opener.state.value)
    advanceTimeBy(StoreOpener.FIRST_BACKOFF_MS - 1); runCurrent()
    assertEquals("still waiting", 1, r.opens.size)
    advanceTimeBy(1); runCurrent()
    assertEquals(2, r.opens.size)
    assertEquals(StoreOpener.State.Failed(IndexOpenProblem.BUSY, 2), r.opener.state.value)
    advanceTimeBy(2 * StoreOpener.FIRST_BACKOFF_MS); runCurrent()
    assertEquals(3, r.opens.size)
    val ready = r.opener.state.value as StoreOpener.State.Ready<*>
    assertSame(r.made[0], ready.store)
    assertEquals("the work missed while it was closed is redone", listOf(true), r.ready)
    assertEquals(listOf(IndexOpenProblem.STORAGE_FULL to 1, IndexOpenProblem.BUSY to 2), r.failed)
    advanceTimeBy(10 * StoreOpener.MAX_BACKOFF_MS); runCurrent()
    assertEquals("no more opens once ready", 3, r.opens.size)
  }

  @Test fun theWaitDoublesAndIsCapped() = runTest {
    val r = Rig(this) { n -> if (n <= 9) IndexOpenProblem.IO else null }
    r.opener.start()
    advanceTimeBy(30 * 60_000L); runCurrent()
    assertEquals(10, r.opens.size)
    val gaps = r.opens.zipWithNext { a, b -> b - a }
    assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L), gaps)
    assertTrue(r.opener.state.value is StoreOpener.State.Ready<*>)
  }

  @Test fun retryNowSkipsTheWaitOnlyWhileFailed() = runTest {
    val r = Rig(this) { n -> if (n == 1) IndexOpenProblem.BUSY else null }
    r.opener.retryNow()          // not started, not failed: dropped
    r.opener.start()
    runCurrent()
    assertEquals(1, r.opens.size)
    assertTrue(r.opener.state.value is StoreOpener.State.Failed)
    r.opener.retryNow()
    runCurrent()
    assertEquals("opened again at once, not after 5 s", 2, r.opens.size)
    assertEquals(0L, currentTime)
    assertTrue(r.opener.state.value is StoreOpener.State.Ready<*>)
    r.opener.retryNow()          // ready: dropped
    runCurrent()
    assertEquals(2, r.opens.size)
  }

  @Test fun aKickDuringAnOpenInFlightIsNotRememberedForTheNextWait() = runTest {
    val r = Rig(this) { n -> if (n <= 2) IndexOpenProblem.BUSY else null }
    r.opener.start()
    runCurrent()
    r.opener.retryNow()          // second open starts now and fails
    runCurrent()
    assertEquals(2, r.opens.size)
    r.opener.retryNow()          // this one is for the third
    runCurrent()
    assertEquals(3, r.opens.size)
    assertTrue(r.opener.state.value is StoreOpener.State.Ready<*>)
  }

  @Test fun aFailureInsideTheOpenShowsAtOnceThenItRecovers() = runTest {
    // IndexStore.open retries by itself and reports each transient failure; the state must flip then, not when the open finally ends
    val seen = ArrayList<StoreOpener.State>()
    val io = StandardTestDispatcher(testScheduler)
    val opener = StoreOpener(
      scope = backgroundScope, ioDispatcher = io,
      open = { transient -> transient(Boom(IndexOpenProblem.BUSY)); transient(Boom(IndexOpenProblem.BUSY)); Fake(1) },
      classify = { (it as? Boom)?.problem ?: IndexOpenProblem.OTHER },
      onFailed = { p, n -> seen += StoreOpener.State.Failed(p, n) },
    )
    opener.start()
    runCurrent()
    assertEquals(listOf<StoreOpener.State>(StoreOpener.State.Failed(IndexOpenProblem.BUSY, 0), StoreOpener.State.Failed(IndexOpenProblem.BUSY, 0)), seen)
    assertTrue(opener.state.value is StoreOpener.State.Ready<*>)
  }

  @Test fun aPrepareErrorCountsAsAFailedOpenAndClosesTheStore() = runTest {
    val r = Rig(this) { null }
    r.prepareFails = 1
    r.opener.start()
    runCurrent()
    assertEquals(StoreOpener.State.Failed(IndexOpenProblem.IO, 1), r.opener.state.value)
    assertTrue("the half-ready store is closed", r.made[0].closed)
    advanceTimeBy(StoreOpener.FIRST_BACKOFF_MS); runCurrent()
    assertSame(r.made[1], r.opener.current)
    assertFalse(r.made[1].closed)
  }

  @Test fun lateWorkThatFailsDoesNotUndoAnOpenIndex() = runTest {
    val r = Rig(this) { null }
    r.readyThrows = true
    r.opener.start()
    runCurrent()
    assertSame(r.made[0], r.opener.current)
    assertEquals(1, r.opens.size)
  }

  @Test fun detachHandsBackTheStoreAndStartOpensAgain() = runTest {
    val r = Rig(this) { null }
    r.opener.start()
    runCurrent()
    val first = r.opener.current
    val handed = r.opener.detach()
    assertSame(first, handed)
    assertNull(r.opener.current)
    assertTrue(r.opener.state.value is StoreOpener.State.Opening)
    r.opener.start()
    runCurrent()
    assertSame(r.made[1], r.opener.current)
  }

  @Test fun detachWorksWhenTheStoreNeverOpened() = runTest {
    val r = Rig(this) { n -> if (n == 1) IndexOpenProblem.IO else null }
    r.opener.start()
    runCurrent()
    assertNull(r.opener.detach())
    advanceTimeBy(10 * StoreOpener.MAX_BACKOFF_MS); runCurrent()
    assertEquals("a detached loop does not keep trying", 1, r.opens.size)
    r.opener.start()
    runCurrent()
    assertSame(r.made[0], r.opener.current)
  }

  @Test fun startTwiceRunsOneLoop() = runTest {
    val r = Rig(this) { null }
    r.opener.start(); r.opener.start()
    runCurrent()
    assertEquals(1, r.opens.size)
  }
}
