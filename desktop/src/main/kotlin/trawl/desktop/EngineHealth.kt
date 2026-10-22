package trawl.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * A20: the engine self-test, ported from the phone (`EngineSelfTest.kt`; the golden vectors are the phone's own `engine_probes.json`,
 * which the desktop build already has on its classpath). A damaged LiteRT-LM cache does not fail loudly: the engine loads and then
 * returns garbage vectors (NaN, or a cosine of 0.39 to the right ones), so meaning search would be wrong for good and nothing would
 * say so. After the engine loads, a few fixed texts are embedded and compared with the reference vectors. NaN, or a cosine under
 * [MIN_COSINE]: the model's cache files are deleted and the engine is loaded once more. If that fails too the engine is not served,
 * Trawl reads and finds by words and names alone, says "repairing" and tries again later. No text of the person is involved.
 */
class EngineProbe(private val bundles: Map<String, List<Golden>>) {
  class Golden(val id: String, val text: String, val vector: FloatArray)

  sealed interface Verdict {
    data object Healthy : Verdict
    /** The bundle has no golden vectors (an unknown or newer model file): not checked, which is not a failure. */
    data object Unchecked : Verdict
    /** A probe failed. [reason] names the probe and the number, never text. */
    data class Bad(val reason: String) : Verdict
  }

  fun hasGolden(bundle: String): Boolean = !bundles[bundle].isNullOrEmpty()

  /** Embeds this bundle's probes with [embed] and compares. A probe that throws counts as a failure. */
  fun check(bundle: String, embed: (String) -> FloatArray): Verdict {
    val probes = bundles[bundle]?.takeIf { it.isNotEmpty() } ?: return Verdict.Unchecked
    for (p in probes) {
      val v = try { embed(p.text) } catch (t: Throwable) { return Verdict.Bad("probe ${p.id} threw ${t.javaClass.simpleName}") }
      if (v.size != p.vector.size) return Verdict.Bad("probe ${p.id} has ${v.size} dimensions, expected ${p.vector.size}")
      if (v.any { it.isNaN() || it.isInfinite() }) return Verdict.Bad("probe ${p.id} has NaN or infinite values")
      val c = cosine(v, p.vector)
      if (c.isNaN() || c < MIN_COSINE) return Verdict.Bad("probe ${p.id} cosine ${"%.3f".format(c)} to its reference vector (needs $MIN_COSINE)")
    }
    return Verdict.Healthy
  }

  companion object {
    const val RESOURCE = "/engine_probes.json"
    /** The phone's parity with the reference is 1.0000; the damaged caches seen measured 0.39 or NaN. */
    const val MIN_COSINE = 0.99f
    val NONE = EngineProbe(emptyMap())

    fun cosine(a: FloatArray, b: FloatArray): Float {
      var dot = 0.0; var na = 0.0; var nb = 0.0
      for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
      return if (na == 0.0 || nb == 0.0) Float.NaN else (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** `{"bundles": {"<model file>": [{"id", "text", "vector": base64 little-endian float32}]}}`. */
    fun fromJson(json: String): EngineProbe {
      val bundles = JSONObject(json).getJSONObject("bundles")
      val out = HashMap<String, List<Golden>>()
      for (name in bundles.keys().asSequence().map { it as String }.toList()) {
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

    /** The probes shipped in the app. None (so no self-test, and no false alarm) when they are not there. */
    fun fromClasspath(): EngineProbe = runCatching {
      EngineProbe::class.java.getResourceAsStream(RESOURCE)!!.use { fromJson(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrDefault(NONE)
  }
}

/** The result of loading an engine with its self-test. */
sealed interface EngineLoad<out E> {
  /** Passed (or there was nothing to check it against). */
  class Ready<E>(val engine: E) : EngineLoad<E>
  /** Failed the first time, passed after the cache was cleared. [reason] names the probe and number. */
  class Repaired<E>(val engine: E, val reason: String) : EngineLoad<E>
  /** Failed with a fresh cache too: not to be used. */
  class Unhealthy(val reason: String) : EngineLoad<Nothing>
}

object EngineGuard {
  /** How long meaning search stays off after a failed repair before the engine is tried again. */
  const val RETRY_MS = 5 * 60_000L

  fun <E : Any> load(
    bundle: String, probe: EngineProbe, create: () -> E, embed: (E, String) -> FloatArray, close: (E) -> Unit,
    clearCache: () -> Int, log: (String) -> Unit = {},
  ): EngineLoad<E> {
    val first = create()
    val v1 = probe.check(bundle) { embed(first, it) }
    if (v1 !is EngineProbe.Verdict.Bad) return EngineLoad.Ready(first)
    log("engine self-test FAILED (${v1.reason}): clearing the cache of $bundle and loading again")
    runCatching { close(first) }
    log("cleared ${clearCache()} cache files")
    val second = create()
    val v2 = probe.check(bundle) { embed(second, it) }
    if (v2 !is EngineProbe.Verdict.Bad) { log("engine self-test passed after clearing the cache"); return EngineLoad.Repaired(second, v1.reason) }
    log("engine self-test FAILED again (${v2.reason}): not using this engine for now")
    runCatching { close(second) }
    clearCache()   // leave nothing from this attempt for the next one to trust
    return EngineLoad.Unhealthy(v2.reason)
  }

  /**
   * LiteRT-LM names the files it caches for a model `<model file name>_<n>_<n>.<part>.xnnpack_cache`. Deletes those of [modelFileName]
   * only; everything else in the folder stays. Returns how many files went.
   */
  fun clearCache(cacheDir: File, modelFileName: String): Int {
    val files = cacheDir.listFiles { f -> f.isFile && f.name.startsWith("${modelFileName}_") && f.name.endsWith(".xnnpack_cache") } ?: return 0
    return files.count { it.delete() }
  }
}
