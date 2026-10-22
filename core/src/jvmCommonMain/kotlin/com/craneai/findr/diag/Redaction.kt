package com.craneai.findr.diag

import com.craneai.findr.core.FindrLog

/**
 * What may go into a problem report (the research notes D13 to D16: nothing about the person's files leaves
 * the phone unread). A report holds code names, counts and the phone's own facts. It never holds a file name, a path, a
 * query, OCR text or a transcript, so everything that came from outside Findr's code passes through here first.
 *
 *  - An exception: its class and stack frames name code. Its message often carries a path or a query, so
 *    [FindrLog.trace] prints `<private>` in its place. That stays true in a detail-logging build: a report is read by
 *    other people, so it is stricter than logcat.
 *  - A log line: already redacted by [FindrLog] when it was written, and then scrubbed again for paths and URIs.
 *  - The system's own words (an exit description, an ANR thread dump): reduced to what names code.
 */
object Redaction {
  const val PATH = "<path>"
  const val URI = "<uri>"

  /** Frames kept per exception in a cause chain. */
  const val FRAMES = 25

  /** The stack trace of [t]: class names and frames of the whole cause chain, every message replaced by `<private>`. */
  fun trace(t: Throwable): String = scrub(FindrLog.trace(t, FRAMES))

  /**
   * Removes anything shaped like a path or a URI from one line: `/storage/emulated/0/DCIM/a.jpg`, `content://...`,
   * `file:///...`, a Windows-style `C:\...`. A stack frame such as `at a.b.C.d(C.kt:12)` has no slash and is untouched.
   */
  fun scrub(text: String): String = text
    .replace(Regex("""[A-Za-z][A-Za-z0-9+.\-]*://\S+"""), URI)
    .replace(Regex("""(?<![\w<])(?:/[^\s/:;,'"()<>\[\]]+){2,}/?"""), PATH)
    .replace(Regex("""[A-Za-z]:\\\S+"""), PATH)

  /**
   * One line of the app's log for a report: scrubbed and clipped. Lines come from [FindrLog], which has already
   * replaced names, queries and messages with `<private>`.
   */
  fun logLine(line: String, max: Int = 180): String = scrub(line).take(max)

  /**
   * The system's description of a process exit ("Native crash", "user request", "input dispatching timed out (...
   * is not responding. Waited 5001ms for MotionEvent)"). Kept short, with paths and URIs removed and quoted text cut,
   * because a component or window title in it could name something the person typed.
   */
  fun exitDescription(description: String?): String? {
    val d = description?.trim().orEmpty()
    if (d.isEmpty()) return null
    return scrub(d).replace(Regex("""'[^']*'|"[^"]*""""), "<text>").take(160)
  }

  /**
   * The main thread of an ANR trace (the system's thread dump, text), reduced to what shows why it was stuck: the thread
   * state line and its Java and native frames. Native frames lose their library path (`libc.so (syscall+28)`). Waiting
   * and locking lines, other threads, registers, memory maps and anything else in the dump are left out.
   */
  fun anrMainThread(dump: String, maxLines: Int = 45): String? {
    val lines = dump.lineSequence().toList()
    val start = lines.indexOfFirst { it.startsWith("\"main\"") }
    if (start < 0) return null
    val out = ArrayList<String>()
    out += stateLine(lines[start])
    for (raw in lines.drop(start + 1)) {
      if (raw.isBlank() || raw.startsWith("\"")) break   // the next thread
      val line = raw.trim()
      when {
        line.startsWith("at ") -> out += "  " + scrub(line).take(200)
        line.startsWith("native: #") -> out += "  " + nativeFrame(line)
        else -> Unit
      }
      if (out.size >= maxLines) break
    }
    return if (out.size > 1) out.joinToString("\n") else out.joinToString("\n") + "\n  (no frames in the dump)"
  }

  /** `"main" prio=5 tid=1 Blocked` -> `main thread: Blocked`. */
  private fun stateLine(line: String): String {
    val state = Regex("""tid=\d+\s+(\w+)""").find(line)?.groupValues?.get(1) ?: "state unknown"
    return "main thread: $state"
  }

  /** `native: #02 pc 0000000000087a3c  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28) (BuildId: x)` -> `libc.so (syscall+28)`. */
  private fun nativeFrame(line: String): String {
    val m = Regex("""#(\d+)\s+pc\s+[0-9a-fA-F]+\s+(\S+)(?:\s+\(([^)]*)\))?""").find(line) ?: return "native frame"
    val lib = m.groupValues[2].substringAfterLast('/')
    val sym = m.groupValues[3].takeIf { it.isNotBlank() && !it.startsWith("BuildId") }?.let { " ($it)" }.orEmpty()
    return "#${m.groupValues[1]} $lib$sym"
  }
}
