package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import trawl.desktop.shell.DBusConnection
import trawl.desktop.shell.Variant
import java.io.File

/**
 * Linux: Trawl asks systemd to lower the CPU weight (and, for Gentle, put a CPU quota) on the scope it runs in, while no window is
 * showing, and to put both back the moment one comes forward ([BackgroundMode]). `SetUnitProperties` on the person's own service manager;
 * it needs no rights beyond theirs, is reversible, and covers every thread and helper program in the scope.
 *
 * It replaces `SCHED_IDLE` and `ionice`, which are measured to be wrong for this: a thread at `SCHED_IDLE` never runs while another one wants
 * the core, so the reader starved and a search that waited for it took 234 s at p95 (priority inversion). A weight shares the cores
 * instead of ranking them, so nothing waits for ever; and only a quota lowers heat (a weight does nothing on a computer with idle cores).
 *
 * Safe by construction, and it fails open (does nothing, says so in the log):
 *  - only in the person's own service manager (`user@UID.service`), never the system's;
 *  - only when the scope holds nothing but Trawl and what Trawl started: a terminal's scope that Trawl was started from would otherwise
 *    be limited with the shell and every other program in it;
 *  - systemd 252 or later (older ones do not delegate the CPU controller to a user).
 */
class LinuxScope(
  private val unit: String,
  private val set: (unit: String, weight: Long, quotaUsec: Long) -> Boolean,
  private val quotaPct: Int = BackgroundTuning.linuxQuotaPct,
  private val weight: Int = BackgroundTuning.linuxWeight,
) : Throttle {
  override fun enter(speed: LearningSpeed): String? {
    // Gentle: the quota lowers heat; Balanced: the weight alone (a share, never a wall). Fast is never throttled.
    val quota = if (speed == LearningSpeed.GENTLE && quotaPct > 0) quotaPct * 10_000L else NONE
    val w = weight.coerceIn(1, 10_000).toLong()
    return if (set(unit, w, quota)) "CPUWeight $w" + (if (quota != NONE) ", CPUQuota $quotaPct%" else "") else null
  }

  override fun leave(): String? = if (set(unit, NONE, NONE)) "CPUWeight and CPUQuota back to default" else null

  companion object {
    /** systemd's "not set": the default weight, no quota. (All ones, as an unsigned 64-bit number.) */
    const val NONE = -1L

    /** The unit to change, from the first line of `/proc/self/cgroup`, or null when it is not one this program may change (see above). */
    fun unitOf(cgroup: String, uid: Long, onlyOurs: (String) -> Boolean): String? {
      val line = cgroup.lineSequence().firstOrNull { it.startsWith("0::") } ?: return null
      val path = line.removePrefix("0::").trim()
      if (!path.contains("/user@$uid.service/")) return null
      val name = path.substringAfterLast('/')
      if (!(name.endsWith(".scope") || name.endsWith(".service")) || name.startsWith("user@") || name.startsWith("session-")) return null
      return if (onlyOurs(path)) name else null
    }

    /** True when every process in the cgroup at [path] is this program or one it started. */
    fun holdsOnlyUs(path: String, procs: (String) -> List<Long>? = ::readProcs, self: Long = ProcessHandle.current().pid(), below: Set<Long> = descendants()): Boolean {
      val pids = procs(path) ?: return false
      return pids.isNotEmpty() && pids.all { it == self || it in below }
    }

    private fun readProcs(path: String): List<Long>? = try {
      File("/sys/fs/cgroup$path/cgroup.procs").readLines().mapNotNull { it.trim().toLongOrNull() }
    } catch (_: Throwable) { null }

    private fun descendants(): Set<Long> = try {
      ProcessHandle.current().descendants().use { s -> s.map { it.pid() }.toList().toSet() }
    } catch (_: Throwable) { emptySet() }

    /** The real thing, or null with a reason in [why]. */
    fun open(why: (String) -> Unit = {}): LinuxScope? {
      val uid = DBusConnection.currentUid()
      val cgroup = try { File("/proc/self/cgroup").readText() } catch (_: Throwable) { why("no /proc/self/cgroup"); return null }
      val unit = unitOf(cgroup, uid) { holdsOnlyUs(it) }
      if (unit == null) { why("this program does not run alone in a scope of the user's own service manager"); return null }
      val address = DBusConnection.sessionAddress()
      if (address == null) { why("no session bus"); return null }
      return LinuxScope(unit, { u, w, q -> setOnBus(address, u, w, q) })
    }

    private fun setOnBus(address: String, unit: String, weight: Long, quotaUsec: Long): Boolean = try {
      DBusConnection.connect(address).use { bus ->
        bus.call(
          "org.freedesktop.systemd1", "/org/freedesktop/systemd1", "org.freedesktop.systemd1.Manager", "SetUnitProperties",
          "sba(sv)", listOf(unit, true, listOf(listOf("CPUWeight", Variant("t", weight)), listOf("CPUQuotaPerSecUSec", Variant("t", quotaUsec)))),
        )
      }
      true
    } catch (_: Throwable) { false }
  }
}
