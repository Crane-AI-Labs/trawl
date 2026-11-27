package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules by which the reader gives way to the computer's other work, the keyboard and the battery, with made-up readings. */
class PolitenessTest {
  private var clock = 0L
  private var reading = Politeness.Reading(othersCores = 0.0, user = UserState.AWAY, onBattery = false)
  private var speed = LearningSpeed.BALANCED

  /** Balanced 2 threads: 0.7 cores while the person is active or the laptop is on its battery, 2.0 away on the charger. Fast has no cell of its own. */
  private val table = DutyTable(mapOf(
    LearningSpeed.GENTLE to DutyCell(threads = 2, activeCores = 0.3, awayBatteryCores = 0.3, awayAcCores = 1.0),
    LearningSpeed.BALANCED to DutyCell(threads = 2, activeCores = 0.7, awayBatteryCores = 0.7, awayAcCores = 2.0),
    LearningSpeed.FAST to DutyCell(threads = 4, activeCores = 0.0, awayBatteryCores = 0.0, awayAcCores = 0.0),
  ))

  private fun politeness(
    cores: Int = 8, enabled: Boolean = true, spare: Double = 1.0, min: Double = 0.25, maxRest: Long = 5_000, smoothing: Double = 1.0,
  ) = Politeness(cores, { reading }, { clock }, enabled, spare, min, maxRest, sampleMs = 1_000, smoothing = smoothing, table = table, speed = { speed })

  @Test fun offItNeverRestsAndNeverLooksAtTheSystem() {
    var looked = 0
    val p = Politeness(8, { looked++; reading }, { clock }, enabled = false, table = table, speed = { speed })
    assertEquals(0L, p.restMs(cpuNs = 9_000_000_000, wallMs = 1))
    assertEquals(0, looked)
  }

  @Test fun fastOnAnIdleComputerTakesAllButTheSpareCore() {
    speed = LearningSpeed.FAST
    val p = politeness()
    assertEquals(7.0, p.capCores(), 1e-9)
    assertEquals(0L, p.restMs(cpuNs = 3_000_000_000, wallMs = 500), "3 cores for 0.5 s is 6 cores on average: under the cap")
    // 4 s of processor time in 0.5 s of the clock is 8 cores: it rests until the average is 7
    assertEquals(((4_000.0 / 7.0) - 500).toLong(), p.restMs(cpuNs = 4_000_000_000, wallMs = 500))
  }

  @Test fun theOtherProgramsTakeTheirCoresFirst() {
    speed = LearningSpeed.FAST
    reading = reading.copy(othersCores = 5.0)
    assertEquals(2.0, politeness().capCores(), 1e-9, "8 cores, 5 used by others, 1 kept for the person")
  }

  @Test fun aFullyBusyComputerStillGetsTheIndexBuiltSlowly() {
    speed = LearningSpeed.FAST
    reading = reading.copy(othersCores = 8.0)
    val p = politeness()
    assertEquals(0.25, p.capCores(), 1e-9)
    assertEquals(1_500L, p.restMs(cpuNs = 500_000_000, wallMs = 500), "0.5 s of processor time at a quarter core takes 2 s in all, so 1.5 s of rest")
    assertEquals(5_000L, p.restMs(cpuNs = 60_000_000_000, wallMs = 100), "one rest is never longer than the maximum")
  }

  @Test fun everySpeedInEveryStateRestsAtMostTheMaximumSoTheIndexNeverStalls() {
    val states = listOf(
      Politeness.Reading(8.0, UserState.ACTIVE, true), Politeness.Reading(8.0, UserState.ACTIVE, false), Politeness.Reading(8.0, UserState.AWAY, true),
      Politeness.Reading(8.0, UserState.AWAY, false), Politeness.Reading(8.0, UserState.UNKNOWN, null), Politeness.Reading(null, UserState.UNKNOWN, null),
    )
    for (s in LearningSpeed.values()) for (r in states) {
      speed = s; reading = r; clock += 1_000
      val p = politeness(smoothing = 1.0)
      val rest = p.restMs(cpuNs = 3_600_000_000_000, wallMs = 1)   // an hour of processor time in a millisecond
      assertTrue(rest in 0..5_000, "$s $r rests $rest ms")
      assertTrue(p.capCores() >= 0.25, "$s $r cap ${p.capCores()}")
    }
  }

