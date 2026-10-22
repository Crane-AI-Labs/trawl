package com.craneai.findr.extract

/** Safety limits shared by the extractors. A phone has little RAM and the files come from anywhere. */
object ExtractLimits {
  /** Text extraction refuses anything bigger than this. */
  const val MAX_FILE_BYTES = 200L * 1024 * 1024

  /** One zip entry may inflate to this much (zip-bomb guard). The limit is hit as end-of-file, not as an error. */
  const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

  /** All entries of one archive together. */
  const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024

  /** A PDF longer than this is cut off (DigUp uses the same cap). */
  const val MAX_PDF_PAGES = 5000

  /**
   * Free Java-heap headroom a PDF page needs before [DocumentTextExtractor] will attempt it: below this, extraction
   * stops and keeps the pages already read, instead of risking an OutOfMemoryError that (uncaught per page) used to
   * throw away every page read so far and leave the file searchable by name only. A 13.5 MB PDF with many embedded
   * fonts (the messy corpus' clinical guidelines) ran pdfbox out of heap on a 192 MB phone (the TECNO, and the 2 GB
   * emulator's lite profile) before this existed; the margin is sized against that device class.
   */
  const val PDF_PAGE_HEAP_MARGIN_BYTES = 24L * 1024 * 1024

  /** Spreadsheets and CSV files stop at this much text (about 2,500 chunks): cells of numbers add little to search. */
  const val TABULAR_MAX_CHARS = 1_000_000

  /** ... or at this many non-empty cells, whichever comes first. */
  const val MAX_CELLS = 200_000

  /** CSV, TSV and the like: how many bytes of the head are read. */
  const val MAX_DELIMITED_BYTES = 4 * 1024 * 1024

  /** One EPUB chapter (or a whole book kept in one file) is read to this many bytes. */
  const val MAX_CHAPTER_BYTES = 4 * 1024 * 1024

  /** Shared-string tables of huge spreadsheets stop growing here; later strings read as empty. */
  const val MAX_SHARED_STRING_CHARS = 8_000_000
}

/** The document types [DocumentTextExtractor] reads, chosen by extension first (MIME types from file managers lie). */
enum class DocFormat {
  PLAIN, DELIMITED, HTML, RTF, PDF, DOCX, XLSX, PPTX, ODT, ODS, ODP, EPUB, SVG;

  companion object {
    private val byExtension = mapOf(
      "txt" to PLAIN, "md" to PLAIN, "markdown" to PLAIN, "json" to PLAIN, "log" to PLAIN,
      "csv" to DELIMITED, "tsv" to DELIMITED,
      "html" to HTML, "htm" to HTML, "xhtml" to HTML,
      "rtf" to RTF, "pdf" to PDF, "docx" to DOCX, "xlsx" to XLSX, "pptx" to PPTX,
      "odt" to ODT, "ods" to ODS, "odp" to ODP, "epub" to EPUB, "svg" to SVG, "svgz" to SVG,
    )
    private val byMime = mapOf(
      "text/plain" to PLAIN, "text/markdown" to PLAIN,
      "text/csv" to DELIMITED, "text/comma-separated-values" to DELIMITED, "text/tab-separated-values" to DELIMITED,
      "application/json" to PLAIN,
      "text/html" to HTML, "application/xhtml+xml" to HTML,
      "text/rtf" to RTF, "application/rtf" to RTF, "application/pdf" to PDF,
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DOCX,
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to XLSX,
      "application/vnd.openxmlformats-officedocument.presentationml.presentation" to PPTX,
      "application/vnd.oasis.opendocument.text" to ODT,
      "application/vnd.oasis.opendocument.spreadsheet" to ODS,
      "application/vnd.oasis.opendocument.presentation" to ODP,
      "application/epub+zip" to EPUB, "image/svg+xml" to SVG,
    )

    fun detect(mimeType: String?, name: String): DocFormat? {
      val ext = name.substringAfterLast('.', "").lowercase()
      byExtension[ext]?.let { return it }
      return mimeType?.substringBefore(';')?.trim()?.lowercase()?.let { byMime[it] }
    }

    /**
     * What a file is from its first bytes, for a file whose name and type did not say (saved without an extension, or an
     * extension nobody reads). Null for binary data. A zip is reported as DOCX: [OfficeText] looks inside and reads it as
     * whatever its parts say, or finds nothing.
     */
    fun fromHeader(bytes: ByteArray, length: Int = bytes.size): DocFormat? {
      if (length < 4) return null
      fun b(i: Int) = bytes[i].toInt() and 0xFF
      if (TextDecoding.isPdf(bytes, length)) return PDF
      if (b(0) == 'P'.code && b(1) == 'K'.code && b(2) == 3 && b(3) == 4) return DOCX
      if (b(0) == 0x1F && b(1) == 0x8B) return SVG   // gzip: only SVGZ is read this way
      if (TextDecoding.looksBinary(bytes, length)) return null
      val head = TextDecoding.decode(bytes, length, cut = true).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
      return when {
        head.startsWith("{\\rtf") -> RTF
        head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg")) -> SVG
        head.startsWith("<!doctype html") || head.startsWith("<html") -> HTML
        else -> PLAIN
      }
    }
  }
}
