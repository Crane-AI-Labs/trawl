package com.craneai.findr.ui.common

/**
 * File names inside a sentence. A photo's name is one long word ("IMG_20261003_121502.jpg"): wrapped inside a sentence it broke
 * wherever the line ended, even inside the extension ("…121502.jp / g yet.", UI audit P5, D18). In a sentence it is shortened in the
 * middle instead, keeping how it starts and how it ends, extension included, as [androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis]
 * does for a name on its own line. Pure, so it is unit-tested.
 */
object FileNames {
  /** Characters a name may keep in a sentence (the ellipsis included): at normal text, and at large text (1.3 and up). */
  const val NORMAL = 28
  const val LARGE = 14

  /** The end always kept: the extension and a few characters before it, so "…1502.jpg" still tells two photos apart. */
  const val TAIL = 8

  /** Word joiners round the ellipsis: invisible, and the shortened name stays one word (a line could break after "…"). */
  private const val JOIN = "⁠"

  fun middle(name: String, large: Boolean = false): String {
    val budget = if (large) LARGE else NORMAL
    val cps = name.codePoints().toArray()   // by code point, so an emoji in a name is never cut in half
    if (cps.size <= budget) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.codePointCount(dot, name.length) else 0
    val tail = maxOf(TAIL, if (ext in 2..6) ext + 4 else 0).coerceAtMost(budget - 2)
    val head = budget - 1 - tail
    return String(cps, 0, head) + JOIN + "…" + JOIN + String(cps, cps.size - tail, tail)
  }

  /** [middle] as a person sees it (the invisible joiners left out): for tests and for counting. */
  fun visible(s: String): String = s.replace(JOIN, "")
}
