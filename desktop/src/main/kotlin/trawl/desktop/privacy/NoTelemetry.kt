package trawl.desktop.privacy

import ai.onnxruntime.OrtEnvironment
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/**
 * Keeps ONNX Runtime from phoning home. Trawl's promise is that nothing leaves the computer, and ONNX Runtime 1.29 to 1.31 on macOS, Linux and
 * Android-class builds carried a Microsoft telemetry client ("1DS" / OneCollector) that is ON by default: about ten seconds after the first
 * environment is created it opens a TCP connection to `mobile.events.data.microsoft.com:443` and uploads usage events (see
 * internal/research/desktop-network-and-jvm.md). The call `OrtEnvironment.setTelemetry(false)` does NOT stop it; only the environment variable
 * `ORT_DISABLE_TELEMETRY=1`, read when the library initialises, does.
 *
 * Three layers, so no single slip can bring it back:
 *  1. The build pins ONNX Runtime 1.28.0, which has no such client in it at all (`checkNoTelemetryInNatives` in desktop/build.gradle.kts fails
 *     the build if any native library Trawl ships holds the client's strings).
 *  2. [apply] sets `ORT_DISABLE_TELEMETRY=1` in the real process environment (the one the C library reads, which JVM code cannot change)
 *     before ONNX Runtime is first touched, through JNA's `setenv`.
 *  3. [environment] also calls `setTelemetry(false)` after creating the environment.
 *
 * The release gate (desktop/scripts/net-gate.py) is the proof: it fails when the running app opens any network connection.
 */
object NoTelemetry {
  const val VARIABLE = "ORT_DISABLE_TELEMETRY"

  private interface Libc : Library { fun setenv(name: String, value: String, overwrite: Int): Int }
  private interface Msvcrt : Library { fun _putenv_s(name: String, value: String): Int }
  private interface Kernel32 : Library { fun SetEnvironmentVariableW(name: com.sun.jna.WString, value: com.sun.jna.WString): Boolean }

  @Volatile private var applied = false

  /** Sets the variable in the process environment. Safe to call many times; true when the system accepted it. */
  @Synchronized fun apply(): Boolean {
    if (applied) return true
    val ok = runCatching {
      if (Platform.isWindows()) {
        val a = Native.load("ucrtbase", Msvcrt::class.java)._putenv_s(VARIABLE, "1") == 0
        val b = Native.load("kernel32", Kernel32::class.java).SetEnvironmentVariableW(com.sun.jna.WString(VARIABLE), com.sun.jna.WString("1"))
        a || b
      } else {
        Native.load("c", Libc::class.java).setenv(VARIABLE, "1", 1) == 0
      }
    }.getOrDefault(false)
    applied = ok
    return ok
  }

  /** The one ONNX Runtime environment of the process, made with telemetry off. */
  fun environment(): OrtEnvironment {
    apply()
    return OrtEnvironment.getEnvironment("trawl-privacy").also { runCatching { it.setTelemetry(false) } }
  }
}
