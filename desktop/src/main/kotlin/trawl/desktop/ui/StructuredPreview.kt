package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craneai.findr.ui.theme.LocalSunlight
import java.io.File
import trawl.desktop.FileSubType

/** Structured preview renderer mode for non-media documents (zero external dependencies). */
enum class StructuredKind {
  JSON,
  TABLE,
  CODE,
  PROSE;

  /** Only wide tables and genuine source code / JSON scroll horizontally; all prose and Markdown documents soft-wrap. */
  val usesHorizontalScroll: Boolean
    get() = this == JSON || this == TABLE || this == CODE

  companion object {
    private val JSON_EXTS = setOf("json", "jsonl", "ndjson")
    private val TABLE_EXTS = setOf("csv", "tsv", "xlsx", "xls", "ods")
    private val CODE_EXTS = setOf(
      "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
      "sql", "yaml", "yml", "xml", "html", "htm", "css", "sh", "bash", "zsh",
      "rs", "go", "c", "h", "cpp", "hpp", "toml", "ini", "conf",
      "swift", "m", "mm", "rb", "php", "gradle",
    )
    private val MARKDOWN_EXTS = setOf("md", "markdown")
    private val PROSE_DISK_EXTS = setOf("md", "markdown", "txt", "log", "rst", "org")

    fun of(fileName: String?): StructuredKind {
      val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return PROSE
      return when (ext) {
        in JSON_EXTS -> JSON
        in TABLE_EXTS -> TABLE
        in CODE_EXTS -> CODE
        else -> PROSE
      }
    }

    /** True for Markdown documents (`.md`, `.markdown`) which get clean `#` heading styling with zero code-keyword noise. */
    fun isMarkdown(fileName: String?): Boolean {
      val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return false
      return ext in MARKDOWN_EXTS
    }

    /** True when the file on disk is UTF-8 plain text we can read directly (capped) for intact formatting/structure. */
    fun isPlainDiskText(fileName: String?): Boolean {
      val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return false
      return ext in JSON_EXTS || ext in CODE_EXTS || ext in PROSE_DISK_EXTS || ext == "csv" || ext == "tsv"
    }
  }
}

/** WCAG-AA (>= 4.5:1 on both `paper` and `paper2`) syntax colors for Light and Dark Sunlight surfaces. */
data class SyntaxPalette(
  val key: Color,
  val string: Color,
  val number: Color,
  val literal: Color,
  val comment: Color,
  val tag: Color,
  val punct: Color,
) {
  companion object {
    val LIGHT = SyntaxPalette(
      key = Color(0xFF1D4ED8),      // Blue (>= 6.0:1 on paper/paper2)
      string = Color(0xFF116631),   // Forest green (>= 5.9:1)
      number = Color(0xFFA33608),   // Burnt orange (>= 5.2:1)
      literal = Color(0xFF7B24CC),  // Deep violet (>= 5.3:1)
      comment = Color(0xFF57534A),  // Warm slate (>= 6.5:1)
      tag = Color(0xFF0B5E58),      // Deep teal (>= 6.3:1)
      punct = Color(0xFF57534A),
    )

    val DARK = SyntaxPalette(
      key = Color(0xFF60A5FA),      // Sky blue (>= 6.4:1 on dark paper/paper2)
      string = Color(0xFF4ADE80),   // Mint green (>= 9.8:1)
      number = Color(0xFFFB923C),   // Warm amber-orange (>= 7.4:1)
      literal = Color(0xFFC084FC),  // Soft violet (>= 6.7:1)
      comment = Color(0xFFB5AE9F),  // Warm gray (>= 7.5:1)
      tag = Color(0xFF2DD4BF),      // Teal (>= 9.2:1)
      punct = Color(0xFFB5AE9F),
    )

    fun of(dark: Boolean): SyntaxPalette = if (dark) DARK else LIGHT
  }
}

