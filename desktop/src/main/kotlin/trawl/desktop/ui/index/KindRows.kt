package trawl.desktop.ui.index

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.IndexAdmin
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunType

/** The words and the icon for one kind of file in the by-kind list. */
object KindLabels {
  fun key(kind: FileKind): String = when (kind) {
    FileKind.IMAGE -> "index_kind_image"
    FileKind.SCREENSHOT -> "index_kind_screenshot"
    FileKind.DOCUMENT -> "index_kind_document"
    FileKind.PDF -> "index_kind_pdf"
    FileKind.VIDEO -> "index_kind_video"
    FileKind.AUDIO -> "index_kind_audio"
    FileKind.OTHER -> "kind_other"
  }

  fun label(kind: FileKind): String = t(key(kind))

  fun icon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> Icons.Photo
    FileKind.SCREENSHOT -> Icons.Phone
    FileKind.DOCUMENT -> Icons.Document
    FileKind.PDF -> Icons.Pdf
    FileKind.VIDEO -> Icons.Video
    FileKind.AUDIO -> Icons.Mic
    FileKind.OTHER -> Icons.Document
  }

  /** The numbers of one row as a sentence: "120 of 150 · 20 waiting · 2 could not be read" (the parts that are zero are left out). */
  fun summary(row: IndexAdmin.KindRow, on: Boolean): String {
    if (!on) return t("index_kind_off")
    val parts = ArrayList<String>()
    parts += t("index_level_count", formatCount(row.understood), formatCount(row.known))
    if (row.waiting > 0) parts += tn("index_kind_waiting", row.waiting, formatCount(row.waiting))
    if (row.failed > 0) parts += tn("index_kind_failed", row.failed, formatCount(row.failed))
    return parts.joinToString(" · ")
  }
}

/** The by-kind list: one line per kind that has files, its counts, and the size of those files on disk. Kinds switched off are listed and say so. */
@Composable
fun KindRows(rows: List<IndexAdmin.KindRow>, kindsOn: Set<FileKind>?, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    rows.forEach { row ->
      val on = kindsOn == null || row.kind in kindsOn
      Row(Modifier.fillMaxWidth().heightIn(min = Sun.Target).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(KindLabels.icon(row.kind), contentDescription = null, tint = if (on) tk.ink else tk.ink2, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(KindLabels.label(row.kind), style = SunType.Label, color = if (on) tk.ink else tk.ink2)
          Text(KindLabels.summary(row, on), style = SunType.Caption, color = tk.ink2)
        }
        Spacer(Modifier.width(12.dp))
        Text(formatBytes(row.bytes), style = SunType.Caption, color = tk.ink2)
      }
    }
  }
}
