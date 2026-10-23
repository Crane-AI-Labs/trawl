package com.craneai.findr.engine

import com.craneai.findr.core.BuildFlags
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The text/picture/audio embedder, as the engine uses it. Two editions of the app, two implementations (see [Edition]):
 *  - full: [LiteRtEmbedder], EmbeddingGemma 2 on LiteRT-LM (src/full);
 *  - Go: NoModelEmbedder, which never has a model (src/go). LiteRT-LM ships no 32-bit library and a 1 GB-free phone could not hold
 *    the model anyway, so in Trawl Go "meaning is pending" is the permanent, honest state: names and words are searched, nothing else.
 * Nothing outside src/full touches LiteRT-LM classes. The one place that builds an embedder is [newEmbedder] (FindrEngine calls it).
 */
interface Embedder {
  val profile: MemoryProfile

  /** True while the engine has failed its self-test even after a fresh cache: search runs on words alone ("Search is repairing itself"). */
  val repairing: StateFlow<Boolean>

  /** The model files this phone may use, in order of preference ([modelNames]). */
  val modelNames: List<String>

  /** The model file in app-private storage, or null when there is none (always null in Trawl Go). */
  val modelFile: File?

  /** EmbeddingGemma's audio encoder can run here. */
  val supportsAudio: Boolean

  /** Pictures and video frames go through EmbeddingGemma's vision encoder (false in the lite profile: the quick-look model reads them). */
  val imagesByEmbeddingGemma: Boolean

  /** What the index records as its image and audio vector space (IndexStore.ensureModelFingerprint); null without a model. */
  val imageFingerprint: String?

  /** The text model alone (IndexStore.ensureTextFingerprint); null without a model. */
  val textFingerprint: String?

  suspend fun query(text: String): FloatArray

  /** Loads the text engine if nothing is loaded yet; no-op otherwise. For search-box focus (prewarm). */
  suspend fun warm()

  suspend fun document(text: String, title: String? = null): FloatArray

  suspend fun image(jpeg: ByteArray): FloatArray

  suspend fun audio(wav: ByteArray): FloatArray

  /** Drops back to nothing (memory) when the indexer is done; the next search reloads text-only. */
  suspend fun release()

  /**
   * Like [release], but the loaded engine is stale and must not be used again (a new model file was installed): the next call loads
   * the new file. [release] may keep a GPU engine on Qualcomm phones (closing it there crashed the driver); this one never reuses it.
   */
  suspend fun discard() = release()

  /**
   * Lets go of the audio encoder (~100 MB mapped, plus its buffers) once a stretch of recordings is over: only an engine loaded with
   * the audio encoder is closed, and the next picture or text reloads at the level it needs (5-10 s, with the xnnpack cache in place).
   * For a phone short of memory (MemoryBudget); a roomy one keeps the engine as it is for the whole run.
   */
  suspend fun dropAudioEncoder()

