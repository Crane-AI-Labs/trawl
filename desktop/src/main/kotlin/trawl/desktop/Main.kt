package trawl.desktop

import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SearchResults
import com.craneai.findr.engine.QueryLanguage
import com.craneai.findr.engine.WordWeightPolicy
import com.craneai.findr.index.IndexStore
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import java.io.File
import kotlin.system.exitProcess
import org.json.JSONObject

private val MODEL = File(System.getenv("TRAWL_MODEL") ?: "bench-data/models/embeddinggemma-2-text-vision-440m.litertlm")

/** Resident set size of this process in MB (Linux: /proc, macOS: ps; Windows: tasklist is too slow, report -1). */
fun rssMb(): Long {
  val pid = ProcessHandle.current().pid()
  File("/proc/$pid/status").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("VmRSS") }?.let { return it.filter(Char::isDigit).toLong() / 1024 }
  if (System.getProperty("os.name").startsWith("Windows")) {
    // tasklist prints "image","pid","session","#","12,345 K"
    return runCatching { ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH").start().inputStream.bufferedReader().readText()
      .substringAfterLast("\",\"").filter(Char::isDigit).toLong() / 1024 }.getOrDefault(-1)
  }
  return runCatching { ProcessBuilder("ps", "-o", "rss=", "-p", "$pid").start().inputStream.bufferedReader().readText().trim().toLong() / 1024 }.getOrDefault(-1)
}

/** The whole engine of the prototype: the app's IndexStore + VectorIndex + Searcher, with the desktop embedder and OCR. */
class Engine(val indexDir: File, ocrKind: String) : AutoCloseable {
  val store = IndexStore.open(indexDir, log = {})
  val vectors = VectorIndex(parallelism = 4)
  val searcher = Searcher(store, vectors)
  val embedder = DesktopEmbedder(MODEL, File(indexDir, "engine-cache"))
  val ocr: Ocr? = when (ocrKind) {
    "none" -> null
    "tesseract" -> TesseractOcr()
    "windows" -> WindowsOcr(File(System.getenv("TRAWL_OCR_PS1") ?: "desktop/native/ocr-windows.ps1"))
    "apple" -> AppleOcr(AppleOcr.build(File("desktop/native/ocr-stdin.swift"), File(indexDir, "ocr-bin")))
    else -> error("unknown ocr $ocrKind")
  }
  val indexer = Indexer(store, vectors, embedder, ocr)
  private val ctx = ClasspathAssets
  private val weights = WordWeightPolicy.fromAssets(ctx, QueryLanguage(com.craneai.findr.engine.SpeechLanguageGate.fromAssets(ctx).words)) { MODEL.name }

  /** What the phone's engine does for a typed query: embed it, pick the language's word weight, run the shared Searcher. */
  fun search(q: String, filter: KindFilter = KindFilter.ALL, limit: Int = 50): Triple<SearchResults, Long, Long> {
    val t = System.nanoTime()
    val qv = embedder.query(q)
    val embedMs = (System.nanoTime() - t) / 1_000_000
    val t2 = System.nanoTime()
    val r = searcher.search(q, qv, filter, limit, prefixLast = true, wordWeight = weights.meaningWeight(q, qv))
    return Triple(r, embedMs, (System.nanoTime() - t2) / 1_000_000)
  }

  override fun close() { embedder.close(); ocr?.close(); store.close() }
}

/**
 * The app's one entry point. With no arguments (or an unknown one) the window opens; `index`, `search` and `eval` run the same
 * engine from the command line, which is how the speed and quality numbers of the spike are still measured.
 */
fun main(args: Array<String>) {
  // Before any native library loads: no ONNX Runtime in this process (the privacy pack's, or sherpa-onnx's own copy) may phone home.
  trawl.desktop.privacy.NoTelemetry.apply()
  when {
    args.firstOrNull() == GpuProbe.FLAG && args.size >= 2 -> GpuProbe.main(arrayOf(args[1]))   // the GPU probe's child, started by the app's own launcher
    args.firstOrNull() in setOf("index", "search", "eval", "speed", "media") -> cliMain(args)
    else -> trawl.desktop.ui.runWindow(args.toList())   // shell (Package D): args carry --quick
  }
}

