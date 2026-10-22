package com.craneai.findr.extract

import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.core.ExtractedText
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import org.xmlpull.v1.XmlPullParser.TEXT
import org.xmlpull.v1.XmlPullParserException

private const val TAG = "FindrExtract"

/** How many characters may still be produced for one file. Shared by every page and part of that file. */
class Budget(val max: Int) {
  var used = 0
  var truncated = false
  val full get() = used >= max
}

/** Builds one page of text. Newlines never stack up beyond one blank line, and the shared [Budget] is respected. */
class TextSink(private val budget: Budget) {
  private val sb = StringBuilder()

  fun append(s: CharSequence) {
    if (s.isEmpty()) return
    if (budget.full) {
      budget.truncated = true
      return
    }
    val room = budget.max - budget.used
    if (s.length > room) {
      sb.append(s, 0, room)
      budget.used += room
      budget.truncated = true
    } else {
      sb.append(s)
      budget.used += s.length
    }
  }

  fun newline() {
    if (budget.full) return
    while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
    val l = sb.length
    if (l == 0 || (l >= 2 && sb[l - 1] == '\n' && sb[l - 2] == '\n')) return
    append("\n")
  }

  /** A tab or space between cells/paragraphs, unless one is already there. */
  fun separator(c: Char) {
    if (sb.isNotEmpty() && !sb.last().isWhitespace()) append(c.toString())
  }

  /** A cell boundary: a tab, taking the place of the space a paragraph end just left. */
  fun cellBreak() {
    if (sb.isNotEmpty() && sb.last() == ' ') sb.setCharAt(sb.length - 1, '\t') else separator('\t')
  }

  fun take(): String {
    val s = sb.toString().trim()
    sb.setLength(0)
    return s
  }
}

/**
 * Zip archive with the zip-bomb guard: no entry inflates past [ExtractLimits.MAX_ENTRY_BYTES] and the whole archive
 * stops at [ExtractLimits.MAX_ARCHIVE_BYTES]. Hitting a limit reads as end-of-file and sets [hitLimit], so the text
 * found so far is kept.
 */
class Archive(private val zip: ZipFile) {
  var hitLimit = false
    private set
  private var remaining = ExtractLimits.MAX_ARCHIVE_BYTES

  fun names(): List<String> = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()

  fun has(name: String) = zip.getEntry(name) != null

  fun open(name: String): InputStream? {
    val entry = zip.getEntry(name) ?: return null
    if (entry.isDirectory) return null
    return Bounded(zip.getInputStream(entry))
  }

  fun readBytes(name: String, max: Int): ByteArray? {
    val stream = open(name) ?: return null
    return stream.use { s ->
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(16 * 1024)
      while (out.size() < max) {
        val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
      }
      out.toByteArray()
    }
  }

  private inner class Bounded(input: InputStream) : FilterInputStream(input) {
    private var left = ExtractLimits.MAX_ENTRY_BYTES

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val allowed = minOf(left, remaining)
      if (allowed <= 0) {
        hitLimit = true
        return -1
      }
      val n = super.read(b, off, minOf(len.toLong(), allowed).toInt())
      if (n > 0) {
        left -= n
        remaining -= n
      }
      return n
    }
  }
}

/** What to read out of a stream of XML: which elements hold text, and which ends or starts mean a break. */
class XmlSpec(
  /** Local names whose character data is text. Null means any element. */
  val textElements: Set<String>?,
  /** End of these ends a line. */
  val paragraphs: Set<String> = emptySet(),
  /** End of these puts a space between words, unless there is one. */
  val wordBreaks: Set<String> = emptySet(),
  /** End of these puts a tab between cells, unless there is one. */
  val cellEnds: Set<String> = emptySet(),
  /** Start of these is a line break. */
  val lineBreaks: Set<String> = emptySet(),
  val tabs: Set<String> = emptySet(),
  /** ODF `text:s`: one or more spaces. */
  val spaces: Set<String> = emptySet(),
  /** Whole subtrees to ignore (styles, field codes, duplicated fallbacks). */
  val skip: Set<String> = emptySet(),
  /** End of this element closes a page (an ODP slide). */
  val pageElement: String? = null,
  /** ODF text collapses runs of whitespace; OOXML text is literal. */
  val collapseWhitespace: Boolean = false,
)

