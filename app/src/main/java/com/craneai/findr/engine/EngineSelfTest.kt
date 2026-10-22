package com.craneai.findr.engine

import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The engine self-test. A corrupt LiteRT-LM xnnpack cache (a crash in the middle of a write, say) does not fail loudly: the
 * engine loads and then returns garbage vectors (NaN, or a cosine of 0.39 to the right vector), so search would be wrong for good
 * and nothing would say so. After every engine load Findr embeds a few fixed texts and compares them with vectors made by the
 * Mac/ARM reference (scripts/make_engine_probes.py; phone parity with it is 1.0000). A NaN, or a cosine under [MIN_COSINE]:
 * the model's cache files are deleted and the engine is loaded once more. If that fails too the engine is not served
 * ([EngineUnhealthyException]) and search falls back to words alone, instead of answering with garbage.
 *
 * Nothing here is Android-bound except the asset reader, so the JVM tests run all of it. No query or file text is ever logged.
 */
class EngineProbe(private val bundles: Map<String, List<Golden>>) {
  /** One fixed probe: the text, and the vector the reference makes for it with this bundle. */
  class Golden(val id: String, val text: String, val vector: FloatArray)

  sealed interface Verdict {
    /** Every probe matched. */
    data object Healthy : Verdict
    /** The bundle has no golden vectors (an unknown or newer bundle): not checked, which is not a failure. */
    data object Unchecked : Verdict
    /** A probe failed. [reason] names the probe and the number, never text. */
    data class Bad(val reason: String) : Verdict
  }

  fun hasGolden(bundle: String): Boolean = !bundles[bundle].isNullOrEmpty()

