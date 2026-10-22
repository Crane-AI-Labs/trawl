package trawl.desktop

import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import trawl.desktop.ui.DemoPlan
import trawl.desktop.ui.DemoState
import trawl.desktop.ui.ReduceMotion

/** The first-screen demo's pace and loop, with no clock and no window: a fake wait that records the delays and cancels after N of them. */
class DemoPlanTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val queries = listOf("birthday party", "wifi password", "receipt from the phone shop", "funny dog video", "voice note about the rent")

  /** Runs [state] until [stopAfter] waits have happened, returning each delay asked for. */
  private fun play(state: DemoState, stopAfter: Int, onWait: (Int, Long) -> Unit = { _, _ -> }): List<Long> {
    val waits = mutableListOf<Long>()
    runBlocking {
      try {
        state.run { ms ->
          if (waits.size >= stopAfter) throw CancellationException("pause")
          waits += ms
          onWait(waits.size, ms)
        }
      } catch (_: CancellationException) {
      }
    }
    return waits
  }

  @Test fun everySceneHasItsQueryInTheStrings() {
    assertEquals(5, DemoPlan.scenes.size)
    assertEquals(queries, DemoPlan.scenes.map { t(it.queryKey) })
    for (s in DemoPlan.scenes) assertTrue(s.foundCount in 1..3, "$s")
  }

  @Test fun lettersAreTypedAtAHumanPaceTheSameEveryTime() {
    for (scene in 0 until 5) for (i in 0 until 40) {
      val d = DemoPlan.typeDelay(scene, i)
      assertTrue(d in DemoPlan.TYPE_MIN_MS until DemoPlan.TYPE_MIN_MS + DemoPlan.TYPE_SPREAD_MS, "scene $scene letter $i took $d ms")
      assertEquals(d, DemoPlan.typeDelay(scene, i))
    }
    assertTrue((0 until 40).map { DemoPlan.typeDelay(0, it) }.toSet().size > 5, "a little uneven, not a metronome")
  }

  @Test fun theLoopVisitsEveryScenesAndWraps() {
    assertEquals(1, DemoPlan.next(0))
    assertEquals(0, DemoPlan.next(4))
    var s = 0
    val seen = (0 until 5).map { s.also { s = DemoPlan.next(s) } }
    assertEquals(listOf(0, 1, 2, 3, 4), seen)
    assertEquals(0, s)
  }

  @Test fun transitionAndLoopLengthsAddUp() {
    val len = queries.map { it.length }
    val t1 = DemoPlan.transitionMs(1, len[1])
    assertEquals(DemoPlan.GAP_MS + (0 until len[1]).sumOf { DemoPlan.typeDelay(1, it) } + DemoPlan.AFTER_TYPE_MS, t1)
    val loop = DemoPlan.loopMs(len)
    assertEquals(5 * DemoPlan.HOLD_MS + (0 until 5).sumOf { DemoPlan.transitionMs(it, len[it]) }, loop)
    assertTrue(loop in 20_000..60_000, "a loop of $loop ms is about half a minute")
  }

  @Test fun theStateStartsOnTheFirstSceneCompleteSoTheStillIsTheFirstPaint() {
    val s = DemoState(queries)
    assertEquals(0, s.scene)
    assertEquals("birthday party", s.query)
    assertTrue(s.complete); assertTrue(s.shown)
  }

  @Test fun theHoldComesFirstThenTheGapThenEveryLetter() {
    val s = DemoState(queries)
    val waits = play(s, stopAfter = 2 + queries[1].length + 1)
    assertEquals(DemoPlan.HOLD_MS, waits[0])
    assertEquals(DemoPlan.GAP_MS, waits[1])
    assertEquals((0 until queries[1].length).map { DemoPlan.typeDelay(1, it) }, waits.subList(2, 2 + queries[1].length))
    assertEquals(DemoPlan.AFTER_TYPE_MS, waits[2 + queries[1].length])
    assertEquals(1, s.scene)
    assertTrue(s.complete); assertTrue(s.shown)
    assertEquals(queries[1], s.query)
  }

  @Test fun typingShowsOneMoreLetterEachStepAndHidesResultsUntilDone() {
    val s = DemoState(queries)
    val seenTyped = mutableListOf<Int>()
    val seenShown = mutableListOf<Boolean>()
    play(s, stopAfter = 2 + queries[1].length + 2) { _, _ -> seenTyped += s.typed; seenShown += s.shown }
    // after the hold the scene is the next one, empty, results gone
    assertEquals(0, seenTyped[1], "the gap starts on an empty box")
    assertFalse(seenShown[1])
    assertTrue(seenTyped.drop(1).zipWithNext().all { (a, b) -> b == a || b == a + 1 }, "never skips a letter: $seenTyped")
  }

  @Test fun cancellingMidTypingAndRunningAgainResumesWhereItStopped() {
    val s = DemoState(queries)
    play(s, stopAfter = 2 + 5)            // hold, gap, then 5 letters
    assertEquals(DemoState.Phase.TYPING, s.phase)
    assertEquals(1, s.scene)
    assertEquals(5, s.typed)
    assertEquals("wifi ", s.query)
    assertFalse(s.shown)
    val resumed = play(s, stopAfter = 3)
    assertEquals(DemoPlan.typeDelay(1, 5), resumed[0], "carries on with letter 6, not from the start")
    assertEquals(8, s.typed)
  }

  @Test fun cancellingDuringTheHoldResumesTheHoldNotTheNextSearch() {
    val s = DemoState(queries)
    assertEquals(emptyList(), play(s, stopAfter = 0))
    assertEquals(0, s.scene, "cancelled while holding: still the same scene, still complete")
    assertTrue(s.complete)
    val again = play(s, stopAfter = 1)
    assertEquals(listOf(DemoPlan.HOLD_MS), again, "the hold starts over, then the next search begins")
    assertEquals(1, s.scene)
    assertEquals(0, s.typed)
    assertFalse(s.shown)
  }

  @Test fun theLoopWrapsFromTheLastSceneToTheFirst() {
    val s = DemoState(queries)
    s.show(4)
    val waits = play(s, stopAfter = 2 + queries[0].length + 1)
    assertEquals(0, s.scene)
    assertEquals(queries[0], s.query)
    assertEquals(DemoPlan.HOLD_MS, waits.first())
    assertTrue(s.complete)
  }

  @Test fun finishGivesTheCalmEndStateThatPauseAndReducedMotionShow() {
    val s = DemoState(queries)
    play(s, stopAfter = 2 + 3)
    assertFalse(s.complete)
    s.finish()
    assertTrue(s.complete); assertTrue(s.shown)
    assertEquals(queries[1], s.query)
  }

  @Test fun showJumpsToAnyFrameForTheScreenshotDriver() {
    val s = DemoState(queries)
    s.show(2, lettersTyped = 7, resultsShown = false)
    assertEquals("receipt", s.query)
    assertFalse(s.shown); assertEquals(DemoState.Phase.TYPING, s.phase)
    s.show(3)
    assertEquals(queries[3], s.query)
    assertTrue(s.shown)
    s.show(0, lettersTyped = 999, resultsShown = false)
    assertEquals(queries[0], s.query, "letters are clamped to the query")
    s.show(0, lettersTyped = -4)
    assertEquals("", s.query)
  }

  // --- reduce motion ---

  @Test fun macReportsOneWhenReduceMotionIsOn() {
    assertTrue(ReduceMotion.fromMac("1\n"))
    assertFalse(ReduceMotion.fromMac("0\n"))
    assertFalse(ReduceMotion.fromMac(null), "cannot ask: motion is fine")
    assertFalse(ReduceMotion.fromMac(""))
  }

  @Test fun gnomeReportsFalseWhenAnimationsAreOff() {
    assertTrue(ReduceMotion.fromGnome("false\n"))
    assertTrue(ReduceMotion.fromGnome("False"))
    assertFalse(ReduceMotion.fromGnome("true\n"))
    assertFalse(ReduceMotion.fromGnome(null))
    assertFalse(ReduceMotion.fromGnome("No such schema"))
  }

  @Test fun windowsReportsZeroWhenAnimationsAreOff() {
    val off = "\nHKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics\n    MinAnimate    REG_SZ    0\n\n"
    val on = off.replace("    0", "    1")
    assertTrue(ReduceMotion.fromWindows(off))
    assertFalse(ReduceMotion.fromWindows(on))
    assertFalse(ReduceMotion.fromWindows(null))
    assertFalse(ReduceMotion.fromWindows("ERROR: The system was unable to find the specified registry key or value."))
  }

  @Test fun theOverrideBeatsTheSystemEitherWay() {
    assertEquals(true, ReduceMotion.fromOverride("1"))
    assertEquals(true, ReduceMotion.fromOverride(" true "))
    assertEquals(false, ReduceMotion.fromOverride("0"))
    assertEquals(false, ReduceMotion.fromOverride("false"))
    assertNull(ReduceMotion.fromOverride(null))
    assertNull(ReduceMotion.fromOverride("maybe"))
    assertTrue(ReduceMotion.detect(Os.LINUX, mapOf("TRAWL_REDUCE_MOTION" to "1")))
    assertFalse(ReduceMotion.detect(Os.MAC, mapOf("TRAWL_REDUCE_MOTION" to "0")))
  }
}
