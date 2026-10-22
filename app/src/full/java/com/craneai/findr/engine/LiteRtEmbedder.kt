package com.craneai.findr.engine

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.craneai.findr.BuildConfig
import com.craneai.findr.core.FindrLog as Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.craneai.findr.engine.Embedder.Companion.AUDIO_NAMES
import com.craneai.findr.engine.Embedder.Companion.IMAGE_SPACE
import com.craneai.findr.engine.Embedder.Companion.LITE_FINGERPRINT_PREFIX
import com.craneai.findr.engine.Embedder.Companion.LITE_MAX_INPUT_CHARS
import com.craneai.findr.engine.Embedder.Companion.QUERY_PREFIX
import com.craneai.findr.engine.Embedder.Companion.SCREEN_OFF_SETTLE_MS
import com.craneai.findr.engine.Embedder.Companion.UNHEALTHY_RETRY_MS
import com.craneai.findr.engine.Embedder.Companion.VISION_TOKENS

private const val TAG = "FindrEmbedder"

/**
 * EmbeddingGemma 2 on LiteRT-LM, set up the way the A06 measurements said (the build notes, M1/M2):
 * text on CPU (GPU text needed 1.8 GB), images on GPU at 70 vision tokens (4.8 s, vs 15.5 s CPU at 140), audio on
 * CPU and only when asked for (17 s per 30 s window). One model file serves text and images: its text vectors are
 * identical to the text-only model's.
 *
 * One engine per process, shared by search and the indexer, behind a mutex: two loaded copies would cost ~1.3 GB on a
 * 4 GB phone. The engine starts text-only (fast to load, ~0.6–2.5 s) and is rebuilt with vision/audio when the indexer
 * needs them; search keeps working on whichever engine is loaded.
 *
 * In the [MemoryProfile.LITE] profile (2 GB phones) the engine is text-only for good: the text-only model file is preferred,
 * no vision or audio encoder is ever loaded, and pictures go to the quick-look model instead ([imagesByEmbeddingGemma]).
 */
class LiteRtEmbedder(private val context: Context, override val profile: MemoryProfile = MemoryProfile.STANDARD) : Embedder {
  private enum class Level { TEXT, VISION, AUDIO }

  private val mutex = Mutex()
  private var engine: EmbeddingEngine? = null
  private var level: Level? = null
  /**
   * False after the GPU failed to load the vision encoder (CPU fallback); benches set it to compare backends. Test builds for
   * devices whose GPU path hangs rather than fails (the 2 GB emulator: "Failed to lock custom tensor buffer", 2 min per image)
   * start on the CPU with `-PfindrVisionCpu=true`. Shipped builds always try the GPU first.
   */
  internal var visionOnGpu = !BuildConfig.FINDR_VISION_CPU && Tuning.current.visionGpu
  /** The loaded engine was built with a vision executor (every multimodal bundle; not the text-only one). */
  private var hasVisionExecutor = true
  /** Text threads the loaded engine was built with (null: LiteRT-LM's default, 4). */
  private var loadedThreads: Int? = null
  private val power = context.getSystemService(PowerManager::class.java)
  private var screenOn = true
  private var screenChangedAt = 0L

  private val lite = profile == MemoryProfile.LITE
  private val probeMemory: ProbeMemory by lazy { PrefsProbeMemory(context) }

  /**
   * The engine self-test (EngineSelfTest): fixed texts embedded after every engine load and compared with the Mac reference's
   * vectors; a corrupt xnnpack cache otherwise makes every vector garbage without any error.
   */
  private val probes by lazy { EngineProbe.fromAssets(context) }
  private var unhealthyUntil = 0L
  private val repairingFlow = MutableStateFlow(false)
  /** True while the engine has failed its self-test even after a fresh cache: search runs on words alone ("Search is repairing itself"). */
  override val repairing: StateFlow<Boolean> get() = repairingFlow

  /** The model files this phone may use, in order of preference ([modelNames]). */
  override val modelNames: List<String> = Embedder.modelNames(profile)

  /** The model file in app-private storage: the full model (with audio) if present, else text + vision (lite: text-only first). */
  override val modelFile: File?
    get() = modelNames.map { File(context.filesDir, "models/$it") }.firstOrNull { it.isFile }

  /** EmbeddingGemma's audio encoder can run here (never in the lite profile: that encoder alone is ~90 MB of packed weights). */
  override val supportsAudio: Boolean get() = !lite && modelFile?.name in AUDIO_NAMES

  /** Pictures and video frames go through EmbeddingGemma's vision encoder (false in the lite profile: the quick-look model reads them). */
  override val imagesByEmbeddingGemma: Boolean get() = !lite

  /**
   * What the index records as its model fingerprint: the image and audio vector space (IndexStore.ensureModelFingerprint). In
   * the lite profile pictures live in the quick-look model's space, so its fingerprint stands in: moving a phone between the
   * profiles, or changing the quick-look model, reads every file again.
   */
  override val imageFingerprint: String?
    get() = modelFile?.let { if (lite) "$LITE_FINGERPRINT_PREFIX${QuickLookPack.FINGERPRINT}" else "${IMAGE_SPACE[it.name] ?: it.name}:vt$VISION_TOKENS" }

