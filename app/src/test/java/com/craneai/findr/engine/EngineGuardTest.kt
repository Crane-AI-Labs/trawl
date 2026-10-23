package com.craneai.findr.engine

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Adreno close crash's guard, driven by a fake engine (the real one is native and needs a phone). */
class EngineGuardTest {
  /** A native engine stand-in: records where it was created and closed, and whether a close landed on top of an inference. */
  private class FakeEngine(val thread: Thread = Thread.currentThread()) : AutoCloseable {
    val closes = AtomicInteger()
    val infer = AtomicInteger()
    @Volatile var closedOnThread: Thread? = null
    @Volatile var closedDuringInference = false
    @Volatile var closeThrows = false

    fun inference() {
      infer.incrementAndGet()
      Thread.sleep(2)
      infer.decrementAndGet()
    }

    override fun close() {
      closes.incrementAndGet()
      closedOnThread = Thread.currentThread()
      if (infer.get() != 0) closedDuringInference = true
      if (closeThrows) throw IllegalStateException("driver said no")
    }
  }

  private val executor = Executors.newFixedThreadPool(1) { Thread(it, "test-engine") }
  private val dispatcher = executor.asCoroutineDispatcher()
  private val logs = ArrayList<String>()

  @After fun tearDown() { executor.shutdownNow() }

  private fun guard(keepGpu: Boolean) = EngineGuard<FakeEngine>(dispatcher, keepGpu) { synchronized(logs) { logs += it } }

  // --- idempotent close

  @Test fun closeOnceReachesTheEngineOnlyOnce() {
    val e = FakeEngine()
    val h = EngineHandle(e, gpu = true)
    assertTrue(h.closeOnce())
    assertFalse(h.closeOnce())
    assertFalse(h.closeOnce())
    assertEquals(1, e.closes.get())
    assertTrue(h.isClosed)
  }

  @Test fun aCloseThatThrowsIsNotRetried() {
    val e = FakeEngine().apply { closeThrows = true }
    val h = EngineHandle(e, gpu = false)
    try { h.closeOnce(); fail() } catch (_: IllegalStateException) {}
    assertFalse("the second call must not reach the driver again", h.closeOnce())
    assertEquals(1, e.closes.get())
  }

  @Test fun discardingTwiceAndDisposingAnInstalledEngineClosesItOnce() = runBlocking {
    val g = guard(keepGpu = false)
    val e = FakeEngine()
    val h = EngineHandle(e, gpu = false)
    g.serial {
      g.install(h)
      g.discard("a"); g.discard("b")   // second one finds the slot empty
      g.dispose(h)                      // the self-test's failure path holding the same handle
    }
    assertEquals(1, e.closes.get())
    assertNull(g.serial { g.current })
  }

  @Test fun aThrowingCloseIsContainedAndLogged() = runBlocking {
    val g = guard(keepGpu = false)
    val e = FakeEngine().apply { closeThrows = true }
    g.serial { g.install(EngineHandle(e, gpu = false)); assertTrue(g.unload("test")) }
    assertTrue(logs.any { it.contains("close failed") && it.contains("IllegalStateException") })
    assertEquals(1, e.closes.get())
  }

  // --- serialisation

  @Test fun everyCallRunsOnTheOneEngineThreadAndNeverTwoAtOnce() = runBlocking {
    val g = guard(keepGpu = false)
    val inside = AtomicInteger()
    val maxInside = AtomicInteger()
    val threads = java.util.Collections.synchronizedSet(HashSet<String>())
    (1..60).map {
      async {
        g.serial {
          val now = inside.incrementAndGet()
          maxInside.updateAndGet { m -> maxOf(m, now) }
          threads += Thread.currentThread().name.substringBefore(" @coroutine")   // the debug agent appends the coroutine id
          Thread.sleep(1)
          inside.decrementAndGet()
        }
      }
    }.awaitAll()
    assertEquals("a block never overlaps another", 1, maxInside.get())
    assertEquals(setOf("test-engine"), threads.toSet())
  }

  @Test fun anEngineIsCreatedUsedAndClosedOnTheSameThread() = runBlocking {
    val g = guard(keepGpu = false)
    val e = g.serial { FakeEngine().also { g.install(EngineHandle(it, gpu = true)) } }
    g.serial { g.current!!.engine.inference() }
    g.serial { assertTrue(g.unload("idle")) }
    assertEquals("test-engine", e.thread.name)
    assertSame("close runs on the creating thread", e.thread, e.closedOnThread)
  }

  @Test fun aCloseNeverLandsOnAnInferenceOrAnotherClose() = runBlocking {
    val g = guard(keepGpu = false)
    val engines = ArrayList<FakeEngine>()
    // Many callers at once: inferences, releases that close, and re-installs, as search, the indexer and a trim do.
    (1..40).map { i ->
      async {
        g.serial {
          if (g.current == null) { val e = FakeEngine(); engines += e; g.install(EngineHandle(e, gpu = i % 2 == 0)) }
          if (i % 5 == 0) g.unload("release") else g.current?.engine?.inference()
        }
      }
    }.awaitAll()
    g.serial { g.unload("end") }
    assertTrue(engines.isNotEmpty())
    for (e in engines) {
      assertFalse("close during inference", e.closedDuringInference)
      assertTrue("never closed twice", e.closes.get() <= 1)
    }
  }

