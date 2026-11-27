package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.TwoStage
import com.craneai.findr.search.VectorFormat
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression test for 22 Nov: a developer Mac, a 3.6 GB index (730k rows), the installed app's `-Xmx1g`: "index open failed (MEMORY)", then
 * the same failure every minute for 40 hours. This builds a library of [ROWS] vector rows (768-d fp16, as shipped) in the real database and
 * opens it with the real [IndexService] in a child JVM that runs with the heap flags the installers carry (`shippedHeapArgs` in
 * desktop/build.gradle.kts, passed in as a system property), on a computer simulated as the smallest we expect (2 GB of RAM: the percentage
 * gives the child 718 MB, less than the old fixed 1 GB). It asserts the index opens and answers a words search, that a second start adopts
 * the settled vector file instead of reading every vector again, that the vector table is in the mapped file and not on the heap, and that a
 * meaning search through [Searcher] finds a planted vector. The words are the real service's, the vectors the real index's: only the
 * numbers in them are synthetic (a pool of 512 random unit vectors, and one planted).
 *
 * Takes about a minute and a half (most of it writing the database). Rows: `TRAWL_OPEN_ROWS` (default 300,000); `TRAWL_OPEN_ROWS=0` skips.
 */
class IndexOpensUnderShippedHeapTest {
  @Test fun aBigIndexOpensAndSearchesUnderTheHeapTheInstallersShip() {
    val rows = System.getenv("TRAWL_OPEN_ROWS")?.toIntOrNull() ?: ROWS
    if (rows == 0) return
    val shipped = System.getProperty("trawl.shippedHeapArgs")?.split(' ')?.filter { it.isNotBlank() }
    assertTrue(shipped != null && shipped.isNotEmpty(), "the build passes the heap flags the installers ship (trawl.shippedHeapArgs)")
    assertTrue(shipped.none { it.startsWith("-Xmx") }, "an explicit -Xmx would win over the percentage: the launcher must not carry one")
    val dir = Files.createTempDirectory("trawl-bigopen").toFile()
    try {
      val out = runChild(shipped, dir, rows)
      val r = out.lines().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
      fun field(k: String) = r[k] ?: throw AssertionError("child printed no $k:\n$out")
      println("RESULT rows=$rows " + r.entries.joinToString(" ") { "${it.key}=${it.value}" })
      val maxMb = field("maxHeapMb").toLong()
      assertTrue(maxMb in 600..800, "the shipped flags on a 2 GB computer give about 718 MB, not the machine's default: $maxMb MB\n$out")
      assertEquals("true", field("opened"), out)
      assertEquals("$rows", field("vectorsLoaded"), out)
      assertEquals("true", field("wordsHit"), out)
      assertEquals("true", field("adopted"), "the second start maps the settled file, it does not rebuild\n$out")
      assertEquals("true", field("offHeap"), out)
      assertEquals("true", field("plantedFirst"), "a meaning search finds the planted vector first\n$out")
      // What the index holds on the heap is its row facts and lookup (tens of bytes a row), a small part of the table's 1.5 KB a row.
      val heapPer100k = field("indexHeapMb").toDouble() / rows * 100_000
      assertTrue(heapPer100k < 15, "the index keeps $heapPer100k MB on the heap per 100k rows; the vector table (150 MB per 100k) must not be there")
      assertTrue(field("peakHeapMb").toLong() < maxMb * 0.8, "the open and the searches stay well inside the heap: peak ${field("peakHeapMb")} of $maxMb MB\n$out")
    } finally { dir.deleteRecursively() }
  }

  private fun runChild(shipped: List<String>, dir: File, rows: Int): String {
    val javaBin = File(System.getProperty("java.home"), "bin/java").path
    val cp = System.getProperty("java.class.path")
    // MaxRAM below the real memory makes the JVM size its heap as if the computer had that much (a 2 GB laptop).
    val cmd = listOf(javaBin) + shipped + listOf("-XX:MaxRAM=2g", "-Djava.awt.headless=true", "-cp", cp, Child::class.java.name, dir.path, rows.toString())
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    assertTrue(p.waitFor(20, TimeUnit.MINUTES), "child did not finish")
    assertEquals(0, p.exitValue(), "child failed:\n$text")
    return text
  }