  /** Embeds this bundle's probes with [embed] and compares. A probe that throws counts as a failure (the engine is not usable). */
  fun check(bundle: String, embed: (String) -> FloatArray): Verdict {
    val probes = bundles[bundle]?.takeIf { it.isNotEmpty() } ?: return Verdict.Unchecked
    for (p in probes) {
      val v = try {
        embed(p.text)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (t: Throwable) {
        return Verdict.Bad("probe ${p.id} threw ${t.javaClass.simpleName}")
      }
      if (v.size != p.vector.size) return Verdict.Bad("probe ${p.id} has ${v.size} dimensions, expected ${p.vector.size}")
      if (v.any { it.isNaN() || it.isInfinite() }) return Verdict.Bad("probe ${p.id} has NaN or infinite values")
      val c = cosine(v, p.vector)
      if (c.isNaN() || c < MIN_COSINE) return Verdict.Bad("probe ${p.id} cosine ${"%.3f".format(c)} to its golden vector (needs $MIN_COSINE)")
    }
    return Verdict.Healthy
  }

  companion object {
    const val ASSET = "engine_probes.json"
    /** Phone parity with the reference is 1.0000; the corrupt caches seen measured 0.39 or NaN. */
    const val MIN_COSINE = 0.99f
    val NONE = EngineProbe(emptyMap())

    fun cosine(a: FloatArray, b: FloatArray): Float {
      var dot = 0.0; var na = 0.0; var nb = 0.0
      for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
      return if (na == 0.0 || nb == 0.0) Float.NaN else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** The asset's JSON: `{"bundles": {"<model file>": [{"id", "text", "vector": base64 little-endian float32}]}}`. */
    fun fromJson(json: String): EngineProbe {
      val bundles = JSONObject(json).getJSONObject("bundles")
      val out = HashMap<String, List<Golden>>()
      for (name in bundles.keys()) {
        val arr = bundles.getJSONArray(name)
        out[name] = (0 until arr.length()).map { i ->
          val o = arr.getJSONObject(i)
          Golden(o.getString("id"), o.getString("text"), decode(o.getString("vector")))
        }
      }
      return EngineProbe(out)
    }

    private fun decode(b64: String): FloatArray {
      val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
      return FloatArray(buf.remaining() / 4) { buf.getFloat() }
    }

    fun fromAssets(context: android.content.Context): EngineProbe =
      try {
        context.assets.open(ASSET).use { fromJson(it.readBytes().toString(Charsets.UTF_8)) }
      } catch (t: Throwable) {
        com.craneai.findr.core.FindrLog.w("FindrEmbedder", "no engine probes in the app (${t.javaClass.simpleName}): the engine is not self-tested")
        NONE
      }
  }
}

/** The engine failed its probes twice, with a freshly built cache: it must not be used. Carries no text. */
class EngineUnhealthyException(reason: String) : IllegalStateException("embedding engine failed its self-test: $reason")

/**
 * Load, probe, and on failure clear the model's cache and load once more. [E] is the engine type (the real [Embedder] passes
 * its EmbeddingEngine; the tests pass a fake with a file-backed cache).
 */
class EngineSelfTest<E : Any>(
  private val probe: EngineProbe,
  /** Deletes the cache files of the model bundle being loaded; returns how many. Called with no engine of that bundle loaded. */
  private val clearCache: () -> Int,
  private val log: (String) -> Unit,
  /** Called when the first load was bad and the reload was good (a repair). */
  private val onRepaired: (reason: String) -> Unit = {},
  /**
   * Asked after the load, before the probes: null means probe, a string means skip and says why (the cache is the one that already
   * passed, see [ProbeGate]). The default always probes.
   */
  private val skipReason: () -> String? = { null },
  /** A probe run passed (first load or after the repair): the caller remembers what it passed on. */
  private val onPassed: () -> Unit = {},
  /** A probe run failed: whatever was remembered as passed is no longer to be trusted. */
  private val onFailed: () -> Unit = {},
) {
  /** Milliseconds the last probe run took (all attempts); for the log and the measurement. */
  @Volatile var lastProbeMs = 0L
    private set

  fun loadVerified(bundle: String, load: () -> E, embed: (E, String) -> FloatArray, close: (E) -> Unit): E {
    if (!probe.hasGolden(bundle)) {
      log("no golden probe for $bundle: the engine is not self-tested")
      return load()
    }
    lastProbeMs = 0
    val first = load()
    val skip = skipReason()
    if (skip != null) { log("engine self-test skipped: $skip"); return first }
    val v1 = timed { probe.check(bundle) { embed(first, it) } }
    if (v1 is EngineProbe.Verdict.Healthy) { log("engine self-test passed ($lastProbeMs ms)"); onPassed(); return first }
    onFailed()
    val reason = (v1 as EngineProbe.Verdict.Bad).reason
    log("engine self-test FAILED ($reason): clearing the cache of $bundle and loading again")
    close(first)
    val cleared = clearCache()
    log("cleared $cleared cache files")
    val second = load()
    val v2 = timed { probe.check(bundle) { embed(second, it) } }
    if (v2 is EngineProbe.Verdict.Healthy) {
      log("engine self-test passed after clearing the cache ($lastProbeMs ms in all)")
      onPassed()
      onRepaired(reason)
      return second
    }
    val reason2 = (v2 as EngineProbe.Verdict.Bad).reason
    log("engine self-test FAILED again ($reason2): not serving this engine")
    close(second)
    onFailed()
    clearCache()   // leave nothing from this attempt for the next one to trust
    throw EngineUnhealthyException(reason2)
  }

  private inline fun <T> timed(block: () -> T): T {
    val t0 = System.nanoTime()
    try { return block() } finally { lastProbeMs += (System.nanoTime() - t0) / 1_000_000 }
  }
}

object EngineCache {
  /**
   * LiteRT-LM names the files it caches for a model `<model file name>_<n>_<n>.<part>.xnnpack_cache` in the cache directory
   * (seen on the phone and on the Mac). Deletes those of [modelFileName] only; other models' caches and everything else in the
   * folder stay. Returns how many files went.
   */
  fun clear(cacheDir: File, modelFileName: String): Int {
    val files = cacheDir.listFiles { f -> f.isFile && f.name.startsWith("${modelFileName}_") && f.name.endsWith(".xnnpack_cache") } ?: return 0
    return files.count { it.delete() }
  }
}
