package trawl.desktop

import com.craneai.findr.engine.Embedder.Companion.QUERY_PREFIX
import com.craneai.findr.engine.Embedder.Companion.VISION_TOKENS
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File

/**
 * EmbeddingGemma 2 on LiteRT-LM's JVM build: the same `.litertlm` file, the same prefixes and the same options as the phone's
 * LiteRtEmbedder (text on CPU, pictures on the GPU (Metal here) at 70 vision tokens, 768-d, normalised). One engine, one lock.
 */
class DesktopEmbedder(
  private val model: File,
  private val cacheDir: File,
  /** null = decide by trying it safely (see [GpuProbe]); true or false forces it. `TRAWL_VISION_CPU` / `TRAWL_VISION_GPU` set it from outside. */
  visionGpu: Boolean? = when { System.getenv("TRAWL_VISION_CPU") != null -> false; System.getenv("TRAWL_VISION_GPU") != null -> true; else -> null },
) : AutoCloseable {
  private var engine: EmbeddingEngine? = null

  /**
   * The Save data pack's 270m model reads text only. It gets no vision backend (a vision budget without one is refused), the GPU
   * probe is skipped, and [image] is not available: pictures are read through the words in them instead ([Indexer]).
   */
  val textOnly: Boolean = ModelSetup.isTextOnly(model)
  private val options = if (textOnly) EmbeddingOptions(normalize = true) else EmbeddingOptions(normalize = true, visionTokensPerImage = VISION_TOKENS)
  private val wantGpu = visionGpu
  /** CPU threads for the text side: null leaves it to LiteRT-LM (the Balanced speed, how Trawl has always run). */
  @Volatile private var threads: Int? = null
  var initMs = 0L; private set

  /** Which vision backend ended up in use ("gpu" or "cpu"). */
  var visionUsed = if (textOnly) "none" else if (visionGpu == true) "gpu" else "cpu"; private set

  private fun create(gpu: Boolean) = EmbeddingEngine(
    EmbeddingEngineConfig(
      modelPath = model.absolutePath,
      backend = Backend.CPU(threadCount = threads),
      visionBackend = if (textOnly) null else if (gpu) Backend.GPU() else Backend.CPU(),
      cacheDir = cacheDir.absolutePath,
      visionTokensPerImage = if (textOnly) null else VISION_TOKENS,
    )
  ).also { it.initialize() }

  @Synchronized private fun engine(): EmbeddingEngine = engine ?: run {
    val t = System.nanoTime()
    cacheDir.mkdirs()
    if (textOnly) return@run create(false).also { engine = it; initMs = (System.nanoTime() - t) / 1_000_000 }
    // A computer with no usable GPU (a headless VM, no Vulkan or D3D12 adapter) does not always fail with an exception: LiteRT-LM's
    // GPU path can crash the whole process in native code, and nothing in the JVM can catch that. So the first time, a separate
    // process tries the GPU path on a tiny picture; only if it comes back clean does this process use the GPU. The answer is kept.
    val gpu = wantGpu ?: GpuProbe.usable(model, File(cacheDir.parentFile ?: cacheDir, "vision-backend.txt"))
    val e = if (gpu) runCatching { create(true) }.getOrElse { create(false).also { visionUsed = "cpu" } } else create(false).also { visionUsed = "cpu" }
    if (gpu && visionUsed != "cpu") visionUsed = "gpu"
    e.also { engine = it; initMs = (System.nanoTime() - t) / 1_000_000 }
  }

  @Synchronized private fun run(input: InputData): FloatArray = engine().computeEmbedding(listOf(input), options).embedding

  fun warm() { engine() }

  /** Changes the thread count (the work speed). The engine is rebuilt with it before the next embedding; false when nothing changed. */
  @Synchronized fun useThreads(n: Int?): Boolean {
    if (n == threads) return false
    threads = n
    engine?.close(); engine = null
    return true
  }
  val threadCount: Int? get() = threads
  /** A text exactly as given, with no prefix added: the self-test's fixed probes carry their own. */
  fun raw(text: String): FloatArray = run(InputData.Text(text))
  fun query(text: String): FloatArray = run(InputData.Text(QUERY_PREFIX + text))
  fun document(text: String, title: String? = null): FloatArray =
    run(InputData.Text("title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: $text"))
  fun image(jpeg: ByteArray): FloatArray {
    check(!textOnly) { "the text-only model cannot embed a picture" }
    return run(InputData.Image(jpeg))
  }

  @Synchronized override fun close() { engine?.close(); engine = null }
}
