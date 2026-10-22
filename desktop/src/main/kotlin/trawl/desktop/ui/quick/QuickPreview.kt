package trawl.desktop.ui.quick

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.PlayState
import trawl.desktop.shell.QuickHit
import trawl.desktop.shell.QuickPreviewApi
import trawl.desktop.t
import trawl.desktop.ui.A11yIconButton
import trawl.desktop.ui.Icons
import trawl.desktop.ui.KeyCap
import trawl.desktop.ui.KindTile
import trawl.desktop.ui.PathText
import trawl.desktop.ui.PreviewFacts
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.SnippetText
import trawl.desktop.ui.FactRow
import trawl.desktop.ui.a11yClickable
import trawl.desktop.ui.a11yLive
import trawl.desktop.ui.kindLabel

/** What the preview draws large, chosen from the file's kind and whether the privacy blur is hiding it. Pure, so each case is a test. */
enum class PreviewShape {
  /** A picture the blur is hiding: only blurred pixels, no name, no text, no sound. */
  HIDDEN_PICTURE,
  PICTURE,
  /** A video: the frame at the moment the search matched, with a play button. */
  VIDEO,
  AUDIO,
  /** Anything with words: the matched text and the opening of the file. */
  DOCUMENT;

  companion object {
    fun of(kind: FileKind, hidden: Boolean): PreviewShape = when {
      PreviewFacts.isMedia(kind) -> if (hidden) HIDDEN_PICTURE else PICTURE
      kind == FileKind.VIDEO -> VIDEO
      kind == FileKind.AUDIO -> AUDIO
      else -> DOCUMENT
    }
  }
}

/** The words of [query] marked in [text] (case-blind), for the file's opening text. Pure. */
fun highlightTerms(text: String, query: String, style: SpanStyle): AnnotatedString {
  val words = query.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }.distinct()
  if (words.isEmpty()) return AnnotatedString(text)
  val rx = Regex(words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
  return buildAnnotatedString {
    var at = 0
    for (m in rx.findAll(text)) {
      if (m.range.first < at) continue
      append(text.substring(at, m.range.first))
      withStyle(style) { append(m.value) }
      at = m.range.last + 1
    }
    append(text.substring(at))
  }
}

/** The cap on the file's opening text drawn in the preview: a page, not the whole file. */
internal const val LEAD_CHARS = 1800

/** The largest side asked of a picture or frame. A frame is cached (a small LRU), so it is smaller than a picture. */
internal const val PICTURE_SIDE = 1400
internal const val FRAME_SIDE = 900

