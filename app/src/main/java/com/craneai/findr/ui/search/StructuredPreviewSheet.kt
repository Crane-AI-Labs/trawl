package com.craneai.findr.ui.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.craneai.findr.R
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.extract.DocumentTextExtractor
import com.craneai.findr.extract.TextDecoding
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface LoadedPreview {
  data object Loading : LoadedPreview
  data object Empty : LoadedPreview
  data object Unavailable : LoadedPreview
  data class Json(val pretty: String, val spans: List<StructuredPreview.HighlightSpan>, val truncated: Boolean) : LoadedPreview
  data class Table(val data: StructuredPreview.TableData) : LoadedPreview
  data class Code(val text: String, val spans: List<StructuredPreview.HighlightSpan>, val truncated: Boolean) : LoadedPreview
  data class Prose(val text: String, val spans: List<StructuredPreview.HighlightSpan>, val truncated: Boolean) : LoadedPreview
}

/**
 * Sunlight-styled structured preview sheet for JSON, CSV/TSV/Excel tables, code/config files, and soft-wrapped prose/documents.
 * Zero external dependencies, bounded memory reads ([StructuredPreview.MAX_PREVIEW_BYTES]), and WCAG-AA syntax colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StructuredPreviewSheet(
  file: IndexedFile,
  hidden: Boolean,
  query: String = "",
  onOpenExternal: () -> Unit,
  onShare: () -> Unit,
  onCopy: () -> Unit,
  onReveal: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
  ) {
    StructuredPreviewContent(
      file = file,
      hidden = hidden,
      query = query,
      onOpenExternal = onOpenExternal,
      onShare = onShare,
      onCopy = onCopy,
      onReveal = onReveal,
    )
  }
}

@Composable
private fun StructuredPreviewContent(
  file: IndexedFile,
  hidden: Boolean,
  query: String,
  onOpenExternal: () -> Unit,
  onShare: () -> Unit,
  onCopy: () -> Unit,
  onReveal: () -> Unit,
) {
  val context = LocalContext.current
  val t = LocalSunlight.current
  val dark = t.paper.luminance() < 0.5f
  val style = remember(file.name, file.kind) { FileTypeVisual.of(file.name, file.kind) }
  var loaded by remember(file.uri, hidden, query) { mutableStateOf<LoadedPreview>(LoadedPreview.Loading) }

  LaunchedEffect(file.uri, hidden, query) {
    if (hidden) return@LaunchedEffect
    loaded = loadStructuredPreview(context, file, query)
  }

  Column(
    Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = Sun.Gutter)
      .padding(bottom = Sun.S16)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(Sun.S12),
  ) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    ) {
      Text(
        file.name,
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = t.ink,
        maxLines = 2,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.weight(1f),
      )
      if (style != null) TypeBadge(style)
    }

    if (hidden) {
      Column(
        Modifier
          .fillMaxWidth()
          .clip(MaterialTheme.shapes.small)
          .background(t.paper2)
          .border(Sun.Border, t.line, MaterialTheme.shapes.small)
          .padding(Sun.S16),
        verticalArrangement = Arrangement.spacedBy(Sun.S12),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.preview_hidden), style = SunType.Body, color = t.ink2)
        Row(
          Modifier
            .heightIn(min = Sun.Target)
            .clip(MaterialTheme.shapes.small)
            .background(t.action)
            .clickable(role = Role.Button, onClick = onReveal)
            .padding(horizontal = Sun.S16, vertical = Sun.S12),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Sun.S8),
        ) {
          Icon(Icons.Outlined.Visibility, contentDescription = null, tint = t.onAction, modifier = Modifier.size(18.dp))
          Text(stringResource(R.string.action_show_preview), style = SunType.Label, color = t.onAction)
        }
      }
      return
    }

    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(min = 120.dp, max = 420.dp)
        .clip(MaterialTheme.shapes.small)
        .background(t.paper2)
        .border(Sun.Border, t.line2, MaterialTheme.shapes.small),
    ) {
      when (val state = loaded) {
        LoadedPreview.Loading -> Text(
          stringResource(R.string.preview_loading),
          style = SunType.Body,
          color = t.ink2,
          modifier = Modifier.align(Alignment.Center).padding(Sun.S16),
        )
        LoadedPreview.Empty -> Text(
          stringResource(R.string.preview_empty),
          style = SunType.Body,
          color = t.ink2,
          modifier = Modifier.align(Alignment.Center).padding(Sun.S16),
        )
        LoadedPreview.Unavailable -> Text(
          stringResource(R.string.preview_unavailable),
          style = SunType.Body,
          color = t.ink2,
          modifier = Modifier.align(Alignment.Center).padding(Sun.S16),
        )
        is LoadedPreview.Json -> {
          val annotated = remember(state.pretty, state.spans, dark, t.marker, t.onMarker) {
            buildHighlightedText(state.pretty, state.spans, dark, t.ink, t.ink2, t.marker, t.onMarker)
          }
          HighlightedCodeBox(annotated, state.truncated)
        }
        is LoadedPreview.Code -> {
          val annotated = remember(state.text, state.spans, dark, t.marker, t.onMarker) {
            buildHighlightedText(state.text, state.spans, dark, t.ink, t.ink2, t.marker, t.onMarker)
          }
          HighlightedCodeBox(annotated, state.truncated)
        }
        is LoadedPreview.Prose -> {
          val annotated = remember(state.text, state.spans, dark, t.marker, t.onMarker) {
            buildHighlightedText(state.text, state.spans, dark, t.ink, t.ink2, t.marker, t.onMarker)
          }
          ProsePreviewBox(annotated, state.truncated)
        }
        is LoadedPreview.Table -> TablePreviewBox(state.data)
      }
    }

    if (LocalDensity.current.fontScale >= 1.3f) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
        PreviewActionButton(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open), onOpenExternal, Modifier.fillMaxWidth())
        PreviewActionButton(Icons.Outlined.Share, stringResource(R.string.action_share), onShare, Modifier.fillMaxWidth())
        PreviewActionButton(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), onCopy, Modifier.fillMaxWidth())
      }
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
        PreviewActionButton(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open), onOpenExternal, Modifier.weight(1f))
        PreviewActionButton(Icons.Outlined.Share, stringResource(R.string.action_share), onShare, Modifier.weight(1f))
        PreviewActionButton(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), onCopy, Modifier.weight(1f))
      }
    }
  }
}

/** Soft-wrapped prose/document preview (Markdown, TXT, extracted PDF/DOCX/RTF): vertical scroll only, zero right-edge clipping. */
@Composable
private fun ProsePreviewBox(annotated: AnnotatedString, truncated: Boolean) {
  val t = LocalSunlight.current
  Column(Modifier.fillMaxWidth()) {
    Box(
      Modifier
        .fillMaxWidth()
        .weight(1f, fill = false)
        .verticalScroll(rememberScrollState())
        .padding(Sun.S12),
    ) {
      Text(
        text = annotated,
        style = SunType.Body.copy(lineHeight = 22.sp),
        color = t.ink,
        softWrap = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (truncated) {
      Text(
        stringResource(R.string.preview_bytes_truncated),
        style = SunType.Caption,
        color = t.ink2,
        modifier = Modifier
          .fillMaxWidth()
          .background(t.paper)
          .padding(horizontal = Sun.S12, vertical = Sun.S4),
      )
    }
  }
}

@Composable
private fun HighlightedCodeBox(annotated: AnnotatedString, truncated: Boolean) {
  val t = LocalSunlight.current
  Column(Modifier.fillMaxWidth()) {
    Box(
      Modifier
        .fillMaxWidth()
        .weight(1f, fill = false)
        .verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState())
        .padding(Sun.S12),
    ) {
      Text(
        text = annotated,
        style = SunType.Caption.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
        color = t.ink,
      )
    }
    if (truncated) {
      Text(
        stringResource(R.string.preview_bytes_truncated),
        style = SunType.Caption,
        color = t.ink2,
        modifier = Modifier
          .fillMaxWidth()
          .background(t.paper)
          .padding(horizontal = Sun.S12, vertical = Sun.S4),
      )
    }
  }
}

@Composable
private fun TablePreviewBox(data: StructuredPreview.TableData) {
  val t = LocalSunlight.current
  val hScroll = rememberScrollState()
  val colWidths = remember(data) {
    List(data.headers.size) { col ->
      val maxLen = maxOf(
        data.headers[col].length,
        data.rows.take(50).maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0,
      )
      (maxLen.coerceIn(8, 28) * 8 + 20).dp
    }
  }
  Column(Modifier.fillMaxWidth()) {
    Text(
      stringResource(R.string.preview_rows_cols, data.rows.size, data.headers.size),
      style = SunType.Caption,
      color = t.ink2,
      modifier = Modifier
        .fillMaxWidth()
        .background(t.paper)
        .padding(horizontal = Sun.S12, vertical = Sun.S4),
    )
    LazyColumn(
      Modifier
        .fillMaxWidth()
        .weight(1f, fill = false)
        .horizontalScroll(hScroll),
    ) {
      item(key = "header") {
        Row(
          Modifier
            .background(t.paper2)
            .border(0.5.dp, t.line2),
        ) {
          data.headers.forEachIndexed { col, heading ->
            Text(
              heading,
              style = SunType.CaptionBold,
              color = t.ink,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier
                .width(colWidths[col])
                .padding(horizontal = 8.dp, vertical = 6.dp),
            )
          }
        }
      }
      itemsIndexed(data.rows, key = { idx, _ -> idx }) { idx, row ->
        Row(
          Modifier
            .background(if (idx % 2 == 0) t.paper else t.paper2)
            .border(0.5.dp, t.line2.copy(alpha = 0.35f)),
        ) {
          row.forEachIndexed { col, value ->
            Text(
              value,
              style = SunType.Caption,
              color = t.ink,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier
                .width(colWidths[col])
                .padding(horizontal = 8.dp, vertical = 5.dp),
            )
          }
        }
      }
    }
    if (data.truncated) {
      Text(
        stringResource(R.string.preview_truncated, data.rows.size),
        style = SunType.Caption,
        color = t.ink2,
        modifier = Modifier
          .fillMaxWidth()
          .background(t.paper)
          .padding(horizontal = Sun.S12, vertical = Sun.S4),
      )
    }
  }
}

@Composable
private fun PreviewActionButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val t = LocalSunlight.current
  val shape = MaterialTheme.shapes.small
  Row(
    modifier
      .heightIn(min = Sun.Target)
      .clip(shape)
      .background(t.paper, shape)
      .border(Sun.Border, t.line, shape)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = Sun.S8, vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Sun.S4, Alignment.CenterHorizontally),
  ) {
    Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(18.dp))
    Text(label, style = SunType.Label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

private fun buildHighlightedText(
  text: String,
  spans: List<StructuredPreview.HighlightSpan>,
  dark: Boolean,
  ink: Color,
  ink2: Color,
  marker: Color,
  onMarker: Color,
): AnnotatedString {
  // WCAG-AA Sunlight accents (matching FileTypeVisual's verified palette)
  val keyColor = if (dark) Color(0xFF60A5FA) else Color(0xFF1D4ED8)
  val stringColor = if (dark) Color(0xFF4ADE80) else Color(0xFF116631)
  val numberColor = if (dark) Color(0xFFFB923C) else Color(0xFFA33608)
  val boolColor = if (dark) Color(0xFFC084FC) else Color(0xFF7B24CC)
  val keywordColor = if (dark) Color(0xFFA78BFA) else Color(0xFF6D28D9)
  return buildAnnotatedString {
    append(text)
    for (s in spans) {
      if (s.start < 0 || s.end > text.length || s.start >= s.end) continue
      val style = when (s.kind) {
        StructuredPreview.TokenKind.KEY -> SpanStyle(color = keyColor, fontWeight = FontWeight.Bold)
        StructuredPreview.TokenKind.STRING -> SpanStyle(color = stringColor)
        StructuredPreview.TokenKind.NUMBER -> SpanStyle(color = numberColor)
        StructuredPreview.TokenKind.BOOLEAN_NULL -> SpanStyle(color = boolColor, fontWeight = FontWeight.Bold)
        StructuredPreview.TokenKind.KEYWORD -> SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)
        StructuredPreview.TokenKind.COMMENT -> SpanStyle(color = ink2)
        StructuredPreview.TokenKind.PUNCTUATION -> SpanStyle(color = ink2)
        StructuredPreview.TokenKind.HEADING -> SpanStyle(color = ink, fontWeight = FontWeight.Bold)
        StructuredPreview.TokenKind.MATCH -> SpanStyle(color = onMarker, background = marker, fontWeight = FontWeight.SemiBold)
        StructuredPreview.TokenKind.PLAIN -> SpanStyle(color = ink)
      }
      addStyle(style, s.start, s.end)
    }
  }
}

