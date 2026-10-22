package com.craneai.findr.diag

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The last few dozen lines Findr logged, kept in memory for a problem report. It is fed by `FindrLog.tap`, which only
 * runs when detail logging is off, so every line is already redacted (names, paths, queries and messages print as
 * `<private>`), and [Redaction.logLine] scrubs it once more on the way out. Lost with the process: a crash report
 * takes a copy of it first ([CrashHandler]).
 */
class LogRing(private val capacity: Int = 150, private val clock: () -> Long = System::currentTimeMillis) {
  private val lines = ArrayDeque<String>(capacity)

  fun add(level: Char, tag: String, text: String) {
    // A message with a stack trace is several lines: keep the first few, so one failure does not push out the history.
    val parts = text.lineSequence().take(MAX_LINES_EACH).toList()
    val stamp = TIME.format(Instant.ofEpochMilli(clock()))
    synchronized(this) {
      parts.forEachIndexed { i, p ->
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(if (i == 0) "$stamp $level/$tag: $p" else "    $p")
      }
    }
  }

  /** The newest [n] lines, oldest first, scrubbed and clipped. */
  fun tail(n: Int = 40): List<String> = synchronized(this) { lines.toList() }.takeLast(n).map { Redaction.logLine(it) }

  fun clear() = synchronized(this) { lines.clear() }

  private companion object {
    const val MAX_LINES_EACH = 4
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
  }
}
