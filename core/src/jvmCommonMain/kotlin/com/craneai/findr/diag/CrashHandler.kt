package com.craneai.findr.diag

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android's standard way to see a crash: a default uncaught-exception handler. It writes one redacted report to
 * app-private storage and then hands the exception to the handler that was there before, so the system still shows its
 * crash dialog and ends the process exactly as it would without us. Whatever goes wrong while writing is swallowed:
 * a failure to report must never change how the app fails.
 */
class CrashHandler(
  private val store: ReportStore,
  private val log: LogRing,
  private val previous: Thread.UncaughtExceptionHandler?,
  private val pid: Int,
  private val clock: () -> Long = System::currentTimeMillis,
) : Thread.UncaughtExceptionHandler {
  private val writing = AtomicBoolean(false)

  override fun uncaughtException(thread: Thread, error: Throwable) {
    if (writing.compareAndSet(false, true)) {
      try {
        store.flushHandled()
        store.add(eventFor(thread, error))
      } catch (_: Throwable) {
        // Out of memory, disk full, a broken store: the crash goes on as it would have.
      } finally {
        writing.set(false)
      }
    }
    previous?.uncaughtException(thread, error)
  }

  /** The event for one crash: what the stack says, and the last lines logged before it. */
  fun eventFor(thread: Thread, error: Throwable): StoredEvent {
    val now = clock()
    val body = buildString {
      appendLine("Event: ${EventKind.CRASH.label}")
      appendLine("Time: ${Instant.ofEpochMilli(now)}")
      appendLine("Process: pid $pid")
      appendLine("Thread: ${Redaction.logLine(thread.name, 60)}")
      appendLine(Redaction.trace(error).take(MAX_TRACE_CHARS))
      val tail = log.tail(LOG_LINES)
      if (tail.isNotEmpty()) {
        appendLine()
        appendLine("Log before the crash (redacted):")
        tail.forEach { appendLine(it) }
      }
    }
    return StoredEvent(now, EventKind.CRASH, pid, body)
  }

  companion object {
    const val MAX_TRACE_CHARS = 6_000
    const val LOG_LINES = 25

    /** Puts the handler in front of whatever is installed now. */
    fun install(store: ReportStore, log: LogRing, pid: Int): CrashHandler =
      CrashHandler(store, log, Thread.getDefaultUncaughtExceptionHandler(), pid).also { Thread.setDefaultUncaughtExceptionHandler(it) }
  }
}
