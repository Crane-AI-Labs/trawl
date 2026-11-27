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
  /** True when the results pane is in tabular List view instead of Grid view. */
  val listMode: Boolean = false,
  /** Current results pane width in dp, used to align responsive List view metadata columns. */
  val listWidthDp: Int = 600,
)

val LocalRowContext = compositionLocalOf { RowContext() }

/** Columns shown in List view. */
enum class ListColumn { NAME, FOLDER, SIZE, MODIFIED, CREATED }

/** Responsive visibility of List view columns so narrow split panes never crush the file name. */
data class ListColumnVisibility(
  val showFolder: Boolean,
  val showModified: Boolean,
  val showCreated: Boolean,
) {
  companion object {
    const val FOLDER_MIN_DP = 540
    const val BOTH_DATES_MIN_DP = 460

    fun forWidth(widthDp: Int, sort: SortOrder): ListColumnVisibility {
      val showFolder = widthDp >= FOLDER_MIN_DP
      if (widthDp >= BOTH_DATES_MIN_DP) return ListColumnVisibility(showFolder, showModified = true, showCreated = true)
      val createdActive = sort == SortOrder.CREATED_NEWEST || sort == SortOrder.CREATED_OLDEST
      return ListColumnVisibility(showFolder = false, showModified = !createdActive, showCreated = createdActive)
    }
  }
}

/** Pure sort-toggle rules for clicking column headers in List view. */
object ListColumnSort {
  fun nextSort(column: ListColumn, current: SortOrder): SortOrder? = when (column) {
    ListColumn.NAME -> SortOrder.NAME
    ListColumn.FOLDER -> null
    ListColumn.SIZE -> if (current == SortOrder.LARGEST) SortOrder.SMALLEST else SortOrder.LARGEST
    ListColumn.MODIFIED -> if (current == SortOrder.NEWEST) SortOrder.OLDEST else SortOrder.NEWEST
    ListColumn.CREATED -> if (current == SortOrder.CREATED_NEWEST) SortOrder.CREATED_OLDEST else SortOrder.CREATED_NEWEST
  }

  /** Sort indicator glyph for [column] under [current]: "↓" descending, "↑" ascending, or null when inactive. */
  fun indicator(column: ListColumn, current: SortOrder): String? = when (column) {
    ListColumn.NAME -> if (current == SortOrder.NAME) "↑" else null
    ListColumn.FOLDER -> null
    ListColumn.SIZE -> when (current) {
      SortOrder.LARGEST -> "↓"
      SortOrder.SMALLEST -> "↑"
      else -> null
    }
    ListColumn.MODIFIED -> when (current) {
      SortOrder.NEWEST -> "↓"
      SortOrder.OLDEST -> "↑"
      else -> null
    }
    ListColumn.CREATED -> when (current) {
      SortOrder.CREATED_NEWEST -> "↓"
      SortOrder.CREATED_OLDEST -> "↑"
      else -> null
    }
  }
}

/**
 * The second line of a document row: age, then folder; the size is added when the list is sorted by Largest or Smallest (and then comes first, since it is
 * what the order is about). Age and size come before the folder, so a long path that is cut short never hides them. Pure, so it is tested.
 */
object RowText {
  const val SEP = "  ·  "

  fun subtitle(age: String, folder: String, size: String, sort: SortOrder): String =
    listOfNotNull(size.takeIf { (sort == SortOrder.LARGEST || sort == SortOrder.SMALLEST) && it.isNotBlank() }, age.takeIf { it.isNotBlank() }, folder.takeIf { it.isNotBlank() }).joinToString(SEP)
}

/** The text is large (the system's or the app's text size at 130% or more) or the list is narrow: rows stack their parts instead of squeezing them. */
fun stackRows(fontScale: Float, listWidthDp: Int): Boolean = fontScale >= STACK_FONT_SCALE || listWidthDp < STACK_BELOW_DP

const val STACK_FONT_SCALE = 1.3f
const val STACK_BELOW_DP = 360