private suspend fun loadStructuredPreview(context: Context, file: IndexedFile, query: String): LoadedPreview = withContext(Dispatchers.IO) {
  val kind = StructuredPreview.kindOf(file.name) ?: return@withContext LoadedPreview.Unavailable
  try {
    val uri = file.uri.toUri()
    if (kind == StructuredPreview.PreviewKind.TABLE && StructuredPreview.isSpreadsheetBinary(file.name)) {
      val extracted = DocumentTextExtractor().extract(context, uri, file.mimeType, file.name, maxChars = 64_000)
      val body = extracted.pages.joinToString("\n")
      if (body.isBlank()) return@withContext LoadedPreview.Empty
      val table = StructuredPreview.parseSpreadsheetText(body)
      return@withContext if (table.headers.isEmpty()) LoadedPreview.Empty else LoadedPreview.Table(table)
    }
    if (kind == StructuredPreview.PreviewKind.PROSE && StructuredPreview.isDocumentExtracted(file.name)) {
      val extracted = DocumentTextExtractor().extract(context, uri, file.mimeType, file.name, maxChars = 64_000)
      val body = extracted.pages.joinToString("\n\n").trim()
      if (body.isBlank()) return@withContext LoadedPreview.Empty
      return@withContext LoadedPreview.Prose(
        text = body,
        spans = StructuredPreview.tokenizeProse(body, markdown = false, query = query),
        truncated = extracted.truncated,
      )
    }
    val stream = context.contentResolver.openInputStream(uri) ?: return@withContext LoadedPreview.Unavailable
    val (bytes, more) = stream.use { input ->
      val buf = ByteArray(StructuredPreview.MAX_PREVIEW_BYTES + 1)
      var total = 0
      while (total < buf.size) {
        val n = input.read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
      }
      val cut = total > StructuredPreview.MAX_PREVIEW_BYTES
      val kept = if (cut) buf.copyOf(StructuredPreview.MAX_PREVIEW_BYTES) else buf.copyOf(total)
      kept to cut
    }
    if (bytes.isEmpty()) return@withContext LoadedPreview.Empty
    if (TextDecoding.looksBinary(bytes)) return@withContext LoadedPreview.Unavailable
    val text = TextDecoding.decode(bytes, bytes.size, cut = more)
    if (text.isBlank()) return@withContext LoadedPreview.Empty
    when (kind) {
      StructuredPreview.PreviewKind.JSON -> {
        val pretty = StructuredPreview.prettyJson(text)
        LoadedPreview.Json(pretty, StructuredPreview.tokenizeJson(pretty), more)
      }
      StructuredPreview.PreviewKind.TABLE -> {
        val table = StructuredPreview.parseDelimited(text, StructuredPreview.delimiterOf(file.name))
        if (table.headers.isEmpty()) LoadedPreview.Empty else LoadedPreview.Table(table.copy(truncated = table.truncated || more))
      }
      StructuredPreview.PreviewKind.PROSE -> {
        val md = StructuredPreview.isMarkdown(file.name)
        LoadedPreview.Prose(text, StructuredPreview.tokenizeProse(text, markdown = md, query = query), more)
      }
      StructuredPreview.PreviewKind.CODE -> {
        val ext = FileSubType.extensionOf(file.name).orEmpty()
        LoadedPreview.Code(text, StructuredPreview.tokenizeCode(text, ext), more)
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (_: Throwable) {
    LoadedPreview.Unavailable
  }
}
