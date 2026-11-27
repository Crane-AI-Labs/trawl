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
    // When a staging cache dir is provided, both command forms append it so compiled caches can be promoted on exit 0.
    val staging = File("/data/trawl/engine-cache.probe-123")
    val macStaged = GpuProbe.childCommand(File("/m/model.litertlm"), "/nonexistent", "cp.jar", { "/Applications/Trawl.app/Contents/MacOS/Trawl" }, logs, Os.MAC, stagingDir = staging)
    assertEquals(listOf("/Applications/Trawl.app/Contents/MacOS/Trawl", "--gpu-probe", "/m/model.litertlm", staging.absolutePath), macStaged)
  }

  @Test fun cleanExitPromotesCompiledCacheAndDeletesStagingDir() {
    val root = Files.createTempDirectory("trawl-probe-promote").toFile()
    val cacheDir = File(root, "engine-cache")
    var seenStaging: File? = null
    val ok = GpuProbe.runWithStagingCache(cacheDir) { staging ->
      seenStaging = staging
      assertTrue(staging != null && staging.isDirectory)
      assertEquals(root.canonicalFile, staging.parentFile.canonicalFile, "staging must be a sibling of cacheDir for atomic move")
      File(staging, "model.litertlm.xnnpack_cache").writeText("compiled-cpu")
      File(staging, "metal/vision_weight_cache.bin").apply { parentFile.mkdirs(); writeText("compiled-gpu") }
      true
    }
    assertTrue(ok)
    assertEquals("compiled-cpu", File(cacheDir, "model.litertlm.xnnpack_cache").readText())
    assertEquals("compiled-gpu", File(cacheDir, "metal/vision_weight_cache.bin").readText())
    assertFalse(seenStaging!!.exists(), "staging dir must be cleaned up after promotion")
    root.deleteRecursively()
  }

  @Test fun crashOrFailureDeletesStagingDirWithoutTouchingCacheDir() {
    val root = Files.createTempDirectory("trawl-probe-crash").toFile()
    val cacheDir = File(root, "engine-cache").apply { mkdirs() }
    val existing = File(cacheDir, "existing.cache").apply { writeText("good") }
    var seenStaging: File? = null
    val ok = GpuProbe.runWithStagingCache(cacheDir) { staging ->
      seenStaging = staging
      File(staging!!, "corrupt.cache").writeText("partial")
      false
    }
    assertFalse(ok)
    assertFalse(seenStaging!!.exists(), "staging dir must be deleted on crash/non-zero exit")
    assertFalse(File(cacheDir, "corrupt.cache").exists(), "partial files must never be promoted")
    assertEquals("good", existing.readText())

    var thrownStaging: File? = null
    val threw = runCatching {
      GpuProbe.runWithStagingCache(cacheDir) { staging ->
        thrownStaging = staging
        File(staging!!, "boom.cache").writeText("partial")
        error("probe boom")
      }
    }.isFailure
    assertTrue(threw)
    assertFalse(thrownStaging!!.exists(), "staging dir must be deleted even on exception")
    assertFalse(File(cacheDir, "boom.cache").exists())
    root.deleteRecursively()
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

  @Test fun concurrentUsableCallsSerializeAndWriteAnswerAtomically() {
    val d = Files.createTempDirectory("trawl-probe-concurrent").toFile()
    val model = File(d, "m.litertlm").apply { writeText("model-bytes") }
    val answer = File(d, "vision-backend.txt")
    val runs = java.util.concurrent.atomic.AtomicInteger(0)
    val startLatch = java.util.concurrent.CountDownLatch(1)
    val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    val threads = (1..4).map {
      Thread {
        startLatch.await()
        results += GpuProbe.usable(
          model,
          answer,
          run = {
            runs.incrementAndGet()
            Thread.sleep(60)
            true
          },
          softwareOnly = { false },
        )
      }.apply { start() }
    }
    startLatch.countDown()
    threads.forEach { it.join(5_000) }
    assertEquals(1, runs.get(), "probeLock must serialize concurrent usable() calls so only one probes")
    assertEquals(listOf(true, true, true, true), results)
    assertTrue(answer.isFile)
    assertEquals(listOf("m.litertlm", "vision-backend.txt"), d.list()!!.sorted(), "atomic answer write must leave no .tmp files behind")
    d.deleteRecursively()
  }

  @Test fun awaitChildReapsTimedOutAndInterruptedProcess() {
    if (Platform.os == Os.WINDOWS) return
    val timedOut = ProcessBuilder("sleep", "30").start()
    assertFalse(GpuProbe.awaitChild(timedOut, timeoutS = 0))
    assertFalse(timedOut.isAlive, "timed-out child must be forcibly destroyed and reaped before returning")

    val interruptedProc = ProcessBuilder("sleep", "30").start()
    var result: Boolean? = null
    var flagPreserved = false
    val t = Thread {
      result = GpuProbe.awaitChild(interruptedProc, timeoutS = 30)
      flagPreserved = Thread.currentThread().isInterrupted
    }
    t.start()
    Thread.sleep(80)
    t.interrupt()
    t.join(5_000)
    assertEquals(false, result)
    assertFalse(interruptedProc.isAlive, "interrupted child must be forcibly destroyed and reaped before returning")
    assertTrue(flagPreserved, "awaitChild must restore the thread interrupt flag")
  }
}
