package com.craneai.findr.diag

import java.time.Instant

/**
 * How an earlier Findr process ended, as the system recorded it (ApplicationExitInfo, Android 11+), reduced to plain
 * data so the rules below run on the JVM. [trace] is the system's text thread dump for an ANR, when it kept one.
 */
data class ExitRecord(
  val timestamp: Long,
  val pid: Int,
  val reason: Int,
  val status: Int,
  val importance: Int,
  val description: String?,
  val trace: String?,
) {
  companion object {
    // ApplicationExitInfo.REASON_* values, written out so this file has no Android dependency; ExitConstantsTest (in :app) checks them against the platform's.
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_LOW_MEMORY = 3
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
  }
}

/** Turns a system exit record into a stored, redacted event, or nothing when it is not worth reporting. */
object ExitEvents {
  /** A kill for memory is normal for a process nobody is looking at; only one that was visible or doing work is a finding. */
  private val IMPORTANT_ENOUGH: Int get() = IMPORTANCE_SERVICE

  fun toEvent(r: ExitRecord): StoredEvent? {
    val kind = when (r.reason) {
      ExitRecord.REASON_CRASH -> EventKind.CRASH
      ExitRecord.REASON_CRASH_NATIVE -> EventKind.NATIVE_CRASH
      ExitRecord.REASON_ANR -> EventKind.ANR
      ExitRecord.REASON_INITIALIZATION_FAILURE -> EventKind.STARTUP_FAILURE
      ExitRecord.REASON_LOW_MEMORY -> if (r.importance <= IMPORTANT_ENOUGH) EventKind.LOW_MEMORY else return null
      ExitRecord.REASON_EXCESSIVE_RESOURCE_USAGE -> EventKind.OTHER
      else -> return null   // the person closed it, the system updated it, a clean exit: nothing went wrong
    }
    val body = buildString {
      appendLine("Event: ${kind.label}")
      appendLine("Time: ${Instant.ofEpochMilli(r.timestamp)}")
      appendLine("Process: pid ${r.pid}, exit status ${r.status}, was ${importanceLabel(r.importance)}")
      Redaction.exitDescription(r.description)?.let { appendLine("System note: $it") }
      when (kind) {
        EventKind.ANR -> {
          val main = r.trace?.let { Redaction.anrMainThread(it) }
          if (main != null) { appendLine("What the main thread was doing:"); appendLine(main) } else appendLine("No thread dump was kept for this ANR.")
        }
        EventKind.CRASH -> appendLine("No stack trace was saved for this crash (the process ended before Findr could write one).")
        EventKind.NATIVE_CRASH -> appendLine("A crash in native code. The system's crash dump is binary and is not included.")
        else -> Unit
      }
    }
    return StoredEvent(r.timestamp, kind, r.pid, body)
  }

  fun importanceLabel(importance: Int): String = when {
    importance <= IMPORTANCE_FOREGROUND -> "in the foreground"
    importance <= IMPORTANCE_FOREGROUND_SERVICE + 5 -> "running a foreground service"
    importance <= IMPORTANCE_VISIBLE -> "visible"
    importance <= IMPORTANCE_SERVICE -> "running a service"
    importance <= IMPORTANCE_CACHED -> "in the background"
    else -> "importance $importance"
  }

  // ActivityManager.RunningAppProcessInfo.IMPORTANCE_* values (checked against the platform's by ExitConstantsTest in :app).
  const val IMPORTANCE_FOREGROUND = 100
  const val IMPORTANCE_FOREGROUND_SERVICE = 125
  const val IMPORTANCE_VISIBLE = 200
  const val IMPORTANCE_SERVICE = 300
  const val IMPORTANCE_CACHED = 400
}
