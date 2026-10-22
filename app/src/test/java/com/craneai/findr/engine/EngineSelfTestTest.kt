package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

class EngineSelfTestTest {
  @get:Rule val tmp = TemporaryFolder()

  private val bundle = "embeddinggemma-2-test.litertlm"
  private val texts = listOf("probe one text", "probe two text", "probe three text")
  private val golden = texts.mapIndexed { i, t -> EngineProbe.Golden("p$i", t, unit(Random(i + 1), 768)) }
  private val probe = EngineProbe(mapOf(bundle to golden))

  private fun unit(r: Random, n: Int): FloatArray {
    val v = FloatArray(n) { r.nextFloat() - 0.5f }
    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(n) { v[it] / norm }
  }

  /**
   * A stand-in for the engine whose behaviour is decided by its cache folder, like the real one: it reads the cache file at
   * load, and a file that says CORRUPT makes it garbage. Loading without a cache file writes a good one.
   */
  private class FakeEngine(val mode: String, val vectors: Map<String, FloatArray>) {
    var closed = false
    fun embed(text: String): FloatArray = when (mode) {
      "NAN" -> FloatArray(768) { Float.NaN }
      "GARBAGE" -> unitOf(text)
      "SHORT" -> FloatArray(100)
      "THROW" -> throw IllegalStateException("kernel failed: $text")
      "NEAR" -> near(vectors.getValue(text), 0.98f)
      "CLOSE" -> near(vectors.getValue(text), 0.995f)
      else -> vectors.getValue(text)
    }
    private fun unitOf(text: String): FloatArray {
      val r = Random(text.hashCode() + 99)
      val v = FloatArray(768) { r.nextFloat() - 0.5f }
      val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      return FloatArray(768) { v[it] / n }
    }
    /** A unit vector with exactly this cosine to [g]. */
    private fun near(g: FloatArray, cos: Float): FloatArray {
      val r = Random(5)
      val o = FloatArray(768) { r.nextFloat() - 0.5f }
      val dot = (0 until 768).sumOf { (o[it] * g[it]).toDouble() }.toFloat()
      val perp = FloatArray(768) { o[it] - dot * g[it] }
      val pn = sqrt(perp.sumOf { (it * it).toDouble() }).toFloat()
      val s = sqrt(1 - cos * cos)
      return FloatArray(768) { cos * g[it] + s * perp[it] / pn }
    }
  }

  private inner class Rig(
    private val cacheDir: File = tmp.newFolder(),
    /** What a fresh cache turns the engine into after a repair: "OK" (healthy), or a mode that persists (a bad kernel, say). */
    private val afterClear: String = "OK",
  ) {
    val events = ArrayList<String>()
    val logs = ArrayList<String>()
    var loads = 0
    var repairedReason: String? = null
    val vectors = golden.associate { it.text to it.vector }
    val cacheFile = File(cacheDir, "${bundle}_123_456.text_encoder.xnnpack_cache")
    val other = File(cacheDir, "other-model.litertlm_1_2.text_encoder.xnnpack_cache").apply { writeText("keep") }
    val unrelated = File(cacheDir, "scratch.tmp").apply { writeText("keep") }

    fun corruptCache(kind: String) { cacheFile.writeText(kind) }

    val test = EngineSelfTest<FakeEngine>(
      probe, clearCache = { EngineCache.clear(cacheDir, bundle).also { events += "clear($it)" } }, log = { logs += it },
      onRepaired = { repairedReason = it },
    )

    private fun load(): FakeEngine {
      loads++; events += "load"
      val state = if (cacheFile.exists()) cacheFile.readText() else afterClear.also { cacheFile.writeText(it) }
      return FakeEngine(state, vectors)
    }

    fun run(name: String = bundle): FakeEngine =
      test.loadVerified(name, ::load, { e, t -> e.embed(t) }, { it.closed = true; events += "close" })
  }

  @Test fun `a healthy engine passes at once, nothing is cleared`() {
    val rig = Rig()
    val e = rig.run()
    assertEquals(1, rig.loads)
    assertFalse(e.closed)
    assertEquals(listOf("load"), rig.events)
    assertEquals(null, rig.repairedReason)
    assertTrue(rig.cacheFile.exists())
  }

  @Test fun `a corrupt cache is detected, cleared and the reload is healthy`() {
    val rig = Rig()
    rig.corruptCache("GARBAGE")
    val e = rig.run()
    assertEquals("loaded twice", 2, rig.loads)
    assertEquals("the bad engine is closed before the cache goes, then the reload", listOf("load", "close", "clear(1)", "load"), rig.events)
    assertEquals("OK", e.mode)
    assertFalse(e.closed)
    assertNotNull(rig.repairedReason)
    assertTrue("the reason names a cosine: ${rig.repairedReason}", rig.repairedReason!!.contains("cosine"))
    assertEquals("only this model's cache files were deleted", "keep", rig.other.readText())
    assertEquals("keep", rig.unrelated.readText())
    assertEquals("the reload wrote a fresh cache", "OK", rig.cacheFile.readText())
  }

