package com.craneai.findr.diag

import android.os.Process
import android.os.SystemClock
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Test builds only ([BuildConfig.STARTUP_LOG], `-PfindrStartupLog=true`; on in debug builds): what Home draws in the first seconds of
 * each process, as "FindrStartup" lines, so a cold start's first frames can be checked from the log instead of from a screen
 * recording (the "Get the search models" card that flashed on a cold start with the models installed, 1.0.8).
 *
 * One line per change of each thing watched ("card=MODELS at +412 ms"), only in the first [WINDOW_MS] after the process started.
 * The callers note from a SideEffect, i.e. once the composition that draws it is committed. Nothing private is logged.
 */
object StartupFrames {
  const val TAG = "FindrStartup"
  private const val WINDOW_MS = 10_000L
  private val last = ConcurrentHashMap<String, String>()

  /** Whether this build logs at all: callers skip their SideEffect when it does not. */
  val on: Boolean get() = BuildConfig.STARTUP_LOG

  fun note(what: String, value: String) {
    if (!BuildConfig.STARTUP_LOG) return
    val ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
    if (ms > WINDOW_MS) return
    if (last.put(what, value) == value) return
    Log.i(TAG, "$what=$value at +$ms ms")   // log-safe: Home's own state names (card, phase) and a time
  }
}
