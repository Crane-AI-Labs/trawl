package trawl.desktop.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import trawl.desktop.Reporter
import trawl.desktop.t
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType

/**
 * The card shown on the next start after Trawl crashed: it asks once whether to look at a report. Either answer clears it for
 * that crash ([Reporter.acknowledge]); nothing is sent by either, because Trawl sends nothing.
 *
 * Draws nothing when there is no crash waiting. Show it at the top of the main screen.
 */
@Composable
fun CrashCard(reporter: Reporter, openLogFolder: () -> Unit = {}, modifier: Modifier = Modifier) {
  var pending by remember { mutableStateOf(runCatching { reporter.pendingCrash() }.getOrNull() != null) }
  var viewing by remember { mutableStateOf(false) }
  if (pending) {
    SunCard(modifier.fillMaxWidth()) {
      Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
        Text(t("report_card_title"), style = SunType.Label)
        Text(t("report_card_body"), style = SunType.Body)
        Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          SunButton(onClick = { viewing = true }) { Text(t("report_card_view")) }
          SunTextButton(onClick = { reporter.acknowledge(); pending = false }) { Text(t("report_card_not_now")) }
        }
      }
    }
  }
  if (viewing) {
    ReportDialog(reporter, onClose = { viewing = false; reporter.acknowledge(); pending = false }, openLogFolder = openLogFolder)
  }
}
