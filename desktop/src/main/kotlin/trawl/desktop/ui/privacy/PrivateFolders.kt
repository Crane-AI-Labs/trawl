package trawl.desktop.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import java.io.File
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.t
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Notice
import trawl.desktop.ui.Pickers
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.a11yHeading

/**
 * Settings, Privacy: the folders the person treats as private (audit rows D2 and D10), the same behaviour as the phone's list.
 *  - Adding hides more, so it never asks for the unlock.
 *  - Removing makes the pictures in the folder searchable again, which is the unlocking direction: with a passphrase or a system prompt set up
 *    it asks first ([PrivacyUi.proveUnhide]); with neither there is nobody to prove it to.
 *  - A folder is kept relative to the searched folder that holds it ("Photos/Private"), which is what the index matches on. One that is outside
 *    every searched folder, or is a searched folder itself, is refused with the reason.
 *
 * @param roots the folders Trawl searches
 * @param pick the folder chooser (the system's; a test hands in its own)
 */
@Composable
fun PrivateFolders(ui: PrivacyUi, roots: List<File>, pick: () -> File? = { Pickers.folder(roots.firstOrNull(), t("privacy_folders_pick")) }) {
  val c: PrivacyController = ui.c
  val s by c.settings.collectAsState()
  val tk = LocalSunlight.current
  var bad by remember { mutableStateOf(false) }

  Text(t("privacy_folders"), style = SunType.Label, color = tk.ink, modifier = Modifier.padding(top = Sun.S12).a11yHeading())
  Text(t("privacy_folders_hint"), style = SunType.Caption, color = tk.ink2)
  val folders = s.rules.privateFolders.sorted()
  if (folders.isEmpty()) {
    Text(t("privacy_folders_empty"), style = SunType.Body, color = tk.ink, modifier = Modifier.padding(vertical = Sun.S8))
  } else {
    Column(Modifier.padding(top = Sun.S4)) {
      for (folder in folders) {
        Row(Modifier.fillMaxWidth().heightIn(min = Sun.Target), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Folder, contentDescription = null, tint = tk.ink2, modifier = Modifier.size(Sun.Icon))
          Spacer(Modifier.width(Sun.S12))
          Text(folder, style = SunType.Body, color = tk.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
          // Every row says "Remove"; the spoken name says which folder.
          SunTextButton(
            onClick = { ui.proveUnhide { c.setPrivateFolder(folder, false) } },
            modifier = Modifier.semantics { contentDescription = t("speak_remove_folder", folder) },
          ) { Text(t("action_remove"), style = SunType.Label) }
        }
      }
    }
  }
  if (bad) Notice(t("privacy_folders_bad"), bad = true, modifier = Modifier.padding(vertical = Sun.S4))
  SunOutlinedButton(
    onClick = {
      val chosen = pick() ?: return@SunOutlinedButton
      val relative = PrivacyController.relativeFolder(chosen, roots)
      if (relative == null) bad = true else { bad = false; c.setPrivateFolder(relative, true) }
    },
    modifier = Modifier.padding(top = Sun.S8),
  ) {
    Icon(Icons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(Sun.S8))
    Text(t("privacy_folders_add"), style = SunType.Label)
  }
}
