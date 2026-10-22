package trawl.desktop.ui.search

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.ResultsUi
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunType
import trawl.desktop.ui.a11yLive

/**
 * The search's own line in the status bar (A5): "Words now · meaning in a moment" while the fused answer is still coming, "12 so far ·
 * meaning in a moment" once words have found something, "12 found" when it is done. A polite live region, so a screen reader hears the
 * list settle once and is not interrupted by every step. Nothing for the Recent list (the list header counts those).
 */
object SearchStatus {
  fun text(r: ResultsUi): String? {
    if (r.browse) return null
    val n = r.rows.size
    return when {
      !r.complete -> if (n == 0) t("search_status_words") else tn("search_status_so_far", n, formatCount(n))
      n == 0 -> null   // the empty state says it
      else -> tn("search_status_found", n, formatCount(n))
    }
  }
}

@Composable
fun SearchStatusLine(results: ResultsUi, modifier: Modifier = Modifier) {
  val text = SearchStatus.text(results) ?: return
  val tk = LocalSunlight.current
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Spacer(Modifier.width(Sun.S4))
    Text(text, style = SunType.Caption, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.a11yLive())
  }
}