  @Test fun `a NaN vector is detected`() {
    val rig = Rig()
    rig.corruptCache("NAN")
    val e = rig.run()
    assertEquals(2, rig.loads)
    assertTrue(rig.repairedReason!!.contains("NaN"))
    assertEquals("OK", e.mode)
  }

  @Test fun `an engine that is still bad after a fresh cache is closed, not served, and the cache left empty`() {
    val rig = Rig(afterClear = "GARBAGE")
    rig.corruptCache("GARBAGE")
    try {
      rig.run()
      fail("expected EngineUnhealthyException")
    } catch (e: EngineUnhealthyException) {
      assertTrue(e.message!!.contains("cosine"))
    }
    assertEquals("one reload only", 2, rig.loads)
    assertEquals(listOf("load", "close", "clear(1)", "load", "close", "clear(1)"), rig.events)
    assertFalse("nothing from the failed attempt is left to trust", rig.cacheFile.exists())
    assertEquals(null, rig.repairedReason)
    assertEquals("keep", rig.other.readText())
  }

  @Test fun `a bundle with no golden vectors is not checked, with a log line`() {
    val rig = Rig()
    rig.corruptCache("GARBAGE")   // would fail the check if it ran
    val e = rig.run(name = "some-newer-bundle.litertlm")
    assertEquals(1, rig.loads)
    assertEquals("GARBAGE", e.mode)
    assertEquals(listOf("load"), rig.events)
    assertTrue("the skip is logged: ${rig.logs}", rig.logs.any { it.contains("no golden probe for some-newer-bundle.litertlm") })
    assertEquals(EngineProbe.Verdict.Unchecked, probe.check("some-newer-bundle.litertlm") { error("must not embed") })
    assertEquals("an empty probe set checks nothing either", EngineProbe.Verdict.Unchecked, EngineProbe.NONE.check(bundle) { error("must not embed") })
  }

  @Test fun `the threshold is a cosine of 0 point 99`() {
    assertEquals(0.99f, EngineProbe.MIN_COSINE, 0f)
    val near = Rig(); near.corruptCache("NEAR")
    near.run()
    assertEquals("0.98 is bad", 2, near.loads)
    val close = Rig(); close.corruptCache("CLOSE")
    close.run()
    assertEquals("0.995 passes", 1, close.loads)
  }

  @Test fun `a wrong dimension and a throwing probe are failures too`() {
    for (mode in listOf("SHORT", "THROW")) {
      val rig = Rig(); rig.corruptCache(mode)
      val e = rig.run()
      assertEquals("$mode repaired by the reload", 2, rig.loads)
      assertEquals("OK", e.mode)
    }
    val reason = (probe.check(bundle) { throw IllegalStateException("secret text $it") } as EngineProbe.Verdict.Bad).reason
    assertFalse("a thrown message is never copied: $reason", reason.contains("secret") || reason.contains("probe one"))
  }

  @Test fun `no probe text is ever logged`() {
    val rig = Rig(afterClear = "GARBAGE"); rig.corruptCache("GARBAGE")
    val thrown = runCatching { rig.run() }.exceptionOrNull()
    assertTrue(thrown is EngineUnhealthyException)
    val all = (rig.logs + thrown!!.message.orEmpty()).joinToString("\n")
    for (t in texts) assertFalse("log has probe text: $all", all.contains(t))
  }

  @Test fun `the cache clearing takes this model's xnnpack files and nothing else`() {
    val dir = tmp.newFolder()
    val mine = listOf("m.litertlm_1_2.text_encoder.xnnpack_cache", "m.litertlm_1_2.vision_encoder.xnnpack_cache", "m.litertlm_9_9.audio_adapter.xnnpack_cache")
    mine.forEach { File(dir, it).writeText("x") }
    val keep = listOf("m2.litertlm_1_2.text_encoder.xnnpack_cache", "m.litertlm", "m.litertlm_1_2.json", "scratch")
    keep.forEach { File(dir, it).writeText("x") }
    assertEquals(3, EngineCache.clear(dir, "m.litertlm"))
    assertEquals(keep.sorted(), dir.list()!!.sorted())
    assertEquals("a missing folder is fine", 0, EngineCache.clear(File(dir, "nope"), "m.litertlm"))
  }

  // ---- the shipped asset ----------------------------------------------------------------------------------------

  private val shipped = File("src/main/assets/${EngineProbe.ASSET}").readText().let { EngineProbe.fromJson(it) }

  @Test fun `every stock bundle has three unit-length 768-d golden probes in the shipped asset`() {
    for (name in Embedder.MODEL_NAMES.filter { it !in Embedder.FINETUNED_NAMES } + Embedder.TEXT_ONLY_NAME) {
      assertTrue("no golden for $name", shipped.hasGolden(name))
      val checked = shipped.check(name) { text ->
        // the golden is its own vector: embedding the probe text must find it, so look it up through a check that returns it
        shippedVectors.getValue(name).getValue(text)
      }
      assertEquals(name, EngineProbe.Verdict.Healthy, checked)
      assertEquals(name, 3, shippedVectors.getValue(name).size)
      for (v in shippedVectors.getValue(name).values) {
        assertEquals(768, v.size)
        assertEquals(1.0, sqrt(v.sumOf { (it * it).toDouble() }), 1e-3)
        assertTrue(v.none { it.isNaN() })
      }
    }
  }

