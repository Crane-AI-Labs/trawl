package com.craneai.findr.core

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Findr's only way to write to logcat (the research notes, "Logs").
 *
 * Logcat is not private. System apps with READ_LOGS can read it, it goes into every bug report (`adb bugreport`, an
 * OEM's "send diagnostics"), and anyone holding a phone with USB debugging on can read it too. So a release build logs
 * counts, timings and states, and never anything about what is in the user's files: file names, folders, paths, URIs,
 * queries, extracted text, transcripts, OCR text, or exception messages (which often carry a path). Each of those goes
 * through [redact], which prints [PRIVATE] unless the build has detail logging. Apple's unified logging does the same
 * with dynamic strings.
 *
 * Detail logging ([detail]) is on in debug builds, and in a release build made with `-PfindrLogDetail=true`. The
 * smoke scripts use that flag on test phones because they read file names from the log. Never ship a build made with it.
 *
 * `LogHygieneTest` fails the unit tests when main code calls `android.util.Log` directly, or puts a name, path, query,
 * text or exception message into a log line without [redact].
 */
object FindrLog {
  /** What a private value prints as when detail logging is off. */
  const val PRIVATE = "<private>"

  /** Detail logging: true in debug builds and in `-PfindrLogDetail=true` release builds (test phones only). */
  var detail: Boolean
    get() = detailOverride ?: BuildFlags.logDetail
    set(value) { detailOverride = value }   // for tests

  @Volatile private var detailOverride: Boolean? = null

  /** A private value (file name, folder, path, URI, query, text, exception message): itself with detail on, else [PRIVATE]. */
  fun redact(value: Any?): String = if (detail) value.toString() else PRIVATE

  /**
   * Where the last lines of the log are kept for a problem report (diag/LogRing). Fed only when detail logging is off,
   * so the lines are exactly the redacted ones a release build writes to logcat, and a detail build never puts a file
   * name into a report. Level, tag and the line as logged.
   */
  @Volatile
  var tap: ((level: Char, tag: String, line: String) -> Unit)? = null

  fun i(tag: String, msg: String) {
    platformLog('I', tag, msg, null)
    if (!detail) tap?.invoke('I', tag, msg)
  }

  fun w(tag: String, msg: String, t: Throwable? = null) {
    if (t == null) platformLog('W', tag, msg, null) else if (detail) platformLog('W', tag, msg, t) else platformLog('W', tag, "$msg\n${trace(t)}", null)
    if (!detail) tap?.invoke('W', tag, if (t == null) msg else "$msg\n${trace(t, 4)}")
  }

  fun e(tag: String, msg: String, t: Throwable? = null) {
    if (t == null) platformLog('E', tag, msg, null) else if (detail) platformLog('E', tag, msg, t) else platformLog('E', tag, "$msg\n${trace(t)}", null)
    if (!detail) tap?.invoke('E', tag, if (t == null) msg else "$msg\n${trace(t, 4)}")
  }

  /** Developer detail: built and written only when detail logging is on, so it may hold anything. */
  inline fun d(tag: String, msg: () -> String) {
    if (detail) platformLog('D', tag, msg(), null)
  }

  /**
   * A throwable as a release build may log it: the class and stack frames of each exception in the cause chain, with
   * every message left out. Frames name code, never data, so a crash stays diagnosable without any user data.
   */
  fun trace(t: Throwable, framesEach: Int = 12): String = buildString {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cur: Throwable? = t
    var first = true
    while (cur != null && seen.add(cur) && seen.size <= 6) {
      if (!first) append("\nCaused by: ")
      append(cur.javaClass.name)
      if (cur.message != null) append(": ").append(PRIVATE)
      val frames = cur.stackTrace
      for (f in frames.take(framesEach)) append("\n\tat ").append(f.toString())
      if (frames.size > framesEach) append("\n\t... ").append(frames.size - framesEach).append(" more")
      first = false
      cur = cur.cause
    }
  }

  /** "IOException" with detail off; "IOException: /storage/…/x.pdf (No such file)" with detail on. For one-line notes. */
  fun brief(t: Throwable): String = t.javaClass.simpleName + (t.message?.let { ": " + redact(it) } ?: "")
}

/**
 * Where a log line goes. [level] is I, W, E or D; [t] is set only when detail logging is on and the line may carry the whole exception.
 * The phone writes to logcat (`FindrLog.android.kt` in :app) and the desktop to its own sink; with none set, nothing is written.
 */
fun interface LogSink {
  fun log(level: Char, tag: String, msg: String, t: Throwable?)
}

/** The host's sink, set once at start-up by `FindrApp` (phone) or the desktop's `main`. */
object LogSinks {
  @Volatile var sink: LogSink? = null
}

@PublishedApi
internal fun platformLog(level: Char, tag: String, msg: String, t: Throwable?) {
  LogSinks.sink?.log(level, tag, msg, t)
}
