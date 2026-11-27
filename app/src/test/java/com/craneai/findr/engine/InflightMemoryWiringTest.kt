package com.craneai.findr.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InflightMemoryWiringTest {
  private val root = File("src/main/java/com/craneai/findr/engine")

  @Test fun readingStartsWithAFreshMemoryPollAfterTheDurableMark() {
    val engine = File(root, "FindrEngine.kt").readText()
    val mark = engine.substringAfter("fun markInflight(").substringBefore("\n  }")
    assertTrue(mark.indexOf("store.markInflight(") >= 0)
    assertTrue(mark.indexOf("pollMemory(force = true)") > mark.indexOf("store.markInflight("))
    assertFalse("an earlier trim is not evidence for this attempt", "memoryPressureAt" in mark)
    val worker = File(root, "IndexWorker.kt").readText()
    assertTrue("engine.markInflight(file.id, from, windowed)" in worker)
  }

  @Test fun realTrimsAndLowMemoryPollsPersistEvidenceForTheCurrentAttempt() {
    val engine = File(root, "FindrEngine.kt").readText()
    val trim = engine.substringAfter("override fun noteMemoryTrim(").substringBefore("val pressureNow")
    assertTrue(Regex("if \\(PolitePolicy\\.pressure\\(level\\)\\) \\{[^}]*storeOrNull\\(\\)\\?\\.noteInflightMemoryPressure\\(\\)").containsMatchIn(trim))
    val poll = engine.substringAfter("private fun pollMemory(").substringBefore("fun memoryPressureSince(")
    assertTrue(Regex("if \\(short\\) \\{[^}]*storeOrNull\\(\\)\\?\\.noteInflightMemoryPressure\\(\\)").containsMatchIn(poll))
    assertTrue("MemoryBudget.pressure(availMb, lineMb, mi.lowMemory)" in poll)
  }

  @Test fun aTrimBeforeTheIndexOpensDoesNotThrow() {
    // onTrimMemory reaches noteMemoryTrim (and its forced poll) on the main thread, possibly before the index is open: the throwing
    // [FindrEngine.store] getter there crashed the 1.1.0 release build in 12 of 20 itel cold starts (itel-coldstart.md, 24 Nov).
    val engine = File(root, "FindrEngine.kt").readText()
    val trim = engine.substringAfter("override fun noteMemoryTrim(").substringBefore("\n  }")
    val poll = engine.substringAfter("private fun pollMemory(").substringBefore("fun memoryPressureSince(")
    assertFalse(Regex("(?<![A-Za-z])store\\.").containsMatchIn(trim))
    assertFalse(Regex("(?<![A-Za-z])store\\.").containsMatchIn(poll))
  }

  @Test fun theWorkerRetriesOnlyOnUpdateBeforeRecoveringThisRunsDeaths() {
    val worker = File(root, "IndexWorker.kt").readText()
    assertTrue(worker.indexOf("retryFailuresAfterUpdate(engine)") < worker.indexOf("engine.store.recoverInflight()"))
    assertTrue("engine.store.retryFailedAfterUpdate(build)" in worker)
    assertFalse("the log uses the recorded reason", "file marked failed (too large for this phone's memory)" in worker)
  }
}
