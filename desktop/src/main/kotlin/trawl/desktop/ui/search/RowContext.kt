package trawl.desktop.ui.search

import androidx.compose.runtime.compositionLocalOf
import com.craneai.findr.core.SortOrder

/**
 * What every row of the list needs to know about the list it is in, handed down once instead of through each row: how it is sorted (the size shows
 * only for "Largest") and what time it is, from the computer's clock moved up to the newest file's date when the clock is behind (so an age never
 * counts from a wrong "now"). [nowMs] changes every half minute, which redraws the ages.
 */
data class RowContext(
  val sort: SortOrder = SortOrder.BEST,
  val nowMs: Long = System.currentTimeMillis(),
  /** The name, the badges and the subtitle stack on their own lines (and may wrap to two): the window is narrow or the text is large (H3). */
  val stack: Boolean = false,
)

val LocalRowContext = compositionLocalOf { RowContext() }

/**
 * The second line of a document row: age, then folder; the size is added only when the list is sorted by Largest (and then comes first, since it is
 * what the order is about). Age and size come before the folder, so a long path that is cut short never hides them. Pure, so it is tested.
 */
object RowText {
  const val SEP = "  ·  "

  fun subtitle(age: String, folder: String, size: String, sort: SortOrder): String =
    listOfNotNull(size.takeIf { sort == SortOrder.LARGEST && it.isNotBlank() }, age.takeIf { it.isNotBlank() }, folder.takeIf { it.isNotBlank() }).joinToString(SEP)
}

/** The text is large (the system's or the app's text size at 130% or more) or the list is narrow: rows stack their parts instead of squeezing them. */
fun stackRows(fontScale: Float, listWidthDp: Int): Boolean = fontScale >= STACK_FONT_SCALE || listWidthDp < STACK_BELOW_DP

const val STACK_FONT_SCALE = 1.3f
const val STACK_BELOW_DP = 360
