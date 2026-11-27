package com.craneai.findr.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.common.icon
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.SunType

/** Visual cue for a file's format: short aligned uppercase tag ("PDF", "DOCX", "XLSX", "PPTX") + accessible light/dark accent colors + icon. */
data class FileTypeStyle(
  val tag: String,
  val lightAccent: Color,
  val darkAccent: Color,
  val icon: ImageVector,
) {
  fun accent(darkSurface: Boolean): Color = if (darkSurface) darkAccent else lightAccent
}

object FileTypeVisual {
  private val RED_LIGHT = Color(0xFFB01E1E)
  private val RED_DARK = Color(0xFFF87171)

  private val BLUE_LIGHT = Color(0xFF1D4ED8)
  private val BLUE_DARK = Color(0xFF60A5FA)

  private val GREEN_LIGHT = Color(0xFF116631)
  private val GREEN_DARK = Color(0xFF4ADE80)

  private val ORANGE_LIGHT = Color(0xFFA33608)
  private val ORANGE_DARK = Color(0xFFFB923C)

  private val TEAL_LIGHT = Color(0xFF0B5E58)
  private val TEAL_DARK = Color(0xFF2DD4BF)

  private val INDIGO_LIGHT = Color(0xFF6D28D9)
  private val INDIGO_DARK = Color(0xFFA78BFA)

  private val VIOLET_LIGHT = Color(0xFF7B24CC)
  private val VIOLET_DARK = Color(0xFFC084FC)

  private val CYAN_LIGHT = Color(0xFF035C8C)
  private val CYAN_DARK = Color(0xFF38BDF8)

  private val WORD_EXTS = setOf("docx", "doc", "odt", "rtf", "pages")
  private val SHEET_EXTS = setOf("xlsx", "xls", "ods", "csv", "tsv", "numbers")
  private val SLIDE_EXTS = setOf("pptx", "ppt", "odp", "key")
  private val NOTE_EXTS = setOf("md", "markdown", "txt", "rst", "org")
  private val CODE_EXTS = setOf("json", "xml", "yaml", "yml", "toml", "ini", "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "h", "cpp", "rs", "go", "sh", "sql", "log", "svg")

  /** Maps a file's extension and [FileKind] to its color-coded visual tag & icon, or null when no special badge applies. */
  fun of(fileName: String?, kind: FileKind): FileTypeStyle? {
    val ext = fileName?.let { FileSubType.extensionOf(it) }
    val short = ext?.uppercase()?.let { if (it == "MARKDOWN") "MD" else it.take(4) }
    return when {
      kind == FileKind.PDF || ext == "pdf" -> FileTypeStyle("PDF", RED_LIGHT, RED_DARK, Icons.Outlined.PictureAsPdf)
      ext in WORD_EXTS -> FileTypeStyle(short ?: "DOCX", BLUE_LIGHT, BLUE_DARK, Icons.Outlined.Description)
      ext in SHEET_EXTS -> FileTypeStyle(short ?: "XLSX", GREEN_LIGHT, GREEN_DARK, Icons.Outlined.TableChart)
      ext in SLIDE_EXTS -> FileTypeStyle(short ?: "PPTX", ORANGE_LIGHT, ORANGE_DARK, Icons.Outlined.Slideshow)
      ext in NOTE_EXTS -> FileTypeStyle(short ?: "TXT", TEAL_LIGHT, TEAL_DARK, Icons.AutoMirrored.Outlined.TextSnippet)
      ext in CODE_EXTS -> FileTypeStyle(short ?: "CODE", INDIGO_LIGHT, INDIGO_DARK, Icons.Outlined.Code)
      kind == FileKind.VIDEO -> FileTypeStyle(short ?: "VID", VIOLET_LIGHT, VIOLET_DARK, Icons.Outlined.Videocam)
      kind == FileKind.AUDIO -> FileTypeStyle(short ?: "AUD", CYAN_LIGHT, CYAN_DARK, Icons.Outlined.AudioFile)
      else -> null
    }
  }
}

private val TYPE_BADGE_SHAPE = RoundedCornerShape(6.dp)
private val TYPE_BADGE_HAIRLINE = 1.dp

/** Compact color-coded format pill ("PDF", "DOCX", "XLSX", "PPTX", "WAV", "MP4") aligned beside the file name. */
@Composable
fun TypeBadge(style: FileTypeStyle, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  val accent = style.accent(tk.paper.luminance() < 0.5f)
  Text(
    style.tag,
    style = SunType.CaptionBold,
    color = accent,
    maxLines = 1,
    modifier = modifier
      .background(accent.copy(alpha = 0.14f), TYPE_BADGE_SHAPE)
      .border(TYPE_BADGE_HAIRLINE, accent.copy(alpha = 0.45f), TYPE_BADGE_SHAPE)
      .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

/** Color-coded icon tile for non-visual files (documents, PDFs, spreadsheets, slides, code, audio) matching Desktop 1.1.0. */
@Composable
fun ColorKindTile(
  fileName: String?,
  kind: FileKind,
  modifier: Modifier = Modifier,
  iconSize: Dp = 26.dp,
) {
  val tk = LocalSunlight.current
  val style = FileTypeVisual.of(fileName, kind)
  val dark = tk.paper.luminance() < 0.5f
  val accent = style?.accent(dark)
  val bg = accent?.copy(alpha = if (dark) 0.18f else 0.12f) ?: tk.paper2
  val borderColor = accent?.copy(alpha = 0.35f) ?: tk.line2
  val fg = accent ?: tk.ink2
  val icon = style?.icon ?: if (kind == FileKind.OTHER) Icons.AutoMirrored.Outlined.InsertDriveFile else kind.icon()
  Box(
    modifier
      .clip(MaterialTheme.shapes.extraSmall)
      .background(bg)
      .border(TYPE_BADGE_HAIRLINE, borderColor, MaterialTheme.shapes.extraSmall),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
  }
}
