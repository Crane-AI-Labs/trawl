package com.craneai.findr.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ReportStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private var now = 1_000_000L
  private fun store(dir: File = tmp.newFolder(), maxReports: Int = 10, maxBytes: Int = 100_000) =
    ReportStore(dir, maxReports, maxBytes, clock = { now })

  private fun crash(time: Long, pid: Int = 1, body: String = "Event: crash\n") = StoredEvent(time, EventKind.CRASH, pid, body)
  private fun exit(time: Long, reason: Int, pid: Int = 1, importance: Int = 100, description: String? = null, trace: String? = null) =
    ExitRecord(time, pid, reason, 0, importance, description, trace)

  // ---- de-duplication ----------------------------------------------------------------------------------------------------

  @Test fun theSameExitTimestampIsStoredOnce() {
    val s = store()
    val r = exit(5_000, ExitRecord.REASON_ANR, pid = 9)
    assertEquals(1, s.ingest(listOf(r)).size)
    assertEquals(0, s.ingest(listOf(r)).size)
    assertEquals(1, s.events().size)
  }

  @Test fun anExitIsNotStoredAgainEvenAfterItsReportWasDeleted() {
    val s = store()
    val r = exit(5_000, ExitRecord.REASON_ANR)
    s.ingest(listOf(r))
    s.wipe()
    assertEquals("Delete everything must not make the old exit look new", 0, s.ingest(listOf(r)).size)
    assertTrue(s.events().isEmpty())
  }

  @Test fun aCrashTheHandlerAlreadyWroteIsNotStoredTwice() {
    val s = store()
    s.add(crash(time = 10_000, pid = 33, body = "Event: crash\nwith the full stack\n"))
    val added = s.ingest(listOf(exit(10_400, ExitRecord.REASON_CRASH, pid = 33)))
    assertTrue(added.isEmpty())
    assertEquals(1, s.events().size)
    assertTrue("the one with the stack is the one kept", s.events()[0].body.contains("full stack"))
  }

  @Test fun aCrashWithoutAHandlerReportIsStoredFromTheExitRecord() {
    val s = store()
    val added = s.ingest(listOf(exit(10_000, ExitRecord.REASON_CRASH, pid = 33)))
    assertEquals(1, added.size)
    assertTrue(added[0].body, added[0].body.contains("No stack trace was saved"))
  }

  @Test fun aCrashOfAnotherProcessOrMuchLaterIsNotMistakenForTheSame() {
    val s = store()
    s.add(crash(time = 10_000, pid = 33))
    assertEquals(1, s.ingest(listOf(exit(10_400, ExitRecord.REASON_CRASH, pid = 34))).size)
    assertEquals(1, s.ingest(listOf(exit(200_000, ExitRecord.REASON_CRASH, pid = 33))).size)
  }

  @Test fun onlyRealProblemsBecomeEvents() {
    val s = store()
    val records = listOf(
      exit(1, ExitRecord.REASON_ANR),
      exit(2, ExitRecord.REASON_CRASH_NATIVE),
      exit(3, ExitRecord.REASON_INITIALIZATION_FAILURE),
      exit(4, ExitRecord.REASON_LOW_MEMORY, importance = 100),    // it was in use: a finding
      exit(5, ExitRecord.REASON_LOW_MEMORY, importance = 400),    // cached, nobody was looking: normal
      exit(6, 10 /* REASON_USER_REQUESTED */),
      exit(7, 11 /* REASON_USER_STOPPED */),
      exit(8, 0 /* REASON_UNKNOWN */),
    )
    val kinds = s.ingest(records).map { it.kind }.toSet()
    assertEquals(setOf(EventKind.ANR, EventKind.NATIVE_CRASH, EventKind.STARTUP_FAILURE, EventKind.LOW_MEMORY), kinds)
  }

  @Test fun anAnrKeepsTheMainThreadFramesFromTheDump() {
    val s = store()
    val dump = "\"main\" prio=5 tid=1 Blocked\n  at com.craneai.findr.Foo.bar(Foo.kt:1)\n  at android.os.Handler.dispatchMessage(Handler.java:1)\n\n\"other\" prio=5 tid=2 Native\n  at no.Here.x(X.java:1)\n"
    val e = s.ingest(listOf(exit(1, ExitRecord.REASON_ANR, description = "user request after error: Input dispatching timed out (Window{abc u0 com.x/.Y} is not responding)", trace = dump))).single()
    assertTrue(e.body, e.body.contains("main thread: Blocked"))
    assertTrue(e.body, e.body.contains("com.craneai.findr.Foo.bar"))
    assertFalse(e.body, e.body.contains("no.Here"))
  }

  // ---- retention -----------------------------------------------------------------------------------------------------------

  @Test fun atMostTenReportsAreKeptAndTheOldestGoFirst() {
    val s = store()
    for (i in 1..14) s.add(crash(time = 1_000L * i))
    val times = s.events().map { it.time }
    assertEquals(10, times.size)
    assertEquals(14_000L, times.first())
    assertEquals(5_000L, times.last())
  }

  @Test fun theTotalSizeStaysUnderTheLimit() {
    val s = store(maxBytes = 5_000)
    for (i in 1..9) s.add(crash(time = 1_000L * i, body = "x".repeat(1_500)))
    val total = tmp.root.walkTopDown().filter { it.isFile && it.name.startsWith("e-") }.sumOf { it.length() }
    assertTrue("$total", total <= 5_000)
    assertEquals("the newest survives", 9_000L, s.events().first().time)
  }

  @Test fun theDefaultsAreTenReportsAndAboutOneHundredKilobytes() {
    assertEquals(10, ReportStore.MAX_REPORTS)
    assertEquals(100_000, ReportStore.MAX_BYTES)
    val s = store()
    for (i in 1..30) s.add(crash(time = i.toLong(), body = "y".repeat(40_000)))
    val total = tmp.root.walkTopDown().filter { it.isFile && it.name.startsWith("e-") }.sumOf { it.length() }
    assertTrue("$total", total <= ReportStore.MAX_BYTES)
  }

  @Test fun aCrashOutlivesAnyNumberOfLessImportantEvents() {
    val s = store(maxReports = 3)
    s.add(crash(time = 1_000))
    for (i in 2..8) s.add(StoredEvent(1_000L * i, EventKind.LOW_MEMORY, 1, "Event: memory\n"))
    val kinds = s.events().map { it.kind }
    assertEquals(3, kinds.size)
    assertTrue("the crash is kept over newer memory kills", EventKind.CRASH in kinds)
  }

  @Test fun oneEventIsCutToItsLimit() {
    val s = ReportStore(tmp.newFolder(), maxEventChars = 500)
    s.add(crash(time = 1, body = "z".repeat(5_000)))
    assertTrue(s.events().single().body.length < 600)
  }

  // ---- the card ----------------------------------------------------------------------------------------------------------------

  @Test fun theCardAsksAboutCrashesAndAnrsOnceAndNotAboutMemoryKills() {
    val s = store()
    s.ingest(listOf(exit(1, ExitRecord.REASON_LOW_MEMORY, importance = 100)))
    assertTrue(s.pending().isEmpty())
    s.ingest(listOf(exit(2, ExitRecord.REASON_ANR)))
    assertEquals(1, s.pending().size)
    s.acknowledge()
    assertTrue("Not now means not again for this one", s.pending().isEmpty())
    s.ingest(listOf(exit(3, ExitRecord.REASON_CRASH, pid = 5)))
    assertEquals("a newer problem asks again", 1, s.pending().size)
  }

  // ---- handled errors ----------------------------------------------------------------------------------------------------------

  @Test fun handledErrorsAreCountedWithTheClassOnly() {
    val s = store()
    s.recordHandled(HandledKind.OCR, IOException("/storage/emulated/0/DCIM/private.jpg"))
    s.recordHandled(HandledKind.OCR, IllegalStateException("also private"))
    s.recordHandled(HandledKind.MODEL_INSTALL, IOException("x"))
    val counts = s.handledCounts()
    assertEquals(2, counts[HandledKind.OCR]!!.count)
    assertEquals("java.lang.IllegalStateException", counts[HandledKind.OCR]!!.lastClass)
    assertEquals(1, counts[HandledKind.MODEL_INSTALL]!!.count)
    val onDisk = File(tmp.root, "x").let { tmp.root.walkTopDown().filter { f -> f.name == "handled.json" }.single().readText() }
    assertFalse(onDisk, onDisk.contains("private"))
  }

  @Test fun installFailuresReachTheDiskAtOnceAndThrottledOnesLater() {
    val dir = tmp.newFolder()
    val s = ReportStore(dir, clock = { now })
    s.recordHandled(HandledKind.MODEL_INSTALL, IOException())
    assertTrue("immediate kind", File(dir, "handled.json").isFile)
    File(dir, "handled.json").delete()
    s.recordHandled(HandledKind.FILE_FAILED, IOException())
    assertFalse("a per-file error waits for the throttle", File(dir, "handled.json").isFile)
    s.flushHandled()
    assertTrue(File(dir, "handled.json").isFile)
    now += 20_000
    File(dir, "handled.json").delete()
    s.recordHandled(HandledKind.FILE_FAILED, IOException())
    assertTrue("after the throttle window it writes", File(dir, "handled.json").isFile)
  }

  @Test fun countsSurviveARestart() {
    val dir = tmp.newFolder()
    ReportStore(dir, clock = { now }).apply { recordHandled(HandledKind.INDEX_CORRUPT, IOException()) }
    assertEquals(1, ReportStore(dir, clock = { now }).handledCounts()[HandledKind.INDEX_CORRUPT]!!.count)
  }

  // ---- delete everything -------------------------------------------------------------------------------------------------------

  @Test fun wipeRemovesReportsCountsAndTheAnswerButKeepsOnlyExitTimestamps() {
    val dir = tmp.newFolder()
    val s = ReportStore(dir, clock = { now })
    s.ingest(listOf(exit(2, ExitRecord.REASON_ANR)))
    s.recordHandled(HandledKind.PACK_INSTALL, IOException())
    s.acknowledge()
    s.wipe()
    assertTrue(s.events().isEmpty())
    assertTrue(s.handledCounts().isEmpty())
    assertEquals(listOf("seen"), dir.list()!!.sorted())
    assertEquals("2", File(dir, "seen").readText().trim())
  }
}
