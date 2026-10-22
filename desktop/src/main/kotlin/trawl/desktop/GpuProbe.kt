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

  fun usable(model: File, answer: File, run: (File) -> Boolean = ::tryInChild): Boolean {
    val key = "${model.name}:${model.length()}:${System.getProperty("os.name")}:${System.getProperty("java.version")}"
    runCatching {
      val lines = answer.takeIf { it.isFile }?.readLines()
      if (lines != null && lines.size >= 2 && lines[0] == key) return lines[1] == "gpu"
    }
    val ok = run(model)
    runCatching { answer.parentFile?.mkdirs(); answer.writeText("$key\n${if (ok) "gpu" else "cpu"}\n") }
    return ok
  }

  private fun tryInChild(model: File): Boolean = try {
    val java = File(System.getProperty("java.home"), "bin/" + if (Platform.os == Os.WINDOWS) "java.exe" else "java")
    // jpackage's macOS runtime has no bin/java (found on the first real Mac build: the child never started, so the answer was always "cpu").
    // There the child is the app's own launcher, which hands "--gpu-probe <model>" to main() before any window opens.
    val cmd = if (java.isFile) listOf(java.absolutePath, "-Xmx768m", "-cp", System.getProperty("java.class.path"), "trawl.desktop.GpuProbe", model.absolutePath)
    else listOf(ProcessHandle.current().info().command().orElseThrow(), FLAG, model.absolutePath)
    val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
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
