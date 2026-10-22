package com.craneai.findr.engine

/**
 * When the index worker may start a GPU image encode (the research notes). An encode can't be interrupted (~3.6 s of
 * GPU on the A06), and while it runs the shared GPU freezes the app's lists for 0.5-1.4 s per frame. So it starts only:
 * - [QUIET_MS] after the last touch (CPU steps need only the worker's 1.5 s), and
 * - while a list is on screen, [BROWSE_HOLD_MS] after the last touch: a reader pauses longer than the quiet window between
 *   flings. Watching the meter on the home grid without touching it gets encodes after that, so a first run never looks stuck.
 * The screen off or the app in the background ([uiVisible] false) leaves only the quiet window.
 */
object GpuGate {
  const val QUIET_MS = 8_000L
  const val BROWSE_HOLD_MS = 30_000L

  /** [lastTouchEndMs]: when the last touch ended (null: a finger is down or the search box holds focus). */
  fun shouldWait(nowMs: Long, lastTouchEndMs: Long?, uiVisible: Boolean, listsOnScreen: Int): Boolean {
    if (lastTouchEndMs == null) return true
    val since = nowMs - lastTouchEndMs
    if (uiVisible && listsOnScreen > 0 && since < BROWSE_HOLD_MS) return true
    return since < QUIET_MS
  }
}