  object Child {
    @JvmStatic fun main(args: Array<String>) {
      val data = File(args[0]); val rows = args[1].toInt()
      val random = Random(21)
      val pool = List(512) { unit(random) }
      val planted = unit(random)
      val perFile = 6
      val files = rows / perFile
      val index = File(data, "index")

      // 1. The library, written the way the indexer writes it: a crawl batch, then each file's segments with their vectors.
      val t0 = System.nanoTime()
      withStore(index) { store ->
        val now = System.currentTimeMillis()
        for (from in 1..files step 500) {
          store.upsertFromCrawl((from until minOf(from + 500, files + 1)).map { i ->
            CrawlRecord("primary", i.toLong(), "file:///library/note-$i.txt", "library/", "note-$i.txt", FileKind.DOCUMENT, "text/plain", 100, now - i * 1000L)
          })
        }
        var written = 0
        while (true) {
          val pending = store.nextPending(500, setOf(FileKind.DOCUMENT))
          if (pending.isEmpty()) break
          for (p in pending) {
            val isPlanted = written == files / 2
            val segs = List(perFile) { k ->
              val v = if (isPlanted && k == 0) planted else pool[(written * perFile + k) % pool.size]
              Segment(SegmentKind.CHUNK, Modality.TEXT, text = if (isPlanted && k == 0) "zebraquartz is the planted line" else "note $written part $k plain words", vector = v)
            }
            store.saveSegments(p.file.id, segs, FileState.UNDERSTOOD)
            written++
          }
        }
        println("built=$written files, ${written * perFile} rows, ${(System.nanoTime() - t0) / 1_000_000} ms")
      }

      // 2. The real service opens it: the vectors load from the database into the mapped file.
      var opened = false; var loaded = -1; var wordsHit = false
      val t1 = System.nanoTime()
      IndexService(data).use { ix ->
        opened = waitOpen(ix)
        println("firstOpenMs=${(System.nanoTime() - t1) / 1_000_000}")
        wordsHit = ix.search("zebraquartz", KindFilter.ALL).hits.isNotEmpty()
        loaded = ix.searchHooks.vectorCount()
      }
      println("opened=$opened"); println("vectorsLoaded=$loaded"); println("wordsHit=$wordsHit")

      // 3. A second start finds the file settled at the database's generation and adopts it.
      val t2 = System.nanoTime()
      IndexService(data).use { ix -> waitOpen(ix); println("secondOpenMs=${(System.nanoTime() - t2) / 1_000_000}") }

      // 4. The index on its own: adopts without reading a vector, keeps the table off the heap, and finds the planted vector by meaning.
      withStore(index) { store ->
        val vectors = VectorIndex(parallelism = 4, backing = File(index, IndexService.VECTORS_FILE), twoStage = TwoStage.DEFAULT, format = VectorFormat.SQ8)
        var adopted = true
        vectors.loadFrom({ store.vectorGeneration() }) { adopted = false }
        println("adopted=$adopted"); println("offHeap=${vectors.offHeap}"); println("indexHeapMb=${vectors.heapBytes() / MB}")
        val hit = Searcher(store, vectors).search("zebraquartz", planted, KindFilter.ALL, 20).hits.firstOrNull()
        println("plantedFirst=${hit != null && hit.file.name.startsWith("note-") && hit.snippet?.contains("zebraquartz") == true}")
        vectors.close()
      }

      val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
      println("maxHeapMb=${Runtime.getRuntime().maxMemory() / MB}")
      println("peakHeapMb=${heapPools.sumOf { it.peakUsage.used } / MB}")
      System.gc()
      println("retainedHeapMb=${ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / MB}")
    }

    private fun <T> withStore(index: File, block: (IndexStore) -> T): T {
      val store = IndexStore.open(index, log = {})
      try { return block(store) } finally { store.close() }
    }

    private fun waitOpen(ix: IndexService): Boolean {
      val end = System.currentTimeMillis() + 10 * 60_000
      while (System.currentTimeMillis() < end) {
        val u = ix.ui.value
        if (u.openProblem != null) return false
        if (!u.opening && ix.searchHooks.vectorCount() > 0) return true
        Thread.sleep(50)
      }
      return false
    }

    private fun unit(random: Random): FloatArray {
      val v = FloatArray(768) { (random.nextDouble() * 2 - 1).toFloat() }
      val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      return FloatArray(v.size) { v[it] / n }
    }

    private const val MB = 1024L * 1024
  }

  companion object {
    const val ROWS = 300_000
  }
}
