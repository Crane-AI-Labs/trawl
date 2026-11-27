package com.craneai.findr.ui.search

/**
 * Pure, zero-dependency formatting and tokenization for Android's structured file preview sheet:
 * pretty-printed syntax-highlighted JSON, RFC-4180 CSV/TSV & Excel tabular grids, lightweight code highlighting,
 * and calm soft-wrapped prose/document preview (Markdown headings + query match highlights only, zero code-keyword noise).
 */
object StructuredPreview {
  enum class PreviewKind { JSON, TABLE, CODE, PROSE }

  enum class TokenKind { PLAIN, KEY, STRING, NUMBER, BOOLEAN_NULL, KEYWORD, COMMENT, PUNCTUATION, HEADING, MATCH }

  data class HighlightSpan(val start: Int, val end: Int, val kind: TokenKind)

  data class TableData(
    val headers: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean = false,
  )

  const val MAX_PREVIEW_BYTES = 128 * 1024
  const val MAX_TABLE_ROWS = 200
  const val MAX_TABLE_COLS = 24
  private const val MAX_MATCH_SPANS = 200

  private val TABLE_TEXT_EXTS = setOf("csv", "tsv")
  private val SPREADSHEET_EXTS = setOf("xlsx", "xls", "ods")
  private val PROSE_TEXT_EXTS = setOf("md", "markdown", "txt", "log", "rst", "org", "eml")
  private val DOCUMENT_EXTRACT_EXTS = setOf("pdf", "docx", "doc", "odt", "rtf", "pptx", "ppt", "odp")
  private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "sql", "yaml", "yml",
    "xml", "sh", "c", "h", "cpp", "rs", "go", "html", "htm",
    "css", "toml", "ini",
  )

  private val QUERY_TERM_RE = Regex("""[\p{L}\p{Nd}_'-]{2,}""")

  private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "enum", "data", "sealed", "private", "public", "internal",
    "protected", "override", "suspend", "inline", "return", "if", "else", "when", "for", "while", "try", "catch",
    "finally", "throw", "import", "package", "def", "lambda", "from", "as", "with", "yield", "async", "await",
    "function", "const", "let", "export", "default", "switch", "case", "break", "continue", "new", "this", "super",
    "select", "where", "order", "group", "by", "join", "left", "inner", "on", "insert", "into", "values", "update",
    "set", "delete", "create", "table", "index", "limit", "and", "or", "not", "in", "is", "null", "true", "false",
    "none", "struct", "impl", "fn", "pub", "use", "match", "type", "static", "void", "int", "long", "float", "double",
    "boolean", "string",
  )

  fun kindOf(fileName: String?): PreviewKind? {
    val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return null
    return when {
      ext == "json" -> PreviewKind.JSON
      ext in TABLE_TEXT_EXTS || ext in SPREADSHEET_EXTS -> PreviewKind.TABLE
      ext in PROSE_TEXT_EXTS || ext in DOCUMENT_EXTRACT_EXTS -> PreviewKind.PROSE
      ext in CODE_EXTS -> PreviewKind.CODE
      else -> null
    }
  }

  /** Files that open directly into [StructuredPreviewSheet] on tap (Android rarely has a clean native viewer for JSON/CSV/code/Markdown/text). */
  fun isDirectPreviewable(fileName: String?): Boolean {
    val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return false
    return ext == "json" || ext in TABLE_TEXT_EXTS || ext in CODE_EXTS || ext in PROSE_TEXT_EXTS
  }

  fun isSpreadsheetBinary(fileName: String?): Boolean {
    val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return false
    return ext in SPREADSHEET_EXTS
  }

  fun isDocumentExtracted(fileName: String?): Boolean {
    val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return false
    return ext in DOCUMENT_EXTRACT_EXTS
  }

  fun isMarkdown(fileName: String?): Boolean {
    val ext = fileName?.let { FileSubType.extensionOf(it) } ?: return false
    return ext == "md" || ext == "markdown"
  }

  fun delimiterOf(fileName: String?): Char =
    if (fileName?.let { FileSubType.extensionOf(it) } == "tsv") '\t' else ','

  /** Pretty-prints JSON with 2-space indentation; preserves strings/escapes and falls back gracefully on truncated input. */
  fun prettyJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val sb = StringBuilder(trimmed.length + trimmed.length / 4)
    var indent = 0
    var inString = false
    var escaped = false
    var i = 0
    val n = trimmed.length
    fun appendIndent() {
      repeat(indent.coerceIn(0, 16)) { sb.append("  ") }
    }
    while (i < n) {
      val ch = trimmed[i]
      if (inString) {
        sb.append(ch)
        if (escaped) escaped = false
        else if (ch == '\\') escaped = true
        else if (ch == '"') inString = false
        i++
        continue
      }
      when (ch) {
        '"' -> {
          inString = true
          sb.append(ch)
        }
        '{', '[' -> {
          sb.append(ch)
          var j = i + 1
          while (j < n && trimmed[j].isWhitespace()) j++
          if (j < n && ((ch == '{' && trimmed[j] == '}') || (ch == '[' && trimmed[j] == ']'))) {
            sb.append(trimmed[j])
            i = j + 1
            continue
          }
          indent++
          sb.append('\n')
          appendIndent()
        }
        '}', ']' -> {
          indent = (indent - 1).coerceAtLeast(0)
          sb.append('\n')
          appendIndent()
          sb.append(ch)
        }
        ',' -> {
          sb.append(",\n")
          appendIndent()
        }
        ':' -> sb.append(": ")
        ' ', '\t', '\r', '\n' -> {}
        else -> sb.append(ch)
      }
      i++
    }
    return sb.toString()
  }

  /** Tokenizes pretty-printed JSON for WCAG-AA Sunlight syntax highlighting. */
  fun tokenizeJson(text: String): List<HighlightSpan> {
    val out = ArrayList<HighlightSpan>()
    val n = text.length
    var i = 0
    while (i < n) {
      val ch = text[i]
      when {
        ch.isWhitespace() -> i++
        ch == '"' -> {
          val start = i
          i++
          var esc = false
          while (i < n) {
            val c = text[i++]
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == '"') break
          }
          var j = i
          while (j < n && text[j].isWhitespace()) j++
          val isKey = j < n && text[j] == ':'
          out += HighlightSpan(start, i, if (isKey) TokenKind.KEY else TokenKind.STRING)
        }
        ch == '-' || ch.isDigit() -> {
          val start = i
          i++
          while (i < n && (text[i].isDigit() || text[i] in ".eE+-")) i++
          out += HighlightSpan(start, i, TokenKind.NUMBER)
        }
        ch.isLetter() -> {
          val start = i
          while (i < n && text[i].isLetter()) i++
          val word = text.substring(start, i)
          val kind = if (word == "true" || word == "false" || word == "null") TokenKind.BOOLEAN_NULL else TokenKind.PLAIN
          out += HighlightSpan(start, i, kind)
        }
        ch in "{}[]:," -> {
          out += HighlightSpan(i, i + 1, TokenKind.PUNCTUATION)
          i++
        }
        else -> i++
      }
    }
    return out
  }

  /** RFC-4180 CSV/TSV parser supporting quoted fields, escaped quotes (`""`), and embedded delimiters/newlines. */
  fun parseDelimited(raw: String, delimiter: Char = ',', maxRows: Int = MAX_TABLE_ROWS, maxCols: Int = MAX_TABLE_COLS): TableData {
    val clean = raw.removePrefix("\uFEFF")
    if (clean.isBlank()) return TableData(emptyList(), emptyList(), truncated = false)
    val allRows = ArrayList<List<String>>()
    var currentRow = ArrayList<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    val n = clean.length
    var truncated = false

    fun finishCell() {
      if (currentRow.size < maxCols) currentRow += cell.toString().trim()
      cell.setLength(0)
    }
    fun finishRow() {
      finishCell()
      if (currentRow.any { it.isNotEmpty() }) {
        if (allRows.size >= maxRows + 1) {
          truncated = true
        } else {
          allRows += currentRow
        }
      }
      currentRow = ArrayList()
    }

    while (i < n && !truncated) {
      val ch = clean[i]
      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < n && clean[i + 1] == '"') {
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
          '"' -> {
            if (cell.isEmpty()) inQuotes = true else cell.append(ch)
            i++
          }
          delimiter -> {
            finishCell()
            i++
          }
          '\r' -> {
            finishRow()
            i += if (i + 1 < n && clean[i + 1] == '\n') 2 else 1
          }
          '\n' -> {
            finishRow()
            i++
          }
          else -> {
            cell.append(ch)
            i++
          }
        }
      }
    }
    if (!truncated && (cell.isNotEmpty() || currentRow.isNotEmpty())) finishRow()
    if (allRows.isEmpty()) return TableData(emptyList(), emptyList(), truncated = false)
    val width = allRows.maxOf { it.size }.coerceIn(1, maxCols)
    val headers = allRows.first().let { h -> List(width) { idx -> h.getOrElse(idx) { "" } } }
    val body = allRows.drop(1).map { r -> List(width) { idx -> r.getOrElse(idx) { "" } } }
    return TableData(headers, body, truncated)
  }

  /** Turns `OfficeText`'s extracted tab/newline spreadsheet text (`.xlsx`/`.xls`/`.ods`) into tabular grid rows. */
  fun parseSpreadsheetText(extractedText: String, maxRows: Int = MAX_TABLE_ROWS, maxCols: Int = MAX_TABLE_COLS): TableData =
    parseDelimited(extractedText, delimiter = '\t', maxRows = maxRows, maxCols = maxCols)

  /**
   * Calm tokenizer for prose & document previews (`.md`, `.txt`, `.pdf`/`.docx`/`.rtf` extracted text, `.log`, `.eml`).
   * Emits ONLY Markdown `#` heading lines (when [markdown] is true) and case-insensitive [query] match spans —
   * never code-keyword highlighting on English prose words (`and`, `with`, `or`, `in`, `is`, etc.).
   */
  fun tokenizeProse(text: String, markdown: Boolean, query: String = ""): List<HighlightSpan> {
    val out = ArrayList<HighlightSpan>()
    val n = text.length
    if (markdown) {
      var lineStart = 0
      while (lineStart < n) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) n else nl
        var p = lineStart
        while (p < lineEnd && (text[p] == ' ' || text[p] == '\t')) p++
        var hashes = 0
        while (p + hashes < lineEnd && hashes < 6 && text[p + hashes] == '#') hashes++
        if (hashes in 1..6 && p + hashes < lineEnd && text[p + hashes] == ' ') {
          out += HighlightSpan(lineStart, lineEnd, TokenKind.HEADING)
        }
        lineStart = if (nl < 0) n else nl + 1
      }
    }
    val terms = QUERY_TERM_RE.findAll(query).map { it.value.lowercase() }.distinct().sortedByDescending { it.length }.toList()
    if (terms.isNotEmpty()) {
      var count = 0
      var i = 0
      while (i < n && count < MAX_MATCH_SPANS) {
        var matchedLen = 0
        for (term in terms) {
          if (i + term.length <= n && text.regionMatches(i, term, 0, term.length, ignoreCase = true)) {
            matchedLen = term.length
            break
          }
        }
        if (matchedLen > 0) {
          out += HighlightSpan(i, i + matchedLen, TokenKind.MATCH)
          count++
          i += matchedLen
        } else {
          i++
        }
      }
    }
    return out
  }

  /** Lightweight zero-dependency syntax tokenizer for code and config files. */
  fun tokenizeCode(text: String, ext: String): List<HighlightSpan> {
    val out = ArrayList<HighlightSpan>()
    val hashComments = ext in setOf("py", "sh", "yaml", "yml", "toml", "ini")
    val dashComments = ext == "sql"
    val n = text.length
    var i = 0
    while (i < n) {
      val ch = text[i]
      when {
        ch == '/' && i + 1 < n && text[i + 1] == '/' -> {
          val start = i
          while (i < n && text[i] != '\n') i++
          out += HighlightSpan(start, i, TokenKind.COMMENT)
        }
        ch == '/' && i + 1 < n && text[i + 1] == '*' -> {
          val start = i
          i += 2
          while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
          i = (i + 2).coerceAtMost(n)
          out += HighlightSpan(start, i, TokenKind.COMMENT)
        }
        hashComments && ch == '#' -> {
          val start = i
          while (i < n && text[i] != '\n') i++
          out += HighlightSpan(start, i, TokenKind.COMMENT)
        }
        dashComments && ch == '-' && i + 1 < n && text[i + 1] == '-' -> {
          val start = i
          while (i < n && text[i] != '\n') i++
          out += HighlightSpan(start, i, TokenKind.COMMENT)
        }
        ch == '"' || ch == '\'' -> {
          val quote = ch
          val start = i++
          var esc = false
          while (i < n) {
            val c = text[i++]
            if (esc) esc = false
            else if (c == '\\') esc = true
            else if (c == quote || c == '\n') break
          }
          out += HighlightSpan(start, i, TokenKind.STRING)
        }
        ch.isDigit() -> {
          val start = i
          while (i < n && (text[i].isDigit() || text[i] in ".xXa-fA-F_")) i++
          out += HighlightSpan(start, i, TokenKind.NUMBER)
        }
        ch.isLetter() || ch == '_' -> {
          val start = i
          while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
          val word = text.substring(start, i)
          if (word.lowercase() in KEYWORDS) {
            val kind = if (word.lowercase() in setOf("true", "false", "null", "none")) TokenKind.BOOLEAN_NULL else TokenKind.KEYWORD
            out += HighlightSpan(start, i, kind)
          }
        }
        else -> i++
      }
    }
    return out
  }
}
