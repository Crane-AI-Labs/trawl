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
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Finds out, without risking the app, whether the picture model can run on this computer's GPU. The GPU path can end the whole
 * process in native code (seen on a VM with no GPU: SIGSEGV), so it is tried in a child process on a tiny picture. A clean exit
 * means "gpu"; a crash, an error or a timeout means "cpu". The answer is written next to the models and reused until the model
 * file, the OS or the Java version changes (a driver update is covered by deleting `vision-backend.txt`, or by `TRAWL_VISION_GPU=1`).
 */
object GpuProbe {
  private const val TIMEOUT_S = 180L
  const val FLAG = "--gpu-probe"

  /**
   * [softwareOnly]: the computer has no Vulkan driver but software ones ([VulkanIcds]); then the answer is "cpu" without starting
   * anything, and it is not written down (a GPU driver installed later is noticed the next time).
   */
  fun usable(model: File, answer: File, run: (File) -> Boolean = ::tryInChild, softwareOnly: () -> Boolean = { VulkanIcds.onlySoftwareHere() }): Boolean {
    val key = "${model.name}:${model.length()}:${System.getProperty("os.name")}:${System.getProperty("java.version")}"
    runCatching {
      val lines = answer.takeIf { it.isFile }?.readLines()
      if (lines != null && lines.size >= 2 && lines[0] == key) return lines[1] == "gpu"
    }
    if (runCatching(softwareOnly).getOrDefault(false)) return false
    val ok = run(model)
    runCatching { answer.parentFile?.mkdirs(); answer.writeText("$key\n${if (ok) "gpu" else "cpu"}\n") }
    return ok
  }

  /**
   * The JVM flags that make a probe crash cheap. A crashing JVM writes `hs_err_pid<n>.log` in the working folder (the person's home) and then
   * aborts, which on Ubuntu hands a core of the whole process to apport (1 GB, about 95 s while the app waited for the child to be reaped). So
   * the crash report goes to Trawl's own logs folder, and no core is asked for: the child just ends and the answer is "cpu" at once.
   */
  fun crashFlags(logsDir: File): List<String> = listOf("-XX:ErrorFile=${File(logsDir, "hs_err_gpu_probe_%p.log").path}", "-XX:-CreateCoredumpOnCrash")

  /** The child's command line: a plain `java` when the runtime has one, else the app's own launcher (jpackage's macOS runtime has no bin/java). */
  fun childCommand(model: File, javaHome: String, classPath: String, launcher: () -> String, logsDir: File, os: Os = Platform.os): List<String> {
    val java = File(javaHome, "bin/" + if (os == Os.WINDOWS) "java.exe" else "java")
    // jpackage's macOS runtime has no bin/java (found on the first real Mac build: the child never started, so the answer was always "cpu").
    // There the child is the app's own launcher, which hands "--gpu-probe <model>" to main() before any window opens.
    return if (java.isFile) listOf(java.absolutePath, "-Xmx768m") + crashFlags(logsDir) + listOf("-cp", classPath, "trawl.desktop.GpuProbe", model.absolutePath)
    else listOf(launcher(), FLAG, model.absolutePath)
  }

  private fun tryInChild(model: File): Boolean = try {
    val logs = File(Platform.dataDir(), "logs").also { it.mkdirs() }
    val cmd = childCommand(model, System.getProperty("java.home"), System.getProperty("java.class.path"), { ProcessHandle.current().info().command().orElseThrow() }, logs)
    val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
    // The launcher path cannot take JVM flags on its command line; they go in through the environment instead (a flag with a space in its
    // path, as macOS's "Application Support" has, is left out: JAVA_TOOL_OPTIONS splits on spaces).
    if (cmd.getOrNull(1) == FLAG) pb.environment()["JAVA_TOOL_OPTIONS"] = crashFlags(logs).filter { f -> f.none { it.isWhitespace() } }.joinToString(" ")
    val p = pb.start()
    val done = p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)
    if (!done) p.destroyForcibly()
    done && p.exitValue() == 0
  } catch (_: Exception) { false }

  /** The child process: build the engine with the GPU vision backend and embed one 64 x 64 picture. Exit 0 only if that worked. */
  @JvmStatic fun main(args: Array<String>) {
    val code = try {
      val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
      val cache = File.createTempFile("gpuprobe", "").let { it.delete(); it.also { d -> d.mkdirs() } }
      val e = EmbeddingEngine(
        EmbeddingEngineConfig(
          modelPath = args[0], backend = Backend.CPU(threadCount = null), visionBackend = Backend.GPU(),
          cacheDir = cache.absolutePath, visionTokensPerImage = VISION_TOKENS,
        )
      ).also { it.initialize() }
      val v = e.computeEmbedding(listOf(InputData.Image(jpeg)), EmbeddingOptions(normalize = true, visionTokensPerImage = VISION_TOKENS)).embedding
      e.close(); cache.deleteRecursively()
      if (v.isNotEmpty() && v.all { it.isFinite() }) 0 else 2
    } catch (_: Throwable) { 3 }
    System.exit(code)
  }
}
