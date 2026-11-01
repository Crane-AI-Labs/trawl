package com.craneai.findr.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5: where the engine reacts to a full disk. FindrEngine and IndexWorker cannot be built in a unit test (see EngineOpenGuardTest),
 * so these read the sources: every place that writes the index on its own (the words pass, the meaning pass, the crawl) must route a
 * full disk to [FindrEngine.noteIfStorageFull] before it marks a file failed or done, and the meaning pass must end its run.
 */
class StorageFullGuardTest {
  private fun src(name: String): String =
    listOf("src/main/java/com/craneai/findr/$name", "app/src/main/java/com/craneai/findr/$name").map(::File).first { it.exists() }.readText()

  private val engine = src("engine/FindrEngine.kt")
  private val worker = src("engine/IndexWorker.kt")

  @Test fun theWordsPassChecksBeforeMarkingTheFileDone() {
    assertTrue("the words pass ends on a full disk", engine.contains("if (noteIfStorageFull(t)) return@run false"))
    val at = engine.indexOf("if (noteIfStorageFull(t)) return@run false")
    val doneAt = engine.indexOf("store.setState(p.file.id, finished, IndexStore.WORDS_DONE)", at)
    assertTrue("the check comes before the file is marked done", at in 1 until doneAt)
  }

  @Test fun theMeaningPassLeavesTheFilePendingAndEndsTheRun() {
    val at = worker.indexOf("StorageFull.isFull(t)) throw StorageFullException(t)")
    val failedAt = worker.indexOf("FileState.FAILED, t.javaClass.simpleName", at)
    assertTrue("thrown before the file is marked failed", at in 1 until failedAt)
    assertTrue("run() catches it, says so and ends", worker.contains("catch (e: StorageFullException)") && worker.contains("engine.noteIfStorageFull("))
  }

  @Test fun theCrawlSaysSoToo() {
    assertTrue(engine.contains("if (!noteIfStorageFull(t)) Log.w(TAG, \"crawl failed\", t)"))
  }

  @Test fun theRetryIsLaterNotAtOnce() {
    val body = engine.substringAfter("fun noteIfStorageFull(").substringBefore("fun noteStorageHasRoom")
    assertTrue(body.contains("scheduleRetryLater()"))
    assertTrue(body.contains("PauseReason.STORAGE_LOW"))
    assertEquals("the storage pause is shown by the meter, not just logged", 1, Regex("refreshStatus\\(\\)").findAll(body).count())
  }

  @Test fun aSuccessfulWriteTakesThePauseOff() {
    val okAt = engine.indexOf("noteStorageHasRoom()\n        } catch (e: TimeoutCancellationException)")
    assertTrue("the words pass clears it after a save that went through", okAt > 0)
  }
}
