package trawl.desktop

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Windows read-back for the levers in [WinPriority]: what the system says about a thread or a process, not what the program asked for.
 * Test and bench only (used by [WinTuneBench]); every call reports the Win32 error code instead of hiding a failure.
 */
object WinProbe {
  interface K32 : Library {
    fun GetCurrentThread(): Pointer
    fun GetCurrentThreadId(): Int
    fun GetCurrentProcess(): Pointer
    @Throws(LastErrorException::class) fun GetThreadPriority(h: Pointer): Int
    @Throws(LastErrorException::class) fun SetThreadPriority(h: Pointer, priority: Int): Boolean
    @Throws(LastErrorException::class) fun GetThreadInformation(h: Pointer, cls: Int, info: Pointer, size: Int): Boolean
    @Throws(LastErrorException::class) fun SetThreadInformation(h: Pointer, cls: Int, info: Pointer, size: Int): Boolean
    @Throws(LastErrorException::class) fun GetPriorityClass(h: Pointer): Int
    @Throws(LastErrorException::class) fun SetPriorityClass(h: Pointer, c: Int): Boolean
    @Throws(LastErrorException::class) fun GetProcessInformation(h: Pointer, cls: Int, info: Pointer, size: Int): Boolean
    @Throws(LastErrorException::class) fun SetProcessInformation(h: Pointer, cls: Int, info: Pointer, size: Int): Boolean
    @Throws(LastErrorException::class) fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
    fun CloseHandle(h: Pointer): Boolean
  }

  interface Nt : Library {
    fun NtQueryInformationThread(h: Pointer, cls: Int, buf: Pointer, len: Int, ret: Pointer?): Int
    fun NtQueryInformationProcess(h: Pointer, cls: Int, buf: Pointer, len: Int, ret: Pointer?): Int
  }

  interface Winmm : Library { fun timeBeginPeriod(ms: Int): Int }

  private val k: K32 by lazy { Native.load("kernel32", K32::class.java) }
  private val nt: Nt by lazy { Native.load("ntdll", Nt::class.java) }

  /** Raises the timer resolution to 1 ms for this process (as a window toolkit does), so that a sleep probe is not quantised to 15.6 ms. */
  fun timerResolution1ms(): String = try { "timeBeginPeriod(1)=" + Native.load("winmm", Winmm::class.java).timeBeginPeriod(1) } catch (e: Throwable) { "failed " + e.javaClass.simpleName }

  private inline fun api(f: () -> Any?): String = try { f().toString() } catch (e: LastErrorException) { "FAIL(err=${e.errorCode})" } catch (e: Throwable) { "ERROR(${e.javaClass.simpleName}: ${e.message})" }

  private fun u32(m: Memory, at: Long = 0) = m.getInt(at).toLong() and 0xffffffffL

  const val PRIORITY_CLASS_NORMAL = 0x20
  const val PRIORITY_CLASS_BELOW_NORMAL = 0x4000
  const val THREAD_MODE_BACKGROUND_BEGIN = 0x00010000
  const val THREAD_MODE_BACKGROUND_END = 0x00020000

  fun className(c: Int) = when (c) {
    0x20 -> "NORMAL"; 0x4000 -> "BELOW_NORMAL"; 0x40 -> "IDLE"; 0x8000 -> "ABOVE_NORMAL"; 0x80 -> "HIGH"; 0x100 -> "REALTIME"; else -> "0x" + Integer.toHexString(c)
  }

