package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.lang.management.ManagementFactory

/**
 * How much of the computer the reader may take right now, so that the person's own work (a build, a video call, a game) is never slowed
 * and the fans never spin up for an index. The answer is a number of cores to average: [capCores]. The reader checks it after every
 * file; if the file took more processor time than the cap allows, it rests for the difference ([restMs]) before the next one.
 * That keeps working at any speed and any thread count, with no change to how fast one file is read.
 *
 * The cap is the smaller of two things, and never less than [minCores] (the index still gets built, slowly, however busy the computer is):
 *  - what the computer has left: its cores, minus what the other programs are using, minus one core kept free for the person;
 *  - the duty cell of the chosen speed ([DutyTable]): a few tenths of a core for Gentle and Balanced while the person is active
 *    ([UserState.UNKNOWN] counts as active), more once they are away, more again on the charger; none for Fast.
 * Heat and Low Power Mode are not part of it: they are Holds the person can override (IndexService).
 *
 * Every rest is at most [maxRestMs], so a file is read at least that often in every state: the index never stalls.
 * Everything the cap depends on comes from [Sensors], so the rule is tested with made-up readings, and the benchmark can switch it on
 * and off ([BackgroundTuning.governor]). Off, [restMs] is always 0 and nothing is read from the system.
 */
class Politeness(
  private val cores: Int = Runtime.getRuntime().availableProcessors(),
  private val sensors: Sensors = SystemSensors(),
  private val clock: () -> Long = System::currentTimeMillis,
  val enabled: Boolean = BackgroundTuning.governor != 0,
  private val spareCores: Double = BackgroundTuning.governorSpareCores,
  private val minCores: Double = BackgroundTuning.governorMinCores,
  private val maxRestMs: Long = BackgroundTuning.governorMaxRestMs,
  private val sampleMs: Long = BackgroundTuning.governorSampleMs,
  private val smoothing: Double = BackgroundTuning.governorSmoothing,
  private val table: DutyTable = DutyTable.fromTuning(cores),
  private val speed: () -> LearningSpeed = { LearningSpeed.BALANCED },
  /** Live thermal level (0 nominal, 1 fair/warm, 2+ serious): eases CPU duty at Fair (1) before hitting a hard thermal hold. */
  private val thermalLevel: () -> Int = { 0 },
) {
  /** [othersCores]: cores the other programs used since the last reading (null: not known). [onBattery] null: not known (treated as on the charger). */
  data class Reading(val othersCores: Double?, val user: UserState, val onBattery: Boolean?)

  fun interface Sensors { fun read(): Reading }

  private var readAt = Long.MIN_VALUE
  private var others: Double? = null
  @Volatile private var cap = Double.POSITIVE_INFINITY
  @Volatile private var capSpeed: LearningSpeed? = null
  @Volatile var lastReading: Reading? = null; private set

  /** Cores the reader may average at this moment (infinity when the rule is off). */
  @Synchronized fun capCores(): Double {
    if (!enabled) return Double.POSITIVE_INFINITY
    val now = clock()
    val s = speed()
    if (readAt == Long.MIN_VALUE || now - readAt >= sampleMs) sample(now, s)   // (the first look must not subtract from MIN_VALUE: that overflows)
    else if (s != capSpeed) work(s)                                          // a new speed is felt at once, with the last reading
    return cap
  }

  private fun sample(now: Long, s: LearningSpeed) {
    readAt = now
    val r = try { sensors.read() } catch (_: Throwable) { return }
    lastReading = r
    r.othersCores?.let { o -> others = others?.let { smoothing * o + (1 - smoothing) * it } ?: o }
    work(s)
  }

  private fun work(s: LearningSpeed) {
    val r = lastReading ?: return
    var c = cores - spareCores
    others?.let { c = minOf(c, cores - it - spareCores) }
    val duty = table.of(s).cores(r.user, r.onBattery == true)
    if (duty > 0) c = minOf(c, duty)
    // Thermal pre-throttle (macOS Fair = 1 / Linux 72-81C): ease CPU budget proactively before reaching Serious (2)
    // so fans stay quiet and indexing does not oscillate into hard thermal holds.
    val thermal = runCatching { thermalLevel() }.getOrDefault(0)
    if (thermal >= 1) {
      val warmCap = when (s) {
        LearningSpeed.GENTLE -> 0.5
        LearningSpeed.BALANCED -> 1.0
        LearningSpeed.FAST -> maxOf(2.0, cores * 0.25)
      }
      c = minOf(c, warmCap)
    }
    cap = c.coerceIn(minCores, cores.toDouble())
    capSpeed = s
  }

  /** How long to rest after a step that took [wallMs] of the clock and [cpuNs] of processor time, to average no more than the cap. */
  fun restMs(cpuNs: Long, wallMs: Long): Long {
    val c = capCores()
    if (c >= cores) return 0
    return ((cpuNs / 1e6) / c - wallMs).toLong().coerceIn(0L, maxRestMs)
  }

  /** The processor time this program and the programs it started have used, in nanoseconds. */
  object Cpu {
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private val kids = TrackedChildren()
    fun nowNs(): Long = os.processCpuTime + if (Platform.os == Os.LINUX) reapedChildrenNs() else kids.totalNs()

    /** Registers a helper [process] Trawl started so its CPU time is sampled directly without an OS-wide process scan. */
    fun track(process: Process): Process = kids.track(process)

    /** Registers a helper [handle] Trawl started so its CPU time is sampled directly without an OS-wide process scan. */
    fun track(handle: ProcessHandle) = kids.track(handle)

    /** Number of helper processes currently tracked live (for tests and diagnostics). */
    internal val trackedCount: Int get() = kids.trackedCount

    /** Linux: the helper programs (ffmpeg and the rest) that have finished. A program still running is counted when it ends. */
    private fun reapedChildrenNs(): Long {
      return try {
        val s = File("/proc/self/stat").readText()
        val after = s.substring(s.lastIndexOf(')') + 2).split(' ')
        (after[13].toLong() + after[14].toLong()) * 10_000_000L   // clock ticks of 10 ms
      } catch (_: Throwable) { 0 }
    }
  }
}

