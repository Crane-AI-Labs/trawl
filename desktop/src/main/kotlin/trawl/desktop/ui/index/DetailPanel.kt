package trawl.desktop.ui.index

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.IndexAdmin
import trawl.desktop.IndexUi
import trawl.desktop.Phase
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunDivider
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunType

/** One label and value of the panel's facts, with a note under it when there is one. */
data class Fact(val label: String, val value: String, val note: String? = null)

/** The facts of the panel as text, worked out apart from the drawing so a test can read them for any state. */
object DetailFacts {
  /** Files an hour, from the files-a-minute the service measured; null until it has measured. */
  fun perHour(ix: IndexUi): Int? = ix.ratePerMin?.takeIf { it > 0 }?.let { (it * 60).toInt().coerceAtLeast(1) }

  fun of(ix: IndexUi, d: IndexAdmin.Detail?, freeBytes: Long? = ix.freeBytes): List<Fact> {
    val out = ArrayList<Fact>()
    val working = ix.phase == Phase.INDEXING || ix.phase == Phase.SCANNING
    if (working) {
      val eta = ix.etaMs
      out += Fact(t("index_stat_time_left"), if (eta != null) MeterTexts.etaText(eta) else t("index_stat_measuring"))
      val speed = perHour(ix)
      out += Fact(t("index_stat_speed"), if (speed != null) tn("index_speed", speed, formatCount(speed)) else t("index_stat_measuring"))
    }
    if (d != null) {
      out += Fact(t("index_stat_index_size"), formatBytes(d.indexBytes))
      out += Fact(t("index_stat_model_size"), d.modelBytes?.let(::formatBytes) ?: t("index_stat_none"))
      d.estimate?.let {
        out += Fact(t("index_stat_estimate"), t("index_stat_estimate_value", formatBytes(it.indexBytes)))
        out += Fact(t("index_stat_memory"), formatBytes(it.ramBytes), t("index_stat_memory_hint"))
      }
    }
    if (freeBytes != null) out += Fact(t("index_stat_disk_free"), formatBytes(freeBytes))
    return out
  }

  /** "12 files are waiting to be read." or "Nothing is waiting." */
  fun waitingLine(waiting: Int): String =
    if (waiting <= 0) t("index_waiting_none") else tn("index_waiting_line", waiting, formatCount(waiting))
}

/** The detail panel in a dialog: the meter's second page. [onClose] runs for Done, Escape and a click outside. */
@Composable
fun IndexDetailDialog(ix: IndexUi, detail: IndexAdmin.Detail?, actions: IndexActions, kindsOn: Set<FileKind>?, onClose: () -> Unit) {
  Dialog(onDismissRequest = onClose) {
    IndexDetailPanel(ix, detail, actions, kindsOn, onClose)
  }
}