  /** What the system holds for the calling thread. IoPriority is 0 very low, 1 low, 2 normal; MemoryPriority is 1 (lowest) to 5 (normal). */
  fun threadState(): LinkedHashMap<String, String> {
    val h = k.GetCurrentThread()
    val m = LinkedHashMap<String, String>()
    m["tid"] = k.GetCurrentThreadId().toString()
    m["GetThreadPriority"] = api { k.GetThreadPriority(h) }
    val mem = Memory(16).also { it.clear() }
    m["MemoryPriority"] = api { if (k.GetThreadInformation(h, 0 /* ThreadMemoryPriority */, mem, 4)) u32(mem) else "false" }
    val io = Memory(16).also { it.clear() }
    m["IoPriority"] = api { val st = nt.NtQueryInformationThread(h, 22 /* ThreadIoPriority */, io, 4, null); if (st == 0) u32(io) else "ntstatus=0x" + Integer.toHexString(st) }
    val pt = Memory(16).also { it.clear(); it.setInt(0, 1) }   // the Get calls refuse (87) a buffer whose Version is not set to 1
    m["PowerThrottling(class3)"] = api { if (k.GetThreadInformation(h, 3 /* ThreadPowerThrottling */, pt, 12)) "control=${u32(pt, 4)} state=${u32(pt, 8)} version=${u32(pt, 0)}" else "false" }
    return m
  }

  /** What the system holds for process [pid] (0 = this one), read through a fresh handle. */
  fun processState(pid: Int = 0): LinkedHashMap<String, String> {
    val m = LinkedHashMap<String, String>()
    val own = pid == 0
    val h = if (own) k.GetCurrentProcess() else try { k.OpenProcess(0x0400 or 0x1000, false, pid) } catch (e: LastErrorException) { null }
    m["pid"] = if (own) ProcessHandle.current().pid().toString() else pid.toString()
    if (h == null) { m["open"] = "FAILED"; return m }
    try {
      m["PriorityClass"] = api { className(k.GetPriorityClass(h)) }
      val pt = Memory(16).also { it.clear(); it.setInt(0, 1) }
      m["PowerThrottling(class4)"] = api { if (k.GetProcessInformation(h, 4 /* ProcessPowerThrottling */, pt, 12)) "control=${u32(pt, 4)} state=${u32(pt, 8)} version=${u32(pt, 0)}" else "false" }
      val io = Memory(16).also { it.clear() }
      m["IoPriority"] = api { val st = nt.NtQueryInformationProcess(h, 33 /* ProcessIoPriority */, io, 4, null); if (st == 0) u32(io) else "ntstatus=0x" + Integer.toHexString(st) }
    } finally { if (!own) k.CloseHandle(h) }
    return m
  }

  /** Direct calls, with their error codes, for the two thread levers; the point is to see the system's answer. */
  fun trySetThread(background: Boolean): String = api { k.SetThreadPriority(k.GetCurrentThread(), if (background) THREAD_MODE_BACKGROUND_BEGIN else THREAD_MODE_BACKGROUND_END) }

  fun trySetThreadEco(cls: Int, on: Boolean): String {
    val s = WinPriority.PowerThrottling().also { it.StateMask = if (on) 1 else 0; it.write() }
    return api { k.SetThreadInformation(k.GetCurrentThread(), cls, s.pointer, s.size()) }
  }

  /** The whole of this process: below normal, and optionally EcoQoS (what Task Manager's "Efficiency mode" does). Used by one arm of the bench. */
  fun lowerOwnProcess(eco: Boolean): String {
    val h = k.GetCurrentProcess()
    val a = api { k.SetPriorityClass(h, PRIORITY_CLASS_BELOW_NORMAL) }
    if (!eco) return "SetPriorityClass=$a"
    val s = WinPriority.PowerThrottling().also { it.write() }
    return "SetPriorityClass=$a SetProcessInformation(4)=" + api { k.SetProcessInformation(h, 4, s.pointer, s.size()) }
  }

  /** The state of a thread of another process, read by Windows PowerShell (which is what a person would use): id, base and current priority. */
  fun powershellThreadView(pid: Long, tid: Int): String {
    val cmd = "Get-Process -Id $pid | ForEach-Object { \$_.Threads } | Where-Object { \$_.Id -eq $tid } | Select-Object Id,BasePriority,CurrentPriority,PriorityLevel | ConvertTo-Json -Compress"
    return powershell(cmd)
  }

  fun powershell(cmd: String): String = try {
    val p = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS); out
  } catch (e: Throwable) { "ERROR ${e.message}" }
}
