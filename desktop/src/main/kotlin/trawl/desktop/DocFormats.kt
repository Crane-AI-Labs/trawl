package trawl.desktop

import com.craneai.findr.core.ExtractedText
import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Rules
import com.craneai.findr.engine.Chunker
import com.craneai.findr.extract.DelimitedText
import com.craneai.findr.extract.DocFormat
import com.craneai.findr.extract.ExtractLimits
import com.craneai.findr.extract.HtmlText
import com.craneai.findr.extract.OfficeText
import com.craneai.findr.extract.RtfText
import com.craneai.findr.extract.SvgText
import com.craneai.findr.extract.TextDecoding
import java.io.File
import java.io.IOException
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * Every document format the phone reads, read the same way: plain text, markdown, json and log files, CSV and TSV, HTML, RTF, SVG,
 * Word (docx), Excel (xlsx), PowerPoint (pptx), the OpenDocument family (odt, ods, odp) and ePub. The work is done by the core's own
 * extractors ([OfficeText], [HtmlText], [RtfText], [DelimitedText], [SvgText], [TextDecoding]); this file is the desktop's twin of the
 * phone's `DocumentTextExtractor`, with the same order of judgement and the same limits:
 *
 *  1. the name picks the format, and a file the name could not place (no extension, an unknown one) is judged by its first bytes;
 *  2. a file over [ExtractLimits.MAX_FILE_BYTES] is not read;
 *  3. a text name on a PDF's bytes ("speech.pdf.txt") is a PDF: [read] says so with [Read.pdf] and the caller uses PDFBox;
 *  4. binary data under a text name gives no text.
 *
 * It never throws: a corrupt, encrypted or oversized file gives an empty [Read] with a plain [Read.why] ("The file is damaged or
 * password-protected", "Over 200 MB") the detail panel can show, and the file stays findable by name.
 */
object DocFormats {
  /** The phone's budget for one document (`TextExtractor.extract` default). */
  const val MAX_CHARS = 2_000_000

  /**
   * Why a file gave no words. [detail] is what is stored on the file (`files.detail`, English and stable, like the phone's
   * `IndexStore.CANT_OPEN`); the detail panel maps it to a plain sentence in the person's language ([IndexAdmin.failReason]).
   */
  enum class Why(val detail: String) {
    TOO_BIG("too big to read"), BINARY("not text"), DAMAGED("damaged or protected"), UNREADABLE("could not be read")
  }

  /**
   * What [read] found. [pages] holds one entry per page or slide (pptx and odp), one for the rest, the way the phone's extractor
   * returns it. [pdf] is true when the bytes are a PDF whatever the name says: read it with [Extract.pdfPages].
   */
  data class Read(val pages: List<String>, val truncated: Boolean = false, val pdf: Boolean = false, val why: Why? = null) {
    val empty get() = pages.none { it.isNotBlank() }
  }

  private val NOTHING = Read(emptyList())

  /** The format a file would be read as, by name then by its first bytes (null: nobody reads it). */
  fun formatOf(file: File): DocFormat? = DocFormat.detect(null, file.name) ?: headerFormat(file)

  /** Whether a document with this name has a reader. Name only: the cheap test the crawler-side code uses. */
  fun canReadName(name: String): Boolean = DocFormat.detect(null, name) != null

  /** The extensions a person can expect to be read, for the detail panel's "Formats" line and the tests. */
  val EXTENSIONS: List<String> = listOf(
    "txt", "md", "json", "log", "csv", "tsv", "html", "htm", "rtf", "svg", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "pdf",
  )

  private fun headerFormat(file: File): DocFormat? = try {
    file.inputStream().use { readHead(it, 512) }.first.let { DocFormat.fromHeader(it) }
  } catch (e: IOException) {
    null
  }

  /** Reads [file] as its format. A PDF is not read here (the caller owns PDFBox): the result says [Read.pdf]. */
  fun read(file: File, maxChars: Int = MAX_CHARS): Read {
    val format = formatOf(file) ?: return NOTHING
    return try {
      if (file.length() > ExtractLimits.MAX_FILE_BYTES) return Read(emptyList(), why = Why.TOO_BIG)
      if (format == DocFormat.PDF) return Read(emptyList(), pdf = true)
      val textual = format in TEXT_FORMATS
      // A text name on a PDF's bytes: read it as the PDF it is.
      if (textual && file.inputStream().use { TextDecoding.isPdf(readHead(it, 5).first) }) return Read(emptyList(), pdf = true)
      if (textual) text(file, format, maxChars)
      else OfficeText.extract(format, file, maxChars, newParser).toRead()
    } catch (e: InterruptedException) {
      throw e
    } catch (t: Throwable) {
      // A zip that does not open, a password, a broken part: no words, and the file stays findable by name.
      Read(emptyList(), why = Why.DAMAGED)
    }
  }

  /** What the indexer takes from a document: [chunks] per page (page = index + 1), [pdf] when PDFBox read it, [failure] a [Why.detail] when it could not be read. */
  class Pages(val chunks: List<List<String>>, val pdf: Boolean, val failure: String? = null)

  /**
   * The one hook the indexers call (`Indexer.process`, `WordsReader.words`): the pieces of [file], 400-character chunks per page,
   * from PDFBox for a PDF (named or hidden under a text name) and from the core's extractors for everything else.
   */
  fun pages(file: File, namedPdf: Boolean): Pages {
    if (namedPdf) return Pages(Extract.pdfPages(file), pdf = true)
    val r = read(file)
    if (r.pdf) return Pages(Extract.pdfPages(file), pdf = true)
    return Pages(r.pages.map { Chunker.chunks(it) }, pdf = false, failure = r.why?.detail)
  }

  /**
   * What a file is, as the phone decides it ([Rules.classify], then [Rules.sniffKind] for a file whose name told nothing). The phone has a
   * MediaStore type to pass; a computer has none, so a `.log` is "text/plain" here (MediaStore calls it that), and a file with no or an
   * unknown extension is judged by its first bytes, exactly as the phone's crawler does. The crawler's one hook line.
   */
  fun classify(file: java.nio.file.Path, name: String, relativePath: String): FileKind {
    val ext = Rules.extensionOf(name)
    var kind = Rules.classify(name, if (ext == "log") "text/plain" else null, relativePath)
    // `.ts` is TypeScript far more often than video, and the shared rules (which the phone also uses) go by the extension alone. Here the
    // first bytes decide: a file that is not a transport stream is not a video, and OTHER is a kind the desktop does not keep, so it is
    // skipped quietly, before anything is queued or any decoder is asked.
    if (kind == FileKind.VIDEO && TransportStream.isAmbiguous(name) && !TransportStream.check(file)) return FileKind.OTHER
    if (Rules.shouldSniff(name, kind)) {
      val head = try {
        java.nio.file.Files.newInputStream(file).use { readHead(it, Rules.SNIFF_BYTES).first }
      } catch (e: IOException) { null }
      val sniffed = head?.let { Rules.sniffKind(it, extensionless = ext.isEmpty()) }
      if (sniffed != null && sniffed != FileKind.OTHER) kind = if (sniffed == FileKind.IMAGE && Rules.isScreenshot(relativePath, name)) FileKind.SCREENSHOT else sniffed
    }
    return kind
  }

  /** Whether a document that is not named .pdf has a reader (by name, else by its first bytes). */
  fun canRead(file: File): Boolean = formatOf(file) != null

  /** Plain, HTML, RTF, CSV and SVG: only the head of the file is read (a character is at most 4 bytes), decoded, converted. */
  private fun text(file: File, format: DocFormat, maxChars: Int): Read {
    // Markup carries bytes that don't become characters, so read more than `maxChars` of it.
    val byteLimit = if (format == DocFormat.DELIMITED) ExtractLimits.MAX_DELIMITED_BYTES
    else (maxChars.toLong() * (if (format == DocFormat.PLAIN) 4 else 8)).coerceIn(4096, 64L * 1024 * 1024).toInt()
    val (bytes, more) = file.inputStream().use { readHead(it, if (format == DocFormat.SVG) SvgText.MAX_BYTES else byteLimit) }
    if (format == DocFormat.SVG) return Read(listOf(SvgText.toText(bytes, maxChars, newParser)), more)
    if (TextDecoding.looksBinary(bytes)) return Read(emptyList(), why = Why.BINARY)
    var text = TextDecoding.decode(bytes, bytes.size, cut = more)
    if (format == DocFormat.DELIMITED) {
      val table = DelimitedText.toText(text, minOf(maxChars, ExtractLimits.TABULAR_MAX_CHARS))
      return Read(listOf(table.text), more || table.truncated)
    }
    text = when (format) {
      DocFormat.HTML -> HtmlText.toText(text)
      DocFormat.RTF -> RtfText.toText(text)
      else -> text
    }
    return Read(listOf(if (text.length > maxChars) text.substring(0, maxChars) else text), more || text.length > maxChars)
  }

  private fun ExtractedText.toRead() = Read(pages, truncated)

  private val TEXT_FORMATS = setOf(DocFormat.PLAIN, DocFormat.DELIMITED, DocFormat.HTML, DocFormat.RTF, DocFormat.SVG)

  /** The core's readers take a parser factory (Android's `Xml.newPullParser`); on a computer it is kXML, the same pull API. */
  private val newParser: () -> XmlPullParser = { KXmlParser() }

  /** Reads up to [limit] bytes; the flag says whether the stream had more. (The phone's `readHead`.) */
  internal fun readHead(input: java.io.InputStream, limit: Int): Pair<ByteArray, Boolean> {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
      val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
      if (n < 0) return out.toByteArray() to false
      out.write(buf, 0, n)
    }
    return out.toByteArray() to (input.read() >= 0)
  }
}
