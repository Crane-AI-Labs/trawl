package com.craneai.findr.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Windows with too little height for Home's stacked layout: a phone on its side, a tablet in landscape, split screen.
 *
 * Found on the tablet (SM-X135G, 601 dp tall on its side: 1340 x 800 px at 213 dpi): the header, meter, note, filter chips and sort chips took about 360 dp before
 * the first result, and the search box and the bar under it kept another 120 dp, so the list showed about one row. In a short
 * window the whole top of the screen (header, cards, meter, notes, chips) is the first thing in the list, so it scrolls away with
 * it; the header and the meter share one line; the kind, sort and date chips share one line; and result rows use the one-line
 * form they already use while typing. A tall window (every portrait phone and tablet, a large tablet on its side) is untouched.
 */
internal object ShortWindow {
  /** Below this height a window is short whatever its shape (split screen, a phone's small-window mode). */
  const val SHORT_DP = 480

  /** A landscape window under this height is short; a large tablet on its side (800 dp tall) has the room for the stacked layout. */
  const val LANDSCAPE_TALL_DP = 700

  /** Wide enough to hold the wordmark, the meter and Settings on one line; narrower than this the meter drops below the header. */
  const val MERGED_HEADER_MIN_WIDTH_DP = 600

  fun isShort(widthDp: Int, heightDp: Int): Boolean = heightDp < SHORT_DP || (widthDp > heightDp && heightDp < LANDSCAPE_TALL_DP)

  fun mergedHeader(widthDp: Int): Boolean = widthDp >= MERGED_HEADER_MIN_WIDTH_DP
}

/** Height of the nothing-found and empty-home bodies in a short window: they scroll by themselves, so they need a bounded height. */
internal val SHORT_EMPTY_BODY = 260.dp

/** Whether this window is short. Reads the live configuration, so a rotation or a split-screen change recomposes. */
@Composable
internal fun rememberShortWindow(): Boolean {
  val c = LocalConfiguration.current
  return ShortWindow.isShort(c.screenWidthDp, c.screenHeightDp)
}
