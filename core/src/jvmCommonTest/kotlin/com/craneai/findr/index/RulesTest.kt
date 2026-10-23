package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
  private fun kind(path: String, mime: String? = null): FileKind {
    val name = path.substringAfterLast('/')
    val dir = if ('/' in path) path.substringBeforeLast('/') + "/" else ""
    return Rules.classify(name, mime, dir)
  }

  @Test fun photosAndScreenshots() {
    assertEquals(FileKind.IMAGE, kind("DCIM/Camera/20261001_101500.jpg", "image/jpeg"))
    assertEquals(FileKind.IMAGE, kind("DCIM/Camera/PXL_20261001_101500123.heic", "image/heic"))
    assertEquals(FileKind.IMAGE, kind("Pictures/WhatsApp Images/IMG-20261001-WA0004.jpg", "image/jpeg"))
    assertEquals(FileKind.SCREENSHOT, kind("DCIM/Screenshots/Screenshot_20261001_101500_Chrome.jpg", "image/jpeg"))
    assertEquals(FileKind.SCREENSHOT, kind("Pictures/Screenshots/anything.png", "image/png"))
    assertEquals(FileKind.SCREENSHOT, kind("Download/Screenshot_2026-10-01-10-15-00-12.png", "image/png"))
    assertEquals(FileKind.IMAGE, kind("DCIM/Camera/screen_protector_review.jpg", "image/jpeg"))
  }

  @Test fun documentsVideoAudio() {
    assertEquals(FileKind.PDF, kind("Download/receipt.pdf", "application/pdf"))
    assertEquals(FileKind.PDF, kind("Download/RECEIPT.PDF", "application/octet-stream"))
    assertEquals(FileKind.DOCUMENT, kind("Documents/notes.txt", "text/plain"))
    assertEquals(FileKind.DOCUMENT, kind("Download/letter.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    assertEquals(FileKind.DOCUMENT, kind("Download/letter.docx", null))
    assertEquals(FileKind.VIDEO, kind("DCIM/Camera/VID_20261001.mp4", "video/mp4"))
    assertEquals(FileKind.VIDEO, kind("Movies/clip.mkv", null))
    assertEquals(FileKind.AUDIO, kind("Recordings/Voice/rec 001.m4a", "audio/mp4"))
    assertEquals(FileKind.AUDIO, kind("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202641/PTT-20261001-WA0001.opus", "audio/ogg"))
  }

  @Test fun officeSpreadsheetsSlidesBooksAndTablesAreDocuments() {
    val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    val pptx = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    assertEquals(FileKind.DOCUMENT, kind("Download/budget.xlsx", xlsx))
    assertEquals(FileKind.DOCUMENT, kind("Download/budget.XLSX", "application/octet-stream"))   // file managers and Xender lose the type
    assertEquals(FileKind.DOCUMENT, kind("Download/deck.pptx", pptx))
    assertEquals(FileKind.DOCUMENT, kind("Download/deck.pptx", null))
    assertEquals(FileKind.DOCUMENT, kind("Download/sheet.ods", "application/vnd.oasis.opendocument.spreadsheet"))
    assertEquals(FileKind.DOCUMENT, kind("Download/slides.odp", null))
    assertEquals(FileKind.DOCUMENT, kind("Books/novel.epub", "application/epub+zip"))
    assertEquals(FileKind.DOCUMENT, kind("Books/novel.epub", "application/octet-stream"))
    assertEquals(FileKind.DOCUMENT, kind("Download/export.csv", "text/csv"))
    assertEquals(FileKind.DOCUMENT, kind("Download/export.csv", "text/comma-separated-values"))
    assertEquals(FileKind.DOCUMENT, kind("Download/export.tsv", null))
    // The type alone is enough when the name has no useful ending.
    assertEquals(FileKind.DOCUMENT, kind("Download/attachment", xlsx))
    // Legacy binary Excel has no reader but is attempted as a document (policy: no format is skipped); an archive is just an archive.
    assertEquals(FileKind.DOCUMENT, kind("Download/old.xls", "application/vnd.ms-excel"))
    assertEquals(FileKind.OTHER, kind("Download/pack.zip", "application/zip"))
  }

  @Test fun rareExtensionsMediaStoreFilesAsOctetStream() {
    // Found by the formats sweep on a real phone: these were name-only OTHER because the provider gives no media type.
    assertEquals(FileKind.IMAGE, kind("Download/photo.jfif", "application/octet-stream"))
    assertEquals(FileKind.IMAGE, kind("Download/photo.JFIF", null))
    assertEquals(FileKind.IMAGE, kind("Download/photo.jpe", null))
    assertEquals(FileKind.AUDIO, kind("Download/clip.weba", "application/octet-stream"))
    assertEquals(FileKind.AUDIO, kind("Download/clip.weba", null))
  }

  @Test fun everythingElseIsOther() {
    assertEquals(FileKind.OTHER, kind("Download/app.apk", "application/vnd.android.package-archive"))
    assertEquals(FileKind.OTHER, kind("Download/backup.zip", "application/zip"))
    assertEquals(FileKind.OTHER, kind("Download/library.so", null))
    assertEquals(FileKind.OTHER, kind("Download/data.bin", "application/octet-stream"))
  }

  @Test fun everyFormatIsAttemptedNotSkipped() {
    // Policy: a format is never a reason to skip. Pictures, video and sound the platform may or may not open are all attempted.
    for (n in listOf("a.tif", "a.tiff", "a.jp2", "a.jxl", "a.tga", "a.pcx", "a.ppm", "a.ico", "a.dng", "a.cr2", "a.nef", "a.arw", "a.psd", "a.JXL")) {
      assertEquals(n, FileKind.IMAGE, kind("Pictures/$n", null))
    }
    assertEquals(FileKind.IMAGE, kind("Pictures/a.tif", "image/tiff"))
    assertEquals("a type the rules never heard of still means a picture", FileKind.IMAGE, kind("Pictures/a.xyz", "image/x-something-new"))
    assertEquals(FileKind.IMAGE, kind("Pictures/raw.dng", "image/x-adobe-dng"))
    assertEquals(FileKind.IMAGE, kind("Pictures/art.psd", "image/vnd.adobe.photoshop"))
    for (n in listOf("a.wmv", "a.flv", "a.avi", "a.ts", "a.mts", "a.m2ts", "a.vob", "a.ogv", "a.3g2", "a.asf", "a.rmvb")) {
      assertEquals(n, FileKind.VIDEO, kind("Movies/$n", null))
    }
    assertEquals(FileKind.VIDEO, kind("Movies/a.xyz", "video/x-future"))
    for (n in listOf("a.aiff", "a.ape", "a.mka", "a.wv", "a.ac3", "a.au", "a.caf", "a.m4b", "a.mp2", "a.awb", "a.3ga")) {
      assertEquals(n, FileKind.AUDIO, kind("Music/$n", null))
    }
    assertEquals(FileKind.AUDIO, kind("Music/a.xyz", "audio/x-future"))
    // Vector art is a document (its words are read), legacy Office and web pages are attempted as documents.
    assertEquals(FileKind.DOCUMENT, kind("Pictures/logo.svg", "image/svg+xml"))
    assertEquals(FileKind.DOCUMENT, kind("Pictures/logo.svg", null))
    assertEquals(FileKind.DOCUMENT, kind("Pictures/logo.svgz", null))
    assertEquals(FileKind.DOCUMENT, kind("Download/page.html", "text/html"))
    assertEquals(FileKind.DOCUMENT, kind("Download/old.xls", "application/vnd.ms-excel"))
    assertEquals(FileKind.DOCUMENT, kind("Download/old.ppt", null))
  }

  private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
  private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
  private fun padded(b: ByteArray, size: Int = 64) = b.copyOf(maxOf(size, b.size))

  @Test fun filesAreRecognisedByTheirFirstBytes() {
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(bytes(0xFF, 0xD8, 0xFF, 0xE0))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(bytes(0x89) + ascii("PNG\r\n"))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(ascii("GIF89a"))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(ascii("II") + bytes(0x2A, 0))))        // TIFF
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(ascii("MM") + bytes(0, 0x2A))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBP"))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(bytes(0, 0, 0, 0x0C) + ascii("jP  "))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(bytes(0, 0, 0, 0x18) + ascii("ftypheic"))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(bytes(0, 0, 0, 0x1C) + ascii("ftypavif"))))
    assertEquals(FileKind.IMAGE, Rules.sniffKind(padded(ascii("8BPS"))))
    assertEquals(FileKind.VIDEO, Rules.sniffKind(padded(bytes(0, 0, 0, 0x20) + ascii("ftypisom"))))
    assertEquals(FileKind.VIDEO, Rules.sniffKind(padded(bytes(0, 0, 0, 0x14) + ascii("ftypqt  "))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(bytes(0, 0, 0, 0x20) + ascii("ftypM4A "))))
    assertEquals(FileKind.VIDEO, Rules.sniffKind(padded(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("AVI "))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WAVE"))))
    assertEquals(FileKind.VIDEO, Rules.sniffKind(padded(bytes(0x1A, 0x45, 0xDF, 0xA3))))       // Matroska
    assertEquals(FileKind.VIDEO, Rules.sniffKind(padded(bytes(0x30, 0x26, 0xB2, 0x75, 0x8E))))  // ASF
    assertEquals(FileKind.VIDEO, Rules.sniffKind(padded(ascii("FLV") + bytes(1, 5))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(ascii("OggS"))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(ascii("fLaC"))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(ascii("ID3") + bytes(3, 0))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(bytes(0xFF, 0xFB, 0x90, 0x00))))
    assertEquals(FileKind.AUDIO, Rules.sniffKind(padded(ascii("#!AMR\n"))))
    assertEquals(FileKind.PDF, Rules.sniffKind(padded(ascii("%PDF-1.7\n"))))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(padded(ascii("{\\rtf1\\ansi"))))
  }

  private fun zipHeader(firstEntry: String, content: String = ""): ByteArray {
    val name = ascii(firstEntry)
    val head = ByteArray(30) + name + ascii(content)
    head[0] = 'P'.code.toByte(); head[1] = 'K'.code.toByte(); head[2] = 3; head[3] = 4
    head[26] = name.size.toByte(); head[27] = 0
    return padded(head, 128)
  }

  @Test fun zipsAreDocumentsOnlyWhenTheirFirstPartSaysSo() {
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(zipHeader("[Content_Types].xml")))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(zipHeader("xl/workbook.xml")))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(zipHeader("mimetype", "application/epub+zip")))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(zipHeader("mimetype", "application/vnd.oasis.opendocument.text")))
    assertNull("an ordinary archive", Rules.sniffKind(zipHeader("photos/one.jpg")))
    assertNull("an app", Rules.sniffKind(zipHeader("AndroidManifest.xml")))
    assertNull("a mimetype that is not a document", Rules.sniffKind(zipHeader("mimetype", "application/vnd.android.package-archive")))
  }

  @Test fun textIsGuessedOnlyWhenTheNameGivesNoHint() {
    val note = ascii("Shopping list for Saturday: tomatoes, onions and rice.\n")
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(padded(note, note.size), extensionless = true))
    assertNull("unknown extension: only real signatures count", Rules.sniffKind(padded(note, note.size), extensionless = false))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><text>Hi</text></svg>")))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(ascii("<?xml version=\"1.0\"?>\n<svg width=\"10\"></svg>")))
    assertEquals(FileKind.DOCUMENT, Rules.sniffKind(ascii("<!DOCTYPE html><html><body>x</body></html>")))
    // Random data and executables say nothing.
    assertNull(Rules.sniffKind(padded(bytes(0x7F) + ascii("ELF") + bytes(2, 1, 1, 0, 1, 2, 3)), extensionless = true))
    assertNull(Rules.sniffKind(bytes(0, 1, 2), extensionless = true))
    assertNull("a UTF-16 text is not mistaken for MPEG audio", Rules.sniffKind(bytes(0xFF, 0xFE, 'h'.code, 0, 'i'.code, 0, ' '.code, 0)))
  }

  @Test fun onlyUnplacedFilesAreSniffed() {
    assertTrue(Rules.shouldSniff("IMG_0001", FileKind.OTHER))
    assertTrue(Rules.shouldSniff("clip.dat1", FileKind.OTHER))
    assertFalse("an archive is not opened", Rules.shouldSniff("backup.zip", FileKind.OTHER))
    assertFalse("an app is not opened", Rules.shouldSniff("app.apk", FileKind.OTHER))
    assertFalse("already classified", Rules.shouldSniff("IMG_0001.jpg", FileKind.IMAGE))
  }

  private fun skip(
    path: String, name: String, kind: FileKind = FileKind.IMAGE, size: Long = 200_000, w: Int? = 1200, h: Int? = 900,
    excluded: Set<String> = emptySet(), noMedia: Boolean = false,
  ) = Rules.skipReason(path, name, kind, size, w, h, excluded, noMedia)

  @Test fun normalPhoneFilesAreKept() {
    assertNull(skip("DCIM/Camera/", "IMG_1.jpg"))
    assertNull(skip("DCIM/Screenshots/", "Screenshot_1.jpg", FileKind.SCREENSHOT))
    assertNull(skip("Download/", "receipt.pdf", FileKind.PDF, w = null, h = null))
    assertNull(skip("Pictures/WhatsApp Images/Sent/", "IMG-WA0001.jpg"))
    assertNull(skip("Download/WhatsApp Documents/", "invoice.pdf", FileKind.PDF, w = null, h = null))
    // WhatsApp's media lives in Android/media on Android 11+: user content, so not skipped.
    assertNull(skip("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "IMG-WA0002.jpg"))
    assertNull(skip("", "root-note.txt", FileKind.DOCUMENT, w = null, h = null))
  }

  @Test fun appPrivateTreesAreSkipped() {
    assertTrue(skip("Android/data/com.example/files/", "pic.jpg")!!.contains("app-private"))
    assertTrue(skip("Android/obb/com.game/", "main.1.obb", FileKind.OTHER, w = null, h = null)!!.contains("app-private"))
    assertTrue(skip("android/DATA/com.example/", "pic.jpg")!!.contains("app-private"))
  }

  @Test fun hiddenCacheTrashAndThumbnailFoldersAreSkipped() {
    assertEquals("hidden folder", skip("Pictures/.hidden/", "a.jpg"))
    assertEquals("WhatsApp status", skip("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/", "status.mp4", FileKind.VIDEO, w = null, h = null))
    assertEquals("WhatsApp status", skip("WhatsApp/Media/.Statuses/", "status.jpg"))
    assertEquals("thumbnails", skip("DCIM/.thumbnails/", "1234.jpg"))
    assertEquals("thumbnails", skip("Pictures/thumbnails/", "t.jpg"))
    assertEquals("trashed", skip("Pictures/.trashed-1760000000/", "x.jpg"))
    assertEquals("cache", skip("Download/Cache/", "x.jpg"))
    assertEquals("trash", skip("Pictures/Trash/", "x.jpg"))
    assertEquals("stickers", skip("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/", "s.webp"))
    assertEquals("stickers", skip("WhatsApp/Media/Stickers/", "s.webp"))
  }

  @Test fun trashedAndPendingFilesAreSkippedByName() {
    assertEquals("trashed", skip("Pictures/", ".trashed-1760000000-IMG_1.jpg"))
    assertEquals("pending download", skip("Download/", ".pending-1760000000-video.mp4", FileKind.VIDEO))
    assertEquals("hidden file", skip("Pictures/", ".secret.jpg"))
    assertEquals("temporary or system file", skip("Download/", "movie.mp4.crdownload", FileKind.OTHER, w = null, h = null))
  }

  @Test fun appDatabasesAndChatBackupsAreClutterWithAReason() {
    val wa = "Android/media/com.whatsapp/WhatsApp/Databases/"
    assertEquals("chat backup (encrypted crypt15)", skip(wa, "msgstore.db.crypt15", FileKind.OTHER, w = null, h = null))
    assertEquals("chat backup (encrypted crypt14)", skip("Download/", "msgstore-2026-10-01.1.db.crypt14", FileKind.OTHER, w = null, h = null))
    assertEquals("chat backup (encrypted crypt12)", skip("Download/", "MSGSTORE.DB.CRYPT12", FileKind.OTHER, w = null, h = null))
    assertEquals("chat app database", skip("Download/", "msgstore.db", FileKind.OTHER, w = null, h = null))
    assertEquals("chat app database", skip("Download/", "wa.db", FileKind.OTHER, w = null, h = null))
    assertEquals("chat app database", skip("Download/", "wa.db-wal", FileKind.OTHER, w = null, h = null))
    assertEquals("app database", skip("Download/", "contacts.db", FileKind.OTHER, w = null, h = null))
    assertEquals("app database", skip("Download/", "contacts.db-wal", FileKind.OTHER, w = null, h = null))
    assertEquals("app database", skip("Download/", "contacts.db-shm", FileKind.OTHER, w = null, h = null))
    // folders: anything in a chat app's Databases or Backups folder, whatever it is called
    assertEquals("chat app databases and backups folder", skip(wa, "export.bin", FileKind.OTHER, w = null, h = null))
    assertEquals("chat app databases and backups folder", skip("Android/media/com.whatsapp/WhatsApp/Backups/", "chatbackup", FileKind.OTHER, w = null, h = null))
    assertEquals("chat app databases and backups folder", skip("Telegram/Backups/", "x.bin", FileKind.OTHER, w = null, h = null))
    assertEquals("chat app databases and backups folder", skip("Android/media/org.telegram.messenger/Telegram/Databases/", "x.bin", FileKind.OTHER, w = null, h = null))
    // a person's own Backups folder, and ordinary files, are not touched
    assertNull(skip("Documents/Backups/", "tax-2025.pdf", FileKind.PDF, w = null, h = null))
    assertNull(skip("Download/", "database-design-notes.txt", FileKind.DOCUMENT, w = null, h = null))
    assertNull(skip("Download/", "trawl-1.0.0-test.apk", FileKind.OTHER, w = null, h = null))
    assertNull(skip("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/", "receipt.pdf", FileKind.PDF, w = null, h = null))
  }

  @Test fun trawlsOwnPacksAreNotUserFiles() {
    // The current trawl- names, and the pre-1.0.3 findr- names that may still sit in Downloads.
    for (n in listOf(
      "trawl-quicklook-models.zip", "trawl-speech-models.zip", "trawl-private-models.zip", "Trawl-Speech-Models (1).zip",
      "findr-quicklook-models.zip", "findr-speech-models.zip", "findr-private-models.zip", "Findr-Speech-Models (1).zip",
      "embeddinggemma-2-740m.litertlm",
    )) {
      assertEquals(n, "Trawl's own add-on pack", skip("Download/", n, FileKind.OTHER, w = null, h = null))
    }
    // Look-alikes that are the person's own stay.
    for (n in listOf(
      "findr-models-notes.zip", "my-findr-quicklook-models.zip", "findr-quicklook-models-backup.zip", "findr.zip", "findr-report.pdf",
      "trawl-models-notes.zip", "my-trawl-speech-models.zip", "trawl-speech-models-backup.zip", "trawl.zip", "trawl-report.pdf",
    )) {
      assertNull(n, skip("Download/", n, FileKind.OTHER, w = null, h = null))
    }
  }

  @Test fun tinyImagesAreSkipped() {
    assertTrue(skip("Pictures/", "icon.png", size = 2_000, w = 48, h = 48)!!.startsWith("tiny image"))
    assertTrue(skip("Pictures/", "small.jpg", size = 5_000, w = null, h = null)!!.contains("6 KB"))
    assertTrue(skip("Pictures/", "thumb.jpg", size = 30_000, w = 90, h = 120)!!.contains("96 px"))
    assertTrue(skip("Pictures/", "banner.jpg", size = 30_000, w = 1200, h = 80)!!.contains("96 px"))
    assertNull(skip("Pictures/", "ok.jpg", size = 30_000, w = 96, h = 96))
    // Unknown size (-1) / dimensions don't count as tiny, and the rule is for images only.
    assertNull(skip("Pictures/", "unknown.jpg", size = -1, w = null, h = null))
    assertNull(skip("Documents/", "tiny.txt", FileKind.DOCUMENT, size = 100, w = null, h = null))
  }

  @Test fun emptyFilesWaitUntilTheyHaveBytes() {
    // A file still being written shows up with 0 bytes; its next crawl (new size) picks it up.
    assertEquals("empty (still being written?)", skip("Download/", "report.pdf", FileKind.PDF, size = 0, w = null, h = null))
    assertEquals("empty (still being written?)", skip("Pictures/", "IMG-WA0001.jpg", size = 0, w = null, h = null))
  }

  @Test fun excludedFoldersFromSettings() {
    val excluded = setOf("DCIM/Private", "Download/work/")
    assertEquals("excluded folder", skip("DCIM/Private/", "a.jpg", excluded = excluded))
    assertEquals("excluded folder", skip("dcim/private/trips/", "a.jpg", excluded = excluded))
    assertEquals("excluded folder", skip("Download/Work/Q3/", "a.jpg", excluded = excluded))
    assertNull(skip("DCIM/PrivateStuff/", "a.jpg", excluded = excluded))   // a prefix of the name is not the folder
    assertNull(skip("DCIM/Camera/", "a.jpg", excluded = excluded))
  }

  @Test fun noMediaMarksTheWholeSubtree() {
    val marked = setOf("/storage/emulated/0/Pictures/Hidden")
    val has: (String) -> Boolean = { it in marked }
    assertTrue(Rules.underNoMedia("/storage/emulated/0/Pictures/Hidden", has))
    assertTrue(Rules.underNoMedia("/storage/emulated/0/Pictures/Hidden/2026/trip", has))
    assertFalse(Rules.underNoMedia("/storage/emulated/0/Pictures/Visible", has))
    assertFalse(Rules.underNoMedia("/storage/emulated/0/Pictures", has))
    assertEquals(".nomedia folder", skip("Pictures/Hidden/", "a.jpg", noMedia = true))
    assertNotNull(skip("Pictures/Hidden/", "a.jpg", noMedia = true))
  }
}
