package com.craneai.findr.extract

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import android.util.Xml
import com.craneai.findr.core.ExtractedText
import com.craneai.findr.core.TextExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

private const val TAG = "FindrExtract"

/**
 * Text from documents: plain text and web/RTF files, the Office and OpenDocument family, epub and PDF.
 *
 * Never throws (cancellation aside): a corrupt, encrypted, oversized or unknown file logs why and returns no pages.
 * PDF pages with no text layer come back as empty strings so the engine can render and OCR them
 * ([PdfPageRenderer]). pptx and odp give one page per slide, PDF one per page, everything else a single page.
 */
class DocumentTextExtractor(
  private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) : TextExtractor {

  override fun canRead(mimeType: String?, name: String): Boolean = DocFormat.detect(mimeType, name) != null

  override suspend fun extract(context: Context, uri: Uri, mimeType: String?, name: String, maxChars: Int): ExtractedText =
    withContext(Dispatchers.IO) {
      // Name and type first; a file they could not place (no extension, an extension nobody reads) is judged by its first bytes.
      val format = DocFormat.detect(mimeType, name) ?: headerFormat(context, uri) ?: return@withContext EMPTY
      var temp: File? = null
      try {
        val ctx = currentCoroutineContext()
        val tick = { ctx.ensureActive() }
        val known = sizeOf(context, uri)
        if (known > ExtractLimits.MAX_FILE_BYTES) {
          Log.w(TAG, "${redact(name)}: ${known / 1_048_576} MB is over the ${ExtractLimits.MAX_FILE_BYTES / 1_048_576} MB limit")
          return@withContext EMPTY
        }
        // A text name on a PDF's bytes ("speech.pdf.txt"): read it as the PDF it is.
        val pdfInside = format in TEXT_FORMATS && open(context, uri).use { TextDecoding.isPdf(readHead(it, 5).first) }
        when {
          format in TEXT_FORMATS && !pdfInside -> text(context, uri, format, maxChars, name)
          else -> {
            val file = localFile(context, uri, name)
            if (file.second) temp = file.first
            if (format == DocFormat.PDF || pdfInside) pdf(context, file.first, name, maxChars, tick)
            else OfficeText.extract(format, file.first, maxChars, newParser, tick)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        Log.w(TAG, "${redact(name)}: no text (${Log.brief(t)})")
        EMPTY
      } finally {
        temp?.delete()
      }
    }

  private fun headerFormat(context: Context, uri: Uri): DocFormat? = try {
    open(context, uri).use { readHead(it, 512) }.first.let { DocFormat.fromHeader(it) }
  } catch (e: IOException) {
    null
  }

  /** Plain, HTML and RTF: read only the head of the file (a character is at most 4 bytes), decode, convert. */
  private fun text(context: Context, uri: Uri, format: DocFormat, maxChars: Int, name: String): ExtractedText {
    // Markup carries bytes that don't become characters, so read more than `maxChars` of it.
    val byteLimit = if (format == DocFormat.DELIMITED) ExtractLimits.MAX_DELIMITED_BYTES
    else (maxChars.toLong() * (if (format == DocFormat.PLAIN) 4 else 8)).coerceIn(4096, 64L * 1024 * 1024).toInt()
    val (bytes, more) = open(context, uri).use { readHead(it, if (format == DocFormat.SVG) SvgText.MAX_BYTES else byteLimit) }
    if (format == DocFormat.SVG) return ExtractedText(listOf(SvgText.toText(bytes, maxChars, newParser)), more)
    if (TextDecoding.looksBinary(bytes)) {
      Log.w(TAG, "${redact(name)}: binary data, not text")
      return EMPTY
    }
    var text = TextDecoding.decode(bytes, bytes.size, cut = more)
    if (format == DocFormat.DELIMITED) {
      val table = DelimitedText.toText(text, minOf(maxChars, ExtractLimits.TABULAR_MAX_CHARS))
      return ExtractedText(listOf(table.text), more || table.truncated)
    }
    text = when (format) {
      DocFormat.HTML -> HtmlText.toText(text)
      DocFormat.RTF -> RtfText.toText(text)
      else -> text
    }
    val truncated = more || text.length > maxChars
    return ExtractedText(listOf(if (text.length > maxChars) text.substring(0, maxChars) else text), truncated)
  }

  private fun pdf(context: Context, file: File, name: String, maxChars: Int, tick: () -> Unit, heapHeadroom: HeapHeadroom = systemHeapHeadroom): ExtractedText {
    PdfBoxInit.ensure(context)
    val doc = try {
      LeanPdfLoader.load(file, HeapFuse.forThisProcess())
    } catch (e: HeapFuseException) {
      Log.w(TAG, "${redact(name)}: skipped, too big for the heap to open (${Log.brief(e)})")
      return EMPTY
    } catch (e: OutOfMemoryError) {
      Log.w(TAG, "${redact(name)}: out of memory opening the PDF")
      return EMPTY
    }
    doc.use { d ->
      if (d.isEncrypted && !d.currentAccessPermission.canExtractContent()) {
        Log.w(TAG, "${redact(name)}: PDF does not allow text extraction")
        return EMPTY
      }
      val stripper = PDFTextStripper()
      val trace = com.craneai.findr.BuildConfig.FINDR_HEAPLOG
      val t0 = System.nanoTime()
      val before = com.craneai.findr.diag.HeapProbe.liveMb(trace)
      return collectPdfPages(d.numberOfPages, maxChars, heapHeadroom, pdfPageHeapMargin(Runtime.getRuntime().maxMemory() - com.craneai.findr.diag.HeapProbe.ballastBytes())) { page ->
        tick()
        if (trace && page % 50 == 0) {
          Log.i(TAG, "pdf trace ${file.length() / 1_048_576} MB: page $page of ${d.numberOfPages}, ${(System.nanoTime() - t0) / 1_000_000} ms, alive ${com.craneai.findr.diag.HeapProbe.liveMb(true)} MB (before this file $before)")
        }
        try {
          stripper.startPage = page
          stripper.endPage = page
          stripper.getText(d)
        } catch (e: IOException) {
          Log.w(TAG, "${redact(name)}: page $page unreadable (${Log.brief(e)})")
          ""
        } catch (e: RuntimeException) {
          Log.w(TAG, "${redact(name)}: page $page unreadable (${e.javaClass.simpleName})")
          ""
        } catch (e: OutOfMemoryError) {
          Log.w(TAG, "${redact(name)}: page $page ran out of memory; keeping the pages read before it")
          null
        }
      }
    }
  }

  /** Best-effort size without reading: -1 when the provider doesn't say. */
  private fun sizeOf(context: Context, uri: Uri): Long {
    if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1
      } ?: -1
    } catch (e: Exception) {
      -1
    }
  }

  private fun open(context: Context, uri: Uri): InputStream =
    context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")

  /** A real file for formats that need random access: the file itself for `file:` URIs, else a cache copy. */
  private fun localFile(context: Context, uri: Uri, name: String): Pair<File, Boolean> {
    if (uri.scheme == "file") uri.path?.let { return File(it) to false }
    val ext = name.substringAfterLast('.', "tmp")
    // App-private (never shared storage), deleted when this file is done; LocalData.sweepScratch catches a killed process.
    val temp = File.createTempFile("extract", ".$ext", com.craneai.findr.core.LocalData.scratchDir(context.cacheDir).apply { mkdirs() })
    try {
      open(context, uri).use { input ->
        temp.outputStream().use { out ->
          val buf = ByteArray(64 * 1024)
          var copied = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            copied += n
            if (copied > ExtractLimits.MAX_FILE_BYTES) throw IOException("over the size limit")
            out.write(buf, 0, n)
          }
        }
      }
    } catch (t: Throwable) {
      temp.delete()
      throw t
    }
    return temp to true
  }

  companion object {
    /**
     * Bump when what this reads out of a file changes: documents already in the index are read again (IndexWorker),
     * only those the change can affect ([VERSION_AFFECTS]: name endings, plus text/ mime types), so an update doesn't
     * re-read every PDF on the phone (on the Infinix a blanket re-read was 108 documents and ~6 min of words alone).
     */
    const val VERSION = 2   // 2: a PDF named .txt is read as a PDF; binary data with a text name gives no text
    val VERSION_AFFECTS = listOf("txt", "md", "markdown", "csv", "tsv", "json", "log", "html", "htm", "xhtml", "rtf")

    private val EMPTY = ExtractedText(emptyList())
    private val TEXT_FORMATS = setOf(DocFormat.PLAIN, DocFormat.DELIMITED, DocFormat.HTML, DocFormat.RTF, DocFormat.SVG)
  }
}

