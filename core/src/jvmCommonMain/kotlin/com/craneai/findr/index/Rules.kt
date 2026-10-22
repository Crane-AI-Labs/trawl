package com.craneai.findr.index

import com.craneai.findr.core.FileKind

/**
 * What a file is, and whether to read it. Pure functions over what MediaStore tells us, so they are unit-tested
 * against realistic phone paths. The rules come from DigUp's (a real library's lessons) plus what phones add:
 * app-private trees, trash, caches, WhatsApp's statuses and stickers.
 */
object Rules {

  /**
   * Bump when [classify] starts calling existing files something else: the worker then crawls every row once, and a
   * file already in the index whose kind changed is queued to be read as its new kind (IndexStore.upsertOne).
   * 2: xlsx, pptx, ods, odp, epub, csv and tsv became DOCUMENT (they were name-only OTHER files).
   * 3: attempt every format: more picture, video and sound extensions, SVG as a document, and files with no or an
   *    unknown extension are recognised by their first bytes ([sniffKind]).
   * 4: app databases and chat backups (msgstore, wa.db, *.crypt12/14/15, WhatsApp/Telegram Databases and Backups) are
   *    clutter ([appDataReason]); files already indexed are marked skipped with a reason.
   */
  const val VERSION = 4

  // ---- classification --------------------------------------------------------------------------------------------
  //
  // POLICY (2026-10-16): a file is never skipped because of its FORMAT. Every picture, video, sound and
  // document-like file is attempted, whatever the extension or MIME type says. If this phone cannot open it, the
  // attempt FAILS SAFELY (state failed, "this phone can't open this format", still found by name, not retried until
  // an app update) and the queue moves on. Only clutter is skipped (see [skipReason]), and each skip has a reason.

  // jfif and weba: MediaStore files them as application/octet-stream, so without the extension they were name-only OTHER (formats sweep).
  private val IMAGE_EXT = setOf(
    "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif", "jfif", "jpe",
    "tif", "tiff", "jp2", "j2k", "jpf", "jpx", "jxl", "tga", "pcx", "ppm", "pgm", "pbm", "pnm", "ico", "wbmp", "xbm",
    "heics", "heifs", "avci", "avcs", "dng", "psd",
    // Camera raw: the phone may decode the embedded preview (DNG) or nothing at all; either way it is attempted.
    "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "srw", "pef", "raw",
  )
  private val VIDEO_EXT = setOf(
    "mp4", "mkv", "mov", "3gp", "3gpp", "3g2", "webm", "avi", "m4v", "mpg", "mpeg", "mpe",
    "wmv", "asf", "flv", "f4v", "ts", "mts", "m2ts", "vob", "ogv", "divx", "rm", "rmvb", "mxf", "dv", "qt",
  )
  private val AUDIO_EXT = setOf(
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "awb", "wma", "mid", "midi", "waptt", "weba",
    "aif", "aiff", "aifc", "ape", "wv", "mka", "m4b", "m4r", "mp2", "ac3", "dts", "au", "snd", "caf", "tta", "mpc", "ra", "spx", "3ga", "dsf", "dff",
  )
  private val DOC_EXT = setOf(
    "txt", "text", "md", "markdown", "rtf", "doc", "docx", "odt",
    // Read by content (Office/OpenDocument zips, EPUB, delimited text): the office-readers work.
    "xlsx", "pptx", "ods", "odp", "epub", "csv", "tsv",
    // Document-like files we can read, and ones we attempt although no reader understands them yet (legacy Office,
    // iWork, web pages): attempted means found by name and read when a reader exists, never skipped for the format.
    "xls", "ppt", "html", "htm", "xhtml", "json", "svg", "svgz", "pages", "numbers", "key", "odg", "xlsm", "docm", "pptm",
  )
  private val DOC_MIME = setOf(
    "text/plain", "text/markdown", "text/rtf", "application/rtf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation",
    "application/epub+zip", "text/csv", "text/comma-separated-values", "text/tab-separated-values",
    "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "text/html", "application/xhtml+xml", "application/json",
    "image/svg+xml",
  )

  fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

  /**
   * Screenshots are images in a `Screenshots` folder or named `Screenshot_…` (Samsung, Pixel, Xiaomi and others
   * all follow one of the two). People search for them differently from photos, so they are a kind of their own.
   */
  fun isScreenshot(relativePath: String, name: String): Boolean {
    if (name.startsWith("Screenshot", ignoreCase = true) || name.startsWith("Screen Shot", ignoreCase = true)) return true
    return relativePath.split('/').any { it.equals("Screenshots", ignoreCase = true) || it.equals("Screenshot", ignoreCase = true) }
  }

