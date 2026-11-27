package trawl.desktop

import com.craneai.findr.core.KindFilter

/**
 * Optional file-format sub-filter inside a selected [KindFilter] (e.g. narrowing Documents to PDFs, Word, Excel, or PowerPoint).
 * Pure metadata so both the filter bar chip and unit tests share one definition.
 */
enum class FileSubType(
  val menuLabel: String,
  val chipLabel: String,
  val filters: Set<KindFilter>,
  val extensions: Set<String>,
) {
  PDF("PDF (.pdf)", "PDF", setOf(KindFilter.DOCUMENTS), setOf("pdf")),
  WORD("Word (.docx, .doc, .rtf)", "Word", setOf(KindFilter.DOCUMENTS), setOf("docx", "doc", "odt", "rtf", "pages")),
  SPREADSHEET("Excel & CSV (.xlsx, .csv)", "Excel / CSV", setOf(KindFilter.DOCUMENTS), setOf("xlsx", "xls", "ods", "csv", "tsv", "numbers")),
  PRESENTATION("PowerPoint (.pptx, .ppt)", "PowerPoint", setOf(KindFilter.DOCUMENTS), setOf("pptx", "ppt", "odp", "key")),
  TEXT_NOTES("Text & Markdown (.txt, .md)", "Text / MD", setOf(KindFilter.DOCUMENTS), setOf("txt", "md", "markdown", "rst", "org")),
  CODE_DATA("Code & Data (.json, .xml, ...)", "Code / Data", setOf(KindFilter.DOCUMENTS), setOf("json", "xml", "yaml", "yml", "toml", "ini", "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "h", "cpp", "rs", "go", "sh", "sql", "log", "svg")),

  PNG("PNG (.png)", "PNG", setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("png")),
  JPEG("JPEG (.jpg, .jpeg)", "JPEG", setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("jpg", "jpeg")),
  HEIC("HEIC (.heic, .heif)", "HEIC", setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("heic", "heif")),
  WEBP("WebP (.webp)", "WebP", setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("webp")),

  MP4("MP4 (.mp4, .m4v)", "MP4", setOf(KindFilter.VIDEOS), setOf("mp4", "m4v")),
  MOV("QuickTime (.mov)", "MOV", setOf(KindFilter.VIDEOS), setOf("mov")),
  MKV_WEBM("MKV / WebM (.mkv, .webm)", "MKV / WebM", setOf(KindFilter.VIDEOS), setOf("mkv", "webm", "avi")),

  M4A("M4A / AAC (.m4a, .aac)", "M4A", setOf(KindFilter.AUDIO), setOf("m4a", "aac")),
  MP3("MP3 (.mp3)", "MP3", setOf(KindFilter.AUDIO), setOf("mp3")),
  WAV("WAV (.wav)", "WAV", setOf(KindFilter.AUDIO), setOf("wav")),
  OGG_FLAC("OGG / FLAC (.ogg, .flac)", "OGG / FLAC", setOf(KindFilter.AUDIO), setOf("ogg", "oga", "opus", "flac"));

  fun matches(fileName: String): Boolean {
    val ext = extensionOf(fileName) ?: return false
    return ext in extensions
  }

  companion object {
    fun extensionOf(fileName: String): String? {
      val dot = fileName.lastIndexOf('.')
      if (dot <= 0 || dot >= fileName.lastIndex) return null
      return fileName.substring(dot + 1).lowercase()
    }

    fun forFilter(filter: KindFilter): List<FileSubType> =
      if (filter == KindFilter.ALL) emptyList() else entries.filter { filter in it.filters }
  }
}
