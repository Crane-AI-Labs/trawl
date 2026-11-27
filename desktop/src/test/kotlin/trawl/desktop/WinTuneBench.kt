package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * The Windows part of internal/research/desktop-tuning.md: do the thread and helper-process levers of [WinPriority] (W1, W2) do anything for the
 * person's own programs, and do they take Trawl's reading speed away when the person is not there? A plain `main`, run with
 * `java -cp <test classpath> trawl.desktop.WinTuneBench mode=...`, on an Azure Windows 11 VM.
 *
 *   gen      mode=gen tree=<dir> files=<n> [photos=<n> assets=<dir with .jpg>]     a made-up Documents folder (text, markdown, csv, html, docx; pictures)
 *   coexist  mode=coexist label= rep= arm=0|1|2 [proc=below|eco] work=docs|photos|none fg=<threads> win=<s> tree=<dir>
 *            the person's programs (fg: busy threads timed per iteration, plus a timer-jitter probe) run in their own process while the real
 *            IndexService reads the tree for win seconds. arm is -Dtrawl.tune.windowsBackground (pass it on the command line too).
 *   proof    mode=proof [child=1]  what Windows says about the thread / helper after the levers ran (read-back, not what we asked)
 *   fg       (internal) the person's programs
 */
object WinTuneBench {
  private val osb = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
  private fun javaExe() = File(System.getProperty("java.home"), "bin/" + (if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")).path

  @JvmStatic fun main(raw: Array<String>) {
    val a = raw.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    try {
      when (a["mode"]) { "gen" -> gen(a); "fg" -> fgMain(a); "proof" -> proof(a); "procproof" -> procProof(); "coexist" -> coexist(a); "seed" -> seed(a); else -> error("mode=gen|coexist|proof|seed") }
    } catch (t: Throwable) { t.printStackTrace(); System.exit(1) }
    System.exit(0)
  }

  private fun median(xs: List<Double>): Double = if (xs.isEmpty()) Double.NaN else xs.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
  private fun pct(xs: LongArray, n: Int, p: Double): Double {
    if (n == 0) return Double.NaN
    val s = xs.copyOf(n).also { it.sort() }
    return s[((n - 1) * p).toInt().coerceIn(0, n - 1)].toDouble()
  }

  // ---- gen ------------------------------------------------------------------------------------------------------------------------

  private fun gen(a: Map<String, String>) {
    val tree = File(a["tree"]!!); val n = a["files"]!!.toInt(); val photos = a["photos"]?.toInt() ?: 0
    val r = Random(a["seed"]?.toLong() ?: 5)
    val syl = listOf("ra", "ko", "mi", "tu", "an", "el", "or", "ing", "est", "ver", "con", "pro", "data", "file", "user", "node", "list", "map", "get", "set", "index", "query", "path", "name", "type")
    val vocab = List(30_000) { i -> buildString { repeat(2 + r.nextInt(3)) { append(syl[r.nextInt(syl.size)]) }; append(i % 97) } }
    fun words(k: Int) = buildString { repeat(k) { if (it > 0) append(' '); append(vocab[r.nextInt(vocab.size)]) } }
    fun para(k: Int) = (1..k).joinToString("\n\n") { words(60 + r.nextInt(80)) }
    for (i in 0 until n) {
      val dir = File(tree, "d${i / 100}"); dir.mkdirs()
      val k = 2 + r.nextInt(6)
      when (i % 10) {
        in 0..3 -> File(dir, "note-$i.txt").writeText("Meeting note $i\n\n" + para(k))
        in 4..5 -> File(dir, "plan-$i.md").writeText("# Plan $i\n\n" + (1..k).joinToString("\n\n") { "## " + words(3) + "\n\n" + words(70 + r.nextInt(60)) })
        6 -> File(dir, "table-$i.csv").writeText("id,name,note,amount\n" + (1..(20 + r.nextInt(40))).joinToString("\n") { "$it,${words(1)},${words(5)},${r.nextInt(100000)}" })
        7, 8 -> File(dir, "page-$i.html").writeText("<html><head><title>${words(4)}</title></head><body>" + (1..k).joinToString("") { "<h2>${words(3)}</h2><p>${words(80)}</p>" } + "</body></html>")
        else -> docx(File(dir, "report-$i.docx"), (1..k).map { words(70 + r.nextInt(60)) })
      }
    }
    if (photos > 0) {
      val src = File(a["assets"]!!).listFiles { f -> f.name.endsWith(".jpg") }!!.sorted()
      for (i in 0 until photos) {
        val dir = File(tree, "p${i / 100}"); dir.mkdirs()
        File(dir, "IMG_%05d.jpg".format(i)).outputStream().use { o -> o.write(src[i % src.size].readBytes()); o.write("\nWINTUNE $i".toByteArray()) }
      }
    }
    println("gen ${tree.path}: $n documents, $photos pictures")
  }

  private fun docx(f: File, paragraphs: List<String>) {
    ZipOutputStream(f.outputStream()).use { z ->
      fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
      put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
      put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
      put("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" } + "</w:body></w:document>")
    }
  }

  // ---- fg: the person's own programs --------------------------------------------------------------------------------------------

  /** Busy threads that each time every iteration (about 3 to 5 ms of arithmetic), and one thread that sleeps 4 ms at a time and records how late it wakes. */
  private fun fgMain(a: Map<String, String>) {
    val threads = a["threads"]!!.toInt(); val out = File(a["out"]!!)
    val stop = File(out.path + ".stop"); val ready = File(out.path + ".ready")
    val timer = WinProbe.timerResolution1ms()
    val cap = 400_000
    val at = Array(threads) { LongArray(cap) }; val dur = Array(threads) { LongArray(cap) }; val cnt = IntArray(threads)
    val jat = LongArray(cap); val jlate = LongArray(cap); var jn = 0
    val epoch0 = System.currentTimeMillis(); val nano0 = System.nanoTime()
    fun epoch(nano: Long) = epoch0 + (nano - nano0) / 1_000_000
    val running = AtomicBoolean(true)
    val workers = List(threads) { id ->
      Thread {
        var x = 1.0
        while (running.get()) {
          val t0 = System.nanoTime()
          var i = 0
          while (i < 1_500_000) { x = x * 1.0000001 + 1e-9; i++ }
          val t1 = System.nanoTime()
          if (cnt[id] < cap) { at[id][cnt[id]] = epoch(t1); dur[id][cnt[id]] = t1 - t0; cnt[id]++ }
          if (x < 0) println(x)
        }
      }.apply { name = "fg-$id"; isDaemon = true; start() }
    }
    val probe = Thread {
      while (running.get()) {
        val t0 = System.nanoTime()
        LockSupport.parkNanos(4_000_000)
        val t1 = System.nanoTime()
        if (jn < cap) { jat[jn] = epoch(t1); jlate[jn] = (t1 - t0 - 4_000_000).coerceAtLeast(0); jn++ }
      }
    }.apply { name = "fg-jitter"; isDaemon = true; start() }
    Thread.sleep(4000); ready.writeText("$timer ${ProcessHandle.current().pid()}")
    while (!stop.exists()) Thread.sleep(50)
    running.set(false); workers.forEach { it.join(2000) }; probe.join(2000)
    out.bufferedWriter().use { w ->
      for (t in 0 until threads) for (i in 0 until cnt[t]) w.write("i,$t,${at[t][i]},${dur[t][i] / 1000}\n")
      for (i in 0 until jn) w.write("j,0,${jat[i]},${jlate[i] / 1000}\n")
    }
  }

  private class Fg(val threads: Int, val out: File) {
    var proc: Process? = null
    fun start() {
      out.delete(); File(out.path + ".stop").delete(); File(out.path + ".ready").delete()
      proc = ProcessBuilder(javaExe(), "-Xmx256m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"), "trawl.desktop.WinTuneBench", "mode=fg", "threads=$threads", "out=${out.path}")
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      val until = System.currentTimeMillis() + 60_000
      while (!File(out.path + ".ready").exists() && System.currentTimeMillis() < until) Thread.sleep(100)
      check(File(out.path + ".ready").exists()) { "the foreground program did not start" }
    }
    fun finish(): Boolean { File(out.path + ".stop").writeText("x"); return proc?.waitFor(60, TimeUnit.SECONDS) ?: false }
    /** Iterations and wake-ups between two clock readings (epoch ms). */
    fun window(t0: Long, t1: Long): Map<String, Double> {
      val iters = ArrayList<Long>(); val late = ArrayList<Long>()
      out.useLines { lines -> for (l in lines) { val p = l.split(','); val t = p[2].toLong(); if (t < t0 || t > t1) continue; (if (p[0] == "i") iters else late) += p[3].toLong() } }
      val iv = iters.toLongArray(); val lv = late.toLongArray()
      val m = LinkedHashMap<String, Double>()
      val secs = (t1 - t0) / 1000.0
      m["fg_iters_s"] = iv.size / secs
      m["fg_iter_ms_mean"] = if (iv.isEmpty()) Double.NaN else iv.average() / 1000.0
      m["fg_iter_ms_p50"] = pct(iv, iv.size, 0.5) / 1000.0
      m["fg_iter_ms_p95"] = pct(iv, iv.size, 0.95) / 1000.0
      m["fg_iter_ms_p99"] = pct(iv, iv.size, 0.99) / 1000.0
      m["jitter_n"] = lv.size.toDouble()
      m["jitter_ms_p50"] = pct(lv, lv.size, 0.5) / 1000.0
      m["jitter_ms_p95"] = pct(lv, lv.size, 0.95) / 1000.0
      m["jitter_ms_p99"] = pct(lv, lv.size, 0.99) / 1000.0
      m["jitter_ms_max"] = (lv.maxOrNull() ?: 0L) / 1000.0
      return m
    }
  }

