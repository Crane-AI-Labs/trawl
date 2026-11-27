package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.index.IndexStore
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import kotlin.random.Random

/**
 * The benchmark behind internal/research/desktop-tuning.md: how the desktop app behaves in the background, measured on the real
 * [IndexService] and [FolderWatcher] over a big index and a Documents-like tree. A plain `main`, run with `java -cp <test classpath>`
 * so that one sweep point costs no Gradle round trip; each `-Dtrawl.tune.<name>=<value>` overrides one [BackgroundTuning] number.
 *
 *   prep:  mode=prep   (once: scans the golden tree, settles the index, gives it vectors)
 *   run:   mode=run label=<name> rep=<n> scen=start,idle,paused,latency,tput,search,churn,busy [speed=balanced] [watch=poll]
 *
 * Scenarios (objectives a to e of the brief):
 *   start    the app starts on the big index: time to settle, CPU, what the first walk costs
 *   idle     (a) CPU-s per minute with nothing to do;   paused  (a) the same while paused
 *   latency  (b) seconds from a new file appearing until it is found by name, and until it is read (words and meaning)
 *   tput     (c) files per minute by kind when the person is away
 *   search   (d) search p50/p95 while photos are being read
 *   churn    (e) a coding agent writing 1,000 files a minute into a watched folder: CPU-s/min of the app
 *   busy     (e) a CPU-heavy task of the person's own runs while the app reads: how much slower it gets, how much the app still reads
 * Every scenario appends one JSON line to $TRAWL_BG_DIR/results/<label>.jsonl.
 */
object BackgroundBench {
  private val bg = File(System.getenv("TRAWL_BG_DIR") ?: "/mnt/data/trawl-bg")
  private val TREE_BASE = File(System.getenv("TRAWL_STATS_DIR") ?: "/mnt/data/trawl-stats")
  private val linux = System.getProperty("os.name").lowercase().contains("linux")
  private val mac = System.getProperty("os.name").lowercase().contains("mac")
  private val osb = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private val tmx = ManagementFactory.getThreadMXBean()
  private val ncpu = Runtime.getRuntime().availableProcessors()

  @JvmStatic fun main(raw: Array<String>) {
    val a = raw.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    try {
      when (a["mode"] ?: "run") { "prep" -> Prep().run(); "rebase" -> rebase(a); "spin" -> spinMain(a); else -> Run(a).run() }
    } catch (t: Throwable) {
      t.printStackTrace(); System.exit(1)
    }
    System.exit(0)   // the engine's native threads would keep the process alive
  }

  private fun sh(vararg cmd: String, timeoutS: Long = 3600): String {
    val p = ProcessBuilder(cmd.toList()).redirectErrorStream(true).start()
    val out = p.inputStream.readBytes().decodeToString()
    p.waitFor(timeoutS, TimeUnit.SECONDS)
    return out
  }

  private fun median(xs: List<Double>): Double = if (xs.isEmpty()) Double.NaN else xs.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
  private fun pct(xs: List<Double>, p: Int): Double = if (xs.isEmpty()) Double.NaN else xs.sorted().let { it[((it.size - 1) * p / 100.0).let { x -> Math.round(x).toInt() }.coerceIn(0, it.size - 1)] }

  // ---- prep -----------------------------------------------------------------------------------------------------------------------

