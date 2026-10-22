package trawl.desktop.ui

/**
 * Where the window changes shape, as pure arithmetic so the breakpoints are tested rather than eyeballed. Widths are in dp (what Compose
 * measures: points on a Retina Mac). The minimum window (720 x 560) is always a single column; from [SPLIT_MIN] up the results sit on
 * the left and a preview on the right.
 */
object Layout {
  /** Below this width the window is one column: results, and the preview is a Quick Look overlay. */
  const val SPLIT_MIN = 900

  /** The preview's share of the window, in percent, and the bounds it is clamped to (a 1920 window must not grow a billboard). */
  const val PREVIEW_PERCENT = 40
  const val PREVIEW_MIN = 340
  const val PREVIEW_MAX = 720

  /** A photo tile: at least this wide, plus the gap between tiles. */
  const val TILE_MIN = 132
  const val TILE_GAP = 8

  /** Settings shows its section list on the left from this width. */
  const val RAIL_MIN = 880

  /** First run puts the welcome on the left and the setup on the right from this width. */
  const val TWO_COLUMN_MIN = 900

  enum class Mode { SINGLE, SPLIT }

  fun mode(widthDp: Int): Mode = if (widthDp >= SPLIT_MIN) Mode.SPLIT else Mode.SINGLE

  /** The preview pane's width for a window [widthDp] wide (0 in a single column). */
  fun previewWidth(widthDp: Int): Int =
    if (mode(widthDp) == Mode.SINGLE) 0 else (widthDp * PREVIEW_PERCENT / 100).coerceIn(PREVIEW_MIN, PREVIEW_MAX)

  /** The results list's width, preview and the divider taken off. */
  fun listWidth(widthDp: Int): Int = widthDp - previewWidth(widthDp)

  /** How many photo tiles fit across a results list [listWidthDp] wide after its side padding; never fewer than two. */
  fun gridColumns(listWidthDp: Int): Int = ((listWidthDp + TILE_GAP) / (TILE_MIN + TILE_GAP)).coerceAtLeast(2)

  /** The bottom bar lists the main shortcuts from this width; narrower, it keeps only the reading status. */
  const val KEY_HINTS_MIN = 1100

  fun showKeyHints(widthDp: Int): Boolean = widthDp >= KEY_HINTS_MIN

  fun showRail(widthDp: Int): Boolean = widthDp >= RAIL_MIN

  fun twoColumnFirstRun(widthDp: Int): Boolean = widthDp >= TWO_COLUMN_MIN

  /**
   * Which Settings section the section list highlights while the page scrolls: the last one whose top has reached the top of the view
   * (less [SPY_SLACK] so a section lights up just before it touches). [tops] are the sections' offsets in page order; -1 when none are measured.
   */
  const val SPY_SLACK = 48

  fun activeSection(tops: List<Int>, scroll: Int, maxScroll: Int): Int {
    if (tops.isEmpty()) return -1
    // At the very bottom the last section is the one being read, even when it is too short to reach the top.
    if (maxScroll > 0 && scroll >= maxScroll - 2) return tops.lastIndex
    var active = 0
    tops.forEachIndexed { i, top -> if (top <= scroll + SPY_SLACK) active = i }
    return active
  }
}