  fun classify(name: String, mimeType: String?, relativePath: String = ""): FileKind {
    val ext = extensionOf(name)
    val mime = mimeType?.lowercase()
    return when {
      mime == "application/pdf" || ext == "pdf" -> FileKind.PDF
      // Vector art is a document here: it has no bitmap to decode, but the words inside it can be read (DocFormat.SVG).
      mime == "image/svg+xml" || ext == "svg" || ext == "svgz" -> FileKind.DOCUMENT
      mime?.startsWith("image/") == true -> if (isScreenshot(relativePath, name)) FileKind.SCREENSHOT else FileKind.IMAGE
      mime?.startsWith("video/") == true -> FileKind.VIDEO
      mime?.startsWith("audio/") == true || mime == "application/ogg" -> FileKind.AUDIO
      mime != null && mime in DOC_MIME -> FileKind.DOCUMENT
      // Some providers report no type or "application/octet-stream": the extension decides.
      ext in IMAGE_EXT -> if (isScreenshot(relativePath, name)) FileKind.SCREENSHOT else FileKind.IMAGE
      ext in VIDEO_EXT -> FileKind.VIDEO
      ext in AUDIO_EXT -> FileKind.AUDIO
      ext in DOC_EXT -> FileKind.DOCUMENT
      else -> FileKind.OTHER
    }
  }

  // ---- recognising a file by its first bytes ---------------------------------------------------------------------

  /** Bytes the crawler reads from a file whose name and MIME type told us nothing ([sniffKind]). */
  const val SNIFF_BYTES = 512

  /**
   * Extensions that are known not to be pictures, sounds, videos or documents (archives, apps, libraries, fonts, disk
   * images, code): a file with one of these is not opened to look at its bytes. Everything else with an extension
   * nobody classified, and every file with no extension at all, is sniffed.
   */
  private val KNOWN_OTHER_EXT = setOf(
    "zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "zst", "lz", "cab", "arj",
    "apk", "apks", "xapk", "aab", "jar", "dex", "odex", "vdex", "oat", "art", "so", "dll", "exe", "msi", "dmg", "pkg", "deb", "rpm", "iso", "img", "bin", "dat", "obb", "pak", "torrent",
    "ttf", "otf", "woff", "woff2", "ttc", "kt", "java", "py", "js", "c", "h", "cpp", "sh", "xml", "yaml", "yml", "ini", "cfg", "conf", "properties", "sql", "vcf", "ics", "lock", "pem", "key", "crt", "p12", "keystore", "jks",
  )

  /** Whether the crawler should open this unclassified file and look at its first bytes. */
  fun shouldSniff(name: String, kind: FileKind): Boolean = kind == FileKind.OTHER && extensionOf(name) !in KNOWN_OTHER_EXT