  /**
   * Makes base/data: a copy of the golden index (the 130,000-file Documents tree of the stats harness, 1.4 million segments), cleaned of the 50,000
   * rows of a skipped folder, every file marked read, and a tenth of the chunks given a vector so that a search costs what it does for a person
   * who has used Trawl for a while. The tree itself is shared and only read; a run writes into its own `Live/` and `Projects/agent` folders and removes them.
   */
  private class Prep {
    fun run() {
      val base = File(bg, "base")
      if (File(base, "prep.done").exists()) { println("base exists"); return }
      base.deleteRecursively(); base.mkdirs()
      println(sh("cp", "-a", File(TREE_BASE, "golden").path, File(base, "data").path))
      val store = IndexStore.open(File(base, "data/index"))
      val ids = ArrayList<Long>()
      store.read { db -> db.query("SELECT id FROM files WHERE uri LIKE '%/Projects/big/node_modules/%'") { ids += it.getLong(0) } }
      val t0 = System.currentTimeMillis()
      for (b in ids.chunked(3200)) store.markDeleted(b, checkpoint = false)
      println("forgot ${ids.size} ghost rows in ${(System.currentTimeMillis() - t0) / 1000} s")
      store.write { db -> db.run("UPDATE files SET state = 'understood', detail = NULL WHERE state IN ('searchable', 'new')") }
      val pct = (System.getProperty("vecpct") ?: "10").toInt()
      store.write { db -> db.run("UPDATE segments SET vec = randomblob(1536), modality = 'text' WHERE kind = 'chunk' AND vec IS NULL AND (id % 100) < $pct") }
      val counts = HashMap<String, Long>()
      store.read { db -> db.query("SELECT state, COUNT(*) FROM files GROUP BY state") { counts[it.getText(0)] = it.getLong(1) } }
      val segs = store.read { db -> db.long("SELECT COUNT(*) FROM segments") }
      val vecs = store.read { db -> db.long("SELECT COUNT(*) FROM segments WHERE vec IS NOT NULL") }
      store.trimWal(0)
      store.close()
      println("prep index: files $counts, segments $segs, vectors $vecs")
      println(sh("du", "-sh", File(base, "data").path))
      File(base, "prep.done").writeText("ok")
    }
  }

  /**
   * Moves a prepared index to another place on disk: `mode=rebase index=<dir of the index> from=<old tree root> to=<new tree root>` rewrites the
   * path of every file row (a file's identity is a hash of its path, so both the address and the id change). It is how the Mac gets the same
   * 130,000-file index that Crane measured, without reading the tree again.
   */
  private fun rebase(a: Map<String, String>) {
    val from = File(a["from"]!!).toPath().toAbsolutePath().normalize(); val to = File(a["to"]!!).toPath().toAbsolutePath().normalize()
    val store = IndexStore.open(File(a["index"]!!))
    val rows = ArrayList<Pair<Long, String>>()
    store.read { db -> db.query("SELECT id, uri FROM files") { rows += it.getLong(0) to it.getText(1) } }
    var moved = 0; var kept = 0
    for (b in rows.chunked(5000)) store.write { db ->
      db.transaction {
        for ((id, uri) in b) {
          val old = java.nio.file.Paths.get(java.net.URI(uri)).toAbsolutePath().normalize()
          if (!old.startsWith(from)) { kept++; continue }
          val now = to.resolve(from.relativize(old)).normalize()
          db.run("UPDATE files SET uri = ?, mediastore_id = ? WHERE id = ?", now.toUri().toString(), Crawler.idOf(now), id)
          moved++
        }
      }
    }
    store.trimWal(0); store.close()
    println("rebased $moved rows from $from to $to ($kept left as they were)")
  }

  // ---- the run --------------------------------------------------------------------------------------------------------------------

