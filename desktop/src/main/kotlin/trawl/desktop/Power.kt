package trawl.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.io.File

/**
 * Whether the computer is running on its battery, where the OS says so. Used by the Gentle work speed to wait for the charger.
 * Unknown (a desktop with no battery, a tool that is not there, an answer we cannot read) is `null` and is treated as "plugged in":
 * Trawl never waits for a charger it cannot see. The three readers are small and their parsers are pure, so each OS's answer is
 * unit-tested on every machine.
 */
object Power {
  /** True on battery, false on mains power, null when it cannot be told. */
  fun onBattery(os: Os = Platform.os): Boolean? = try {
    when (os) {
      Os.LINUX -> parseLinux(File("/sys/class/power_supply"))
      Os.MAC -> parsePmset(run(listOf("pmset", "-g", "batt")))
      Os.WINDOWS -> parseWindows(windowsAcLineStatus())
    }
  } catch (_: Throwable) {
    null
  }

  /**
   * Linux: the folders under `/sys/class/power_supply`. A supply of type "Mains" (or "USB"...) with `online` = 1 means plugged in; a "Battery"
   * with status "Discharging" and no online mains means on battery. No Battery entry means a desktop machine: null.
   */
  fun parseLinux(dir: File): Boolean? {
    val supplies = dir.listFiles()?.filter { it.isDirectory } ?: return null
    fun read(d: File, n: String) = runCatching { File(d, n).readText().trim() }.getOrNull()
    val battery = supplies.filter { read(it, "type").equals("Battery", ignoreCase = true) && read(it, "scope") != "Device" }
    if (battery.isEmpty()) return null
    val mainsOnline = supplies.any { s -> read(s, "type")?.lowercase() in MAINS_TYPES && read(s, "online") == "1" }
    if (mainsOnline) return false
    val discharging = battery.any { read(it, "status").equals("Discharging", ignoreCase = true) }
    return if (discharging) true else if (battery.any { read(it, "status") != null }) false else null
  }

  /** macOS `pmset -g batt`: the first line says "Now drawing from 'Battery Power'" or "'AC Power'". */
  fun parsePmset(text: String?): Boolean? {
    val line = text?.lineSequence()?.firstOrNull { it.contains("drawing from", ignoreCase = true) } ?: return null
    return when {
      line.contains("Battery Power", ignoreCase = true) -> true
      line.contains("AC Power", ignoreCase = true) -> false
      else -> null
    }
  }

  /** Windows `SYSTEM_POWER_STATUS.ACLineStatus`: 0 offline (on battery), 1 online, 255 unknown. A machine with no battery reports 1. */
  fun parseWindows(acLineStatus: Int?): Boolean? = when (acLineStatus) {
    0 -> true
    1 -> false
    else -> null
  }

  private val MAINS_TYPES = setOf("mains", "usb", "usb_c", "usb_pd", "usb_dcp", "usb_cdp", "ac", "wireless")

  private fun run(cmd: List<String>): String? {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    return if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) out else null
  }

  @Suppress("PropertyName")
  class SystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
    override fun getFieldOrder() = listOf("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime")
  }

  interface Kernel32 : Library {
    fun GetSystemPowerStatus(status: SystemPowerStatus): Boolean
  }

  private fun windowsAcLineStatus(): Int? {
    val k = Native.load("kernel32", Kernel32::class.java)
    val s = SystemPowerStatus()
    return if (k.GetSystemPowerStatus(s)) s.ACLineStatus.toInt() and 0xff else null
  }
}

/** Remembers the answer for a while: asking the OS for it on every file would cost more than the answer is worth. */
class BatteryWatch(private val ttlMs: Long = 20_000, private val ask: () -> Boolean? = { Power.onBattery() }, private val now: () -> Long = System::currentTimeMillis) {
  private var at = Long.MIN_VALUE
  private var last: Boolean? = null

  @Synchronized fun onBattery(): Boolean? {
    val t = now()
    if (at == Long.MIN_VALUE || t - at >= ttlMs) { last = ask(); at = t }
    return last
  }
}
