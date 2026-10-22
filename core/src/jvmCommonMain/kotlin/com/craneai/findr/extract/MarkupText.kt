package com.craneai.findr.extract

/**
 * HTML as the words a reader sees. A hand-rolled scanner rather than an HTML parser: it never throws, runs in one
 * pass, and also reads the sloppy (and XHTML) markup that epub chapters and saved web pages are made of.
 */
object HtmlText {
  /** Content of these is code or styling, never prose. */
  private val skipped = setOf("script", "style", "template", "svg", "noscript")

  /** Tags that end a line. */
  private val blocks = setOf(
    "p", "div", "br", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6", "section", "article",
    "header", "footer", "nav", "aside", "blockquote", "pre", "hr", "dl", "dt", "dd", "form", "title", "figure",
    "figcaption", "caption", "tbody", "thead", "tfoot", "body", "address", "details", "summary", "main",
  )

  fun toText(html: String): String {
    val out = StringBuilder(html.length / 2)
    var i = 0
    val n = html.length

    fun newline() {
      while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
      if (out.isEmpty()) return
      val l = out.length
      if (out[l - 1] == '\n' && l >= 2 && out[l - 2] == '\n') return
      out.append('\n')
    }

    fun space() {
      if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n' && out.last() != '\t') out.append(' ')
    }

    while (i < n) {
      val c = html[i]
      when {
        c == '<' && html.startsWith("<!--", i) -> {
          val end = html.indexOf("-->", i + 4)
          i = if (end < 0) n else end + 3
        }
        c == '<' && i + 1 < n && (html[i + 1].isLetter() || html[i + 1] == '/') -> {
          val close = html[i + 1] == '/'
          var j = i + (if (close) 2 else 1)
          val nameStart = j
          while (j < n && !html[j].isWhitespace() && html[j] != '>' && html[j] != '/') j++
          val name = html.substring(nameStart, j).lowercase().substringAfter(':')
          j = endOfTag(html, j)
          i = j
          if (!close && name in skipped) {
            i = endOfElement(html, name, i)
          } else if (name == "td" || name == "th") {
            space()
          } else if (name in blocks) {
            newline()
          }
        }
        c == '<' && i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?') -> {
          val end = html.indexOf('>', i)
          i = if (end < 0) n else end + 1
        }
        c == '&' -> {
          val (text, next) = decodeEntity(html, i)
          if (text == " ") space() else out.append(text)
          i = next
        }
        c.isWhitespace() || c == ' ' -> {
          space()
          i++
        }
        else -> {
          out.append(c)
          i++
        }
      }
    }
    return out.toString().trim()
  }

  /** Index just past the `>` that closes a tag opened before `from`, ignoring `>` inside quoted attribute values. */
  private fun endOfTag(s: String, from: Int): Int {
    var j = from
    var quote = '\u0000'
    while (j < s.length) {
      val c = s[j]
      if (quote != '\u0000') {
        if (c == quote) quote = '\u0000'
      } else if (c == '"' || c == '\'') {
        quote = c
      } else if (c == '>') {
        return j + 1
      }
      j++
    }
    return s.length
  }

  /** Index just past `</name>` (or the end of the text when it is never closed). */
  private fun endOfElement(s: String, name: String, from: Int): Int {
    val pattern = "</$name"
    val at = s.indexOf(pattern, from, ignoreCase = true)
    if (at < 0) return s.length
    return endOfTag(s, at + pattern.length)
  }

  /** Decodes the entity at `s[at] == '&'`. Unknown ones stay as literal text. Returns the text and the next index. */
  private fun decodeEntity(s: String, at: Int): Pair<String, Int> {
    val semi = s.indexOf(';', at + 1)
    if (semi < 0 || semi - at > 10) return "&" to at + 1
    val body = s.substring(at + 1, semi)
    val code = when {
      body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
      body.startsWith("#") -> body.substring(1).toIntOrNull()
      else -> HtmlEntities.named[body]
    }
    if (code == null || code <= 0 || code > 0x10FFFF || (code in 0xD800..0xDFFF)) return "&" to at + 1
    val text = if (code == 0xA0) " " else String(Character.toChars(code))
    return text to semi + 1
  }
}

object HtmlEntities {
  private val latin1 = (
    "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute " +
      "micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring " +
      "AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml " +
      "times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil " +
      "egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash " +
      "ugrave uacute ucirc uuml yacute thorn yuml"
    ).split(' ')