  /**
   * The person's own task: a program of their own that keeps [threads] cores busy. It is a separate process, as a build or a video call
   * would be, so that the app's governor sees it as somebody else's work and not as its own. It writes how much it has done, and when,
   * to a file that the bench reads; the rate is worked out against the clock stamped in the file.
   */
  private class Spinner(val threads: Int) {
    private val out = File.createTempFile("trawl-spin", ".cnt")
    private var proc: Process? = null
    fun start() {
      val java = File(System.getProperty("java.home"), "bin/java").path
      val cmd = listOf(java, "-Xmx64m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"), "trawl.desktop.BackgroundBench", "mode=spin", "threads=$threads", "out=${out.path}")
      // -Dbench.spinscope=1 (Linux): the person's program gets a systemd scope of its own, as a program started from a terminal or a launcher has. Without it the
      // program shares the app's scope, and a CPU weight put on that scope (the Linux lever) would lower both alike and show nothing.
      val wrapped = if (System.getProperty("bench.spinscope") == "1") listOf("systemd-run", "--user", "--scope", "--quiet") + cmd else cmd
      proc = ProcessBuilder(wrapped)
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      val until = System.currentTimeMillis() + 15_000
      while (read().first == 0L && System.currentTimeMillis() < until) Thread.sleep(50)
    }
    /** Units done so far, and the clock (System.nanoTime) at which they were counted. */
    fun read(): Pair<Long, Long> = try {
      val p = out.readText().trim().split(' ')
      if (p.size == 2) p[0].toLong() to p[1].toLong() else 0L to 0L
    } catch (_: Exception) { 0L to 0L }
    fun stop() { proc?.destroy(); proc?.waitFor(5, TimeUnit.SECONDS); out.delete(); File(out.path + ".tmp").delete() }
  }

  /** The other end: spin on [threads] threads and write "units nanoTime" to [out] every 50 ms, until the file is deleted. */
  private fun spinMain(a: Map<String, String>) {
    val threads = a["threads"]!!.toInt(); val out = File(a["out"]!!)
    val units = LongAdder()
    repeat(threads) {
      Thread {
        var x = 1.0
        while (true) {
          var i = 0
          while (i < 300_000) { x = x * 1.0000001 + 1e-9; i++ }   // about a millisecond
          if (x < 0) println(x)
          units.increment()
        }
      }.apply { isDaemon = true; name = "bench-spin"; start() }
    }
    val tmp = File(out.path + ".tmp")
    while (out.exists()) {
      tmp.writeText("${units.sum()} ${System.nanoTime()}")
      tmp.renameTo(out)
      Thread.sleep(50)
    }
  }

  private class Snap(val wallNs: Long, val procNs: Long, val trawlNs: Long, val kidsNs: Long, val sysBusy: Long, val sysTotal: Long, val spin: Long, val spinNs: Long, val walks: Long, val published: Long)

  private fun procStat(): Pair<Long, Long> {
    // /proc/stat "cpu" line: user nice system idle iowait irq softirq steal
    val f = File("/proc/stat").readLines().first().split(Regex("\\s+")).drop(1).map { it.toLong() }
    val total = f.take(8).sum()
    return (total - f[3] - f[4]) to total
  }

  private fun kidsNs(): Long {
    if (!linux) return 0
    val s = File("/proc/self/stat").readText()
    val after = s.substring(s.lastIndexOf(')') + 2).split(' ')   // field 3 onward
    val cutime = after[13].toLong(); val cstime = after[14].toLong()
    return (cutime + cstime) * 10_000_000L   // 100 ticks a second
  }

  private fun trawlThreadsNs(): Long {
    var t = 0L
    for (id in tmx.allThreadIds) {
      val name = tmx.getThreadInfo(id)?.threadName ?: continue
      if (name.startsWith("trawl-")) t += tmx.getThreadCpuTime(id).coerceAtLeast(0)
    }
    return t
  }

  private class Run(val a: Map<String, String>) {
    val label = a["label"] ?: "run"
    val rep = a["rep"]?.toInt() ?: 1
    val scens = (a["scen"] ?: "start,idle,paused,latency,tput,search,churn,busy").split(',').map { it.trim() }.toSet()
    val win = (a["win"] ?: "45").toLong()
    val speed = when ((a["speed"] ?: "balanced").lowercase()) { "gentle" -> LearningSpeed.GENTLE; "fast" -> LearningSpeed.FAST; else -> LearningSpeed.BALANCED }
    val poll = a["watch"] == "poll"
    val model = File(a["model"] ?: System.getenv("TRAWL_BG_MODEL") ?: "/mnt/data/findr-ml/litert/embeddinggemma-2-text-vision-440m.litertlm")
    val useModel = (a["model"] ?: "on") != "off"
    val assets = File(a["assets"] ?: File(bg, "assets").path)
    val work = File(bg, "work/$label-$rep")
    val base = File(a["base"] ?: File(bg, "base").path)
    val docs = File(a["tree"] ?: File(TREE_BASE, "tree/Documents").path)
    val live = File(docs, "Live")
    val agent = File(docs, "Projects/agent")
    val resultsFile = File(bg, "results/$label.jsonl")
    lateinit var ix: IndexService
    var watcher: FolderWatcher? = null
    val rnd = Random(rep * 7919L + 3)
    var seq = 0

    fun emit(scen: String, m: Map<String, Any?>) {
      val body = m.entries.joinToString(",") { (k, v) -> "\"$k\":" + when (v) { is Double -> if (v.isNaN() || v.isInfinite()) "null" else "%.4f".format(v); is String -> "\"$v\""; else -> v.toString() } }
      val line = "{\"label\":\"$label\",\"rep\":$rep,\"scen\":\"$scen\",$body}"
      println(line)
      resultsFile.parentFile.mkdirs(); resultsFile.appendText(line + "\n")
    }

    fun snap(sp: Spinner? = null): Snap {
      val (busy, total) = if (linux) procStat() else 0L to 0L
      val (u, un) = sp?.read() ?: (0L to 0L)
      return Snap(System.nanoTime(), osb.processCpuTime, trawlThreadsNs(), kidsNs(), busy, total, u, un, Crawler.Counters.walks.sum(), ix.publishCount.get())
    }

    /** What the interval between two snapshots cost: CPU-seconds a minute (this process, its helper programs), cores the whole computer was busy, and the rest. */
    fun cost(a: Snap, b: Snap): MutableMap<String, Any?> {
      val secs = (b.wallNs - a.wallNs) / 1e9
      val min = secs / 60.0
      val proc = (b.procNs - a.procNs) / 1e9 / min
      val kids = (b.kidsNs - a.kidsNs) / 1e9 / min
      val m = LinkedHashMap<String, Any?>()
      m["secs"] = secs
      m["cpu_proc_min"] = proc
      m["cpu_threads_min"] = (b.trawlNs - a.trawlNs) / 1e9 / min
      m["cpu_kids_min"] = kids
      m["cpu_all_min"] = proc + kids
      if (linux && b.sysTotal > a.sysTotal) {
        val busyCores = (b.sysBusy - a.sysBusy).toDouble() / (b.sysTotal - a.sysTotal) * ncpu
        m["sys_busy_cores"] = busyCores
        m["others_cores"] = (busyCores - (proc + kids) / 60.0).coerceAtLeast(0.0)
      }
      m["walks_min"] = (b.walks - a.walks) / min
      m["publishes_min"] = (b.published - a.published) / min
      return m
    }

    fun write(f: File, text: String) { f.parentFile.mkdirs(); f.writeText(text) }

    val vocab: List<String> by lazy {
      val r = Random(11)
      val syl = listOf("ra", "ko", "mi", "tu", "an", "el", "or", "ing", "est", "ver", "con", "pro", "data", "file", "user", "node", "list", "map", "get", "set", "index", "query", "path", "name", "type")
      List(30_000) { i -> buildString { repeat(2 + r.nextInt(3)) { append(syl[r.nextInt(syl.size)]) }; append(i % 97) } }
    }
    fun words(n: Int): String = buildString { repeat(n) { if (it > 0) append(' '); append(vocab[rnd.nextInt(vocab.size)]) } }

    fun photos(): List<File> = File(assets, "photos").listFiles { f -> f.name.endsWith(".jpg") }!!.sorted()
    fun pdfs(): List<File> = File(assets, "pdf").listFiles { f -> f.name.endsWith(".pdf") }!!.sorted()

    fun addPhoto(dir: File, n: Int): File {
      val src = photos()[n % photos().size]
      val dst = File(dir, "IMG_%05d.jpg".format(n)); dst.parentFile.mkdirs()
      dst.outputStream().use { o -> o.write(src.readBytes()); o.write("\nTRAWLBENCH $label $rep $n ${System.nanoTime()}".toByteArray()) }   // bytes after the end of a JPEG are ignored: a new file for the app, the same picture
      return dst
    }
    fun addDoc(dir: File, n: Int, token: String = "zq${rep}x${n}q"): File {
      val dst = File(dir, "note-$token.${if (n % 3 == 0) "md" else "txt"}")
      write(dst, "Meeting note $token about the budget, the garden and the trip to Entebbe.\n\n" + (1..4).joinToString("\n\n") { words(90) } + "\n")
      return dst
    }
    fun addPdf(dir: File, n: Int): File {
      val src = pdfs()[n % pdfs().size]
      val dst = File(dir, "doc-%04d-${src.name}".format(n)); dst.parentFile.mkdirs()
      dst.outputStream().use { o -> o.write(src.readBytes()); o.write("\n%TRAWLBENCH $label $rep $n ${System.nanoTime()}\n".toByteArray()) }
      return dst
    }

    fun settle(maxS: Long = 900, quietS: Int = 4): Boolean {
      val end = System.currentTimeMillis() + maxS * 1000
      var ok = 0
      while (System.currentTimeMillis() < end) {
        Thread.sleep(500)
        val u = ix.ui.value
        if (u.error != null) error("index error: ${u.error}")
        if (u.phase == Phase.UP_TO_DATE) ok++ else ok = 0
        if (ok >= quietS * 2) return true
      }
      return false
    }

    /** Waits until no folder has been walked for [stableS] seconds (true), or [maxS] seconds have passed (false). */
    fun quietWalks(maxS: Long, stableS: Int): Boolean {
      val end = System.currentTimeMillis() + maxS * 1000
      var last = Crawler.Counters.walks.sum(); var calm = 0
      while (System.currentTimeMillis() < end) {
        Thread.sleep(1000)
        val w = Crawler.Counters.walks.sum()
        if (w == last) calm++ else { calm = 0; last = w }
        if (calm >= stableS) return true
      }
      return false
    }

    fun thermal(): String = if (mac) sh("pmset", "-g", "therm", timeoutS = 5).lineSequence().firstOrNull { it.contains("CPU_Speed_Limit") }?.trim() ?: "nominal" else ""

    fun run() {
      println("== $label rep $rep scen $scens tunables ${System.getProperties().stringPropertyNames().filter { it.startsWith("trawl.tune.") }.sorted().joinToString(" ") { "$it=${System.getProperty(it)}" }}")
      live.deleteRecursively(); agent.deleteRecursively()
      work.deleteRecursively(); work.mkdirs()
      println(sh("cp", "-a", File(base, "data/index").path, File(work, "index").path))
      val ghost = a["ghost"]?.toInt() ?: 0
      if (ghost > 0) addGhosts(ghost)
      val ocr = if ((a["ocr"] ?: "none") == "real") ({ Ocrs.choose(work) }) else ({ null })
      ix = IndexService(work, decoderChoice = { MediaDecoders.choose(File(work, "helpers")) }, ocrChoice = ocr, log = { if (it.startsWith("background:") || it.startsWith("foreground:")) println("svc: $it") })
      try {
        ix.configure(Crawler.KINDS, emptyList(), speed, paused = a["paused"] == "1")
        // windows=none: no window of the app shows (the whole-app lever goes in, if this system has one); back: shown but not in front; front: the default.
        when (a["windows"]) { "none" -> ix.windows(mainVisible = false, mainFocused = false, panelOpen = false); "back" -> ix.windows(mainVisible = true, mainFocused = false, panelOpen = false) }
        val spawn = snap()
        if (useModel) {
          ix.attachModel(model)
          val until = System.currentTimeMillis() + 300_000
          while (System.currentTimeMillis() < until && !ix.ui.value.modelReady) Thread.sleep(250)
          check(ix.ui.value.modelReady) { "the model did not load" }
          emit("model", mapOf("secs" to (snap().wallNs - spawn.wallNs) / 1e9, "rss_mb" to rssMb()))
        }
        val s0 = snap()
        ix.setFolders(listOf(docs))
        Thread.sleep(2500)
        watcher = FolderWatcher(maxWatches = if (poll) ({ 100L }) else ({ FolderWatcher.inotifyLimit() }), ignore = { false }, onDirty = { ix.reconcile(it) }).also { w -> ix.onWalked = { d, ms -> w.walked(d, ms) }; w.watch(listOf(docs)) }
        val settled = if (a["paused"] != "1") settle(a["settle"]?.toLong() ?: 1800) else { Thread.sleep(if ("start" in scens) win * 1000 else 3000); false }
        val s1 = snap()
        val m = cost(s0, s1)
        m["settled"] = settled; m["files"] = ix.ui.value.total; m["understood"] = ix.ui.value.understood; m["forgotten"] = ix.forgottenSoFar; m["watch"] = watcher!!.info.modes.values.toString(); m["rss_mb"] = rssMb()
        if ("start" in scens) emit("start", m)
        Thread.sleep(8000)
        if ("idle" in scens) idle("idle")
        if ("paused" in scens) paused()
        if ("latency" in scens) latency()
        if ("tput" in scens) tput()
        if ("search" in scens) search()
        if ("churn" in scens) churn()
        if ("busy" in scens) busy()
      } finally {
        runCatching { watcher?.close() }; runCatching { ix.close() }
        live.deleteRecursively(); agent.deleteRecursively()
        work.deleteRecursively()
      }
    }

    fun rssMb(): Long = if (linux) File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong() / 1024 else Runtime.getRuntime().totalMemory() / 1_000_000

    fun addGhosts(n: Int) {
      val store = IndexStore.open(File(work, "index"))
      store.write { db ->
        db.transaction {
          for (i in 0 until n) {
            val f = File(live, "ghost/g${i / 100}/f$i.txt"); f.parentFile.mkdirs(); val p = f.toPath()
            db.run("INSERT INTO files(volume, mediastore_id, uri, rel_path, name, kind, size, mtime, state, fingerprint, priority, date_ms) VALUES(?,?,?,?,?,?,?,?, 'understood', '1:2', 5, ?)",
              Crawler.VOLUME, Crawler.idOf(p), p.toUri().toString(), "Live/ghost/g${i / 100}/", f.name, FileKind.DOCUMENT.name, 100L, 1_700_000_000_000L + i, 1_700_000_000_000L + i)
            val id = db.lastInsertId()
            db.run("INSERT INTO segments(file, kind, modality) VALUES(?, 'name', 'none')", id)
            db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)", db.lastInsertId(), f.name, "ghost")
            for (c in 0 until 4) {
              db.run("INSERT INTO segments(file, kind, modality, loc_start, loc_end, late) VALUES(?, 'chunk', 'none', ?, ?, 0)", id, c * 400L, c * 400L + 400)
              db.run("INSERT INTO fts(rowid, name, body) VALUES(?, '', ?)", db.lastInsertId(), words(22))
            }
          }
        }
      }
      store.close()
    }

    // ---- (a) idle and paused -------------------------------------------------------------------------------------------------

    fun idle(name: String) {
      val a0 = snap(); val th0 = thermal(); Thread.sleep(win * 1000); val a1 = snap()
      val m = cost(a0, a1); m["rss_mb"] = rssMb(); m["thermal"] = thermal().ifEmpty { th0 }
      emit(name, m)
    }

    fun paused() {
      ix.configure(Crawler.KINDS, emptyList(), speed, paused = true)
      Thread.sleep(4000)
      // A computer that has been paused for hours is not walking its folders for the first time: measure from the quiet after that walk.
      val s0 = snap(); val t0 = System.nanoTime(); val q = quietWalks(900, 15)
      val m0 = cost(s0, snap()); m0["quiet"] = q; m0["quiet_s"] = (System.nanoTime() - t0) / 1e9
      emit("paused_scan", m0)
      idle("paused")
      if (a["paused"] == "1") return   // a computer that started paused with a backlog stays paused: resuming would start reading it
      ix.configure(Crawler.KINDS, emptyList(), speed, paused = false)
      settle(120, 3)
    }

    // ---- (b) from a new file to findable ---------------------------------------------------------------------------------------

    fun latency() {
      val dir = File(live, "latency")
      for ((kind, make) in listOf<Pair<String, (Int) -> File>>("doc" to { n -> addDoc(dir, 5000 + n) }, "photo" to { n -> addPhoto(dir, 5000 + n) }, "pdf" to { n -> addPdf(dir, 5000 + n) })) {
        val rowAt = ArrayList<Double>(); val wordsAt = ArrayList<Double>(); val doneAt = ArrayList<Double>()
        var findable = 0
        for (i in 0 until 5) {
          settle(120, 3)
          val f = make(i)
          val t0 = System.nanoTime()
          var row = -1.0; var done = -1.0
          val limit = t0 + 120_000_000_000L
          while (System.nanoTime() < limit) {
            val st = ix.progressOf(f)
            val t = (System.nanoTime() - t0) / 1e9
            if (st != null && row < 0) row = t
            if (st != null && st.first == "understood" || st != null && st.first == "failed") { done = t; break }
            Thread.sleep(20)
          }
          if (row >= 0) rowAt += row
          if (done >= 0) doneAt += done
          if (kind == "doc" && row >= 0) {
            val token = f.name.removePrefix("note-").substringBeforeLast('.')
            if (ix.search(token, KindFilter.ALL).hits.any { it.file.name == f.name }) findable++
          }
          Thread.sleep(2500)
        }
        emit("latency_$kind", mapOf("name_s_med" to median(rowAt), "name_s_max" to (rowAt.maxOrNull() ?: Double.NaN), "read_s_med" to median(doneAt), "read_s_max" to (doneAt.maxOrNull() ?: Double.NaN), "n" to rowAt.size, "read_n" to doneAt.size, "found_by_name" to (if (kind == "doc") findable else -1)))
      }
      live.deleteRecursively(); settle(120, 3)
    }

    // ---- (c) throughput by kind ------------------------------------------------------------------------------------------------

    fun tput() {
      for ((kind, n) in listOf("photo" to 30, "doc" to 60, "pdf" to 14)) {
        val dir = File(live, "tput-$kind")
        settle(120, 3)
        repeat(n) { i -> when (kind) { "photo" -> addPhoto(dir, i); "doc" -> addDoc(dir, i, "zq${rep}t$i"); else -> addPdf(dir, i) } }
        val a0 = snap(); val t0 = System.nanoTime()
        var done = 0
        val limit = t0 + 300_000_000_000L
        while (System.nanoTime() < limit) {
          val s = ix.statesUnder(dir)
          done = (s["understood"] ?: 0) + (s["failed"] ?: 0)
          if (done >= n) break
          Thread.sleep(250)
        }
        val a1 = snap()
        val secs = (a1.wallNs - a0.wallNs) / 1e9
        val m = cost(a0, a1)
        m["kind"] = kind; m["files"] = done; m["of"] = n; m["files_min"] = done / (secs / 60.0); m["cpu_per_file"] = (a1.procNs - a0.procNs + a1.kidsNs - a0.kidsNs) / 1e9 / done.coerceAtLeast(1)
        emit("tput", m)
        dir.deleteRecursively()
      }
      settle(120, 3)
    }

    // ---- (d) search while the app reads ----------------------------------------------------------------------------------------

    val phrases = listOf("a dog on the beach", "receipt from the shop", "birthday party photo", "school fees for term two", "map of the city", "invoice for the garden", "wedding programme", "blood test results", "the trip to entebbe", "photo of a car")
    fun query(i: Int): String = if (i % 3 == 2) phrases[i % phrases.size] else "${vocab[rnd.nextInt(vocab.size)]} ${vocab[rnd.nextInt(vocab.size)]}"

    fun search() {
      settle(120, 3)
      val quiet = ArrayList<Double>()
      repeat(25) { i -> val t = System.nanoTime(); ix.search(query(i), KindFilter.ALL); quiet += (System.nanoTime() - t) / 1e6; Thread.sleep(300) }
      val dir = File(live, "search")
      repeat(80) { addPhoto(dir, 100 + it) }
      Thread.sleep(2500)
      val lat = ArrayList<Double>()
      val a0 = snap(); val before = (ix.statesUnder(dir)["understood"] ?: 0)
      val end = System.nanoTime() + win * 1_000_000_000L
      var i = 0
      while (System.nanoTime() < end) {
        val t = System.nanoTime(); ix.search(query(100 + i++), KindFilter.ALL); lat += (System.nanoTime() - t) / 1e6
        Thread.sleep(600)
      }
      val a1 = snap()
      val readDuring = (ix.statesUnder(dir)["understood"] ?: 0) - before
      val m = cost(a0, a1)
      m["idle_p50_ms"] = median(quiet); m["idle_p95_ms"] = pct(quiet, 95)
      m["busy_p50_ms"] = median(lat); m["busy_p95_ms"] = pct(lat, 95); m["busy_max_ms"] = lat.maxOrNull() ?: Double.NaN; m["queries"] = lat.size
      m["photos_read"] = readDuring; m["photos_min"] = readDuring / (m["secs"] as Double / 60.0)
      emit("search", m)
      dir.deleteRecursively(); settle(300, 3)
    }

    // ---- (e) a coding agent writing into a watched folder ----------------------------------------------------------------------

    fun churn() {
      settle(120, 3)
      val dir = agent
      val a0 = snap(); val reads0 = ix.statesUnder(dir)
      val secs = win * 2
      val perSec = 1000 / 60.0
      val end = System.nanoTime() + secs * 1_000_000_000L
      var written = 0; var next = System.nanoTime()
      while (System.nanoTime() < end) {
        val r = rnd.nextDouble()
        val k = rnd.nextInt(400)
        when {
          r < 0.55 -> write(File(dir, "src/m${k / 20}/f${k % 20}.${listOf("kt", "py", "js", "json")[k % 4]}"), "// rev ${written} \nfun f$k() = ${rnd.nextInt()}\n".repeat(1 + rnd.nextInt(4)))
          r < 0.65 -> write(File(dir, "src/new/n${written}.kt"), "class N$written\n")
          r < 0.80 -> write(File(dir, "build/classes/c${rnd.nextInt(300)}.class.txt"), "x${written}")
          r < 0.90 -> write(File(dir, "node_modules/pkg${rnd.nextInt(50)}/i${written % 20}.js"), "module.exports = $written;\n")
          else -> addDoc(File(dir, "docs"), 20_000 + written, "zq${rep}c$written")
        }
        written++
        next += (1e9 / perSec).toLong()
        val wait = (next - System.nanoTime()) / 1_000_000
        if (wait > 0) Thread.sleep(wait)
      }
      val a1 = snap()
      val m = cost(a0, a1)
      m["written"] = written
      emit("churn", m)
      val d0 = snap()
      settle(300, 4)
      val d1 = snap()
      val dm = cost(d0, d1)
      dm["docs_read"] = (ix.statesUnder(dir)["understood"] ?: 0) - (reads0["understood"] ?: 0)
      emit("churn_drain", dm)
      agent.deleteRecursively(); Thread.sleep(3000); settle(120, 3)
    }

    // ---- (e) the person's own heavy task while the app reads -------------------------------------------------------------------

    fun busy() {
      for (threads in listOf(ncpu, ncpu / 2)) {
        settle(180, 3)
        val sp = Spinner(threads); sp.start()
        Thread.sleep(2000)
        val b0 = snap(sp); Thread.sleep(8000); val b1 = snap(sp)          // the person's task alone
        val dir = File(live, "busy-$threads")
        repeat(80) { addPhoto(dir, 300 + it) }
        Thread.sleep(2000)
        val u0 = ix.statesUnder(dir)["understood"] ?: 0
        val c0 = snap(sp)
        Thread.sleep(win * 1000)
        val c1 = snap(sp)
        val u1 = ix.statesUnder(dir)["understood"] ?: 0
        // Then the person searches, still with their own task running and the reader still at work: a reader at the lowest priority
        // must not hold the search up. (Kept apart from the window above: a search makes the reader step aside, which would hide how much it reads.)
        val lat = ArrayList<Double>()
        repeat(10) { qi -> val t = System.nanoTime(); ix.search(query(500 + qi), KindFilter.ALL); lat += (System.nanoTime() - t) / 1e6; Thread.sleep(1500) }
        ix.configure(Crawler.KINDS, emptyList(), speed, paused = true)
        Thread.sleep(2000)
        val d0 = snap(sp); Thread.sleep(8000); val d1 = snap(sp)           // alone again
        sp.stop()
        ix.configure(Crawler.KINDS, emptyList(), speed, paused = false)
        fun rate(x: Snap, y: Snap) = (y.spin - x.spin) / ((y.spinNs - x.spinNs) / 1e9)
        val alone = (rate(b0, b1) + rate(d0, d1)) / 2
        val m = cost(c0, c1)
        m["fg_threads"] = threads; m["fg_alone_rate"] = alone; m["fg_busy_rate"] = rate(c0, c1)
        m["fg_slowdown_pct"] = (1 - rate(c0, c1) / alone) * 100
        m["fg_alone_drift_pct"] = (rate(d0, d1) / rate(b0, b1) - 1) * 100
        m["photos_read"] = u1 - u0; m["photos_min"] = (u1 - u0) / (m["secs"] as Double / 60.0)
        m["search_p50_ms"] = median(lat); m["search_p95_ms"] = pct(lat, 95); m["search_max_ms"] = lat.maxOrNull() ?: Double.NaN; m["queries"] = lat.size
        emit("busy", m)
        dir.deleteRecursively(); settle(300, 3)
      }
    }
  }
}
