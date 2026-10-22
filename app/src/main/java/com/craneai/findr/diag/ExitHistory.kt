package com.craneai.findr.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.craneai.findr.core.FindrLog as Log

/** Reads the system's record of how earlier Findr processes ended (the Android half of [ExitEvents], which turns a record into an event). */
object ExitHistory {
  /** The system's list of earlier exits of this app, newest first, as plain records. Empty on any failure. */
  fun read(context: Context, maxRecords: Int = 16): List<ExitRecord> = try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    am.getHistoricalProcessExitReasons(context.packageName, 0, maxRecords).map { info ->
      ExitRecord(
        timestamp = info.timestamp,
        pid = info.pid,
        reason = info.reason,
        status = info.status,
        importance = info.importance,
        description = info.description,
        trace = if (info.reason == ApplicationExitInfo.REASON_ANR) readTrace(info) else null,
      )
    }
  } catch (t: Throwable) {
    Log.w("FindrDiag", "could not read the exit history (${Log.brief(t)})")
    emptyList()
  }

  private fun readTrace(info: ApplicationExitInfo): String? = try {
    info.traceInputStream?.use { input ->
      // InputStream.readNBytes needs API 33; Findr's minimum is 30.
      val buf = java.io.ByteArrayOutputStream()
      val chunk = ByteArray(8192)
      while (buf.size() < MAX_TRACE_BYTES) {
        val n = input.read(chunk)
        if (n < 0) break
        buf.write(chunk, 0, n)
      }
      buf.toString(Charsets.UTF_8.name())
    }
  } catch (_: Throwable) {
    null
  }

  private const val MAX_TRACE_BYTES = 512 * 1024
}