/**
 * The large preview beside the list: the picture, the document's opening text with the match marked, a video's frame at the moment the search
 * matched with a play button, or an audio tile with the same, then the file's details and the three actions. Nothing here opens the file:
 * Enter does that. A picture the blur is hiding is only ever asked for blurred, shows "hidden" for its name, and gets no text, frame or play.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickPreview(
  hit: QuickHit, hidden: Boolean, query: String, preview: QuickPreviewApi, mod: String,
  onOpen: () -> Unit, onFolder: () -> Unit, onFull: () -> Unit, onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tk = LocalSunlight.current
  val f = hit.file
  val shape = PreviewShape.of(f.kind, hidden)
  val player = preview.player
  // Leaving this file (or the preview) ends any sound.
  DisposableEffect(f.id) { onDispose { player?.stop() } }
  val file = remember(f.id) { preview.fileOf(f) }
  val folder = remember(f.id) { PathText.tilde(file.parent.orEmpty()) }

  Column(modifier.background(tk.paper2).semantics { contentDescription = t("quick_preview_label") }.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
      Text(
        if (hidden) t("hidden_label") else f.name, style = SunType.PanelTitle, color = tk.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      A11yIconButton(Icons.Close, t("quick_preview_close"), onClose)
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      when (shape) {
        PreviewShape.HIDDEN_PICTURE -> PictureBox(f, preview, blurred = true, note = t("quick_hidden_preview"))
        PreviewShape.PICTURE -> PictureBox(f, preview, blurred = false, note = null)
        PreviewShape.VIDEO, PreviewShape.AUDIO -> MediaBox(f, hit.startMs ?: 0L, hit.snippet, preview, shape == PreviewShape.VIDEO, Modifier.fillMaxSize())
        PreviewShape.DOCUMENT -> TextBox(hit, query, preview)
      }
    }
    if (!hidden) Column(verticalArrangement = Arrangement.spacedBy(Sun.S4)) { PreviewFacts.of(f, folder).forEach { FactRow(it) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
      SunButton(onClick = onOpen) {
        Text(t("action_open"), style = SunType.Label)
        Spacer(Modifier.width(Sun.S8))
        KeyCap("Enter")
      }
      SunOutlinedButton(onClick = onFolder) {
        Text(t("action_show_folder"), style = SunType.Label)
        Spacer(Modifier.width(Sun.S8))
        KeyCap("$mod Enter")
      }
      if (preview.full.available) SunOutlinedButton(onClick = onFull, enabled = !hidden) {
        Text(t("quick_full_preview"), style = SunType.Label)
        Spacer(Modifier.width(Sun.S8))
        KeyCap("$mod Shift Y")
      }
    }
  }
}

private fun frame(modifier: Modifier, tk: com.craneai.findr.ui.theme.SunlightTokens) =
  modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(tk.paper).border(Sun.Hairline, tk.line2, RoundedCornerShape(10.dp))

/** A picture, drawn to fit its box. [blurred] pictures only ever come back blurred. */
@Composable
private fun PictureBox(f: IndexedFile, preview: QuickPreviewApi, blurred: Boolean, note: String?) {
  val tk = LocalSunlight.current
  val bmp = produceState<Loaded>(Loaded.Wait, f.id, blurred) { value = Loaded.of(preview.picture(f, PICTURE_SIDE, blurred)) }.value
  Box(frame(Modifier, tk), contentAlignment = Alignment.Center) {
    when (bmp) {
      is Loaded.Ready -> Image(
        bmp.image, contentDescription = if (blurred) t("hidden_label") else f.name, modifier = Modifier.fillMaxSize().padding(Sun.S4), contentScale = ContentScale.Fit,
      )
      Loaded.Empty -> Note(Icons.Photo, t("preview_cannot_show"))
      Loaded.Wait -> Text(t("quick_preview_loading"), style = SunType.PanelDetail, color = tk.ink2)
    }
    if (note != null && bmp is Loaded.Ready) {
      Text(
        note, style = SunType.PanelDetail, color = tk.ink, textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).padding(Sun.S8).clip(RoundedCornerShape(8.dp)).background(tk.paper).padding(horizontal = Sun.S12, vertical = Sun.S4),
      )
    }
  }
}

/**
 * A video's frame at the moment the search matched (or the start), or an audio tile; both with the play-from-here bar. Shared by the quick
 * panel and the main window's preview, so a file looks and plays the same in both.
 */
@Composable
fun MediaBox(f: IndexedFile, from: Long, snippetText: String?, preview: QuickPreviewApi, video: Boolean, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val frameBmp = produceState<Loaded>(Loaded.Wait, f.id, from) {
    value = if (video) Loaded.of(preview.frame(f, from, FRAME_SIDE)) else Loaded.Empty
  }.value
  Column(modifier, verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Box(frame(Modifier.weight(1f), tk), contentAlignment = Alignment.Center) {
      when {
        frameBmp is Loaded.Ready -> Image(
          frameBmp.image, contentDescription = t("quick_frame_at", formatDuration(from)), modifier = Modifier.fillMaxSize().padding(Sun.S4), contentScale = ContentScale.Fit,
        )
        video && frameBmp == Loaded.Wait -> Text(t("quick_preview_loading"), style = SunType.PanelDetail, color = tk.ink2)
        else -> KindTile(f.kind, 72.dp)
      }
    }
    val snippet = snippetText?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
    if (snippet != null) SnippetText(snippet, lines = 3, style = SunType.PanelDetail)
    PlayBar(preview, f, from)
  }
}

