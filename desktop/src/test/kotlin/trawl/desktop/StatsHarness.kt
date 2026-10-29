package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.index.IndexStore
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test

/**
 * The measuring harness behind internal/research/desktop-cpu-stats.md. It does nothing unless TRAWL_STATS_DIR is set.
 *
 *   build:   TRAWL_STATS_DIR=/mnt/data/trawl-stats TRAWL_STATS_PHASE=build gradlew -p desktop test --tests '*StatsHarness*'
 *   measure: TRAWL_STATS_DIR=... TRAWL_STATS_PHASE=measure TRAWL_STATS_LABEL=before TRAWL_STATS_ONLY=stats,forget,rescan,idle,paused ...
 *
 * `build` makes a tree of about 130,000 real files (documents and a few photos), has the real [IndexService] scan it, then fills the index
 * the way a long-used one looks: about 1.4 million segments with keyword text, some vectors, and 50,000 extra rows under a `node_modules`
 * folder that a first build had indexed and that is skipped now. `measure` copies that index afresh for each scenario and reports.
 */
class StatsHarness {
  private val dir: String? = System.getenv("TRAWL_STATS_DIR")?.takeIf { it.isNotBlank() }
  private val phase = System.getenv("TRAWL_STATS_PHASE") ?: "measure"
  private val label = System.getenv("TRAWL_STATS_LABEL") ?: "run"
  private val only = (System.getenv("TRAWL_STATS_ONLY") ?: "stats,forget,forgetpaused,rescan,idle,paused").split(',').map { it.trim() }.toSet()
  private val winS = (System.getenv("TRAWL_STATS_WIN") ?: "60").toLong()
  private val out = StringBuilder()
  private fun say(s: String) { println(s); out.appendLine(s); File(dir!!, "result-$label.txt").writeText(out.toString()) }

  private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private val tmx = ManagementFactory.getThreadMXBean()
  private fun workerCpu(): Long {
    var t = 0L
    for (id in tmx.allThreadIds) {
      val name = tmx.getThreadInfo(id)?.threadName ?: continue
      if (name.startsWith("trawl-index")) t += tmx.getThreadCpuTime(id).coerceAtLeast(0)
    }
    return t
  }

  @Test fun run() {
    val base = dir?.let { File(it) } ?: return
    base.mkdirs()
    if (phase == "build") build(base) else measure(base)
  }

  // ---- build ----------------------------------------------------------------------------------------------------------------------

  private val vocab: List<String> by lazy {
    val r = Random(11)
    val syl = listOf("ra", "ko", "mi", "tu", "an", "el", "or", "ing", "est", "ver", "con", "pro", "data", "file", "user", "node", "list", "map", "get", "set", "index", "query", "path", "name", "type")
    List(30_000) { i -> buildString { repeat(2 + r.nextInt(3)) { append(syl[r.nextInt(syl.size)]) }; append(i % 97) } }
  }

  private fun words(r: Random, n: Int): String = buildString { repeat(n) { if (it > 0) append(' '); append(vocab[(r.nextDouble().let { x -> x * x } * vocab.size).toInt().coerceAtMost(vocab.size - 1)]) } }

