package com.craneai.findr.diag

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Why a background run ended. One per run, recorded by the worker; the first line of any "why did it stop" question. */
enum class RunEnd(val label: String) {
  /** Nothing left to do (or the pass it was asked for is complete). */
  FINISHED("finished"),

  /** Polite mode: the person was in another app, so the run left (the hand-off after the wait, or started while polite). */
  POLITE_HANDOFF("polite hand-off"),

  /**
   * The run found itself with no foreground service and no running job (a worker WorkManager started inside a cached process): Android
   * kills such a process for using the CPU, so the heavy work ended and a protected start picks it up.
   */
  UNPROTECTED("not protected (no foreground service, no running job)"),

  /** Held back by a low battery or no charger: names and words only. */
  HELD_BATTERY("held for battery or charger"),

  /** Held back by heat (a thermal stop). */
  HELD_HEAT("held for heat"),

  /** The system's foreground-service time limit ended it (WorkManager stop reason -128). */
  FGS_TIMEOUT("foreground-service time limit"),

  /** Trawl's own ledger ended it before the limit, because no foreground-service type had room left. */
  FGS_BUDGET_SPENT("foreground-service time spent (ended in an orderly way)"),

  /** The system stopped the run for another reason (quota, standby, constraints, memory: see the stop code). */
  STOPPED_BY_SYSTEM("stopped by the system"),

  /** The app cancelled it (paused, wiped, work replaced). */
  CANCELLED("cancelled by the app"),

  /** The person paused indexing, or all-files access is off. */
  PAUSED("paused or no access"),

  /** The index could not be opened; the run did nothing and asked for another. */
  INDEX_NOT_OPEN("index not open"),

  /** Another run already held the worker lock. */
  BUSY("another run was at it"),

  ENGINE_UNHEALTHY("search model failed its self-test"),
  FAILED("failed"),

  /** The process ended in the middle of the run (a kill, a crash, or the phone restarting); matched to the system's exit record when it has one. */
  PROCESS_DIED("process ended mid-run"),
}

/**
 * The WorkManager stop reasons by name (androidx.work.WorkInfo.STOP_REASON_*), written out so core has no Android dependency; the
 * values are checked against the library's by StopReasonNamesTest in :app. `-128` is the Android 15 foreground-service timeout.
 */
object StopReasons {
  const val NOT_STOPPED = -256
  const val UNKNOWN = -512
  const val FOREGROUND_SERVICE_TIMEOUT = -128
  const val CANCELLED_BY_APP = 1
  const val PREEMPT = 2
  const val TIMEOUT = 3
  const val DEVICE_STATE = 4
  const val CONSTRAINT_BATTERY_NOT_LOW = 5
  const val CONSTRAINT_CHARGING = 6
  const val CONSTRAINT_CONNECTIVITY = 7
  const val CONSTRAINT_DEVICE_IDLE = 8
  const val CONSTRAINT_STORAGE_NOT_LOW = 9
  const val QUOTA = 10
  const val BACKGROUND_RESTRICTION = 11
  const val APP_STANDBY = 12
  const val USER = 13
  const val SYSTEM_PROCESSING = 14
  const val ESTIMATED_APP_LAUNCH_TIME_CHANGED = 15

  fun name(code: Int): String = when (code) {
    NOT_STOPPED -> "NOT_STOPPED"
    UNKNOWN -> "UNKNOWN"
    FOREGROUND_SERVICE_TIMEOUT -> "FOREGROUND_SERVICE_TIMEOUT"
    CANCELLED_BY_APP -> "CANCELLED_BY_APP"
    PREEMPT -> "PREEMPT"
    TIMEOUT -> "TIMEOUT"
    DEVICE_STATE -> "DEVICE_STATE"
    CONSTRAINT_BATTERY_NOT_LOW -> "CONSTRAINT_BATTERY_NOT_LOW"
    CONSTRAINT_CHARGING -> "CONSTRAINT_CHARGING"
    CONSTRAINT_CONNECTIVITY -> "CONSTRAINT_CONNECTIVITY"
    CONSTRAINT_DEVICE_IDLE -> "CONSTRAINT_DEVICE_IDLE"
    CONSTRAINT_STORAGE_NOT_LOW -> "CONSTRAINT_STORAGE_NOT_LOW"
    QUOTA -> "QUOTA"
    BACKGROUND_RESTRICTION -> "BACKGROUND_RESTRICTION"
    APP_STANDBY -> "APP_STANDBY"
    USER -> "USER"
    SYSTEM_PROCESSING -> "SYSTEM_PROCESSING"
    ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "ESTIMATED_APP_LAUNCH_TIME_CHANGED"
    else -> "CODE_$code"
  }

