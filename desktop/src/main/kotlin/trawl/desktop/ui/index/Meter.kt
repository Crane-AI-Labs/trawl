package trawl.desktop.ui.index

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.ui.common.Eta
import com.craneai.findr.ui.common.etaOf
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.DiskSpace
import trawl.desktop.Hold
import trawl.desktop.IndexUi
import trawl.desktop.Phase
import trawl.desktop.Platform
import trawl.desktop.Scope
import trawl.desktop.Os
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.sunFocus

/** What the meter's buttons do. A nullable action is a button the caller does not offer. */
class IndexActions(
  val onPause: (() -> Unit)? = null,
  val onResume: (() -> Unit)? = null,
  val onReadOnBattery: (() -> Unit)? = null,
  val onRetryOpen: (() -> Unit)? = null,
  val onRetryUnreadable: (() -> Unit)? = null,
)

/**
 * The two levels the meter shows, in numbers: [searchable] (the pale level: the file's name and words are in) and [understood] (the
 * solid level: its meaning is in too). Both are fractions of the files kept; the solid one is never longer than the pale one, and with
 * no model it is empty, because nothing is understood by meaning yet.
 */
data class Levels(val searchable: Float, val understood: Float) {
  val searchablePercent get() = (searchable * 100).toInt()
  val understoodPercent get() = (understood * 100).toInt()

  companion object {
    fun of(ix: IndexUi): Levels {
      if (ix.total <= 0) return Levels(0f, 0f)
      val total = ix.total.toFloat()
      val meaning = if (ix.readingWords || !ix.modelReady) 0 else ix.understood
      val words = maxOf(ix.wordsIn, if (ix.readingWords) ix.understood else 0, meaning)
      return Levels((words / total).coerceIn(0f, 1f), (meaning / total).coerceIn(0f, 1f))
    }
  }
}

/** The title and the second line of the meter for the state [ix] is in: the phone's words, plus the desktop's own reasons. Pure, so a test can read every state. */
data class MeterText(val title: String, val sub: String, val bad: Boolean)

object MeterTexts {
  fun of(ix: IndexUi, os: Os = Platform.os): MeterText {
    // Reading the words with no model is work in progress (it has its own title); "names and words only" is the state after it.
    val namesOnly = !ix.modelReady && !ix.modelLoading && !ix.readingWords && (ix.phase == Phase.INDEXING || ix.phase == Phase.UP_TO_DATE)
    val eta = ix.etaMs
    val reembed = ix.reembed
    val title = when {
      ix.openProblem != null -> t("open_problem_title", t("app_name"))
      ix.opening -> t("index_opening")
      ix.phase == Phase.ERROR -> t("meter_title_error")
      ix.phase == Phase.NOT_STARTED -> t("meter_title_not_started")
      ix.phase == Phase.PAUSED -> when (ix.hold) {
        Hold.BATTERY -> t("pause_not_charging")
        Hold.STORAGE_LOW -> t("pause_storage_low")
        Hold.NO_PERMISSION -> t("pause_no_permission")
        else -> t("pause_user")
      }
      ix.phase == Phase.SCANNING -> t("meter_title_scanning")
      namesOnly -> t("meter_title_waiting_models")
      ix.phase == Phase.INDEXING && ix.readingWords -> t("meter_title_words")
      ix.phase == Phase.INDEXING -> t("meter_title_indexing")
      else -> t("meter_title_up_to_date")
    }
    val sub = when {
      ix.openProblem != null -> t("open_problem_detail")
      ix.opening -> t("index_opening_detail")
      ix.phase == Phase.ERROR -> ix.error ?: ""
      ix.phase == Phase.NOT_STARTED -> ""
      ix.phase == Phase.PAUSED -> when (ix.hold) {
        Hold.BATTERY -> t("pause_not_charging_detail")
        Hold.STORAGE_LOW -> t("pause_storage_low_detail")
        Hold.NO_PERMISSION -> t(if (os == Os.MAC) "pause_no_permission_detail_mac" else "pause_no_permission_detail")
        else -> t("pause_user_detail")
      }
      ix.phase == Phase.SCANNING -> tn("meter_sub_scanning", ix.scanned, formatCount(ix.scanned))
      namesOnly -> t("meter_sub_waiting_models")
      reembed != null && ix.phase == Phase.INDEXING -> t("meter_sub_reembed", formatCount(reembed.first), formatCount(reembed.second))
      ix.phase == Phase.INDEXING && ix.readingWords && eta != null -> t("meter_sub_words_eta", formatCount(ix.understood), formatCount(ix.total), etaText(eta))
      ix.phase == Phase.INDEXING && ix.readingWords -> t("meter_sub_words", formatCount(ix.understood), formatCount(ix.total))
      ix.phase == Phase.INDEXING && ix.modelLoading -> t("meter_sub_names_first")
      ix.phase == Phase.INDEXING && eta != null -> t("meter_sub_progress_eta", formatCount(ix.understood), formatCount(ix.total), etaText(eta))
      ix.phase == Phase.INDEXING -> t("meter_sub_progress", formatCount(ix.understood), formatCount(ix.total))
      ix.unreadable > 0 -> tn("index_ready_unreadable", ix.unreadable, formatCount(ix.unreadable))
      else -> tn("meter_sub_up_to_date", ix.total, formatCount(ix.total))
    }
    val bad = ix.phase == Phase.ERROR || ix.openProblem != null || namesOnly ||
      (ix.phase == Phase.PAUSED && (ix.hold == Hold.STORAGE_LOW || ix.hold == Hold.NO_PERMISSION))
    return MeterText(title, sub, bad)
  }

