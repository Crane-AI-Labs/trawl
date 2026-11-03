package com.craneai.findr.harness

import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.index.IndexStore
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Searches one phone's own index (pulled with run-as from a debuggable side build) with the app's real [Searcher], so a before/after
 * comparison of that index is not limited by what fits on the phone's first screen. Does nothing unless FINDR_PROBE_DB is set.
 *
 *   FINDR_PROBE_DB=<dir with findr-index.db and its -wal/-shm>  FINDR_PROBE_QUERIES=<jsonl {"id","q","qv"}>
 *   FINDR_PROBE_QVECS=<float32 LE rows, 768 wide>  FINDR_PROBE_OUT=<jsonl>
 *
 * Out, one line per query: every hit in order as {"name","score","reason","at"}, `at` being the start of a time locator (ms) or null. The
 * database is copied to a temporary folder first (opening it may migrate it). Used for internal/research/old-videos-catchup.md.
 */
class PhoneIndexProbeTest {
  @Test fun probe() {
    val src = System.getenv("FINDR_PROBE_DB")
    assumeTrue("FINDR_PROBE_DB not set", src != null)
    val dir = Files.createTempDirectory("findr-probe").toFile()
    File(src!!).listFiles()!!.filter { it.name.startsWith(IndexStore.FILE_NAME) }.forEach { it.copyTo(File(dir, it.name)) }
    val store = IndexStore.open(dir, log = { println(it) })
    val vectors = VectorIndex(parallelism = 4)
    vectors.load(store)
    val searcher = Searcher(store, vectors)
    val raf = RandomAccessFile(File(System.getenv("FINDR_PROBE_QVECS")!!), "r")
    val qv = ByteBuffer.allocate(raf.length().toInt()).order(ByteOrder.LITTLE_ENDIAN).also { raf.channel.read(it); it.flip() }.asFloatBuffer()
    raf.close()
    val out = File(System.getenv("FINDR_PROBE_OUT")!!).also { it.parentFile?.mkdirs() }
    var n = 0
    out.bufferedWriter().use { w ->
      for (line in File(System.getenv("FINDR_PROBE_QUERIES")!!).readLines().filter { it.isNotBlank() }) {
        @Suppress("UNCHECKED_CAST") val q = MiniJson.parse(line) as Map<String, Any?>
        val row = (q["qv"] as Number).toInt()
        val vector = if (row < 0) null else FloatArray(768) { qv.get(row * 768 + it) }
        val hits = searcher.search(q["q"] as String, vector, KindFilter.ALL, AppPathHarness.LIMIT, prefixLast = true).hits
        w.write("{\"id\":${MiniJson.quote(q["id"].toString())},\"hits\":[" + hits.joinToString(",") { h ->
          "{\"name\":${MiniJson.quote(h.file.name)},\"score\":${h.score},\"reason\":\"${h.reason}\",\"at\":${(h.locator as? Locator.Time)?.startMs}}"
        } + "]}\n")
        n++
      }
    }
    println("probe: ${vectors.size} vectors, $n queries -> ${out.path}")
    vectors.close(); store.close(); dir.deleteRecursively()
  }
}