fun localName(qualified: String): String = qualified.substringAfter(':')

/** The value of the attribute whose name, minus any prefix, is `name`. */
fun XmlPullParser.attr(name: String): String? {
  for (i in 0 until attributeCount) if (localName(getAttributeName(i)) == name) return getAttributeValue(i)
  return null
}

/** The `r:id` of OOXML (prefixed, to tell it from the plain numeric `id`). */
fun XmlPullParser.relId(): String? {
  for (i in 0 until attributeCount) {
    val n = getAttributeName(i)
    if (n.contains(':') && localName(n) == "id") return getAttributeValue(i)
  }
  return null
}

private val whitespaceRun = Regex("\\s+")

/**
 * Streams one XML part into pages of text. A malformed part ends the walk quietly and keeps what was read (partial
 * text beats none for search). `tick` runs now and then so a cancelled coroutine stops the walk.
 */
fun flattenXml(parser: XmlPullParser, spec: XmlSpec, budget: Budget, tick: () -> Unit): List<String> {
  val pages = ArrayList<String>()
  var sink = TextSink(budget)
  var textDepth = 0
  var skipDepth = 0
  var events = 0
  try {
    var event = parser.eventType
    while (event != END_DOCUMENT && !budget.full) {
      if ((++events and 0x3FF) == 0) tick()
      when (event) {
        START_TAG -> {
          val name = localName(parser.name)
          if (skipDepth > 0) {
            skipDepth++
          } else if (name in spec.skip) {
            skipDepth = 1
          } else {
            if (spec.textElements != null && name in spec.textElements) textDepth++
            if (name in spec.lineBreaks) sink.newline()
            if (name in spec.tabs) sink.append("\t")
            if (name in spec.spaces) sink.append(" ".repeat((parser.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 32)))
          }
        }
        END_TAG -> {
          val name = localName(parser.name)
          if (skipDepth > 0) {
            skipDepth--
          } else {
            if (spec.textElements != null && name in spec.textElements) textDepth--
            if (name in spec.paragraphs) sink.newline()
            if (name in spec.wordBreaks) sink.separator(' ')
            if (name in spec.cellEnds) sink.cellBreak()
            if (name == spec.pageElement) {
              pages.add(sink.take())
              sink = TextSink(budget)
            }
          }
        }
        TEXT -> if (skipDepth == 0 && (spec.textElements == null || textDepth > 0)) {
          val text = parser.text ?: ""
          sink.append(if (spec.collapseWhitespace) text.replace(whitespaceRun, " ") else text)
        }
      }
      event = parser.next()
    }
  } catch (e: XmlPullParserException) {
    Log.w(TAG, "XML part ended early: ${Log.brief(e)}")
  } catch (e: IOException) {
    Log.w(TAG, "XML part unreadable: ${Log.brief(e)}")
  }
  val last = sink.take()
  if (last.isNotEmpty() || spec.pageElement == null) pages.add(last)
  return pages
}

/**
 * Text from the zip-and-XML formats: docx, xlsx, pptx, odt/ods/odp and epub. Pure JVM apart from the parser, which is
 * injected (`android.util.Xml` on the phone, kxml in the unit tests), so the order and break rules are tested off-device.
 */
