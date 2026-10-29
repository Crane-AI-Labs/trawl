package com.craneai.findr.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.io.File

/** What happened when we tried to hand a file to another app. */
enum class OpenResult { OPENED, COPIED, NO_APP, SAMPLE }

/**
 * Opening and sharing go through the file's content URI with a read grant, so no storage permission leaks to other apps.
 *
 * Copy is different: a MediaStore content URI set on the clipboard is not reliably grantable to the app that pastes it
 * (found on Android 16, where a paste into another app came back empty). [clipboardUri] hands out a URI from Findr's
 * own FileProvider for the file's real path instead, falling back to the MediaStore URI only when that path can't be
 * resolved or isn't under shared storage (an SD card, say). The quick search panel's own Copy ([com.craneai.findr.quick.QuickActions])
 * uses the same function, so the two Copy buttons in the app behave alike.
 */
object FileActions {
  /** The provider declared in the manifest for Copy; not exported, and scoped to shared storage only (res/xml/file_paths.xml). */
  internal fun authority(context: Context) = "${context.packageName}.quick.files"

  private fun isSample(file: IndexedFile) = file.uri.startsWith("fake://")

  /** The extra video players read for where to start: an Int, in ms (MX Player's documented name, also read by VLC, mpv-android and Just Player). */
  internal const val EXTRA_POSITION = "position"

  /** The player starts this far before the moment, so the first word or the start of the scene isn't clipped. */
  internal const val OPEN_LEAD_MS = 1_000L

  /**
   * The start position to pass for a result at [atMs]: [OPEN_LEAD_MS] before it (not before 0), only for a video or recording, and only
   * when that is past the start and fits the Int the extra carries. The badge keeps the moment's own time; the lead is only where the
   * player begins. Android has no standard start position for ACTION_VIEW, so a player that doesn't read the extra (the system
   * Gallery, Google Photos) ignores it and plays from 0; the badge's time is then the only cue. Null: pass nothing.
   */
  internal fun startPosition(kind: FileKind, atMs: Long?): Int? {
    if (kind != FileKind.VIDEO && kind != FileKind.AUDIO) return null
    val from = (atMs ?: return null) - OPEN_LEAD_MS
    return if (from > 0 && from <= Int.MAX_VALUE) from.toInt() else null
  }

  /** [atMs] is the moment of a timed result (a picture match in a video, a spoken phrase); see [startPosition]. */
  fun open(context: Context, file: IndexedFile, atMs: Long? = null): OpenResult {
    if (isSample(file)) return OpenResult.SAMPLE
    val uri = file.uri.toUri()
    val intent = Intent(Intent.ACTION_VIEW)
      .setDataAndType(uri, file.mimeType ?: "*/*")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startPosition(file.kind, atMs)?.let { intent.putExtra(EXTRA_POSITION, it) }
    return try {
      context.startActivity(intent)
      OpenResult.OPENED
    } catch (_: ActivityNotFoundException) {
      OpenResult.NO_APP
    } catch (_: SecurityException) {
      OpenResult.NO_APP
    }
  }

  fun share(context: Context, file: IndexedFile): OpenResult {
    if (isSample(file)) return OpenResult.SAMPLE
    val uri = file.uri.toUri()
    val send = Intent(Intent.ACTION_SEND)
      .setType(file.mimeType ?: "*/*")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    return try {
      context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
      OpenResult.OPENED
    } catch (_: ActivityNotFoundException) {
      OpenResult.NO_APP
    } catch (_: SecurityException) {
      OpenResult.NO_APP
    }
  }

