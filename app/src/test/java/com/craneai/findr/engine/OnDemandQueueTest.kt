package com.craneai.findr.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blur until scored: the on-demand privacy queue scores what is on screen one picture at a time, newest first, drops what left the
 * screen, waits while the user scrolls, and shares or skips work found another way (internal/research/blur-until-scored.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDemandQueueTest {
  @Test fun oneScoreAtATimeAndTheNewestAskFirst() = runTest {
    val order = mutableListOf<Int>()
    val q = OnDemandQueue<Int, Int>(backgroundScope) { k -> order += k; delay(1_000); k * 10 }
    val asks = (1..5).map { k -> async { q.get(k) } }
    advanceUntilIdle()
    assertEquals(listOf(10, 20, 30, 40, 50), asks.awaitAll())
    assertEquals("never two scores at once", 1, q.mostAtOnce)
    assertEquals("the picture shown last goes first", listOf(5, 4, 3, 2, 1), order)
    assertEquals(5, q.started)
  }

  @Test fun aPictureThatLeftTheScreenIsNotScored() = runTest {
    val started = mutableListOf<Int>()
    val q = OnDemandQueue<Int, Int>(backgroundScope) { k -> started += k; delay(1_000); k }
    val left = async { q.get(1) }
    val shown = async { q.get(2) }
    runCurrent()   // 2 (the newest) is being scored; 1 waits
    assertEquals(listOf(2), started)
    assertEquals(1, q.waiting)
    left.cancel()  // its thumbnail scrolled away
    runCurrent()
    assertEquals("a dropped ask leaves the queue at once", 0, q.waiting)
    assertEquals(2, shown.await())
    advanceUntilIdle()
    assertEquals(listOf(2), started)
    assertTrue(!q.busy)
  }

  @Test fun theQueueOnlyHoldsWhatIsOnScreen() = runTest {
    val q = OnDemandQueue<Int, Int>(backgroundScope) { k -> delay(1_000); k }
    val asks = (1..40).map { k -> async { q.get(k) } }
    runCurrent()
    assertEquals(39, q.waiting)
    asks.drop(10).forEach { it.cancel() }   // a fling: 30 of them scrolled past
    runCurrent()
    assertEquals("only the 10 asks still on screen wait", 10, q.waiting)
    advanceUntilIdle()
    assertTrue("at most the 10 still shown, plus the one already running, were scored: ${q.started}", q.started <= 11)
    assertEquals(1, q.mostAtOnce)
  }

  @Test fun nothingStartsWhileTheUserScrolls() = runTest {
    var scrolling = true
    val started = mutableListOf<Int>()
    val q = OnDemandQueue<Int, Int>(backgroundScope, hold = { while (scrolling) delay(100) }) { k -> started += k; k }
    val a = async { q.get(1) }
    advanceTimeBy(5_000)
    assertTrue(started.isEmpty())
    scrolling = false
    assertEquals(1, a.await())
    assertEquals(listOf(1), started)
  }

  @Test fun twoThumbnailsOfOnePictureShareOneScore() = runTest {
    var runs = 0
    val q = OnDemandQueue<Int, Int>(backgroundScope) { k -> runs++; delay(500); k }
    val tile = async { q.get(7) }
    val row = async { q.get(7) }
    assertEquals(7, tile.await()); assertEquals(7, row.await())
    assertEquals(1, runs)
  }

  @Test fun aScoreFromTheBackgroundPassEndsTheWait() = runTest {
    val started = mutableListOf<Int>()
    val q = OnDemandQueue<Int, Int>(backgroundScope) { k -> started += k; delay(1_000); k }
    val busy = async { q.get(1) }
    runCurrent()
    val waiting = async { q.get(2) }
    runCurrent()
    q.offer(2, 99)   // the pass got there first
    assertEquals(99, waiting.await())
    assertEquals(1, busy.await())
    advanceUntilIdle()
    assertEquals("an offered score is not computed again", listOf(1), started)
  }
}
