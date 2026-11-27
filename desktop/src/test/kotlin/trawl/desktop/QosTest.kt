package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The parts of the macOS and Windows levers that can be checked anywhere: when a helper program is wrapped, and that other systems are left alone. */
class QosTest {
  private val cmd = listOf("/usr/bin/ffmpeg", "-i", "a.mov")
  private val there = { _: String -> true }

  @Test fun aHelperIsOnlyWrappedOnAMacWhenAskedAndWhenTaskpolicyIsThere() {
    assertEquals(cmd, Qos.wrap(cmd, Os.MAC, mode = 0, exists = there))
    assertEquals(cmd, Qos.wrap(cmd, Os.LINUX, mode = 1, exists = there))
    assertEquals(cmd, Qos.wrap(cmd, Os.WINDOWS, mode = 1, exists = there))
    assertEquals(cmd, Qos.wrap(cmd, Os.MAC, mode = 1, exists = { false }))
    assertEquals(listOf("/usr/sbin/taskpolicy", "-c", "utility", "-d", "throttle") + cmd, Qos.wrap(cmd, Os.MAC, mode = 1, exists = there))
    assertEquals(listOf("/usr/sbin/taskpolicy", "-c", "background", "-d", "throttle") + cmd, Qos.wrap(cmd, Os.MAC, mode = 2, exists = there))
  }

  @Test fun theThreadLeverDoesNothingOffAMacOrWhenOff() {
    assertNull(LowPriority.applyToThisThread(Os.LINUX, qos = 2), "Linux has no thread lever: SCHED_IDLE was measured to starve the reader")
    assertNull(LowPriority.applyToThisThread(Os.MAC, qos = 0))
    assertNull(LowPriority.applyToThisThread(Os.WINDOWS, qos = 2), "Windows has no thread lever: a thread's background mode slowed reading 5.5 times on an idle computer")
  }

  @Test fun theFactoryNamesTheThreadAndMakesItADaemon() {
    var seen: Thread? = null
    val t = LowPriority.factory("trawl-test").newThread { seen = Thread.currentThread() }
    t.start(); t.join()
    assertEquals("trawl-test", seen?.name); assertEquals(true, seen?.isDaemon)
  }

  @Test fun workerThreadTierSwitchesBetweenUtilityAndDefaultOnMacOnly() {
    val applied = ArrayList<Int>()
    val fake: (Int) -> String? = { applied += it; "ok-$it" }

    assertEquals("ok-${Qos.QOS_UTILITY}", LowPriority.applyTierToThisThread(BackgroundTier.UTILITY, Os.MAC, fixedQos = 0, applyQos = fake))
    assertEquals("ok-${Qos.QOS_DEFAULT}", LowPriority.applyTierToThisThread(BackgroundTier.FOREGROUND, Os.MAC, fixedQos = 0, applyQos = fake))
    assertEquals("ok-${Qos.QOS_DEFAULT}", LowPriority.applyTierToThisThread(BackgroundTier.BACKGROUND, Os.MAC, fixedQos = 0, applyQos = fake))
    assertEquals(listOf(Qos.QOS_UTILITY, Qos.QOS_DEFAULT, Qos.QOS_DEFAULT), applied)

    // Explicit fixedQos override or non-macOS leaves thread untouched.
    assertNull(LowPriority.applyTierToThisThread(BackgroundTier.UTILITY, Os.MAC, fixedQos = 2, applyQos = fake))
    assertNull(LowPriority.applyTierToThisThread(BackgroundTier.UTILITY, Os.LINUX, fixedQos = 0, applyQos = fake))
    assertNull(LowPriority.applyTierToThisThread(BackgroundTier.UTILITY, Os.WINDOWS, fixedQos = 0, applyQos = fake))

    // Newly started threads pick up Utility QoS when activeTier is UTILITY.
    assertEquals("ok-${Qos.QOS_UTILITY}", LowPriority.applyToThisThread(Os.MAC, qos = 0, tier = BackgroundTier.UTILITY, applyQos = fake))
    assertNull(LowPriority.applyToThisThread(Os.MAC, qos = 0, tier = BackgroundTier.FOREGROUND, applyQos = fake))
  }

