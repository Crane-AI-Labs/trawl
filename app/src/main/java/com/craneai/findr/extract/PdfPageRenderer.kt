package com.craneai.findr.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.craneai.findr.core.FindrLog as Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FindrExtract"

/**
 * Renders PDF pages to bitmaps so scanned pages (the ones [DocumentTextExtractor] returns empty) can be OCRed.
 * Uses the platform `PdfRenderer`, which needs a seekable descriptor, so `file:` and most `content:` URIs work.
 *
 * Open one per PDF, render the pages needed, then [close]. `PdfRenderer` allows one open page at a time, so calls are
 * serialised. The caller owns the returned bitmaps and should recycle them after OCR.
 */
class PdfPageRenderer private constructor(
  private val descriptor: ParcelFileDescriptor,
  private val renderer: PdfRenderer,
) : AutoCloseable {

  val pageCount: Int = renderer.pageCount

  /** Page `index` (0-based) on a white background, `targetWidth` px wide. Null if the page can't be rendered. */
  suspend fun render(index: Int, targetWidth: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
    try {
      synchronized(this@PdfPageRenderer) {
        if (index !in 0 until renderer.pageCount) return@synchronized null
        renderer.openPage(index).use { page ->
          val (w, h) = targetSize(page.width, page.height, targetWidth)
          val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(Color.WHITE)   // pages render onto transparent; OCR wants dark-on-white
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          bitmap
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "PDF page $index not rendered (${Log.brief(t)})")
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.DECODER, t)
      null
    }
  }

  @Synchronized
  override fun close() {
    try {
      renderer.close()
    } catch (_: Exception) {
    }
    try {
      descriptor.close()
    } catch (_: Exception) {
    }
  }

  companion object {
    /** Opens a PDF for rendering; null when it is corrupt, password-protected or unreadable. */
    suspend fun open(context: Context, uri: Uri): PdfPageRenderer? = withContext(Dispatchers.IO) {
      var descriptor: ParcelFileDescriptor? = null
      try {
        descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
        PdfPageRenderer(descriptor, PdfRenderer(descriptor))
      } catch (e: CancellationException) {
        descriptor?.close()
        throw e
      } catch (t: Throwable) {
        Log.w(TAG, "PDF not opened for rendering (${Log.brief(t)})")
        com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.DECODER, t)
        descriptor?.close()
        null
      }
    }

    /**
     * Bitmap size for a page of `pageW` x `pageH` points at `targetWidth` px wide, keeping the aspect ratio. A very
     * tall page (a long receipt) is capped at `maxHeight` px, shrinking the width with it.
     */
    internal fun targetSize(pageW: Int, pageH: Int, targetWidth: Int, maxHeight: Int = 4096): Pair<Int, Int> {
      val pw = pageW.coerceAtLeast(1)
      val ph = pageH.coerceAtLeast(1)
      var w = targetWidth.coerceAtLeast(1).toDouble()
      var h = w * ph / pw
      if (h > maxHeight) {
        w *= maxHeight / h
        h = maxHeight.toDouble()
      }
      return Math.round(w).toInt().coerceAtLeast(1) to Math.round(h).toInt().coerceAtLeast(1)
    }
  }
}