object OfficeText {
  private val ooxml = XmlSpec(
    textElements = setOf("t"), paragraphs = setOf("p"), lineBreaks = setOf("br", "cr"), tabs = setOf("tab"),
    skip = setOf("pPr", "rPr", "endParaRPr", "Fallback"),
  )
  private val odfSkip = setOf("automatic-styles", "font-face-decls", "scripts")
  /** A spreadsheet's cell comments are not its data. */
  private val odsSkip = odfSkip + "annotation"
  private val odtSpec = XmlSpec(
    textElements = setOf("p", "h"), paragraphs = setOf("p", "h"), lineBreaks = setOf("line-break"),
    tabs = setOf("tab"), spaces = setOf("s"), skip = odfSkip, collapseWhitespace = true,
  )
  /** Slide notes: the same, minus fields (the slide number placeholder would add a stray "‹#›" to every note). */
  private val ooxmlNotes = XmlSpec(
    textElements = setOf("t"), paragraphs = setOf("p"), lineBreaks = setOf("br", "cr"), tabs = setOf("tab"),
    skip = setOf("pPr", "rPr", "endParaRPr", "Fallback", "fld"),
  )
  private val odpSpec = XmlSpec(
    textElements = setOf("p", "h"), paragraphs = setOf("p", "h"), lineBreaks = setOf("line-break"),
    tabs = setOf("tab"), spaces = setOf("s"), skip = odfSkip, pageElement = "page", collapseWhitespace = true,
  )

  /** Reads a zip-based document. Returns empty pages when `format` isn't one. */
  fun extract(
    format: DocFormat, file: File, maxChars: Int, newParser: () -> XmlPullParser, tick: () -> Unit = {},
  ): ExtractedText {
    ZipFile(file).use { zip ->
      val archive = Archive(zip)
      // A file whose name lies about what it is ("budget.docx" that is really a spreadsheet) is read as what its parts say.
      val actual = sniff(archive, format) ?: format
      val budget = Budget(if (actual == DocFormat.XLSX || actual == DocFormat.ODS) minOf(maxChars, ExtractLimits.TABULAR_MAX_CHARS) else maxChars)
      val reader = Reader(archive, newParser, tick)
      val pages = when (actual) {
        DocFormat.DOCX -> listOf(reader.flatten("word/document.xml", ooxml, budget).joinToString("\n\n"))
        DocFormat.PPTX -> reader.pptx(budget)
        DocFormat.XLSX -> listOf(reader.xlsx(budget))
        DocFormat.ODT -> listOf(reader.flatten("content.xml", odtSpec, budget).joinToString("\n\n"))
        DocFormat.ODS -> listOf(reader.ods(budget))
        DocFormat.ODP -> reader.odp(budget)
        DocFormat.EPUB -> listOf(reader.epub(budget))
        else -> emptyList()
      }
      return ExtractedText(pages, budget.truncated || archive.hitLimit)
    }
  }

  private val ZIP_FORMATS = setOf(DocFormat.DOCX, DocFormat.XLSX, DocFormat.PPTX, DocFormat.ODT, DocFormat.ODS, DocFormat.ODP, DocFormat.EPUB)

  /**
   * What the zip really is, when that differs from what [declared] says; null to trust the name. A part named by the
   * declared format wins, so a healthy file is never re-judged. OpenDocument and EPUB say what they are in `mimetype`.
   */
  fun sniff(archive: Archive, declared: DocFormat): DocFormat? {
    if (declared !in ZIP_FORMATS) return null
    val byMimetype = {
      archive.readBytes("mimetype", 128)?.let { String(it, Charsets.US_ASCII).trim() }?.let {
        when (it) {
          "application/vnd.oasis.opendocument.text" -> DocFormat.ODT
          "application/vnd.oasis.opendocument.spreadsheet" -> DocFormat.ODS
          "application/vnd.oasis.opendocument.presentation" -> DocFormat.ODP
          "application/epub+zip" -> DocFormat.EPUB
          else -> null
        }
      }
    }
    // The ODF family share content.xml, so only `mimetype` tells a sheet from a text.
    if (declared == DocFormat.ODT || declared == DocFormat.ODS || declared == DocFormat.ODP) {
      return byMimetype() ?: when {
        archive.has("content.xml") -> null
        archive.has("word/document.xml") -> DocFormat.DOCX
        archive.has("xl/workbook.xml") -> DocFormat.XLSX
        archive.has("ppt/presentation.xml") -> DocFormat.PPTX
        else -> null
      }
    }
    val own = when (declared) {
      DocFormat.DOCX -> "word/document.xml"
      DocFormat.XLSX -> "xl/workbook.xml"
      DocFormat.PPTX -> "ppt/presentation.xml"
      else -> "META-INF/container.xml"
    }
    if (archive.has(own)) return null
    return when {
      archive.has("word/document.xml") -> DocFormat.DOCX
      archive.has("xl/workbook.xml") -> DocFormat.XLSX
      archive.has("ppt/presentation.xml") -> DocFormat.PPTX
      else -> byMimetype()
    }
  }