  private val shippedVectors: Map<String, Map<String, FloatArray>> by lazy {
    val root = org.json.JSONObject(File("src/main/assets/${EngineProbe.ASSET}").readText()).getJSONObject("bundles")
    root.keys().asSequence().associateWith { name ->
      val arr = root.getJSONArray(name)
      (0 until arr.length()).associate { i ->
        val o = arr.getJSONObject(i)
        val b = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(o.getString("vector"))).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        o.getString("text") to FloatArray(b.remaining() / 4) { b.getFloat() }
      }
    }
  }

  @Test fun `the probe texts use the app's real prefixes, in English and Luganda, and the stock bundles agree`() {
    val texts = shippedVectors.getValue(Embedder.TEXT_ONLY_NAME).keys.toList()
    assertEquals(3, texts.size)
    assertTrue(texts.count { it.startsWith(Embedder.QUERY_PREFIX) } == 2)
    assertTrue(texts.count { it.startsWith("title: none | text: ") } == 1)
    val a = shippedVectors.getValue(Embedder.TEXT_ONLY_NAME)
    for (name in listOf("embeddinggemma-2-text-vision-440m.litertlm", "embeddinggemma-2-740m.litertlm")) {
      for ((t, v) in shippedVectors.getValue(name)) assertTrue("$name shares the text model's vectors", EngineProbe.cosine(a.getValue(t), v) > 0.999f)
    }
  }

  @Test fun `an unknown bundle name (the fine-tuned ones, until they ship their own) is skipped by the real asset`() {
    for (name in Embedder.FINETUNED_NAMES) assertFalse(shipped.hasGolden(name))
    assertEquals(EngineProbe.Verdict.Unchecked, shipped.check("findr-eg2-ft-740m.litertlm") { error("must not embed") })
  }

  // ---- what the app does with it --------------------------------------------------------------------------------

  private fun src(path: String) = File("src/main/java/com/craneai/findr/$path").readText()

  /** The full edition's model code (LiteRT-LM, ONNX Runtime) lives in src/full, not src/main: see engine/Edition.kt. */
  private fun fullSrc(path: String) = File("src/full/java/com/craneai/findr/$path").readText()

  @Test fun `only one engine can exist in the shipped app, so cache creation needs no file lock`() {
    val manifest = File("src/main/AndroidManifest.xml").readText()
    assertFalse("a second process would have its own engine", manifest.contains("android:process"))
    val code = listOf("src/main/java", "src/full/java", "src/go/java")
    val creators = code.flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" && f.readText().contains("EmbeddingEngine(") }.map { f -> f.name }.toList() }
    assertEquals("the one place that builds an engine", listOf("LiteRtEmbedder.kt"), creators)
    val owners = code.flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" && Regex("""(?<!class )(?<![A-Za-z])LiteRtEmbedder\(""").containsMatchIn(f.readText()) }.map { f -> f.name }.toList() }
    assertEquals("the one place that builds the model embedder (quick panel, tile and widget use the app's engine)", listOf("EditionFactory.kt"), owners)
    assertTrue("the engine builds its embedder through the edition's factory", src("engine/FindrEngine.kt").contains("newEmbedder(context, profile)"))
    assertTrue(src("quick/QuickSearchActivity.kt").contains("(application as FindrApp).engine"))
    assertTrue("every load goes through ensure(), which callers reach only under the mutex", fullSrc("engine/LiteRtEmbedder.kt").let { it.contains("mutex.withLock { ensure(Level.TEXT) }") && it.contains("val e = ensure(needed)") })
  }

  @Test fun `the embedder verifies every engine it loads and the worker does not blame files for it`() {
    val e = fullSrc("engine/LiteRtEmbedder.kt")
    assertTrue(e.contains("selfTest.loadVerified("))
    assertEquals("one cache folder, handed to the engine in one place", 1, Regex("""cacheDir = context\.cacheDir""").findAll(e).count())
    assertTrue("the unhealthy state is reported through Diag", e.contains("HandledKind.ENGINE_UNHEALTHY"))
    val w = src("engine/IndexWorker.kt")
    val rethrow = w.indexOf("catch (e: EngineUnhealthyException)")
    assertTrue("understand() rethrows before its catch-all marks the file failed", rethrow in 1 until w.indexOf("FileState.FAILED, t.javaClass"))
    val f = src("engine/FindrEngine.kt")
    assertTrue("a query falls back to words without a warning per keystroke", f.contains("catch (e: EngineUnhealthyException)"))
    assertTrue(f.contains("override val searchRepairing"))
  }
}