/** Reads up to `limit` bytes; the flag says whether the stream had more. */
internal fun readHead(input: InputStream, limit: Int): Pair<ByteArray, Boolean> {
  val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
  val buf = ByteArray(64 * 1024)
  while (out.size() < limit) {
    val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
    if (n < 0) return out.toByteArray() to false
    out.write(buf, 0, n)
  }
  return out.toByteArray() to (input.read() >= 0)
}

/**
 * Collects page texts under one character budget. Page boundaries are kept (a scan stays an empty string at its
 * index); once the budget runs out the last page is cut and the rest are dropped.
 */
internal class PageCollector(private val maxChars: Int) {
  private val pages = ArrayList<String>()
  private var used = 0
  private var truncated = false

  /** Adds one page. Returns false when the budget is spent and no more pages are wanted. */
  fun add(raw: String): Boolean {
    val text = raw.replace("\r\n", "\n").trim()
    val room = maxChars - used
    if (text.length > room) {
      pages.add(text.substring(0, room.coerceAtLeast(0)))
      used = maxChars
      truncated = true
      return false
    }
    pages.add(text)
    used += text.length
    return used < maxChars
  }

  fun result(truncatedByCap: Boolean = false) = ExtractedText(pages, truncated || truncatedByCap)
}

/** Free Java-heap headroom right now, in bytes. A plain function reference so a test can fake a nearly-full heap
 * deterministically instead of needing to actually exhaust one (real tests). */
