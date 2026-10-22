package trawl.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.theme.LocalSunlight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import trawl.desktop.AppController
import trawl.desktop.ResultRow
import trawl.desktop.t
import trawl.desktop.privacy.LocalPreviewBlur
import trawl.desktop.ui.privacy.LocalPrivacyUi
import trawl.desktop.ui.privacy.PrivateImage
import trawl.desktop.ui.privacy.RevealEye
import trawl.desktop.ui.privacy.rememberLeave

/** One labelled fact in the preview ("Size", "12 MB"). */
data class Fact(val label: String, val value: String)

object PreviewFacts {
  fun date(millis: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(millis).atZone(zone))

  fun isMedia(kind: FileKind) = kind == FileKind.IMAGE || kind == FileKind.SCREENSHOT

  /** The facts under the preview, in reading order. Pure so each kind of file is a test. */
  fun of(file: IndexedFile, folder: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): List<Fact> = buildList {
    if (folder.isNotBlank()) add(Fact(t("fact_folder"), folder))
    add(Fact(t("fact_size"), formatBytes(file.sizeBytes)))
    val taken = file.dateMillis != file.modifiedMillis && (isMedia(file.kind) || file.kind == FileKind.VIDEO)
    if (file.dateMillis > 0) add(Fact(t(if (taken) "fact_taken" else "fact_modified"), date(file.dateMillis, locale, zone)))
    val type = listOfNotNull(kindLabel(file.kind), file.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) }).joinToString("  ·  ")
    add(Fact(t("fact_kind"), type))
    val w = file.width
    val h = file.height
    if (w != null && h != null && w > 0 && h > 0) add(Fact(t("fact_dimensions"), "$w × $h"))
  }
}

/**
 * The right-hand pane: what is selected, large. A picture is drawn to fit, a document shows the text that matched, and under it the facts
 * and the three actions (which live here, not on every row). The whole pane scrolls if the window is short.
 *
 * A picture that looks private is blurred (PreviewMedia) with an eye under it and its name replaced by "hidden"; a sensitive file gets a
 * Privacy block under the facts with the mark/unmark action. Quick Look reuses PreviewMedia, so it follows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewPane(row: ResultRow?, browsing: Boolean, c: AppController, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Box(modifier.background(tk.paper2).semantics { contentDescription = t("preview_label") }) {
    if (row == null) {
      Column(Modifier.fillMaxSize().padding(Sun.S24), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Search, contentDescription = null, tint = tk.ink2, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(Sun.S12))
        Text(t("preview_empty_title"), style = SunType.Heading, color = tk.ink)
        Spacer(Modifier.height(Sun.S4))
        Text(t("preview_empty_body"), style = SunType.Body, color = tk.ink2)
      }
      return@Box
    }
    val f = row.file
    val blur = LocalPreviewBlur.current
    val privacyUi = LocalPrivacyUi.current
    val leave = rememberLeave()
    val hidden = blur.hides(f)
    val folder = remember(f.id) { PathText.tilde(c.fileOf(f).parent.orEmpty()) }
    // The shield for screenshots and screen sharing follows what is on screen: a sensitive file, or a private-looking picture that was shown.
    val sensitiveShown = f.sensitive || (blur.looksPrivate(f) && !hidden)
    androidx.compose.runtime.DisposableEffect(f.id, sensitiveShown) {
      c.privacy.setSensitiveOnScreen("preview", sensitiveShown)
      onDispose { c.privacy.setSensitiveOnScreen("preview", false) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val mediaHeight = (maxHeight * 0.5f).coerceIn(200.dp, 560.dp)
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Sun.S20), verticalArrangement = Arrangement.spacedBy(Sun.S16)) {
        PreviewMedia(row, c, mediaHeight, side = 1400, excerptLines = 10)
        RevealEye(f, blur)
        Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
          SelectionContainer {
            Text(if (hidden) t("hidden_label") else f.name, style = SunType.Heading, color = tk.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
          }
          RowBadges(row)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
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
          SunOutlinedButton(onClick = { leave(f.id) { c.copyPath(f) } }) {
            Icon(Icons.Copy, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
            Spacer(Modifier.width(Sun.S8))
            Text(t("action_copy_path"), style = SunType.Label)
          }
        }
        SunDivider()
        Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
          PreviewFacts.of(f, folder).forEach { FactRow(it) }
          FactRow(Fact(t("fact_match"), whyText(row.reason, browsing)))
        }
        // Privacy: what makes this file private, and the way to change it. Shown only when there is something to say.
        val why = when {
          f.markedSensitive -> t("fact_privacy_marked")
          f.sensitive -> t("fact_privacy_folder")
          blur.looksPrivate(f) -> t("fact_privacy_looks")
          else -> null
        }
        if (why != null || privacyUi != null && !f.sensitive) {
          SunDivider()
          Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
            if (why != null) FactRow(Fact(t("fact_privacy"), why))
            if (privacyUi != null && (f.markedSensitive || !f.sensitive)) {
              SunOutlinedButton(onClick = { privacyUi.mark(listOf(f.id), !f.markedSensitive) }) {
                Text(if (f.markedSensitive) t("sensitive_unmark") else t("sensitive_mark"), style = SunType.Label)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun FactRow(fact: Fact) {
  val tk = LocalSunlight.current
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
    Text(fact.label, style = SunType.Caption, color = tk.ink2, modifier = Modifier.width(84.dp).padding(top = 2.dp))
    SelectionContainer(Modifier.weight(1f)) { Text(fact.value, style = SunType.Body, color = tk.ink) }
  }
}

/**
 * The large picture or text of a result, shared by the preview pane and Quick Look. A picture is drawn to fit its box (never cropped, never
 * stretched); a picture this computer cannot decode says so; a video is a labelled placeholder (the file is opened to watch it); a document
 * shows the matched text with the match marked.
 */