  /**
   * What a file is from its first bytes, or null when they say nothing we attempt (an archive, an app, a database, random data).
   * [extensionless] allows the "this is plain text" guess, which is only safe when the name gave no hint at all (a README, a
   * note saved without an extension); with an unknown extension (`.xyz`) only formats with a real signature count.
   */
  fun sniffKind(header: ByteArray, extensionless: Boolean = false): FileKind? {
    val n = header.size
    if (n < 4) return null
    fun b(i: Int) = if (i < n) header[i].toInt() and 0xFF else -1
    fun at(i: Int, s: String) = s.indices.all { b(i + it) == s[it].code }
    fun ascii(i: Int, len: Int) = String(header, i, minOf(len, maxOf(0, n - i)), Charsets.ISO_8859_1)

    // Pictures
    if (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return FileKind.IMAGE                       // JPEG
    if (b(0) == 0x89 && at(1, "PNG")) return FileKind.IMAGE
    if (at(0, "GIF87a") || at(0, "GIF89a")) return FileKind.IMAGE
    if (at(0, "II") && b(2) == 0x2A && b(3) == 0 || at(0, "MM") && b(2) == 0 && b(3) == 0x2A) return FileKind.IMAGE   // TIFF (DNG and most raw files too)
    if (at(0, "8BPS")) return FileKind.IMAGE                                                       // Photoshop
    if (b(0) == 0 && b(1) == 0 && b(2) == 0 && b(3) == 0x0C && (at(4, "jP  ") || at(4, "JXL "))) return FileKind.IMAGE   // JPEG 2000, JPEG XL
    if (b(0) == 0xFF && b(1) == 0x4F && b(2) == 0xFF && b(3) == 0x51) return FileKind.IMAGE       // JPEG 2000 codestream
    if (b(0) == 0 && b(1) == 0 && b(2) == 1 && b(3) == 0 && b(5) == 0 && b(4) in 1..64) return FileKind.IMAGE   // ICO
    if (at(0, "BM") && b(6) == 0 && b(7) == 0 && b(8) == 0 && b(9) == 0) return FileKind.IMAGE     // BMP
    if (b(0) == 'P'.code && b(1) in '1'.code..'6'.code && (b(2) == 0x0A || b(2) == 0x20 || b(2) == 0x0D || b(2) == 0x09)) return FileKind.IMAGE   // PBM/PGM/PPM
    // RIFF containers
    if (at(0, "RIFF") && n >= 12) return when (ascii(8, 4)) {
      "WEBP" -> FileKind.IMAGE
      "AVI " -> FileKind.VIDEO
      "WAVE" -> FileKind.AUDIO
      else -> null
    }
    // ISO base media: the brand says what is inside
    if (at(4, "ftyp") && n >= 12) {
      val brand = ascii(8, 4)
      return when {
        brand in HEIF_BRANDS || brand == "crx " -> FileKind.IMAGE
        brand in AUDIO_BRANDS -> FileKind.AUDIO
        else -> FileKind.VIDEO
      }
    }
    if (at(4, "moov") || at(4, "mdat") || at(4, "wide")) return FileKind.VIDEO   // old QuickTime
    // Video
    if (b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3) return FileKind.VIDEO       // Matroska / WebM
    if (b(0) == 0x30 && b(1) == 0x26 && b(2) == 0xB2 && b(3) == 0x75) return FileKind.VIDEO       // ASF (wmv, wma)
    if (at(0, "FLV") && b(3) == 1) return FileKind.VIDEO
    if (b(0) == 0 && b(1) == 0 && b(2) == 1 && b(3) == 0xBA) return FileKind.VIDEO                // MPEG program stream
    // Sound
    if (at(0, "OggS") || at(0, "fLaC") || at(0, "ID3") || at(0, "MThd") || at(0, "#!AMR") || at(0, "MAC ") || at(0, "wvpk") || at(0, "FORM") && at(8, "AIF")) return FileKind.AUDIO
    if (b(0) == 0xFF && b(1) in MPEG_AUDIO_SYNC) return FileKind.AUDIO                             // MPEG audio / ADTS frame sync
    // Documents
    if (at(0, "%PDF-")) return FileKind.PDF
    if (at(0, "{\\rtf")) return FileKind.DOCUMENT
    if (at(0, "PK") && b(2) == 3 && b(3) == 4 && n >= 30) {
      val len = b(26) or (b(27) shl 8)
      val first = ascii(30, len)
      return if (first == "mimetype") {
        val kind = ascii(30 + len, 64)
        if (kind.startsWith("application/vnd.oasis.opendocument") || kind.startsWith("application/epub+zip")) FileKind.DOCUMENT else null
      } else if (first == "[Content_Types].xml" || first.startsWith("word/") || first.startsWith("xl/") || first.startsWith("ppt/") || first.startsWith("_rels/") || first.startsWith("docProps/")) FileKind.DOCUMENT
      else null
    }
    val text = com.craneai.findr.extract.TextDecoding.decode(header, n, cut = true).trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
    if (!text.contains('\u0000') && (text.startsWith("<svg") || text.startsWith("<!doctype html") || text.startsWith("<html") ||
        (text.startsWith("<?xml") && text.contains("<svg")))) return FileKind.DOCUMENT
    if (extensionless && n >= 8 && !com.craneai.findr.extract.TextDecoding.looksBinary(header, n) && text.count { it.isLetter() } >= 8) return FileKind.DOCUMENT
    return null
  }

  private val MPEG_AUDIO_SYNC = setOf(0xFB, 0xFA, 0xF3, 0xF2, 0xF1, 0xF9)
  private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis")
  private val AUDIO_BRANDS = setOf("M4A ", "M4B ", "M4P ", "F4A ", "F4B ")

  // ---- skip rules ------------------------------------------------------------------------------------------------
  //
  // CLUTTER ONLY, never a format. Each rule below names what it keeps out and why, and the reason string is what the
  // file list shows: app-private and cache/thumbnail/trash/sticker folders (made by the system or an app, not kept by
  // the person), `.nomedia` (Android's own "leave this alone"), hidden and half-downloaded files, Findr's own packs,
  // temporary files by extension, app databases and chat backups ([appDataReason]), empty files (still being written), and icon-sized pictures.
  // Candidates the owner may want attempted too (they are text or images of a sort): `.log`, `.bak`, WhatsApp
  // stickers, thumbnails. They stay skipped until someone says otherwise.

  /** Folder names (any level, case-insensitive) that hold what the system or an app made, not what the user kept. */
  private val SKIPPED_FOLDERS = setOf(
    "cache", "caches", ".cache", "code_cache", "thumbnails", ".thumbnails", "thumbs", ".thumbs",
    "trash", ".trash", "recycle bin", "recyclebin", ".recycle",
    "stickers", "whatsapp stickers", "whatsapp profile photos", ".statuses",
    "node_modules",
  )

  /**
   * App databases and chat backups: WhatsApp's `msgstore.db.crypt15` (and 12/14), `msgstore-2026-10-01.1.db.crypt14`,
   * `wa.db`, and any other SQLite file. They are not documents a person reads, they hold private message data, and
   * they flood Recent and the name index. Matched by name, wherever they sit.
   */
  private val CRYPT_EXT = Regex("""^crypt\d+$""")
  private val APP_DATABASE_EXT = setOf("db", "db-wal", "db-shm", "db-journal", "sqlite", "sqlite3", "sqlite-wal", "sqlite-shm")

  /** Folders chat apps keep their databases and backups in (`.../WhatsApp/Databases`, `.../WhatsApp/Backups`, Telegram's). */
  private val APP_DATA_FOLDERS = setOf("databases", "backups")
  private val CHAT_APPS = listOf("whatsapp", "telegram")

  /**
   * Why a file is an app's database or backup, or null. [relativePath] is the folder it sits in. A `Databases` or
   * `Backups` folder only counts under a WhatsApp or Telegram folder (a person's own "Backups" folder is theirs).
   */
  fun appDataReason(relativePath: String, name: String): String? {
    val lowerName = name.lowercase()
    val ext = extensionOf(name)
    if (CRYPT_EXT.matches(ext)) return "chat backup (encrypted $ext)"
    if (lowerName.startsWith("msgstore") || lowerName == "wa.db" || lowerName.startsWith("wa.db-")) return "chat app database"
    if (ext in APP_DATABASE_EXT) return "app database"
    val parts = relativePath.lowercase().split('/').filter { it.isNotEmpty() }
    for ((i, part) in parts.withIndex()) {
      if (part in APP_DATA_FOLDERS && parts.take(i).any { p -> CHAT_APPS.any { p.contains(it) } }) return "chat app databases and backups folder"
    }
    return null
  }

  /** Extensions of files that are noise by name alone (temp data and databases; they flood a name index). */
  private val JUNK_EXT = setOf("tmp", "temp", "cache", "log", "db", "db-wal", "db-shm", "journal", "nomedia", "thumb", "crdownload", "part", "download", "bak")

  /**
   * Folder names (any level, case-insensitive) that apps use to lock pictures away from the gallery: vault and
   * "secure folder" apps and the phone makers' own. A person who put photos there asked for them to stay private,
   * so they are not read, whatever the switches say. Add a name here and nothing else needs to change.
   */
  private val PRIVATE_FOLDERS = setOf(
    "secure folder", "securefolder", "secure_folder", "knox", "private mode", "privatemode", "vault", "photo vault",
    "gallery vault", "galleryvault", "hide it pro", "hideitpro", "keepsafe", "calculator vault",
  )

  /** Why a file is recorded as skipped when its folder group is switched off; the group id follows. */
  const val SCOPE_OFF_PREFIX = "turned off: "

  const val STATUS_REASON = "WhatsApp status"

  /**
   * Findr's own downloads: add-on packs (`findr-speech-models.zip`, `findr-quicklook-models.zip`, a browser's
   * "findr-speech-models (1).zip") and the embedding model file. They sit in Downloads until installed, and are not the
   * person's files, so they never show up in Recent or in results.
   */
  private val OWN_PACK = Regex("""^findr-[a-z0-9-]+-models( \(\d+\))?\.zip$""", RegexOption.IGNORE_CASE)
  private val OWN_MODEL = Regex("""^embeddinggemma[a-z0-9._-]*\.litertlm$""", RegexOption.IGNORE_CASE)

  fun isOwnPack(name: String): Boolean = OWN_PACK.matches(name) || OWN_MODEL.matches(name)

  const val MIN_IMAGE_SIDE = 96
  const val MIN_IMAGE_BYTES = 6 * 1024

  /**
   * Why a folder is never read, or null. [relativePath] is MediaStore's RELATIVE_PATH ("Android/data/com.x/files/").
   * [allowStatuses] is the one private place a switch can open (WhatsApp Status, off unless asked for).
   * Note `Android/media/<app>/` is deliberately NOT skipped: since Android 11 that is where WhatsApp keeps the
   * photos, voice notes and documents people actually want to search.
   */
  fun folderSkipReason(relativePath: String, excludedFolders: Set<String> = emptySet(), allowStatuses: Boolean = false): String? {
    val parts = relativePath.split('/').filter { it.isNotEmpty() }
    if (parts.size >= 2 && parts[0].equals("Android", true) && (parts[1].equals("data", true) || parts[1].equals("obb", true))) {
      return "app-private folder (Android/${parts[1]})"
    }
    for (part in parts) {
      val lower = part.lowercase()
      if (lower.startsWith(".trashed")) return "trashed"
      if (lower == ".statuses") { if (allowStatuses) continue else return STATUS_REASON }
      if (lower in PRIVATE_FOLDERS) return "private folder"
      // Named junk folders first, so ".thumbnails" reports as thumbnails rather than just "hidden".
      if (lower in SKIPPED_FOLDERS) return folderReason(lower)
      if (lower.startsWith(".")) return "hidden folder"
    }
    val normalized = parts.joinToString("/").lowercase()
    for (excluded in excludedFolders) {
      val e = excluded.trim('/').lowercase()
      if (e.isNotEmpty() && (normalized == e || normalized.startsWith("$e/"))) return "excluded folder"
    }
    return null
  }

  private fun folderReason(lower: String) = when {
    lower.contains("cache") -> "cache"
    lower.contains("thumb") -> "thumbnails"
    lower.contains("trash") || lower.contains("recycle") -> "trash"
    lower.contains("sticker") -> "stickers"
    else -> "app folder"
  }

  /**
   * Whether anything above this file holds a `.nomedia`, which is Android's own "leave this alone" marker.
   * [hasNoMedia] answers for one absolute folder (the crawler caches it); ancestors are checked too, because the
   * marker hides the whole subtree.
   */
  fun underNoMedia(absoluteFolder: String, hasNoMedia: (String) -> Boolean): Boolean {
    var dir = absoluteFolder.trimEnd('/')
    while (dir.isNotEmpty()) {
      if (hasNoMedia(dir)) return true
      val cut = dir.lastIndexOf('/')
      if (cut <= 0) return false
      dir = dir.substring(0, cut)
    }
    return false
  }

  /**
   * Whether the person's folder choices keep this file out: null to read it, otherwise "turned off: <group id>".
   * Runs after the private and excluded rules, so a switch or an added folder can only ever widen what is read
   * within what those allow.
   */
  fun scopeSkipReason(relativePath: String, kind: FileKind, scope: FolderScope): String? {
    val group = FolderGroups.groupOf(relativePath, kind)
    if (scope.isOn(group) || scope.isIncluded(relativePath)) return null
    return SCOPE_OFF_PREFIX + group.id
  }

  /**
   * The whole decision for one file: null to index it, otherwise the reason it is recorded as skipped.
   * [noMedia] is true if a `.nomedia` marks its folder (checked by the crawler, which has the file system).
   */
  fun skipReason(
    relativePath: String, name: String, kind: FileKind, sizeBytes: Long, width: Int?, height: Int?,
    excludedFolders: Set<String> = emptySet(), noMedia: Boolean = false, scope: FolderScope = FolderScope(),
  ): String? {
    folderSkipReason(relativePath, excludedFolders, allowStatuses = scope.isOn(FolderGroups.WHATSAPP_STATUS))?.let { return it }
    if (noMedia) return ".nomedia folder"
    if (name.startsWith(".")) return if (name.startsWith(".trashed")) "trashed" else if (name.startsWith(".pending")) "pending download" else "hidden file"
    if (isOwnPack(name)) return "Findr's own add-on pack"
    appDataReason(relativePath, name)?.let { return it }
    if (extensionOf(name) in JUNK_EXT) return "temporary or system file"
    // An empty file is usually one still being written (WhatsApp, browsers): skip it for now. Once it has bytes its
    // fingerprint (size:mtime) changes and the next crawl picks it up. the research notes
    if (sizeBytes == 0L) return "empty (still being written?)"   // unknown size is -1, not 0
    if (kind == FileKind.IMAGE || kind == FileKind.SCREENSHOT) {
      // Icons, stickers and thumbnails: too small to be a photo someone searches for.
      if (sizeBytes in 1 until MIN_IMAGE_BYTES) return "tiny image (under 6 KB)"
      if (width != null && height != null && width > 0 && height > 0 && minOf(width, height) < MIN_IMAGE_SIDE) return "tiny image (under 96 px)"
    }
    return scopeSkipReason(relativePath, kind, scope)
  }
}