/** Play from a moment, with what it is doing said in words. Hidden when this computer has no player. */
@Composable
fun PlayBar(preview: QuickPreviewApi, f: IndexedFile, from: Long) {
  val tk = LocalSunlight.current
  val player = preview.player ?: return
  val state by player.state.collectAsState()
  val playing = state is PlayState.Loading || state is PlayState.Playing
  val file = remember(f.id) { preview.fileOf(f) }
  val status = when (val s = state) {
    PlayState.Idle -> ""
    PlayState.Loading -> t("quick_play_loading")
    is PlayState.Playing -> t("quick_play_playing", formatDuration(s.fromMs), formatDuration(s.elapsedMs))
    PlayState.Done -> t("quick_play_done")
    PlayState.Failed -> t("quick_play_failed")
  }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
    val label = if (playing) t("quick_play_stop") else t("quick_play_from", formatDuration(from))
    Row(
      Modifier.clip(RoundedCornerShape(24.dp)).background(tk.paper).border(Sun.Hairline, tk.line2, RoundedCornerShape(24.dp))
        .a11yClickable(label, RoundedCornerShape(24.dp)) { if (playing) player.stop() else player.play(file, from) }
        .padding(horizontal = Sun.S16, vertical = Sun.S8),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    ) {
      Icon(if (playing) Icons.Pause else Icons.Play, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
      Text(label, style = SunType.Label, color = tk.ink)
    }
    Text(status, style = SunType.PanelDetail, color = tk.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).a11yLive())
  }
}

/** A document: the matched passage with the match marked, then the opening of the file with the query's words marked. */
@Composable
private fun TextBox(hit: QuickHit, query: String, preview: QuickPreviewApi) {
  val tk = LocalSunlight.current
  val f = hit.file
  val snippet = hit.snippet?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
  val lead = produceState<Loaded.Lead?>(null, f.id) { value = Loaded.Lead(preview.lead(f)?.trim()?.take(LEAD_CHARS)?.takeIf { it.isNotBlank() }) }.value
  Box(frame(Modifier, tk)) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
      if (snippet != null) {
        Text(t("quick_matched_text"), style = SunType.CaptionBold, color = tk.ink2)
        SnippetText(snippet, lines = 12, style = SunType.BodyLarge)
      }
      val body = lead?.text
      when {
        lead == null -> if (snippet == null) Text(t("quick_preview_loading"), style = SunType.PanelDetail, color = tk.ink2)
        body != null && body != snippet -> {
          if (snippet != null) Text(t("quick_file_start"), style = SunType.CaptionBold, color = tk.ink2, modifier = Modifier.padding(top = Sun.S4))
          Text(highlightTerms(body, query, SpanStyle(background = tk.marker, color = tk.onMarker)), style = SunType.BodyLarge, color = tk.ink)
        }
        snippet == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
          KindTile(f.kind, 48.dp)
          Text(t("quick_no_text"), style = SunType.Body, color = tk.ink2)
        }
      }
    }
  }
}

@Composable
private fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
  val tk = LocalSunlight.current
  Column(Modifier.padding(Sun.S24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Icon(icon, contentDescription = null, tint = tk.ink2, modifier = Modifier.size(32.dp))
    Text(text, style = SunType.Body, color = tk.ink2, textAlign = TextAlign.Center)
  }
}

/** A picture on its way: not asked yet, asked and empty, or here. */
private sealed interface Loaded {
  data object Wait : Loaded
  data object Empty : Loaded
  class Ready(val image: ImageBitmap) : Loaded
  class Lead(val text: String?)

  companion object { fun of(b: ImageBitmap?): Loaded = if (b == null) Empty else Ready(b) }
}
