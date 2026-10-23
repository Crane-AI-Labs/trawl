package com.craneai.findr.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wide windows (a tablet on its side, 1007 dp across on the test tablet): lists and forms keep a reading width, centred, instead of
 * stretching a result row across the whole screen with its thumbnail and its words a hand's width apart.
 *
 * The cap is [MAX_DP] (720 dp of content) including the usual gutters, so below that width (every phone, a tablet upright) nothing changes by a single
 * pixel: [sideInset] returns the base padding and [sunContentWidth] fills the width it was given.
 */
object ContentWidth {
  /** 720 dp of content plus the two 12 dp gutters: the six kind chips (about 706 dp) then fit the column in one line. */
  const val MAX_DP = 744

  /** Side padding for a scrolling list or form that needs its own scroll area full width: [base] plus half of what is over the cap. */
  fun sideInset(widthDp: Int, base: Dp): Dp = base + (maxOf(0, widthDp - MAX_DP) / 2).dp

  /** True when the window is wider than the reading column, so a one-line row of eight chips would run past it. */
  fun isWide(widthDp: Int): Boolean = widthDp > MAX_DP

  /**
   * The kind, sort and date chips of a short window share one scrolling line only while it fits the screen. On a wide short window
   * (a tablet on its side) the column is narrower than the line, so Sort and Date would hide off its right edge: they stack in two.
   */
  fun chipsOnOneLine(short: Boolean, widthDp: Int): Boolean = short && !isWide(widthDp)
}

/** The side padding of a full-width scroller (the scroll area keeps the whole screen, the content keeps the reading width). */
@Composable
fun sunSideInset(base: Dp): Dp = ContentWidth.sideInset(LocalConfiguration.current.screenWidthDp, base)

/**
 * Caps a full-width element at the reading width and centres it. Put it first in the chain, before the element's own fillMaxWidth and
 * padding: they then work inside the capped width exactly as they did inside the screen.
 */
fun Modifier.sunContentWidth(): Modifier =
  fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentWidth.MAX_DP.dp)
