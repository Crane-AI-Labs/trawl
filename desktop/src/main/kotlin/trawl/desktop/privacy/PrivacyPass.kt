package trawl.desktop.privacy

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PrivateLook
import com.craneai.findr.engine.PrivateImage
import com.craneai.findr.engine.PrivatePack
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.nio.FloatBuffer
import trawl.desktop.Thumbnails

/** Scores one picture for "looks private" (see [PrivateLook]). A fake in the tests; [OrtPrivateScorer] for real. */
interface PictureScorer {
  /** The Privacy pack is installed, a stat only. */
  fun available(): Boolean

  /** P(private) of the picture in [file], 0..1; null when it cannot be read (HEIC, a damaged file). Throws [ModelFailure] when the model itself will not run. */
  fun score(file: File): Float?

  /** Gives the model's memory back. */
  fun release()
}

/** The model failed to run: the pass stops instead of marking every picture "unreadable". */
class ModelFailure(cause: Throwable) : RuntimeException("the privacy model failed: ${cause.javaClass.simpleName}", cause)

/** Where the Privacy pack puts its model on this computer. */
object PrivacyPackFiles {
  fun modelFile(modelsDir: File) = File(File(modelsDir, PrivatePack.DIR), PrivatePack.MODEL)
}

/**
 * The phone's `PrivateScorer`, on the JVM: Marqo nsfw-image-detection-384 (ViT-tiny, fp32, 22 MB) on ONNX Runtime's CPU provider, same
 * session settings as the phone, two threads. The picture is resized with Pillow's bicubic filter (`PrivateImage.prepare`, the way the
 * model was evaluated): a computer has the time the phone does not, so there is no faster approximate path to disagree with it.
 * Loaded only while the scoring pass runs; [release] gives the roughly 60 MB back.
 */
class OrtPrivateScorer(private val modelFile: File, private val threads: Int = 2) : PictureScorer {
  private val lock = Any()
  private var session: OrtSession? = null
  private val pixels = FloatArray(3 * PrivateImage.SIZE * PrivateImage.SIZE)

  override fun available(): Boolean = modelFile.isFile

  override fun score(file: File): Float? {
    val img = decode(file) ?: return null
    return score(img)
  }

  /** P(private) of an already decoded picture. */
  fun score(img: BufferedImage): Float = synchronized(lock) {
    val w = img.width
    val h = img.height
    val argb = img.getRGB(0, 0, w, h, null, 0, w)
    PrivateImage.prepare(argb, w, h, pixels)
    try {
      val s = session ?: open().also { session = it }
      val shape = longArrayOf(1, 3, PrivateImage.SIZE.toLong(), PrivateImage.SIZE.toLong())
      OnnxTensor.createTensor(env(), FloatBuffer.wrap(pixels), shape).use { input ->
        s.run(mapOf(INPUT to input)).use { result ->
          @Suppress("UNCHECKED_CAST")
          val logits = (result.get(0).value as Array<FloatArray>)[0]
          PrivateLook.probability(logits[0], logits[1])
        }
      }
    } catch (t: Throwable) {
      throw ModelFailure(t)
    }
  }

  override fun release() = synchronized(lock) {
    session?.close()
    session = null
  }

  private fun open(): OrtSession {
    val options = OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(threads)
      setInterOpNumThreads(1)
      setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      addConfigEntry("session.intra_op.allow_spinning", "0")
      // Lean memory, as on the phone: one fixed 384 px input, so an arena has nothing to reuse.
      setCPUArenaAllocator(false)
      setMemoryPatternOptimization(false)
      addConfigEntry("session.use_device_allocator_for_initializers", "1")
    }
    return env().createSession(modelFile.absolutePath, options)
  }

  companion object {
    private const val INPUT = "pixel_values"

    /** The one ONNX Runtime environment of the process. */
    fun env(): OrtEnvironment = NoTelemetry.environment()

    /** A picture decoded near twice the model's input size (ImageIO subsampling, turned upright), or null. */
    fun decode(file: File): BufferedImage? = Thumbnails.decode(file, PrivateImage.SIZE * 2)
  }
}

/**
 * The "looks private" pass: every picture gets one score, once, newest first (the phone's `PrivacyPass`, minus the charging and
 * thermal gates a computer does not need). The score only ever blurs a thumbnail; it never ranks or filters (see [PrivateLook]).
 * A file that cannot be read is marked so it is not retried on every run; a different model ([PrivateLook.MODEL]) clears every score
 * and the pass starts over.
 *
 * Runs on the caller's thread (give it a low-priority one of its own): it checks [stopped] between pictures.
 */
class PrivacyPass(
  private val index: PrivacyIndex,
  private val scorer: PictureScorer,
  private val stopped: () -> Boolean = { false },
  private val kinds: Set<FileKind> = setOf(FileKind.IMAGE, FileKind.SCREENSHOT),
  private val fileOf: (IndexedFile) -> File? = ::fileOfUri,
) {
  /** Files scored (or marked unreadable) this run. */
  var done = 0
    private set

  /** True when the pass ended because the model would not run. */
  var modelFailed = false
    private set

  fun run(): Int {
    if (!scorer.available()) return 0
    index.ensurePrivateModel(PrivateLook.MODEL)
    try {
      while (!stopped()) {
        val batch = index.nextForPrivateScore(BATCH, kinds)
        if (batch.isEmpty()) break
        for (row in batch) {
          if (stopped()) break
          val file = fileOf(row)
          val score = try {
            file?.takeIf { it.isFile }?.let(scorer::score)
          } catch (_: ModelFailure) {
            modelFailed = true
            return done
          } catch (_: Exception) {
            null   // this picture cannot be read: mark it, move on
          }
          index.saveLooksPrivate(row.id, score)
          done++
        }
      }
    } finally {
      scorer.release()
    }
    return done
  }

  /** Runs [run] on a daemon thread at the lowest priority, so searching and indexing keep the CPU. */
  fun runInBackground(onFinished: (Int) -> Unit = {}): Thread =
    Thread({ onFinished(runCatching { run() }.getOrDefault(done)) }, "trawl-privacy-pass").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
      start()
    }

  companion object {
    private const val BATCH = 16

    /** The row's `file:` URI as a file; null for anything else. */
    fun fileOfUri(row: IndexedFile): File? = try {
      URI(row.uri).takeIf { it.scheme == "file" }?.let(::File)
    } catch (_: Exception) {
      null
    }
  }
}
