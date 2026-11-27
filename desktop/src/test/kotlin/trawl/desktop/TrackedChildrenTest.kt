package trawl.desktop

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests and microbenchmark for [TrackedChildren] (replacing OS-wide `ProcessHandle.current().descendants()` scans). */
class TrackedChildrenTest {
  private class FakeHandle(
    override val pid: Long,
    override var alive: Boolean = true,
    var cpu: Long? = 0L,
  ) : TrackedChildren.Handle {
    var cpuReads = 0
    private var exitAction: (() -> Unit)? = null

    override fun cpuNs(): Long? {
      cpuReads++
      return cpu
    }

    override fun onExit(action: () -> Unit) {
      exitAction = action
    }

    fun finish(finalCpu: Long? = cpu) {
      alive = false
      cpu = finalCpu
      exitAction?.invoke()
    }
  }

  @Test fun emptyRegistryTakesFastPathWithZeroClockOrSyscallWork() {
    var clockCalls = 0
    val tracked = TrackedChildren(now = { clockCalls++; 0L }, everyMs = 250)
    repeat(1_000) { assertEquals(0L, tracked.totalNs()) }
    assertEquals(0, clockCalls, "no tracked children: fast path must not touch the clock")
    assertEquals(0, tracked.trackedCount)
  }

  @Test fun sumsLiveChildrenMonotonicallyAndHonorsSampleInterval() {
    var t = 0L
    val tracked = TrackedChildren(now = { t }, everyMs = 250)
    val a = FakeHandle(10, cpu = 1_000L)
    val b = FakeHandle(11, cpu = 500L)
    tracked.track(a)
    tracked.track(b)
    assertEquals(2, tracked.trackedCount)
    assertEquals(1_500L, tracked.totalNs())

    // Within the 250 ms sample window: returns cached value without re-reading handles.
    val readsBefore = a.cpuReads + b.cpuReads
    a.cpu = 9_000L
    t += 100_000_000L
    assertEquals(1_500L, tracked.totalNs())
    assertEquals(readsBefore, a.cpuReads + b.cpuReads)

    // After 250 ms: samples directly and updates total.
    t += 200_000_000L
    b.cpu = 2_000L
    assertEquals(11_000L, tracked.totalNs())

    // A null or lower reading from an exiting handle never runs the total backwards.
    t += 300_000_000L
    a.cpu = null
    b.cpu = 1_000L
    assertEquals(11_000L, tracked.totalNs())
  }

  @Test fun onExitCapturesFinalCpuAndPrunesHandle() {
    var t = 0L
    val tracked = TrackedChildren(now = { t }, everyMs = 250)
    val h = FakeHandle(42, cpu = 1_000L)
    tracked.track(h)
    assertEquals(1, tracked.trackedCount)
    assertEquals(1_000L, tracked.totalNs())

    h.finish(finalCpu = 4_500L)
    assertEquals(0, tracked.trackedCount, "exited handle must be pruned")
    assertEquals(4_500L, tracked.totalNs(), "final CPU time must be preserved after exit")

    // A subsequent helper adds on top of the finalized total.
    val next = FakeHandle(43, cpu = 200L)
    tracked.track(next)
    t += 300_000_000L
    next.cpu = 700L
    assertEquals(5_200L, tracked.totalNs())
    next.finish(finalCpu = null)   // OS reaped before onExit could read: keeps last seen 700
    assertEquals(0, tracked.trackedCount)
    assertEquals(5_200L, tracked.totalNs())
  }

  @Test fun exitedHandleWithoutOnExitCallbackIsPrunedOnNextSample() {
    var t = 0L
    val tracked = TrackedChildren(now = { t }, everyMs = 0)
    val h = FakeHandle(99, cpu = 2_000L)
    tracked.track(h)
    h.cpu = 5_000L
    assertEquals(5_000L, tracked.totalNs())

    // Simulate exit without invoking onExit callback.
    h.alive = false
    h.cpu = null
    t += 1_000_000L
    assertEquals(5_000L, tracked.totalNs())
    assertEquals(0, tracked.trackedCount)
  }

  @Test fun alreadyExitedHandleIsAccountedAndNotRetained() {
    val tracked = TrackedChildren(everyMs = 0)
    val done = FakeHandle(7, alive = false, cpu = 3_000L)
    tracked.track(done)
    assertEquals(0, tracked.trackedCount)
    assertEquals(3_000L, tracked.totalNs())
  }