  companion object {
    /**
     * D4, behind a flag: Findr's language-tuned bundles (the experiment code, setup B). They are Google's
     * 740m/440m bundles with the student backbone transplanted in (int8); vision and audio encoders are Google's, byte
     * for byte. Images and audio pass through the backbone too, so the name is part of the index fingerprint
     * (IndexWorker): switching re-understands every file once. Build with `-PfindrFinetuned=true` to turn it on.
     * the research notes.
     */
    val USE_FINETUNED: Boolean get() = BuildFlags.finetuned
    val FINETUNED_NAMES = listOf("findr-eg2-ft-740m.litertlm", "findr-eg2-ft-440m.litertlm")
    val STOCK_NAMES = listOf("embeddinggemma-2-740m.litertlm", "embeddinggemma-2-text-vision-440m.litertlm")
    /** Preference order: fine-tuned (when the flag is on), then with audio, then text + vision. */
    val MODEL_NAMES: List<String> get() = (if (USE_FINETUNED) FINETUNED_NAMES else emptyList()) + STOCK_NAMES
    /** The text-only bundle (165 MB): the lite profile's model. Its text vectors equal the 440m's and 740m's (the build notes, M1). */
    const val TEXT_ONLY_NAME = "embeddinggemma-2-text-270m.litertlm"

    /**
     * The model files a profile may use, best first. Standard: [MODEL_NAMES], unchanged. Lite: the text-only bundle first; a
     * multimodal one, if that is all there is, also works (its text model is the same and nothing else of it is loaded).
     */
    fun modelNames(profile: MemoryProfile): List<String> =
      if (profile == MemoryProfile.LITE) (if (USE_FINETUNED) FINETUNED_NAMES else emptyList()) + TEXT_ONLY_NAME + STOCK_NAMES
      else MODEL_NAMES
    val AUDIO_NAMES = setOf(FINETUNED_NAMES[0], STOCK_NAMES[0])
    /**
     * Whether a fine-tuned bundle keeps its stock bundle's image and audio vectors. Its student was trained with image and
     * audio anchors, so it stays in the stock space and switching could re-embed text only (old image vectors with new
     * queries measured within noise, the research notes). But the pre-registered rule for the language model is that photos are
     * read again (old photo vectors under new queries lose about 2% of image-only search), so this is off: the image
     * fingerprint changes with the bundle and [REEMBED_STALE] carries the photos over without a gap. Set it to true to go
     * back to the text-only upgrade.
     */
    const val CARRY_OVER_IMAGE_SPACE = false
    val IMAGE_SPACE: Map<String, String> =
      if (CARRY_OVER_IMAGE_SPACE) mapOf(FINETUNED_NAMES[0] to STOCK_NAMES[0], FINETUNED_NAMES[1] to STOCK_NAMES[1]) else emptyMap()
    /**
     * When the image fingerprint changes, keep the old image, frame and audio vectors as stale (searchable, replaced file by
     * file by the worker, recent first, bulk work on the charger) instead of dropping them and leaving a gap. False drops them,
     * as before.
     */
    const val REEMBED_STALE = true
    /** Image fingerprints of the lite profile start with this ([imageFingerprint]). */
    const val LITE_FINGERPRINT_PREFIX = "lite:"

    /**
     * Whether a change of image fingerprint from [old] to [new] keeps the old vectors as stale ([REEMBED_STALE]). Only between
     * two EmbeddingGemma image spaces: a lite index holds no EmbeddingGemma image vectors to keep, so a change into, out of or
     * within the lite profile reads every file again (the old full drop), or its pictures would never be read again.
     */
    fun keepStaleOnChange(old: String?, new: String): Boolean =
      REEMBED_STALE && old?.startsWith(LITE_FINGERPRINT_PREFIX) != true && !new.startsWith(LITE_FINGERPRINT_PREFIX)
    const val DIMENSION = 768
    /** After a failed self-test (twice, with a fresh cache) the engine is not tried again for this long; search uses words alone meanwhile. */
    const val UNHEALTHY_RETRY_MS = 5 * 60_000L
    const val SCREEN_OFF_TEXT_THREADS = 6   // 8 lost under load (CLIP + speech oversubscribe 8 cores)
    const val SCREEN_OFF_SETTLE_MS = 30_000L
    const val VISION_TOKENS = 70   // same overall quality as 140, half the time (M2)
    const val QUERY_PREFIX = "task: search result | query: "
    /**
     * The lite profile's longest text input, prefix included: a token covers at least a character of ordinary text, so 480
     * chars stay inside the 512-token bucket. Measured on the A06 (text-270m, text engine PSS by the largest bucket used):
     * 128 401 MB, 256 467 MB, 512 515 MB, 1,024 619 MB, and a bucket's memory stays once built. The engine's own cap
     * (maxInputLength) was tried and dropped: it refuses longer input instead of cutting it, skips the cached packed weights
     * (+48 to +100 MB of anonymous memory, load 2-3 s against 0.8 s) and saved nothing below 256.
     */
    const val LITE_MAX_INPUT_CHARS = 480
  }
}

/** Diag marker (kind and class only are kept): a corrupt model cache was found by the self-test and cleared, and the engine then passed. */
class EngineCacheRepaired : RuntimeException("model cache cleared")