internal fun interface HeapHeadroom {
  fun bytes(): Long
}

/**
 * The margin a page needs, sized from the heap: the full [ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES] on a big heap, an eighth of it
 * on a small one (a 128 MB phone would otherwise stop at 19% used by documents and everything else).
 */
internal fun pdfPageHeapMargin(maxMemory: Long): Long = minOf(ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES, maxMemory / 8)

/** Real headroom: counts what a collection would free (garbage is not "used"), so a full-looking heap is collected once before a page is refused. */
private val systemHeapHeadroom = HeapHeadroom {
  val rt = Runtime.getRuntime()
  val ballast = com.craneai.findr.diag.HeapProbe.ballastBytes()
  fun headroom() = rt.maxMemory() - ballast - (rt.totalMemory() - rt.freeMemory() - ballast)
  var h = headroom()
  if (h < pdfPageHeapMargin(rt.maxMemory() - ballast)) {
    rt.gc()
    h = headroom()
  }
  h
}

/**
 * Reads a PDF's pages one at a time into a [PageCollector], the way [DocumentTextExtractor.pdf] does, but kept pure
 * so a test can drive it without a real PDF or a real low-memory phone. Stops early -- keeping every page already
 * collected -- the moment any of three things happens: [maxChars] is spent, [heapHeadroom] falls under
 * [ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES] (checked *before* a page is attempted, so the risky page is never
 * started), or [pageText] itself reports it ran out of memory (returns null: the page is not retried and nothing
 * after it is attempted either). A page that is merely unreadable (corrupt, say) returns "" and the loop continues,
 * as before this existed.
 */
internal fun collectPdfPages(
  totalPages: Int,
  maxChars: Int,
  heapHeadroom: HeapHeadroom,
  marginBytes: Long = ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES,
  pageText: (Int) -> String?,
): ExtractedText {
  val collector = PageCollector(maxChars)
  val last = minOf(totalPages, ExtractLimits.MAX_PDF_PAGES)
  var stoppedEarly = false
  for (page in 1..last) {
    if (heapHeadroom.bytes() < marginBytes) {
      stoppedEarly = true
      break
    }
    val text = pageText(page)
    if (text == null) {
      stoppedEarly = true
      break
    }
    if (!collector.add(text)) {
      stoppedEarly = page < last
      break
    }
  }
  return collector.result(truncatedByCap = stoppedEarly || totalPages > last)
}

/** pdfbox-android needs its glyph lists and fonts loaded from assets once per process. */
private object PdfBoxInit {
  @Volatile private var done = false

  fun ensure(context: Context) {
    if (done) return
    synchronized(this) {
      if (!done) {
        PDFBoxResourceLoader.init(context.applicationContext)
        done = true
      }
    }
  }
}