  @Test fun realChildProcessIsTrackedAndPrunedOnExit() {
    if (Platform.os == Os.WINDOWS) return
    val tracked = TrackedChildren(everyMs = 0)
    val p = tracked.track(ProcessBuilder("/bin/sh", "-c", "i=0; while [ \$i -lt 500 ]; do i=\$((i+1)); done").start())
    val during = tracked.totalNs()
    assertTrue(p.waitFor(10, TimeUnit.SECONDS))
    val deadline = System.currentTimeMillis() + 5_000
    while (tracked.trackedCount > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
    assertEquals(0, tracked.trackedCount, "exited real child should be pruned via onExit")
    assertTrue(tracked.totalNs() >= during, "CPU total must be monotonic")
  }

  @Test fun duplicateTrackOfSameProcessDeduplicates() {
    if (Platform.os == Os.WINDOWS) return
    val tracked = TrackedChildren(everyMs = 0)
    val p = ProcessBuilder("sleep", "30").start()
    try {
      tracked.track(p)
      tracked.track(p)
      tracked.track(p.toHandle())
      assertEquals(1, tracked.trackedCount, "tracking the same Process multiple times must not double-track")
    } finally {
      p.destroyForcibly()
      p.waitFor(2, TimeUnit.SECONDS)
    }
  }

  @Test fun benchmarkTrackedSamplingVsOsWideDescendantsScan() {
    if (Platform.os == Os.WINDOWS) return
    val trackedActive = TrackedChildren(everyMs = 0)
    val trackedIdle = TrackedChildren(everyMs = 0)
    val procs = listOf(
      ProcessBuilder("sleep", "30").start(),
      ProcessBuilder("sleep", "30").start(),
    )
    try {
      procs.forEach { trackedActive.track(it) }
      fun legacyDescendantsNs(): Long {
        var sum = 0L
        ProcessHandle.current().descendants().use { st ->
          st.forEach { h -> h.info().totalCpuDuration().ifPresent { d -> sum += d.toNanos() } }
        }
        return sum
      }

      // Warm up JIT and OS caches.
      repeat(20) {
        legacyDescendantsNs()
        trackedActive.totalNs()
      }
      repeat(10_000) { trackedIdle.totalNs() }

      val scanIters = 100
      val tScan0 = System.nanoTime()
      var sink = 0L
      repeat(scanIters) { sink += legacyDescendantsNs() }
      val scanNsPerCall = (System.nanoTime() - tScan0).toDouble() / scanIters

      val activeIters = 200
      val tActive0 = System.nanoTime()
      repeat(activeIters) { sink += trackedActive.totalNs() }
      val activeNsPerCall = (System.nanoTime() - tActive0).toDouble() / activeIters

      val idleIters = 200_000
      val tIdle0 = System.nanoTime()
      repeat(idleIters) { sink += trackedIdle.totalNs() }
      val idleNsPerCall = (System.nanoTime() - tIdle0).toDouble() / idleIters

      val activeSpeedup = scanNsPerCall / activeNsPerCall.coerceAtLeast(1.0)
      val idleSpeedup = scanNsPerCall / idleNsPerCall.coerceAtLeast(0.1)
      println(
        "BENCH: Politeness.Cpu sampling: legacy descendants()=%.1f us/call vs tracked(2 live)=%.1f us/call (%.1fx faster) vs tracked(0 live)=%.1f ns/call (%.0fx faster) [sink=%d]"
          .format(scanNsPerCall / 1_000.0, activeNsPerCall / 1_000.0, activeSpeedup, idleNsPerCall, idleSpeedup, sink),
      )
      assertTrue(activeNsPerCall < scanNsPerCall, "tracked sampling (%.1f us) must beat OS-wide descendants() (%.1f us)".format(activeNsPerCall / 1_000.0, scanNsPerCall / 1_000.0))
      assertTrue(idleNsPerCall < 500.0, "empty fast path should be well under 500 ns/call, was %.1f ns".format(idleNsPerCall))
    } finally {
      procs.forEach { it.destroyForcibly(); it.waitFor(2, TimeUnit.SECONDS) }
    }
  }
}
