package com.craneai.findr.engine

import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact

/**
 * Profiling aid, started only by a build made with -PfindrKnobs=true and a "stackDumpSec" knob: every few seconds it logs what the
 * app's busy threads are doing (their top frames), so a window where the phone sits idle while Trawl "works" can be explained
 * from the log instead of guessed. Threads parked in a pool waiting for work are left out. A shipped build never starts it.
 */
object StackProbe {
  private const val TAG = "FindrStacks"
  @Volatile private var started = false

  fun start(everySec: Int) {
    if (started || everySec <= 0) return
    started = true
    Thread({
      while (true) {
        try { Thread.sleep(everySec * 1000L) } catch (_: InterruptedException) { return@Thread }
        val all = Thread.getAllStackTraces()
        val busy = all.filter { (t, st) -> st.isNotEmpty() && t.name != "StackProbe" && !idle(st) }
        Log.i(TAG, "stacks: ${all.size} threads, ${busy.size} not idle")
        for ((t, st) in busy.entries.sortedBy { it.key.name }.take(14)) {
          Log.i(TAG, "  ${redact(t.name)} [${t.state}]: " + st.take(7).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" })
        }
      }
    }, "StackProbe").apply { isDaemon = true }.start()
  }

  /** Parked or waiting for work in a pool, the main looper, or a plain sleep of a library thread: nothing to explain. */
  private fun idle(st: Array<StackTraceElement>): Boolean {
    val top = st[0]
    if (top.className == "java.lang.Object" && top.methodName == "wait" && st.size < 6) return true
    if (top.methodName == "park" || top.methodName == "nativePollOnce") return true
    return false
  }
}