/**
 * Tracks only the helper processes Trawl itself started and sums their processor time directly via `ProcessHandle.info().totalCpuDuration()`,
 * avoiding OS-wide process-table scans (`ProcessHandle.current().descendants()`).
 *
 * When no tracked helpers are alive, [totalNs] returns the accumulated total with no locking, clock read, or syscall.
 * Exited handles have their final CPU time captured (via [Handle.onExit] or lazily during sampling) and are pruned so the set stays bounded.
 */
class TrackedChildren(
  private val now: () -> Long = System::nanoTime,
  private val everyMs: Long = 250,
) {
  /** One tracked child process. Backed by [ProcessHandle] in production; faked in unit tests. */
  interface Handle {
    val pid: Long
    val alive: Boolean
    fun cpuNs(): Long?
    fun onExit(action: () -> Unit) {}
  }

  private class ProcessHandleEntry(private val handle: ProcessHandle) : Handle {
    override val pid: Long get() = handle.pid()
    override val alive: Boolean get() = handle.isAlive
    override fun cpuNs(): Long? = try { handle.info().totalCpuDuration().map { it.toNanos() }.orElse(null) } catch (_: Throwable) { null }
    override fun onExit(action: () -> Unit) {
      try { handle.onExit().whenComplete { _, _ -> action() } } catch (_: Throwable) { }
    }
    override fun equals(other: Any?): Boolean = other is ProcessHandleEntry && handle == other.handle
    override fun hashCode(): Int = handle.hashCode()
  }

  private class Slot(val handle: Handle, var lastNs: Long)

  private val active = HashMap<Handle, Slot>()
  private var at = Long.MIN_VALUE
  private var gone = 0L
  @Volatile private var total = 0L
  @Volatile private var activeCount = 0

  /** Number of currently tracked live helper handles. */
  val trackedCount: Int get() = activeCount

  fun track(process: Process): Process = process.also { track(it.toHandle()) }

  fun track(handle: ProcessHandle) = track(ProcessHandleEntry(handle))

  fun track(handle: Handle) {
    val initial = (try { handle.cpuNs() } catch (_: Throwable) { null } ?: 0L).coerceAtLeast(0L)
    var registerExit = false
    synchronized(this) {
      if (handle in active) return
      if (!handle.alive) {
        gone += initial
        recomputeTotalLocked()
        return
      }
      active[handle] = Slot(handle, initial)
      recomputeTotalLocked()
      activeCount = active.size
      registerExit = true
    }
    if (registerExit) handle.onExit { finalizeHandle(handle) }
  }

  fun totalNs(): Long {
    // Fast path: with no helpers running, return the accumulated total without locking or making syscalls.
    if (activeCount == 0) return total
    synchronized(this) {
      if (active.isEmpty()) return total
      val t = now()
      if (at != Long.MIN_VALUE && (t - at) >= 0 && (t - at) / 1_000_000 < everyMs) return total
      at = t
      sampleLocked()
      return total
    }
  }

  private fun finalizeHandle(handle: Handle) {
    val finalNs = try { handle.cpuNs() } catch (_: Throwable) { null }
    synchronized(this) {
      val slot = active.remove(handle) ?: return
      val last = if (finalNs != null && finalNs > slot.lastNs) finalNs else slot.lastNs
      gone += last
      recomputeTotalLocked()
      // Publish total before activeCount so lock-free readers seeing activeCount == 0 observe the finalized total.
      activeCount = active.size
    }
  }

  private fun sampleLocked() {
    val it = active.entries.iterator()
    var liveSum = 0L
    while (it.hasNext()) {
      val slot = it.next().value
      val sampled = try { slot.handle.cpuNs() } catch (_: Throwable) { null }
      if (sampled != null && sampled > slot.lastNs) slot.lastNs = sampled
      if (!slot.handle.alive) {
        gone += slot.lastNs
        it.remove()
      } else {
        liveSum += slot.lastNs
      }
    }
    total = gone + liveSum
    activeCount = active.size
  }

  private fun recomputeTotalLocked() {
    var liveSum = 0L
    for (slot in active.values) liveSum += slot.lastNs
    total = gone + liveSum
  }
}

