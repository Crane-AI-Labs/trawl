package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuProbeTest {
  @Test fun theAnswerIsKeptAndReusedUntilTheModelChanges() {
    val d = Files.createTempDirectory("trawl-probe").toFile()
    val model = File(d, "m.litertlm").apply { writeText("model") }
    val answer = File(d, "vision-backend.txt")
    var runs = 0
    val crash = { _: File -> runs++; false }
    val works = { _: File -> runs++; true }
    assertFalse(GpuProbe.usable(model, answer, crash, softwareOnly = { false })); assertEquals(1, runs)
    assertFalse(GpuProbe.usable(model, answer, works, softwareOnly = { false })); assertEquals(1, runs)   // remembered: not tried again
    model.writeText("a different, longer model file")
    assertTrue(GpuProbe.usable(model, answer, works, softwareOnly = { false })); assertEquals(2, runs)   // a new model file is probed again
    d.deleteRecursively()
  }

  @Test fun aComputerWithOnlySoftwareVulkanGoesStraightToTheCpuWithoutStartingTheProbe() {
    val d = Files.createTempDirectory("trawl-probe").toFile()
    val model = File(d, "m.litertlm").apply { writeText("model") }
    val answer = File(d, "vision-backend.txt")
    var runs = 0
    assertFalse(GpuProbe.usable(model, answer, { runs++; true }, softwareOnly = { true }))
    assertEquals(0, runs, "the child was never started")
    assertFalse(answer.exists(), "not written down: a GPU driver installed later is noticed")
    assertTrue(GpuProbe.usable(model, answer, { runs++; true }, softwareOnly = { false }))
    assertEquals(1, runs)
    d.deleteRecursively()
  }

  @Test fun theChildIsStartedWithACrashReportInTrawlsLogsAndNoCoreFile() {
    val java = Files.createTempDirectory("jh").toFile().also { File(it, "bin").mkdirs(); File(it, "bin/java").writeText("") }
    val logs = File("/data/trawl/logs")
    val cmd = GpuProbe.childCommand(File("/m/model.litertlm"), java.path, "cp.jar", { "/opt/trawl/bin/Trawl" }, logs, Os.LINUX)
    assertTrue("-XX:-CreateCoredumpOnCrash" in cmd)
    assertTrue(cmd.any { it.startsWith("-XX:ErrorFile=") && it.endsWith("hs_err_gpu_probe_%p.log") && it.contains("logs") }, "$cmd")
    assertEquals("trawl.desktop.GpuProbe", cmd[cmd.indexOf("-cp") + 2])
    java.deleteRecursively()
    // No bin/java (the macOS runtime): the app's own launcher, told by a flag.
    val mac = GpuProbe.childCommand(File("/m/model.litertlm"), "/nonexistent", "cp.jar", { "/Applications/Trawl.app/Contents/MacOS/Trawl" }, logs, Os.MAC)
    assertEquals(listOf("/Applications/Trawl.app/Contents/MacOS/Trawl", "--gpu-probe", "/m/model.litertlm"), mac)
  }

  private fun icd(vararg names: Pair<String, String>) = names.map { VulkanIcds.Icd(it.first, it.second) }

  @Test fun softwareVulkanDriversAreToldFromHardwareOnes() {
    assertTrue(VulkanIcds.onlySoftware(icd("lvp_icd.x86_64.json" to "libvulkan_lvp.so")))
    assertTrue(VulkanIcds.onlySoftware(icd("lvp_icd.json" to "libvulkan_lvp.so", "swiftshader_icd.json" to "libvk_swiftshader.so")))
    assertTrue(VulkanIcds.onlySoftware(icd("custom.json" to "libvulkan_llvmpipe.so")))
    assertFalse(VulkanIcds.onlySoftware(icd("lvp_icd.json" to "libvulkan_lvp.so", "nvidia_icd.json" to "libGLX_nvidia.so.0")), "one real GPU is enough to try")
    assertFalse(VulkanIcds.onlySoftware(icd("intel_icd.x86_64.json" to "libvulkan_intel.so")))
    assertFalse(VulkanIcds.onlySoftware(icd("radeon_icd.x86_64.json" to "libvulkan_radeon.so")))
    assertFalse(VulkanIcds.onlySoftware(emptyList()), "no list at all: cannot tell, so the probe decides")
  }

  @Test fun theDriverListIsReadFromTheLoadersFoldersOrTheEnvironment() {
    val files = mapOf(
      "/usr/share/vulkan/icd.d/lvp_icd.json" to """{ "file_format_version": "1.0.0", "ICD": { "library_path": "libvulkan_lvp.so", "api_version": "1.3" } }""",
    )
    val read = { f: File -> files[f.path] }
    val list = { d: File -> files.keys.filter { File(it).parent == d.path }.map(::File) }
    val found = VulkanIcds.installed(emptyMap(), "/home/u", read, list)
    assertEquals(listOf(VulkanIcds.Icd("lvp_icd.json", "libvulkan_lvp.so")), found)
    assertTrue(VulkanIcds.onlySoftware(found))
    // VK_DRIVER_FILES names the files itself and the folders are not searched.
    val named = VulkanIcds.installed(mapOf("VK_DRIVER_FILES" to "/usr/share/vulkan/icd.d/lvp_icd.json:/nope.json"), "/home/u", read, { emptyList() })
    assertEquals(1, named.size)
    assertEquals(Os.LINUX, Platform.detect("Linux"))
    assertFalse(VulkanIcds.onlySoftwareHere(Os.MAC), "other systems have no such list")
  }
}
