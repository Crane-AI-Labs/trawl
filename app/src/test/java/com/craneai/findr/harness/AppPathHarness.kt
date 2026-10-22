package com.craneai.findr.harness

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.DateParser
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SortOrder
import com.craneai.findr.core.MatchReason
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import com.craneai.findr.engine.Chunker
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.math.ln

/**
 * The app-path benchmark: builds a real index (IndexStore with bundled SQLite FTS5 + VectorIndex) from precomputed
 * vectors and texts, and ranks every query with the app's own [Searcher] (the same call the UI makes: UI limit 60,
 * prefixLast = true). Nothing here re-implements ranking; this file only loads data, calls the app, and scores the lists.
 *
 * World directory (written by the build_*.py scripts in scripts/bench):
 *  - files.jsonl    {"id":int,"name":str,"folder":str,"kind":"DOCUMENT|PDF|IMAGE|SCREENSHOT|...","segs":[{"k":"chunk|page|image|ocr|transcript","v":row|-1,"t":text?,"late":bool?}]}
 *                   `v` is a row of vectors.f32 (float32 LE, dim wide); text goes to FTS; "image" segments are vector only.
 *                   A "chunk" without "late" is late from the 41st chunk of the file on, as FileProcessor does (CHUNKS_PER_STEP).
 *  - vectors.f32    document-side vectors
 *  - queries.jsonl  {"id":str,"q":str,"rel":[file ids],"slices":[str],"qv":row|-1,"filter":"ALL|PHOTOS|..."?}
 *  - qvecs.f32      query vectors (qv = -1 runs the query words-only, as the app does before the embedder answers)
 *  - meta.json      {"dim":768, "name":..., ...} (free-form, copied into the output)
 * Output: ranks.jsonl (per query, the top hits as file ids) and summary.json (metrics by slice).
 */
object AppPathHarness {
  const val LIMIT = 60          // SearchScreen asks for 60

  private class Seg(val kind: String, val v: Int, val text: String?, val late: Boolean?)
  private class FileRec(val id: Long, val name: String, val folder: String, val kind: FileKind, val segs: List<Seg>)
  private class Query(val id: String, val q: String, val rel: Set<Long>, val slices: List<String>, val qv: Int, val filter: KindFilter)

  @Suppress("UNCHECKED_CAST")
  private fun jsonl(f: File): Sequence<Map<String, Any?>> =
    f.useLines { it.filter { l -> l.isNotBlank() }.map { l -> MiniJson.parse(l) as Map<String, Any?> }.toList() }.asSequence()

  /** float32 LE matrix, memory-mapped. */
  private class Matrix(file: File, val dim: Int) {
    private val raf = RandomAccessFile(file, "r")
    private val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
    val rows = (raf.length() / (4L * dim)).toInt()
    fun row(r: Int): FloatArray {
      require(r in 0 until rows) { "vector row $r outside 0..$rows" }
      val out = FloatArray(dim); val base = r.toLong() * dim * 4
      require(base + 4L * dim <= Int.MAX_VALUE) { "matrix over 2 GB; split the world" }
      for (i in 0 until dim) out[i] = map.getFloat((base + 4L * i).toInt())
      return out
    }
    fun close() = raf.close()
  }

  private fun kindOf(s: String) = FileKind.valueOf(s)
  private fun segKind(k: String) = when (k) {
    "chunk" -> SegmentKind.CHUNK; "page" -> SegmentKind.PAGE; "image" -> SegmentKind.IMAGE; "ocr" -> SegmentKind.OCR
    "transcript" -> SegmentKind.TRANSCRIPT; "sound" -> SegmentKind.SOUND; "frame" -> SegmentKind.FRAME
    else -> error("unknown segment kind $k")
  }
  private fun modalityOf(k: SegmentKind) = if (k == SegmentKind.IMAGE || k == SegmentKind.FRAME) Modality.IMAGE else Modality.TEXT

