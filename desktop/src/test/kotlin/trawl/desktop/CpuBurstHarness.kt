package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test

/**
 * The measuring harness behind internal/research/desktop-cpu-ts.md. It does nothing unless TRAWL_CPU_DIR is set (so the normal gate
 * skips it): it builds a `~/Documents`-like tree there (a 20k-file TypeScript `node_modules`, a few real videos, photos and notes),
 * runs the real [IndexService] and [FolderWatcher] on it, and prints what a coding agent writing into the tree costs: walks per
 * minute and CPU-seconds per minute while idle, in a burst of 1,000 small files, while paused, and when resumed.
 *
 *   TRAWL_CPU_DIR=/mnt/data/trawl-harness TRAWL_CPU_LABEL=before TRAWL_CPU_WIN=60 gradlew -p desktop test --tests '*CpuBurstHarness*'
 */
class CpuBurstHarness {
  private val dir: String? = System.getenv("TRAWL_CPU_DIR")?.takeIf { it.isNotBlank() }
  private val label = System.getenv("TRAWL_CPU_LABEL") ?: "run"
  private val win = (System.getenv("TRAWL_CPU_WIN") ?: "60").toLong()
  private val out = StringBuilder()

  private fun say(s: String) { println(s); out.appendLine(s) }

  private fun write(f: File, text: String) { f.parentFile.mkdirs(); f.writeText(text) }

  private fun ffmpeg(vararg args: String) {
    val p = ProcessBuilder(listOf("ffmpeg", "-y", "-v", "error") + args).redirectErrorStream(true).start()
    p.inputStream.readBytes(); p.waitFor(60, TimeUnit.SECONDS)
  }

