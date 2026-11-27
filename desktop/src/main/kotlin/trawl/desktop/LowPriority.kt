package trawl.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * What each thread that reads for the index does for itself at its start, and what a helper program (ffmpeg, the OCR tools) gets when it is
 * started. The window's threads are never touched.
 *
 * Neither is inherited: a thread started by another thread, and a helper program started by this one, begin again at the normal setting,
 * and one thread cannot set another's. So every thread Trawl starts for reading calls [applyToThisThread] itself, and every helper goes
 * through [HelperProcs]. (The whole process at once, native threads of the model included, is [BackgroundMode].)
 *
 *  - macOS: the thread's quality-of-service class ([BackgroundTuning.threadQos]: 1 utility, 2 background), see [Qos].
 *  - Windows: nothing per thread. A thread's background mode was measured on Windows 11 to lower its disk and memory priority as well and to slow
 *    reading about 5.5 times even on an idle computer, and the thread settings are not inherited by a helper program. The lever there is the whole
 *    process ([WinPriority.thisProcess], by [BackgroundMode]), whose priority class the helper programs do inherit.
 *  - Linux: nothing here. `SCHED_IDLE`, `nice` and `ionice` were measured to starve the reader and to hold up searches (priority inversion),
 *    and they do not protect other programs across systemd scopes; see [LinuxScope].
 *
 * Returns what it did, or null when it did nothing (off, refused, or this system has no such thing).
 */
object LowPriority {
  /** Current [BackgroundMode] tier so newly spawned reading threads can pick up `QOS_UTILITY` on macOS while away on AC power. */
  @Volatile var activeTier: BackgroundTier = BackgroundTier.FOREGROUND

  fun applyToThisThread(
    os: Os = Platform.os,
    qos: Int = BackgroundTuning.threadQos,
    tier: BackgroundTier = activeTier,
    applyQos: (Int) -> String? = Qos::thread,
  ): String? = try {
    when (os) {
      Os.MAC -> when {
        qos == 1 -> applyQos(Qos.QOS_UTILITY)
        qos == 2 -> applyQos(Qos.QOS_BACKGROUND)
        tier == BackgroundTier.UTILITY -> applyQos(Qos.QOS_UTILITY)
        else -> null
      }
      Os.WINDOWS, Os.LINUX -> null
    }
  } catch (_: Throwable) { null }

  /**
   * Adjusts an existing reading worker thread's macOS QoS class to match [tier] (unless an explicit [fixedQos] override was configured).
   * In [BackgroundTier.UTILITY], the thread runs at `QOS_CLASS_UTILITY`; otherwise it returns to `QOS_CLASS_DEFAULT` (and in
   * [BackgroundTier.BACKGROUND] process-wide `PRIO_DARWIN_BG` clamps all threads).
   */
  fun applyTierToThisThread(
    tier: BackgroundTier,
    os: Os = Platform.os,
    fixedQos: Int = BackgroundTuning.threadQos,
    applyQos: (Int) -> String? = Qos::thread,
  ): String? = try {
    if (os != Os.MAC || fixedQos != 0) null
    else applyQos(if (tier == BackgroundTier.UTILITY) Qos.QOS_UTILITY else Qos.QOS_DEFAULT)
  } catch (_: Throwable) { null }

  /** A thread factory for a pool of readers: names the thread, makes it a daemon, and calls [applyToThisThread] on it. */
  fun factory(name: String): java.util.concurrent.ThreadFactory = java.util.concurrent.ThreadFactory { r ->
    Thread({ applyToThisThread(); r.run() }, name).apply { isDaemon = true }
  }
}

/**
 * Helper programs: started through `taskpolicy` on a Mac ([Qos.wrap]). On Windows a helper started while the process is lowered inherits the
 * priority class by itself; [started] adds EcoQoS to it when the process has it (that is not inherited), and below-normal as a belt-and-braces.
 * Long-lived helpers (Apple Vision OCR, AVFoundation/Media Foundation decoders) are also tracked so [syncTier] restores their priority when
 * [BackgroundMode] leaves the background class.
 */
object HelperProcs {
  private val helperLock = Any()
  private val liveHelpers = LinkedHashSet<ProcessHandle>()
  @Volatile private var currentTier: BackgroundTier = BackgroundTier.FOREGROUND

  fun builder(command: List<String>): ProcessBuilder = ProcessBuilder(Qos.wrap(command))
  fun builder(vararg command: String): ProcessBuilder = builder(command.toList())

  /** For a process just started with [builder]; tracks its CPU time and lifecycle, applies platform priority, then returns it. */
  fun started(p: Process): Process = started(p, Platform.os)

