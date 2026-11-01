package com.craneai.findr.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RunLogTest {
  @get:Rule val tmp = TemporaryFolder()

  private var now = 1_700_000_000_000L
  private fun log(file: File = File(tmp.root, RunLog.FILE), max: Int = RunLog.MAX_RECORDS) = RunLog(file, max, clock = { now })
  private fun exit(time: Long, reason: Int, pid: Int, importance: Int = 125, description: String? = null) =
    ExitRecord(time, pid, reason, 0, importance, description, null)

  // ---- a run's life ---------------------------------------------------------------------------------------------------------

  @Test fun aRunIsRecordedWithItsReason() {
    val l = log()
    val id = l.begin("bulk", pid = 7, charging = true, batteryPercent = 80)
    now += 90_000
    l.end(id, RunEnd.FINISHED)
    val r = l.records().single()
    assertEquals(RunEnd.FINISHED, r.end)
    assertEquals(90_000L, r.durationMs)
    assertEquals("bulk", r.kind)
    assertTrue(r.charging)
    assertNull(r.systemCode)
  }

  @Test fun theSystemStopCodeIsKept() {
    val l = log()
    val id = l.begin("now", 7, false, 50)
    l.end(id, StopReasons.runEnd(StopReasons.QUOTA), StopReasons.QUOTA)
    val r = l.records().single()
    assertEquals(RunEnd.STOPPED_BY_SYSTEM, r.end)
    assertEquals(StopReasons.QUOTA, r.systemCode)
  }

  @Test fun theForegroundServiceTimeoutIsItsOwnReason() {
    assertEquals(RunEnd.FGS_TIMEOUT, StopReasons.runEnd(StopReasons.FOREGROUND_SERVICE_TIMEOUT))
    assertEquals(RunEnd.CANCELLED, StopReasons.runEnd(StopReasons.CANCELLED_BY_APP))
    assertEquals(RunEnd.STOPPED_BY_SYSTEM, StopReasons.runEnd(StopReasons.APP_STANDBY))
    assertEquals("FOREGROUND_SERVICE_TIMEOUT", StopReasons.name(-128))
    assertEquals("CODE_99", StopReasons.name(99))
  }

  @Test fun endingARunTwiceKeepsTheFirstReason() {
    val l = log()
    val id = l.begin("index", 7, false, 50)
    l.end(id, RunEnd.POLITE_HANDOFF)
    l.end(id, RunEnd.FINISHED)
    assertEquals(RunEnd.POLITE_HANDOFF, l.records().single().end)
  }

  @Test fun countsAndTimesAreKeptAcrossAHeartbeat() {
    val l = log()
    val id = l.begin("bulk", 7, true, 90)
    l.update(id) { it.copy(files = 12, fgsMs = mapOf("dataSync" to 60_000L, "mediaProcessing" to 120_000L), politeMs = 5_000, coolDowns = 1, coolMs = 20_000) }
    now += 60_000
    l.beat(id)
    val r = log().records().single()   // read back from the disk by a fresh log
    assertEquals(12, r.files)
    assertEquals(120_000L, r.fgsMs["mediaProcessing"])
    assertEquals(1, r.coolDowns)
    assertEquals(now, r.lastBeat)
    assertTrue(r.open)
  }

  // ---- a death --------------------------------------------------------------------------------------------------------------

  @Test fun aRunLeftOpenByAnotherProcessIsMarkedAsADeathAtItsLastBeat() {
    val first = log()
    val id = first.begin("bulk", pid = 100, charging = true, batteryPercent = 70)
    now += 120_000
    first.beat(id)
    val diedAt = now
    now += 3_600_000
    val next = log()                      // a new process reads the file
    next.begin("resume", pid = 200, charging = true, batteryPercent = 71)
    val dead = next.records().last()
    assertEquals(RunEnd.PROCESS_DIED, dead.end)
    assertEquals(diedAt, dead.endedAt)
    assertEquals(120_000L, dead.durationMs)
  }

  @Test fun aDeathIsMatchedToTheSystemsExitRecordOfThatProcess() {
    val first = log()
    val id = first.begin("bulk", 100, true, 70)
    now += 60_000
    first.beat(id)
    val lastBeat = now
    log().begin("resume", 200, true, 70)
    val runs = log().records()
    val withExit = BackgroundReport.withExits(runs, listOf(
      exit(lastBeat + 30_000, 10, pid = 100, description = "stop com.example due to a request"),
      exit(lastBeat + 1_000, 3, pid = 999),   // another process: not this run's
    ))
    assertEquals("user requested", withExit.single { it.pid == 100 }.exitReason)
    assertNull(withExit.single { it.pid == 200 }.exitReason)
  }

  // ---- the ring and the forgetting ------------------------------------------------------------------------------------------

  @Test fun onlyTheLastRunsAreKept() {
    val l = log(max = 5)
    repeat(9) { i -> l.end(l.begin("run$i", 7, false, 50), RunEnd.FINISHED) }
    val kinds = l.records().map { it.kind }
    assertEquals(listOf("run8", "run7", "run6", "run5", "run4"), kinds)
  }

  @Test fun wipeForgetsEverythingEvenOnDisk() {
    val f = File(tmp.root, RunLog.FILE)
    val l = log(f)
    l.end(l.begin("bulk", 7, true, 50), RunEnd.FINISHED)
    assertTrue(f.exists())
    l.wipe()
    assertTrue(l.records().isEmpty())
    assertFalse(f.exists())
    assertTrue(log(f).records().isEmpty())
  }

  @Test fun aBrokenFileReadsAsEmptyAndNeverThrows() {
    val f = File(tmp.root, RunLog.FILE)
    f.writeText("{ not json")
    val l = log(f)
    assertTrue(l.records().isEmpty())
    assertTrue(l.begin("bulk", 7, true, 50) > 0)
    assertEquals(1, l.records().size)
  }

  @Test fun anUnwritableFolderNeverThrows() {
    val blocker = tmp.newFile("blocker")
    val l = RunLog(File(blocker, "sub/runs.json"), clock = { now })
    l.begin("bulk", 7, true, 50)    // cannot create the folder under a file: swallowed
    l.beat(0)
    l.end(0, RunEnd.FINISHED)
    assertTrue(l.records().size <= 1)
  }

  // ---- what the report says -------------------------------------------------------------------------------------------------

  @Test fun theReportBlockCountsReasonsAndNamesNoFiles() {
    val l = log()
    val a = l.begin("bulk", 7, true, 80)
    l.update(a) { it.copy(files = 300, fgsMs = mapOf("dataSync" to 3_600_000L, "mediaProcessing" to 7_200_000L)) }
    now += 3_600_000
    l.end(a, RunEnd.FGS_TIMEOUT, StopReasons.FOREGROUND_SERVICE_TIMEOUT)
    val b = l.begin("resume", 7, true, 75)
    now += 600_000
    l.end(b, RunEnd.STOPPED_BY_SYSTEM, StopReasons.QUOTA)
    val c = l.begin("now", 7, false, 60)
    l.end(c, RunEnd.POLITE_HANDOFF)
    val text = BackgroundReport.lines(l.records(), listOf(exit(now - 5_000, 10, pid = 3, importance = 400)), now).joinToString("\n")
    assertTrue(text, "foreground-service time limit 1" in text)
    assertTrue(text, "stopped by the system 1" in text)
    assertTrue(text, "polite hand-off 1" in text)
    assertTrue(text, "FOREGROUND_SERVICE_TIMEOUT 1" in text && "QUOTA 1" in text)
    assertTrue(text, "dataSync 1 h 00 min" in text && "mediaProcessing 2 h 00 min" in text)
    assertTrue(text, "files understood 300" in text)
    assertTrue(text, "user requested 1" in text)
  }

  @Test fun anEmptyLogSaysSo() {
    assertEquals(listOf("No background runs recorded yet."), BackgroundReport.lines(emptyList(), emptyList(), now))
  }

  @Test fun theBlockReachesTheReportAndFitsTheLimit() {
    val device = DeviceFacts("1", 1, "release", "p", "m", "x", "16", 36, "arm64", 4000, false, 1000, 2000)
    val lines = List(400) { "line $it " + "x".repeat(200) }
    val built = ReportBuilder.build(
      ReportInput(now, device, ModelFacts("m", emptyList()), emptyList(), emptyList(), emptyMap(), listOf("log"), null, background = lines),
    )
    assertTrue("Background runs" in built.text)
    assertTrue("more lines not shown" in built.text)
    assertTrue("the log is not crowded out", "Recent log from this launch" in built.text)
    assertTrue(built.text.length <= ReportBuilder.MAX_CHARS)
    val without = ReportBuilder.build(ReportInput(now, device, ModelFacts("m", emptyList()), emptyList(), emptyList(), emptyMap(), emptyList(), null))
    assertFalse("Background runs" in without.text)
  }
}