  /** The run ends this way for a stop code: the foreground-service timeout, or any other system stop. */
  fun runEnd(code: Int): RunEnd = when (code) {
    FOREGROUND_SERVICE_TIMEOUT -> RunEnd.FGS_TIMEOUT
    CANCELLED_BY_APP -> RunEnd.CANCELLED
    else -> RunEnd.STOPPED_BY_SYSTEM
  }
}

/** ApplicationExitInfo.REASON_* by name, for the tally of how earlier processes ended (checked against the platform's in :app). */
object ExitNames {
  fun name(reason: Int): String = when (reason) {
    1 -> "exit by itself"
    2 -> "signaled (killed)"
    3 -> "low memory"
    4 -> "crash"
    5 -> "native crash"
    6 -> "ANR"
    7 -> "start-up failure"
    8 -> "permission change"
    9 -> "excessive resource use"
    10 -> "user requested"
    11 -> "user stopped (force stop)"
    12 -> "dependency died"
    13 -> "other"
    14 -> "frozen"
    15 -> "package state change"
    16 -> "package updated"
    else -> "reason $reason"
  }
}

/**
 * One background run, in numbers and times only: no file name, folder or text ever goes in here. [endedAt] is 0 while the run is open;
 * a record still open when the next run starts in another process is a process death ([RunEnd.PROCESS_DIED], dated at [lastBeat]).
 */
data class RunRecord(
  val id: Long,
  val pid: Int,
  /** Which request started it: index, now, bulk, resume, later, next, periodic. */
  val kind: String,
  val startedAt: Long,
  val lastBeat: Long,
  val endedAt: Long = 0,
  val end: RunEnd? = null,
  /** The WorkManager stop reason, when the system stopped the run. */
  val systemCode: Int? = null,
  val charging: Boolean = false,
  val batteryPercent: Int = -1,
  /** Foreground-service milliseconds by type key (dataSync, mediaProcessing). */
  val fgsMs: Map<String, Long> = emptyMap(),
  /** The system refused a foreground service, so the run (or part of it) was plain work. */
  val foregroundDenied: Boolean = false,
  /** Files understood during the run (a count). */
  val files: Int = 0,
  val politeMs: Long = 0,
  /** Times the run waited for the phone to cool, and for how long in all. */
  val coolDowns: Int = 0,
  val coolMs: Long = 0,
  /** For a died run: how the system recorded that process ending. */
  val exitReason: String? = null,
) {
  val open: Boolean get() = end == null
  val durationMs: Long get() = ((if (endedAt > 0) endedAt else lastBeat) - startedAt).coerceAtLeast(0)
}

/**
 * The app's own log of why each background run ended, kept on the phone in the reports folder (so "Delete everything Findr knows"
 * clears it) and read by the problem report. A ring of the last [maxRecords] runs. It exists because the system rarely says why a
 * background run stopped: WorkManager's stop reason is available only inside the dying worker, and a process kill says nothing.
 *
 * A run [begin]s, is kept alive by [beat] about once a minute (so a death can be dated), and [end]s with a reason. Safe to call from
 * several threads, and every call swallows its own failures: telemetry must never become a way for a run to fail.
 */