  /** The card's lead icon: a check when up to date, a warning when something is wrong, a pause for a pause the person (or the battery) chose, else the magnifier. Pure. */
  fun icon(ix: IndexUi, text: MeterText): androidx.compose.ui.graphics.vector.ImageVector = when {
    ix.phase == Phase.UP_TO_DATE && !text.bad && !ix.opening -> Icons.Check
    text.bad -> Icons.Warning
    ix.phase == Phase.PAUSED -> Icons.Pause
    else -> Icons.Search
  }

  /** The reason line of the calm card, one sentence per class of failure (the phone's wording). */
  fun problem(p: IndexOpenProblem): String = t(
    when (p) {
      IndexOpenProblem.BUSY -> "open_problem_busy"
      IndexOpenProblem.STORAGE_FULL -> "open_problem_storage_full"
      IndexOpenProblem.IO -> "open_problem_io"
      IndexOpenProblem.MEMORY -> "open_problem_memory"
      IndexOpenProblem.OTHER -> "open_problem_other"
    },
  )

  fun etaText(ms: Long): String = when (val e = etaOf(ms / 1000)) {
    Eta.UnderAMinute -> t("meter_eta_under_minute")
    is Eta.Minutes -> t("meter_eta_minutes", e.minutes)
    is Eta.Hours -> t("meter_eta_hours", e.low, e.high)
  }

  /** The disk line, when the disk is getting low but reading goes on; null otherwise. [growthBytes] is how much more room the index still needs, when known. */
  fun diskWarning(ix: IndexUi, growthBytes: Long = 0L): String? {
    if (ix.disk != DiskSpace.Level.WARN) return null
    val free = ix.freeBytes ?: return null
    return if (growthBytes > 0) t("disk_warn_need", formatBytes(free), formatBytes(growthBytes)) else t("disk_warn", formatBytes(free))
  }
}

/**
 * The bar: pale for what is searchable by name and words, solid for what is understood by meaning, both under the same ink edge so the
 * length says how far, not the colour alone. [searchable] and [understood] are fractions; null [searchable] draws the quarter-bar sliver
 * of "working, length unknown".
 */
@Composable
fun TwoLevelBar(searchable: Float?, understood: Float, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val pale = tk.marker.copy(alpha = 0.38f)
  Box(modifier.fillMaxWidth().heightIn(min = 16.dp).clip(CircleShape).background(tk.paper).border(Sun.Border, tk.line, CircleShape)) {
    val words = (searchable ?: 0.04f).coerceIn(0.04f, 1f)
    Box(Modifier.fillMaxWidth(words).heightIn(min = 16.dp).clip(CircleShape).background(pale))
    if (searchable != null && understood > 0f) {
      Box(Modifier.fillMaxWidth(understood.coerceIn(0.04f, words)).heightIn(min = 16.dp).clip(CircleShape).background(tk.marker))
    }
  }
}

/** The meter as the main screen uses it: reads the index, and opens the detail panel when tapped. */
@Composable
fun IndexMeter(c: AppController) {
  val ix by c.index.collectAsState()
  val settings by c.settings.collectAsState()
  IndexMeter(
    ix, detail = { c.service.detail() }, kindsOn = Scope.enabledKinds(settings),
    actions = IndexActions(
      onPause = { c.setPaused(true) }, onResume = { c.setPaused(false) },
      onReadOnBattery = { c.setSpeed(com.craneai.findr.core.LearningSpeed.BALANCED) },
      onRetryOpen = { c.service.retryOpen() }, onRetryUnreadable = { c.service.retryUnreadable() },
    ),
  )
}

