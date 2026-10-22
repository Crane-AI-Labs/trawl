package com.craneai.findr.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine construction must not open the index. It used to (a field initializer, run on the main thread by FindrApp.onCreate), so a
 * busy or unreadable index froze the app for about a minute and then crashed it on every launch. FindrEngine has no Android-free
 * constructor to call here, so these read the sources: the only open is the off-thread one StoreOpener runs, and the background
 * workers wait for it and skip the run when it is not there.
 */
class EngineOpenGuardTest {
  private fun src(name: String): String =
    listOf("src/main/java/com/craneai/findr/$name", "app/src/main/java/com/craneai/findr/$name").map(::File).first { it.exists() }.readText()

  private val engine = src("engine/FindrEngine.kt")

  @Test fun theOnlyIndexOpenIsTheOneTheOpenerRunsOffThread() {
    val calls = Regex("""IndexStore\.open\(""").findAll(engine).count()
    assertEquals("one open call, inside openIndex", 1, calls)
    val body = engine.substringAfter("private fun openIndex(").substringBefore("\n  }\n")
    assertTrue("the single open call lives in openIndex", body.contains("IndexStore.open("))
    assertTrue("openIndex is only handed to the opener", Regex("""open = ::openIndex""").containsMatchIn(engine))
    assertEquals("nothing else calls it", 0, Regex("""[^:]\bopenIndex\(""").findAll(engine.replace("private fun openIndex(", "")).count())
  }

  @Test fun theStoreIsNotAFieldOpenedWhenTheEngineIsBuilt() {
    assertFalse(Regex("""var\s+store\s*:\s*IndexStore\s*=""").containsMatchIn(engine))
    assertFalse(Regex("""val\s+store\s*:\s*IndexStore\s*=\s*IndexStore""").containsMatchIn(engine))
    assertTrue("the opener runs on an io dispatcher, not the caller's", engine.contains("ioDispatcher"))
  }

  @Test fun theAppDoesNotOpenAnythingAroundTheEngine() {
    val app = src("FindrApp.kt")
    assertFalse(app.contains("IndexStore.open"))
    assertFalse(app.contains("runBlocking"))
  }

  @Test fun workersWaitForTheStoreAndSkipTheRunWhenItIsNotThere() {
    val index = src("engine/IndexWorker.kt")
    assertTrue(index.contains("awaitStore() == null"))
    assertTrue(index.contains("Result.retry()"))
    assertTrue(src("engine/ChangeWorker.kt").contains("awaitStore() == null"))
  }

  @Test fun aStoreNotReadyInBackgroundWorkIsSkippedNotCrashed() {
    assertTrue(engine.contains("StoreNotReadyException"))
    assertTrue(engine.contains("CoroutineExceptionHandler"))
  }
}