  /** Builds the tree once; a marker file says it is complete. */
  private fun build(base: File): File {
    val docs = File(base, "Documents")
    val marker = File(base, "tree.done")
    if (marker.exists()) return docs
    base.deleteRecursively(); docs.mkdirs()
    val rnd = Random(7)
    // 1,000 packages x 20 TypeScript sources = 20,000 files in node_modules, as a coding project has.
    for (p in 0 until 1000) for (i in 0 until 20) {
      val sub = if (i % 4 == 0) "lib" else if (i % 4 == 1) "src" else "types"
      write(File(docs, "Projects/app/node_modules/pkg$p/$sub/f$i.ts"), "export const v$i = ${rnd.nextInt()};\nexport function f$i(a: number): number { return a + $i; }\n")
    }
    write(File(docs, "Projects/app/node_modules/pkg0/package.json"), "{\"name\":\"pkg0\"}")
    write(File(docs, "Projects/app/package.json"), "{\"name\":\"app\"}")
    for (i in 0 until 20) write(File(docs, "Projects/app/src/s$i.ts"), "export const s$i = $i;\n")
    for (i in 0 until 40) write(File(docs, "Projects/app/build/o$i.js"), "var o$i = $i;\n")
    // Notes: real documents the person searches.
    for (i in 0 until 100) write(File(docs, "notes/n$i.txt"), "Meeting note $i about the budget, the garden and the trip to Entebbe. ${rnd.nextInt()}\n")
    // Photos: small JPEGs with noise.
    for (i in 0 until 30) {
      val img = BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB)
      for (x in 0 until 96) for (y in 0 until 64) img.setRGB(x, y, rnd.nextInt() and 0xffffff)
      File(docs, "Photos").mkdirs()
      ImageIO.write(img, "jpg", File(docs, "Photos/p$i.jpg"))
    }
    // Real videos: three mp4, two MPEG transport streams (.ts and .mts) and one 192-byte-packet .m2ts, made by ffmpeg.
    val vids = File(docs, "Videos").apply { mkdirs() }
    for (i in 0 until 3) ffmpeg("-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p", File(vids, "clip$i.mp4").path)
    ffmpeg("-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p", "-f", "mpegts", File(vids, "camera.ts").path)
    ffmpeg("-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p", "-f", "mpegts", File(vids, "holiday.mts").path)
    ffmpeg("-f", "lavfi", "-i", "testsrc=duration=2:size=160x120:rate=10", "-pix_fmt", "yuv420p", "-f", "mpegts", "-mpegts_m2ts_mode", "1", File(vids, "avchd.m2ts").path)
    marker.writeText("ok")
    return docs
  }

  /** The real decoder (ffmpeg and ffprobe on Crane), counting what it is asked to open: the `.ts` family, and of those the ones inside node_modules. */
  private val tsProbes = LongAdder()
  private val nodeProbes = LongAdder()
  private val allProbes = LongAdder()
  private class CountingDecoder(val inner: MediaDecoder, val onProbe: (File) -> Unit) : MediaDecoder by inner {
    override fun probe(file: File): MediaInfo { onProbe(file); return inner.probe(file) }
  }
  private fun decoder(): MediaDecoder? = MediaDecoders.choose(File(dir!!, "helpers-$label"))?.let { d ->
    CountingDecoder(d) { f ->
      allProbes.increment()
      if (TransportStream_isTsName(f.name)) { tsProbes.increment(); if ("/node_modules/" in f.path) nodeProbes.increment() }
    }
  }
  private fun TransportStream_isTsName(n: String): Boolean = n.substringAfterLast('.', "").lowercase().let { it == "ts" || it == "mts" || it == "m2ts" }

  private class Snap(val at: Long, val proc: Long, val threads: Long, val walks: Long, val dirs: Long, val files: Long, val ts: Long, val all: Long)

  private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private val tmx = ManagementFactory.getThreadMXBean()

  private fun snap(): Snap {
    var t = 0L
    for (id in tmx.allThreadIds) {
      val name = tmx.getThreadInfo(id)?.threadName ?: continue
      if (name.startsWith("trawl-")) t += tmx.getThreadCpuTime(id).coerceAtLeast(0)
    }
    return Snap(System.nanoTime(), os.processCpuTime, t, Crawler.Counters.walks.sum(), Crawler.Counters.dirs.sum(), Crawler.Counters.files.sum(), tsProbes.sum(), allProbes.sum())
  }

  private fun report(name: String, a: Snap, b: Snap) {
    val min = (b.at - a.at) / 6e10
    fun cpu(x: Long, y: Long) = (y - x) / 1e9 / min
    say("%-34s %6.1fs  walks/min %7.1f  dirs/min %9.0f  files/min %9.0f  process CPU-s/min %6.2f  trawl-threads CPU-s/min %6.2f  .ts probes %6d (all probes %d)".format(
      name, (b.at - a.at) / 1e9, (b.walks - a.walks) / min, (b.dirs - a.dirs) / min, (b.files - a.files) / min, cpu(a.proc, b.proc), cpu(a.threads, b.threads), b.ts - a.ts, b.all - a.all))
  }

  /** 1,000 small files in 10 bursts across [win] seconds: a build and a coding agent at work. */
  private fun burst(docs: File, tag: String) {
    val gap = win * 1000 / 10
    for (b in 0 until 10) {
      val nm = File(docs, "Projects/app/node_modules/$tag$b")
      for (i in 0 until 70) write(File(nm, if (i % 2 == 0) "lib/a$i.ts" else "dist/b$i.js"), "export const x$i = $b;\n")
      for (i in 0 until 20) write(File(docs, "Projects/app/build/out$tag$b/c$i.js"), "var c$i = $b;\n")
      for (i in 0 until 5) write(File(docs, "Projects/app/src/$tag$b-$i.ts"), "export const t = $i;\n")
      for (i in 0 until 5) write(File(docs, "notes/$tag$b-$i.txt"), "New note $tag $b $i about the harvest and the school fees.\n")
      Thread.sleep(gap)
    }
  }

  private fun settle(ix: IndexService, maxS: Long = 180) {
    var last = Crawler.Counters.walks.sum(); var quiet = 0
    val end = System.currentTimeMillis() + maxS * 1000
    while (System.currentTimeMillis() < end && quiet < 8) {
      Thread.sleep(500)
      val now = Crawler.Counters.walks.sum()
      if (now == last && ix.ui.value.phase != Phase.SCANNING) quiet++ else { quiet = 0; last = now }
    }
  }

  @Test fun measure() {
    val base = dir?.let { File(it) } ?: return
    val docs = build(File(base, "tree"))
    val data = File(base, "data-$label").apply { deleteRecursively(); mkdirs() }
    say("== $label: window ${win}s; node_modules .ts files: ${docs.walkTopDown().count { it.name.endsWith(".ts") }}")
    val ix = IndexService(data, decoderChoice = { decoder() })
    val watcher = FolderWatcher(ignore = { false }, onDirty = { ix.reconcile(it) })
    try {
      ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = false)
      // With a model attached videos are really read (probed), as on a computer that has Trawl set up; without one only the words pass runs.
      System.getenv("TRAWL_CPU_MODEL")?.takeIf { it.isNotBlank() }?.let { m ->
        ix.attachModel(File(m))
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until && !ix.ui.value.modelReady) Thread.sleep(250)
        say("model ready: ${ix.ui.value.modelReady}")
      }
      val s0 = snap()
      ix.setFolders(listOf(docs))
      watcher.watch(listOf(docs))
      val end = System.currentTimeMillis() + 20 * 60_000
      Thread.sleep(2000)
      while (System.currentTimeMillis() < end && (ix.ui.value.phase == Phase.SCANNING || ix.ui.value.phase == Phase.NOT_STARTED || ix.ui.value.opening)) Thread.sleep(250)
      settle(ix, 60)
      val s1 = snap()
      report("first scan (whole run)", s0, s1)
      fun videos() = ix.ui.value.byKind[FileKind.VIDEO] ?: 0
      val real = ix.countUnder(File(docs, "Videos"))
      say("after the first scan: videos in the index ${videos()} (real ones in Videos/: $real), documents ${ix.ui.value.byKind[FileKind.DOCUMENT]}, photos ${ix.ui.value.byKind[FileKind.IMAGE]}")
      say("watch: ${watcher.info.modes} dirs watched ${watcher.info.watchedDirs}")

      val i0 = snap(); Thread.sleep(win * 1000); val i1 = snap()
      report("idle (first minute after scan)", i0, i1)

      // Reading the real photos and videos is work that is meant to happen; the question is what a finished index costs.
      val upBy = System.currentTimeMillis() + 5 * 60_000
      while (System.currentTimeMillis() < upBy && ix.ui.value.phase != Phase.UP_TO_DATE) Thread.sleep(500)
      say("phase before the settled idle: ${ix.ui.value.phase}")
      Thread.sleep(5000)
      val j0 = snap(); Thread.sleep(win * 1000); val j1 = snap()
      report("idle (index up to date)", j0, j1)

      val b0 = snap(); burst(docs, "x"); val b1 = snap()
      report("burst 1,000 files (not paused)", b0, b1)
      settle(ix); val b2 = snap()
      report("  ...and the drain after it", b1, b2)

      ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = true)
      Thread.sleep(3000)
      val p0 = snap(); burst(docs, "y"); val p1 = snap()
      report("burst 1,000 files (PAUSED)", p0, p1)
      val q0 = snap(); Thread.sleep(win * 1000); val q1 = snap()
      report("paused and idle", q0, q1)

      ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = false)
      val r0 = snap(); settle(ix); val r1 = snap()
      report("resume: catching up", r0, r1)
      say("failed rows in the index (ui unreadable): ${ix.ui.value.unreadable}")
      say("final: videos in the index ${videos()} (real ones in Videos/: ${ix.countUnder(File(docs, "Videos"))}), ui failed ${ix.ui.value.unreadable}, documents ${ix.ui.value.byKind[FileKind.DOCUMENT]}")
      say("probes asked of the decoder: ${tsProbes.sum()} of .ts/.mts/.m2ts files (${nodeProbes.sum()} of them inside node_modules), ${allProbes.sum()} in all")
      say("totals: walks ${Crawler.Counters.walks.sum()}, dirs ${Crawler.Counters.dirs.sum()}, files ${Crawler.Counters.files.sum()}")
    } finally {
      runCatching { watcher.close() }; runCatching { ix.close() }
      File(base, "result-$label.txt").writeText(out.toString())
    }
  }
}
