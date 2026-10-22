package com.craneai.findr.quick

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.common.FileActions

/** What an action did, so the panel can say so in plain words. */
internal enum class ActionResult { DONE, NO_APP, FAILED }

/**
 * Share, Copy and Open for the panel, called from the shared `FileRow` and `FileActionSheet`. They work on `IndexedFile` alone.
 * Copy delegates to [FileActions.clipboardUri] (its own FileProvider URI, not the raw MediaStore one), so the panel's
 * Copy and the main screen's Copy behave alike.
 */
internal object QuickActions {
  /** The system share sheet, so Direct Share can list recent chats. The file travels as a one-file read grant. */
  fun share(context: Context, file: IndexedFile): ActionResult {
    if (file.uri.startsWith("fake://")) return ActionResult.FAILED
    val uri = file.uri.toUri()
    val send = Intent(Intent.ACTION_SEND)
      .setType(file.mimeType ?: "*/*")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    return start(context, Intent.createChooser(send, file.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
  }

  fun open(context: Context, file: IndexedFile): ActionResult {
    if (file.uri.startsWith("fake://")) return ActionResult.FAILED
    val view = Intent(Intent.ACTION_VIEW)
      .setDataAndType(file.uri.toUri(), file.mimeType ?: "*/*")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return start(context, view)
  }

  fun copy(context: Context, file: IndexedFile): ActionResult {
    if (file.uri.startsWith("fake://")) return ActionResult.FAILED
    return try {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, file.name, FileActions.clipboardUri(context, file)))
      ActionResult.DONE
    } catch (_: Exception) {
      ActionResult.FAILED
    }
  }

  private fun start(context: Context, intent: Intent): ActionResult = try {
    context.startActivity(intent)
    ActionResult.DONE
  } catch (_: ActivityNotFoundException) {
    ActionResult.NO_APP
  } catch (_: SecurityException) {
    ActionResult.NO_APP
  }
}