  /**
   * mode "datescan": which queries of <world>/queries.jsonl the app's [DateParser] reads a date in (no index, no vectors needed).
   * Writes one line per dated query and a final count line. This is how many queries of a set the date-phrase path can touch at all.
   */
  fun dateScan(world: File, out: File) {
    out.parentFile?.mkdirs()
    val now = DateMode(fallback = false).now
    var n = 0; var dated = 0
    out.bufferedWriter().use { w ->
      for (m in jsonl(File(world, "queries.jsonl"))) {
        n++
        val q = m["q"] as String
        val p = DateParser.parse(q, now, DayOfWeek.MONDAY, emptySet())
        val pd = p.date   // a local copy: ParsedDate lives in :core now, so the property cannot be smart-cast
        if (pd != null) { dated++; w.write("{\"id\":${MiniJson.quote(m["id"].toString())},\"q\":${MiniJson.quote(q)},\"phrase\":${MiniJson.quote(pd.phrase)},\"left\":${MiniJson.quote(p.text.trim())}}\n") }
      }
      w.write("{\"queries\":$n,\"dated\":$dated}\n")
    }
    println("datescan: $n queries, $dated read a date -> ${out.path}")
  }

  /** mode "chunk":docs.jsonl {"id","text"} -> chunks.jsonl {"id","chunks":[...]} with the app's own [Chunker]. */
  fun chunkMode(world: File, out: File) {
    out.parentFile?.mkdirs()
    out.bufferedWriter().use { w ->
      for (d in jsonl(File(world, "docs.jsonl"))) {
        val chunks = Chunker.chunks(d["text"] as String)
        w.write("{\"id\":${MiniJson.quote(d["id"].toString())},\"chunks\":[" + chunks.joinToString(",") { MiniJson.quote(it) } + "]}\n")
      }
    }
  }

  /**
   * [wordWeight]: the word weight for a query's text (null: the Searcher's own, 2.0), so the harness can run the app's per-query policy
   * ([com.craneai.findr.engine.QueryLanguage]). [keep]: which queries to rank, by the smallest relevant file id (null: all);
   * [testHalf] is the D17 TEST half of the research notes 10e. [dropVectors]: a kind this file's vectors are withheld for,
   * [dropVectorsForId]: a file (by id) its vectors are withheld for regardless of kind (for a world with one kind
   * only, such as D17's, where [dropVectors] has nothing to bite on) -- either way the segment text is kept (FTS/words
   * still see it), simulating a partial index where the file has only had its words pass, not its meaning pass yet.
   * The file is saved as `searchable`, not `understood` (see [FileState.SEARCHABLE]), the same state a real phone
   * leaves a file in while it waits. Everything else is unchanged.
   */
  class Policy(
    val wordWeight: ((String) -> Double)? = null,
    val keep: ((Long) -> Boolean)? = null,
    val dropVectors: ((FileKind) -> Boolean)? = null,
    val dropVectorsForId: ((Long) -> Boolean)? = null,
    /** The final answer's weight from the query text AND its own vector, the order of the app's meaning stage (D19c, [com.craneai.findr.engine.WordWeightPolicy.meaningWeight]). Wins over [wordWeight]. */
    val meaningWeight: ((String, FloatArray?) -> Double)? = null,
    /**
     * Date handling as SearchViewModel does it (FINDR_DATES=on|fallback): each query goes through [DateParser] first, the phrase is
     * taken out of the text, and the range filters the search; every file is dated like the landing run pushes them (now minus 30 s per
     * file), so a phrase naming another month or day leaves no file in range. "fallback" adds [DateFallback]: an empty dated answer is
     * asked again with the phrase as words. The query VECTOR is the full query's (the app embeds the stripped text; not available here).
     * Null (the default) is the plain search, which never reads dates.
     */
    val dates: DateMode? = null,
  )