/** The panel itself, so it can also be drawn on its own (the screenshots do). */
@Composable
fun IndexDetailPanel(ix: IndexUi, detail: IndexAdmin.Detail?, actions: IndexActions, kindsOn: Set<FileKind>?, onClose: () -> Unit, modifier: Modifier = Modifier, scroll: androidx.compose.foundation.ScrollState = rememberScrollState()) {
  val tk = LocalSunlight.current
  val levels = Levels.of(ix)
  SunCard(modifier.widthIn(max = 640.dp).heightIn(max = 760.dp), color = MaterialTheme.colorScheme.background) {
    // The content scrolls; Done is pinned under it (always on screen, never against the card's bottom edge).
    Column {
     Box(Modifier.weight(1f, fill = false)) {
      Column(Modifier.verticalScroll(scroll).padding(start = Sun.S24, end = 28.dp, top = Sun.S24, bottom = Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(t("index_detail_title"), style = SunType.Title, color = tk.ink, modifier = Modifier.semantics { heading() })
        if (ix.phase == Phase.PAUSED || ix.phase == Phase.INDEXING || ix.phase == Phase.SCANNING || ix.phase == Phase.UP_TO_DATE) {
          val text = MeterTexts.of(ix)
          Text(text.title, style = SunType.Heading, color = tk.ink)
          if (text.sub.isNotEmpty()) Text(text.sub, style = SunType.Body, color = tk.ink)
        }

        // The two levels.
        val known = detail?.known ?: ix.total
        val twoLevels = ix.modelReady && !ix.readingWords
        TwoLevelBar(if (known == 0) 0f else levels.searchable, if (twoLevels) levels.understood else 0f)
        LevelLine(t("index_level_words"), t("index_level_words_hint"), t("index_level_count", formatCount(detail?.wordsIn ?: ix.wordsIn.coerceAtMost(ix.total)), formatCount(known)), pale = true)
        if (ix.modelReady || ix.readingWords) {
          LevelLine(t("index_level_meaning"), t("index_level_meaning_hint"), t("index_level_count", formatCount(if (ix.readingWords) 0 else (detail?.understood ?: ix.understood)), formatCount(known)), pale = false)
        } else {
          LevelLine(t("index_level_meaning"), t("index_level_meaning_off"), null, pale = false)
        }
        ix.reembed?.let { (done, total) -> Text(t("meter_sub_reembed", formatCount(done), formatCount(total)), style = SunType.Body, color = tk.ink) }

        // Facts.
        val facts = DetailFacts.of(ix, detail)
        if (facts.isNotEmpty()) {
          Spacer(Modifier.height(Sun.S4))
          facts.forEach { Stat(it) }
        }
        if (ix.phase == Phase.INDEXING) ix.current?.let { Stat(Fact(t("index_reading_now"), it)) }

        // Folders the system would not open: which kind of place and how to allow it, never a name.
        trawl.desktop.BlockedNotice.lines(ix.blocked).forEachIndexed { i, line ->
          if (i == 0) Spacer(Modifier.height(Sun.S4))
          Text(line, style = if (i == 0) SunType.Body else SunType.Caption, color = if (i == 0) tk.ink else tk.ink2)
        }

        // By kind.
        if (detail != null && detail.rows.isNotEmpty()) {
          Heading(t("index_by_kind"))
          KindRows(detail.rows, kindsOn)
        }

        // Waiting.
        if (detail != null) {
          Heading(t("index_waiting_title"))
          Text(DetailFacts.waitingLine(detail.waiting), style = SunType.Body, color = tk.ink)
        }

        // Failed, by reason.
        if (detail != null && detail.failed.isNotEmpty()) {
          Heading(t("index_failed_title"))
          detail.failed.forEach { g ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Warning, contentDescription = null, tint = tk.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(t(g.reason.key), style = SunType.Label, color = tk.ink, modifier = Modifier.weight(1f))
                Text(tn("index_failed_files", g.count, formatCount(g.count)), style = SunType.Body, color = tk.ink)
              }
              g.example?.let { Text(t("index_failed_example", it), style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp)) }
            }
          }
          Text(t("index_failed_reassure"), style = SunType.Caption, color = tk.ink2)
          actions.onRetryUnreadable?.let { retry -> SunOutlinedButton(onClick = retry) { Text(t("index_failed_retry"), style = SunType.Label) } }
        }

      }
      VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).heightIn(max = 760.dp))
     }
     SunDivider()
     Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S12)) {
       SunButton(onClick = onClose) { Text(t("action_done"), style = SunType.Label) }
     }
    }
  }
}

@Composable
private fun Heading(text: String) {
  Text(text, style = SunType.Heading, color = LocalSunlight.current.ink, modifier = Modifier.padding(top = Sun.S8).semantics { heading() })
}

@Composable
private fun Stat(f: Fact) {
  val tk = LocalSunlight.current
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
      Text(f.label, style = SunType.Body, color = tk.ink2, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(Sun.S12))
      Text(f.value, style = SunType.Label, color = tk.ink)
    }
    f.note?.let { Text(it, style = SunType.Caption, color = tk.ink2) }
  }
}

/** A level's name and what it gives you, with how many files are at that level. The swatch is drawn as the bar draws the level (pale or solid, ink edge), so the two read together. */
@Composable
private fun LevelLine(name: String, hint: String, count: String?, pale: Boolean) {
  val tk = LocalSunlight.current
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Box(
      Modifier.padding(top = 4.dp).size(18.dp)
        .background(if (pale) tk.marker.copy(alpha = 0.38f) else tk.marker, androidx.compose.foundation.shape.CircleShape)
        .border(2.dp, tk.line, androidx.compose.foundation.shape.CircleShape),
    )
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(name, style = SunType.Label, color = tk.ink)
      Text(hint, style = SunType.Caption, color = tk.ink2)
    }
    if (count != null) {
      Spacer(Modifier.width(Sun.S12))
      Text(count, style = SunType.Label, color = tk.ink)
    }
  }
}
