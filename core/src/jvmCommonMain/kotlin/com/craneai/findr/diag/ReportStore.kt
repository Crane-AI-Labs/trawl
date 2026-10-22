package com.craneai.findr.diag

import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** What happened. [rank] decides what the store keeps when it is full; [offersCard] is what asks the person to send a report. */
enum class EventKind(val label: String, val rank: Int, val offersCard: Boolean) {
  CRASH("crash (uncaught exception)", 3, true),
  NATIVE_CRASH("native crash", 3, true),
  ANR("not responding (ANR)", 3, true),
  STARTUP_FAILURE("failed to start", 3, true),
  LOW_MEMORY("killed for memory while in use", 1, false),
  OTHER("other process exit", 0, false),
}

/** One event worth reporting. [body] is the finished, redacted text; [time] is wall-clock milliseconds. */
data class StoredEvent(val time: Long, val kind: EventKind, val pid: Int, val body: String)

/** Errors Findr handled itself and carried on from. Only counts and the last exception class are kept. */
enum class HandledKind(val label: String, val immediate: Boolean) {
  MODEL_INSTALL("model install", true),
  PACK_INSTALL("add-on install", true),
  INDEX_CORRUPT("index unusable (rebuilt)", true),
  INDEX_REPAIRED("search index repaired (nothing lost)", false),
  OCR("text in pictures (OCR)", false),
  DECODER("picture, video, audio or PDF decoder", false),
  SPEECH("speech to words", false),
  FILE_FAILED("a file that could not be understood", false),
  WORKER("background reading", false),
  ENGINE_REPAIRED("search model cache was corrupt (cleared, fine now)", false),
  ENGINE_UNHEALTHY("search model failed its self-test (words-only search)", true),
}

data class HandledStat(val count: Int, val lastClass: String, val lastAt: Long)

/**
 * The problem reports Findr keeps on the phone, in app-private no_backup storage (never in a backup), as a folder:
 *  - `e-<time>-<pid>-<KIND>.txt`: one event each, at most [maxReports] of them and [maxBytes] in all;
 *  - `seen`: the exit timestamps already read from the system, so the same exit is never stored twice, even after its
 *    report was deleted (timestamps only);
 *  - `ack`: the time of the latest event the person has answered the card for;
 *  - `handled.json`: counts of handled errors.
 * Plain files, so the JVM tests run it. Safe to call from several threads.
 */