  class DateMode(val fallback: Boolean, val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, ZoneId.of("Africa/Kampala")))

  /** The D17 split of scripts/bench/ww_gate.py: DEV when md5(str(min relevant id)) is even, TEST otherwise. */
  fun testHalf(minRel: Long): Boolean =
    java.security.MessageDigest.getInstance("MD5").digest(minRel.toString().toByteArray()).let { it.last().toInt() and 1 == 1 }

  fun run(world: File, outDir: File, log: (String) -> Unit = ::println, policy: Policy = Policy()) {
    outDir.mkdirs()
    @Suppress("UNCHECKED_CAST")
    val meta = MiniJson.parse(File(world, "meta.json").readText()) as Map<String, Any?>
    val dim = (meta["dim"] as Double).toInt()
    require(dim == com.craneai.findr.index.Fp16.DIM) { "the app index is ${com.craneai.findr.index.Fp16.DIM}-d; world is $dim-d" }
    val dir = Files.createTempDirectory("findr-bench").toFile()
    val store = IndexStore.open(dir, log = {})
    // FINDR_TWOSTAGE=<stage dim>:<candidates>:<min rows>[:<scale sample>] runs the two-stage scan; unset or "off" is the exact scan.
    val twoStage = System.getenv("FINDR_TWOSTAGE")?.takeIf { it != "off" && it.isNotBlank() }?.split(":")?.map { it.toInt() }
      ?.let { com.craneai.findr.search.TwoStage(it[0], it[1], it[2], it.getOrElse(3) { 2048 }) }
    if (twoStage != null) log("two-stage scan: $twoStage")
    val vectors = VectorIndex(parallelism = 4, twoStage = twoStage)
    val searcher = Searcher(store, vectors)
    val docs = Matrix(File(world, "vectors.f32"), dim)
    val qvecs = File(world, "qvecs.f32").takeIf { it.exists() }?.let { Matrix(it, dim) }
    try {
      val t0 = System.nanoTime()
      // ---- build the index the way the crawler and the processor do ----
      val files = jsonl(File(world, "files.jsonl")).map { m ->
        @Suppress("UNCHECKED_CAST")
        FileRec(
          (m["id"] as Double).toLong(), m["name"] as String, (m["folder"] as? String) ?: "", kindOf(m["kind"] as String),
          (m["segs"] as List<Map<String, Any?>>).map { Seg(it["k"] as String, ((it["v"] as? Double) ?: -1.0).toInt(), it["t"] as? String, it["late"] as? Boolean) },
        )
      }.toList()
      files.chunked(500).forEach { batch ->
        store.upsertFromCrawl(batch.map { f ->
          CrawlRecord("external_primary", f.id, "content://media/external/file/${f.id}", f.folder, f.name, f.kind, null, 50_000, 1_000,
            dateMillis = policy.dates?.let { it.now.toInstant().toEpochMilli() - f.id * 30_000 } ?: 0)
        })
      }
      val storeIdOf = store.knownMediaStoreIds("external_primary")
      val extOf = HashMap<Long, Long>(storeIdOf.size * 2)
      for ((ext, internal) in storeIdOf) extOf[internal] = ext
      var segCount = 0; var vecCount = 0
      for (f in files) {
        val fileId = storeIdOf.getValue(f.id)
        var chunkNo = 0
        val withheld = policy.dropVectors?.invoke(f.kind) == true || policy.dropVectorsForId?.invoke(f.id) == true
        val segments = f.segs.map { s ->
          val kind = segKind(s.kind)
          val late = s.late ?: (kind == SegmentKind.CHUNK && chunkNo >= 40)
          if (kind == SegmentKind.CHUNK) chunkNo++
          val vec = if (!withheld && s.v >= 0) docs.row(s.v) else null
          Segment(kind, if (vec != null) modalityOf(kind) else Modality.NONE, text = s.text, vector = vec, late = late)
        }
        if (segments.isEmpty()) continue
        // A file with no vector segment at all has only had its words pass, as on a real phone: searchable, not understood.
        val state = if (segments.any { it.vector != null }) FileState.UNDERSTOOD else FileState.SEARCHABLE
        val ids = store.saveSegments(fileId, segments, state)
        segments.forEachIndexed { i, s -> s.vector?.let { vectors.add(ids[i], fileId, f.kind, s.modality, s.late, it); vecCount++ } }
        segCount += segments.size
      }
      log("built index: ${files.size} files, $segCount segments, $vecCount vectors in ${(System.nanoTime() - t0) / 1_000_000_000} s")

      // ---- rank every query with the app's Searcher ----
      @Suppress("UNCHECKED_CAST")
      val queries = jsonl(File(world, "queries.jsonl")).map { m ->
        Query(
          m["id"].toString(), m["q"] as String, (m["rel"] as List<Any?>).map { (it as Double).toLong() }.toSet(),
          (m["slices"] as List<Any?>).map { it as String }, ((m["qv"] as? Double) ?: -1.0).toInt(),
          (m["filter"] as? String)?.let { KindFilter.valueOf(it) } ?: KindFilter.ALL,
        )
      }.toList().filter { q -> policy.keep?.invoke(q.rel.min()) ?: true }
      class Acc { var n = 0; var h1 = 0.0; var h3 = 0.0; var h5 = 0.0; var h10 = 0.0; var rec10 = 0.0; var mrr = 0.0; var ndcg = 0.0; var empty = 0; var rank60 = 0.0 }
      val acc = linkedMapOf<String, Acc>()
      val t1 = System.nanoTime()
      var dated = 0; var emptyAfterDate = 0; var fallbackFired = 0
      File(outDir, "ranks.jsonl").bufferedWriter().use { rw ->
        queries.forEachIndexed { qi, q ->
          val qv = if (q.qv >= 0) qvecs!!.row(q.qv) else null
          val weight = policy.meaningWeight?.invoke(q.q, qv) ?: policy.wordWeight?.invoke(q.q)
          fun run(text: String, view: ResultView) =
            weight?.let { searcher.search(text, qv, q.filter, LIMIT, prefixLast = true, wordWeight = it, view = view) }
              ?: searcher.search(text, qv, q.filter, LIMIT, prefixLast = true, view = view)
          var datedNote = ""
          val dm = policy.dates
          val res = if (dm == null) run(q.q, ResultView.DEFAULT) else {
            val parsed = DateParser.parse(q.q, dm.now, DayOfWeek.MONDAY, emptySet())
            val text = parsed.text.trim()
            val date = parsed.date
            if (date == null || text.isEmpty()) run(q.q, ResultView.DEFAULT) else {
              dated++
              val dRes = run(text, ResultView(SortOrder.BEST, date.filter.range, date.phrase))
              datedNote = ",\"date\":{\"phrase\":${MiniJson.quote(date.phrase)},\"text\":${MiniJson.quote(text)},\"range\":${MiniJson.quote(date.filter.range.toString())},\"dated_hits\":${dRes.hits.size}"
              if (dRes.hits.isNotEmpty()) { datedNote += "}"; dRes }
              else {
                emptyAfterDate++
                if (!dm.fallback) { datedNote += "}"; dRes }
                else {
                  // as SearchViewModel.phraseIsContent: the same query with the phrase left in as words, no date
                  val again = run(q.q, ResultView.DEFAULT)
                  val matched = again.hits.filter { it.reason != MatchReason.MEANING }.map { it.file.id }
                  val content = matched.isNotEmpty() && searcher.mentioning(date.phrase, matched).isNotEmpty()
                  datedNote += ",\"fallback\":$content}"
                  if (content) { fallbackFired++; again } else dRes
                }
              }
            }
          }
          if (datedNote.isNotEmpty() && (res.hits.isEmpty() || dm?.fallback == true))
            log("  dated ${q.id} ${MiniJson.quote(q.q)}$datedNote -> ${res.hits.size} hits, first_rel=${res.hits.map { it.file.uri.substringAfterLast('/').toLong() }.indexOfFirst { it in q.rel } + 1}")
          val ranked = res.hits.map { it.file.uri.substringAfterLast('/').toLong() }
          val firstRel = ranked.indexOfFirst { it in q.rel }.let { if (it < 0) 0 else it + 1 }
          fun hit(k: Int) = if (firstRel in 1..k) 1.0 else 0.0
          val top10 = ranked.take(10)
          val recall10 = top10.count { it in q.rel }.toDouble() / q.rel.size.coerceAtLeast(1)
          val dcg = top10.withIndex().sumOf { (i, id) -> if (id in q.rel) 1.0 / (ln(i + 2.0) / ln(2.0)) else 0.0 }
          val ideal = (0 until minOf(10, q.rel.size)).sumOf { 1.0 / (ln(it + 2.0) / ln(2.0)) }
          val ndcg = if (ideal > 0) dcg / ideal else 0.0
          val mrr = if (firstRel in 1..10) 1.0 / firstRel else 0.0
          for (s in q.slices) {
            val a = acc.getOrPut(s) { Acc() }
            a.n++; a.h1 += hit(1); a.h3 += hit(3); a.h5 += hit(5); a.h10 += hit(10); a.rec10 += recall10; a.mrr += mrr; a.ndcg += ndcg
            if (ranked.isEmpty()) a.empty++
            a.rank60 += if (firstRel > 0) 1.0 else 0.0
          }
          rw.write(
            "{\"id\":${MiniJson.quote(q.id)},\"first_rel\":$firstRel,\"ndcg10\":$ndcg,\"mrr10\":$mrr,\"n_hits\":${ranked.size}," +
              "\"slices\":[${q.slices.joinToString(",") { MiniJson.quote(it) }}],\"top\":[${ranked.take(LIMIT).joinToString(",")}]," +
              "\"strong\":${res.strongCount},\"scores\":[${res.hits.take(LIMIT).joinToString(",") { "%.3f".format(java.util.Locale.ROOT, it.score) }}]," +
              "\"reasons\":\"${res.hits.take(LIMIT).joinToString("") { it.reason.name.substring(0, 1) }}\"$datedNote}\n",
          )
          if ((qi + 1) % 500 == 0) log("  ${qi + 1}/${queries.size} queries, ${(System.nanoTime() - t1) / 1_000_000 / (qi + 1)} ms/query")
        }
      }
      val perQueryMs = (System.nanoTime() - t1) / 1_000_000.0 / queries.size.coerceAtLeast(1)
      File(outDir, "summary.json").writeText(buildString {
        append("{\"world\":${MiniJson.quote(world.path)},\"meta\":${MiniJson.quote(File(world, "meta.json").readText().trim())},")
        append("\"files\":${files.size},\"segments\":$segCount,\"vectors\":$vecCount,\"queries\":${queries.size},\"limit\":$LIMIT,\"ms_per_query\":$perQueryMs,")
        policy.dates?.let { append("\"dates\":{\"fallback\":${it.fallback},\"queries_with_a_date\":$dated,\"empty_after_date\":$emptyAfterDate,\"fallback_fired\":$fallbackFired},") }
        append("\"slices\":{")
        append(acc.entries.joinToString(",") { (k, a) ->
          val n = a.n.toDouble()
          "${MiniJson.quote(k)}:{\"n\":${a.n},\"p1\":${a.h1 / n},\"hit3\":${a.h3 / n},\"hit5\":${a.h5 / n},\"hit10\":${a.h10 / n}," +
            "\"recall10\":${a.rec10 / n},\"mrr10\":${a.mrr / n},\"ndcg10\":${a.ndcg / n},\"found_in_60\":${a.rank60 / n},\"no_results\":${a.empty}}"
        })
        append("}}\n")
      })
      log("ranked ${queries.size} queries, ${"%.1f".format(perQueryMs)} ms/query -> ${outDir.path}")
    } finally {
      docs.close(); qvecs?.close()
      vectors.close(); store.close(); dir.deleteRecursively()
    }
  }
}
