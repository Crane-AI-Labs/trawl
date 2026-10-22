package trawl.desktop.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.core.SortOrder
import trawl.desktop.ResultsUi
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.ListStyle
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunType
import trawl.desktop.ui.sunFocus
import trawl.desktop.ui.sunHover

/**
 * Which shape the list takes (A12, A23): "Find similar" and any chosen sort or date give one flat list in the order asked for (no headings
 * to break it up); Recent (nothing typed, newest first, no date) is the feed; everything else is the grouped results.
 */
fun listStyleOf(r: ResultsUi): ListStyle = when {
  r.similar != null -> ListStyle.SORTED
  r.browse && r.sort == SortOrder.NEWEST && r.date == null -> ListStyle.RECENT
  r.sort != SortOrder.BEST || r.date != null -> ListStyle.SORTED
  else -> ListStyle.RESULTS
}

/**
 * The photo peek's heading: a few photos sit among the first document rows, under "Photos (N)". When more photos follow, "See all" jumps
 * to the full Photos group lower down (the phone's tap on the strip).
 */
@Composable
fun PeekHeading(count: Int, more: Boolean, onSeeAll: () -> Unit) {
  val tk = LocalSunlight.current
  Row(
    Modifier.fillMaxWidth().padding(start = Sun.S16, end = Sun.S8, top = Sun.S12, bottom = Sun.S4).semantics { heading() },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(t("kind_plural_photos"), style = SunType.CaptionBold, color = tk.ink2, modifier = Modifier.weight(1f, fill = false))
    Spacer(Modifier.width(Sun.S8))
    Text(count.toString(), style = SunType.Caption, color = tk.ink2)
    Spacer(Modifier.weight(1f))
    if (more) {
      val shape = RoundedCornerShape(8.dp)
      Row(
        Modifier.sunFocus(shape).sunHover(shape).clip(shape).pointerHoverIcon(PointerIcon.Hand).clickable(role = Role.Button, onClick = onSeeAll)
          .heightIn(min = 32.dp).padding(horizontal = Sun.S8),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(t("peek_see_all"), style = SunType.Label, color = tk.ink)
        Spacer(Modifier.width(Sun.S4))
        Icon(Icons.ExpandMore, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.IconSmall))
      }
    }
  }
}

/** "Show 214 more documents": the Recent list keeps to a few rows; the rest are one click away (it types nothing, it just asks for the documents). */
@Composable
fun MoreRow(group: trawl.desktop.ui.Group, hidden: Int, mix: trawl.desktop.ui.Mix = trawl.desktop.ui.Mix.DOCUMENTS, onMore: () -> Unit) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(8.dp)
  Row(
    Modifier.padding(horizontal = Sun.S8, vertical = Sun.S4).sunFocus(shape).sunHover(shape).clip(shape).pointerHoverIcon(PointerIcon.Hand)
      .clickable(role = Role.Button, onClick = onMore).heightIn(min = Sun.Target).padding(horizontal = Sun.S12),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.ExpandMore, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S8))
    val key = when {
      group != trawl.desktop.ui.Group.DOCUMENTS -> "recent_more_photos"
      mix == trawl.desktop.ui.Mix.MEDIA -> "recent_more_media"
      mix == trawl.desktop.ui.Mix.BOTH -> "recent_more_files"
      else -> "recent_more_documents"
    }
    Text(tn(key, hidden, formatCount(hidden)), style = SunType.Label, color = tk.ink)
  }
}