/** Reads up to [maxBytes] of a local UTF-8 text file safely on a background thread, stripping NUL/BOM. */
internal fun readDiskPreviewText(file: File, maxBytes: Int = 16_384): String? {
  if (!file.isFile || !file.canRead()) return null
  return runCatching {
    val buf = ByteArray(maxBytes)
    val n = file.inputStream().use { ins ->
      var total = 0
      while (total < maxBytes) {
        val r = ins.read(buf, total, maxBytes - total)
        if (r <= 0) break
        total += r
      }
      total
    }
    if (n <= 0) return@runCatching null
    // Guard against binary files disguised with a text extension.
    val checkLen = minOf(n, 512)
    for (i in 0 until checkLen) if (buf[i] == 0.toByte()) return@runCatching null
    String(buf, 0, n, Charsets.UTF_8).removePrefix("\uFEFF").takeIf { it.isNotBlank() }
  }.getOrNull()
}

/** Zero-dependency JSON pretty-printer (2-space indent, tolerates partial/truncated previews). */
object JsonFormatter {
  fun prettyPrint(raw: String, maxChars: Int = 10_000): String {
    val src = raw.trim()
    if (src.isEmpty()) return src
    val out = StringBuilder(minOf(src.length * 2, maxChars + 64))
    var indent = 0
    var inStr = false
    var escaped = false
    val n = src.length
    var i = 0
    fun newline() {
      out.append('\n')
      repeat(indent.coerceAtMost(12) * 2) { out.append(' ') }
    }
    while (i < n && out.length < maxChars) {
      val ch = src[i]
      if (inStr) {
        out.append(ch)
        if (escaped) escaped = false
        else if (ch == '\\') escaped = true
        else if (ch == '"') inStr = false
        i++
        continue
      }
      when (ch) {
        '"' -> { inStr = true; out.append(ch) }
        '{', '[' -> {
          // Keep empty {} / [] compact on one line.
          var j = i + 1
          while (j < n && src[j].isWhitespace()) j++
          val close = if (ch == '{') '}' else ']'
          if (j < n && src[j] == close) {
            out.append(ch).append(close)
            i = j + 1
            continue
          }
          out.append(ch)
          indent++
          newline()
        }
        '}', ']' -> {
          indent = (indent - 1).coerceAtLeast(0)
          newline()
          out.append(ch)
        }
        ',' -> {
          out.append(ch)
          newline()
        }
        ':' -> out.append(": ")
        ' ', '\t', '\r', '\n' -> {}
        else -> out.append(ch)
      }
      i++
    }
    if (i < n) out.append("\n…")
    return out.toString()
  }
}

/** Zero-dependency JSON syntax highlighter with optional query-term marker overlay. */
object JsonHighlighter {
  fun highlight(
    pretty: String,
    palette: SyntaxPalette,
    query: String = "",
    markerBg: Color = Color.Unspecified,
    markerFg: Color = Color.Unspecified,
  ): AnnotatedString = buildAnnotatedString {
    append(pretty)
    val n = pretty.length
    var i = 0
    while (i < n) {
      val ch = pretty[i]
      when {
        ch == '"' -> {
          val start = i
          i++
          var esc = false
          while (i < n) {
            val c = pretty[i]
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') { i++; break }
            i++
          }
          var j = i
          while (j < n && (pretty[j] == ' ' || pretty[j] == '\t')) j++
          val isKey = j < n && pretty[j] == ':'
          addStyle(
            SpanStyle(
              color = if (isKey) palette.key else palette.string,
              fontWeight = if (isKey) FontWeight.SemiBold else FontWeight.Normal,
            ),
            start,
            i,
          )
        }
        ch == '-' || ch in '0'..'9' -> {
          val start = i
          i++
          while (i < n && pretty[i] in "0123456789.eE+-") i++
          addStyle(SpanStyle(color = palette.number), start, i)
        }
        ch == 't' && pretty.startsWith("true", i) && isBoundary(pretty, i + 4) -> {
          addStyle(SpanStyle(color = palette.literal, fontWeight = FontWeight.SemiBold), i, i + 4)
          i += 4
        }
        ch == 'f' && pretty.startsWith("false", i) && isBoundary(pretty, i + 5) -> {
          addStyle(SpanStyle(color = palette.literal, fontWeight = FontWeight.SemiBold), i, i + 5)
          i += 5
        }
        ch == 'n' && pretty.startsWith("null", i) && isBoundary(pretty, i + 4) -> {
          addStyle(SpanStyle(color = palette.literal, fontWeight = FontWeight.SemiBold), i, i + 4)
          i += 4
        }
        ch in "{}[]:," -> {
          addStyle(SpanStyle(color = palette.punct), i, i + 1)
          i++
        }
        else -> i++
      }
    }
    applyQueryMarkers(pretty, query, markerBg, markerFg)
  }

