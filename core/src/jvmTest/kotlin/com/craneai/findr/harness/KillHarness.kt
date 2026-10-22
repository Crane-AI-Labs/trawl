package com.craneai.findr.harness

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.random.Random

/**
 * Kill harness for the index-wipe bug (internal/research/usage-sim.md, bug 1). Not a unit test: run it with
 *   ./gradlew :core:killHarness -Pkh="kill /mnt/data/kh/run1 300 400 6000"
 * Modes:
 *   child  <dir> <seed>                         open the store like the app does, then write forever (the process the orchestrator kills)
 *   kill   <dir> <kills> <minMs> <maxMs> [opts] spawn a child, SIGKILL it at a random moment, start the next one; count wipes
 *            opts: --nowait (start the next child without waiting for the killed one to exit), --openkill (sometimes kill during open)
 *   steady <dir> <seconds> <checkEverySec>      no kills: write with the same workload and check the FTS index while it grows
 * The workload uses the real IndexStore API with the app's write shapes: crawl upserts, the words pass (FTS inserts and, through the
 * `segments_gone` trigger, deletes), the full pass (vectors), quick look, file deletes with a TRUNCATE checkpoint, and readers.
 */

private const val MAIN = "com.craneai.findr.harness.KillHarnessKt"

fun main(args: Array<String>) {
  when (args.getOrNull(0)) {
    "child" -> runChild(File(args[1]), args[2].toLong())
    "kill" -> runKill(args)
    "steady" -> runSteady(File(args[1]), args[2].toInt(), args[3].toInt())
    "busy" -> runBusy(File(args[1]), args[2].toLong())
    else -> { System.err.println("usage: child|kill|steady …"); System.exit(2) }
  }
}

// ---- text and records --------------------------------------------------------------------------------------------------

private object Words {
  private val syl = listOf("ka", "ba", "lo", "mi", "tu", "ren", "sta", "tion", "ver", "pro", "con", "dis", "ing", "al", "or", "an", "ex", "pa", "ti", "de")
  val vocab: List<String> = run {
    val r = Random(7)
    val seen = LinkedHashSet<String>()
    while (seen.size < 6000) seen += (1..(1 + r.nextInt(3))).joinToString("") { syl[r.nextInt(syl.size)] }
    seen.toList()
  }
  private val suffix = listOf("", "", "", "s", "ing", "ed", "ation")
  private val cum: DoubleArray = run {
    var acc = 0.0
    DoubleArray(vocab.size) { acc += 1.0 / (it + 1.0).pow(1.05); acc }
  }
  fun word(r: Random): String {
    val x = r.nextDouble() * cum.last()
    var lo = 0; var hi = cum.size - 1
    while (lo < hi) { val m = (lo + hi) ushr 1; if (cum[m] < x) lo = m + 1 else hi = m }
    return vocab[lo] + suffix[r.nextInt(suffix.size)]
  }
  fun text(r: Random, words: Int) = (1..words).joinToString(" ") { word(r) }
}