  private class Reader(val archive: Archive, val newParser: () -> XmlPullParser, val tick: () -> Unit) {
    /** Opens a part and hands the parser to `block`; null when the part is missing or isn't well-formed XML. */
    fun <T> parse(name: String, block: (XmlPullParser) -> T): T? {
      val stream = archive.open(name) ?: return null
      return try {
        stream.use {
          val parser = newParser()
          parser.setInput(it, null)
          block(parser)
        }
      } catch (e: XmlPullParserException) {
        Log.w(TAG, "${redact(name)}: ${Log.brief(e)}")
        null
      } catch (e: IOException) {
        Log.w(TAG, "${redact(name)}: ${Log.brief(e)}")
        null
      }
    }

    fun flatten(name: String, spec: XmlSpec, budget: Budget): List<String> =
      parse(name) { flattenXml(it, spec, budget, tick) } ?: emptyList()

    /** Relationship id to the part it points at (paths resolved against the source part, `..` collapsed). */
    fun rels(part: String): Map<String, String> {
      val dir = part.substringBeforeLast('/', "")
      val file = part.substringAfterLast('/')
      val relsName = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$file.rels"
      val map = LinkedHashMap<String, String>()
      parse(relsName) { p ->
        var e = p.eventType
        while (e != END_DOCUMENT) {
          if (e == START_TAG && localName(p.name) == "Relationship" && p.attr("TargetMode") != "External") {
            val id = p.attr("Id")
            val target = p.attr("Target")
            if (id != null && target != null) map[id] = resolvePath(dir, target)
          }
          e = p.next()
        }
      }
      return map
    }

    /** `r:id`s of the elements named `element` in a part, in document order. */
    fun relIds(part: String, element: String): List<String> =
      parse(part) { p ->
        val ids = ArrayList<String>()
        var e = p.eventType
        while (e != END_DOCUMENT) {
          if (e == START_TAG && localName(p.name) == element) p.relId()?.let { ids.add(it) }
          e = p.next()
        }
        ids
      } ?: emptyList()

    /** Parts listed in a workbook/presentation order, or `fallback` when that index can't be read. */
    fun ordered(part: String, element: String, fallback: Regex): List<String> {
      val rels = rels(part)
      val listed = relIds(part, element).mapNotNull { rels[it] }.filter { archive.has(it) }
      if (listed.isNotEmpty()) return listed
      return archive.names().filter { fallback.matches(it) }.sortedWith(compareBy({ numberIn(it) }, { it }))
    }

    fun pptx(budget: Budget): List<String> {
      val slides = ordered("ppt/presentation.xml", "sldId", Regex("ppt/slides/slide\\d+\\.xml"))
      val pages = ArrayList<String>()
      for ((i, slide) in slides.withIndex()) {
        tick()
        if (budget.full) {
          budget.truncated = true
          break
        }
        val body = flatten(slide, ooxml, budget).joinToString("\n")
        // The speaker notes are the slide's own words too, and cheap: the slide's relationships name them.
        val notes = if (budget.full) "" else rels(slide).values.firstOrNull { it.startsWith("ppt/notesSlides/") && archive.has(it) }
          ?.let { flatten(it, ooxmlNotes, budget).joinToString("\n") }.orEmpty()
        pages.add(slidePage(i + 1, body, notes))
      }
      return pages
    }

    fun odp(budget: Budget): List<String> =
      flatten("content.xml", odpSpec, budget).mapIndexed { i, body -> slidePage(i + 1, body, "") }

