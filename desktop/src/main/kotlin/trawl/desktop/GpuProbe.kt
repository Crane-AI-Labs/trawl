package trawl.desktop

import com.craneai.findr.engine.Embedder.Companion.VISION_TOKENS
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Finds out, without risking the app, whether the picture model can run on this computer's GPU. The GPU path can end the whole
 * process in native code (seen on a VM with no GPU: SIGSEGV), so it is tried in a child process on a tiny picture. A clean exit
 * means "gpu"; a crash, an error or a timeout means "cpu". The answer is written next to the models and reused until the model
 * file, the OS or the Java version changes (a driver update is covered by deleting `vision-backend.txt`, or by `TRAWL_VISION_GPU=1`).
 *
 * When [cacheDir] is supplied, the child writes its compiled XNNPACK / Metal / Vulkan / D3D12 caches into a sibling staging
 * directory; on clean exit (0) those files are promoted into [cacheDir] so [DesktopEmbedder] skips recompiling from scratch
 * 100 ms later. On crash, timeout, or non-zero exit the staging directory is deleted without touching [cacheDir].
 */
object GpuProbe {
  private const val TIMEOUT_S = 180L
  const val FLAG = "--gpu-probe"
  private val probeLock = Any()

  /**
   * [softwareOnly]: the computer has no Vulkan driver but software ones ([VulkanIcds]); then the answer is "cpu" without starting
   * anything, and it is not written down (a GPU driver installed later is noticed the next time).
   */
  fun usable(
    model: File,
    answer: File,
    run: ((File) -> Boolean)? = null,
    softwareOnly: () -> Boolean = { VulkanIcds.onlySoftwareHere() },
    cacheDir: File? = null,
  ): Boolean = synchronized(probeLock) {
    val key = "${model.name}:${model.length()}:${System.getProperty("os.name")}:${System.getProperty("java.version")}"
    runCatching {
      val lines = answer.takeIf { it.isFile }?.readLines()
      if (lines != null && lines.size >= 2 && lines[0] == key) return@synchronized lines[1] == "gpu"
    }
    if (runCatching(softwareOnly).getOrDefault(false)) return@synchronized false
    val ok = if (run != null) run(model) else tryInChild(model, cacheDir)
    writeAnswerAtomically(answer, "$key\n${if (ok) "gpu" else "cpu"}\n")
    ok
  }