private class Gen(seed: Long) {
  val r = Random(seed)
  fun kind(): FileKind {
    val x = r.nextInt(100)
    return when { x < 25 -> FileKind.DOCUMENT; x < 35 -> FileKind.PDF; x < 75 -> FileKind.IMAGE; x < 90 -> FileKind.SCREENSHOT; x < 95 -> FileKind.VIDEO; else -> FileKind.AUDIO }
  }
  fun record(id: Long, size: Long = 1000L + r.nextInt(5_000_000)): CrawlRecord {
    val k = kind()
    val ext = when (k) { FileKind.DOCUMENT -> "docx"; FileKind.PDF -> "pdf"; FileKind.VIDEO -> "mp4"; FileKind.AUDIO -> "m4a"; else -> "jpg" }
    return CrawlRecord(
      volume = "external_primary", mediaStoreId = id, uri = "content://media/$id", relativePath = "Download/${Words.word(r)}/${Words.word(r)}/",
      name = "${Words.text(r, 1 + r.nextInt(4)).replace(' ', '_')}_$id.$ext", kind = k, mimeType = null, sizeBytes = size,
      modifiedMillis = System.currentTimeMillis() - r.nextLong(0, 400L * 86_400_000), dateMillis = 0,
    )
  }
  fun vec(n: Int) = FloatArray(n) { r.nextFloat() - 0.5f }
  /** A file's words: documents have 1..40 chunks of about 400 characters, a picture 0..1 short OCR pieces. */
  fun pieces(kind: FileKind, vectors: Boolean): List<Segment> = when (kind) {
    FileKind.DOCUMENT, FileKind.PDF -> {
      // 1 in 150 documents is a long one (the usage-sim corpus had PDFs of 4,578 pieces and a docx of about 5,900 chunks)
      val n = if (r.nextInt(150) == 0) 1500 + r.nextInt(4500) else 1 + minOf(40, (-Math.log(1 - r.nextDouble()) * 8).toInt())
      (0 until n).map { Segment(SegmentKind.CHUNK, if (vectors) Modality.TEXT else Modality.NONE, locStart = it.toLong(), text = Words.text(r, 50 + r.nextInt(30)), vector = if (vectors) vec(768) else null) }
    }
    FileKind.IMAGE, FileKind.SCREENSHOT -> buildList {
      if (r.nextInt(100) < 60) add(Segment(SegmentKind.OCR, Modality.NONE, text = Words.text(r, 5 + r.nextInt(40))))
      if (vectors) add(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(768)))
    }
    else -> if (vectors) listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, text = Words.text(r, 60), vector = vec(768))) else emptyList()
  }
}

// ---- the workload -------------------------------------------------------------------------------------------------------

private class Workload(val store: IndexStore, val seed: Long, val fileCap: Int, val stop: AtomicBoolean) {
  val ops = AtomicLong()
  private val nextId = AtomicLong(store.read { (it.long("SELECT COALESCE(MAX(mediastore_id), 0) FROM files") ?: 0) } + 1)
  private val threads = ArrayList<Thread>()
  private val ALL = FileKind.entries.toSet()
  private val DOC_IMG = setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.IMAGE, FileKind.SCREENSHOT)

  private fun loop(name: String, pauseMs: Long = 0, body: (Gen) -> Unit) {
    val g = Gen(seed * 31 + name.hashCode())
    threads += thread(name = name, isDaemon = true) {
      while (!stop.get()) {
        try { body(g); ops.incrementAndGet() } catch (t: Throwable) { if (!stop.get()) System.err.println("WORKLOAD $name: ${t::class.simpleName}: ${t.message}"); Thread.sleep(50) }
        if (pauseMs > 0) Thread.sleep(pauseMs)
      }
    }
  }

  fun start(readers: Boolean = true) {
    loop("crawl", 5) { g ->
      val total = store.read { it.long("SELECT COUNT(*) FROM files") ?: 0 }
      val batch = ArrayList<CrawlRecord>()
      if (total < fileCap) repeat(40) { batch += g.record(nextId.getAndIncrement()) }
      // changed files (new size: segments and words are dropped, the name goes back in) and renamed ones
      val existing = ArrayList<Long>()
      store.read { db -> db.query("SELECT mediastore_id FROM files ORDER BY random() LIMIT 6") { existing += it.getLong(0) } }
      for (id in existing) batch += g.record(id, size = 100L + g.r.nextInt(9_000_000))
      store.upsertFromCrawl(batch)
    }
    loop("words") { g ->
      val todo = store.nextWithoutWords(8, DOC_IMG)
      if (todo.isEmpty()) { Thread.sleep(20); return@loop }
      for (p in todo) store.saveSegments(p.file.id, g.pieces(p.file.kind, vectors = false), FileState.SEARCHABLE, IndexStore.WORDS_DONE, keepQuick = true)
    }
    loop("full", 2) { g ->
      val todo = store.nextPending(2, ALL, includePartial = false)
      if (todo.isEmpty()) { Thread.sleep(20); return@loop }
      for (p in todo) store.saveSegments(p.file.id, g.pieces(p.file.kind, vectors = true), FileState.UNDERSTOOD, null)
    }
    loop("quick", 3) { g ->
      val todo = store.nextForQuickLook(4, setOf(FileKind.IMAGE, FileKind.SCREENSHOT))
      if (todo.isEmpty()) { Thread.sleep(20); return@loop }
      for (p in todo) store.saveQuick(p.file.id, SegmentKind.IMAGE, g.vec(IndexStore.QUICK_DIM))
    }
    loop("delete", 400) { g ->
      val ids = ArrayList<Long>()
      store.read { db -> db.query("SELECT id FROM files ORDER BY random() LIMIT ${1 + g.r.nextInt(6)}") { ids += it.getLong(0) } }
      store.markDeleted(ids)   // includes the TRUNCATE checkpoint
      if (g.r.nextInt(4) == 0) store.trimWal(0)
    }
    if (readers) repeat(2) { i ->
      loop("reader$i", 1) { g ->
        val q = (1..(1 + g.r.nextInt(3))).joinToString(" OR ") { Words.word(g.r).filter(Char::isLetter).ifEmpty { "ka" } }
        store.read { db -> db.query("SELECT rowid FROM fts WHERE fts MATCH ? ORDER BY rank LIMIT 20", q) { } }
        if (g.r.nextInt(50) == 0) store.stats()
      }
    }
  }

  fun join() { threads.forEach { it.join(10_000) } }
}