  /**
   * Several files in one share sheet. One file is the ordinary [share]; more are ACTION_SEND_MULTIPLE with every URI in the stream
   * list and in the ClipData (which is what carries the read grants), typed by [commonMimeType] so the sheet offers apps that can take
   * all of them. The chooser is opened, never sent: the person picks the app.
   */
  fun shareAll(context: Context, files: List<IndexedFile>): OpenResult {
    if (files.isEmpty()) return OpenResult.NO_APP
    if (files.size == 1) return share(context, files.single())
    if (files.any(::isSample)) return OpenResult.SAMPLE
    val uris = ArrayList(files.map { it.uri.toUri() })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
      .setType(commonMimeType(files.map { it.mimeType }))
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(files.first().name, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
    return try {
      context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
      OpenResult.OPENED
    } catch (_: ActivityNotFoundException) {
      OpenResult.NO_APP
    } catch (_: SecurityException) {
      OpenResult.NO_APP
    }
  }

  /** Several files on the clipboard as URIs (see [copy]); a paste into WhatsApp or Messages attaches all of them. */
  fun copyAll(context: Context, files: List<IndexedFile>): OpenResult {
    if (files.isEmpty()) return OpenResult.NO_APP
    if (files.size == 1) return copy(context, files.single())
    if (files.any(::isSample)) return OpenResult.SAMPLE
    return try {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clip = ClipData.newUri(context.contentResolver, files.first().name, clipboardUri(context, files.first()))
      files.drop(1).forEach { clip.addItem(context.contentResolver, ClipData.Item(clipboardUri(context, it))) }
      clipboard.setPrimaryClip(clip)
      OpenResult.COPIED
    } catch (_: SecurityException) {
      OpenResult.NO_APP
    } catch (_: RuntimeException) {
      OpenResult.NO_APP
    }
  }

  /**
   * The one MIME type that describes a whole selection for a share sheet: the type itself when they all agree ("image/jpeg"), the
   * family when only the top-level type agrees ("image/&#42;"), else any ("&#42;/&#42;"). A file with no known type counts as unknown.
   */
  fun commonMimeType(types: List<String?>): String {
    val known = types.map { it?.trim()?.lowercase()?.takeIf { t -> t.contains('/') && !t.endsWith("/") } }
    if (known.isEmpty() || known.any { it == null }) return "*/*"
    val all = known.filterNotNull()
    if (all.distinct().size == 1) return all.first()
    val majors = all.map { it.substringBefore('/') }.distinct()
    return if (majors.size == 1) "${majors.first()}/*" else "*/*"
  }

  /**
   * Puts the file on the clipboard as a content URI (not its bytes), so a paste into WhatsApp or Messages attaches the
   * file itself. Routed through [clipboardUri] (Findr's own FileProvider), not the file's own content URI: the clipboard
   * service does not reliably extend a MediaStore URI's read grant to the app that pastes it.
   */
  fun copy(context: Context, file: IndexedFile): OpenResult {
    if (isSample(file)) return OpenResult.SAMPLE
    return try {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, clipboardUri(context, file)))
      OpenResult.COPIED
    } catch (_: SecurityException) {
      OpenResult.NO_APP
    } catch (_: RuntimeException) {
      OpenResult.NO_APP
    }
  }

  /** Opens the folder the file lives in, in the phone's Files app. */
  fun showInFolder(context: Context, file: IndexedFile): OpenResult {
    if (isSample(file)) return OpenResult.SAMPLE
    val dir = file.relativePath.trim('/')
    val folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$dir")
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
    return try {
      context.startActivity(intent)
      OpenResult.OPENED
    } catch (_: ActivityNotFoundException) {
      OpenResult.NO_APP
    } catch (_: SecurityException) {
      OpenResult.NO_APP
    }
  }

  /**
   * A content URI another app can actually read after a clipboard paste: Findr's own FileProvider wrapping the file's
   * real path on shared storage. Falls back to the file's own content URI when that path can't be resolved (the
   * MediaStore row is gone) or isn't under shared storage (an SD card, say), which is no worse than before this fix.
   */
  internal fun clipboardUri(context: Context, file: IndexedFile): Uri {
    val path = pathOnDisk(context, file)
    if (path != null) {
      val f = File(path)
      if (f.canRead()) {
        try {
          return FileProvider.getUriForFile(context, authority(context), f)
        } catch (_: IllegalArgumentException) {
          // Not under shared storage (an SD card, say): fall through to the MediaStore URI.
        }
      }
    }
    return file.uri.toUri()
  }

  /** MediaStore knows the real path; the index only knows the folder and name, which is the same thing on primary storage. */
  private fun pathOnDisk(context: Context, file: IndexedFile): String? {
    val fromStore = runCatching {
      @Suppress("DEPRECATION")
      context.contentResolver.query(file.uri.toUri(), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      }
    }.getOrNull()
    if (!fromStore.isNullOrEmpty()) return fromStore
    return pathFromIndex(Environment.getExternalStorageDirectory().path, file.relativePath, file.name)
  }

  /** `relativePath` is the folder ("Pictures/FindrTest/") in the index and may carry the name in sample data; accept both. */
  internal fun pathFromIndex(root: String, relativePath: String, name: String): String {
    val folder = relativePath.trim('/').let { if (it == name || it.endsWith("/$name")) it.removeSuffix(name).trim('/') else it }
    return listOf(root.trimEnd('/'), folder, name).filter { it.isNotEmpty() }.joinToString("/")
  }
}
