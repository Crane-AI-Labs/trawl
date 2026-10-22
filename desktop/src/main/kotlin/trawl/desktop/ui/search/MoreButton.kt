package trawl.desktop.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import trawl.desktop.t
import trawl.desktop.ui.Icons
import trawl.desktop.ui.MainUi
import trawl.desktop.ui.MenuRequest
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunIconButton

/** Where something sits in the window, kept as plain numbers (not state): read only when a menu is asked for, so it never causes a redraw. */
class Spot {
  var x = 0
  var y = 0
  var h = 0
}

fun Modifier.spot(s: Spot): Modifier = onGloballyPositioned { val p = it.positionInWindow(); s.x = p.x.toInt(); s.y = p.y.toInt(); s.h = it.size.height }

/**
 * The "..." button on a result (B6): the same menu as the right-click, for anyone without a right button or a keyboard shortcut at hand. The
 * menu opens just under the button; [row] is the result's whole box, so the position is measured inside it like the right-click's is.
 */
@Composable
fun MoreButton(ui: MainUi, id: Long, row: Spot, modifier: Modifier = Modifier) {
  val me = androidx.compose.runtime.remember { Spot() }
  Box(modifier.size(Sun.Target).spot(me)) {
    SunIconButton(Icons.MoreHoriz, t("action_more"), {
      ui.pressSecondary(id)
      ui.menu = MenuRequest(id, (me.x - row.x).coerceAtLeast(0), (me.y - row.y + me.h).coerceAtLeast(0))
    })
  }
}