  // ---- seed: the cold start of the first temp file (the SQLite library is unpacked through one) while the computer is busy ----------------------

  /** `mode=seed fg=<threads> [wait=<s>]`: how long a cold JVM takes for its first temp file with [fg] busy threads running (what the SQLite loader does at launch). */
  private fun seed(a: Map<String, String>) {
    val n = a["fg"]?.toInt() ?: 0; val wait = (a["wait"] ?: "120").toLong()
    val dir = File(a["root"] ?: "C:/wt", "work/seed"); dir.deleteRecursively(); dir.mkdirs()
    val fg = if (n > 0) Fg(n, File(dir, "fg.csv")).also { it.start() } else null
    var ms = -1.0
    val t = Thread { val t0 = System.nanoTime(); java.nio.file.Files.createTempFile("wt", ".tmp").toFile().delete(); ms = (System.nanoTime() - t0) / 1e6 }.apply { isDaemon = true; start() }
    t.join(wait * 1000)
    println("{\"mode\":\"seed\",\"fg_threads\":$n,\"egd\":\"${System.getProperty("java.security.egd") ?: ""}\",\"first_temp_file_ms\":${if (ms >= 0) "%.0f".format(ms) else "\">${wait * 1000}\""}}")
    fg?.finish(); dir.deleteRecursively()
    System.exit(0)
  }

  // ---- coexist ---------------------------------------------------------------------------------------------------------------------

  private fun coexist(a: Map<String, String>) {
    val label = a["label"] ?: "run"; val rep = a["rep"]?.toInt() ?: 1; val arm = a["arm"] ?: "0"
    val work = a["work"] ?: "docs"; val fgThreads = a["fg"]?.toInt() ?: 0; val win = (a["win"] ?: "60").toLong()
    val speed = when ((a["speed"] ?: "fast").lowercase()) { "gentle" -> LearningSpeed.GENTLE; "balanced" -> LearningSpeed.BALANCED; else -> LearningSpeed.FAST }
    val root = File(a["root"] ?: "C:/wt")
    val scratch = File(root, "work/$label-$rep"); scratch.deleteRecursively(); scratch.mkdirs()
    val results = File(root, "results/$label.jsonl"); results.parentFile.mkdirs()
    val tree = File(a["tree"] ?: File(root, if (work == "photos") "tree-photos" else "tree-docs").path)
    val logs = ArrayList<String>()
    val m = LinkedHashMap<String, Any?>()
    m["label"] = label; m["rep"] = rep; m["arm"] = arm; m["proc"] = a["proc"] ?: "no"; m["work"] = work; m["fg_threads"] = fgThreads; m["win_s"] = win; m["speed"] = speed.name
    m["cpus"] = Runtime.getRuntime().availableProcessors()
    java.nio.file.Files.createTempFile("wt", ".tmp").toFile().delete()   // the first temp file seeds the JVM's random numbers, which stalls for minutes on a saturated Windows computer (mode=seed); done before the load starts
    val fg = if (fgThreads > 0) Fg(fgThreads, File(scratch, "fg.csv")).also { it.start() } else null   // (before the lever: a program started from a lowered process starts lowered)
    a["proc"]?.let {
      // app1 / app2: the shipped lever (BackgroundMode's Windows throttle: below normal, or with EcoQoS); below / eco: the bench's own calls
      m["proc_lever"] = if (it == "app1" || it == "app2") Throttle.forThisSystem(Os.WINDOWS, on = true, windowsLevel = it.last() - '0')?.enter(speed) ?: "refused"
        else WinProbe.lowerOwnProcess(eco = it == "eco")
      m["proc_readback"] = WinPriority.readBackThisProcess()
    }
    var ix: IndexService? = null
    try {
      val t0: Long; val t1: Long
      if (work != "none") {
        val ocr = work == "photos"
        val svc = IndexService(scratch, log = { synchronized(logs) { logs += it } }, ocrChoice = { if (ocr) Ocrs.choose(scratch) else null }, decoderChoice = { null })
        ix = svc
        svc.configure(Crawler.KINDS, emptyList(), speed, paused = false)
        val cpu0 = osb.processCpuTime
        val n0 = System.nanoTime(); t0 = System.currentTimeMillis()
        svc.setFolders(listOf(tree))
        val series = ArrayList<String>()
        while (System.nanoTime() - n0 < win * 1_000_000_000L) {
          Thread.sleep(1000)
          val u = svc.ui.value
          series += "${(System.nanoTime() - n0) / 1_000_000_000L}:${u.understood}/${u.total}/${u.scanned}"
        }
        t1 = System.currentTimeMillis()
        val u = svc.ui.value
        val states = svc.statesUnder(tree)
        m["files_read"] = u.understood; m["files_kept"] = u.total; m["files_scanned"] = u.scanned; m["unreadable"] = u.unreadable
        m["states"] = states.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
        m["files_min"] = u.understood.toDouble() / ((t1 - t0) / 60000.0)
        m["trawl_cpu_s"] = (osb.processCpuTime - cpu0) / 1e9
        m["children_cpu_s"] = ProcessHandle.current().descendants().mapToLong { it.info().totalCpuDuration().map { d -> d.toNanos() }.orElse(0L) }.sum() / 1e9
        m["progress"] = series.filterIndexed { i, _ -> i % 10 == 9 }.joinToString(" ")
      } else {
        t0 = System.currentTimeMillis(); Thread.sleep(win * 1000); t1 = System.currentTimeMillis()
      }
      if (fg != null) { fg.finish(); fg.window(t0, t1).forEach { (k, v) -> m[k] = v } }
      synchronized(logs) { m["lever_log"] = logs.filter { it.contains("priority") || it.contains("background") }.distinct().joinToString(" | ") }
    } finally {
      runCatching { ix?.close() }
      runCatching { fg?.finish() }
      scratch.deleteRecursively()
    }
    val body = m.entries.joinToString(",") { (k, v) -> "\"$k\":" + when (v) { is Double -> if (v.isNaN() || v.isInfinite()) "null" else "%.4f".format(v); is Number, is Boolean -> v.toString(); else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "'") + "\"" } }
    val line = "{$body}"
    println(line); results.appendText(line + "\n")
  }

  // ---- proof ------------------------------------------------------------------------------------------------------------------------

  private fun proof(a: Map<String, String>) {
    val knob = BackgroundTuning.windowsBackground
    val pid = ProcessHandle.current().pid()
    println("== proof: windowsBackground knob = $knob, os = ${System.getProperty("os.name")} ${System.getProperty("os.version")}, java ${System.getProperty("java.version")}, cpus ${Runtime.getRuntime().availableProcessors()}, pid $pid")
    println("process before: ${WinProbe.processState()}")
    val t = Thread {
      println("thread before:        ${WinProbe.threadState()}")
      println("app: LowPriority.applyToThisThread -> ${LowPriority.applyToThisThread()}")
      val st = WinProbe.threadState()
      println("thread after (app):   $st")
      println("powershell, from outside: ${WinProbe.powershellThreadView(pid, st["tid"]!!.toInt())}")
      println("thread-level EcoQoS, the class the app used (4): ${WinProbe.trySetThreadEco(4, true)}")
      println("thread-level EcoQoS, ThreadPowerThrottling=3:   ${WinProbe.trySetThreadEco(3, true)}")
      println("thread after class 3: ${WinProbe.threadState()}")
      println("end background mode: ${WinProbe.trySetThread(false)} -> ${WinProbe.threadState()}")
      println("begin again:         ${WinProbe.trySetThread(true)} -> ${WinProbe.threadState()}")
      println("end again:           ${WinProbe.trySetThread(false)}")
    }.apply { name = "trawl-index-probe"; start() }
    t.join()
    println("process after: ${WinProbe.processState()}   (the thread levers must not change the process: that would lower the window too)")
    if (a["child"] == "1") childProof()
  }

  /** `mode=procproof`: the shipped Windows throttle ([WindowsProcess], what [BackgroundMode] calls), read back from the system and from outside (PowerShell). */
  private fun procProof() {
    val pid = ProcessHandle.current().pid()
    println("== procproof: os = ${System.getProperty("os.name")} ${System.getProperty("os.version")}, cpus ${Runtime.getRuntime().availableProcessors()}")
    fun view(tag: String) {
      println("$tag: ${WinPriority.readBackThisProcess()} | powershell: ${WinProbe.powershell("Get-Process -Id $pid | Select-Object Id,PriorityClass | ConvertTo-Json -Compress")}")
    }
    view("before")
    val sleeper = listOf("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 25")
    val early = HelperProcs.started(HelperProcs.builder(sleeper).start())   // started while in the foreground: it must stay normal
    for (level in 1..2) {
      val t = Throttle.forThisSystem(Os.WINDOWS, on = true, windowsLevel = level)!!
      println("-- level $level")
      println("enter: ${t.enter(LearningSpeed.BALANCED)}"); view("after enter")
      println("enter again (safe to repeat): ${t.enter(LearningSpeed.BALANCED)}"); view("after second enter")
      val child = HelperProcs.started(HelperProcs.builder(sleeper).start())
      Thread.sleep(1500)
      println("helper started while lowered: ${WinProbe.processState(child.pid().toInt())}")
      println("leave: ${t.leave()}"); view("after leave")
      println("leave again (safe to repeat): ${t.leave()}")
      println("helper started in the foreground, before: ${WinProbe.processState(early.pid().toInt())}")
      child.destroy()
    }
    early.destroy()
  }

  private fun childProof() {
    println("-- helper programs (knob ${BackgroundTuning.windowsBackground})")
    val sleeper = listOf("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 40")
    // a child started from a thread that is itself in background mode: is the thread's mode inherited?
    var fromBg: Process? = null
    Thread {
      WinPriority.thisThread(eco = true)
      fromBg = ProcessBuilder(sleeper).start()
    }.apply { start(); join() }
    Thread.sleep(1500)
    println("child of a background-mode thread, nothing else done: ${WinProbe.processState(fromBg!!.pid().toInt())}")
    HelperProcs.started(fromBg!!)
    Thread.sleep(300)
    println("same child after HelperProcs.started (the app's call): ${WinProbe.processState(fromBg!!.pid().toInt())}")
    // a grandchild of the lowered child
    val gc = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 3; \$p = Start-Process ping.exe -ArgumentList '-n','30','127.0.0.1' -PassThru -WindowStyle Hidden; Start-Sleep 30").start()
    HelperProcs.started(gc)
    Thread.sleep(7000)
    val kids = ProcessHandle.of(gc.pid()).map { h -> h.descendants().toList() }.orElse(emptyList())
    println("grandchildren of a lowered child: " + kids.joinToString { "${it.info().command().orElse("?").substringAfterLast('\\')} -> ${WinProbe.processState(it.pid().toInt())}" })
    // the real OCR helper, started the way the app starts it
    val dir = File(System.getProperty("java.io.tmpdir"), "wintune-proof-helper"); dir.mkdirs()
    val ocr = Ocrs.choose(dir)
    Thread.sleep(2500)
    val helpers = ProcessHandle.current().children().toList().filter { it.info().command().orElse("").lowercase().contains("powershell") }
    println("the real OCR helper (${ocr?.name}): " + helpers.joinToString { WinProbe.processState(it.pid().toInt()).toString() })
    println("powershell, from outside: " + WinProbe.powershell("Get-Process -Id " + helpers.joinToString(",") { it.pid().toString() } + " | Select-Object Id,PriorityClass | ConvertTo-Json -Compress"))
    ocr?.close(); fromBg?.destroy(); gc.destroy()
    ProcessHandle.current().descendants().forEach { it.destroy() }
  }
}
