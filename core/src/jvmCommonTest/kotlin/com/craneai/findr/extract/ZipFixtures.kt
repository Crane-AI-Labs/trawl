package com.craneai.findr.extract

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/** Test fixtures are built in code so no binary documents are committed. */
internal object ZipFixtures {
  val parser: () -> XmlPullParser = { KXmlParser() }

  fun zip(dir: File, name: String, vararg entries: Pair<String, String>): File {
    val file = File(dir, name)
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((entry, content) in entries) {
        zip.putNextEntry(ZipEntry(entry))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return file
  }

  fun extract(format: DocFormat, file: File, maxChars: Int = 1_000_000) =
    OfficeText.extract(format, file, maxChars, parser)

  const val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
  const val A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
  const val R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
  const val SS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
  const val ODF = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
    "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
    "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
    "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" " +
    "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
}