  @Test fun lifecycleCallsOutsideSerialAreRefused() {
    val g = guard(keepGpu = false)
    for (call in listOf<() -> Unit>({ g.install(EngineHandle(FakeEngine(), false)) }, { g.unload("x") }, { g.discard("x") }, { g.dispose(EngineHandle(FakeEngine(), false)) })) {
      try { call(); fail("expected a refusal") } catch (_: IllegalStateException) {}
    }
  }

  // --- the Adreno rule

  @Test fun onAdrenoAnIdleUnloadKeepsTheGpuEngine() = runBlocking {
    val g = guard(keepGpu = true)
    val gpu = FakeEngine()
    g.serial { g.install(EngineHandle(gpu, gpu = true)) }
    val unloaded = g.serial { g.unload("polite") }
    assertFalse(unloaded)
    assertEquals("the GPU engine is not closed", 0, gpu.closes.get())
    assertSame(gpu, g.serial { g.current!!.engine })
    assertTrue(g.serial { g.pinsGpu })
    assertTrue(logs.any { it.contains("stays loaded") })
  }

  @Test fun onAdrenoACpuEngineIsStillClosedOnUnload() = runBlocking {
    val g = guard(keepGpu = true)
    val cpu = FakeEngine()
    g.serial { g.install(EngineHandle(cpu, gpu = false)) }
    assertTrue(g.serial { g.unload("idle") })
    assertEquals(1, cpu.closes.get())
    assertNull(g.serial { g.current })
    assertFalse(g.serial { g.pinsGpu })
  }

  @Test fun offAdrenoAGpuEngineIsClosedAsBefore() = runBlocking {
    val g = guard(keepGpu = false)
    val gpu = FakeEngine()
    g.serial { g.install(EngineHandle(gpu, gpu = true)) }
    assertTrue(g.serial { g.unload("idle") })
    assertEquals(1, gpu.closes.get())
    assertFalse(g.serial { g.pinsGpu })
  }

  @Test fun onAdrenoAStaleGpuEngineIsSetAsideUnclosedSoTheNextOneStartsClean() = runBlocking {
    val g = guard(keepGpu = true)
    val old = FakeEngine()
    val fresh = FakeEngine()
    g.serial { g.install(EngineHandle(old, gpu = true)) }
    g.serial { g.discard("model replaced") }
    assertNull(g.serial { g.current })
    assertEquals("never closed", 0, old.closes.get())
    assertEquals(1, g.parkedCount)
    g.serial { g.install(EngineHandle(fresh, gpu = true)) }
    assertSame(fresh, g.serial { g.current!!.engine })
    // Replacing again parks that one too, still without a close.
    g.serial { g.install(EngineHandle(FakeEngine(), gpu = true)) }
    assertEquals(0, fresh.closes.get())
    assertEquals(2, g.parkedCount)
  }

  @Test fun anEngineTheSelfTestRejectsIsParkedOnAdrenoAndClosedElsewhere() = runBlocking {
    val a = guard(keepGpu = true)
    val b = guard(keepGpu = false)
    val ea = FakeEngine(); val eb = FakeEngine()
    a.serial { a.dispose(EngineHandle(ea, gpu = true)) }
    b.serial { b.dispose(EngineHandle(eb, gpu = true)) }
    assertEquals(0, ea.closes.get())
    assertEquals(1, eb.closes.get())
  }

  @Test fun theProductionDispatcherIsOneNamedDaemonThread() = runBlocking {
    val d = EngineGuard.newEngineDispatcher()
    val g = EngineGuard<FakeEngine>(d, keepGpuEngines = false)
    val names = (1..5).map { async { g.serial { Thread.currentThread().name.substringBefore(" @coroutine") + "/" + Thread.currentThread().isDaemon } } }.awaitAll().toSet()
    assertEquals(setOf("trawl-engine/true"), names)
  }

  // --- which phones

  @Test fun adrenoIsRecognisedFromTheRendererTheSocMakerOrTheHardwareName() {
    assertTrue(GpuVendor.isAdreno("Adreno (TM) 750", null, null))
    assertTrue(GpuVendor.isAdreno(null, "QTI", "qcom"))            // Galaxy S24 Ultra
    assertTrue(GpuVendor.isAdreno(null, " qti ", null))
    assertTrue(GpuVendor.isAdreno(null, "Qualcomm", null))
    assertTrue(GpuVendor.isAdreno(null, null, "qcom"))             // API 30, before SOC_MANUFACTURER
    assertFalse(GpuVendor.isAdreno("Mali-G52 MC2", "Mediatek", "mt6769z"))   // Galaxy A06
    assertFalse(GpuVendor.isAdreno(null, "Mediatek", "mt6789"))     // Infinix
    assertFalse(GpuVendor.isAdreno(null, "Samsung", "exynos2200"))
    assertFalse(GpuVendor.isAdreno(null, "Unisoc", "ums9230"))
    assertFalse(GpuVendor.isAdreno(null, null, null))
    assertFalse(GpuVendor.isAdreno("", "", ""))
  }
}
