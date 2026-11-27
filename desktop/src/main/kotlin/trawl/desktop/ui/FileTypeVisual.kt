package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.FileSubType

/** Visual cue for a file's format: short aligned uppercase tag ("PDF", "DOCX", "XLSX", "PPTX") + accessible light/dark accent colors. */
data class FileTypeStyle(
  val tag: String,
  val lightAccent: Color,
  val darkAccent: Color,
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

  /** Maps a file's extension and [FileKind] to its color-coded visual tag, or null when no special badge applies. */
  fun of(fileName: String?, kind: FileKind): FileTypeStyle? {
    val ext = fileName?.let { FileSubType.extensionOf(it) }
    val short = ext?.uppercase()?.let { if (it == "MARKDOWN") "MD" else it.take(4) }
    return when {
      kind == FileKind.PDF || ext == "pdf" -> FileTypeStyle("PDF", RED_LIGHT, RED_DARK)
      ext in WORD_EXTS -> FileTypeStyle(short ?: "DOCX", BLUE_LIGHT, BLUE_DARK)
      ext in SHEET_EXTS -> FileTypeStyle(short ?: "XLSX", GREEN_LIGHT, GREEN_DARK)
      ext in SLIDE_EXTS -> FileTypeStyle(short ?: "PPTX", ORANGE_LIGHT, ORANGE_DARK)
      ext in NOTE_EXTS -> FileTypeStyle(short ?: "TXT", TEAL_LIGHT, TEAL_DARK)
      ext in CODE_EXTS -> FileTypeStyle(short ?: "CODE", INDIGO_LIGHT, INDIGO_DARK)
      kind == FileKind.VIDEO -> FileTypeStyle(short ?: "VID", VIOLET_LIGHT, VIOLET_DARK)
      kind == FileKind.AUDIO -> FileTypeStyle(short ?: "AUD", CYAN_LIGHT, CYAN_DARK)
      else -> null
    }
  }
}

private val TYPE_BADGE_SHAPE = RoundedCornerShape(6.dp)

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
      .border(Sun.Hairline, accent.copy(alpha = 0.45f), TYPE_BADGE_SHAPE)
      .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}