  internal fun started(
    p: Process,
    os: Os = Platform.os,
    macEnter: (Long) -> Boolean = Qos.Process::enterPid,
    macRestore: (Long) -> Boolean = Qos.Process::restorePid,
    winLower: (Long, Boolean) -> Unit = WinPriority::lower,
    winRestore: (Long) -> Unit = WinPriority::restore,
    winLowered: () -> Boolean = { WinPriority.processLowered },
    winEco: () -> Boolean = { WinPriority.processEco },
  ): Process {
    Politeness.Cpu.track(p)
    val h = p.toHandle()
    val tier: BackgroundTier
    val registered: Boolean
    synchronized(helperLock) {
      tier = currentTier
      if (h.isAlive) {
        liveHelpers.add(h)
        registered = true
      } else {
        registered = false
      }
    }
    if (registered) {
      runCatching {
        h.onExit().whenComplete { _, _ ->
          synchronized(helperLock) { liveHelpers.remove(h) }
        }
      }
    }
    if (h.isAlive) {
      applyTierToPid(h.pid(), tier, os, macEnter, macRestore, winLower, winRestore, winLowered, winEco)
    }
    return p
  }

  /** Synchronizes long-lived reading helpers with [tier] so helpers spawned during background mode do not stay throttled after foregrounding. */
  fun syncTier(
    tier: BackgroundTier,
    os: Os = Platform.os,
    pids: Iterable<Long>? = null,
    macEnter: (Long) -> Boolean = Qos.Process::enterPid,
    macRestore: (Long) -> Boolean = Qos.Process::restorePid,
    winLower: (Long, Boolean) -> Unit = WinPriority::lower,
    winRestore: (Long) -> Unit = WinPriority::restore,
    winLowered: () -> Boolean = { WinPriority.processLowered },
    winEco: () -> Boolean = { WinPriority.processEco },
  ) {
    val handles: List<ProcessHandle>?
    synchronized(helperLock) {
      currentTier = tier
      handles = if (pids == null) {
        val it = liveHelpers.iterator()
        val snap = ArrayList<ProcessHandle>()
        while (it.hasNext()) {
          val h = it.next()
          if (h.isAlive) snap += h else it.remove()
        }
        snap
      } else null
    }
    if (handles != null) {
      for (h in handles) {
        if (!h.isAlive) continue
        applyTierToPid(h.pid(), tier, os, macEnter, macRestore, winLower, winRestore, winLowered, winEco)
      }
    } else if (pids != null) {
      for (pid in pids) {
        applyTierToPid(pid, tier, os, macEnter, macRestore, winLower, winRestore, winLowered, winEco)
      }
    }
  }

  private fun applyTierToPid(
    pid: Long,
    tier: BackgroundTier,
    os: Os,
    macEnter: (Long) -> Boolean,
    macRestore: (Long) -> Boolean,
    winLower: (Long, Boolean) -> Unit,
    winRestore: (Long) -> Unit,
    winLowered: () -> Boolean,
    winEco: () -> Boolean,
  ) {
    when (os) {
      Os.MAC -> {
        if (tier == BackgroundTier.BACKGROUND) runCatching { macEnter(pid) }
        else runCatching { macRestore(pid) }
      }
      Os.WINDOWS -> {
        if (tier == BackgroundTier.FOREGROUND) runCatching { winRestore(pid) }
        else if (winLowered()) runCatching { winLower(pid, winEco()) }
      }
      Os.LINUX -> {}
    }
  }
}

/** Windows levers, used only when [BackgroundTuning.windowsBackground] says so; measured on a Windows 11 virtual machine (internal/research/desktop-tuning.md). */
object WinPriority {
  private interface Kernel32 : Library {
    fun GetCurrentThread(): Pointer
    fun GetCurrentProcess(): Pointer
    fun GetPriorityClass(process: Pointer): Int
    fun GetProcessInformation(process: Pointer, informationClass: Int, info: Pointer, size: Int): Boolean
    fun SetThreadPriority(thread: Pointer, priority: Int): Boolean
    fun SetThreadInformation(thread: Pointer, informationClass: Int, info: Pointer, size: Int): Boolean
    fun SetProcessInformation(process: Pointer, informationClass: Int, info: Pointer, size: Int): Boolean
    fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
    fun SetPriorityClass(process: Pointer, priorityClass: Int): Boolean
    fun CloseHandle(handle: Pointer): Boolean
  }

  @Suppress("PropertyName")
  class PowerThrottling : Structure() {
    @JvmField var Version: Int = 1
    @JvmField var ControlMask: Int = 1   // THREAD_POWER_THROTTLING_EXECUTION_SPEED (PROCESS_POWER_THROTTLING_EXECUTION_SPEED is the same bit)
    @JvmField var StateMask: Int = 1     // on
    override fun getFieldOrder() = listOf("Version", "ControlMask", "StateMask")
  }

  private val k: Kernel32? by lazy { try { Native.load("kernel32", Kernel32::class.java) } catch (_: Throwable) { null } }

  private const val NORMAL = 0x20
  private const val BELOW_NORMAL = 0x4000