// ---- child -------------------------------------------------------------------------------------------------------------

private fun runChild(dir: File, seed: Long) {
  val lines = ArrayList<String>()
  val t0 = System.nanoTime()
  val store = try {
    IndexStore.open(dir, log = { synchronized(lines) { lines += it }; println("LOG $it"); System.out.flush() })
  } catch (t: Throwable) {
    println("OPENFAIL ${t::class.simpleName}: ${t.message}"); System.out.flush(); System.exit(3); return
  }
  val files = store.read { it.long("SELECT COUNT(*) FROM files") ?: 0 }
  val wiped = synchronized(lines) { lines.any { it.contains("starting fresh") } }
  println("OPENED wiped=$wiped files=$files openMs=${(System.nanoTime() - t0) / 1_000_000}"); System.out.flush()
  val w = Workload(store, seed, fileCap = 3500, stop = AtomicBoolean(false))
  w.start()
  var last = 0L
  while (true) { Thread.sleep(1000); val n = w.ops.get(); println("OPS ${n - last}"); System.out.flush(); last = n }
}

// ---- orchestrator --------------------------------------------------------------------------------------------------------

private fun copyDb(dir: File, to: File) {
  to.deleteRecursively(); to.mkdirs()
  dir.listFiles()?.forEach { if (it.name.startsWith("findr-index.db")) it.copyTo(File(to, it.name), overwrite = true) }
}

