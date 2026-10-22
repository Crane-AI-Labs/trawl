package trawl.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import java.io.File
import trawl.desktop.privacy.NoTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Java runtime is named in the report, the safe minimum is one number in two places, and ONNX Runtime is told not to phone home. */
class RuntimeAndTelemetryTest {
  private val facts = Diagnostics.Facts(
    os = "Linux", osVersion = "6.8", arch = "amd64", cores = 8, ramMb = 31_000, ocr = "Tesseract", vision = "cpu", textThreads = null,
    speed = "balanced", language = "en", folders = 2, skippedFolders = 1, photosOn = true, screenshotsOn = false, documentsOn = true,
    photos = 10, screenshots = 3, documents = 7, unreadable = 1, indexBytes = 5_000_000, modelBytes = 700_000_000, ratePerMin = 42.4,
    models = null, errors = emptyList(),
  )

  @Test fun versionsAreReadAndComparedByNumber() {
    assertEquals(listOf(21, 0, 12, 1), RuntimeInfo.numbers("21.0.12.1+1-LTS"))
    assertEquals(listOf(21, 0, 0, 0), RuntimeInfo.numbers("21+35"))
    assertTrue(RuntimeInfo.atLeast("21.0.12.1+1-LTS"))
    assertTrue(RuntimeInfo.atLeast("21.0.8+9-LTS"))
    assertTrue(RuntimeInfo.atLeast("21.0.8"))
    assertTrue(RuntimeInfo.atLeast("22.0.1+8"))
    assertTrue(RuntimeInfo.atLeast("21.0.10+7", min = "21.0.8"))
  }

  @Test fun theRuntimesThatCrashedAreNotSafe() {
    assertFalse(RuntimeInfo.atLeast("21+35"), "21.0.0 is the runtime that aborted after 92 minutes")
    assertFalse(RuntimeInfo.atLeast("21.0.7+6-LTS"))
    assertFalse(RuntimeInfo.atLeast("21.0.3+9-LTS"), "21.0.3 has the first fix only; JDK-8335662 arrived in 21.0.8")
    assertFalse(RuntimeInfo.atLeast("17.0.14+7"))
    assertFalse(RuntimeInfo.atLeast("unknown"))
    assertFalse(RuntimeInfo.atLeast(""))
  }

  @Test fun theReportAndTheScreensNameTheRuntime() {
    val line = Diagnostics.report(facts).lines().first { it.startsWith("Java runtime: ") }
    assertEquals("Java runtime: ${RuntimeInfo.display}", line)
    assertTrue(RuntimeInfo.version in line)
    assertTrue("Java runtime: Eclipse Adoptium 21.0.12.1+1-LTS" in Diagnostics.report(facts.copy(runtime = "Eclipse Adoptium 21.0.12.1+1-LTS")))
  }

  @Test fun theMinimumIsTheSameNumberInTheBuildAndInTheApp() {
    val gradle = File("build.gradle.kts").readText()
    val v = Regex("""MIN_SAFE_JDK\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
    assertEquals(v, RuntimeInfo.MIN_SAFE, "RuntimeInfo.MIN_SAFE and MIN_SAFE_JDK in build.gradle.kts must be changed together")
  }

  @Test fun theBuildPinsAnOnnxRuntimeWithoutTheTelemetryClient() {
    val gradle = File("build.gradle.kts").readText()
    val v = Regex("""com\.microsoft\.onnxruntime:onnxruntime:([0-9.]+)""").find(gradle)?.groupValues?.get(1)!!
    val (major, minor) = v.split('.').map { it.toInt() }
    assertTrue(major == 1 && minor <= 28, "ONNX Runtime $v: 1.29 and later carry Microsoft's 1DS client; stay at 1.28 or lower (see NoTelemetry)")
  }

  private interface Libc : Library { fun getenv(name: String): String? }

  @Test fun theTelemetryVariableIsSetInTheRealProcessEnvironment() {
    if (Platform.isWindows()) return   // the Windows ONNX Runtime has no such client; the call is still made there, and the gate watches the sockets
    assertTrue(NoTelemetry.apply())
    assertEquals("1", Native.load("c", Libc::class.java).getenv(NoTelemetry.VARIABLE))
  }

  @Test fun noSourceFileMakesAnOnnxEnvironmentAnyOtherWay() {
    val offenders = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" && it.name != "NoTelemetry.kt" }
      .filter { "OrtEnvironment.getEnvironment" in it.readText() }.map { it.name }.toList()
    assertEquals(emptyList(), offenders, "make the environment through NoTelemetry.environment()")
  }
}