  private fun isBoundary(s: String, idx: Int): Boolean =
    idx >= s.length || (!s[idx].isLetterOrDigit() && s[idx] != '_')
}

/** Parsed table ready for clean tabular rendering (CSV, TSV, or pipe-delimited Excel/ODS extract). */
data class ParsedTable(
  val sheetTitle: String?,
  val headers: List<String>,
  val rows: List<List<String>>,
  val truncatedRows: Boolean,
  val truncatedCols: Boolean,
)

/** Zero-dependency CSV / TSV / pipe-separated Excel table parser. */
object TableParser {
  fun parse(raw: String, fileName: String?, maxRows: Int = 60, maxCols: Int = 16): ParsedTable? {
    val ext = fileName?.let { FileSubType.extensionOf(it) }
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lineSequence()
      .map { it.trimEnd() }
      .filter { it.isNotBlank() }
      .take(maxRows + 3)
      .toList()
    if (lines.isEmpty()) return null

    var sheetTitle: String? = null
    val parsedRows: List<List<String>> = when (ext) {
      "tsv" -> lines.map { it.split('\t').map(String::trim) }
      "xlsx", "xls", "ods" -> {
        // OfficeText / XlsText emits optional sheet name line followed by "cell | cell" rows.
        val hasPipes = lines.any { " | " in it }
        if (!hasPipes) return null
        val start = if (" | " !in lines.first() && lines.size >= 2 && " | " in lines[1]) {
          sheetTitle = lines.first().trim()
          1
        } else 0
        lines.drop(start).map { line -> line.split(" | ").map(String::trim) }
      }
      else -> parseCsvLines(raw, maxRows + 2)
    }

    if (parsedRows.isEmpty()) return null
    val rawCols = parsedRows.maxOf { it.size }
    if (rawCols < 2 && parsedRows.size < 2) return null
    val cols = rawCols.coerceAtMost(maxCols)
    val truncatedCols = rawCols > maxCols
    val truncatedRows = parsedRows.size > maxRows + 1
    val capped = parsedRows.take(maxRows + 1).map { r ->
      List(cols) { c -> r.getOrElse(c) { "" } }
    }
    val headers = capped.first()
    val body = capped.drop(1)
    return ParsedTable(sheetTitle, headers, body, truncatedRows, truncatedCols)
  }

  /** RFC-4180 CSV parser handling quoted fields, embedded commas, and escaped `""`. */
  private fun parseCsvLines(raw: String, maxLines: Int): List<List<String>> {
    val rows = ArrayList<List<String>>()
    val row = ArrayList<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    val n = raw.length
    while (i < n && rows.size < maxLines) {
      val ch = raw[i]
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < n && raw[i + 1] == '"') {
            cell.append('"')
            i += 2
          } else {
            inQuotes = false
            i++
          }
        } else {
          cell.append(ch)
          i++
        }
      } else {
        when (ch) {
          '"' -> { inQuotes = true; i++ }
          ',' -> { row += cell.toString().trim(); cell.setLength(0); i++ }
          '\r', '\n' -> {
            if (ch == '\r' && i + 1 < n && raw[i + 1] == '\n') i++
            row += cell.toString().trim()
            cell.setLength(0)
            if (row.any { it.isNotEmpty() }) rows += row.toList()
            row.clear()
            i++
          }
          else -> { cell.append(ch); i++ }
        }
      }
    }
    if (rows.size < maxLines && (cell.isNotEmpty() || row.isNotEmpty())) {
      row += cell.toString().trim()
      if (row.any { it.isNotEmpty() }) rows += row.toList()
    }
    return rows
  }
}

