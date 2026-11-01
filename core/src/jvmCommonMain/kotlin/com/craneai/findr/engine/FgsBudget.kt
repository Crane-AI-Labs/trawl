package com.craneai.findr.engine

import com.craneai.findr.core.FileKind

/**
 * The two foreground-service types Trawl runs under. Android 15+ (target 35+) gives each time-limited type its own 6 hours per 24
 * ([FgsBudget.LIMIT_MS]): data sync for words, documents and waiting, media processing for the heavy picture, video and sound work.
 * Never combined in one ForegroundInfo: WorkManager's onTimeout stops a worker only when its type EQUALS the one that timed out.
 */
enum class FgsType(val key: String, val label: String) {
  DATA_SYNC("dataSync", "data sync"),
  MEDIA_PROCESSING("mediaProcessing", "media processing"),
}

/** Which type a piece of work runs under, and which types this Android version has. Pure, so the split is unit-tested. */
object FgsPolicy {
  /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING exists from Android 15 (API 35); before that only data sync does. */
  const val MEDIA_PROCESSING_SDK = 35

  /**
   * [mediaProcessing] is whether this build declares the media processing type at all (the `mediaProcessingFgs` build flag: a Google Play
   * build leaves it out until Play accepts it, and the edition without models never has it). Off, everything runs under data sync.
   */
  fun available(sdk: Int, mediaProcessing: Boolean = true): Set<FgsType> =
    if (mediaProcessing && sdk >= MEDIA_PROCESSING_SDK) setOf(FgsType.DATA_SYNC, FgsType.MEDIA_PROCESSING) else setOf(FgsType.DATA_SYNC)

  /** Understanding pictures, videos and recordings is media processing; documents, the words pass, waiting and the rest stay data sync. */
  fun preferred(kind: FileKind, sdk: Int, mediaProcessing: Boolean = true): FgsType = when {
    !mediaProcessing || sdk < MEDIA_PROCESSING_SDK -> FgsType.DATA_SYNC
    kind == FileKind.IMAGE || kind == FileKind.SCREENSHOT || kind == FileKind.VIDEO || kind == FileKind.AUDIO -> FgsType.MEDIA_PROCESSING
    else -> FgsType.DATA_SYNC
  }
}

/**
 * A local ledger of the foreground-service time Trawl has used in the last 24 hours, per type. Android keeps this meter itself but
 * never tells the app how full it is, and WorkManager swallows the exception a refused type switch throws, so without a ledger the
 * only signal is the timeout (and its crash risk) arriving. With it the worker picks the type that has room, and ends a run on its
 * own terms [MARGIN_MS] before a limit instead of waiting for the system to.
 *
 * The rules follow AOSP's ActiveServices: time is counted per type from the first start of that type, the window is 24 h from then,
 * and bringing the app to the foreground resets it. Pure (the clock is passed in) and persisted as a short string ([encode]/[restore]),
 * so it survives the process dying, which is exactly when a Samsung or OS kill happens.
 */
