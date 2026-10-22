package trawl.desktop.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import trawl.desktop.HeicRoute
import trawl.desktop.Os
import trawl.desktop.Platform
import trawl.desktop.t
import trawl.desktop.ui.Notice
import trawl.desktop.ui.Sun

/** B15: under "What to read", a line when pictures are on but this computer has no way to open HEIC, HEIF or AVIF, and what to install. */
@Composable
fun HeicRouteNote(photosOn: Boolean, os: Os = Platform.os, present: () -> Boolean = HeicRoute::present) {
  if (!photosOn) return
  val has by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { runCatching(present).getOrDefault(true) } }
  if (has != false) return
  Spacer(Modifier.height(Sun.S8))
  Notice(t(if (os == Os.WINDOWS) "settings_heic_missing_windows" else "settings_heic_missing_linux"), bad = true)
}