/**
 * Clean prose highlighter for Markdown (`.md`, `.markdown`) and plain document excerpts (`.txt`, `.rtf`, `.docx`, `.pdf`,
 * `.odt`, `.pptx`, `.eml`, etc.). Styles `#` ATX Markdown headings (for Markdown files only) and query match markers only —
 * never applies code-keyword syntax colors to prose words like `and`, `with`, `or`, `from`, `return`.
 */
object ProseHighlighter {
  fun highlight(
    raw: String,
    isMarkdown: Boolean,
    headingColor: Color = Color.Unspecified,
    query: String = "",
    markerBg: Color = Color.Unspecified,
    markerFg: Color = Color.Unspecified,
    maxChars: Int = 10_000,
  ): AnnotatedString {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val src = if (normalized.length > maxChars) normalized.take(maxChars) + "\n…" else normalized
    return buildAnnotatedString {
      append(src)
      if (isMarkdown) {
        val n = src.length
        var i = 0
        var lineStart = true
        while (i < n) {
          val ch = src[i]
          if (ch == '\n') {
            lineStart = true
            i++
            continue
          }
          if (lineStart && ch == '#') {
            var hashes = 0
            while (i + hashes < n && src[i + hashes] == '#' && hashes < 6) hashes++
            val afterHashes = i + hashes
            val isHeading = hashes in 1..6 && (
              afterHashes >= n || src[afterHashes] == ' ' || src[afterHashes] == '\t' || src[afterHashes] == '\n'
            )
            if (isHeading) {
              val start = i
              var end = i
              while (end < n && src[end] != '\n') end++
              val headingSize = when (hashes) {
                1 -> 17.sp
                2 -> 15.5.sp
                else -> 14.5.sp
              }
              addStyle(
                SpanStyle(
                  color = headingColor,
                  fontWeight = FontWeight.Bold,
                  fontSize = headingSize,
                ),
                start,
                end,
              )
              i = end
              lineStart = false
              continue
            }
          }
          if (ch != ' ' && ch != '\t') lineStart = false
          i++
        }
      }
      applyQueryMarkers(src, query, markerBg, markerFg)
    }
  }
}