class ReportStore(
  private val dir: File,
  private val maxReports: Int = MAX_REPORTS,
  private val maxBytes: Int = MAX_BYTES,
  private val maxEventChars: Int = MAX_EVENT_CHARS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()

  // ---- events ---------------------------------------------------------------------------------------------------

  /** Stores [e] (cut to [maxEventChars]) and makes room by dropping the least important, then the oldest, events. */
  fun add(e: StoredEvent) = synchronized(lock) {
    dir.mkdirs()
    val body = if (e.body.length > maxEventChars) e.body.take(maxEventChars) + "\n[cut]\n" else e.body
    write(file(e), body)
    enforceLimits()
  }

  /** Newest first. */
  fun events(): List<StoredEvent> = synchronized(lock) {
    list().mapNotNull { (f, e) -> runCatching { e.copy(body = f.readText()) }.getOrNull() }.sortedByDescending { it.time }
  }

  /**
   * Reads the system's record of how earlier processes ended and stores the ones worth reporting. A timestamp seen
   * before is skipped (even if its event was deleted since), and an uncaught-exception crash that [CrashHandler]
   * already wrote a full report for (same process, within a minute) is not stored a second time. Returns what was added.
   */
  fun ingest(records: List<ExitRecord>): List<StoredEvent> = synchronized(lock) {
    val seen = loadSeen().toMutableSet()
    val added = ArrayList<StoredEvent>()
    for (r in records.sortedBy { it.timestamp }) {
      if (!seen.add(r.timestamp)) continue
      val own = events().any { it.kind == EventKind.CRASH && it.pid == r.pid && abs(it.time - r.timestamp) < 60_000 }
      if (own && r.reason == ExitRecord.REASON_CRASH) continue
      ExitEvents.toEvent(r)?.let { add(it); added += it }
    }
    saveSeen(seen)
    added
  }

  /** Events the card should ask about: crashes, ANRs and start-up failures after the last answer. */
  fun pending(): List<StoredEvent> = synchronized(lock) {
    val ack = ackTime()
    events().filter { it.kind.offersCard && it.time > ack }
  }

  /** The person answered the card (Send, or Not now): it does not come back for these events. */
  fun acknowledge() = synchronized(lock) {
    val newest = events().maxOfOrNull { it.time } ?: return@synchronized
    dir.mkdirs()
    write(File(dir, ACK), newest.toString())
  }

  // ---- handled errors ----------------------------------------------------------------------------------------------

  private var handled: MutableMap<HandledKind, HandledStat>? = null
  private var handledFlushedAt = 0L
  private var handledDirty = false

  /**
   * Counts a handled error: the kind and the exception's class, never its message. Install and index failures reach
   * the disk at once; a per-file error (thousands in one run) at most every [FLUSH_MS].
   */
  fun recordHandled(kind: HandledKind, t: Throwable) = synchronized(lock) {
    val map = handledMap()
    val before = map[kind]
    map[kind] = HandledStat((before?.count ?: 0) + 1, t.javaClass.name, clock())
    handledDirty = true
    if (kind.immediate || clock() - handledFlushedAt >= FLUSH_MS) flushHandled()
  }

  /** Writes counts not yet on disk. Called when the process is dying and when a report is built. */
  fun flushHandled() = synchronized(lock) {
    if (!handledDirty) return@synchronized
    val o = JSONObject()
    handledMap().forEach { (k, s) -> o.put(k.name, JSONObject().put("n", s.count).put("last", s.lastClass).put("at", s.lastAt)) }
    dir.mkdirs()
    write(File(dir, HANDLED), o.toString())
    handledFlushedAt = clock()
    handledDirty = false
  }

  fun handledCounts(): Map<HandledKind, HandledStat> = synchronized(lock) { handledMap().toMap() }

  private fun handledMap(): MutableMap<HandledKind, HandledStat> = handled ?: run {
    val m = LinkedHashMap<HandledKind, HandledStat>()
    runCatching {
      val o = JSONObject(File(dir, HANDLED).readText())
      for (k in HandledKind.entries) o.optJSONObject(k.name)?.let { m[k] = HandledStat(it.optInt("n"), it.optString("last"), it.optLong("at")) }
    }
    m
  }.also { handled = it }

  // ---- forgetting ------------------------------------------------------------------------------------------------------

  /**
   * "Delete everything Findr knows": every report, the counts and the answer to the card. Only the list of exit
   * timestamps stays, so the system's older exit records are not read in again as if new; it holds numbers, no text.
   */
  fun wipe() = synchronized(lock) {
    val seenNow = loadSeen()
    dir.listFiles()?.forEach { it.delete() }
    handled = null
    handledDirty = false
    if (seenNow.isNotEmpty()) { dir.mkdirs(); saveSeen(seenNow) }
  }

  // ---- files --------------------------------------------------------------------------------------------------------

  private fun file(e: StoredEvent) = File(dir, "e-${e.time}-${e.pid}-${e.kind.name}.txt")

  private fun list(): List<Pair<File, StoredEvent>> = dir.listFiles().orEmpty().mapNotNull { f ->
    val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
    val kind = EventKind.entries.firstOrNull { it.name == m.groupValues[3] } ?: return@mapNotNull null
    f to StoredEvent(m.groupValues[1].toLong(), kind, m.groupValues[2].toInt(), "")
  }

  /** Drops the least important, then the oldest, until the count and the total size fit. */
  private fun enforceLimits() {
    while (true) {
      val all = list()
      val total = all.sumOf { it.first.length() }
      if (all.size <= maxReports && total <= maxBytes) return
      val victim = all.minWithOrNull(compareBy<Pair<File, StoredEvent>>({ it.second.kind.rank }, { it.second.time })) ?: return
      if (!victim.first.delete()) return
    }
  }

  private fun ackTime(): Long = runCatching { File(dir, ACK).readText().trim().toLong() }.getOrDefault(0L)

  private fun loadSeen(): Set<Long> = runCatching { File(dir, SEEN).readLines().mapNotNull { it.trim().toLongOrNull() }.toSet() }.getOrDefault(emptySet())

  private fun saveSeen(all: Set<Long>) {
    dir.mkdirs()
    write(File(dir, SEEN), all.sorted().takeLast(SEEN_MAX).joinToString("\n"))
  }

  /** Temporary name, then rename: a process killed mid-write never leaves half a report that reads as whole. */
  private fun write(target: File, text: String) {
    val tmp = File(target.path + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
  }

  companion object {
    const val MAX_REPORTS = 10
    const val MAX_BYTES = 100_000
    const val MAX_EVENT_CHARS = 10_000
    const val SEEN_MAX = 64
    const val FLUSH_MS = 15_000L
    private const val ACK = "ack"
    private const val SEEN = "seen"
    private const val HANDLED = "handled.json"
    private val NAME = Regex("""e-(\d+)-(-?\d+)-([A-Z_]+)\.txt""")
  }
}
