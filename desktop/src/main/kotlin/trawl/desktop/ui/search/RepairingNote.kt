package trawl.desktop.ui.search

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import trawl.desktop.t
import trawl.desktop.ui.Notice
import trawl.desktop.ui.Sun

/**
 * A20: shown above the results while the engine that reads meaning failed its self-test and is being repaired. Words and names still find
 * files, so the person is told the search is only partly working instead of finding odd results with no explanation.
 */
@Composable
fun RepairingNote(repairing: Boolean) {
  if (!repairing) return
  Notice(t("search_repairing_note"), bad = true, modifier = Modifier.padding(horizontal = Sun.S20, vertical = Sun.S8))
}
