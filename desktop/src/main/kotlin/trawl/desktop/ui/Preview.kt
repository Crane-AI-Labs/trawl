package trawl.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import trawl.desktop.ui.search.ImageSearch

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
    if (taken) {
      if (file.dateMillis > 0) add(Fact(t("fact_taken"), date(file.dateMillis, locale, zone)))
    } else {
      if (file.modifiedMillis > 0) add(Fact(t("fact_modified"), date(file.modifiedMillis, locale, zone)))
      if (file.dateMillis > 0 && file.dateMillis != file.modifiedMillis) {
        add(Fact(t("fact_created"), date(file.dateMillis, locale, zone)))
      }
    }
    val type = listOfNotNull(kindLabel(file.kind), file.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) }).joinToString("  ·  ")
    add(Fact(t("fact_kind"), type))
    val w = file.width
    val h = file.height
    if (w != null && h != null && w > 0 && h > 0) add(Fact(t("fact_dimensions"), "$w × $h"))
  }
}

/** One action in the uniform 2-column preview action grid. */
data class PreviewAction(
  val label: String,
  val keyCap: String? = null,
  val icon: ImageVector? = null,
  val primary: Boolean = false,
  val enabled: Boolean = true,
  val onClick: () -> Unit,
)

/**
 * Uniform 2-column grid of equal-width, equal-height action buttons. Each row holds up to two buttons stretching via
 * `Modifier.weight(1f)` and `heightIn(min = 42.dp)`, with a single-line ellipsized label and right-aligned [KeyCap].
 */
