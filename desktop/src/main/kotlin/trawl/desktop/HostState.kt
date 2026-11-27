package trawl.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Three small questions Trawl asks the operating system so that it can stay out of the person's way: is the computer getting hot,
 * has the person touched the keyboard lately, how full is the battery. Each answer is `null` (or 0 for heat) when the system does not
 * say, and the readers never throw: a missing answer means "carry on as before". The parsers are pure and unit-tested on any machine.
 */
object HostState {
  // ---- heat -----------------------------------------------------------------------------------------------------------------------

  /** 0 normal, 1 warm (ease off), 2 hot (nearly stop), 3 critical. macOS asks `NSProcessInfo.thermalState`; Linux reads the board's sensors. */
  fun thermal(os: Os = Platform.os): Int = try {
    when (os) {
      Os.MAC -> macThermalState() ?: parsePmsetTherm(cached("pmset-therm") { run("pmset", "-g", "therm") })
      Os.LINUX -> parseLinuxThermal(File("/sys/class/thermal"))
      Os.WINDOWS -> 0
    }
  } catch (_: Throwable) { 0 }

  /** macOS `pmset -g therm`: `CPU_Speed_Limit = 70` means the system is already slowing the processor down. */
  fun parsePmsetTherm(text: String?): Int {
    val limit = text?.lineSequence()?.firstOrNull { it.contains("CPU_Speed_Limit") }?.substringAfter('=')?.trim()?.toIntOrNull() ?: return 0
    return when { limit >= 100 -> 0; limit >= 80 -> 1; limit >= 50 -> 2; else -> 3 }
  }

  /** Linux: the hottest `thermal_zone*` that belongs to a processor or the board, in degrees: from 72 warm, from 82 hot, from 92 critical. */
  fun parseLinuxThermal(dir: File): Int {
    val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return 0
    var hottest = 0.0
    for (z in zones) {
      val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: continue
      if (!(type.contains("pkg") || type.contains("cpu") || type.contains("core") || type.contains("soc") || type.contains("acpitz") || type.contains("x86"))) continue
      val milli = runCatching { File(z, "temp").readText().trim().toDouble() }.getOrNull() ?: continue
      hottest = maxOf(hottest, if (milli > 1000) milli / 1000 else milli)
    }
    return when { hottest >= 92 -> 3; hottest >= 82 -> 2; hottest >= 72 -> 1; else -> 0 }
  }

