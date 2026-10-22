package trawl.desktop.ui.index

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.t
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunType

/**
 * The card shown in place of the meter when the index file cannot be opened (busy, disk full, unreadable, out of memory, other).
 * Calm on purpose: nothing was deleted and Trawl is trying again by itself, so it says that, once, and offers Try again. It names the
 * kind of trouble in a plain sentence and never shows an exception.
 */
@Composable
fun OpenProblemCard(problem: IndexOpenProblem, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  SunCard(modifier.fillMaxWidth(), color = tk.paper) {
    Column(Modifier.padding(horizontal = Sun.S16, vertical = Sun.S12), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Warning, contentDescription = null, tint = tk.ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(t("open_problem_title", t("app_name")), style = SunType.Label, color = tk.ink)
      }
      Text(MeterTexts.problem(problem), style = SunType.Body, color = tk.ink)
      Text(t("open_problem_detail"), style = SunType.Caption, color = tk.ink2)
      if (onRetry != null) SunOutlinedButton(onClick = onRetry) { Text(t("action_try_again"), style = SunType.Label) }
    }
  }
}