fun cliMain(args: Array<String>) {
  val a = args.toMutableList()
  fun opt(name: String): String? = a.indexOf("--$name").takeIf { it >= 0 }?.let { a.removeAt(it); a.removeAt(it) }
  val indexDir = File(opt("index") ?: "trawl-index")
  val ocr = opt("ocr") ?: "none"
  val limit = opt("limit")?.toInt()
  when (a.firstOrNull()) {
    "index" -> {
      val e = Engine(indexDir, ocr)
      e.embedder.warm(); println("engine ready in ${e.embedder.initMs} ms (vision ${e.embedder.visionUsed}), rss ${rssMb()} MB, ocr=$ocr")
      val t = System.nanoTime()
      e.indexer.indexFolder(File(a[1]), limit)
      report(e.indexer.stats, (System.nanoTime() - t) / 1e9)
      println("rss at end ${rssMb()} MB, index file ${indexDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() }?.div(1024)} KB")
      e.close()
    }
    "search" -> {
      val e = Engine(indexDir, "none")
      val t0 = System.nanoTime(); e.vectors.load(e.store); println("index loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
      e.embedder.warm(); println("engine ready in ${e.embedder.initMs} ms (vision ${e.embedder.visionUsed})")
      repeat(2) { e.search("warm up") }
      for (q in a.drop(1)) {
        val (r, embedMs, rankMs) = e.search(q)
        println("\n\"$q\": embed $embedMs ms, rank $rankMs ms, ${r.hits.size} hits (${r.strongCount} strong)")
        r.hits.take(6).forEach { println("  %6.2f  %s%s  [%s]".format(it.score, it.file.relativePath, it.file.name, it.reason)) }
      }
      println("rss ${rssMb()} MB"); e.close()
    }
    "eval" -> Eval.run(File(a[1]), File(a[2]), indexDir, ocr)
    "speed" -> SpeedBench.run(File(a[1]), com.craneai.findr.core.LearningSpeed.of(opt("speed")) ?: error("--speed gentle|balanced|fast"), indexDir)
    "media" -> MediaBench.run(File(a[1]), com.craneai.findr.core.LearningSpeed.of(opt("speed") ?: "balanced") ?: error("--speed gentle|balanced|fast"), indexDir, opt("speech-pack")?.let { File(it) }, a.drop(2))
    else -> { System.err.println("usage: index <folder> [--limit n] | search <q>... | eval <queries.json> <folder> | speed <folder> --speed gentle|balanced|fast | media <folder> [query...] [--speech-pack dir]   [--index dir] [--ocr none|tesseract|apple|windows]"); exitProcess(2) }
  }
}

private fun report(s: Stats, wall: Double) {
  fun rate(n: Int, ns: Long) = if (ns > 0) "%.1f/min".format(n / (ns / 6e10)) else "-"
  println("wall %.1f s; crawl ${s.crawlNs / 1_000_000} ms; skipped ${s.skipped}, failed ${s.failed}".format(wall))
  println("text docs: ${s.docs} (${s.docChunks} chunks) ${rate(s.docs, s.docNs)}, ${"%.0f".format(s.docNs / 1e6 / maxOf(s.docChunks, 1))} ms per chunk")
  println("pdfs: ${s.pdfs} (${s.pdfChunks} chunks) ${rate(s.pdfs, s.pdfNs)}, ${"%.0f".format(s.pdfNs / 1e6 / maxOf(s.pdfChunks, 1))} ms per chunk")
  println("images: ${s.images} ${rate(s.images, s.imageNs)}; per image: jpeg ${s.jpegNs / 1_000_000 / maxOf(s.images, 1)} ms, vision embed ${s.embedNs / 1_000_000 / maxOf(s.images, 1)} ms, ocr ${s.ocrNs / 1_000_000 / maxOf(s.images, 1)} ms (beside the embed), wall ${s.imageNs / 1_000_000 / maxOf(s.images, 1)} ms")
}

/** eval-en through the desktop pipeline: the same files and queries as scripts/eval_quality.py, ranked by the app's own Searcher. */
object Eval {
  private class Row(val tag: String, val kind: String, val rank: Int?)

  fun run(queriesJson: File, corpus: File, indexDir: File, ocr: String) {
    indexDir.deleteRecursively()
    Engine(indexDir, ocr).use { e ->
      e.embedder.warm()
      val t = System.nanoTime()
      e.indexer.indexFolder(corpus)
      report(e.indexer.stats, (System.nanoTime() - t) / 1e9)
      println("rss after indexing ${rssMb()} MB")
      val queries = JSONObject(queriesJson.readText()).getJSONArray("queries")
      val rows = ArrayList<Row>(); val rankMs = ArrayList<Long>(); val embedMs = ArrayList<Long>()
      val out = File(indexDir.parentFile ?: File("."), "eval-ranks.jsonl").bufferedWriter()
      for (i in 0 until queries.length()) {
        val q = queries.getJSONObject(i)
        val expect = q.getJSONArray("expect").let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
        val (r, em, rm) = e.search(q.getString("q"))
        embedMs += em; rankMs += rm
        val ranking = r.hits.map { it.file.relativePath + it.file.name }
        val rank = ranking.indexOfFirst { it in expect }.let { if (it < 0) null else it + 1 }
        rows += Row(q.getString("tag"), q.getString("kind"), rank)
        out.write(JSONObject().put("q", q.getString("q")).put("rank", rank ?: JSONObject.NULL).put("top", ranking.take(3)).toString()); out.newLine()
      }
      out.close()
      fun agg(rs: List<Row>) = "n=%-4d r@1 %.3f  r@5 %.3f  mrr %.3f".format(rs.size, rs.count { (it.rank ?: 99) <= 1 }.toDouble() / rs.size,
        rs.count { (it.rank ?: 99) <= 5 }.toDouble() / rs.size, rs.sumOf { 1.0 / (it.rank ?: Int.MAX_VALUE) } / rs.size)
      println("\nEVAL (Searcher with the app's fusion; ocr=$ocr)")
      println("all              ${agg(rows)}")
      for (tag in rows.map { it.tag }.distinct().sorted()) println("tag:%-12s ${agg(rows.filter { it.tag == tag })}".format(tag))
      for (k in rows.map { it.kind }.distinct().sorted()) println("kind:%-11s ${agg(rows.filter { it.kind == k })}".format(k))
      val sorted = rankMs.sorted(); val es = embedMs.sorted()
      println("query embed: p50 ${es[es.size / 2]} ms, p95 ${es[(es.size * 95) / 100]} ms; rank (Searcher.search): p50 ${sorted[sorted.size / 2]} ms, p95 ${sorted[(sorted.size * 95) / 100]} ms, max ${sorted.last()} ms")
      println("rss at end ${rssMb()} MB")
    }
  }
}

/**
 * Reads a folder through the app's own IndexService at one work speed and prints how many files a minute it managed, from the moment
 * the engine is ready (model loading is not part of reading). This is how the Gentle / Balanced / Fast numbers are measured.
 */
object SpeedBench {
  fun run(folder: File, speed: com.craneai.findr.core.LearningSpeed, dataDir: File) {
    dataDir.deleteRecursively(); dataDir.mkdirs()
    IndexService(dataDir, log = {}, ocrChoice = { null }).use { ix ->
      ix.configure(Crawler.KINDS, emptyList(), speed, paused = false)
      ix.setFolders(listOf(folder))
      ix.attachModel(MODEL)
      var t0 = 0L
      val limit = System.currentTimeMillis() + 30 * 60_000
      while (System.currentTimeMillis() < limit) {
        val u = ix.ui.value
        if (u.error != null) error("index error: ${u.error}")
        if (u.modelReady && t0 == 0L) t0 = System.nanoTime()
        if (u.modelReady && u.phase == Phase.UP_TO_DATE && u.total > 0 && u.understood >= u.total) break
        Thread.sleep(20)
      }
      val secs = (System.nanoTime() - t0) / 1e9
      val u = ix.ui.value
      println("speed=${speed.key} threads=${WorkSpeed.threads(speed) ?: "default"} files=${u.total} unreadable=${u.unreadable} seconds=%.1f files_per_min=%.1f app_rate=%.1f rss_mb=${rssMb()}"
        .format(secs, u.total / (secs / 60.0), u.ratePerMin ?: -1.0))
    }
  }
}
