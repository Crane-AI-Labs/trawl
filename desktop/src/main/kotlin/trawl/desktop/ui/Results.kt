package trawl.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.MatchReason
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.common.highlightedSnippet
import com.craneai.findr.ui.theme.LocalSunlight
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import trawl.desktop.FrameThumbs
import trawl.desktop.ResultRow
import trawl.desktop.Thumbnails
import trawl.desktop.t

/** Two threads decode thumbnails, so a screen of pictures never starves the index worker or the window. */
private val thumbPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "trawl-thumb").apply { isDaemon = true } }.asCoroutineDispatcher()

/** A picture being decoded ([done] false), decoded, or not readable by this computer ([done] with a null [bitmap]: HEIC, WebP, a damaged file). */
class ThumbState(val done: Boolean, val bitmap: ImageBitmap?)

@Composable
fun rememberThumb(file: File, side: Int, modifiedMillis: Long? = null): ThumbState {
  // Strictly keyed mutableStateOf (never produceState!) guarantees switching files NEVER freezes a cached image or bleeds
  // the previous file's bitmap while the new file decodes.
  val path = file.absolutePath
  var state by remember(path, side, modifiedMillis) {
    mutableStateOf(Thumbnails.peek(file, side, modifiedMillis)?.let { ThumbState(true, it) } ?: ThumbState(false, null))
  }
  LaunchedEffect(path, side, modifiedMillis) {
    if (state.done) return@LaunchedEffect
    val bmp = withContext(thumbPool) {
      currentCoroutineContext().ensureActive()
      Thumbnails.load(file, side, modifiedMillis)
    }
    currentCoroutineContext().ensureActive()
    state = ThumbState(true, bmp)
  }
  return state
}

/**
 * What a picture tile shows until its pixels are decoded (and for a format this computer cannot read): a tinted tile with the kind's icon,
 * visibly a picture-to-come, never an empty white box.
 */
@Composable
fun ThumbPlaceholder(icon: ImageVector = Icons.Photo, iconSize: androidx.compose.ui.unit.Dp = 28.dp, modifier: Modifier = Modifier.fillMaxSize()) {
  val tk = LocalSunlight.current
  Box(modifier.background(tk.paper2).background(tk.ink.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, tint = tk.ink2, modifier = Modifier.size(iconSize))
  }
}

/**
 * A picture's thumbnail, decoded off the UI thread; a paper tile with an icon while it loads or when the format cannot be read.
 *
 * Pass [row] (the result's file) and a picture that looks private is drawn blurred, through [PrivateImage] (privacy package): the blurred
 * pixels are all that is ever decoded for it. Without [row] the picture is drawn as it is (nothing here has a score to look at).
 */
@Composable
fun Thumb(
  file: File, modifier: Modifier = Modifier, side: Int = 360, fallback: ImageVector = Icons.Photo,
  contentScale: ContentScale = ContentScale.Crop, iconSize: androidx.compose.ui.unit.Dp = 28.dp,
  row: com.craneai.findr.core.IndexedFile? = null,
) {
  val tk = LocalSunlight.current
  if (row != null) {
    Box(modifier.background(tk.paper2), contentAlignment = Alignment.Center) {
      trawl.desktop.ui.privacy.PrivateImage(
        row, file, side, Modifier.fillMaxSize(), contentScale,
        placeholder = { ThumbPlaceholder(fallback, iconSize) },
      )
    }
    return
  }
  val thumb = rememberThumb(file, side)
  Box(modifier.background(tk.paper2), contentAlignment = Alignment.Center) {
    val b = thumb.bitmap
    if (b != null) Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
    else ThumbPlaceholder(fallback, iconSize)
  }
}

fun kindLabel(kind: FileKind): String = when (kind) {
  FileKind.DOCUMENT -> t("kind_document")
  FileKind.PDF -> t("kind_pdf")
  FileKind.IMAGE -> t("kind_photo")
  FileKind.SCREENSHOT -> t("kind_screenshot")
  FileKind.VIDEO -> t("kind_video")
  FileKind.AUDIO -> t("kind_audio")
  FileKind.OTHER -> t("kind_other")
}

fun kindIcon(kind: FileKind): ImageVector = when (kind) {
  FileKind.PDF -> Icons.Pdf
  FileKind.IMAGE, FileKind.SCREENSHOT -> Icons.Photo
  FileKind.VIDEO -> Icons.Video
  FileKind.AUDIO -> Icons.Mic
  else -> Icons.Document
}

fun reasonLabel(r: MatchReason?): String? = when (r) {
  MatchReason.MEANING -> t("badge_meaning")
  MatchReason.WORDS -> t("badge_words")
  MatchReason.BOTH -> t("badge_both")
  null -> null
}

/** Why a result is here, as a sentence (the preview's "Why it matched"). */
fun whyText(reason: MatchReason?, browsing: Boolean): String = when {
  browsing -> t("why_recent")
  reason == MatchReason.MEANING -> t("why_meaning")
  reason == MatchReason.WORDS -> t("why_words")
  reason == MatchReason.BOTH -> t("why_both")
  else -> t("why_name")
}

/** Pictures are shown as tiles, everything else as rows. */
fun isTile(row: ResultRow): Boolean = row.file.kind == FileKind.IMAGE || row.file.kind == FileKind.SCREENSHOT

private val SNIPPET_WHITESPACE = Regex("\\s+")
private val KIND_TILE_SHAPE = RoundedCornerShape(8.dp)

/** Text read from a picture comes with line breaks; show every snippet as one run. */
fun cleanSnippet(row: ResultRow): String? = row.snippet?.replace(SNIPPET_WHITESPACE, " ")?.trim()?.takeIf { it.isNotBlank() }