class RunLog(
  private val file: File,
  private val maxRecords: Int = MAX_RECORDS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private var records: MutableList<RunRecord>? = null
  private var nextId = 1L

  private fun load(): MutableList<RunRecord> = records ?: run {
    val list = ArrayList<RunRecord>()
    runCatching {
      val a = JSONArray(file.readText())
      for (i in 0 until a.length()) list += fromJson(a.getJSONObject(i))
    }
    list.sortBy { it.id }
    nextId = (list.lastOrNull()?.id ?: 0L) + 1
    list
  }.also { records = it }

  /**
   * A run starts. Any record still open from another process is closed as [RunEnd.PROCESS_DIED] first. Returns the run's id (0 if
   * the log could not be written, in which case the other calls ignore it).
   */
  fun begin(kind: String, pid: Int, charging: Boolean, batteryPercent: Int): Long = guarded(0L) {
    synchronized(lock) {
      val list = load()
      closeDead(list, pid)
      val now = clock()
      val rec = RunRecord(nextId++, pid, kind, now, now, charging = charging, batteryPercent = batteryPercent)
      list += rec
      while (list.size > maxRecords) list.removeAt(0)
      save(list)
      rec.id
    }
  }

  /** Changes the open run [id] in memory (counts and times); it reaches the disk with the next [beat] or [end]. */
  fun update(id: Long, change: (RunRecord) -> RunRecord) = guarded(Unit) {
    if (id == 0L) return@guarded
    synchronized(lock) {
      val list = load()
      val i = list.indexOfFirst { it.id == id }
      if (i >= 0 && list[i].open) list[i] = change(list[i])
    }
  }

  /** The run is alive: stamps the time and writes to disk. */
  fun beat(id: Long) = guarded(Unit) {
    if (id == 0L) return@guarded
    synchronized(lock) {
      val list = load()
      val i = list.indexOfFirst { it.id == id }
      if (i >= 0 && list[i].open) { list[i] = list[i].copy(lastBeat = clock()); save(list) }
    }
  }

  /** The run ended, and why. [systemCode] is the WorkManager stop reason when the system stopped it. Ending a run twice keeps the first. */
  fun end(id: Long, end: RunEnd, systemCode: Int? = null) = guarded(Unit) {
    if (id == 0L) return@guarded
    synchronized(lock) {
      val list = load()
      val i = list.indexOfFirst { it.id == id }
      if (i >= 0 && list[i].open) {
        val now = clock()
        list[i] = list[i].copy(end = end, systemCode = systemCode, endedAt = now, lastBeat = now)
        save(list)
      }
    }
  }

  /** Newest first. */
  fun records(): List<RunRecord> = guarded(emptyList()) { synchronized(lock) { load().reversed() } }

  /** Forget everything (and the file). */
  fun wipe() = guarded(Unit) {
    synchronized(lock) {
      records = ArrayList()
      nextId = 1
      file.delete()
    }
  }

  private fun closeDead(list: MutableList<RunRecord>, pid: Int) {
    for (i in list.indices) {
      val r = list[i]
      if (r.open && r.pid != pid) list[i] = r.copy(end = RunEnd.PROCESS_DIED, endedAt = r.lastBeat)
    }
  }

  private fun save(list: List<RunRecord>) {
    file.parentFile?.mkdirs()
    val a = JSONArray()
    list.forEach { a.put(toJson(it)) }
    val tmp = File(file.path + ".tmp")
    tmp.writeText(a.toString())
    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
  }

  private inline fun <T> guarded(fallback: T, block: () -> T): T = try { block() } catch (_: Throwable) { fallback }

  companion object {
    const val MAX_RECORDS = 80
    const val FILE = "runs.json"

    private fun toJson(r: RunRecord): JSONObject = JSONObject()
      .put("id", r.id).put("pid", r.pid).put("kind", r.kind).put("start", r.startedAt).put("beat", r.lastBeat).put("endAt", r.endedAt)
      .put("end", r.end?.name ?: JSONObject.NULL).put("code", r.systemCode ?: JSONObject.NULL)
      .put("charging", r.charging).put("battery", r.batteryPercent)
      .put("fgs", JSONObject().also { o -> r.fgsMs.forEach { (k, v) -> o.put(k, v) } })
      .put("denied", r.foregroundDenied).put("files", r.files).put("polite", r.politeMs)
      .put("cool", r.coolDowns).put("coolMs", r.coolMs).put("exit", r.exitReason ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject): RunRecord {
      val fgs = HashMap<String, Long>()
      // keys() is Iterator<String> on Android but a raw Iterator in the desktop's JSON library (as in SignedBundle): read the names as strings on both.
      o.optJSONObject("fgs")?.let { f -> f.keys().forEach { k -> val key = k.toString(); fgs[key] = f.optLong(key) } }
      return RunRecord(
        id = o.getLong("id"), pid = o.optInt("pid"), kind = o.optString("kind"), startedAt = o.optLong("start"), lastBeat = o.optLong("beat"),
        endedAt = o.optLong("endAt"),
        end = if (o.isNull("end")) null else RunEnd.entries.firstOrNull { it.name == o.optString("end") },
        systemCode = if (o.isNull("code")) null else o.optInt("code"),
        charging = o.optBoolean("charging"), batteryPercent = o.optInt("battery", -1), fgsMs = fgs,
        foregroundDenied = o.optBoolean("denied"), files = o.optInt("files"), politeMs = o.optLong("polite"),
        coolDowns = o.optInt("cool"), coolMs = o.optLong("coolMs"),
        exitReason = if (o.isNull("exit")) null else o.optString("exit"),
      )
    }
  }
}

/**
 * The "Background runs" block of the problem report, and the same text for the app's log: counts and times only. Pure: runs and the
 * system's exit records in, lines out.
 */
