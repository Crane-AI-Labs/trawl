package trawl.desktop.ui

/**
 * How big the first window is, as pure arithmetic so it is tested on every OS. The roomy size (1120 x 800) is kept wherever the
 * screen has room for it; on a smaller screen (a 1024 x 768 laptop or a Windows VM) the window shrinks to the usable area, which is
 * the screen minus the taskbar, dock or menu bar. The minimum is clamped the same way, so a small screen is never asked for a
 * window it cannot show.
 */
object WindowSizing {
  const val DEFAULT_W = 1120
  const val DEFAULT_H = 800
  const val MIN_W = 720
  const val MIN_H = 560

  /** Room kept for the window's own title bar and border: the usable area does not include them in every toolkit. */
  const val FRAME_ALLOWANCE = 36

  /**
   * The most of the usable area a first window may take, in percent. A window that fills nearly the whole height or width of the
   * usable area reads as "fullscreen" even though it is not: a 1120 x 800 window on a 14-inch MacBook (1512 x 982 points, about
   * 874 usable under the menu bar and Dock) left 6 points under it. The window is always centred, so this leaves an even margin.
   */
  const val MAX_SHARE_PERCENT = 85

  data class Plan(val width: Int, val height: Int, val minWidth: Int, val minHeight: Int, val clamped: Boolean)

  /**
   * [usableW] x [usableH] is the screen's usable area in the units AWT windows are sized in: points on a Retina Mac (1512 x 982 on
   * the 3024 x 1964 pixel panel) and scaled units on a Windows screen set to 150%. The window and the screen are in the same units,
   * so no scale factor is applied here; passing physical pixels would be the bug. 0 or less means unknown (keep the defaults).
   */
  fun plan(usableW: Int, usableH: Int): Plan {
    if (usableW <= 0 || usableH <= 0) return Plan(DEFAULT_W, DEFAULT_H, MIN_W, MIN_H, clamped = false)
    val maxW = minOf(usableW, usableW * MAX_SHARE_PERCENT / 100)
    val maxH = minOf((usableH - FRAME_ALLOWANCE).coerceAtLeast(300), usableH * MAX_SHARE_PERCENT / 100)
    val w = minOf(DEFAULT_W, maxW)
    val h = minOf(DEFAULT_H, maxH)
    return Plan(w, h, minOf(MIN_W, w), minOf(MIN_H, h), clamped = w < DEFAULT_W || h < DEFAULT_H)
  }
}
