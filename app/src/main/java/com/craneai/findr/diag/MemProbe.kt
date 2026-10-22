package com.craneai.findr.diag

import com.craneai.findr.core.FindrLog as Log

/**
 * Test builds only (the same `-PfindrHeapLog=true` switch as [HeapProbe]): every [PERIOD_MS] one "FindrMem" line with the
 * process's whole memory in groups (see [SmapsGroups]): `group=pss/privateDirty/privateClean` in MB, so a long indexing run on a
 * phone shows which models and buffers hold dirty anonymous memory (the real cost) and which hold clean file pages (cheap).
 * The same line carries RssAnon, RssFile and VmSwap from /proc/self/status and the biggest single mappings.
 *
 * Counts and sizes only, nothing about the person's files. Reading smaps costs a few ms; off in every shipped build.
 */
object MemProbe {
  private const val TAG = "FindrMem"
  private const val PERIOD_MS = 10_000L
  @Volatile private var started = false

  fun start(enabled: Boolean) {
    if (!enabled || started) return
    started = true
    Thread({
      while (!Thread.currentThread().isInterrupted) {
        try {
          Thread.sleep(PERIOD_MS)
          Log.i(TAG, line())
        } catch (e: InterruptedException) {
          break
        } catch (e: Exception) {
          Log.w(TAG, "smaps unreadable: ${e.javaClass.simpleName}")
        }
      }
    }, "findr-memprobe").apply { isDaemon = true; start() }
  }

  /** One sample as a log line. Public so a bench can print the same line at a named moment. */
  fun line(label: String = ""): String {
    val r = SmapsGroups.readSelf()
    val status = java.io.File("/proc/self/status").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
    fun mb(k: String) = (status[k]?.substringBefore(' ')?.toLongOrNull() ?: 0L) / 1024
    val top = r.top.joinToString(" ") { "${it.first.substringAfterLast('/').take(40)}:${it.second / 1024}" }
    return (if (label.isNotEmpty()) "[$label] " else "") +
      "pss ${r.totalPssKb / 1024} dirty ${r.totalPrivateDirtyKb / 1024} anon ${mb("RssAnon")} file ${mb("RssFile")} swap ${mb("VmSwap")} | " +
      SmapsGroups.summary(r) + " | top $top"
  }
}
