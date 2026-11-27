package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Modality
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What a big library costs the desktop's heap, with the vectors on the heap (the desktop until 1.0.9) and in a mapped file (the phone,
 * and the desktop after the 12 Nov crash). Each mode runs in its own JVM with the packaged app's `-Xmx1g` and default collector, adds
 * the rows one by one as the indexer does, then asks queries. It prints one `RESULT` line a mode: heap peak and retained, GC count and
 * time, process CPU, RSS, add and query times, or the error that stopped it.
 *
 *   FINDR_HEAP_BENCH=300000 ./gradlew :core:jvmTest --tests '*VectorIndexHeapBench*' -i | grep RESULT
 *
 * Optional: FINDR_HEAP_BENCH_XMX (default 1g), FINDR_HEAP_BENCH_MODES (default "heap,mapped"), FINDR_HEAP_BENCH_BALLAST_MB (default 0: heap
 * the rest of the app keeps alive, the UI, SQLite's caches, the engine's buffers) and FINDR_HEAP_BENCH_CHURN_KB (default 0: short-lived
 * garbage made per row added, as reading a file makes).
 */
class VectorIndexHeapBench {
  @Test fun heapAgainstMapped() {
    val rows = System.getenv("FINDR_HEAP_BENCH")?.toIntOrNull()
    assumeTrue("set FINDR_HEAP_BENCH=<rows> to run", rows != null)
    val xmx = System.getenv("FINDR_HEAP_BENCH_XMX") ?: "1g"
    val modes = (System.getenv("FINDR_HEAP_BENCH_MODES") ?: "heap,mapped").split(',')
    val javaBin = File(System.getProperty("java.home"), "bin/java").path
    val cp = System.getProperty("java.class.path")
    for (mode in modes) {
      val dir = Files.createTempDirectory("heapbench").toFile()
      val ballast = System.getenv("FINDR_HEAP_BENCH_BALLAST_MB") ?: "0"
      val churn = System.getenv("FINDR_HEAP_BENCH_CHURN_KB") ?: "0"
      val p = ProcessBuilder(javaBin, "-Xmx$xmx", "-cp", cp, Child::class.java.name, mode, rows.toString(), dir.path, ballast, churn)
        .redirectErrorStream(true).start()
      val text = p.inputStream.bufferedReader().readText()
      p.waitFor(30, TimeUnit.MINUTES)
      dir.deleteRecursively()
      text.lines().filter { it.startsWith("RESULT") || it.startsWith("ERROR") }.forEach { println("$it xmx=$xmx ballastMb=$ballast churnKb=$churn") }
    }
  }

  object Child {
    @JvmStatic fun main(args: Array<String>) {
      val mode = args[0]; val rows = args[1].toInt(); val dir = File(args[2])
      val ballast = Array(args.getOrNull(3)?.toInt() ?: 0) { ByteArray(1 shl 20) }
      val churnBytes = (args.getOrNull(4)?.toInt() ?: 0) * 1024
      val random = Random(11)
      val backing = if (mode == "mapped") File(dir, "vectors.bin") else null
      val gen = longArrayOf(0)
      val index = VectorIndex(parallelism = 4, backing = backing, settleDelayMs = -1)
      index.loadFrom({ gen[0] }) { }
      val cpu0 = cpuNs()
      val t0 = System.nanoTime()
      var added = 0
      var error: String? = null
      try {
        // The same vector values cycle (making 300k random vectors costs more than indexing them); every row is still its own copy.
        val pool = List(512) { unit(random) }
        for (s in 1..rows) {
          if (churnBytes > 0) sink = ByteArray(churnBytes).also { it[s % churnBytes] = 1 }
          index.add(s.toLong(), (s / 4).toLong(), FileKind.PDF, Modality.TEXT, false, pool[s % pool.size])
          added = s
        }
      } catch (t: Throwable) {
        error = "${t.javaClass.simpleName} at row ${added + 1}: ${t.message}"
      }
      val addMs = (System.nanoTime() - t0) / 1_000_000
      val queryMs = if (error == null) {
        val q = List(21) { unit(random) }
        index.scores(q[0])   // warm
        q.drop(1).map { val t = System.nanoTime(); index.scores(it); (System.nanoTime() - t) / 1e6 }.sorted()[10]
      } else Double.NaN
      val cpuMs = (cpuNs() - cpu0) / 1_000_000
      val gcs = ManagementFactory.getGarbageCollectorMXBeans()
      val gcCount = gcs.sumOf { it.collectionCount }
      val gcMs = gcs.sumOf { it.collectionTime }
      val heapPools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
      val peakMb = heapPools.sumOf { it.peakUsage.used } / MB
      val oldPeakMb = heapPools.filter { "Old" in it.name || "Tenured" in it.name }.sumOf { it.peakUsage.used } / MB
      System.gc()
      val retainedMb = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / MB
      check(ballast.size >= 0 && sink.size >= 0)   // both stay reachable to here
      val hwm = File("/proc/self/status").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("VmHWM") }?.filter(Char::isDigit)?.toLong()?.div(1024)
      println(
        "RESULT mode=$mode rows=$rows added=$added addMs=$addMs queryMedianMs=${"%.1f".format(queryMs)} cpuMs=$cpuMs gcCount=$gcCount gcMs=$gcMs " +
          "heapPeakSumMb=$peakMb oldGenPeakMb=$oldPeakMb retainedHeapMb=$retainedMb indexHeapMb=${index.heapBytes() / MB} mappedMb=${index.mappedBytes() / MB} rssPeakMb=$hwm" +
          (error?.let { " error=\"$it\"" } ?: ""),
      )
      // Restart cost: a settled file is adopted (mapped mode); the heap index can only be rebuilt (here from memory, so a floor: the app reads SQLite).
      if (error == null && mode == "mapped") {
        index.settleNow(); index.close()
        val again = VectorIndex(parallelism = 4, backing = backing, settleDelayMs = -1)
        val t = System.nanoTime()
        again.loadFrom({ gen[0] }) { error("rebuilt instead of adopted") }
        println("RESULT mode=mapped-restart rows=${again.size} adoptMs=${(System.nanoTime() - t) / 1_000_000}")
      }
    }

    @Volatile private var sink = ByteArray(0)

    private fun cpuNs(): Long = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime

    private fun unit(random: Random): FloatArray {
      val v = FloatArray(768) { (random.nextDouble() * 2 - 1).toFloat() }
      val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      return FloatArray(v.size) { v[it] / n }
    }

    private const val MB = 1024L * 1024
  }
}
