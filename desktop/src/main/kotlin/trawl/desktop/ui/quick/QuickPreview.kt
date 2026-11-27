package trawl.desktop.ui.quick

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import trawl.desktop.ui.KindTile
import trawl.desktop.ui.PathText
import trawl.desktop.ui.PreviewAction
import trawl.desktop.ui.PreviewActionGrid
import trawl.desktop.ui.PreviewFacts
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunType
import trawl.desktop.ui.SnippetText
import trawl.desktop.ui.FactRow
import trawl.desktop.ui.a11yClickable
import trawl.desktop.ui.a11yLive

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
@Composable
fun QuickPreview(
  hit: QuickHit, hidden: Boolean, query: String, preview: QuickPreviewApi, mod: String,
  onOpen: () -> Unit, onFolder: () -> Unit, onFull: () -> Unit, onClose: () -> Unit,
  modifier: Modifier = Modifier,
  onCopy: (() -> Unit)? = null,
) {
  val tk = LocalSunlight.current
  val f = hit.file
  val shape = PreviewShape.of(f.kind, hidden)
  val player = preview.player
  // Leaving this file (or the preview) ends any sound; switching files never auto-plays.
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
      key(f.id) {
        when (shape) {
          PreviewShape.HIDDEN_PICTURE -> PictureBox(f, preview, blurred = true, note = t("quick_hidden_preview"))
          PreviewShape.PICTURE -> PictureBox(f, preview, blurred = false, note = null)
          PreviewShape.VIDEO, PreviewShape.AUDIO -> MediaBox(f, hit.startMs ?: 0L, hit.snippet, preview, shape == PreviewShape.VIDEO, Modifier.fillMaxSize(), onOpen = onOpen)
          PreviewShape.DOCUMENT -> TextBox(hit, query, preview)
        }
      }
    }
    if (!hidden) Column(verticalArrangement = Arrangement.spacedBy(Sun.S4)) { PreviewFacts.of(f, folder).forEach { FactRow(it) } }
    val actions = buildList {
      add(PreviewAction(label = t("action_open"), keyCap = "Enter", primary = true, onClick = onOpen))
      add(PreviewAction(label = t("action_show_folder"), keyCap = "$mod Enter", onClick = onFolder))
      if (onCopy != null && !hidden) {
        add(PreviewAction(label = t("action_copy"), keyCap = "$mod C", icon = Icons.Copy, onClick = onCopy))
      }
      if (preview.full.available) {
        add(PreviewAction(label = t("quick_full_preview"), keyCap = "$mod Shift Y", enabled = !hidden, onClick = onFull))
      }
    }
    PreviewActionGrid(actions)
  }
}

private fun frame(modifier: Modifier, tk: com.craneai.findr.ui.theme.SunlightTokens) =
  modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(tk.paper).border(Sun.Hairline, tk.line2, RoundedCornerShape(10.dp))