  @Test fun theDutyCellDecidesByUserAndPower() {
    val p = politeness()
    reading = reading.copy(user = UserState.ACTIVE, onBattery = false); clock += 1_000
    assertEquals(0.7, p.capCores(), 1e-9, "Balanced while the person is at the keyboard: 0.7 of a core")
    reading = reading.copy(user = UserState.AWAY, onBattery = false); clock += 1_000
    assertEquals(2.0, p.capCores(), 1e-9, "away on the charger: 2 cores")
    reading = reading.copy(user = UserState.AWAY, onBattery = true); clock += 1_000
    assertEquals(0.7, p.capCores(), 1e-9, "away on the battery: still 0.7")
    reading = reading.copy(user = UserState.UNKNOWN, onBattery = false); clock += 1_000
    assertEquals(0.7, p.capCores(), 1e-9, "a keyboard the system cannot see counts as active: the polite mistake")
    reading = reading.copy(user = UserState.AWAY, onBattery = null); clock += 1_000
    assertEquals(2.0, p.capCores(), 1e-9, "an unknown power source counts as the charger")
  }

  @Test fun theComputersSpareCoresStillCapWhenTheCellAllowsMore() {
    reading = reading.copy(othersCores = 5.5, user = UserState.AWAY, onBattery = false)
    assertEquals(1.5, politeness().capCores(), 1e-9, "the cell says 2 but only 1.5 cores are spare once the 1-core reserve is kept")
  }

  @Test fun aNewSpeedIsFeltAtOnceWithoutAnotherReading() {
    var looked = 0
    val p = Politeness(8, { looked++; reading }, { clock }, true, 1.0, 0.25, 5_000, 1_000, 1.0, table = table, speed = { speed })
    assertEquals(2.0, p.capCores(), 1e-9)
    speed = LearningSpeed.GENTLE
    assertEquals(1.0, p.capCores(), 1e-9, "Gentle away on the charger")
    speed = LearningSpeed.FAST
    assertEquals(7.0, p.capCores(), 1e-9)
    assertEquals(1, looked, "no new reading was needed")
  }

  @Test fun theSystemIsOnlyLookedAtOncePerSample() {
    var looked = 0
    val p = Politeness(8, { looked++; reading }, { clock }, true, 1.0, 0.25, 5_000, 1_000, 1.0, table = table, speed = { speed })
    repeat(50) { clock += 10; p.capCores() }
    assertEquals(1, looked, "50 questions inside one second cost one reading")
    clock += 1_000; p.capCores()
    assertEquals(2, looked)
  }

  @Test fun aBusyMomentIsSmoothedSoOneSpikeDoesNotStopTheReader() {
    speed = LearningSpeed.FAST
    val p = politeness(smoothing = 0.4)
    reading = reading.copy(othersCores = 0.0); assertEquals(7.0, p.capCores(), 1e-9)
    reading = reading.copy(othersCores = 8.0); clock += 1_000
    assertEquals(8 - 0.4 * 8 - 1.0, p.capCores(), 1e-9, "40% of the new reading, 60% of the old")
  }

  @Test fun aBrokenSensorLeavesTheLastAnswerStanding() {
    speed = LearningSpeed.FAST
    var fail = false
    val p = Politeness(8, { if (fail) error("no") else reading }, { clock }, true, 1.0, 0.25, 5_000, 1_000, 1.0, table = table, speed = { speed })
    reading = reading.copy(othersCores = 4.0)
    assertEquals(3.0, p.capCores(), 1e-9)
    fail = true; clock += 1_000
    assertEquals(3.0, p.capCores(), 1e-9)
  }

  @Test fun fairThermalLevelProactivelyEasesCpuAcrossAllSpeedsBeforeSeriousHold() {
    var thermal = 0
    val p = Politeness(8, { reading }, { clock }, true, 1.0, 0.25, 5_000, 1_000, 1.0, table = table, speed = { speed }, thermalLevel = { thermal })

    // Nominal (0): full Fast / Balanced / Gentle budgets
    speed = LearningSpeed.FAST
    assertEquals(7.0, p.capCores(), 1e-9)
    speed = LearningSpeed.BALANCED
    assertEquals(2.0, p.capCores(), 1e-9)

    // Fair / Warm (1): proactively caps Fast (<= 2.0 on 8-core), Balanced (<= 1.0), Gentle (<= 0.5)
    thermal = 1
    clock += 1_000; speed = LearningSpeed.FAST
    assertEquals(2.0, p.capCores(), 1e-9, "Fast must ease to 25% of cores at thermal Fair (1)")
    clock += 1_000; speed = LearningSpeed.BALANCED
    assertEquals(1.0, p.capCores(), 1e-9, "Balanced away-on-AC must ease to 1.0 core at thermal Fair (1)")
    clock += 1_000; speed = LearningSpeed.GENTLE
    assertEquals(0.5, p.capCores(), 1e-9, "Gentle must ease to 0.5 core at thermal Fair (1)")
  }

  // ---- helper programs' processor time -------------------------------------------------------------------------------------------