private fun runKill(args: Array<String>) {
  val dir = File(args[1]); val kills = args[2].toInt(); val minMs = args[3].toLong(); val maxMs = args[4].toLong()
  val noWait = "--nowait" in args; val openKill = "--openkill" in args
  dir.mkdirs()
  val evidence = File(dir.parentFile, dir.name + "-evidence"); evidence.mkdirs()
  val javaBin = File(System.getProperty("java.home"), "bin/java").path
  val cp = System.getProperty("java.class.path")
  val rnd = Random(System.nanoTime())
  var wipes = 0; var openFails = 0; var cleanOpens = 0; var retried = 0
  val reasons = LinkedHashMap<String, Int>()
  var previous: Process? = null
  val snap = File(dir.parentFile, dir.name + "-snap")
  println("kill harness: dir=$dir kills=$kills window=$minMs..${maxMs}ms nowait=$noWait openkill=$openKill")
  for (n in 1..kills) {
    if (!noWait) previous?.waitFor(30, TimeUnit.SECONDS)
    // Snapshot the files as the killed process left them (only possible once it is gone), to keep evidence of a wipe.
    if (!noWait) copyDb(dir, snap)
    // Each JVM unpacks SQLite's native library into its temp dir and a killed one never removes it (250 kills filled a 495 GB root disk
    // once): keep the temp dir beside the run and empty it every round.
    val tmp = File(dir.path + ".tmp").also { it.mkdirs() }
    tmp.listFiles()?.forEach { it.delete() }
    val pb = ProcessBuilder(javaBin, "-Xmx700m", "-Djava.io.tmpdir=${tmp.path}", "-cp", cp, MAIN, "child", dir.path, rnd.nextLong().toString()).redirectErrorStream(true)
    val p = pb.start()
    val out = ArrayList<String>()
    val opened = java.util.concurrent.CountDownLatch(1)
    val reader = thread(isDaemon = true) {
      p.inputStream.bufferedReader().forEachLine { l -> synchronized(out) { out += l }; if (l.startsWith("OPENED") || l.startsWith("OPENFAIL")) opened.countDown() }
    }
    val killEarly = openKill && rnd.nextInt(4) == 0
    if (killEarly) Thread.sleep(rnd.nextLong(0, 400)) else {
      if (!opened.await(60, TimeUnit.SECONDS)) println("  [$n] child did not open within 60 s")
      Thread.sleep(minMs + rnd.nextLong(maxOf(1, maxMs - minMs)))
    }
    val snapshot = synchronized(out) { out.toList() }
    p.destroyForcibly()   // SIGKILL
    previous = p
    reader.join(2000)
    val lines = synchronized(out) { out.toList() }
    val wiped = lines.any { it.contains("starting fresh") }
    val openFail = lines.any { it.startsWith("OPENFAIL") }
    val retry = lines.any { it.contains("retry") }
    val workErr = lines.count { it.startsWith("WORKLOAD") }
    if (wiped) {
      wipes++
      val reason = lines.first { it.contains("index unusable") }.substringAfter("index unusable (").substringBefore("); deleting")
        .replace(Regex("\\d+"), "#")
      reasons.merge(reason, 1, Int::plus)
      if (wipes <= 5 && snap.exists()) { val ev = File(evidence, "wipe$wipes"); snap.copyRecursively(ev, overwrite = true); File(ev, "child.log").writeText(lines.joinToString("\n")) }
    } else if (openFail) openFails++ else cleanOpens++
    if (retry) retried++
    val o = lines.firstOrNull { it.startsWith("OPENED") } ?: lines.firstOrNull { it.startsWith("OPENFAIL") } ?: "no-open-line(killed ${if (killEarly) "early" else "late"})"
    println("[$n] $o${if (wiped) "  <<< WIPE: " + lines.first { it.contains("index unusable") } else ""}${if (workErr > 0) "  workloadErrors=$workErr" else ""}")
    if (snapshot.isEmpty() && !killEarly) println("  (no output captured before kill)")
  }
  previous?.waitFor(30, TimeUnit.SECONDS)
  println("RESULT kills=$kills wipes=$wipes openFails=$openFails cleanOpens=$cleanOpens retried=$retried")
  reasons.forEach { (r, c) -> println("  reason x$c: $r") }
  println("END-MARKER kill-harness done")
}

// ---- busy: another connection still holds the file while the app opens it ----------------------------------------------------

/**
 * A process that is still dying (slow flash, an exiting JVM) keeps the database locked for a while. This builds an index, then holds
 * the file with a second connection in EXCLUSIVE locking mode for [holdMs] and opens the store meanwhile. The old open() gave up after
 * its 5 s busy timeout, took the BUSY for damage and deleted the index; the fixed one waits and retries. Prints one BUSY-RESULT line.
 */