  /** The XML ones, HTML 4's Latin-1 block, and the punctuation that turns up in real pages. */
  val named: Map<String, Int> = buildMap {
    put("amp", '&'.code); put("lt", '<'.code); put("gt", '>'.code); put("quot", '"'.code); put("apos", '\''.code)
    latin1.forEachIndexed { index, name -> put(name, 0xA0 + index) }
    put("ndash", 0x2013); put("mdash", 0x2014); put("lsquo", 0x2018); put("rsquo", 0x2019); put("sbquo", 0x201A)
    put("ldquo", 0x201C); put("rdquo", 0x201D); put("bdquo", 0x201E); put("dagger", 0x2020); put("bull", 0x2022)
    put("hellip", 0x2026); put("permil", 0x2030); put("prime", 0x2032); put("lsaquo", 0x2039); put("rsaquo", 0x203A)
    put("euro", 0x20AC); put("trade", 0x2122); put("larr", 0x2190); put("rarr", 0x2192); put("uarr", 0x2191)
    put("darr", 0x2193); put("ensp", 0x2002); put("emsp", 0x2003); put("thinsp", 0x2009); put("OElig", 0x152)
    put("oelig", 0x153); put("Scaron", 0x160); put("scaron", 0x161); put("Yuml", 0x178); put("fnof", 0x192)
    put("minus", 0x2212); put("infin", 0x221E); put("ne", 0x2260); put("le", 0x2264); put("ge", 0x2265)
  }
}

/**
 * Rich Text Format read for its words: control words dropped (paragraphs and lines kept as line breaks), escapes
 * decoded, and destination groups (fonts, colours, styles, pictures) skipped. Enough for search. Ported from
 * DigUp's idea (MIT), not its code.
 */
object RtfText {
  private val skippedGroups = setOf(
    "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "footerl",
    "footerr", "listtable", "listoverridetable", "rsidtbl", "generator", "themedata", "colorschememapping",
    "datastore", "xmlnstbl", "latentstyles", "pgdsctbl", "filetbl", "revtbl", "mmathPr", "fldinst", "object",
  )

  /** Windows-1252's upper half (0x80-0x9F); the rest of the range is Latin-1. */
  private val cp1252High = intArrayOf(
    0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x8D,
    0x017D, 0x8F, 0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A,
    0x0153, 0x9D, 0x017E, 0x0178,
  )

  private fun cp1252(byte: Int): Int = if (byte in 0x80..0x9F) cp1252High[byte - 0x80] else byte

  fun toText(rtf: String): String {
    val out = StringBuilder()
    val n = rtf.length
    var i = 0
    var skipDepth = 0     // inside a skipped group: its nesting depth
    var unicodeSkip = 1   // \ucN: characters after \uN to skip
    var pendingSkip = 0

    fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    fun isDigit(c: Char) = c in '0'..'9'

    while (i < n) {
      val c = rtf[i]
      if (skipDepth > 0) {
        when (c) {
          '{' -> skipDepth++
          '}' -> skipDepth--
          '\\' -> i++
        }
        i++
        continue
      }
      when (c) {
        '{' -> {
          var j = i + 1
          if (j < n && rtf[j] == '\\') {
            j++
            if (j < n && rtf[j] == '*') {   // {\* ...}: a destination readers may ignore
              skipDepth = 1
              i = j + 1
              continue
            }
            val word = StringBuilder()
            while (j < n && isAsciiLetter(rtf[j]) && word.length < 32) word.append(rtf[j++])
            if (word.toString() in skippedGroups) {
              skipDepth = 1
              i = j
              continue
            }
          }
          i++
        }
        '}' -> i++
        '\\' -> {
          i++
          if (i >= n) break
          val next = rtf[i]
          if (next == '\'') {   // \'hh: a byte in the document's code page (Windows-1252 here)
            val hex = rtf.substring(minOf(i + 1, n), minOf(i + 3, n))
            val value = hex.toIntOrNull(16)
            if (hex.length == 2 && value != null) {
              if (pendingSkip > 0) pendingSkip-- else out.appendCodePoint(cp1252(value))
              i += 3
            } else {
              i++
            }
          } else if (isAsciiLetter(next)) {
            val word = StringBuilder()
            var j = i
            while (j < n && isAsciiLetter(rtf[j])) word.append(rtf[j++])
            val number = StringBuilder()
            if (j < n && rtf[j] == '-') number.append(rtf[j++])
            while (j < n && isDigit(rtf[j])) number.append(rtf[j++])
            if (j < n && rtf[j] == ' ') j++   // the space ending a control word belongs to it
            when (word.toString()) {
              "par", "line", "sect", "page", "row" -> out.append('\n')
              "tab", "cell" -> out.append('\t')
              "emdash" -> out.append('—')
              "endash" -> out.append('–')
              "lquote" -> out.append('‘')
              "rquote" -> out.append('’')
              "ldblquote" -> out.append('“')
              "rdblquote" -> out.append('”')
              "bullet" -> out.append('•')
              "uc" -> unicodeSkip = number.toString().toIntOrNull() ?: 1
              "u" -> {
                val value = number.toString().toIntOrNull()
                if (value != null) out.append((if (value < 0) value + 65536 else value).toChar())
                pendingSkip = unicodeSkip
              }
            }
            i = j
          } else {
            when (next) {
              '\\', '{', '}' -> out.append(next)
              '~' -> out.append(' ')
              '\n', '\r' -> out.append('\n')
            }
            i++
          }
        }
        '\r', '\n' -> i++
        else -> {
          if (pendingSkip > 0) pendingSkip-- else out.append(c)
          i++
        }
      }
    }
    return out.toString().lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
  }
}
