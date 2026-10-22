package com.craneai.findr.harness

import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchResults
import com.craneai.findr.engine.EnglishWords
import com.craneai.findr.engine.QueryLanguage
import com.craneai.findr.engine.SpeechLanguageGate
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Rules
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import com.craneai.findr.ui.search.SearchViewModel
import com.craneai.findr.ui.search.shownFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The usage-sim grid, replayed on the JVM through the real [SearchViewModel] and the real [Searcher]: what the TECNO KN3 showed
 * (the screen rank of the file each search is for), without the phone. The state is the one that phone was in: every file is in
 * the index by name (the words pass), every picture has its quick-look vector (TinyCLIP, made by scripts/bench/quicklook_embed.py),
 * and nothing has been understood by EmbeddingGemma yet, so documents are "pending meaning". Limits, all stated in the write-up:
 * documents carry no body text here (name and folder only), videos have no quick-look vector, and the quick-look state is
 * "all pictures done" rather than the phone's partial one.
 *
 * World directory:
 *  - files.jsonl   {"path","name","folder","mtime"(ms)}  one line per file that is searchable (skipped files are left out); the line
 *                  number + 1 is the file's MediaStore id.
 *  - qimg.f32/.json  quick-look vectors of the pictures (n x 512 float32, unit) and the paths in row order
 *  - qt.f32/.json    quick-look vectors of the query texts (written by the "texts" pass, see below)
 *  - queries.jsonl {"id","group","q","lang","expect":[paths],"now":"real"|"phone","quick_n":pictures with a quick-look vector at that moment (optional)}
 * Pass "texts": no query vectors yet; writes qneeded.txt, the texts the screen asked the engine for (the words left after a date
 * phrase is taken out), one per line. Pass "run": reads qt.* and writes grid-results.jsonl.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object UsageSimGrid {
  private val zone = ZoneId.of("Africa/Kampala")
  private val REAL_NOW = ZonedDateTime.of(2026, 10, 20, 7, 0, 0, 0, zone)      // the day the grid was run
  private val PHONE_NOW = ZonedDateTime.of(2026, 8, 31, 13, 9, 0, 0, zone)     // the KN3's wrong clock

  private class Matrix(file: File, val dim: Int) {
    private val raf = RandomAccessFile(file, "r")
    private val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
    val rows = (raf.length() / (4L * dim)).toInt()
    fun row(r: Int): FloatArray = FloatArray(dim) { map.getFloat(((r.toLong() * dim + it) * 4).toInt()) }
    fun close() = raf.close()
  }

  @Suppress("UNCHECKED_CAST")
  private fun jsonl(f: File): List<Map<String, Any?>> = f.readLines().filter { it.isNotBlank() }.map { MiniJson.parse(it) as Map<String, Any?> }

  /** The engine the screen talks to, over the real searcher; the quick-look query vector is looked up by the text it is asked for. */
  private class Engine(
    val store: IndexStore, var searcher: Searcher, val quickOf: (String) -> FloatArray?, val weightOf: (String) -> Double,
  ) : SearchEngine {
    val asked = LinkedHashSet<String>()
    var last: SearchResults? = null
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      val q = query.trim()
      if (q.isEmpty()) return SearchResults(query, emptyList(), 0, 0, indexIncomplete = false)
      asked += q
      return searcher.search(q, null, filter, limit, prefixLast = true, quickVector = quickOf(q), wordWeight = weightOf(q), view = view).also { last = it }
    }
    override suspend fun anyMentions(phrase: String, files: List<IndexedFile>): Boolean = searcher.mentioning(phrase, files.map { it.id }).isNotEmpty()
    /** The clock rule's evidence. FINDR_NO_CLOCKSENSE=1 answers none, which replays the screen as it was before ClockSense. */
    override suspend fun newestDates(limit: Int, notAfterMs: Long): List<Long> =
      if (System.getenv("FINDR_NO_CLOCKSENSE") != null) emptyList() else store.newestDates(limit, notAfterMs)
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> =
      store.recent(filter.kinds, limit, includeSensitive = false, order = view.sort, dates = view.dates)
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("not used")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  fun run(world: File, outDir: File, pass: String, wordsGz: File, log: (String) -> Unit = ::println) {
    val gate = SpeechLanguageGate(lazy { wordsGz.inputStream().use { EnglishWords.fromGzip(it) } })
    val lang = QueryLanguage(lazy { wordsGz.inputStream().use { EnglishWords.fromGzip(it) } })
    val dir = Files.createTempDirectory("findr-grid").toFile()
    val store = IndexStore.open(dir, log = {})
    val vectors = VectorIndex(parallelism = 1)
    val quicks = ArrayList<VectorIndex>()
    try {
      val files = jsonl(File(world, "files.jsonl"))
      files.chunked(500).forEachIndexed { b, batch ->
        store.upsertFromCrawl(batch.mapIndexed { i, m ->
          val name = m["name"] as String; val folder = m["folder"] as String; val mtime = (m["mtime"] as Double).toLong()
          CrawlRecord("external_primary", (b * 500 + i + 1).toLong(), "file:///" + (m["path"] as String), folder, name, Rules.classify(name, null, folder), null, 1000, mtime, dateMillis = mtime)
        })
      }
      val internalOf = store.knownMediaStoreIds("external_primary")
      val idOfPath = HashMap<String, Long>()
      files.forEachIndexed { i, m -> idOfPath[m["path"] as String] = internalOf.getValue((i + 1).toLong()) }
      val kindOf = HashMap<Long, com.craneai.findr.core.FileKind>()
      files.forEachIndexed { i, m -> kindOf[idOfPath.getValue(m["path"] as String)] = Rules.classify(m["name"] as String, null, m["folder"] as String) }
      // The quick-look pass has run over some of the pictures: newest first, as the pass takes them (IndexStore.nextForQuickLook). Each search
      // sees the first "quick_n" of them (the count the phone's own log showed at that moment); a search with none sees all.
      val qimgPaths = (MiniJson.parse(File(world, "qimg.json").readText()) as Map<*, *>)["paths"] as List<*>
      val qimg = Matrix(File(world, "qimg.f32"), IndexStore.QUICK_DIM)
      class Q(val mtime: Long, val id: Long, val seg: Long, val kind: com.craneai.findr.core.FileKind, val v: FloatArray)
      val queue = ArrayList<Q>()
      val mtimeOf = HashMap<String, Long>()
      files.forEach { mtimeOf[it["path"] as String] = (it["mtime"] as Double).toLong() }
      qimgPaths.forEachIndexed { r, p ->
        val id = idOfPath[p as String] ?: return@forEachIndexed
        val kind = kindOf.getValue(id)
        if (kind !in IndexStore.QUICK_KINDS) return@forEachIndexed
        val v = qimg.row(r)
        val seg = store.saveQuick(id, SegmentKind.IMAGE, v)
        if (seg > 0) queue += Q(mtimeOf.getValue(p), id, seg, kind, v)
      }
      qimg.close()
      queue.sortWith(compareByDescending<Q> { it.mtime }.thenBy { it.id })
      val searchers = HashMap<Int, Searcher>()
      fun searcherFor(n: Int): Searcher = searchers.getOrPut(n) {
        val quick = VectorIndex(dim = IndexStore.QUICK_DIM, initialCapacity = 256, parallelism = 1, holds = VectorIndex.QUICK_LOOK).also { quicks += it }
        queue.take(n).forEach { quick.add(it.seg, it.id, it.kind, Modality.CLIP, false, it.v) }
        Searcher(store, vectors, quick = quick)
      }
      log("grid world: ${files.size} files, ${queue.size} quick-look vectors")

      val qtTexts = File(world, "qt.json").takeIf { pass == "run" && it.exists() }?.let { (MiniJson.parse(it.readText()) as Map<*, *>)["texts"] as List<*> }
      val qt = File(world, "qt.f32").takeIf { qtTexts != null }?.let { Matrix(it, IndexStore.QUICK_DIM) }
      val qtIndex = qtTexts?.withIndex()?.associate { (i, t) -> (t as String) to i } ?: emptyMap()
      val missing = LinkedHashSet<String>()
      val needed = LinkedHashSet<String>()
      // The same gate the engine applies: English, three characters or more. Anything else runs on words alone.
      val quickOf = { q: String ->
        if (q.length < 3 || !gate.isEnglishQuery(q)) null
        else { needed += q; qtIndex[q]?.let { qt!!.row(it) } ?: run { if (pass == "run") missing += q; null } }
      }
      val engine = Engine(store, searcherFor(queue.size), quickOf, { q -> lang.wordWeight(q) })

      outDir.mkdirs()
      val out = File(outDir, "grid-results.jsonl").bufferedWriter()
      val dispatcher = StandardTestDispatcher()
      Dispatchers.setMain(dispatcher)
      try {
        runTest(dispatcher) {
          for (m in jsonl(File(world, "queries.jsonl"))) {
            val q = m["q"] as String
            if (q.isBlank()) continue
            val now = if (m["now"] == "phone") PHONE_NOW else REAL_NOW
            engine.searcher = searcherFor(((m["quick_n"] as? Double)?.toInt() ?: queue.size).coerceAtMost(queue.size))
            val vm = SearchViewModel(engine, clock = { now }, weekStart = { DayOfWeek.MONDAY }, feedDispatcher = dispatcher)
            advanceUntilIdle()
            engine.last = null
            vm.setQuery(q); advanceUntilIdle()
            val s = vm.state.value
            val shown = s.feed.shownFiles()
            @Suppress("UNCHECKED_CAST")
            val expect = (m["expect"] as List<String>).map { "file:///$it" }
            val ranks = expect.map { u -> shown.indexOfFirst { it.uri == u } + 1 }
            val range = s.date?.filter?.range
            val last = engine.last
            out.write(
              "{\"id\":${MiniJson.quote(m["id"].toString())},\"group\":${MiniJson.quote(m["group"].toString())},\"q\":${MiniJson.quote(q)}," +
                "\"phrase\":${s.date?.phrase?.let { MiniJson.quote(it) } ?: "null"},\"from_ms\":${range?.fromMs ?: "null"},\"until_ms\":${range?.untilMs ?: "null"}," +
                "\"chip\":${s.date != null},\"resultsQuery\":${MiniJson.quote(s.resultsQuery)},\"found\":${shown.size},\"strong\":${last?.strongCount ?: -1},\"hits\":${last?.hits?.size ?: -1}," +
                "\"nothing\":${s.nothingFound},\"ranks\":[${ranks.joinToString(",")}]," +
                "\"top\":[${shown.take(5).joinToString(",") { MiniJson.quote(it.name) }}]}\n",
            )
          }
        }
      } finally { Dispatchers.resetMain(); out.close() }
      if (pass == "texts") File(outDir, "qneeded.txt").writeText(needed.joinToString("\n") + "\n")
      if (missing.isNotEmpty()) log("grid: ${missing.size} query texts had no quick-look vector: ${missing.take(5)}")
      log("grid: pass=$pass, ${needed.size} quick-look query texts, results in ${File(outDir, "grid-results.jsonl").path}")
      qt?.close()
    } finally {
      vectors.close(); quicks.forEach { it.close() }; store.close(); dir.deleteRecursively()
    }
  }
}
