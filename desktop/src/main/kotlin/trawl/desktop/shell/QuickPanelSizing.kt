package trawl.desktop.shell

/**
 * How big the quick panel is, and where it sits, for a given screen. Pure numbers in dp (points on a Mac), so it is tested without a window.
 *
 * The list-only panel is Spotlight's shape: wide and not very tall, the query large. Opening a preview widens it and makes it taller so the
 * preview has room (the result list narrows to a column on the left, the preview takes the rest). Either way the panel never exceeds the
 * screen it opens on: a 1280 x 800 laptop gets a smaller panel than a 1512 x 982 one, and a small window still fits.
 */
object QuickPanelSizing {
  data class Size(val width: Int, val height: Int)

  /** The panel's card size (without the transparent shadow margin the window adds around it). */
  fun of(screenWidth: Int, screenHeight: Int, preview: Boolean): Size {
    val w = if (preview) minOf(PREVIEW_WIDTH, (screenWidth * 0.86).toInt()) else minOf(LIST_WIDTH, (screenWidth * 0.9).toInt())
    val h = if (preview) minOf(PREVIEW_HEIGHT, (screenHeight * 0.84).toInt()) else (screenHeight * 0.7).toInt().coerceIn(MIN_HEIGHT, LIST_HEIGHT)
    return Size(w.coerceAtLeast(MIN_WIDTH), h.coerceAtLeast(MIN_HEIGHT).coerceAtMost(screenHeight))
  }

  /** Left edge for a card of [width] centred on the screen whose left edge is [screenX]. */
  fun left(screenX: Int, screenWidth: Int, width: Int): Int = screenX + (screenWidth - width) / 2

  /**
   * Top edge: the upper third (16 % down, where Spotlight sits), moved up if the card would run off the bottom, and never above the screen.
   * [margin] keeps a little desktop under the card when it has room.
   */
  fun top(screenY: Int, screenHeight: Int, height: Int, margin: Int = 24): Int {
    val wanted = (screenHeight * 0.16).toInt()
    val limit = screenHeight - height - margin
    return screenY + minOf(wanted, limit).coerceAtLeast(0)
  }

  const val LIST_WIDTH = 760
  const val LIST_HEIGHT = 620
  const val PREVIEW_WIDTH = 1040
  const val PREVIEW_HEIGHT = 700
  const val MIN_WIDTH = 420
  const val MIN_HEIGHT = 320
}