/**
 * Adds up the processor time of helper programs that come and go. [live] lists the programs running now and what each has used so far;
 * one that has left the list keeps its last seen total, so the sum only grows. The list is asked for at most every [everyMs] (it walks the
 * process table), which is also the most a short helper can use unseen.
 */
class ChildCpu(
  private val live: () -> Map<Long, Long>,
  private val now: () -> Long = System::nanoTime,
  private val everyMs: Long = 250,
) {
  private var at = Long.MIN_VALUE
  private var seen: Map<Long, Long> = emptyMap()
  private var gone = 0L
  private var total = 0L

  @Synchronized fun totalNs(): Long {
    val t = now()
    if (at != Long.MIN_VALUE && (t - at) / 1_000_000 < everyMs) return total
    at = t
    val cur = try { live() } catch (_: Throwable) { return total }
    for ((pid, ns) in seen) { val n = cur[pid]; if (n == null || n < ns) gone += ns }   // left the list (or the number was reused by another program)
    seen = cur
    total = gone + cur.values.sum()
    return total
  }
}

/**
 * The real readings. [read] must be called by one caller only, at least a few hundred milliseconds apart: it works from the change
 * since the last call. Others' cores are the whole computer's busy cores minus this program's own (the program and its helper programs).
 * [onBattery] is the app's cached answer (asking the system on every reading would start a program every second on a Mac).
 */
class SystemSensors(
  private val os: Os = Platform.os,
  private val cores: Int = Runtime.getRuntime().availableProcessors(),
  private val onBattery: () -> Boolean? = { Power.onBattery(os) },
) : Politeness.Sensors {
  private val jdk = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private var lastWall = 0L
  private var lastOurs = 0L
  private var lastBusy = -1L
  private var lastTotal = -1L

  override fun read(): Politeness.Reading {
    val wall = System.nanoTime()
    val ours = Politeness.Cpu.nowNs()
    val dt = (wall - lastWall) / 1e9
    var others: Double? = null
    val busyCores = busyCores()
    if (busyCores != null && lastWall != 0L && dt > 0.05) {
      val own = (ours - lastOurs) / 1e9 / dt
      others = (busyCores - own).coerceAtLeast(0.0)
    }
    lastWall = wall; lastOurs = ours
    val user = when (BackgroundTuning.assumeActive) {
      1 -> UserState.ACTIVE
      0 -> UserState.AWAY
      else -> UserActivity.read(os)
    }
    val battery = when (BackgroundTuning.assumePower) {
      1 -> true
      0 -> false
      else -> onBattery()
    }
    return Politeness.Reading(others, user, battery)
  }

  /** Cores busy on the whole computer since the last call (Linux: /proc/stat; elsewhere the JDK's own system load). */
  private fun busyCores(): Double? {
    if (os == Os.LINUX) {
      val f = try { File("/proc/stat").useLines { it.first() }.split(Regex("\\s+")).drop(1).map { it.toLong() } } catch (_: Throwable) { return null }
      if (f.size < 5) return null
      val total = f.take(8).sum(); val busy = total - f[3] - f[4]
      val r = if (lastTotal > 0 && total > lastTotal) (busy - lastBusy).toDouble() / (total - lastTotal) * cores else null
      lastBusy = busy; lastTotal = total
      return r
    }
    val l = jdk.cpuLoad
    return if (l.isNaN() || l < 0) null else l * cores
  }
}

/**
 * Is the person using the computer: [UserState.ACTIVE] within [BackgroundTuning.activeWithinS] of the last key or touch, [UserState.AWAY]
 * after that, [UserState.UNKNOWN] when this system cannot say (Linux without a display server we can ask), and then the reader acts as if
 * they were active: a wrong "away" is the mistake to avoid.
 */
object UserActivity {
  fun read(os: Os = Platform.os, idleSeconds: Double? = HostState.idleSeconds(os), awayAfterS: Long = BackgroundTuning.activeWithinS): UserState =
    classify(idleSeconds, awayAfterS)

  fun classify(idleSeconds: Double?, awayAfterS: Long): UserState = when {
    idleSeconds == null || idleSeconds < 0 -> UserState.UNKNOWN
    idleSeconds < awayAfterS -> UserState.ACTIVE
    else -> UserState.AWAY
  }
}