class FgsBudget(
  private val types: Set<FgsType> = FgsType.entries.toSet(),
  private val limitMs: Long = LIMIT_MS,
  private val marginMs: Long = MARGIN_MS,
) {
  private class Tally { var windowStart = NONE; var usedMs = 0L }

  private val tallies = FgsType.entries.associateWith { Tally() }
  private var open: FgsType? = null
  private var openSince = 0L

  /** The type the foreground service is running under now, if any. */
  val current: FgsType? @Synchronized get() = open

  /** A window that began more than 24 h ago is over: its time is forgotten. */
  private fun roll(t: FgsType, now: Long) {
    val tally = tallies.getValue(t)
    if (tally.windowStart != NONE && now - tally.windowStart >= WINDOW_MS) {
      tally.windowStart = NONE
      tally.usedMs = 0L
      if (open == t) { tally.windowStart = now; openSince = now }   // a service still running starts a fresh window
    }
  }

  /** Adds the time of the open stretch to its type and starts the next stretch from [now]. */
  private fun settle(now: Long) {
    val t = open ?: return
    roll(t, now)
    if (open == null) return
    tallies.getValue(t).usedMs += (now - openSince).coerceAtLeast(0)
    openSince = now
  }

  /** The foreground service now runs under [type] (a new start, or a switch from the other type). */
  @Synchronized fun enter(type: FgsType, now: Long) {
    settle(now)
    roll(type, now)
    if (open != type) { open = type; openSince = now }
    val tally = tallies.getValue(type)
    if (tally.windowStart == NONE) tally.windowStart = now
  }

  /** The foreground service ended (the run finished, or the system stopped it). */
  @Synchronized fun leave(now: Long) {
    settle(now)
    open = null
  }

  /** Milliseconds of [type] left in its window; 0 when spent. */
  @Synchronized fun remainingMs(type: FgsType, now: Long): Long {
    roll(type, now)
    val used = tallies.getValue(type).usedMs + if (open == type) (now - openSince).coerceAtLeast(0) else 0L
    return (limitMs - used).coerceAtLeast(0)
  }

  @Synchronized fun usedMs(type: FgsType, now: Long): Long = limitMs - remainingMs(type, now)

  /** A start or switch to [type] was refused (or its timeout fired): it counts as spent until its window ends or the app is opened. */
  @Synchronized fun markExhausted(type: FgsType, now: Long) {
    settle(now)
    val tally = tallies.getValue(type)
    if (tally.windowStart == NONE) tally.windowStart = now
    tally.usedMs = limitMs
  }

  /** Android restarts both meters when the app comes to the foreground; a service running at that moment starts counting again. */
  @Synchronized fun uiForeground(now: Long) {
    for ((t, tally) in tallies) {
      tally.usedMs = 0
      tally.windowStart = if (open == t) now else NONE
    }
    if (open != null) openSince = now
  }

  /**
   * The type to run under: [preferred] if it has more than [marginMs] left, else the other available type if that has, else null
   * (no foreground service can safely be kept: the run should end in an orderly way, and a later one goes on as plain work).
   */
  @Synchronized fun choose(preferred: FgsType, now: Long): FgsType? {
    val order = listOf(preferred) + FgsType.entries.filter { it != preferred }
    return order.firstOrNull { it in types && remainingMs(it, now) > marginMs }
  }

  /** One line per type for the report: used and left in the current window. */
  @Synchronized fun describe(now: Long): String =
    FgsType.entries.filter { it in types }.joinToString("; ") { "${it.label} used ${fmt(usedMs(it, now))} of ${fmt(limitMs)}" }

  /** A short text of the whole ledger, with the open stretch counted up to [now] (the last heartbeat), to keep across a process death. */
  @Synchronized fun encode(now: Long): String {
    settle(now)
    return FgsType.entries.joinToString(";") { t -> tallies.getValue(t).let { "${t.key}=${it.windowStart},${it.usedMs}" } } +
      (open?.let { ";open=${it.key},$now" } ?: "")
  }

  /** Loads [text] (from [encode]). A stretch that was open when the process died counts up to its last heartbeat. Bad text leaves the ledger empty. */
  @Synchronized fun restore(text: String?, now: Long) {
    open = null
    tallies.values.forEach { it.windowStart = NONE; it.usedMs = 0 }
    if (text.isNullOrBlank()) return
    try {
      for (part in text.split(';')) {
        val (k, v) = part.split('=', limit = 2)
        if (k == "open") continue   // the stretch open at the last save is already counted in the totals
        val nums = v.split(',').map { it.toLong() }
        val t = FgsType.entries.first { it.key == k }
        tallies.getValue(t).apply { windowStart = nums[0]; usedMs = nums[1].coerceIn(0, limitMs) }
      }
    } catch (_: Exception) {
      tallies.values.forEach { it.windowStart = NONE; it.usedMs = 0 }
    }
    FgsType.entries.forEach { roll(it, now) }
  }

  companion object {
    /** The ledger for a phone running [sdk]: the 6-hour limits exist from Android 15; before that nothing is limited, so nothing ever runs out. */
    fun forSdk(sdk: Int, mediaProcessing: Boolean = true): FgsBudget =
      FgsBudget(FgsPolicy.available(sdk, mediaProcessing), limitMs = if (sdk >= FgsPolicy.MEDIA_PROCESSING_SDK) LIMIT_MS else UNLIMITED_MS)

    private const val NONE = -1L

    /** "No limit" that cannot overflow when added to. */
    const val UNLIMITED_MS = Long.MAX_VALUE / 4

    /** Android 15's limit for each of data sync and media processing. */
    const val LIMIT_MS = 6 * 60 * 60_000L

    /** How long a window lasts (from the first start of the type). */
    const val WINDOW_MS = 24 * 60 * 60_000L

    /**
     * A type with this much or less left is not used for the next file. Longer than the longest per-file timeout (10 minutes), so a
     * file started with room never runs into the limit; the system's own timeout stays as the backstop (the -128 stop and rerun).
     */
    const val MARGIN_MS = 12 * 60_000L

    private fun fmt(ms: Long): String = if (ms >= UNLIMITED_MS) "no limit" else "%d h %02d min".format(ms / 3_600_000, ms / 60_000 % 60)
  }
}