/** Lightweight zero-dependency code & config syntax highlighter (Kotlin/Java/Python/JS/TS/SQL/YAML/XML). */
object CodeHighlighter {
  private val COMMON_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "override", "private", "public", "internal", "protected",
    "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "import", "package", "companion", "suspend", "inline",
    "def", "lambda", "async", "await", "yield", "from", "as", "with", "pass", "elif", "except", "raise", "and", "or", "not", "in", "is",
    "function", "const", "let", "export", "default", "switch", "case", "break", "continue", "new", "this", "super", "extends", "implements", "typeof", "type",
    "struct", "impl", "fn", "pub", "mod", "use", "match", "mut", "trait", "func", "defer", "go", "chan", "range", "void", "static", "final",
    "select", "where", "join", "left", "right", "inner", "outer", "group", "order", "by", "having", "limit", "offset", "insert", "into", "values",
    "update", "set", "delete", "create", "table", "index", "alter", "drop", "distinct", "union", "on", "begin", "commit", "end", "trigger", "pragma",
  )
  private val LITERALS = setOf("true", "false", "null", "nil", "none", "None", "True", "False", "undefined")
  private val HASH_COMMENT_EXTS = setOf("py", "sh", "bash", "zsh", "yaml", "yml", "toml", "ini", "conf", "rb")
  private val SQL_EXTS = setOf("sql")
  private val MARKUP_EXTS = setOf("xml", "html", "htm")

  fun highlight(
    raw: String,
    ext: String?,
    palette: SyntaxPalette,
    query: String = "",
    markerBg: Color = Color.Unspecified,
    markerFg: Color = Color.Unspecified,
    maxChars: Int = 8_000,
  ): AnnotatedString {
    val e = ext?.lowercase().orEmpty()
    if (e == "md" || e == "markdown") {
      return ProseHighlighter.highlight(
        raw = raw,
        isMarkdown = true,
        headingColor = Color.Unspecified,
        query = query,
        markerBg = markerBg,
        markerFg = markerFg,
        maxChars = maxChars,
      )
    }
    val src = if (raw.length > maxChars) raw.take(maxChars) + "\n…" else raw
    val hashComments = e in HASH_COMMENT_EXTS
    val sqlMode = e in SQL_EXTS
    val markupMode = e in MARKUP_EXTS
    val yamlMode = e == "yaml" || e == "yml" || e == "toml" || e == "ini"

    return buildAnnotatedString {
      append(src)
      val n = src.length
      var i = 0
      var lineStart = true
      while (i < n) {
        val ch = src[i]
        if (ch == '\n') { lineStart = true; i++; continue }
        // Comments
        if ((ch == '/' && i + 1 < n && src[i + 1] == '/') ||
          (hashComments && ch == '#') ||
          (sqlMode && ch == '-' && i + 1 < n && src[i + 1] == '-')
        ) {
          val start = i
          while (i < n && src[i] != '\n') i++
          addStyle(SpanStyle(color = palette.comment), start, i)
          lineStart = false
          continue
        }
        if (ch == '/' && i + 1 < n && src[i + 1] == '*') {
          val start = i
          i += 2
          while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
          i = (i + 2).coerceAtMost(n)
          addStyle(SpanStyle(color = palette.comment), start, i)
          lineStart = false
          continue
        }
        // Markup tags (<tag ...>)
        if (markupMode && ch == '<') {
          val start = i
          i++
          if (i < n && (src[i] == '/' || src[i] == '!' || src[i] == '?')) i++
          while (i < n && (src[i].isLetterOrDigit() || src[i] in "-_:")) i++
          if (i > start + 1) addStyle(SpanStyle(color = palette.tag, fontWeight = FontWeight.SemiBold), start, i)
          lineStart = false
          continue
        }
        // Strings
        if (ch == '"' || ch == '\'' || ch == '`') {
          val quote = ch
          val start = i
          i++
          var esc = false
          while (i < n && src[i] != '\n') {
            val c = src[i]
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == quote) { i++; break }
            i++
          }
          addStyle(SpanStyle(color = palette.string), start, i)
          lineStart = false
          continue
        }
        // Annotations (@Composable)
        if (ch == '@' && i + 1 < n && src[i + 1].isLetter()) {
          val start = i
          i++
          while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
          addStyle(SpanStyle(color = palette.tag), start, i)
          lineStart = false
          continue
        }
        // Numbers
        if (ch in '0'..'9' && (i == 0 || (!src[i - 1].isLetterOrDigit() && src[i - 1] != '_'))) {
          val start = i
          i++
          while (i < n && (src[i].isLetterOrDigit() || src[i] in ".xX_")) i++
          addStyle(SpanStyle(color = palette.number), start, i)
          lineStart = false
          continue
        }
        // Identifiers / YAML keys / Keywords / Literals
        if (ch.isLetter() || ch == '_') {
          val start = i
          i++
          while (i < n && (src[i].isLetterOrDigit() || src[i] in "_-")) {
            if (src[i] == '-' && !yamlMode) break
            i++
          }
          val word = src.substring(start, i)
          var j = i
          while (j < n && src[j] == ' ') j++
          val isConfigKey = yamlMode && lineStart && j < n && (src[j] == ':' || src[j] == '=')
          when {
            isConfigKey -> addStyle(SpanStyle(color = palette.key, fontWeight = FontWeight.SemiBold), start, i)
            word in LITERALS -> addStyle(SpanStyle(color = palette.literal, fontWeight = FontWeight.SemiBold), start, i)
            word in COMMON_KEYWORDS || (sqlMode && word.lowercase() in COMMON_KEYWORDS) ->
              addStyle(SpanStyle(color = palette.key, fontWeight = FontWeight.SemiBold), start, i)
          }
          lineStart = false
          continue
        }
        if (!ch.isWhitespace()) lineStart = false
        i++
      }
      applyQueryMarkers(src, query, markerBg, markerFg)
    }
  }
}

private fun AnnotatedString.Builder.applyQueryMarkers(
  text: String,
  query: String,
  markerBg: Color,
  markerFg: Color,
) {
  if (query.isBlank() || markerBg == Color.Unspecified) return
  val words = query.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }.distinct()
  if (words.isEmpty()) return
  val rx = Regex(words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
  val mark = SpanStyle(background = markerBg, color = markerFg, fontWeight = FontWeight.Bold)
  for (m in rx.findAll(text)) {
    addStyle(mark, m.range.first, m.range.last + 1)
  }
}

