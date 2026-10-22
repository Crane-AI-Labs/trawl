package com.craneai.findr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueryVectorCacheTest {
  private var embeds = 0
  private val seen = ArrayList<String>()

  /** A stand-in model: the vector is a pure function of the text and the model, like the real one. */
  private fun embed(model: Int): suspend (String) -> FloatArray = { text ->
    embeds++; seen += text
    FloatArray(4) { i -> (text.hashCode() % 997) + i + model * 1000f }
  }

  private fun get(c: QueryVectorCache, fp: String, text: String, model: Int = 1) = runBlocking { c.get(fp, text, embed(model)) }!!

  @Test fun `a hit skips the embed and returns the same vector`() {
    val c = QueryVectorCache()
    val first = get(c, "m1", "invoice")
    val second = get(c, "m1", "invoice")
    assertEquals(1, embeds)
    assertArrayEquals(first, second, 0f)
    assertEquals(1L, c.hits); assertEquals(1L, c.misses)
  }

  @Test fun `spelling variants of the same query share one entry and the model sees the normalised text`() {
    val c = QueryVectorCache()
    get(c, "m1", "  red   car\n")
    get(c, "m1", "red car")
    get(c, "m1", "red\tcar ")
    assertEquals(1, embeds)
    assertEquals(listOf("red car"), seen)
  }

  @Test fun `case is part of the key`() {
    val c = QueryVectorCache()
    get(c, "m1", "Apple"); get(c, "m1", "apple")
    assertEquals(2, embeds)
  }

  @Test fun `a new model fingerprint invalidates everything`() {
    val c = QueryVectorCache()
    val old = get(c, "m1", "invoice", model = 1)
    val new = get(c, "m2", "invoice", model = 2)
    assertEquals(2, embeds)
    assertFalse(old.contentEquals(new))
    // and the old model's entries are gone, not just shadowed
    get(c, "m1", "invoice", model = 1)
    assertEquals(3, embeds)
  }

  @Test fun `clear forgets every entry`() {
    val c = QueryVectorCache()
    get(c, "m1", "a"); get(c, "m1", "b")
    assertEquals(2, c.size)
    c.clear()
    assertEquals(0, c.size)
    get(c, "m1", "a")
    assertEquals(3, embeds)
  }

  @Test fun `an embed that finishes after a clear is not stored`() {
    val c = QueryVectorCache()
    val v = runBlocking {
      c.get("m1", "slow") { text -> c.clear(); embeds++; FloatArray(2) { text.length.toFloat() } }   // the lock closes mid-embed
    }
    assertEquals(2, v!!.size)
    assertEquals(0, c.size)
  }

  @Test fun `a failed embed stores nothing and the next call tries again`() {
    val c = QueryVectorCache()
    try { runBlocking { c.get("m1", "boom") { error("no model") } } } catch (_: IllegalStateException) { }
    assertEquals(0, c.size)
    get(c, "m1", "boom")
    assertEquals(1, embeds)
  }

  @Test fun `no vector is not stored`() {
    val c = QueryVectorCache()
    assertEquals(null, runBlocking { c.get("m1", "x") { null } })
    assertEquals(0, c.size)
  }

  @Test fun `least recently used goes first`() {
    val c = QueryVectorCache(capacity = 3)
    get(c, "m1", "a"); get(c, "m1", "b"); get(c, "m1", "c")
    get(c, "m1", "a")            // a is fresh again, b is the oldest
    get(c, "m1", "d")            // evicts b
    assertEquals(3, c.size)
    embeds = 0
    get(c, "m1", "a"); get(c, "m1", "c"); get(c, "m1", "d")
    assertEquals(0, embeds)
    get(c, "m1", "b")
    assertEquals(1, embeds)
  }

  @Test fun `callers cannot change a cached vector`() {
    val c = QueryVectorCache()
    val a = get(c, "m1", "x")
    val expected = a.copyOf()
    a.fill(-1f)
    assertArrayEquals(expected, get(c, "m1", "x"), 0f)
    get(c, "m1", "x").fill(-2f)
    assertArrayEquals(expected, get(c, "m1", "x"), 0f)
  }

  // ---- the wiring that only runs on a phone is checked at the source, as LogHygieneTest does for logs ------------------

  // The app's sources, or :core's for the files that moved there.
  private fun src(path: String): String {
    val file = listOf("src/main/java", "../core/src/jvmCommonMain/kotlin").map { File("$it/com/craneai/findr/$path") }.firstOrNull { it.isFile }
    assertTrue("run from the app module: $path", file != null)
    return file!!.readText()
  }

  @Test fun `the engine clears the cache when everything is deleted and routes both vectors through it`() {
    val engine = src("engine/FindrEngine.kt")
    val delete = engine.substringAfter("override suspend fun deleteIndex()").substringBefore("override suspend fun folderCounts")
    assertTrue("deleteIndex must clear the query cache", "clearQueryCache()" in delete)
    assertTrue("EmbeddingGemma's query must go through the cache", "queryCache.get(" in engine && "embedder.query(it)" in engine)
    assertFalse("nothing may call embedder.query outside the cache", Regex("""embedder\.query\(q\)""").containsMatchIn(engine))
    assertTrue("the quick-look text tower goes through its own cache", "quickQueryCache.get(" in engine)
  }

  @Test fun `the app lock closing and memory trims clear the cache`() {
    val lock = src("privacy/PrivacyController.kt")
    val publish = lock.substringAfter("private fun publish()").substringBefore("engine.setSensitiveRevealed")
    assertTrue("publish() must clear the cache when the lock closes", "engine.clearQueryCache()" in publish && "gate.isLocked && !_locked.value" in publish)
    val app = src("FindrApp.kt")
    assertTrue("onTrimMemory must clear the cache", "override fun onTrimMemory" in app && "engine.clearQueryCache()" in app)
  }

  @Test fun `the cache is memory only and logs no text`() {
    val text = src("engine/QueryVectorCache.kt")
    assertFalse("no disk", Regex("""java\.io|FileOutputStream|SharedPreferences|SQLite|openFileOutput|writeText""").containsMatchIn(text))
    assertFalse("no logging", Regex("""\bLog\b|println|FindrLog""").containsMatchIn(text.substringAfter("internal class")))
  }
}
