package com.craneai.findr.ui.search

import androidx.annotation.StringRes
import com.craneai.findr.R
import com.craneai.findr.core.KindFilter

/**
 * Optional file-format sub-filter inside a selected [KindFilter] (e.g. narrowing Documents to PDFs, Word, Excel, or PowerPoint).
 * Pure metadata so both the filter bar chip and unit tests share one definition.
 */
enum class FileSubType(
  @param:StringRes val menuLabelRes: Int,
  @param:StringRes val chipLabelRes: Int,
  val filters: Set<KindFilter>,
  val extensions: Set<String>,
) {
  PDF(R.string.subtype_pdf_menu, R.string.subtype_pdf_chip, setOf(KindFilter.DOCUMENTS), setOf("pdf")),
  WORD(R.string.subtype_word_menu, R.string.subtype_word_chip, setOf(KindFilter.DOCUMENTS), setOf("docx", "doc", "odt", "rtf", "pages")),
  SPREADSHEET(R.string.subtype_spreadsheet_menu, R.string.subtype_spreadsheet_chip, setOf(KindFilter.DOCUMENTS), setOf("xlsx", "xls", "ods", "csv", "tsv", "numbers")),
  PRESENTATION(R.string.subtype_presentation_menu, R.string.subtype_presentation_chip, setOf(KindFilter.DOCUMENTS), setOf("pptx", "ppt", "odp", "key")),
  TEXT_NOTES(R.string.subtype_text_menu, R.string.subtype_text_chip, setOf(KindFilter.DOCUMENTS), setOf("txt", "md", "markdown", "rst", "org")),
  CODE_DATA(R.string.subtype_code_menu, R.string.subtype_code_chip, setOf(KindFilter.DOCUMENTS), setOf("json", "xml", "yaml", "yml", "toml", "ini", "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "h", "cpp", "rs", "go", "sh", "sql", "log", "svg")),

  PNG(R.string.subtype_png_menu, R.string.subtype_png_chip, setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("png")),
  JPEG(R.string.subtype_jpeg_menu, R.string.subtype_jpeg_chip, setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("jpg", "jpeg")),
  HEIC(R.string.subtype_heic_menu, R.string.subtype_heic_chip, setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("heic", "heif")),
  WEBP(R.string.subtype_webp_menu, R.string.subtype_webp_chip, setOf(KindFilter.PHOTOS, KindFilter.SCREENSHOTS), setOf("webp")),

  MP4(R.string.subtype_mp4_menu, R.string.subtype_mp4_chip, setOf(KindFilter.VIDEOS), setOf("mp4", "m4v")),
  MOV(R.string.subtype_mov_menu, R.string.subtype_mov_chip, setOf(KindFilter.VIDEOS), setOf("mov")),
  MKV_WEBM(R.string.subtype_mkv_menu, R.string.subtype_mkv_chip, setOf(KindFilter.VIDEOS), setOf("mkv", "webm", "avi")),

  M4A(R.string.subtype_m4a_menu, R.string.subtype_m4a_chip, setOf(KindFilter.AUDIO), setOf("m4a", "aac")),
  MP3(R.string.subtype_mp3_menu, R.string.subtype_mp3_chip, setOf(KindFilter.AUDIO), setOf("mp3")),
  WAV(R.string.subtype_wav_menu, R.string.subtype_wav_chip, setOf(KindFilter.AUDIO), setOf("wav")),
  OGG_FLAC(R.string.subtype_ogg_menu, R.string.subtype_ogg_chip, setOf(KindFilter.AUDIO), setOf("ogg", "oga", "opus", "flac"));

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