  private interface ObjcPointer : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer
    fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
  }
  private interface ObjcLong : Library { fun objc_msgSend(receiver: Pointer, selector: Pointer): Long }

  private class MacObjc(val p: ObjcPointer, val l: ObjcLong, val processInfo: Pointer, val sel: Pointer, val lowPowerSel: Pointer)

  private val macObjc: MacObjc? by lazy {
    try {
      NativeLibrary.getInstance("Foundation")
      val p = Native.load("objc", ObjcPointer::class.java)
      val l = Native.load("objc", ObjcLong::class.java)
      val cls = p.objc_getClass("NSProcessInfo") ?: return@lazy null
      val info = p.objc_msgSend(cls, p.sel_registerName("processInfo")) ?: return@lazy null
      MacObjc(p, l, info, p.sel_registerName("thermalState"), p.sel_registerName("isLowPowerModeEnabled"))
    } catch (_: Throwable) { null }
  }

  /** `NSProcessInfo.thermalState`: nominal 0, fair 1, serious 2, critical 3. */
  private fun macThermalState(): Int? {
    val m = macObjc ?: return null
    return try { m.l.objc_msgSend(m.processInfo, m.sel).toInt().coerceIn(0, 3) } catch (_: Throwable) { null }
  }

  /** `NSProcessInfo.isLowPowerModeEnabled` (a BOOL: only the low byte of the answer counts). */
  private fun macLowPower(): Boolean? {
    val m = macObjc ?: return null
    return try { (m.l.objc_msgSend(m.processInfo, m.lowPowerSel) and 0xff) != 0L } catch (_: Throwable) { null }
  }

  /**
   * Whether the system is asking apps to save power: Low Power Mode (macOS), Battery Saver (Windows), the power-saver profile (Linux, when
   * power-profiles-daemon is there). False when the system does not say.
   */
  fun lowPower(os: Os = Platform.os): Boolean = try {
    when (os) {
      Os.MAC -> macLowPower() ?: false
      Os.WINDOWS -> Power.windowsBatterySaver() ?: false
      Os.LINUX -> cached("powerprofile") { if (powerProfilesMissing) null else try { run("powerprofilesctl", "get") } catch (_: java.io.IOException) { powerProfilesMissing = true; null } }?.trim() == "power-saver"
    }
  } catch (_: Throwable) { false }
  @Volatile private var powerProfilesMissing = false

  // ---- the person at the keyboard ---------------------------------------------------------------------------------------------------

  /** Seconds since the last key, mouse move or touch, or null when this system cannot say (Linux without a display server we can ask). */
  fun idleSeconds(os: Os = Platform.os): Double? = try {
    when (os) {
      Os.MAC -> macIdle() ?: parseIoregIdle(cached("ioreg-idle") { run("ioreg", "-c", "IOHIDSystem", "-d", "4") })
      Os.WINDOWS -> windowsIdle()
      Os.LINUX -> null
    }
  } catch (_: Throwable) { null }

  /** `ioreg -c IOHIDSystem`: `"HIDIdleTime" = 123456789` in nanoseconds. */
  fun parseIoregIdle(text: String?): Double? =
    text?.lineSequence()?.firstOrNull { it.contains("HIDIdleTime") }?.substringAfter('=')?.trim()?.toLongOrNull()?.let { it / 1e9 }

  private interface CoreGraphics : Library { fun CGEventSourceSecondsSinceLastEventType(state: Int, type: Int): Double }
  private val cg: CoreGraphics? by lazy { try { Native.load("CoreGraphics", CoreGraphics::class.java) } catch (_: Throwable) { null } }

  /** kCGEventSourceStateHIDSystemState (1), kCGAnyInputEventType (all ones): the time since any hardware input. */
  private fun macIdle(): Double? = cg?.let { c -> try { c.CGEventSourceSecondsSinceLastEventType(1, -1).takeIf { it >= 0 } } catch (_: Throwable) { null } }

  @Suppress("PropertyName")
  class LastInputInfo : Structure() {
    @JvmField var cbSize: Int = 8
    @JvmField var dwTime: Int = 0
    override fun getFieldOrder() = listOf("cbSize", "dwTime")
  }
  private interface User32 : Library { fun GetLastInputInfo(info: LastInputInfo): Boolean }
  private interface Kernel32Tick : Library { fun GetTickCount(): Int }

  private fun windowsIdle(): Double? {
    val info = LastInputInfo()
    if (!Native.load("user32", User32::class.java).GetLastInputInfo(info)) return null
    val now = Native.load("kernel32", Kernel32Tick::class.java).GetTickCount()
    return ((now - info.dwTime).toLong() and 0xffffffffL) / 1000.0
  }

  // ---- the battery ------------------------------------------------------------------------------------------------------------------

  /** The charge in percent, or null when there is no battery or the system does not say. */
  fun batteryPercent(os: Os = Platform.os): Int? = try {
    when (os) {
      Os.LINUX -> parseLinuxCapacity(File("/sys/class/power_supply"))
      Os.MAC -> parsePmsetPercent(cached("pmset-batt") { run("pmset", "-g", "batt") })
      Os.WINDOWS -> windowsPercent()
    }
  } catch (_: Throwable) { null }

  fun parsePmsetPercent(text: String?): Int? = text?.let { Regex("(\\d{1,3})%").find(it)?.groupValues?.get(1)?.toIntOrNull() }

  fun parseLinuxCapacity(dir: File): Int? {
    val batteries = dir.listFiles()?.filter { d -> runCatching { File(d, "type").readText().trim().equals("Battery", true) && File(d, "scope").let { !it.exists() || it.readText().trim() != "Device" } }.getOrDefault(false) } ?: return null
    val levels = batteries.mapNotNull { runCatching { File(it, "capacity").readText().trim().toInt() }.getOrNull() }
    return if (levels.isEmpty()) null else levels.average().toInt()
  }

  private fun windowsPercent(): Int? {
    val k = Native.load("kernel32", Power.Kernel32::class.java)
    val s = Power.SystemPowerStatus()
    if (!k.GetSystemPowerStatus(s)) return null
    val p = s.BatteryLifePercent.toInt() and 0xff
    return if (p in 0..100) p else null   // 255: unknown
  }

  // ---- helpers ----------------------------------------------------------------------------------------------------------------------

  private val answers = HashMap<String, Pair<Long, String?>>()

  /** A small program's answer, kept for ten seconds: heat and battery do not change faster, and a program started every second would cost more than the answer is worth. */
  @Synchronized private fun cached(key: String, ask: () -> String?): String? {
    val now = System.currentTimeMillis()
    answers[key]?.let { (at, v) -> if (now - at < 10_000) return v }
    return ask().also { answers[key] = now to it }
  }

  /**
   * A small program's output, or null when it fails or takes longer than [timeoutS]. The output is read on its own thread so that the
   * timeout holds even when the program hangs with its output open (a hung `pmset` must not hold up the reader); a late one is killed.
   */
  internal fun run(vararg cmd: String, timeoutS: Long = 3): String? {
    val p = Politeness.Cpu.track(ProcessBuilder(cmd.toList()).redirectErrorStream(true).start())
    var out: String? = null
    val reader = Thread { out = runCatching { p.inputStream.bufferedReader().readText() }.getOrNull() }.apply { isDaemon = true; start() }
    return try {
      if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
      reader.join(1_000)
      if (p.exitValue() == 0) out else null
    } catch (_: InterruptedException) {
      p.destroyForcibly(); Thread.currentThread().interrupt(); null
    }
  }
}