  /** The text model alone (IndexStore.ensureTextFingerprint): when only this changes, text is re-embedded, not images. */
  override val textFingerprint: String? get() = modelFile?.name

  override suspend fun query(text: String): FloatArray = embedText(QUERY_PREFIX + text, forIndexing = false)

  /** Loads the text engine if nothing is loaded yet; no-op otherwise. For search-box focus (prewarm). */
  override suspend fun warm() { withContext(Dispatchers.Default) { mutex.withLock { ensure(Level.TEXT) } } }

  override suspend fun document(text: String, title: String?): FloatArray =
    embedText("title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: $text")

  override suspend fun image(jpeg: ByteArray): FloatArray = run(Level.VISION, listOf(InputData.Image(jpeg)), forIndexing = true)

  override suspend fun audio(wav: ByteArray): FloatArray = run(Level.AUDIO, listOf(InputData.Audio(wav)), forIndexing = true)

  private suspend fun embedText(text: String, forIndexing: Boolean = true): FloatArray =
    run(Level.TEXT, listOf(InputData.Text(liteCut(text))), forIndexing)

  /**
   * The lite profile embeds at most [LITE_MAX_INPUT_CHARS] of a text (its words all stay in the keyword index). Findr's pieces
   * are ~400 chars plus a prefix and pass whole; a long speech segment or a pasted query is cut, so the engine never builds
   * its 1,024-8,192-token buckets, whose memory stays once built (the research notes).
   */
  private fun liteCut(text: String): String = if (lite && text.length > LITE_MAX_INPUT_CHARS) text.take(LITE_MAX_INPUT_CHARS) else text

  /** [forIndexing]: the worker's call, which may rebuild the engine for the screen state ([textThreads]); a query never waits for that. */
  private suspend fun run(needed: Level, inputs: List<InputData>, forIndexing: Boolean): FloatArray = withContext(Dispatchers.Default) {
    mutex.withLock {
      if (forIndexing && engine != null && loadedThreads != textThreads()) {
        Log.i(TAG, "text threads ${loadedThreads ?: "default"} -> ${textThreads() ?: "default"} (screen ${if (screenOn) "on" else "off"})")
        engine?.close(); engine = null; level = null
      }
      val e = ensure(needed)
      e.computeEmbedding(inputs, if (hasVisionExecutor) OPTIONS else TEXT_OPTIONS).embedding
    }
  }

  /**
   * Threads for the text backbone (documents, transcripts, and the CPU part of every image): more once the screen has been
   * off for [SCREEN_OFF_SETTLE_MS] (charging overnight, nobody looking), the default as soon as it is on again. On the
   * A06, 6 threads cut a ~250-token chunk from 1.12 to 0.84 s with bit-identical vectors, but janked our grid while
   * the screen was on, and would take cores from whatever app is in front (the research notes).
   */
  private fun textThreads(): Int? {
    val on = power?.isInteractive ?: true
    val now = SystemClock.elapsedRealtime()
    if (on != screenOn) { screenOn = on; screenChangedAt = now }
    return if (!screenOn && now - screenChangedAt >= SCREEN_OFF_SETTLE_MS) Tuning.current.textThreadsOff else Tuning.current.textThreadsOn
  }