  private fun writeAnswerAtomically(answer: File, content: String) {
    runCatching {
      val parent = (answer.absoluteFile.parentFile ?: answer.absoluteFile).also { it.mkdirs() }
      val tmp = Files.createTempFile(parent.toPath(), "${answer.name}.", ".tmp")
      try {
        Files.writeString(tmp, content)
        runCatching {
          Files.move(tmp, answer.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
          Files.move(tmp, answer.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
      } catch (t: Throwable) {
        runCatching { Files.deleteIfExists(tmp) }
        throw t
      }
    }
  }

  /**
   * The JVM flags that make a probe crash cheap. A crashing JVM writes `hs_err_pid<n>.log` in the working folder (the person's home) and then
   * aborts, which on Ubuntu hands a core of the whole process to apport (1 GB, about 95 s while the app waited for the child to be reaped). So
   * the crash report goes to Trawl's own logs folder, and no core is asked for: the child just ends and the answer is "cpu" at once.
   */
  fun crashFlags(logsDir: File): List<String> = listOf("-XX:ErrorFile=${File(logsDir, "hs_err_gpu_probe_%p.log").path}", "-XX:-CreateCoredumpOnCrash")

  /** The child's command line: a plain `java` when the runtime has one, else the app's own launcher (jpackage's macOS runtime has no bin/java). */
  fun childCommand(
    model: File, javaHome: String, classPath: String, launcher: () -> String, logsDir: File,
    os: Os = Platform.os, stagingDir: File? = null,
  ): List<String> {
    val java = File(javaHome, "bin/" + if (os == Os.WINDOWS) "java.exe" else "java")
    val tail = listOfNotNull(model.absolutePath, stagingDir?.absolutePath)
    // jpackage's macOS runtime has no bin/java (found on the first real Mac build: the child never started, so the answer was always "cpu").
    // There the child is the app's own launcher, which hands "--gpu-probe <model> [stagingDir]" to main() before any window opens.
    return if (java.isFile) listOf(java.absolutePath, "-Xmx768m") + crashFlags(logsDir) + listOf("-cp", classPath, "trawl.desktop.GpuProbe") + tail
    else listOf(launcher(), FLAG) + tail
  }

  /**
   * Creates a sibling staging directory next to [cacheDir] while [probe] runs. On clean exit (`true`), compiled cache files
   * are promoted into [cacheDir]; on failure, crash, timeout, or exception, the staging directory is deleted cleanly.
   */
  internal fun runWithStagingCache(cacheDir: File?, probe: (stagingDir: File?) -> Boolean): Boolean {
    val staging = cacheDir?.let { target ->
      val parent = (target.absoluteFile.parentFile ?: target.absoluteFile).also { it.mkdirs() }
      Files.createTempDirectory(parent.toPath(), "${target.name}.probe-").toFile()
    }
    return try {
      val ok = probe(staging)
      if (ok && staging != null) promoteCache(staging, cacheDir)
      ok
    } finally {
      staging?.deleteRecursively()
    }
  }

  internal fun promoteCache(staging: File, target: File) {
    if (!staging.isDirectory) return
    target.mkdirs()
    val srcRoot = staging.toPath()
    val dstRoot = target.toPath()
    Files.walk(srcRoot).use { stream ->
      stream.forEach { src ->
        if (Files.isRegularFile(src)) {
          val dest = dstRoot.resolve(srcRoot.relativize(src))
          dest.parent?.let { Files.createDirectories(it) }
          runCatching {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
          }.recoverCatching {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING)
          }.recoverCatching {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
          }
        }
      }
    }
  }

  internal fun awaitChild(p: Process, timeoutS: Long = TIMEOUT_S): Boolean {
    var interrupted = false
    val done = try {
      p.waitFor(timeoutS, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      interrupted = true
      false
    } finally {
      if (p.isAlive) {
        p.destroyForcibly()
        runCatching { p.waitFor(5, TimeUnit.SECONDS) }
      }
      if (interrupted) Thread.currentThread().interrupt()
    }
    return done && !p.isAlive && runCatching { p.exitValue() == 0 }.getOrDefault(false)
  }

  private fun tryInChild(model: File, cacheDir: File? = null): Boolean = try {
    runWithStagingCache(cacheDir) { staging ->
      val logs = File(Platform.dataDir(), "logs").also { it.mkdirs() }
      val cmd = childCommand(model, System.getProperty("java.home"), System.getProperty("java.class.path"), { ProcessHandle.current().info().command().orElseThrow() }, logs, stagingDir = staging)
      val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
      // The launcher path cannot take JVM flags on its command line; they go in through the environment instead (a flag with a space in its
      // path, as macOS's "Application Support" has, is left out: JAVA_TOOL_OPTIONS splits on spaces).
      if (cmd.getOrNull(1) == FLAG) pb.environment()["JAVA_TOOL_OPTIONS"] = crashFlags(logs).filter { f -> f.none { it.isWhitespace() } }.joinToString(" ")
      val p = Politeness.Cpu.track(pb.start())
      awaitChild(p, TIMEOUT_S)
    }
  } catch (_: Exception) { false }

  /** The child process: build the engine with the GPU vision backend and embed one 64 x 64 picture. Exit 0 only if that worked. */
  @JvmStatic fun main(args: Array<String>) {
    val externalCache = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { File(it).also { d -> d.mkdirs() } }
    val ownedTempCache = if (externalCache == null) File.createTempFile("gpuprobe", "").let { it.delete(); it.also { d -> d.mkdirs() } } else null
    val cache = externalCache ?: ownedTempCache!!
    val code = try {
      val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
      val e = EmbeddingEngine(
        EmbeddingEngineConfig(
          modelPath = args[0], backend = Backend.CPU(threadCount = null), visionBackend = Backend.GPU(),
          cacheDir = cache.absolutePath, visionTokensPerImage = VISION_TOKENS,
        )
      ).also { it.initialize() }
      val v = e.computeEmbedding(listOf(InputData.Image(jpeg)), EmbeddingOptions(normalize = true, visionTokensPerImage = VISION_TOKENS)).embedding
      e.close()
      if (v.isNotEmpty() && v.all { it.isFinite() }) 0 else 2
    } catch (_: Throwable) { 3 } finally {
      // If the parent passed a staging directory, the parent owns promotion (on exit 0) or deletion (on failure/crash).
      ownedTempCache?.deleteRecursively()
    }
    System.exit(code)
  }
}