/** The indexing meter: one card that says what the app is doing and how far it has got. Tap it (or Details) for the panel behind it. */
@Composable
fun IndexMeter(ix: IndexUi, detail: () -> trawl.desktop.IndexAdmin.Detail?, actions: IndexActions, kindsOn: Set<FileKind>? = null, startOpen: Boolean = false) {
  if (ix.openProblem != null) {
    OpenProblemCard(ix.openProblem, actions.onRetryOpen)
    return
  }
  val tk = LocalSunlight.current
  var open by remember { mutableStateOf(startOpen) }
  val text = MeterTexts.of(ix)
  val levels = Levels.of(ix)
  val working = ix.phase == Phase.SCANNING || ix.phase == Phase.INDEXING
  val twoLevels = ix.modelReady && !ix.readingWords
  val shape = MaterialTheme.shapes.medium
  val a11y = t("index_levels_a11y", text.title, levels.searchablePercent, levels.understoodPercent)

  SunCard(
    Modifier.fillMaxWidth().sunFocus(shape).pointerHoverIcon(PointerIcon.Hand)
      .clickable(role = Role.Button, onClickLabel = t("index_detail_open_a11y")) { open = true }.semantics { contentDescription = a11y },
    color = if (text.bad) tk.paper else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(Modifier.padding(horizontal = Sun.S16, vertical = Sun.S12), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          MeterTexts.icon(ix, text),
          contentDescription = null, tint = if (ix.phase == Phase.ERROR) tk.danger else tk.ink, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text.title, style = SunType.Label, color = tk.ink, modifier = Modifier.weight(1f))
        if (ix.phase == Phase.INDEXING && ix.photos + ix.documents + ix.videos + ix.recordings > 0) {
          val parts = listOfNotNull(
            tn("count_photos", ix.photos), tn("count_documents", ix.documents),
            tn("count_videos", ix.videos).takeIf { ix.videos > 0 }, tn("count_recordings", ix.recordings).takeIf { ix.recordings > 0 },
          )
          Text(parts.reduce { a, b -> t("meter_kinds", a, b) }, style = SunType.Caption, color = tk.ink2)
          Spacer(Modifier.width(Sun.S8))
        }
        SunTextButton(onClick = { open = true }) { Text(t("index_detail_open"), style = SunType.Label) }
      }
      if (text.sub.isNotEmpty()) Text(text.sub, style = SunType.Body, color = tk.ink)
      MeterTexts.diskWarning(ix)?.let { line ->
        Row(verticalAlignment = Alignment.Top) {
          Icon(Icons.Warning, contentDescription = null, tint = tk.danger, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(6.dp))
          Text(line, style = SunType.Caption, color = tk.ink)
        }
      }
      if (ix.phase == Phase.PAUSED && ix.hold != Hold.STORAGE_LOW && ix.hold != Hold.NO_PERMISSION && ix.total > 0) {
        Text(t("meter_sub_progress", formatCount(ix.understood), formatCount(ix.total)), style = SunType.Caption, color = tk.ink2)
      }
      if (ix.phase == Phase.INDEXING && actions.onPause != null && !(!ix.modelReady && !ix.modelLoading && !ix.readingWords)) {
        SunTextButton(onClick = actions.onPause) { Text(t("action_pause"), style = SunType.Label) }
      }
      if (ix.phase == Phase.PAUSED) {
        Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          if (ix.hold == Hold.USER && actions.onResume != null) SunOutlinedButton(onClick = actions.onResume) { Text(t("action_resume"), style = SunType.Label) }
          if (ix.hold == Hold.BATTERY && actions.onReadOnBattery != null) SunOutlinedButton(onClick = actions.onReadOnBattery) { Text(t("action_read_on_battery"), style = SunType.Label) }
        }
      }
      if (ix.phase == Phase.SCANNING) {
        TwoLevelBar(null, 0f)
      } else if (working || (ix.phase == Phase.PAUSED && ix.total > 0)) {
        TwoLevelBar(if (ix.modelLoading && !ix.readingWords && levels.searchable == 0f) null else levels.searchable, if (twoLevels) levels.understood else 0f)
        if (twoLevels) Text(t("index_levels_caption", levels.searchablePercent, levels.understoodPercent), style = SunType.Caption, color = tk.ink2)
        if (ix.phase == Phase.INDEXING) ix.current?.let { Text(t("meter_current", it), style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
      }
    }
  }
  if (open) IndexDetailDialog(ix, detail(), actions, kindsOn) { open = false }
}
