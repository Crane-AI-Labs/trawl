package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.ResultRow
import trawl.desktop.t
import trawl.desktop.privacy.LocalPreviewBlur
import trawl.desktop.ui.privacy.rememberLeave

/**
 * Space on a result: the file large, over a dimmed window, like Quick Look on a Mac. Arrows step to the next and previous result, Enter opens,
 * Space or Esc (or a click outside) closes. It is drawn inside the window, so it works the same on every computer and in a single column.
 */
@Composable
fun QuickLook(row: ResultRow, position: Int, total: Int, c: AppController, onClose: () -> Unit) {
  val tk = LocalSunlight.current
  val f = row.file
  val hidden = LocalPreviewBlur.current.hides(f)
  val leave = rememberLeave()
  val shape = RoundedCornerShape(14.dp)
  val folder = remember(f.id) { PathText.tilde(c.fileOf(f).path) }
  BoxWithConstraints(
    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { onClose() } },
    contentAlignment = Alignment.Center,
  ) {
    val w = (maxWidth - 64.dp).coerceIn(320.dp, 1040.dp)
    val h = (maxHeight - 64.dp).coerceIn(300.dp, 760.dp)
    Column(
      Modifier.size(w, h).clip(shape).background(tk.paper).border(Sun.Border, tk.line, shape).pointerInput(Unit) { detectTapGestures { } }
        .semantics { contentDescription = t("quicklook_label") }.padding(Sun.S16),
      verticalArrangement = Arrangement.spacedBy(Sun.S12),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(if (hidden) t("hidden_label") else f.name, style = SunType.Heading, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        RowBadges(row)
        Spacer(Modifier.weight(1f))
        Text("$position / $total", style = SunType.Caption, color = tk.ink2)
        SunIconButton(Icons.Close, t("action_close"), onClose)
      }
      PreviewMedia(row, c, height = null, side = 1800, excerptLines = 40, modifier = Modifier.weight(1f))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(if (hidden) t("hidden_label") else folder, style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(t("quicklook_move"), style = SunType.Caption, color = tk.ink2)
        Text(t("quicklook_hint"), style = SunType.Caption, color = tk.ink2)
        SunButton(onClick = { leave(f.id) { c.open(f) } }) {
          Text(t("action_open"), style = SunType.Label)
          Spacer(Modifier.width(Sun.S8))
          KeyCap("Enter")
        }
        SunOutlinedButton(onClick = { leave(f.id) { c.showInFolder(f) } }) {
          Text(t("action_show_folder"), style = SunType.Label)
          Spacer(Modifier.width(Sun.S8))
          KeyCap("${MainUi.modName} Enter")
        }
      }
    }
  }
}