    /** "Slide 3: …" for a slide with text; a slide without any stays an empty page, so page numbers keep matching slide numbers. */
    private fun slidePage(number: Int, body: String, notes: String): String {
      val text = listOf(body.trim(), notes.trim().takeIf { it.isNotEmpty() }?.let { "Notes: $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString("\n")
      return if (text.isEmpty()) "" else "Slide $number: $text"
    }

    /** Sheets in workbook order as (name, part). A workbook whose index can't be read gives its sheet parts, unnamed. */
    private fun sheets(): List<Pair<String, String>> {
      val rels = rels("xl/workbook.xml")
      val listed = parse("xl/workbook.xml") { p ->
        val out = ArrayList<Pair<String, String>>()
        var e = p.eventType
        while (e != END_DOCUMENT) {
          if (e == START_TAG && localName(p.name) == "sheet") {
            val part = p.relId()?.let { rels[it] }
            if (part != null && archive.has(part)) out.add((p.attr("name") ?: "") to part)
          }
          e = p.next()
        }
        out
      }.orEmpty()
      if (listed.isNotEmpty()) return listed
      return archive.names().filter { Regex("xl/worksheets/sheet\\d+\\.xml").matches(it) }
        .sortedWith(compareBy({ numberIn(it) }, { it })).map { "" to it }
    }

    private var cellsLeft = ExtractLimits.MAX_CELLS

    /** Each sheet as its name, then one line per row: "cell | cell | cell", empty cells left out. */
    fun xlsx(budget: Budget): String {
      val sheets = sheets()
      val shared = sharedStrings()
      val parts = ArrayList<String>()
      for ((name, part) in sheets) {
        tick()
        if (budget.full || cellsLeft <= 0) {
          budget.truncated = true
          break
        }
        val text = parse(part) { readSheet(it, shared, budget) } ?: continue
        if (text.isNotEmpty()) parts.add(if (name.isBlank()) text else "$name\n$text")
      }
      return parts.joinToString("\n\n")
    }

    /** A content.xml spreadsheet the same way: table name, then "cell | cell" rows. */
    fun ods(budget: Budget): String =
      parse("content.xml") { readOds(it, budget) }.orEmpty()

    private fun readOds(p: XmlPullParser, budget: Budget): String {
      val sink = TextSink(budget)
      val cell = StringBuilder()
      var tables = 0           // nesting: only the outermost table gets a title
      var skipDepth = 0
      var inCell = false
      var inText = false
      var rowHasCell = false
      var events = 0
      try {
        var e = p.eventType
        while (e != END_DOCUMENT && !budget.full) {
          if ((++events and 0x3FF) == 0) tick()
          when (e) {
            START_TAG -> {
              val name = localName(p.name)
              if (skipDepth > 0) skipDepth++
              else if (name in odsSkip) skipDepth = 1
              else when (name) {
                "table" -> {
                  if (tables++ == 0) {
                    sink.newline()
                    p.attr("name")?.takeIf { it.isNotBlank() }?.let { sink.append(it); sink.newline() }
                  }
                }
                "table-cell", "covered-table-cell" -> { inCell = true; cell.setLength(0) }
                "p", "h" -> if (inCell) { inText = true; if (cell.isNotEmpty()) cell.append(' ') }
                "s" -> if (inCell) cell.append(" ".repeat((p.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 32)))
                "tab", "line-break" -> if (inCell) cell.append(' ')
              }
            }
            END_TAG -> {
              val name = localName(p.name)
              if (skipDepth > 0) skipDepth--
              else when (name) {
                "p", "h" -> inText = false
                "table-cell", "covered-table-cell" -> {
                  if (inCell) {
                    inCell = false
                    val text = cell.toString().replace(whitespaceRun, " ").trim()
                    if (text.isNotEmpty()) {
                      if (cellsLeft <= 0) {
                        budget.truncated = true
                        return sink.take()
                      }
                      cellsLeft--
                      if (rowHasCell) sink.append(" | ")
                      sink.append(text)
                      rowHasCell = true
                    }
                  }
                }
                "table-row" -> { if (rowHasCell) sink.newline(); rowHasCell = false }
                "table" -> if (tables > 0) tables--
              }
            }
            TEXT -> if (inCell && inText && skipDepth == 0) cell.append(p.text)
          }
          e = p.next()
        }
      } catch (ex: XmlPullParserException) {
        Log.w(TAG, "spreadsheet ended early: ${Log.brief(ex)}")
      }
      return sink.take()
    }

    private fun sharedStrings(): List<String> {
      val list = ArrayList<String>()
      parse("xl/sharedStrings.xml") { p ->
        val cur = StringBuilder()
        var inSi = false
        var inT = false
        var phDepth = 0
        var total = 0
        var events = 0
        try {
          var e = p.eventType
          while (e != END_DOCUMENT) {
            if ((++events and 0x3FF) == 0) tick()
            when (e) {
              START_TAG -> when (localName(p.name)) {
                "si" -> { inSi = true; cur.setLength(0) }
                "t" -> if (inSi && phDepth == 0) inT = true
                "rPh" -> phDepth++
              }
              END_TAG -> when (localName(p.name)) {
                "t" -> inT = false
                "rPh" -> phDepth--
                "si" -> {
                  inSi = false
                  total += cur.length
                  list.add(if (total > ExtractLimits.MAX_SHARED_STRING_CHARS) "" else cur.toString())
                }
              }
              TEXT -> if (inT && total <= ExtractLimits.MAX_SHARED_STRING_CHARS) cur.append(p.text)
            }
            e = p.next()
          }
        } catch (ex: XmlPullParserException) {
          Log.w(TAG, "sharedStrings ended early: ${Log.brief(ex)}")
        }
      }
      return list
    }

    /**
     * Cells of one worksheet as "cell | cell" rows. Numbers are kept as written; booleans and errors are dropped. Stops
     * at the cell cap, and says so.
     */
    private fun readSheet(p: XmlPullParser, shared: List<String>, budget: Budget): String {
      val sink = TextSink(budget)
      val v = StringBuilder()
      var cellType: String? = null
      var inV = false
      var inIs = false
      var inT = false
      var rowHasCell = false
      var events = 0
      try {
        var e = p.eventType
        while (e != END_DOCUMENT && !budget.full) {
          if ((++events and 0x3FF) == 0) tick()
          when (e) {
            START_TAG -> when (localName(p.name)) {
              "c" -> { cellType = p.attr("t"); v.setLength(0) }
              "v" -> inV = true
              "is" -> inIs = true
              "t" -> if (inIs) inT = true
            }
            END_TAG -> when (localName(p.name)) {
              "v" -> inV = false
              "t" -> inT = false
              "is" -> inIs = false
              "c" -> {
                val raw = v.toString()
                val text = when (cellType) {
                  "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1) ?: ""
                  "b", "e" -> ""
                  else -> raw
                }.replace(whitespaceRun, " ").trim()
                if (text.isNotEmpty()) {
                  if (cellsLeft <= 0) {
                    budget.truncated = true
                    return sink.take()
                  }
                  cellsLeft--
                  if (rowHasCell) sink.append(" | ")
                  sink.append(text)
                  rowHasCell = true
                }
              }
              "row" -> { if (rowHasCell) sink.newline(); rowHasCell = false }
            }
            TEXT -> if (inV || (inIs && inT)) v.append(p.text)
          }
          e = p.next()
        }
      } catch (ex: XmlPullParserException) {
        Log.w(TAG, "worksheet ended early: ${Log.brief(ex)}")
      }
      return sink.take()
    }

    /**
     * The book's title and author, then its chapters in spine order, each passed through the HTML reader (which keeps
     * the chapter's own title and headings). A chapter that can't be read is left out, not the whole book; a book whose
     * chapters are DRM-encrypted gives nothing, rather than the noise inside them.
     */
    fun epub(budget: Budget): String {
      val opf = parse("META-INF/container.xml") { p ->
        var path: String? = null
        var e = p.eventType
        while (e != END_DOCUMENT && path == null) {
          if (e == START_TAG && localName(p.name) == "rootfile") path = p.attr("full-path")
          e = p.next()
        }
        path
      }
      val book = opf?.let { spine(it) }
      val chapters = (book?.chapters ?: emptyList()).ifEmpty {
        archive.names().filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }.sorted()
      }
      val locked = encryptedParts()
      if (chapters.isNotEmpty() && chapters.all { it in locked }) {
        Log.w(TAG, "epub: chapters are encrypted (DRM)")
        return ""
      }
      val sink = TextSink(budget)
      val head = listOfNotNull(book?.title, book?.author?.let { "by $it" }).joinToString(" ")
      if (head.isNotBlank()) { sink.append(head); sink.newline() }
      for (chapter in chapters) {
        tick()
        if (budget.full) {
          budget.truncated = true
          break
        }
        if (chapter in locked) continue
        val bytes = try {
          archive.readBytes(chapter, ExtractLimits.MAX_CHAPTER_BYTES)
        } catch (e: IOException) {
          Log.w(TAG, "epub chapter unreadable: ${Log.brief(e)}")
          null
        } ?: continue
        if (bytes.size >= ExtractLimits.MAX_CHAPTER_BYTES) budget.truncated = true
        val text = HtmlText.toText(TextDecoding.decode(bytes, cut = bytes.size >= ExtractLimits.MAX_CHAPTER_BYTES))
        if (text.isEmpty()) continue
        sink.newline()
        sink.append(text)
        sink.newline()
      }
      return sink.take()
    }

    /** Parts the book encrypts for real. Font obfuscation (the IDPF and Adobe algorithms) hides nothing a reader needs. */
    private fun encryptedParts(): Set<String> =
      parse("META-INF/encryption.xml") { p ->
        val out = HashSet<String>()
        var algorithm = ""
        var e = p.eventType
        while (e != END_DOCUMENT) {
          if (e == START_TAG) {
            when (localName(p.name)) {
              "EncryptionMethod" -> algorithm = p.attr("Algorithm").orEmpty()
              "CipherReference" -> if (!algorithm.startsWith("http://www.idpf.org/2008/embedding") && !algorithm.startsWith("http://ns.adobe.com/pdf/enc")) {
                p.attr("URI")?.let { out.add(resolvePath("", urlDecode(it))) }
              }
            }
          }
          e = p.next()
        }
        out
      }.orEmpty()

    private class Book(val chapters: List<String>, val title: String?, val author: String?)

    private fun spine(opfPath: String): Book? =
      parse(opfPath) { p ->
        val dir = opfPath.substringBeforeLast('/', "")
        val manifest = HashMap<String, String>()
        val order = ArrayList<String>()
        var title: String? = null
        var author: String? = null
        var reading = ""
        var e = p.eventType
        while (e != END_DOCUMENT) {
          when (e) {
            START_TAG -> {
              val tag = localName(p.name)
              reading = ""
              when (tag) {
                "item" -> {
                  val id = p.attr("id")
                  val href = p.attr("href")
                  if (id != null && href != null) manifest[id] = resolvePath(dir, urlDecode(href))
                }
                "itemref" -> p.attr("idref")?.let { order.add(it) }
                "title", "creator" -> reading = tag
              }
            }
            TEXT -> {
              val t = p.text?.trim().orEmpty()
              if (t.isNotEmpty()) {
                if (reading == "title" && title == null) title = t.take(200)
                if (reading == "creator" && author == null) author = t.take(200)
              }
            }
            END_TAG -> reading = ""
          }
          e = p.next()
        }
        Book(order.mapNotNull { manifest[it] }.filter { archive.has(it) }, title, author)
      }
  }

  /** `target` resolved against the directory `dir` of the part that names it; `.` and `..` are folded away. */
  fun resolvePath(dir: String, target: String): String {
    val clean = target.substringBefore('#')
    val joined = if (clean.startsWith("/")) clean.substring(1) else if (dir.isEmpty()) clean else "$dir/$clean"
    val out = ArrayList<String>()
    for (part in joined.split('/')) {
      when (part) {
        "", "." -> Unit
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
        else -> out.add(part)
      }
    }
    return out.joinToString("/")
  }

  private fun urlDecode(s: String) = try {
    URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
  } catch (e: IllegalArgumentException) {
    s
  }

  private fun numberIn(name: String): Int = Regex("\\d+").find(name.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0
}