  /** Loads (or upgrades) the engine to at least `needed`. Called with the mutex held. */
  private fun ensure(needed: Level): EmbeddingEngine {
    val current = level
    engine?.let { if (current != null && current >= needed) return it }
    val file = modelFile ?: error("No embedding model in ${File(context.filesDir, "models")}")
    check(!lite || needed == Level.TEXT) { "the lite profile loads no $needed encoder" }
    if (needed == Level.AUDIO) check(supportsAudio) { "${file.name} has no audio encoder" }
    if (SystemClock.elapsedRealtime() < unhealthyUntil) throw EngineUnhealthyException("failed its self-test a moment ago; trying again later")
    engine?.close()
    engine = null
    val start = System.nanoTime()
    val threads = textThreads()
    val schedule = ProbeSchedule(probeMemory, context.cacheDir, file, BuildConfig.VERSION_CODE.toLong(), ::lastCrashAtMs)
    val selfTest = EngineSelfTest<EmbeddingEngine>(
      probes,
      clearCache = { EngineCache.clear(context.cacheDir, file.name) },
      log = { Log.i(TAG, it) },
      onRepaired = { reason ->
        Log.w(TAG, "a corrupt model cache was found and cleared ($reason)")
        com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.ENGINE_REPAIRED, EngineCacheRepaired())
      },
      // probes run only when the cache files changed, the app version changed, or Findr crashed since the last pass (ProbeGate)
      skipReason = schedule::skipReason,
      onPassed = schedule::passed,
      onFailed = schedule::failed,
    )
    val e = try {
      selfTest.loadVerified(file.name, { load(file, needed, threads) }, ::probeEmbedding, { it.close() })
    } catch (u: EngineUnhealthyException) {
      unhealthyUntil = SystemClock.elapsedRealtime() + UNHEALTHY_RETRY_MS
      repairingFlow.value = true
      Log.w(TAG, "the embedding engine is unusable: search runs on words alone for ${UNHEALTHY_RETRY_MS / 60_000} min", u)   // reason: probe id and number only
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.ENGINE_UNHEALTHY, u)
      throw u
    }
    repairingFlow.value = false
    engine = e
    level = needed
    loadedThreads = threads
    Log.i(TAG, "loaded ${file.name} at $needed (vision ${if (needed >= Level.VISION && visionOnGpu) "GPU" else "CPU, idle"}) in ${(System.nanoTime() - start) / 1_000_000} ms, self-test ${selfTest.lastProbeMs} ms")   // log-safe: our model file
    return e
  }

  /** When the system last recorded a Findr crash, native crash or ANR (the diag package's exit history); 0 when none or unreadable. */
  private fun lastCrashAtMs(): Long = com.craneai.findr.diag.ExitHistory.read(context)
    .filter { it.reason == com.craneai.findr.diag.ExitRecord.REASON_CRASH || it.reason == com.craneai.findr.diag.ExitRecord.REASON_CRASH_NATIVE ||
      it.reason == com.craneai.findr.diag.ExitRecord.REASON_ANR }
    .maxOfOrNull { it.timestamp } ?: 0L

  /** The engine as the model needs it: build, with the GPU-vision fallback. */
  private fun load(file: File, needed: Level, threads: Int?): EmbeddingEngine = try {
    build(file, needed, visionOnGpu, threads)
  } catch (t: Throwable) {
    if (needed < Level.VISION || !visionOnGpu) throw t
    // Some GPUs/drivers can't run the vision encoder: fall back to CPU (slower, but it works).
    Log.w(TAG, "vision on GPU failed, falling back to CPU", t)
    visionOnGpu = false
    build(file, needed, false, threads)
  }

  private fun probeEmbedding(e: EmbeddingEngine, text: String): FloatArray =
    e.computeEmbedding(listOf(InputData.Text(text)), if (hasVisionExecutor) OPTIONS else TEXT_OPTIONS).embedding

  private fun build(file: File, needed: Level, gpuVision: Boolean, textThreads: Int?): EmbeddingEngine {
    // A multimodal model won't start without a vision executor ("Vision executor settings are not configured"), even
    // for text alone: give it the CPU one when only text is needed (unused; the GPU one costs seconds and memory).
    val vision = when {
      needed >= Level.VISION -> if (gpuVision) Backend.GPU() else Backend.CPU(threadCount = Tuning.current.visionCpuThreads)
      isMultimodal(file) -> Backend.CPU()
      else -> null
    }
    hasVisionExecutor = vision != null
    // Audio uses every core: 8 threads ran a 10 s window in 2.5 s on the A06, against 3.6 s on the default 4
    // (the research notes). Text threads: see textThreads() (more only with the screen off).
    val audio = if (needed >= Level.AUDIO) Backend.CPU(threadCount = Runtime.getRuntime().availableProcessors()) else null
    return EmbeddingEngine(
      EmbeddingEngineConfig(
        modelPath = file.absolutePath,
        backend = Backend.CPU(threadCount = textThreads),
        visionBackend = vision,
        audioBackend = audio,
        cacheDir = context.cacheDir.absolutePath,
        // A vision budget without a vision executor is refused ("Vision executor settings are not configured"): the text-only
        // bundle gets none. Every multimodal bundle has an executor (GPU, or the idle CPU one), so standard phones are unchanged.
        visionTokensPerImage = if (vision != null) VISION_TOKENS else null,
      )
    ).also { it.initialize() }
  }

  private fun isMultimodal(file: File) = "text-270m" !in file.name

  /** Drops back to nothing (memory) when the indexer is done; the next search reloads text-only. */
  override suspend fun release() = mutex.withLock {
    engine?.close()
    engine = null
    level = null
    loadedThreads = null
  }

  /**
   * Lets go of the audio encoder (~100 MB mapped, plus its buffers) once a stretch of recordings is over: only an engine loaded at
   * [Level.AUDIO] is closed, and the next picture or text reloads at the level it needs (5-10 s, with the xnnpack cache in place).
   * For a phone short of memory (MemoryBudget); a roomy one keeps the engine as it is for the whole run.
   */
  override suspend fun dropAudioEncoder() = mutex.withLock {
    if (level == Level.AUDIO) {
      Log.i(TAG, "audio encoder released (the engine reloads at the level the next file needs)")
      engine?.close()
      engine = null
      level = null
      loadedThreads = null
    }
  }

  private companion object {
    private val OPTIONS = EmbeddingOptions(normalize = true, visionTokensPerImage = VISION_TOKENS)
    /** For an engine built without a vision executor (the text-only bundle, lite profile). */
    private val TEXT_OPTIONS = EmbeddingOptions(normalize = true)
  }
}
