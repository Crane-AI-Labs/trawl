package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the worker works the status out again: state changes at once, progress at most once a gap, and nothing held back for good. */
class PublishThrottleTest {
  private var clock = 1_000_000L
  private fun throttle(gap: Long = 1000) = PublishThrottle(gap) { clock }

  @Test fun theFirstProgressGoesOutAtOnceAndTheRestWaitForTheGap() {
    val t = throttle()
    assertTrue(t.progress(), "nothing was published yet")
    repeat(500) { clock += 1; assertFalse(t.progress(), "a tick inside the gap is held back") }
    clock += 600
    assertTrue(t.progress(), "the gap is over")
  }

  @Test fun tenThousandTicksInTenSecondsPublishAboutTenTimes() {
    val t = throttle()
    var published = 0
    repeat(10_000) { clock += 1; if (t.progress()) published++ }
    assertTrue(published in 9..11, "published $published times in 10 s")
  }

  @Test fun aStateChangeIsImmediateAndRestartsTheGap() {
    val t = throttle()
    assertTrue(t.progress())
    clock += 10
    assertFalse(t.progress())
    assertTrue(t.hasPending())
    t.immediate()                       // the caller publishes now
    assertFalse(t.hasPending(), "what was held back is covered by the publish that just went out")
    clock += 10
    assertFalse(t.progress(), "the gap counts from the state change")
  }

  @Test fun aHeldBackTickIsFlushedOnceTheGapIsOverAndOnlyOnce() {
    val t = throttle()
    assertTrue(t.progress())
    clock += 100
    assertFalse(t.progress())
    assertFalse(t.flushDue(), "too early")
    assertEquals(900L, t.msUntilDue())
    clock += 900
    assertEquals(0L, t.msUntilDue())
    assertTrue(t.flushDue(), "the final publish of a burst")
    assertFalse(t.flushDue(), "handed out once")
    assertNull(t.msUntilDue())
  }

  @Test fun nothingPendingMeansNothingToFlush() {
    val t = throttle()
    clock += 5_000
    assertFalse(t.flushDue())
    assertNull(t.msUntilDue())
  }
}