  @Test fun childCpuKeepsWhatAFinishedHelperUsed() {
    var t = 0L
    var live = mapOf(10L to 1_000L)
    val c = ChildCpu({ live }, { t }, everyMs = 250)
    assertEquals(1_000L, c.totalNs())
    t += 300_000_000; live = mapOf(10L to 3_000L, 11L to 500L)
    assertEquals(3_500L, c.totalNs())
    t += 300_000_000; live = mapOf(11L to 900L)   // helper 10 is gone: its last 3 000 stays
    assertEquals(3_900L, c.totalNs())
    t += 300_000_000; live = mapOf(11L to 1_400L, 12L to 100L)
    assertEquals(3_000L + 1_400L + 100L, c.totalNs())
  }

  @Test fun childCpuOnlyWalksTheProcessTableEveryQuarterSecond() {
    var t = 0L; var asked = 0
    val c = ChildCpu({ asked++; mapOf(1L to 5L) }, { t }, everyMs = 250)
    repeat(100) { t += 1_000_000; c.totalNs() }   // 100 ms
    assertEquals(1, asked)
  }

  @Test fun aNumberReusedByAnotherHelperDoesNotRunTheTotalBackwards() {
    var t = 0L; var live = mapOf(7L to 4_000L)
    val c = ChildCpu({ live }, { t }, everyMs = 0)
    assertEquals(4_000L, c.totalNs())
    t += 1_000_000; live = mapOf(7L to 50L)   // the id was handed to a new program that has used 50
    assertEquals(4_050L, c.totalNs())
  }

  // ---- is the person there -------------------------------------------------------------------------------------------------------

  @Test fun theKeyboardIdleTimeDecidesActiveAwayOrUnknown() {
    assertEquals(UserState.ACTIVE, UserActivity.classify(5.0, 120))
    assertEquals(UserState.ACTIVE, UserActivity.classify(119.9, 120))
    assertEquals(UserState.AWAY, UserActivity.classify(120.0, 120))
    assertEquals(UserState.AWAY, UserActivity.classify(86_400.0, 120))
    assertEquals(UserState.UNKNOWN, UserActivity.classify(null, 120))
    assertEquals(UserState.UNKNOWN, UserActivity.classify(-1.0, 120))
  }

  // ---- the speeds' duty cells ------------------------------------------------------------------------------------------------------

  @Test fun theThreadCountsFollowTheFormulasAndStayWithinTheCores() {
    val t = DutyTable.fromTuning(10)
    assertEquals(2, t.of(LearningSpeed.GENTLE).threads, "a quarter of 10 cores")
    assertEquals(2, t.of(LearningSpeed.BALANCED).threads)
    assertEquals(5, t.of(LearningSpeed.FAST).threads, "half the cores")
    val two = DutyTable.fromTuning(2)
    assertEquals(1, two.of(LearningSpeed.GENTLE).threads, "never fewer than one")
    assertEquals(2, two.of(LearningSpeed.FAST).threads, "never more than the cores")
    assertEquals(4, DutyTable.fromTuning(8).of(LearningSpeed.FAST).threads)
  }

  @Test fun everySpeedHasAThreadCountSoChangingTheSpeedAlwaysRebuildsTheEngineTheSameWay() {
    for (s in LearningSpeed.values()) assertTrue(WorkSpeed.threads(s, 8) in 1..8, "$s")
  }

  @Test fun onlyGentleWaitsForTheCharger() {
    assertTrue(WorkSpeed.waitsForCharger(LearningSpeed.GENTLE))
    assertFalse(WorkSpeed.waitsForCharger(LearningSpeed.BALANCED))
    assertFalse(WorkSpeed.waitsForCharger(LearningSpeed.FAST))
  }

  // ---- the system's own answers, parsed -------------------------------------------------------------------------------------------

  @Test fun aHungHelperProgramIsKilledAtTheTimeoutNotWaitedOn() {
    if (Platform.os == Os.WINDOWS) return
    assertEquals("hi", HostState.run("echo", "hi")?.trim())
    val t0 = System.nanoTime()
    assertNull(HostState.run("sleep", "30", timeoutS = 1), "a program that does not finish in time gives no answer")
    val took = (System.nanoTime() - t0) / 1e9
    assertTrue(took < 5, "the timeout held: $took s")
    assertNull(HostState.run("false"), "a failing program gives no answer")
  }

  @Test fun pmsetThermalReadsTheSpeedLimit() {
    assertEquals(0, HostState.parsePmsetTherm("Note: No thermal warning level has been recorded\nCPU_Scheduler_Limit = 100\nCPU_Available_CPUs = 10\nCPU_Speed_Limit = 100\n"))
    assertEquals(1, HostState.parsePmsetTherm("CPU_Speed_Limit = 85"))
    assertEquals(2, HostState.parsePmsetTherm("CPU_Speed_Limit = 60"))
    assertEquals(3, HostState.parsePmsetTherm("CPU_Speed_Limit = 30"))
    assertEquals(0, HostState.parsePmsetTherm("nothing useful here"))
    assertEquals(0, HostState.parsePmsetTherm(null))
  }