  @Test fun longLivedHelpersFollowBackgroundAndForegroundTransitions() {
    val entered = ArrayList<Long>()
    val restored = ArrayList<Long>()
    val pids = listOf(101L, 202L)

    HelperProcs.syncTier(BackgroundTier.BACKGROUND, Os.MAC, pids, macEnter = { entered += it; true }, macRestore = { restored += it; true })
    assertEquals(pids, entered)
    assertEquals(emptyList(), restored)

    HelperProcs.syncTier(BackgroundTier.UTILITY, Os.MAC, pids, macEnter = { entered += it; true }, macRestore = { restored += it; true })
    assertEquals(pids, restored, "Utility tier must lift PRIO_DARWIN_BG on long-lived helpers too")

    restored.clear()
    HelperProcs.syncTier(BackgroundTier.FOREGROUND, Os.MAC, pids, macEnter = { entered += it; true }, macRestore = { restored += it; true })
    assertEquals(pids, restored, "Foreground must lift PRIO_DARWIN_BG on long-lived helpers")
  }

  @Test fun startedAppliesCurrentTierImmediatelyToAvoidSpawnTransitionRace() {
    if (Platform.os == Os.WINDOWS) return
    val p = ProcessBuilder("sleep", "30").start()
    try {
      val entered = ArrayList<Long>()
      val restored = ArrayList<Long>()

      // Helper spawned while process transitioned to FOREGROUND before started() ran must be restored immediately.
      HelperProcs.syncTier(BackgroundTier.FOREGROUND, Os.MAC, emptyList())
      HelperProcs.started(p, Os.MAC, macEnter = { entered += it; true }, macRestore = { restored += it; true })
      assertEquals(emptyList(), entered)
      assertEquals(listOf(p.pid()), restored, "started() must apply FOREGROUND restore immediately")

      // Helper registered while BACKGROUND must enter background immediately on started().
      entered.clear(); restored.clear()
      HelperProcs.syncTier(BackgroundTier.BACKGROUND, Os.MAC, emptyList())
      HelperProcs.started(p, Os.MAC, macEnter = { entered += it; true }, macRestore = { restored += it; true })
      assertEquals(listOf(p.pid()), entered, "started() must apply BACKGROUND enter immediately")
      assertEquals(emptyList(), restored)

      // Windows: FOREGROUND calls winRestore, BACKGROUND calls winLower when lowered.
      val winRestored = ArrayList<Long>()
      val winLoweredPids = ArrayList<Pair<Long, Boolean>>()
      HelperProcs.syncTier(BackgroundTier.FOREGROUND, Os.WINDOWS, emptyList())
      HelperProcs.started(
        p,
        Os.WINDOWS,
        winLower = { pid, eco -> winLoweredPids += pid to eco },
        winRestore = { winRestored += it },
        winLowered = { true },
        winEco = { true },
      )
      assertEquals(listOf(p.pid()), winRestored)
      assertEquals(emptyList(), winLoweredPids)

      winRestored.clear()
      HelperProcs.syncTier(BackgroundTier.BACKGROUND, Os.WINDOWS, emptyList())
      HelperProcs.started(
        p,
        Os.WINDOWS,
        winLower = { pid, eco -> winLoweredPids += pid to eco },
        winRestore = { winRestored += it },
        winLowered = { true },
        winEco = { true },
      )
      assertEquals(emptyList(), winRestored)
      assertEquals(listOf(p.pid() to true), winLoweredPids)
    } finally {
      HelperProcs.syncTier(BackgroundTier.FOREGROUND, Os.MAC, emptyList())
      p.destroyForcibly()
      p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    }
  }

  @Test fun macUtilityProcessSyscallsRoundTripCleanly() {
    if (Platform.os != Os.MAC) return
    try {
      val util = Qos.Process.enterUtility()
      assertEquals(false, Qos.Process.inBackground(), "enterUtility must keep PRIO_DARWIN_BG cleared (got $util)")
      val left = Qos.Process.leave()
      assertEquals(false, Qos.Process.inBackground(), "leave must keep PRIO_DARWIN_BG cleared (got $left)")
    } finally {
      Qos.Process.leave()
    }
  }
}
