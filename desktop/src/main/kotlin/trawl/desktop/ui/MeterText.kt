package trawl.desktop.ui

import com.craneai.findr.ui.common.Eta
import com.craneai.findr.ui.common.etaOf
import com.craneai.findr.ui.common.formatCount
import trawl.desktop.Hold
import trawl.desktop.IndexUi
import trawl.desktop.Phase
import trawl.desktop.t
import trawl.desktop.tn

/** What the status bar says about reading, in the phone's words: a title, a second line, and how it should look. Pure, so every state is a test. */
data class MeterLine(
  val title: String,
  val sub: String,
  /** Names and words only because the models are not installed: a warning, not progress. */
  val namesOnly: Boolean,
  /** Something the person may need to act on (an error, or the models missing): drawn as a warning. */
  val warning: Boolean,
  val error: Boolean,
  /** Reading is under way, so a bar and Pause make sense. */
  val working: Boolean,
  /** The bar's length, 0..1, or null for an unknown length. Null as well when there is no bar. */
  val fraction: Float?,
  val showBar: Boolean,
  /** "3 photos, 12 documents" while reading. */
  val kinds: String?,
)

object MeterText {
  fun etaText(ms: Long): String = when (val e = etaOf(ms / 1000)) {
    Eta.UnderAMinute -> t("meter_eta_under_minute")
    is Eta.Minutes -> t("meter_eta_minutes", e.minutes)
    is Eta.Hours -> t("meter_eta_hours", e.low, e.high)
  }

  fun of(ix: IndexUi): MeterLine {
    // The words come from the shared meter text (ui/index), so this line and the meter card never disagree; this adds the compact line's own flags.
    val namesOnly = !ix.modelReady && !ix.modelLoading && !ix.readingWords && (ix.phase == Phase.INDEXING || ix.phase == Phase.UP_TO_DATE)
    val shared = trawl.desktop.ui.index.MeterTexts.of(ix)
    val title = shared.title
    val sub = shared.sub
    val working = ix.phase == Phase.INDEXING && !namesOnly
    val showBar = ix.phase == Phase.SCANNING || working
    val fraction = when {
      ix.phase == Phase.SCANNING -> null
      working && ix.modelLoading && !ix.readingWords -> null
      working -> com.craneai.findr.ui.common.fraction(ix.understood, ix.total)
      else -> null
    }
    val kinds = if (ix.phase == Phase.INDEXING && ix.photos + ix.documents + ix.videos + ix.recordings > 0) {
      listOfNotNull(
        tn("count_photos", ix.photos), tn("count_documents", ix.documents),
        tn("count_videos", ix.videos).takeIf { ix.videos > 0 }, tn("count_recordings", ix.recordings).takeIf { ix.recordings > 0 },
      ).reduce { a, b -> t("meter_kinds", a, b) }
    } else null
    return MeterLine(
      title = title, sub = sub, namesOnly = namesOnly, warning = shared.bad, error = ix.phase == Phase.ERROR,
      working = working, fraction = fraction, showBar = showBar, kinds = kinds,
    )
  }
}
