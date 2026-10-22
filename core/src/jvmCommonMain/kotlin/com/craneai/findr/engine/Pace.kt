package com.craneai.findr.engine

import com.craneai.findr.core.BuildFlags
import com.craneai.findr.core.FileKind
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStats
import java.util.concurrent.ConcurrentHashMap

/**
 * How fast this phone is actually going, per kind of file: feeds the ETA and the rate on the meter. (Not the
 * worker's rests: per-file times vary with file size, so they can't tell a hot phone from a long document.)
 * Starts from the A06's measured costs (the build notes) and moves toward what this phone really does.
 */
object Pace {
  /** Seconds per file before anything is measured: the Galaxy A06, warm, from M1–M2.5. */
  private val PRIOR get() = if (BuildFlags.go) mapOf(
    // Trawl Go reads words only (a picture's text, a document's text): a few seconds per picture on a 32-bit phone, a moment per document.
    // The first guess before this phone has measured itself; names-only kinds are done at once. Measured on the itel A669W (4x Cortex-A7,
    // median per file): screenshot 2.0 s, photo 0.7 s, document 0.04 s, PDF 0.5 s. Rounded up for real galleries (bigger, busier photos).
    FileKind.SCREENSHOT to 2.5, FileKind.IMAGE to 1.5, FileKind.DOCUMENT to 0.1, FileKind.PDF to 1.0,
    FileKind.VIDEO to 0.0, FileKind.AUDIO to 0.0, FileKind.OTHER to 0.0,
  ) else mapOf(
    FileKind.SCREENSHOT to 5.2, FileKind.IMAGE to 5.2, FileKind.DOCUMENT to 15.0, FileKind.PDF to 25.0,
    FileKind.VIDEO to 20.0, FileKind.AUDIO to 60.0, FileKind.OTHER to 1.0,
  )
  private val average = ConcurrentHashMap<FileKind, Double>()
  private val recent = ArrayDeque<Pair<Long, FileKind>>()   // (time, kind) of files done in the last hour

  @Synchronized fun record(kind: FileKind, seconds: Double) {
    average[kind] = average[kind]?.let { it * 0.9 + seconds * 0.1 } ?: seconds
    val now = System.currentTimeMillis()
    recent.addLast(now to kind)
    while (recent.isNotEmpty() && now - recent.first().first > 3_600_000) recent.removeFirst()
  }

  fun secondsPerFile(kind: FileKind): Double = average[kind] ?: PRIOR.getValue(kind)

  /** Files understood in the last hour, scaled up while the hour isn't over yet. Null until there's data. */
  @Synchronized fun ratePerHour(): Double? {
    if (recent.size < 3) return null
    val span = (System.currentTimeMillis() - recent.first().first).coerceAtLeast(60_000)
    return recent.size * 3_600_000.0 / span
  }


  fun eta(stats: IndexStats, kinds: Set<FileKind>): Long? {
    val seconds = kinds.sumOf { k ->
      val waiting = stats.count(k, FileState.NEW) + stats.count(k, FileState.SEARCHABLE) + stats.count(k, FileState.PARTIAL)
      waiting * secondsPerFile(k)
    }
    return if (seconds <= 0) null else seconds.toLong()
  }
}
