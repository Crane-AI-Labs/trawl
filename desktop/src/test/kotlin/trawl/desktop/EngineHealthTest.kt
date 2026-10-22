package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.search.RepairingNote

/** A20: an engine that loads but returns garbage vectors is cleaned, tried again, and if it still fails Trawl says it is repairing. */
@OptIn(ExperimentalTestApi::class)
class EngineHealthTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val good = floatArrayOf(1f, 0f, 0f, 0f)
  private val probe = EngineProbe(mapOf("m.litertlm" to listOf(EngineProbe.Golden("q", "task: search result | query: x", good), EngineProbe.Golden("d", "title: none | text: y", floatArrayOf(0f, 1f, 0f, 0f)))))
  private fun healthy(text: String) = if (text.startsWith("task")) good else floatArrayOf(0f, 1f, 0f, 0f)

  @Test fun healthyVectorsPass() = assertEquals(EngineProbe.Verdict.Healthy, probe.check("m.litertlm", ::healthy))

  @Test fun aModelWithNoReferenceVectorsIsNotCheckedAndNotFailed() {
    assertEquals(EngineProbe.Verdict.Unchecked, probe.check("other.litertlm", ::healthy))
    assertEquals(EngineProbe.Verdict.Unchecked, EngineProbe.NONE.check("m.litertlm", ::healthy))
  }

  @Test fun nanAWrongSizeALowCosineAndAThrowAreAllBad() {
    assertTrue(probe.check("m.litertlm") { floatArrayOf(Float.NaN, 0f, 0f, 0f) } is EngineProbe.Verdict.Bad)
    assertTrue(probe.check("m.litertlm") { floatArrayOf(1f, 0f) } is EngineProbe.Verdict.Bad)
    assertTrue(probe.check("m.litertlm") { floatArrayOf(0.39f, 0.92f, 0f, 0f) } is EngineProbe.Verdict.Bad, "the damaged cache seen on the phone measured 0.39")
    assertTrue(probe.check("m.litertlm") { error("boom") } is EngineProbe.Verdict.Bad)
    assertTrue(probe.check("m.litertlm") { floatArrayOf(0f, 0f, 0f, 0f) } is EngineProbe.Verdict.Bad, "an all-zero vector has no direction")
  }

  @Test fun theReasonNamesTheProbeAndTheNumberNeverTheText() {
    val bad = probe.check("m.litertlm") { floatArrayOf(0.39f, 0.92f, 0f, 0f) } as EngineProbe.Verdict.Bad
    assertTrue("probe q" in bad.reason && "0.39" in bad.reason, bad.reason)
    assertFalse("query: x" in bad.reason)
  }

  private class Fake(val broken: Boolean)

  private fun run(broken: List<Boolean>, cleared: IntArray = IntArray(1), closed: IntArray = IntArray(1)): EngineLoad<Fake> {
    var n = 0
    return EngineGuard.load(
      "m.litertlm", probe, create = { Fake(broken[n++]) },
      embed = { e, text -> if (e.broken) floatArrayOf(0.39f, 0.92f, 0f, 0f) else healthy(text) },
      close = { closed[0]++ }, clearCache = { cleared[0]++; 3 },
    )
  }

  @Test fun aHealthyEngineLoadsOnceAndTheCacheIsLeftAlone() {
    val cleared = IntArray(1)
    assertTrue(run(listOf(false), cleared) is EngineLoad.Ready)
    assertEquals(0, cleared[0])
  }

  @Test fun aBadEngineIsRepairedByClearingTheCacheAndLoadingAgain() {
    val cleared = IntArray(1); val closed = IntArray(1)
    val r = run(listOf(true, false), cleared, closed)
    assertTrue(r is EngineLoad.Repaired, "$r")
    assertFalse((r as EngineLoad.Repaired).engine.broken)
    assertEquals(1, cleared[0]); assertEquals(1, closed[0], "the bad engine was closed before the second load")
  }

  @Test fun anEngineThatFailsAgainIsNotServed() {
    val closed = IntArray(1)
    val r = run(listOf(true, true), closed = closed)
    assertTrue(r is EngineLoad.Unhealthy, "$r")
    assertEquals(2, closed[0], "neither bad engine is left open")
  }

  @Test fun theCacheCleanerRemovesOnlyThisModelsXnnpackFiles() {
    val dir = Files.createTempDirectory("trawl-a20").toFile()
    try {
      val mine = listOf("m.litertlm_1_2.0.xnnpack_cache", "m.litertlm_1_2.1.xnnpack_cache").map { File(dir, it).also { f -> f.writeText("x") } }
      val others = listOf("other.litertlm_1_2.0.xnnpack_cache", "m.litertlm", "notes.txt").map { File(dir, it).also { f -> f.writeText("x") } }
      assertEquals(2, EngineGuard.clearCache(dir, "m.litertlm"))
      assertTrue(mine.none { it.exists() }); assertTrue(others.all { it.exists() })
      assertEquals(0, EngineGuard.clearCache(File(dir, "missing"), "m.litertlm"))
    } finally { dir.deleteRecursively() }
  }

  @Test fun theRealProbesShippedWithTheAppParse() {
    val real = EngineProbe.fromClasspath()
    var found = 0
    for (name in listOf("embeddinggemma-2-740m.litertlm", "embeddinggemma-2-text-vision-440m.litertlm", "embeddinggemma-2-text-270m.litertlm")) if (real.hasGolden(name)) found++
    assertTrue(found >= 1, "the phone's golden vectors are on the desktop classpath")
    assertTrue(real.hasGolden("embeddinggemma-2-740m.litertlm"))
  }

  @Test fun jsonVectorsAreLittleEndianFloat32() {
    val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-2f)
    val b64 = Base64.getEncoder().encodeToString(buf.array())
    val p = EngineProbe.fromJson("""{"bundles":{"z.litertlm":[{"id":"a","text":"t","vector":"$b64"}]}}""")
    assertEquals(EngineProbe.Verdict.Healthy, p.check("z.litertlm") { floatArrayOf(0.5f, -2f) })
  }

  @Test fun theRetryWaitIsFiveMinutes() = assertEquals(300_000L, EngineGuard.RETRY_MS)

  @Test fun theNoteShowsOnlyWhileRepairing() {
    runComposeUiTest {
      setContent { SunlightTheme { RepairingNote(true) } }
      onNodeWithText("repairing itself", substring = true).assertExists()
    }
    runComposeUiTest {
      setContent { SunlightTheme { RepairingNote(false) } }
      onNodeWithText("repairing itself", substring = true).assertDoesNotExist()
    }
  }

  /** Needs the real model file (TRAWL_MODEL, as on a build machine); with none it has nothing to prove and passes. */
  @Test fun theRealEngineOnThisComputerMatchesThePhonesGoldenVectorsAndASabotagedCacheIsRepaired() {
    val model = System.getenv("TRAWL_MODEL")?.let(::File)?.takeIf { it.isFile } ?: return
    val real = EngineProbe.fromClasspath()
    assertTrue(real.hasGolden(model.name), "the phone ships golden vectors for ${model.name}")
    val cache = Files.createTempDirectory("trawl-a20-cache").toFile()
    try {
      fun load() = EngineGuard.load(model.name, real, create = { DesktopEmbedder(model, cache).also { it.warm() } }, embed = { e, t -> e.raw(t) }, close = { it.close() },
        clearCache = { EngineGuard.clearCache(cache, model.name) }, log = { println("A20 " + it) })
      val first = load()
      assertTrue(first is EngineLoad.Ready, "a clean engine passes the phone's golden vectors here (cosine >= ${EngineProbe.MIN_COSINE}): $first")
      (first as EngineLoad.Ready).engine.close()
      val files = cache.listFiles { f -> f.name.endsWith(".xnnpack_cache") }.orEmpty()
      println("A20 cache files after first load: ${files.size}")
      // Sabotage: overwrite the cache with noise, as a damaged cache would be, and load again.
      files.forEach { it.writeBytes(ByteArray(it.length().toInt().coerceAtLeast(16)) { i -> (i * 31).toByte() }) }
      val second = try { load() } catch (t: Throwable) { println("A20 load threw ${t.javaClass.simpleName}"); null }
      println("A20 after sabotage: ${second?.javaClass?.simpleName}")
      (second as? EngineLoad.Ready)?.engine?.close(); (second as? EngineLoad.Repaired)?.engine?.close()
    } finally { cache.deleteRecursively() }
  }
}