/** A picture, drawn to fit its box. [blurred] pictures only ever come back blurred. */
@Composable
private fun PictureBox(f: IndexedFile, preview: QuickPreviewApi, blurred: Boolean, note: String?) {
  val tk = LocalSunlight.current
  // Keyed mutableStateOf (never produceState!) guarantees switching files or toggling blur drops any prior bitmap immediately.
  var bmp by remember(f.id, blurred, f.modifiedMillis) { mutableStateOf<Loaded>(Loaded.Wait) }
  LaunchedEffect(f.id, blurred, f.modifiedMillis) {
    val loaded = preview.picture(f, PICTURE_SIDE, blurred)
    currentCoroutineContext().ensureActive()
    bmp = Loaded.of(loaded)
  }
  Box(frame(Modifier, tk), contentAlignment = Alignment.Center) {
    when (val shown = bmp) {
      is Loaded.Ready -> Image(
        shown.image, contentDescription = if (blurred) t("hidden_label") else f.name, modifier = Modifier.fillMaxSize().padding(Sun.S4), contentScale = ContentScale.Fit,
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
 * A video's live frame synced to the playback/scrub position (or an audio tile), paired with an interactive timeline scrubber showing
 * start/end duration, a match-moment marker pin, and a live moving playhead. Shared by the quick panel and the main window's preview.
 */
@Composable
fun MediaBox(
  f: IndexedFile,
  from: Long,
  snippetText: String?,
  preview: QuickPreviewApi,
  video: Boolean,
  modifier: Modifier = Modifier,
  onOpen: (() -> Unit)? = null,
) {
  val tk = LocalSunlight.current
  val player = preview.player
  // Switching files stops any active audio/video playback immediately and leaves the next file Idle until Play is clicked.
  DisposableEffect(f.id, player) { onDispose { player?.stop() } }
  val file = remember(f.id) { preview.fileOf(f) }
  val rawPlayState: PlayState = if (player != null) player.state.collectAsState().value else PlayState.Idle
  val playerFile = if (player != null) player.activeFile.collectAsState().value else null
  // Ignore any playback state belonging to a different file (eliminates even 1-frame state bleed on rapid switching).
  val playState: PlayState = if (playerFile != null && playerFile.absolutePath == file.absolutePath) rawPlayState else PlayState.Idle
  val durationMs = (f.durationMs ?: 0L).coerceAtLeast(0L)
  var seekMs by remember(f.id, from) { mutableStateOf(from.coerceAtLeast(0L)) }
  var userSeeked by remember(f.id, from) { mutableStateOf(false) }
  val activeMs = when (val s = playState) {
    is PlayState.Playing -> (s.fromMs + s.elapsedMs).let { pos -> if (durationMs > 0L) pos.coerceIn(0L, durationMs) else pos }
    else -> seekMs
  }
  val playing = playState is PlayState.Loading || playState is PlayState.Playing
  // 1 s cadence while playing (with monotonic guard so a slow decode never jumps backwards); exact 250 ms bucket on seek.
  val stepMs = if (playing) 1_000L else 250L
  val frameBucket = (activeMs / stepMs) * stepMs
  var frameLoaded by remember(f.id, f.modifiedMillis) { mutableStateOf<Loaded>(if (video) Loaded.Wait else Loaded.Empty) }
  var lastFrameMs by remember(f.id, f.modifiedMillis) { mutableStateOf(-1L) }
  val latestBucket = androidx.compose.runtime.rememberUpdatedState(frameBucket)
  val latestPlaying = androidx.compose.runtime.rememberUpdatedState(playing)
  // Reset monotonic frame guard whenever playback starts fresh so replaying from an earlier timestamp never freezes at the old end frame.
  LaunchedEffect(f.id, playing) {
    if (playing) lastFrameMs = -1L
  }
  // Conflated snapshotFlow lets slow (>1s) 4K HEVC/ffmpeg/MF keyframe decodes finish instead of getting cancelled every 1s tick.
  LaunchedEffect(f.id, f.modifiedMillis, video) {
    if (!video) { frameLoaded = Loaded.Empty; return@LaunchedEffect }
    androidx.compose.runtime.snapshotFlow { latestBucket.value }
      .conflate()
      .collect { bucket ->
        if (latestPlaying.value && lastFrameMs >= 0L && bucket < lastFrameMs) return@collect
        val next = preview.frame(f, bucket, FRAME_SIDE)
        currentCoroutineContext().ensureActive()
        if (next != null) {
          lastFrameMs = bucket
          frameLoaded = Loaded.Ready(next)
        } else if (frameLoaded == Loaded.Wait) {
          frameLoaded = Loaded.Empty
        }
      }
  }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Box(frame(Modifier.weight(1f), tk), contentAlignment = Alignment.Center) {
      when (val fl = frameLoaded) {
        is Loaded.Ready -> Image(
          fl.image, contentDescription = t("quick_frame_at", formatDuration(activeMs)), modifier = Modifier.fillMaxSize().padding(Sun.S4), contentScale = ContentScale.Fit,
        )
        Loaded.Wait -> if (video) Text(t("quick_preview_loading"), style = SunType.PanelDetail, color = tk.ink2) else KindTile(f.kind, 72.dp, fileName = f.name)
        Loaded.Empty -> KindTile(f.kind, 72.dp, fileName = f.name)
      }
      // Corner expand button on videos so one click pops the clip straight into full native playback.
      if (video && onOpen != null) {
        Row(
          Modifier.align(Alignment.BottomStart).padding(Sun.S8).clip(RoundedCornerShape(8.dp))
            .background(tk.paper.copy(alpha = 0.90f)).border(Sun.Hairline, tk.line2, RoundedCornerShape(8.dp))
            .a11yClickable(t("action_open"), RoundedCornerShape(8.dp)) { player?.stop(); onOpen() }
            .padding(horizontal = Sun.S8, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(Icons.OpenInNew, contentDescription = null, tint = tk.ink, modifier = Modifier.size(14.dp))
          Text(t("action_open"), style = SunType.Caption, color = tk.ink)
        }
      }
      // Live timestamp badge over the corner so video/audio position is immediately visible while playing or scrubbing.
      if (durationMs > 0L || activeMs > 0L) {
        val stamp = if (durationMs > 0L) "${formatDuration(activeMs)} / ${formatDuration(durationMs)}" else formatDuration(activeMs)
        Text(
          stamp,
          style = SunType.Caption,
          color = if (playing) tk.focus else tk.ink,
          modifier = Modifier.align(Alignment.BottomEnd).padding(Sun.S8).clip(RoundedCornerShape(6.dp))
            .background(tk.paper.copy(alpha = 0.88f)).border(Sun.Hairline, tk.line2, RoundedCornerShape(6.dp))
            .padding(horizontal = Sun.S8, vertical = 2.dp),
        )
      }
    }
    val snippet = snippetText?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
    if (snippet != null) SnippetText(snippet, lines = 3, style = SunType.PanelDetail, modifier = Modifier.fillMaxWidth())
    if (durationMs > 0L) {
      MediaTimeline(
        currentMs = activeMs,
        durationMs = durationMs,
        matchMs = from.takeIf { it > 0L && it <= durationMs },
        playing = playing,
        onSeek = { targetMs ->
          lastFrameMs = -1L
          seekMs = targetMs
          userSeeked = true
          if (playing && player != null) player.play(file, targetMs)
        },
        onJumpToMatch = { matchPos ->
          lastFrameMs = -1L
          seekMs = matchPos
          userSeeked = false
          if (playing && player != null) player.play(file, trawl.desktop.PreviewPlayer.matchPlayStartMs(matchPos))
        },
      )
    }
    PlayBar(preview, f, seekMs, playFrom = if (userSeeked) seekMs else trawl.desktop.PreviewPlayer.matchPlayStartMs(seekMs))
  }
}

/** Interactive timeline track showing full duration, match-moment pin, and live playhead position; click anywhere to seek. */
@Composable
private fun MediaTimeline(
  currentMs: Long,
  durationMs: Long,
  matchMs: Long?,
  playing: Boolean,
  onSeek: (Long) -> Unit,
  onJumpToMatch: (Long) -> Unit = onSeek,
) {
  val tk = LocalSunlight.current
  val fraction = (currentMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
  val matchFraction = matchMs?.let { (it.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Box(
      Modifier.fillMaxWidth().height(20.dp).pointerHoverIcon(PointerIcon.Hand).pointerInput(durationMs, onSeek) {
        detectTapGestures { offset ->
          val w = size.width.toFloat().coerceAtLeast(1f)
          val f = (offset.x / w).coerceIn(0f, 1f)
          onSeek((f * durationMs).toLong())
        }
      },
      contentAlignment = Alignment.Center,
    ) {
      Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val trackH = 5.dp.toPx()
        val cy = size.height / 2f
        val r = CornerRadius(trackH / 2f, trackH / 2f)
        // Full track background
        drawRoundRect(color = tk.line2.copy(alpha = 0.5f), topLeft = Offset(0f, cy - trackH / 2f), size = Size(size.width, trackH), cornerRadius = r)
        // Elapsed / current position fill
        val headX = (fraction * size.width).coerceIn(0f, size.width)
        val fillColor = if (playing) tk.focus else tk.ink2
        if (headX > 0f) {
          drawRoundRect(color = fillColor, topLeft = Offset(0f, cy - trackH / 2f), size = Size(headX, trackH), cornerRadius = r)
        }
        // Match-moment marker pin (shows where the matched passage sits inside the whole video/audio track)
        if (matchFraction != null) {
          val mx = (matchFraction * size.width).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
          drawRoundRect(
            color = tk.focus,
            topLeft = Offset(mx - 1.5.dp.toPx(), cy - 6.dp.toPx()),
            size = Size(3.dp.toPx(), 12.dp.toPx()),
            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
          )
        }
        // Playhead knob
        drawCircle(color = if (playing) tk.focus else tk.ink, radius = 5.5.dp.toPx(), center = Offset(headX.coerceIn(5.5.dp.toPx(), (size.width - 5.5.dp.toPx()).coerceAtLeast(5.5.dp.toPx())), cy))
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(formatDuration(currentMs), style = SunType.Caption, color = if (playing) tk.focus else tk.ink2)
      if (matchMs != null) {
        Text(
          t("badge_at_time", formatDuration(matchMs)),
          style = SunType.Caption,
          color = tk.focus,
          modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).pointerInput(matchMs, onJumpToMatch) { detectTapGestures { onJumpToMatch(matchMs) } },
        )
      }
      Text(formatDuration(durationMs), style = SunType.Caption, color = tk.ink2)
    }
  }
}

/** Play from a moment, with what it is doing said in words. Hidden when this computer has no player. */
@Composable
fun PlayBar(preview: QuickPreviewApi, f: IndexedFile, from: Long, playFrom: Long = from) {
  val tk = LocalSunlight.current
  val player = preview.player ?: return
  val file = remember(f.id) { preview.fileOf(f) }
  val rawState by player.state.collectAsState()
  val playerFile by player.activeFile.collectAsState()
  val state = if (playerFile?.absolutePath == file.absolutePath) rawState else PlayState.Idle
  val playing = state is PlayState.Loading || state is PlayState.Playing
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
        .a11yClickable(label, RoundedCornerShape(24.dp)) { if (playing) player.stop() else player.play(file, playFrom) }
        .padding(horizontal = Sun.S16, vertical = Sun.S8),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    ) {
      Icon(if (playing) Icons.Pause else Icons.Play, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
      Text(label, style = SunType.Label, color = tk.ink)
    }
    Text(status, style = SunType.PanelDetail, color = tk.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).a11yLive())
  }
}

/** A document: the matched passage with the match marked, then structured JSON/CSV/Excel/code or soft-wrapped Markdown/prose with query words marked. */
@Composable
private fun TextBox(hit: QuickHit, query: String, preview: QuickPreviewApi) {
  val tk = LocalSunlight.current
  val f = hit.file
  val snippet = hit.snippet?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
  val structuredKind = remember(f.name) { trawl.desktop.ui.StructuredKind.of(f.name) }
  val isMarkdown = remember(f.name) { trawl.desktop.ui.StructuredKind.isMarkdown(f.name) }
  val richBody = structuredKind.usesHorizontalScroll || isMarkdown
  // Strictly keyed mutableStateOf (never produceState!) guarantees switching PDFs or documents NEVER freezes the previous page or bleeds text.
  var lead by remember(f.id, f.modifiedMillis, structuredKind) { mutableStateOf<Loaded.Lead?>(null) }
  LaunchedEffect(f.id, f.modifiedMillis, structuredKind) {
    val raw = if (trawl.desktop.ui.StructuredKind.isPlainDiskText(f.name)) {
      val disk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { trawl.desktop.ui.readDiskPreviewText(preview.fileOf(f)) }.getOrNull()
      }
      disk ?: preview.lead(f)
    } else {
      preview.lead(f)
    }
    currentCoroutineContext().ensureActive()
    lead = Loaded.Lead(raw?.trim()?.take(if (richBody) 12_000 else LEAD_CHARS)?.takeIf { it.isNotBlank() })
  }
  val pdfFile = remember(f.id, f.uri, f.sensitive) { if (f.kind == FileKind.PDF && !f.sensitive) preview.fileOf(f) else null }
  var pdfBmp by remember(f.id, pdfFile, f.modifiedMillis) {
    mutableStateOf(pdfFile?.let { trawl.desktop.PdfThumbs.peek(it, 1, side = 560, modifiedMillis = f.modifiedMillis) })
  }
  LaunchedEffect(f.id, pdfFile, f.modifiedMillis) {
    if (pdfBmp != null || pdfFile == null) return@LaunchedEffect
    val rendered = trawl.desktop.PdfThumbs.loadSuspend(pdfFile, 1, side = 560, modifiedMillis = f.modifiedMillis)
    currentCoroutineContext().ensureActive()
    pdfBmp = rendered
  }
  Box(frame(Modifier, tk)) {
    key(f.id) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
        val pageImg = pdfBmp
        if (pageImg != null) {
          Box(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp))
              .background(androidx.compose.ui.graphics.Color.White)
              .border(Sun.Hairline, tk.line2, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Image(pageImg, contentDescription = f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
          }
        }
        if (snippet != null) {
          Text(t("quick_matched_text"), style = SunType.CaptionBold, color = tk.ink2)
          SelectionContainer(Modifier.fillMaxWidth()) {
            SnippetText(snippet, lines = if (richBody) 5 else 12, style = SunType.BodyLarge, modifier = Modifier.fillMaxWidth())
          }
        }
        val body = lead?.text
        when {
          lead == null -> if (snippet == null && pageImg == null) Text(t("quick_preview_loading"), style = SunType.PanelDetail, color = tk.ink2)
          body != null && (body != snippet || richBody) -> {
            if (snippet != null) Text(t("quick_file_start"), style = SunType.CaptionBold, color = tk.ink2, modifier = Modifier.padding(top = Sun.S4))
            val rendered = trawl.desktop.ui.StructuredContentView(fileName = f.name, rawText = body, query = query, maxLines = 36)
            if (!rendered) {
              SelectionContainer(Modifier.fillMaxWidth()) {
                Text(
                  highlightTerms(body, query, SpanStyle(background = tk.marker, color = tk.onMarker, fontWeight = FontWeight.Bold)),
                  style = SunType.BodyLarge,
                  color = tk.ink,
                  softWrap = true,
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
          snippet == null && pageImg == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
            KindTile(f.kind, 48.dp, fileName = f.name)
            Text(t("quick_no_text"), style = SunType.Body, color = tk.ink2)
          }
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
