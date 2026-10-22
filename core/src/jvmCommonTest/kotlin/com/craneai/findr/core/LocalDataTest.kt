package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalDataTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun wipeDeletesTheIndexItsLogAndEveryCacheFile() {
    val index = tmp.newFolder("no_backup", "index")
    val cache = tmp.newFolder("cache")
    File(index, "findr-index.db").writeBytes(ByteArray(4096))
    File(index, "findr-index.db-wal").writeBytes(ByteArray(1000))
    File(index, "findr-index.db-shm").writeBytes(ByteArray(32))
    File(index, "findr-index.db.corrupt-123").writeBytes(ByteArray(10))
    File(LocalData.scratchDir(cache).apply { mkdirs() }, "extract123.pdf").writeBytes(ByteArray(7))
    File(cache, "image_cache").mkdirs()
    File(cache, "image_cache/abc.0").writeBytes(ByteArray(3))
    // LiteRT's compiled caches are built from the model, not from anyone's files: they stay.
    val modelCache = File(cache, "embeddinggemma-2-740m.litertlm_1782694132_484622336.text_encoder.xnnpack_cache").apply { writeBytes(ByteArray(11)) }
    val gone = LocalData.wipe(index, cache)
    assertEquals(6, gone.files)
    assertEquals("what Findr learned: the index and the temporary copy", 4096L + 1000 + 32 + 10 + 7, gone.bytes)
    assertEquals("the rest of the cache", 3L, gone.cacheBytes)
    assertTrue(index.isDirectory && index.listFiles()!!.isEmpty())
    assertEquals(listOf(modelCache), cache.listFiles()!!.toList())
  }

  @Test fun wipeLeavesSettingsAndModelsAlone() {
    val data = tmp.newFolder("data")
    val index = File(data, "no_backup/index").apply { mkdirs() }
    val cache = File(data, "cache").apply { mkdirs() }
    val prefs = File(data, "shared_prefs/findr.xml").apply { parentFile!!.mkdirs(); writeText("<map/>") }
    val model = File(data, "files/models/m.litertlm").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5)) }
    LocalData.wipe(index, cache)
    assertTrue(prefs.exists())
    assertTrue(model.exists())
  }

  @Test fun sweepDeletesLeftoverTemporaryCopiesOnly() {
    val cache = tmp.newFolder("cache")
    val scratch = LocalData.scratchDir(cache).apply { mkdirs() }
    File(scratch, "extract1.docx").writeBytes(ByteArray(1))
    File(scratch, "extract2.pdf").writeBytes(ByteArray(1))
    File(cache, "extract99.pdf").writeBytes(ByteArray(1))   // where older builds put them
    val runtimeCache = File(cache, "model.xnnpack_cache").apply { writeBytes(ByteArray(1)) }
    assertEquals(3, LocalData.sweepScratch(cache))
    assertTrue(scratch.listFiles()!!.isEmpty())
    assertFalse(File(cache, "extract99.pdf").exists())
    assertTrue("a runtime's own cache is not a temporary copy", runtimeCache.exists())
  }

  @Test fun sweepOfAMissingFolderIsANoOp() {
    assertEquals(0, LocalData.sweepScratch(File(tmp.root, "nothing-here")))
  }
}
