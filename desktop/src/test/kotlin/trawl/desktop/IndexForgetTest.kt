package trawl.desktop

import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.IndexStore
import com.craneai.findr.search.VectorFile
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IndexForgetTest {
  @Test fun stopsWorkerClosesStoreZeroesEveryFileThenReopensAndReadsFoldersAgain() {
    val data = Files.createTempDirectory("trawl-forget-order").toFile()
    val root = File(data, "documents").apply { mkdirs() }
    val document = File(root, "garden.txt").apply { writeText("garden orchids in the sunshine") }
    val opened = Collections.synchronizedList(ArrayList<IndexStore>())
    val worker = AtomicReference<Thread>()
    val shredded = ArrayList<String>()
    var snapshot = emptySet<String>()
    val service = IndexService(data,
      openStore = { retry ->
        if (opened.isNotEmpty()) assertTrue(snapshot.all { it in shredded }, "every old file was shredded before opening a fresh store")
        IndexStore.open(File(data, "index"), onRetry = retry).also { opened += it }
      },
      freeSpace = { directory -> worker.set(Thread.currentThread()); DiskSpace.freeBytes(directory) },
      shredFile = { file ->
        assertFalse(assertNotNull(worker.get()).isAlive, "the indexing worker stopped before shredding")
        assertFails("the old store was closed before shredding") { opened.first().write { it.long("SELECT COUNT(*) FROM files") } }
        val length = file.length()
        assertEquals(length, VectorFile.shred(file))
        assertEquals(length, file.length())
        assertTrue(file.readBytes().all { it == 0.toByte() }, "every byte is overwritten before deletion")
        shredded += file.name
        length
      },
    )
    try {
      service.setFolders(listOf(root))
      runBlocking { withTimeout(30_000) { service.ui.first { it.phase == Phase.UP_TO_DATE && it.total == 1 } } }
      File(data, "index/vectors.bin").writeBytes(ByteArray(65_537) { 91 })
      File(data, "index/old.corrupt-copy").writeText("old learned words")
      snapshot = File(data, "index").listFiles()!!.filter { it.isFile && !it.name.endsWith("-wal") && !it.name.endsWith("-shm") }.map { it.name }.toSet()
      val forgot = service.forgetAll()
      assertEquals(1, forgot.files)
      assertTrue(forgot.bytes > 0)
      assertEquals(2, opened.size)
      assertFalse(File(data, "index/vectors.bin").exists())
      assertFalse(File(data, "index/old.corrupt-copy").exists())
      runBlocking { withTimeout(30_000) { service.ui.first { it.phase == Phase.UP_TO_DATE && it.total == 1 } } }
      assertEquals("garden orchids in the sunshine", document.readText())
      assertEquals(1, service.search("orchids", KindFilter.ALL).hits.size, "the fresh reader is bound to the new store")
    } finally { service.close(); data.deleteRecursively() }
  }

  @Test fun existingReaderFinishesBeforeTheStoreIsClosedAndNewReadersDoNotWait() = runBlocking {
    val data = Files.createTempDirectory("trawl-forget-reader").toFile()
    val shredStarted = CountDownLatch(1)
    val service = IndexService(data, shredFile = { file -> shredStarted.countDown(); VectorFile.shred(file) })
    try {
      withTimeout(30_000) { service.ui.first { !it.opening && it.openProblem == null } }
      val reader = assertNotNull(service.access.acquire())
      val wiping = async(Dispatchers.IO) { service.forgetAll() }
      try {
        withTimeout(10_000) {
          while (service.access.read(false) { true }) kotlinx.coroutines.delay(10)
        }
        assertFalse(shredStarted.await(100, TimeUnit.MILLISECONDS), "the store must not be shredded under an existing reader")
        assertTrue(service.recent(KindFilter.ALL).isEmpty(), "a new UI read is refused rather than waiting for the wipe")
      } finally { reader.close() }
      withTimeout(30_000) { wiping.await() }
      assertTrue(shredStarted.await(10, TimeUnit.SECONDS))
    } finally { service.close(); data.deleteRecursively() }
  }
}