@Composable
fun PreviewActionGrid(actions: List<PreviewAction>, modifier: Modifier = Modifier) {
  if (actions.isEmpty()) return
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    actions.chunked(2).forEach { pair ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
        pair.forEach { action ->
          PreviewActionButton(action, Modifier.weight(1f).heightIn(min = 42.dp))
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun PreviewActionButton(action: PreviewAction, modifier: Modifier) {
  val content: @Composable RowScope.() -> Unit = {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    ) {
      if (action.icon != null) {
        Icon(action.icon, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
      }
      Text(
        action.label,
        style = SunType.Label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (action.keyCap != null) {
        KeyCap(action.keyCap)
      }
    }
  }
  val buttonMod = modifier.semantics { role = Role.Button }
  if (action.primary) {
    SunButton(onClick = action.onClick, modifier = buttonMod, enabled = action.enabled, content = content)
  } else {
    SunOutlinedButton(onClick = action.onClick, modifier = buttonMod, enabled = action.enabled, content = content)
  }
}

/**
 * The right-hand pane: what is selected, large. A picture is drawn to fit, a document shows the text that matched, and under it the facts
 * and the uniform 2-column action grid (which lives here, not on every row). The whole pane scrolls if the window is short.
 *
 * A picture that looks private is blurred (PreviewMedia) with an eye under it and its name replaced by "hidden"; a sensitive file gets a
 * Privacy block under the facts with the mark/unmark action. Quick Look reuses PreviewMedia, so it follows.
 */
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
      key(f.id) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Sun.S20), verticalArrangement = Arrangement.spacedBy(Sun.S16)) {
          PreviewMedia(row, c, mediaHeight, side = 1400, excerptLines = 10)
          RevealEye(f, blur)
          Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
            SelectionContainer {
              Text(if (hidden) t("hidden_label") else f.name, style = SunType.Heading, color = tk.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            RowBadges(row)
          }
          val actions = buildList {
            add(PreviewAction(label = t("action_open"), keyCap = "Enter", primary = true, onClick = { leave(f.id) { c.open(f) } }))
            add(PreviewAction(label = t("action_show_folder"), keyCap = "${MainUi.modName} Enter", onClick = { leave(f.id) { c.showInFolder(f) } }))
            add(PreviewAction(label = t("action_copy"), keyCap = "${MainUi.modName} C", icon = Icons.Copy, onClick = { leave(f.id) { c.copyFiles(listOf(f)) } }))
            if (c.canFindSimilar(f)) {
              add(PreviewAction(label = t("action_find_similar"), keyCap = "${MainUi.modName} Shift S", icon = Icons.ImageSearch, onClick = { c.findSimilar(f, nameHidden = hidden) }))
            }
          }
          PreviewActionGrid(actions)
          SunDivider()
          Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
            PreviewFacts.of(f, folder).forEach { FactRow(it) }
            FactRow(Fact(t("fact_match"), whyText(row.reason, browsing)))
          }
          // Privacy & index controls: sensitivity status + mark/unmark, followed by Remove from index (never touches disk).
          val why = when {
            f.markedSensitive -> t("fact_privacy_marked")
            f.sensitive -> t("fact_privacy_folder")
            blur.looksPrivate(f) -> t("fact_privacy_looks")
            else -> null
          }
          SunDivider()
          Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
            if (why != null) FactRow(Fact(t("fact_privacy"), why))
            if (privacyUi != null && (f.markedSensitive || !f.sensitive)) {
              SunOutlinedButton(
                onClick = { privacyUi.mark(listOf(f.id), !f.markedSensitive) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp).semantics { role = Role.Button },
              ) {
                Text(if (f.markedSensitive) t("sensitive_unmark") else t("sensitive_mark"), style = SunType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
            SunOutlinedButton(
              onClick = { c.removeFromIndex(listOf(f)) },
              danger = true,
              modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp).semantics { role = Role.Button },
            ) {
              Icon(Icons.Close, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
              Spacer(Modifier.width(Sun.S8))
              Text(t("action_remove_from_index"), style = SunType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
      val thumb = rememberThumb(c.fileOf(f), side, modifiedMillis = f.modifiedMillis)
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
      val leave = rememberLeave()
      androidx.compose.runtime.DisposableEffect(f.id) { onDispose { c.previewPlayer.stop() } }
      trawl.desktop.ui.quick.MediaBox(
        f, row.startMs ?: 0L, cleanSnippet(row), preview, f.kind == FileKind.VIDEO,
        modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier.fillMaxSize()),
        onOpen = { leave(f.id) { c.open(f) } },
      )
    }
    else -> {
      // The text shown: the match when there is one, else the file's opening text as it was indexed (the Recent view has no match to quote).
      // JSON, CSV/TSV/Excel, code, and Markdown/prose files load structured or clean soft-wrapped prose content.
      val quoted = remember(row) { cleanSnippet(row) }
      val structuredKind = remember(f.name) { StructuredKind.of(f.name) }
      val isMarkdown = remember(f.name) { StructuredKind.isMarkdown(f.name) }
      val loadStructuredBody = quoted == null || height == null || structuredKind.usesHorizontalScroll || isMarkdown
      val activeQuery by c.query.collectAsState()
      // Strictly keyed mutableStateOf (never produceState!) prevents stale document text or frozen PDF pages from bleeding across file switches.
      var lead by remember(f.id, f.modifiedMillis, quoted, structuredKind, loadStructuredBody) {
        mutableStateOf(if (!loadStructuredBody) Lead(null) else null)
      }
      LaunchedEffect(f.id, f.modifiedMillis, quoted, structuredKind, loadStructuredBody) {
        if (!loadStructuredBody) {
          lead = Lead(null)
          return@LaunchedEffect
        }
        val loaded = if (StructuredKind.isPlainDiskText(f.name)) {
          val disk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readDiskPreviewText(c.fileOf(f)) }
          Lead(disk ?: c.leadText(f))
        } else {
          Lead(c.leadText(f))
        }
        currentCoroutineContext().ensureActive()
        lead = loaded
      }
      val text = quoted ?: lead?.text
      run {
        // A page of text is as tall as its words (up to the box it was given), not a tall empty card with one line in it. Quick Look has no
        // height of its own (it fills the space it is offered), so it keeps the full frame.
        val textFrame = if (height != null) modifier.fillMaxWidth().heightIn(min = 120.dp, max = height).clip(shape).background(tk.paper).border(Sun.Hairline, tk.line2, shape) else frame
        val pdfFile = remember(f.id, f.uri, f.sensitive) { if (f.kind == FileKind.PDF && !f.sensitive) c.fileOf(f) else null }
        val pdfPage = row.page ?: 1
        var pdfBmp by remember(f.id, pdfFile, pdfPage, f.modifiedMillis) {
          mutableStateOf(pdfFile?.let { trawl.desktop.PdfThumbs.peek(it, pdfPage, side = 720, modifiedMillis = f.modifiedMillis) })
        }
        LaunchedEffect(f.id, pdfFile, pdfPage, f.modifiedMillis) {
          if (pdfBmp != null || pdfFile == null) return@LaunchedEffect
          val rendered = trawl.desktop.PdfThumbs.loadSuspend(pdfFile, pdfPage, side = 720, modifiedMillis = f.modifiedMillis)
          currentCoroutineContext().ensureActive()
          pdfBmp = rendered
        }
        Box(textFrame) {
          key(f.id, pdfPage) {
            Column(
              Modifier.fillMaxWidth().then(if (height != null) Modifier else Modifier.fillMaxSize()).verticalScroll(rememberScrollState()).padding(Sun.S16),
              verticalArrangement = Arrangement.spacedBy(Sun.S12),
            ) {
              val pageImg = pdfBmp
              if (pageImg != null) {
                Box(
                  Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .border(Sun.Hairline, tk.line2, RoundedCornerShape(8.dp)),
                  contentAlignment = Alignment.Center,
                ) {
                  Image(pageImg, contentDescription = f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))
                }
              } else {
                KindTile(f.kind, 36.dp, fileName = f.name)
              }
              if (quoted != null) {
                SelectionContainer(Modifier.fillMaxWidth()) {
                  SnippetText(
                    quoted,
                    lines = if (loadStructuredBody) minOf(excerptLines, 5) else excerptLines,
                    style = SunType.BodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                  )
                }
              }
              val structuredSource = lead?.text
              val renderedStructured = if (loadStructuredBody && structuredSource != null) {
                StructuredContentView(
                  fileName = f.name,
                  rawText = structuredSource,
                  query = activeQuery,
                  maxLines = if (height == null) 80 else if (structuredKind == StructuredKind.PROSE) excerptLines.coerceAtLeast(18) else 28,
                )
              } else {
                false
              }
              if (!renderedStructured && quoted == null) {
                if (text != null) {
                  SelectionContainer(Modifier.fillMaxWidth()) {
                    SnippetText(text, lines = excerptLines, style = SunType.BodyLarge, modifier = Modifier.fillMaxWidth())
                  }
                } else if (lead != null && pageImg == null) {
                  Text(t("preview_no_snippet"), style = SunType.Body, color = tk.ink2)
                }
              }
            }
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
