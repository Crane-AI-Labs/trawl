package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

private class Model(val id: Int) { var closed = false }
private fun <R> ModelHold<Model>.leaseNow(tight: () -> Boolean, build: () -> Model, block: (() -> Model) -> R): R = runBlocking { lease(tight, build) { block(it) } }

/** The lifetime rules of the speech pack's recogniser ([ModelHold]): kept across files, released on idle, trim, pause, pack removal, never kept when tight. */
class ModelHoldTest {


  /** A fake clock and timer: [advance] moves time and fires what is due, like the real timer thread would. */
  private class Fixture(val idleMs: Long = 45_000) {
    var t = 0L
    var built = 0
    val closedModels = ArrayList<Model>()
    private class Task(val at: Long, val action: () -> Unit) { var cancelled = false }
    private val tasks = ArrayList<Task>()
    val hold = ModelHold<Model>(idleMs, { t }, { d, a -> Task(t + d, a).also { tasks += it }.let { k -> { k.cancelled = true } } }) { it.closed = true; closedModels += it }
    fun advance(ms: Long) {
      t += ms
      tasks.filter { !it.cancelled && it.at <= t }.also { tasks.removeAll(it) }.forEach { it.action() }
    }
    fun file(tight: Boolean = false, reads: Int = 3): Model {
      var used: Model? = null
      hold.leaseNow({ tight }, { Model(++built) }) { get -> repeat(reads) { used = get() } }
      return used!!
    }
  }

  @Test fun theModelIsLoadedOnceForConsecutiveFiles() {
    val f = Fixture()
    val first = f.file()
    f.advance(2_000)
    val second = f.file()
    f.advance(2_000)
    val third = f.file()
    assertEquals(1, f.built)
    assertSame(first, second); assertSame(first, third)
    assertTrue(f.hold.loaded); assertFalse(first.closed)
  }

  @Test fun aFileThatNeedsNoSecondReadNeverLoadsIt() {
    val f = Fixture()
    f.hold.leaseNow({ false }, { Model(++f.built) }) { _ -> }
    assertEquals(0, f.built); assertFalse(f.hold.loaded)
  }

  @Test fun itLeavesAfterTheIdleTimeAndLoadsAgainForTheNextFile() {
    val f = Fixture()
    val m = f.file()
    f.advance(44_000); assertTrue(f.hold.loaded)
    f.advance(1_000); assertFalse(f.hold.loaded); assertTrue(m.closed)
    f.file(); assertEquals(2, f.built)
  }

  @Test fun useRestartsTheIdleClock() {
    val f = Fixture()
    f.file()
    f.advance(40_000); f.file()          // used again at 40 s: the first timer would have fired at 45 s
    f.advance(10_000); assertTrue("50 s after the start, 10 s after the last use", f.hold.loaded)
    f.advance(35_000); assertFalse(f.hold.loaded)
    assertEquals(1, f.built)
  }

  @Test fun aPhoneThatIsTightNeverKeepsIt() {
    val f = Fixture()
    val a = f.file(tight = true)
    assertTrue(a.closed); assertFalse(f.hold.loaded)
    f.file(tight = true)
    assertEquals(2, f.built)
  }

  @Test fun aPhoneThatGetsTightDuringTheFileLetsGoAtTheEndOfIt() {
    val f = Fixture()
    var tight = false
    f.hold.leaseNow({ tight }, { Model(++f.built) }) { get -> get(); tight = true; get() }
    assertFalse(f.hold.loaded); assertEquals(1, f.closedModels.size)
  }

  @Test fun trimPauseAndPackRemovalReleaseAtOnce() {
    // All three are release(): the engine calls it from onTrimMemory pressure, pauseIndexing / run end, and pack removal.
    for (reason in listOf("trim", "pause", "pack removed")) {
      val f = Fixture()
      val m = f.file()
      assertTrue(reason, f.hold.loaded)
      f.hold.release()
      assertFalse(reason, f.hold.loaded); assertTrue(reason, m.closed)
      f.advance(60_000)                   // the cancelled idle timer does nothing
      assertEquals(reason, 1, f.closedModels.size)
    }
  }

  @Test fun aReleaseDuringAReadWaitsForTheReadToEnd() {
    val f = Fixture()
    var m: Model? = null
    f.hold.leaseNow({ false }, { Model(++f.built) }) { get ->
      m = get()
      f.hold.release()                    // the system asks for memory while the recogniser is reading
      assertFalse("never freed under a read", m!!.closed)
      get()
    }
    assertTrue(m!!.closed); assertFalse(f.hold.loaded)
    f.file(); assertEquals(2, f.built)
  }

  @Test fun idleExpiryNeverClosesAModelInUse() {
    val f = Fixture()
    f.file()
    f.t += 100_000
    f.hold.leaseNow({ false }, { Model(++f.built) }) { get -> get(); f.hold.expireIfIdle(); assertTrue(f.hold.loaded) }
    assertEquals(1, f.built)
  }

  @Test fun aFailingCloseDoesNotTakeTheRunDown() {
    val hold = ModelHold<Model>(1_000, { 0 }, { _, _ -> {} }) { throw IllegalStateException("native") }
    hold.leaseNow({ false }, { Model(1) }) { it() }
    hold.release()
    assertFalse(hold.loaded)
  }

  @Test fun theHeldTimeIsAShortWhile() {
    assertTrue(ModelHold.IDLE_MS in 30_000..60_000)
  }
}