  /** The class the process had before [thisProcess], so that leaving puts back what the person (or a shortcut) had chosen, not a guess. */
  @Volatile private var classBefore = 0
  /** Whether the whole process is lowered now, and with EcoQoS: a helper program started meanwhile gets the same ([HelperProcs.started]). */
  @Volatile var processLowered = false; private set
  @Volatile var processEco = false; private set

  /**
   * The whole process (every thread, the model's native ones too, and the helper programs it starts from now on: they inherit the priority class,
   * not the EcoQoS state) at below-normal priority, and with [eco] also EcoQoS (what Task Manager calls efficiency mode). The measured choice on
   * Windows: a thread's background mode lowers its disk and memory priority too and slowed reading 5.5 times even on an idle machine.
   */
  fun thisProcess(eco: Boolean): String? {
    val c = k ?: return null
    val me = c.GetCurrentProcess()
    if (!processLowered) classBefore = c.GetPriorityClass(me).takeIf { it != 0 } ?: NORMAL
    val done = ArrayList<String>()
    if (c.SetPriorityClass(me, BELOW_NORMAL)) { done += "below normal"; processLowered = true }
    if (eco) {
      val s = PowerThrottling().also { it.write() }
      if (c.SetProcessInformation(me, 4 /* ProcessPowerThrottling */, s.pointer, s.size())) { done += "EcoQoS"; processEco = true }
    }
    return done.takeIf { it.isNotEmpty() }?.joinToString(" + ")
  }

  /** Back to what [thisProcess] found: the earlier class, and the system's own choice about power throttling (neither forced on nor off). */
  fun restoreThisProcess(): String? {
    val c = k ?: return null
    val me = c.GetCurrentProcess()
    val done = ArrayList<String>()
    if (c.SetPriorityClass(me, classBefore.takeIf { it != 0 } ?: NORMAL)) { done += "priority class restored"; processLowered = false }
    if (processEco) {
      val s = PowerThrottling().also { it.ControlMask = 0; it.StateMask = 0; it.write() }   // "not controlled": the system decides again
      if (c.SetProcessInformation(me, 4, s.pointer, s.size())) { done += "EcoQoS cleared"; processEco = false }
    }
    return done.takeIf { it.isNotEmpty() }?.joinToString(" + ")
  }

  /** What the system says now: the priority class (hex) and whether EcoQoS is on; null when it cannot be read. For logs and the checks on a real machine. */
  fun readBackThisProcess(): String? {
    val c = k ?: return null
    val me = c.GetCurrentProcess()
    val cls = c.GetPriorityClass(me)
    val s = PowerThrottling().also { it.ControlMask = 0; it.StateMask = 0; it.write() }
    val eco = if (c.GetProcessInformation(me, 4, s.pointer, s.size())) { s.read(); if (s.ControlMask and 1 != 0 && s.StateMask and 1 != 0) "on" else "off" } else "unreadable"
    return "class 0x%x, EcoQoS %s".format(cls, eco)
  }

  fun thisThread(eco: Boolean): String? {
    val c = k ?: return null
    val done = ArrayList<String>()
    if (c.SetThreadPriority(c.GetCurrentThread(), 0x00010000)) done += "THREAD_MODE_BACKGROUND_BEGIN"   // lowers this thread only, never the window's
    if (eco) {
      val s = PowerThrottling().also { it.write() }
      if (c.SetThreadInformation(c.GetCurrentThread(), 3 /* ThreadPowerThrottling; 4 is the process-only class and is refused with error 87 */, s.pointer, s.size())) done += "EcoQoS"
    }
    return done.takeIf { it.isNotEmpty() }?.joinToString(" + ")
  }

  /**
   * A helper program to below-normal priority, and with [eco] also EcoQoS for the whole helper (what Task Manager calls efficiency mode).
   * A short race between its start and this call is accepted. Its own children inherit the priority class, not the EcoQoS state.
   */
  fun lower(pid: Long, eco: Boolean = false) {
    val c = k ?: return
    val h = c.OpenProcess(0x0200 /* PROCESS_SET_INFORMATION */, false, pid.toInt()) ?: return
    try {
      c.SetPriorityClass(h, 0x4000 /* BELOW_NORMAL_PRIORITY_CLASS */)
      if (eco) { val s = PowerThrottling().also { it.write() }; c.SetProcessInformation(h, 4 /* ProcessPowerThrottling */, s.pointer, s.size()) }
    } finally { c.CloseHandle(h) }
  }

  /** Restores a long-lived helper program to normal priority class and clears EcoQoS when the app returns to foreground. */
  fun restore(pid: Long) {
    val c = k ?: return
    val h = c.OpenProcess(0x0200 /* PROCESS_SET_INFORMATION */, false, pid.toInt()) ?: return
    try {
      c.SetPriorityClass(h, classBefore.takeIf { it != 0 } ?: NORMAL)
      val s = PowerThrottling().also { it.ControlMask = 0; it.StateMask = 0; it.write() }
      c.SetProcessInformation(h, 4 /* ProcessPowerThrottling */, s.pointer, s.size())
    } finally { c.CloseHandle(h) }
  }
}