private val MONO_CODE_STYLE = TextStyle(
  fontFamily = FontFamily.Monospace,
  fontSize = 12.5.sp,
  lineHeight = 18.5.sp,
)

private val MONO_GUTTER_STYLE = TextStyle(
  fontFamily = FontFamily.Monospace,
  fontSize = 11.5.sp,
  lineHeight = 18.5.sp,
)

/**
 * Renders structured content for `.json` (pretty-printed + syntax highlighted), `.csv`/`.tsv`/`.xlsx`/`.xls`/`.ods`
 * (aligned scrollable table with bold header + alternating striping), code/config files (monospace syntax highlighting),
 * and Markdown/prose documents (clean soft-wrapped prose with zero horizontal scroll or code-keyword noise).
 * Returns `false` if the input cannot be rendered (e.g. malformed table falling back to plain text).
 */
@Composable
fun StructuredContentView(
  fileName: String?,
  rawText: String,
  query: String = "",
  maxLines: Int = 48,
  modifier: Modifier = Modifier,
): Boolean {
  val kind = remember(fileName) { StructuredKind.of(fileName) }
  val tk = LocalSunlight.current
  val dark = tk.paper.luminance() < 0.5f
  val palette = remember(dark) { SyntaxPalette.of(dark) }

  return when (kind) {
    StructuredKind.JSON -> {
      val formatted = remember(rawText, maxLines) {
        val pretty = JsonFormatter.prettyPrint(rawText)
        val lines = pretty.lines()
        if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") + "\n…" else pretty
      }
      val styled = remember(formatted, palette, query, tk.marker, tk.onMarker) {
        JsonHighlighter.highlight(formatted, palette, query, tk.marker, tk.onMarker)
      }
      val shape = RoundedCornerShape(8.dp)
      Box(
        modifier.fillMaxWidth().clip(shape).background(tk.paper2.copy(alpha = 0.65f))
          .border(Sun.Hairline, tk.line2, shape).horizontalScroll(rememberScrollState()).padding(Sun.S12),
      ) {
        SelectionContainer {
          Text(styled, style = MONO_CODE_STYLE, color = tk.ink, softWrap = false)
        }
      }
      true
    }
    StructuredKind.TABLE -> {
      val table = remember(rawText, fileName, maxLines) {
        TableParser.parse(rawText, fileName, maxRows = maxLines.coerceIn(12, 60))
      } ?: return false
      StructuredTableView(table, query, modifier)
      true
    }
    StructuredKind.CODE -> {
      val ext = remember(fileName) { fileName?.let { FileSubType.extensionOf(it) } }
      val clipped = remember(rawText, maxLines) {
        val lines = rawText.replace("\r\n", "\n").lines()
        if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") + "\n…" else rawText.replace("\r\n", "\n")
      }
      val lineCount = remember(clipped) { clipped.count { it == '\n' } + 1 }
      val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
      val styled = remember(clipped, ext, palette, query, tk.marker, tk.onMarker) {
        CodeHighlighter.highlight(clipped, ext, palette, query, tk.marker, tk.onMarker)
      }
      val shape = RoundedCornerShape(8.dp)
      Row(
        modifier.fillMaxWidth().clip(shape).background(tk.paper2.copy(alpha = 0.65f))
          .border(Sun.Hairline, tk.line2, shape).horizontalScroll(rememberScrollState()).padding(Sun.S12),
        horizontalArrangement = Arrangement.spacedBy(Sun.S12),
      ) {
        Text(
          gutter,
          style = MONO_GUTTER_STYLE,
          color = palette.comment,
          textAlign = TextAlign.End,
          softWrap = false,
        )
        SelectionContainer {
          Text(styled, style = MONO_CODE_STYLE, color = tk.ink, softWrap = false)
        }
      }
      true
    }
    StructuredKind.PROSE -> {
      val isMarkdown = remember(fileName) { StructuredKind.isMarkdown(fileName) }
      val clipped = remember(rawText, maxLines) {
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") + "\n…" else normalized
      }
      val styled = remember(clipped, isMarkdown, tk.ink, query, tk.marker, tk.onMarker) {
        ProseHighlighter.highlight(
          raw = clipped,
          isMarkdown = isMarkdown,
          headingColor = tk.ink,
          query = query,
          markerBg = tk.marker,
          markerFg = tk.onMarker,
        )
      }
      SelectionContainer(modifier.fillMaxWidth()) {
        Text(
          styled,
          style = SunType.BodyLarge,
          color = tk.ink,
          softWrap = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      true
    }
  }
}

@Composable
private fun StructuredTableView(table: ParsedTable, query: String, modifier: Modifier) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(8.dp)
  val colWidths = remember(table) {
    val cols = table.headers.size
    List(cols) { c ->
      val maxChars = maxOf(
        table.headers[c].length,
        table.rows.take(30).maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0,
      )
      (maxChars * 7.5f + 22f).coerceIn(76f, 220f).dp
    }
  }
  val marker = SpanStyle(background = tk.marker, color = tk.onMarker, fontWeight = FontWeight.Bold)
  val queryRegex = remember(query) { compileQueryRegex(query) }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S4)) {
    if (!table.sheetTitle.isNullOrBlank()) {
      Text(table.sheetTitle, style = SunType.CaptionBold, color = tk.ink2)
    }
    Box(
      Modifier.fillMaxWidth().clip(shape).background(tk.paper).border(Sun.Hairline, tk.line2, shape)
        .horizontalScroll(rememberScrollState()),
    ) {
      SelectionContainer {
        Column {
          // Bold sticky-style header row
          Row(
            Modifier.background(tk.paper2).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            table.headers.forEachIndexed { idx, cell ->
              Box(
                Modifier.width(colWidths[idx]).fillMaxHeight()
                  .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart,
              ) {
                Text(
                  highlightCell(cell, queryRegex, marker),
                  style = SunType.CaptionBold,
                  color = tk.ink,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              if (idx < table.headers.lastIndex) {
                Box(Modifier.width(Sun.Hairline).fillMaxHeight().background(tk.line2.copy(alpha = 0.5f)))
              }
            }
          }
          Box(Modifier.fillMaxWidth().height(Sun.Hairline).background(tk.line))
          // Data rows with alternating subtle paper2 striping
          table.rows.forEachIndexed { rowIdx, cells ->
            val bg = if (rowIdx % 2 == 1) tk.paper2.copy(alpha = 0.55f) else tk.paper
            Row(
              Modifier.background(bg).height(IntrinsicSize.Min),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              cells.forEachIndexed { colIdx, cell ->
                Box(
                  Modifier.width(colWidths[colIdx]).fillMaxHeight()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                  contentAlignment = Alignment.CenterStart,
                ) {
                  Text(
                    highlightCell(cell, queryRegex, marker),
                    style = SunType.Caption,
                    color = tk.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                if (colIdx < cells.lastIndex) {
                  Box(Modifier.width(Sun.Hairline).fillMaxHeight().background(tk.line2.copy(alpha = 0.3f)))
                }
              }
            }
            if (rowIdx < table.rows.lastIndex) {
              Box(Modifier.fillMaxWidth().height(Sun.Hairline).background(tk.line2.copy(alpha = 0.35f)))
            }
          }
        }
      }
    }
    if (table.truncatedRows || table.truncatedCols) {
      val hint = buildString {
        append("Showing first ${table.rows.size} rows")
        if (table.truncatedCols) append(" · ${table.headers.size} columns")
      }
      Text(hint, style = SunType.Caption, color = tk.ink2)
    }
  }
}

private fun compileQueryRegex(query: String): Regex? {
  if (query.isBlank()) return null
  val words = query.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }.distinct()
  if (words.isEmpty()) return null
  return Regex(words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
}

private fun highlightCell(text: String, rx: Regex?, style: SpanStyle): AnnotatedString {
  if (rx == null || text.isBlank()) return AnnotatedString(text)
  return buildAnnotatedString {
    append(text)
    for (m in rx.findAll(text)) addStyle(style, m.range.first, m.range.last + 1)
  }
}