object BackgroundReport {
  private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC)
  const val RECENT_SHOWN = 12
  const val DAY_MS = 24 * 60 * 60_000L

  /** Marks died runs with how the system recorded that process ending (matched by process id), without changing what is stored. */
  fun withExits(runs: List<RunRecord>, exits: List<ExitRecord>): List<RunRecord> {
    val byPid = exits.groupBy { it.pid }
    return runs.map { r ->
      if (r.end != RunEnd.PROCESS_DIED || r.exitReason != null) return@map r
      // The exit nearest the run's last sign of life, from that same process.
      val e = byPid[r.pid]?.minByOrNull { kotlin.math.abs(it.timestamp - r.lastBeat) } ?: return@map r
      r.copy(exitReason = ExitNames.name(e.reason))
    }
  }

  /** One line for a finished or open run. */
  fun line(r: RunRecord): String = buildString {
    append(STAMP.format(Instant.ofEpochMilli(r.startedAt))).append(' ').append(r.kind).append(", ").append(fmt(r.durationMs))
    append(if (r.charging) ", charging" else ", on battery").append(" ").append(if (r.batteryPercent >= 0) "${r.batteryPercent}%" else "")
    append(": ").append(r.end?.label ?: "still running or ended without a record")
    r.systemCode?.let { append(" (").append(StopReasons.name(it)).append(", code ").append(it).append(')') }
    r.exitReason?.let { append(" (the system recorded: ").append(it).append(')') }
    if (r.fgsMs.isNotEmpty()) append("; foreground ").append(r.fgsMs.entries.sortedBy { it.key }.joinToString(" ") { "${it.key} ${fmt(it.value)}" })
    if (r.foregroundDenied) append("; no foreground service")
    append("; ").append(r.files).append(" files")
    if (r.politeMs > 0) append("; polite wait ").append(fmt(r.politeMs))
    if (r.coolDowns > 0) append("; cooled ").append(r.coolDowns).append("x ").append(fmt(r.coolMs))
  }

  fun lines(runs: List<RunRecord>, exits: List<ExitRecord>, now: Long): List<String> {
    val all = withExits(runs, exits)
    val out = ArrayList<String>()
    if (all.isEmpty()) out += "No background runs recorded yet."
    else {
      out += "Runs recorded: ${all.size}, newest ${STAMP.format(Instant.ofEpochMilli(all.first().startedAt))} UTC, oldest ${STAMP.format(Instant.ofEpochMilli(all.last().startedAt))} UTC"
      out += "Why runs ended: " + all.groupingBy { it.end?.label ?: "open" }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
      val codes = all.mapNotNull { it.systemCode }.groupingBy { StopReasons.name(it) }.eachCount()
      if (codes.isNotEmpty()) out += "System stop codes: " + codes.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
      val recent = all.filter { now - it.startedAt <= DAY_MS }
      val fgs = FGS_KEYS.associateWith { k -> recent.sumOf { it.fgsMs[k] ?: 0L } }
      out += "Foreground-service time in runs started in the last 24 h: " + fgs.entries.joinToString(", ") { "${it.key} ${fmt(it.value)}" } +
        " (Android 15+ allows ${fmt(6 * 60 * 60_000L)} of each per 24 h)"
      out += "Runs by charger: ${all.count { it.charging }} charging, ${all.count { !it.charging }} on battery; " +
        "total work time ${fmt(all.sumOf { it.durationMs })}, files understood ${all.sumOf { it.files }}"
      out += "Newest ${minOf(RECENT_SHOWN, all.size)} runs (UTC, newest first):"
      all.take(RECENT_SHOWN).forEach { out += "  " + line(it) }
    }
    if (exits.isNotEmpty()) {
      out += "How earlier Trawl processes ended, as the system recorded (${exits.size} records):"
      exits.groupBy { it.reason }.entries.sortedByDescending { e -> e.value.maxOf { it.timestamp } }.forEach { (reason, list) ->
        val newest = list.maxBy { it.timestamp }
        val why = Redaction.exitDescription(newest.description)?.let { ", note: $it" } ?: ""
        out += "  ${ExitNames.name(reason)} ${list.size}, newest ${STAMP.format(Instant.ofEpochMilli(newest.timestamp))} UTC, was ${ExitEvents.importanceLabel(newest.importance)}$why"
      }
    }
    return out
  }

  private val FGS_KEYS = listOf("dataSync", "mediaProcessing")

  fun fmt(ms: Long): String = when {
    ms >= 3_600_000 -> "%d h %02d min".format(ms / 3_600_000, ms / 60_000 % 60)
    ms >= 60_000 -> "%d min".format(ms / 60_000)
    else -> "%d s".format(ms / 1000)
  }
}