/** The small outlined labels on a row: the kind where it is not obvious, the page, and why it matched (the marker one). */
@Composable
fun RowBadges(row: ResultRow, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
    val visual = FileTypeVisual.of(row.file.name, row.file.kind)
    if (visual != null) TypeBadge(visual)
    else if (row.file.kind == FileKind.PDF || row.file.kind == FileKind.VIDEO || row.file.kind == FileKind.AUDIO) Badge(kindLabel(row.file.kind))
    if (row.file.sensitive) Badge(t("sensitive_badge"))
    row.page?.let { Badge(t("badge_page", it)) }
    // A16: a video or recording says where the match is (strong, the point of the row) and how long the whole thing is.
    MediaPills.of(row.file.kind, row.startMs, row.file.durationMs).forEach { Badge(it.text, marker = it.strong) }
    reasonLabel(row.reason)?.let { Badge(it, marker = true) }
  }
}

/** One small pill on a video or recording row. [strong] is the matched moment, drawn in the marker colour. */
data class MediaPill(val text: String, val strong: Boolean)

/** What a video or recording row says about time: "at 1:23" (the hit moment) then the length, "4:12". Pure, so a test can read it. */
object MediaPills {
  fun of(kind: FileKind, startMs: Long?, durationMs: Long?): List<MediaPill> {
    if (kind != FileKind.VIDEO && kind != FileKind.AUDIO) return startMs?.let { listOf(MediaPill(t("badge_at_time", formatDuration(it)), false)) } ?: emptyList()
    return buildList {
      startMs?.let { add(MediaPill(t("badge_at_time", formatDuration(it)), true)) }
      durationMs?.takeIf { it > 0 }?.let { add(MediaPill(formatDuration(it), false)) }
    }
  }
}

/**
 * A video's frame at the hit moment, small, in place of the kind icon (B9). Shows the icon while the frame is coming and whenever this
 * computer cannot give one; a sensitive or hidden file never asks for one at all ([FrameThumbs.mayShow]).
 */
@Composable
fun VideoFrameTile(row: ResultRow, file: File, frames: FrameThumbs, hidden: Boolean, size: androidx.compose.ui.unit.Dp = Sun.Thumb) {
  val f = row.file
  if (!FrameThumbs.mayShow(f, hidden)) { KindTile(f.kind, size, fileName = f.name); return }
  val moment = FrameThumbs.momentFor(row.startMs, f.durationMs)
  val path = file.absolutePath
  val mod = f.modifiedMillis
  var state by remember(path, moment, mod) {
    mutableStateOf(frames.peek(file, moment, modifiedMillis = mod)?.let { ThumbState(true, it) } ?: ThumbState(false, null))
  }
  LaunchedEffect(path, moment, mod) {
    if (state.done) return@LaunchedEffect
    val bmp = frames.loadSuspend(file, moment, modifiedMillis = mod)
    currentCoroutineContext().ensureActive()
    state = ThumbState(true, bmp)
  }
  val bmp = state.bitmap
  if (bmp == null) { KindTile(f.kind, size, fileName = f.name); return }
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(8.dp)
  Box(Modifier.size(width = size * 1.5f, height = size).clip(shape).border(Sun.Hairline, tk.line2, shape), contentAlignment = Alignment.Center) {
    Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
  }
}

/** The matched text with the match marked, as one line or more. */
@Composable
fun SnippetText(text: String, lines: Int, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = SunType.Body) {
  val tk = LocalSunlight.current
  Text(
    highlightedSnippet(text, SpanStyle(background = tk.marker, color = tk.onMarker)),
    style = style, color = tk.ink, maxLines = lines, softWrap = true, overflow = TextOverflow.Ellipsis, modifier = modifier,
  )
}

/** A document's icon on a small uniform tile, color-coded by file extension (PDF red, DOCX blue, XLSX green, PPTX orange, etc.). */
@Composable
fun KindTile(kind: FileKind, size: androidx.compose.ui.unit.Dp = Sun.Thumb, modifier: Modifier = Modifier, fileName: String? = null) {
  val tk = LocalSunlight.current
  val visual = FileTypeVisual.of(fileName, kind)
  if (visual == null) {
    Box(modifier.size(size).background(tk.paper2, KIND_TILE_SHAPE).border(Sun.Hairline, tk.line2, KIND_TILE_SHAPE), contentAlignment = Alignment.Center) {
      Icon(kindIcon(kind), contentDescription = null, tint = tk.ink, modifier = Modifier.size(size * 0.5f))
    }
    return
  }
  val accent = visual.accent(tk.paper.luminance() < 0.5f)
  Box(
    modifier.size(size).background(accent.copy(alpha = 0.13f), KIND_TILE_SHAPE).border(Sun.Hairline, accent.copy(alpha = 0.45f), KIND_TILE_SHAPE),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      Icon(kindIcon(kind), contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.42f))
      Text(
        visual.tag,
        color = accent,
        fontSize = if (size >= 40.dp) 8.sp else 7.sp,
        lineHeight = if (size >= 40.dp) 9.sp else 8.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
      )
    }
  }
}

/** A folder path as a person says it: the home folder as "~". */
object PathText {
  fun tilde(path: String, home: String = System.getProperty("user.home").orEmpty()): String {
    if (home.isBlank()) return path
    val h = home.trimEnd('/', '\\')
    return when {
      path == h -> "~"
      path.startsWith("$h/") || path.startsWith("$h\\") -> "~" + path.substring(h.length)
      else -> path
    }
  }
}
