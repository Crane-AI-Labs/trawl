package com.craneai.findr.extract

import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException

/**
 * Opens a PDF for text extraction without paying for the parts of it text extraction never reads.
 *
 * pdfbox 2.0 parses eagerly: [PDFParser.initialParse] walks every object reachable from the document catalog and keeps
 * all of it on the Java heap, so the cost follows the size of the whole object graph, not the pages we read. A tagged
 * PDF carries a structure tree whose ParentTree holds one array of roughly 66,000 mostly-null elements per page. The
 * 1,161-page Uganda Clinical Guidelines took 259 MB live for that alone (46 MB without it), which on a 128 MB phone
 * is the whole heap and on a 256 MB tablet pinned the heap for minutes while every other thread starved.
 *
 * Text extraction needs the page tree, each page's resources (fonts, content streams) and the info dictionary. It never
 * touches the entries in [SKIPPED_ROOT_ENTRIES]. Skipping them leaves the extracted text byte-identical (checked by
 * SHA-1 on every readable PDF in the usage-sim corpus) and loads 5-10x faster.
 */
internal object LeanPdfLoader {
  /** Catalog entries that hold structure, navigation or form data and no page content. */
  val SKIPPED_ROOT_ENTRIES: Array<COSName> = arrayOf(
    COSName.STRUCT_TREE_ROOT,
    COSName.OUTLINES,
    COSName.NAMES,
    COSName.METADATA,
    COSName.PAGE_LABELS,
    COSName.THREADS,
    COSName.getPDFName("Dests"),
    COSName.getPDFName("OCProperties"),
    COSName.getPDFName("AcroForm"),
  )

  /** Scratch memory pdfbox may hold for stream data before it spills to a temp file. */
  private const val SCRATCH_BYTES = 16L * 1024 * 1024

  /**
   * Loads [file]. Throws [IOException] for a PDF that cannot be read, and [HeapFuseException] (an [IOException]) when the
   * load would take more than [fuse]'s share of the heap, so the caller skips the file instead of starving the app.
   */
  fun load(file: File, fuse: HeapFuse): PDDocument {
    val scratch = ScratchFile(MemoryUsageSetting.setupMixed(SCRATCH_BYTES))
    try {
      val source = FusedRandomAccessRead(RandomAccessBufferedFileInputStream(file), fuse)
      val parser = LeanParser(source, scratch)
      parser.parse()
      return parser.pdDocument
    } catch (e: IOException) {
      scratch.close()
      throw e
    } catch (e: RuntimeException) {
      scratch.close()
      throw e
    } catch (e: OutOfMemoryError) {
      scratch.close()
      throw e
    }
  }

  private class LeanParser(source: RandomAccessRead, scratch: ScratchFile) : PDFParser(source, "", null, null, scratch) {
    // Mirrors PDFParser.initialParse (pdfbox-android 2.0.27) with one change: the catalog's parse skips SKIPPED_ROOT_ENTRIES.
    override fun initialParse() {
      val trailer = retrieveTrailer()
      val base: COSBase? = parseTrailerValuesDynamically(trailer)
      if (base !is COSDictionary) throw IOException("Expected root dictionary, but got this: $base")
      if (isLenient && !base.containsKey(COSName.TYPE)) base.setItem(COSName.TYPE, COSName.CATALOG)
      parseDictObjects(base, *SKIPPED_ROOT_ENTRIES)
      val info = trailer.getDictionaryObject(COSName.INFO)
      if (info is COSDictionary) parseDictObjects(info)
      checkPages(base)
      if (base.getDictionaryObject(COSName.PAGES) !is COSDictionary) throw IOException("Page tree root must be a dictionary")
      document.setDecrypted()
    }
  }
}

/** Thrown when a PDF would take more of the Java heap than it may; an [IOException] so the file is skipped and recorded. */
internal class HeapFuseException(message: String) : IOException(message)

/**
 * A budget on the Java heap for one memory-hungry job. [check] is cheap (two Runtime calls) and, when the heap in use is
 * over [budgetBytes], collects once and throws [HeapFuseException] if it is still over. Garbage that merely has not been
 * collected yet never blows the fuse; live data does.
 */
internal class HeapFuse(private val budgetBytes: Long, private val usedBytes: () -> Long, private val collect: () -> Unit) {
  fun check() {
    if (usedBytes() <= budgetBytes) return
    collect()
    val used = usedBytes()
    if (used > budgetBytes) {
      throw HeapFuseException("heap ${used shr 20} MB in use is over this job's budget of ${budgetBytes shr 20} MB")
    }
  }

  companion object {
    /** Share of the Java heap one PDF open may bring the whole app up to. Leaves the rest for search, OCR and the UI. */
    const val SHARE = 0.75

    fun forThisProcess(): HeapFuse {
      val rt = Runtime.getRuntime()
      val ballast = com.craneai.findr.diag.HeapProbe.ballastBytes()
      val max = rt.maxMemory() - ballast
      return HeapFuse((max * SHARE).toLong(), { rt.totalMemory() - rt.freeMemory() - ballast }, { rt.gc() })
    }
  }
}

/** Passes reads through to [inner] and runs the [fuse] check as the parse advances, so a runaway load stops at its own door. */
private class FusedRandomAccessRead(private val inner: RandomAccessRead, private val fuse: HeapFuse) : RandomAccessRead by inner {
  private var singles = 0

  override fun read(): Int {
    if (++singles and (SINGLE_READ_MASK) == 0) fuse.check()
    return inner.read()
  }

  override fun read(b: ByteArray): Int {
    fuse.check()
    return inner.read(b)
  }

  override fun read(b: ByteArray, offset: Int, length: Int): Int {
    fuse.check()
    return inner.read(b, offset, length)
  }

  override fun seek(position: Long) {
    fuse.check()
    inner.seek(position)
  }

  private companion object {
    const val SINGLE_READ_MASK = 0xFFF
  }
}