@Composable
fun PreviewMedia(row: ResultRow, c: AppController, height: Dp?, side: Int, excerptLines: Int, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val f = row.file
  val shape = RoundedCornerShape(10.dp)
  val frame = modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier).clip(shape).background(tk.paper).border(Sun.Hairline, tk.line2, shape)
  when {
    // A hidden picture: only its blurred pixels are ever decoded (PrivateImage), and its name is not in the description.
    PreviewFacts.isMedia(f.kind) && LocalPreviewBlur.current.hides(f) -> Box(frame, contentAlignment = Alignment.Center) {
      PrivateImage(
        f, c.fileOf(f), side, Modifier.fillMaxSize().padding(Sun.S4), ContentScale.Fit, contentDescription = t("hidden_label"),
        placeholder = { ThumbPlaceholder(Icons.Photo, 32.dp) },
      )
    }
    PreviewFacts.isMedia(f.kind) -> {
      val thumb = rememberThumb(c.fileOf(f), side)
      Box(frame, contentAlignment = Alignment.Center) {
        val b = thumb.bitmap
        when {
          b != null -> Image(b, contentDescription = f.name, modifier = Modifier.fillMaxSize().padding(Sun.S4), contentScale = ContentScale.Fit)
          thumb.done -> Note(Icons.Photo, t("preview_cannot_show"))
          else -> ThumbPlaceholder(Icons.Photo, 32.dp)
        }
      }
    }
    // A video or a recording: its frame at the moment the search matched (a recording gets a tile), the words that matched, and a button that
    // plays from there. The same drawing as the quick panel's preview, so a file looks and plays the same in both.
    (f.kind == FileKind.VIDEO || f.kind == FileKind.AUDIO) -> {
      val preview = remember(c) { trawl.desktop.shell.ControllerPreview(c, player = c.previewPlayer) }
      androidx.compose.runtime.DisposableEffect(f.id) { onDispose { c.previewPlayer.stop() } }
      trawl.desktop.ui.quick.MediaBox(
        f, row.startMs ?: 0L, cleanSnippet(row), preview, f.kind == FileKind.VIDEO,
        modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier.fillMaxSize()),
      )
    }
    else -> {
      // The text shown: the match when there is one, else the file's opening text as it was indexed (the Recent view has no match to quote).
      // [lead] is null while it is being read, so "No text to show" is only ever said once the index has answered.
      val quoted = remember(row) { cleanSnippet(row) }
      val lead by androidx.compose.runtime.produceState<Lead?>(null, f.id, quoted) { value = if (quoted != null) Lead(null) else Lead(c.leadText(f)) }
      val text = quoted ?: lead?.text
      run {
        // A page of text is as tall as its words (up to the box it was given), not a tall empty card with one line in it. Quick Look has no
        // height of its own (it fills the space it is offered), so it keeps the full frame.
        val textFrame = if (height != null) modifier.fillMaxWidth().heightIn(min = 120.dp, max = height).clip(shape).background(tk.paper).border(Sun.Hairline, tk.line2, shape) else frame
        Box(textFrame) {
          Column(Modifier.then(if (height != null) Modifier else Modifier.fillMaxSize()).verticalScroll(rememberScrollState()).padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
            KindTile(f.kind, 36.dp)
            if (text != null) SnippetText(text, lines = excerptLines, style = SunType.BodyLarge)
            else if (lead != null) Text(t("preview_no_snippet"), style = SunType.Body, color = tk.ink2)
          }
        }
      }
    }
  }
}

/** The opening text of a file ([text] null when it has none); the holder tells "still looking" (no holder) from "looked, nothing". */
private class Lead(val text: String?)

@Composable
private fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
  val tk = LocalSunlight.current
  Column(Modifier.padding(Sun.S24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Icon(icon, contentDescription = null, tint = tk.ink2, modifier = Modifier.size(32.dp))
    Text(text, style = SunType.Body, color = tk.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
  }
}