  private fun build(base: File) {
    val docs = File(base, "tree/Documents")
    val golden = File(base, "golden")
    if (File(base, "golden.done").exists()) { println("golden exists"); return }
    base.resolve("tree").deleteRecursively(); golden.deleteRecursively(); golden.mkdirs()
    val r = Random(5)
    val exts = listOf("txt", "txt", "txt", "md", "md", "csv", "json", "json", "log", "html")
    val jpg = java.io.ByteArrayOutputStream().also { o ->
      javax.imageio.ImageIO.write(java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", o)
    }.toByteArray()
    var made = 0
    for (p in 0 until 400) for (d in 0 until 13) {
      val sub = File(docs, "Projects/p$p/d$d").apply { mkdirs() }
      for (i in 0 until 25) {
        if (r.nextInt(20) == 0) File(sub, "img$i.jpg").writeBytes(jpg + ByteArray(r.nextInt(64)))
        else File(sub, "f$i.${exts[r.nextInt(exts.size)]}").writeText("note $p $d $i ${words(r, 6)}\n")
        made++
      }
    }
    say("tree: $made files under $docs")
    // The real scan builds the file rows exactly as the app does (URI, fingerprint, kind). Paused: the scan walks, nothing is read.
    val ix = IndexService(golden, decoderChoice = { null })
    ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = true)
    ix.setFolders(listOf(docs))
    val t0 = System.currentTimeMillis()
    Thread.sleep(3000)
    while (ix.ui.value.phase == Phase.SCANNING || ix.ui.value.phase == Phase.NOT_STARTED || ix.ui.value.opening) Thread.sleep(500)
    say("first scan of the tree took ${(System.currentTimeMillis() - t0) / 1000} s; kinds ${ix.ui.value.byKind}")
    ix.close()
    val store = IndexStore.open(File(golden, "index"))
    // Rows a first build indexed under a folder that is skipped now.
    val nm = File(docs, "Projects/big/node_modules")
    store.write { db ->
      db.transaction {
        for (i in 0 until 50_000) {
          val f = File(nm, "pkg${i / 40}/lib/f$i.txt")
          val p = f.toPath()
          db.run(
            "INSERT INTO files(volume, mediastore_id, uri, rel_path, name, kind, size, mtime, state, fingerprint, priority, date_ms) VALUES(?,?,?,?,?,?,?,?, 'new', '1:2', 5, ?)",
            Crawler.VOLUME, Crawler.idOf(p), p.toUri().toString(), "Projects/big/node_modules/pkg${i / 40}/lib/", f.name, FileKind.DOCUMENT.name, 100L, 1_700_000_000_000L + i, 1_700_000_000_000L + i,
          )
        }
      }
    }
    // Fill the index: a name segment for every file, 1-13 text chunks for most, a vector for about 2% of the chunks.
    val ids = ArrayList<Long>()
    store.read { db -> db.query("SELECT id FROM files WHERE state != 'skipped' ORDER BY id") { ids += it.getLong(0) } }
    say("files to fill: ${ids.size}")
    val names = HashMap<Long, Pair<String, String>>()
    store.read { db -> db.query("SELECT id, name, rel_path FROM files") { names[it.getLong(0)] = it.getText(1) to it.getText(2) } }
    var seg = 0L; var vecs = 0
    ids.chunked(2000).forEachIndexed { n, chunk ->
      store.write { db ->
        db.transaction {
          for (id in chunk) {
            val (name, rel) = names[id]!!
            db.run("INSERT INTO segments(file, kind, modality) VALUES(?, 'name', 'none')", id)
            db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)", db.lastInsertId(), name, rel.trim('/').replace('/', ' '))
            seg++
            val roll = r.nextInt(100)
            val state = when { roll < 90 -> "understood"; roll < 98 -> "searchable"; else -> "failed" }
            var chunks = if (state == "failed") 0 else 1 + r.nextInt(13)
            val jpgFile = name.endsWith(".jpg")
            if (jpgFile) chunks = 0
            for (c in 0 until chunks) {
              val vec = if (r.nextInt(50) == 0) ByteArray(1536).also { r.nextBytes(it) }.also { vecs++ } else null
              db.run("INSERT INTO segments(file, kind, modality, loc_start, loc_end, late, vec) VALUES(?, 'chunk', ?, ?, ?, 0, ?)", id, if (vec != null) "text" else "none", c * 400L, c * 400L + 400, vec)
              db.run("INSERT INTO fts(rowid, name, body) VALUES(?, '', ?)", db.lastInsertId(), words(r, 22))
              seg++
            }
            db.run("UPDATE files SET state = ?, detail = ? WHERE id = ?", state, if (state == "searchable") IndexStore.WORDS_DONE else if (state == "failed") "damaged" else null, id)
          }
        }
      }
      if (n % 10 == 0) println("filled ${(n + 1) * 2000} files, $seg segments, $vecs vectors")
    }
    say("segments $seg, vectors $vecs")
    store.close()
    File(base, "golden.done").writeText("ok")
    say("golden index: " + golden.listFiles()!!.joinToString { "${it.name} ${it.length() / 1_000_000} MB" })
  }

  // ---- measure --------------------------------------------------------------------------------------------------------------------

  private fun fresh(base: File, scn: String): File {
    val d = File(base, "data-$label-$scn")
    d.deleteRecursively()
    ProcessBuilder("cp", "-a", File(base, "golden").path, d.path).inheritIO().start().waitFor()
    return d
  }

  private fun timed(name: String, n: Int = 3, f: () -> Unit) {
    val ms = (0 until n).map { val t = System.nanoTime(); f(); (System.nanoTime() - t) / 1_000_000 }
    say("  %-46s %s ms".format(name, ms.joinToString(" / ")))
  }

  private fun measure(base: File) {
    val docs = File(base, "tree/Documents")
    say("== $label (${java.time.LocalDateTime.now()}) scenarios $only")
    if ("stats" in only) {
      val d = fresh(base, "stats")
      val openT0 = System.nanoTime()
      val store = IndexStore.open(File(d, "index"))
      say("first open of the copy (a schema upgrade, if the code has one, runs here): %d ms".format((System.nanoTime() - openT0) / 1_000_000))
      say("stats on a copy of the golden index (${File(d, "index").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000} MB):")
      val c = store.read { db -> listOf(db.long("SELECT COUNT(*) FROM files"), db.long("SELECT COUNT(*) FROM segments")) }
      say("  files ${c[0]}, segments ${c[1]}")
      timed("store.stats() after a write (publish ran this every time)") { store.write { }; store.stats() }
      timed("  files GROUP BY kind, state") { store.read { db -> db.query("SELECT kind, state, COUNT(*) FROM files GROUP BY kind, state") { } } }
      timed("  COUNT(*), COUNT(vec) over segments") { store.read { db -> db.query("SELECT COUNT(*), COUNT(vec) FROM segments WHERE kind != 'name'") { } } }
      timed("  quick-look JOIN (vectors)") {
        store.read { db -> db.query("SELECT f.kind, COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file WHERE s.modality = 'clip' AND s.vec IS NOT NULL AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind") { } }
      }
      timed("  quick-look JOIN (unreadable)") {
        store.read { db -> db.query("SELECT f.kind, COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file WHERE s.modality = 'clip' AND s.vec IS NULL AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind") { } }
      }
      timed("  too-large count") { store.read { db -> db.long("SELECT COUNT(*) FROM files WHERE state = 'failed' AND detail = 'x'") } }
      timed("IndexAdmin.wordsLeft") { IndexAdmin.wordsLeft(store, Crawler.KINDS) }
      timed("store.waiting(kinds)") { store.waiting(Crawler.KINDS) }
      statsExtra(store)
      store.close()
    }
    if ("forgetbatch" in only) {
      // Does a bigger committed batch make the delete cheaper? The same 6,400 rows under node_modules, dropped in batches of different sizes, straight on the store.
      for (n in listOf(50, 200, 800, 3200)) {
        val d = fresh(base, "forgetbatch")
        val st = IndexStore.open(File(d, "index"))
        val ids = ArrayList<Long>()
        st.read { db -> db.query("SELECT id FROM files WHERE uri LIKE '%/node_modules/%' ORDER BY id LIMIT 6400") { ids += it.getLong(0) } }
        val c0 = os.processCpuTime; val t0 = System.nanoTime()
        for (b in ids.chunked(n)) st.markDeleted(b, checkpoint = false)
        st.trimWal(0)
        val s = (System.nanoTime() - t0) / 1e9
        say("  batch %4d: %d rows in %.1f s = %.0f files/s, CPU %.1f s".format(n, ids.size, s, ids.size / s, (os.processCpuTime - c0) / 1e9))
        st.close()
      }
    }
    for (scn in listOf("forget", "forgetpaused")) if (scn in only) {
      // "forgetpaused" is the same pass with the app paused: the old code ignored the pause, the new code paces it, so it is cut off after a window and reported as a rate.
      val pausedRun = scn == "forgetpaused"
      val d = fresh(base, scn)
      val ix = IndexService(d, decoderChoice = { null }, log = { println("  [log] $it") })
      ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = pausedRun)
      waitOpen(ix)
      val nm = File(docs, "Projects/big/node_modules")
      val before = ix.countUnder(nm)
      say("$scn pass: $before rows under node_modules")
      val p0 = ix.publishCount.get(); val c0 = os.processCpuTime; val t0 = System.nanoTime()
      ix.configure(Crawler.KINDS, listOf(nm.toPath()), LearningSpeed.BALANCED, paused = pausedRun)   // the skip makes the service drop what is under it
      val end = System.currentTimeMillis() + (if (pausedRun) winS * 3 else 40 * 60) * 1000
      var last = before
      while (System.currentTimeMillis() < end) {
        Thread.sleep(2000)
        val now = ix.countUnder(nm)
        if (now != last) { println("  forget: $now left after ${(System.nanoTime() - t0) / 1_000_000_000} s"); last = now }
        if (now == 0) break
      }
      val s = (System.nanoTime() - t0) / 1e9
      say("  forgot ${before - last} rows in %.1f s = %.0f files/s; process CPU %.1f s = %.2f CPU-s/min; publishes %d".format(s, (before - last) / s, (os.processCpuTime - c0) / 1e9, (os.processCpuTime - c0) / 1e9 / (s / 60), ix.publishCount.get() - p0))
      ix.close()
    }
    if ("rescan" in only) {
      val d = fresh(base, "rescan")
      val ix = IndexService(d, decoderChoice = { null }, log = { println("  [log] $it") })
      ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = false)
      waitOpen(ix)
      val nm = File(docs, "Projects/big/node_modules")
      say("rescan of ${docs.walkTopDown().count()} paths; rows under node_modules before: ${ix.countUnder(nm)}; files in the index ${ix.ui.value.total}")
      val p0 = ix.publishCount.get(); val c0 = os.processCpuTime; val w0 = workerCpu(); val t0 = System.nanoTime()
      ix.setFolders(listOf(docs))
      Thread.sleep(2000)
      val end = System.currentTimeMillis() + 90 * 60_000
      var scanEnd = -1.0
      while (System.currentTimeMillis() < end) {
        Thread.sleep(1000)
        val ph = ix.ui.value.phase
        if (ph != Phase.SCANNING && ph != Phase.NOT_STARTED && scanEnd < 0) scanEnd = (System.nanoTime() - t0) / 1e9
        if (scanEnd >= 0 && ix.countUnder(nm) == 0) break
      }
      val s = (System.nanoTime() - t0) / 1e9
      val minutes = s / 60
      say("  rescan finished in %.0f s (scanning phase left at %.0f s); rows under node_modules after: %d".format(s, scanEnd, ix.countUnder(nm)))
      say("  publishes %d = %.1f/min; process CPU %.1f s = %.1f CPU-s/min; worker thread CPU %.1f s".format(ix.publishCount.get() - p0, (ix.publishCount.get() - p0) / minutes, (os.processCpuTime - c0) / 1e9, (os.processCpuTime - c0) / 1e9 / minutes, (workerCpu() - w0) / 1e9))
      ix.close()
    }
    for (scn in listOf("idle", "paused")) if (scn in only) {
      val d = fresh(base, scn)
      val ix = IndexService(d, decoderChoice = { null }, log = { println("  [log] $it") })
      val paused = scn == "paused"
      ix.configure(Crawler.KINDS, emptyList(), LearningSpeed.BALANCED, paused = paused)
      waitOpen(ix)
      Thread.sleep(5000)
      // Settle: the index is already read and the scan of the tree is the one thing a start would do; skip it (no folders chosen).
      val p0 = ix.publishCount.get(); val c0 = os.processCpuTime; val w0 = workerCpu(); val t0 = System.nanoTime()
      Thread.sleep(winS * 1000)
      val min = (System.nanoTime() - t0) / 6e10
      say("%s for %d s: publishes %d (%.1f/min), process CPU %.2f CPU-s/min, worker CPU %.2f CPU-s/min".format(scn, winS, ix.publishCount.get() - p0, (ix.publishCount.get() - p0) / min, (os.processCpuTime - c0) / 1e9 / min, (workerCpu() - w0) / 1e9 / min))
      ix.close()
    }
  }

  private fun waitOpen(ix: IndexService) {
    val end = System.currentTimeMillis() + 120_000
    while (System.currentTimeMillis() < end && (ix.ui.value.opening || ix.indexBytes() == 0L)) Thread.sleep(250)
    Thread.sleep(2000)
  }

  /** Lines that exist only once the fix is in (the cached and the cheap counts). */
  private fun statsExtra(store: IndexStore) {
    timed("fileStats() after a write (what publish runs now)") { store.write { }; store.fileStats() }
    timed("fileStats() while quiet (cached)", 5) { store.fileStats() }
    timed("stats() while quiet (cached)", 5) { store.stats() }
    timed("IndexAdmin.wordsLeft while quiet (cached)", 5) { IndexAdmin.wordsLeft(store, Crawler.KINDS) }
    timed("files GROUP BY kind, state, through the files(kind, state) index") { store.read { db -> db.query("SELECT kind, state, COUNT(*) FROM files GROUP BY kind, state") { } } }
  }
}