  @Test fun ioregIdleIsInNanoseconds() {
    assertEquals(194.293947875, HostState.parseIoregIdle("    | |   \"HIDIdleTime\" = 194293947875")!!, 1e-9)
    assertNull(HostState.parseIoregIdle("no such line"))
    assertNull(HostState.parseIoregIdle(null))
  }

  @Test fun pmsetPercentIsTheFirstPercentage() {
    assertEquals(63, HostState.parsePmsetPercent("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t63%; discharging; 4:12 remaining present: true"))
    assertNull(HostState.parsePmsetPercent("Now drawing from 'AC Power'"))
    assertNull(HostState.parsePmsetPercent(null))
  }

  @Test fun linuxCapacityAveragesTheBatteriesAndSkipsDevices() {
    val root = kotlin.io.path.createTempDirectory("ps").toFile()
    fun supply(name: String, type: String, capacity: Int? = null, scope: String? = null) {
      val d = File(root, name).also { it.mkdirs() }
      File(d, "type").writeText(type)
      capacity?.let { File(d, "capacity").writeText(it.toString()) }
      scope?.let { File(d, "scope").writeText(it) }
    }
    try {
      supply("AC", "Mains")
      supply("BAT0", "Battery", capacity = 40)
      supply("BAT1", "Battery", capacity = 60)
      supply("hidpp_battery_0", "Battery", capacity = 5, scope = "Device")
      assertEquals(50, HostState.parseLinuxCapacity(root))
      assertNull(HostState.parseLinuxCapacity(File(root, "nope")))
    } finally { root.deleteRecursively() }
  }

  @Test fun linuxThermalPicksTheHottestProcessorZone() {
    val root = kotlin.io.path.createTempDirectory("th").toFile()
    fun zone(n: Int, type: String, milli: Int) {
      val d = File(root, "thermal_zone$n").also { it.mkdirs() }
      File(d, "type").writeText(type); File(d, "temp").writeText(milli.toString())
    }
    try {
      assertEquals(0, HostState.parseLinuxThermal(root))
      zone(0, "x86_pkg_temp", 60_000); zone(1, "iwlwifi", 95_000)
      assertEquals(0, HostState.parseLinuxThermal(root), "a radio's temperature is not the processor's")
      zone(2, "acpitz", 75_000)
      assertEquals(1, HostState.parseLinuxThermal(root))
      zone(3, "cpu-thermal", 84_000)
      assertEquals(2, HostState.parseLinuxThermal(root))
      zone(4, "soc", 93_000)
      assertEquals(3, HostState.parseLinuxThermal(root))
    } finally { root.deleteRecursively() }
  }

  // ---- the low-battery rule ---------------------------------------------------------------------------------------------------------

  private class Fake(var onBattery: Boolean? = true, var pct: Int? = 50) {
    var now = 0L
    fun rule(floor: Int = 20) = LowBatteryRule(floor, { onBattery }, { pct }, { now }, ttlMs = 20_000)
  }

  @Test fun lowBatteryHoldsOnlyOffTheChargerAndUnderTheFloor() {
    val f = Fake()
    val r = f.rule()
    assertFalse(r.holds(), "50% on battery: carry on")
    f.pct = 15; f.now += 20_000
    assertTrue(r.holds(), "15% on battery: wait")
    f.onBattery = false
    assertFalse(r.holds(), "plugged in: never wait")
  }

  @Test fun theOverrideLastsUntilTheChargerIsBack() {
    val f = Fake(pct = 10)
    val r = f.rule()
    assertTrue(r.holds())
    r.override()
    assertFalse(r.holds(), "the person said: read on battery anyway")
    f.now += 60_000
    assertFalse(r.holds(), "still on the battery: the answer stands")
    f.onBattery = false
    assertFalse(r.holds())
    assertFalse(r.overridden, "the charger came back: the override is spent")
    f.onBattery = true; f.now += 60_000
    assertTrue(r.holds(), "back on the battery, still low: it waits again")
  }

  @Test fun unknownThingsNeverHoldAndZeroTurnsItOff() {
    assertFalse(Fake(onBattery = null).rule().holds(), "no battery: nothing to wait for")
    assertFalse(Fake(pct = null).rule().holds(), "a charge nobody can read is not a reason to stop")
    assertFalse(Fake(pct = 1).rule(floor = 0).holds(), "0 turns the rule off")
  }

  @Test fun theChargeIsOnlyReadOncePerTtl() {
    var reads = 0
    var now = 0L
    val r = LowBatteryRule(20, { true }, { reads++; 50 }, { now }, ttlMs = 20_000)
    repeat(100) { now += 10; r.holds() }
    assertEquals(1, reads)
    now += 20_000; r.holds()
    assertEquals(2, reads)
  }
}
