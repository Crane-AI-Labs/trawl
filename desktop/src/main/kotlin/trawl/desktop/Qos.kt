package trawl.desktop

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * macOS: how Trawl's reading is put behind the person's own programs. Three levers, because the system keeps them apart:
 *
 *  - [Process]: the whole process is set to the background class and its disk to "throttle" (`setpriority(PRIO_DARWIN_PROCESS, ...)` and
 *    `setiopolicy_np(IOPOL_TYPE_DISK, IOPOL_SCOPE_PROCESS, ...)`). Every thread, native ones the model starts included, and every helper program
 *    started afterwards, is inside it. It is reversible, and it is lifted the moment a window or the quick panel comes forward ([BackgroundMode]).
 *  - [thread]: one thread sets its own quality-of-service class. Not inherited: a new thread, or a helper program, starts again at the
 *    default, and one thread cannot set another's. So every thread Trawl starts for reading calls it at its start.
 *  - [wrap]: a helper program is started through `/usr/sbin/taskpolicy -c <class> -d throttle`, which sets both for it before it runs.
 *
 * Measured on this Mac's kind of machine, see internal/research/desktop-tuning.md. None of these needs administrator rights.
 */
object Qos {
  private interface LibSystem : Library {
    fun pthread_set_qos_class_self_np(qosClass: Int, relativePriority: Int): Int
    fun setiopolicy_np(type: Int, scope: Int, policy: Int): Int
    fun setpriority(which: Int, who: Int, prio: Int): Int
    fun getpriority(which: Int, who: Int): Int
  }

  const val QOS_USER_INITIATED = 0x19
  const val QOS_DEFAULT = 0x15
  const val QOS_UTILITY = 0x11
  const val QOS_BACKGROUND = 0x09

  private const val PRIO_DARWIN_PROCESS = 4
  private const val PRIO_DARWIN_BG = 0x1000
  private const val IOPOL_TYPE_DISK = 0
  private const val IOPOL_SCOPE_PROCESS = 0
  private const val IOPOL_DEFAULT = 0
  private const val IOPOL_THROTTLE = 3
  private const val IOPOL_UTILITY = 4

  private val lib: LibSystem? by lazy { try { Native.load("c", LibSystem::class.java) } catch (_: Throwable) { null } }

  /** The whole process behind the person's programs, and back. Both return what they did, or null when the system refused. */
  object Process {
    fun enter(): String? {
      val c = lib ?: return null
      val done = ArrayList<String>()
      if (c.setpriority(PRIO_DARWIN_PROCESS, 0, PRIO_DARWIN_BG) == 0) done += "darwinbg"
      if (c.setiopolicy_np(IOPOL_TYPE_DISK, IOPOL_SCOPE_PROCESS, IOPOL_THROTTLE) == 0) done += "disk throttle"
      return done.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    /**
     * Middle tier while the person is away on AC power: clears `PRIO_DARWIN_BG` so E/P cores are available for `QOS_UTILITY` worker
     * threads, while setting disk I/O to `IOPOL_UTILITY` so interactive work still wins disk contention.
     */
    fun enterUtility(): String? {
      val c = lib ?: return null
      val done = ArrayList<String>()
      if (c.setpriority(PRIO_DARWIN_PROCESS, 0, 0) == 0) done += "darwinbg cleared"
      if (c.setiopolicy_np(IOPOL_TYPE_DISK, IOPOL_SCOPE_PROCESS, IOPOL_UTILITY) == 0) done += "disk utility"
      return done.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    fun leave(): String? {
      val c = lib ?: return null
      val done = ArrayList<String>()
      if (c.setpriority(PRIO_DARWIN_PROCESS, 0, 0) == 0) done += "darwinbg cleared"
      if (c.setiopolicy_np(IOPOL_TYPE_DISK, IOPOL_SCOPE_PROCESS, IOPOL_DEFAULT) == 0) done += "disk default"
      return done.takeIf { it.isNotEmpty() }?.joinToString(" + ")
    }

    /** Applies `PRIO_DARWIN_BG` to a child helper [pid] owned by this user. */
    fun enterPid(pid: Long): Boolean {
      val c = lib ?: return false
      return pid > 0 && c.setpriority(PRIO_DARWIN_PROCESS, pid.toInt(), PRIO_DARWIN_BG) == 0
    }

    /** Clears `PRIO_DARWIN_BG` on a child helper [pid] so long-lived helpers do not stay throttled after foregrounding. */
    fun restorePid(pid: Long): Boolean {
      val c = lib ?: return false
      return pid > 0 && c.setpriority(PRIO_DARWIN_PROCESS, pid.toInt(), 0) == 0
    }

    /** Whether the process is in the background class now (true/false), or null when it cannot be read. */
    fun inBackground(): Boolean? {
      val c = lib ?: return null
      return c.getpriority(PRIO_DARWIN_PROCESS, 0) == 1   // 1 in the background class, 0 not (-1 on an error reads as not)
    }
  }

  /** The calling thread's class ([QOS_DEFAULT], [QOS_UTILITY], [QOS_BACKGROUND]); null when refused. */
  fun thread(qosClass: Int): String? {
    val c = lib ?: return null
    return if (c.pthread_set_qos_class_self_np(qosClass, 0) == 0) when (qosClass) {
      QOS_BACKGROUND -> "QOS_CLASS_BACKGROUND"
      QOS_UTILITY -> "QOS_CLASS_UTILITY"
      QOS_DEFAULT -> "QOS_CLASS_DEFAULT"
      QOS_USER_INITIATED -> "QOS_CLASS_USER_INITIATED"
      else -> "QOS $qosClass"
    } else null
  }

  private const val TASKPOLICY = "/usr/sbin/taskpolicy"

  /** [command] started through `taskpolicy` when [BackgroundTuning.helperQos] says so; the command itself when it does not (or the tool is missing). */
  fun wrap(command: List<String>, os: Os = Platform.os, mode: Int = BackgroundTuning.helperQos, exists: (String) -> Boolean = { java.io.File(it).canExecute() }): List<String> {
    if (os != Os.MAC || mode == 0 || !exists(TASKPOLICY)) return command
    val cls = if (mode == 2) "background" else "utility"
    return listOf(TASKPOLICY, "-c", cls, "-d", "throttle") + command
  }
}