private fun runBusy(dir: File, holdMs: Long) {
  dir.deleteRecursively(); dir.mkdirs()
  val first = IndexStore.open(dir)
  val stop = AtomicBoolean(false)
  val w = Workload(first, 3, fileCap = 600, stop = stop)
  w.start()
  Thread.sleep(6000)
  stop.set(true); Thread.sleep(500)
  val before = first.read { it.long("SELECT COUNT(*) FROM files") ?: 0 }
  first.close()
  val holder = BundledSQLiteDriver().open(File(dir, IndexStore.FILE_NAME).path)
  holder.execSQL("PRAGMA locking_mode = EXCLUSIVE")
  holder.execSQL("BEGIN IMMEDIATE"); holder.execSQL("COMMIT")   // takes the lock, and keeps it after the commit
  val releaser = thread { Thread.sleep(holdMs); holder.close() }
  val lines = ArrayList<String>()
  val t0 = System.nanoTime()
  var threw: String? = null
  var after = -1L
  try {
    val s = IndexStore.open(dir, log = { synchronized(lines) { lines += it }; println("LOG $it") })
    after = s.read { it.long("SELECT COUNT(*) FROM files") ?: 0 }
    s.close()
  } catch (t: Throwable) { threw = "${t::class.simpleName}" }
  releaser.join()
  val wiped = synchronized(lines) { lines.any { it.contains("starting fresh") || it.contains("index unusable") } }
  println("BUSY-RESULT holdMs=$holdMs filesBefore=$before filesAfter=$after wiped=$wiped threw=${threw ?: "no"} openMs=${(System.nanoTime() - t0) / 1_000_000}")
  println("END-MARKER kill-harness done")
}

// ---- steady: no kills, find when the FTS index first fails its own check --------------------------------------------------

fun ftsCheck(c: SQLiteConnection): String? {
  // 1) rank=0: index internal consistency; 2) rank=1 adds a comparison with the content table. Returns the first error or null.
  for (rank in 0..1) {
    try { c.execSQL("INSERT INTO fts(fts, rank) VALUES('integrity-check', $rank)") } catch (t: Throwable) { return "integrity-check($rank): ${t.message}" }
  }
  return null
}

private fun runSteady(dir: File, seconds: Int, every: Int) {
  dir.mkdirs()
  val store = IndexStore.open(dir, log = { println("LOG $it") })
  val stop = AtomicBoolean(false)
  val w = Workload(store, 1, fileCap = 3500, stop = stop)
  w.start()
  val checker = BundledSQLiteDriver().open(File(dir, IndexStore.FILE_NAME).path)
  checker.execSQL("PRAGMA busy_timeout = 20000")
  println("SQLITE ${checker.prepare("SELECT sqlite_version()").use { s -> s.step(); s.getText(0) }}")
  val t0 = System.currentTimeMillis()
  var firstBad: String? = null
  var checks = 0
  while ((System.currentTimeMillis() - t0) / 1000 < seconds) {
    Thread.sleep(every * 1000L)
    checks++
    val files = store.read { it.long("SELECT COUNT(*) FROM files") ?: 0 }
    val segs = store.read { it.long("SELECT COUNT(*) FROM segments") ?: 0 }
    val r = ftsCheck(checker)
    val qc = checker.prepare("PRAGMA quick_check").use { s -> if (s.step()) s.getText(0) else "?" }
    println("[${(System.currentTimeMillis() - t0) / 1000}s] files=$files segments=$segs ops=${w.ops.get()} fts=${r ?: "ok"} quick_check=$qc")
    if ((r != null || qc != "ok") && firstBad == null) { firstBad = "t=${(System.currentTimeMillis() - t0) / 1000}s files=$files segments=$segs ${r ?: qc}"; break }
  }
  stop.set(true); w.join()
  println("RESULT steady checks=$checks firstBad=${firstBad ?: "none"}")
  println("END-MARKER steady done")
  System.exit(0)
}

private inline fun <T> androidx.sqlite.SQLiteStatement.use(block: (androidx.sqlite.SQLiteStatement) -> T): T = try { block(this) } finally { close() }
