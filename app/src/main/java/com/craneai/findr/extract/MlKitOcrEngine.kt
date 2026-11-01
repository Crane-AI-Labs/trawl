package com.craneai.findr.extract

import android.graphics.Bitmap
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.OcrEngine
import com.craneai.findr.core.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "FindrOcr"

/**
 * On-device text recognition with ML Kit's bundled Latin model.
 *
 * Privacy: the bundled model ships inside the APK and runs in-process, and Findr declares no INTERNET permission,
 * so no recognition data can leave the phone (the unbundled Play-services variant would be a different story and is
 * deliberately not used).
 *
 * One recognizer is created lazily and reused for every call; [close] releases it. Huge bitmaps are downscaled to a
 * 2048 px long side first: bigger images cost time and memory without reading any better.
 */
class MlKitOcrEngine : OcrEngine {
  private var recognizer: TextRecognizer? = null
  private var closed = false

  /** Lines joined with `\n`, blocks in the order ML Kit reports them (its reading order). Empty on any failure. */
  override suspend fun read(bitmap: Bitmap): String =
    recognizeText(bitmap)?.first?.textBlocks?.joinToString("\n") { block -> block.lines.joinToString("\n") { it.text } }.orEmpty()

  /** The same lines (same order), each with ML Kit's confidence, angle, box and language for it. Empty on any failure. */
  override suspend fun readLines(bitmap: Bitmap): List<OcrLine> {
    val (text, size) = recognizeText(bitmap) ?: return emptyList()
    val (w, h) = size
    return text.textBlocks.flatMap { it.lines }.map { line ->
      val box = line.boundingBox
      val elements = line.elements
      OcrLine(
        text = line.text,
        confidence = line.confidence,
        angle = line.angle,
        heightFraction = if (box == null || h <= 0) 0f else box.height().toFloat() / h,
        widthFraction = if (box == null || w <= 0) 0f else box.width().toFloat() / w,
        language = line.recognizedLanguage,
        minWordConfidence = if (elements.isEmpty()) OcrLine.UNKNOWN else elements.minOf { it.confidence },
        words = elements.size,
      )
    }
  }

  /** ML Kit's reading of [bitmap] (scaled to at most [MAX_LONG_SIDE]) and the size it was read at; null on any failure. */
  private suspend fun recognizeText(bitmap: Bitmap): Pair<Text, Pair<Int, Int>>? {
    if (bitmap.isRecycled || bitmap.width < MIN_SIDE || bitmap.height < MIN_SIDE) return null
    val recognizer = recognizer() ?: return null
    var scaled: Bitmap? = null
    try {
      val (w, h) = scaledSize(bitmap.width, bitmap.height, MAX_LONG_SIDE)
      val input = if (w == bitmap.width && h == bitmap.height) bitmap
      else Bitmap.createScaledBitmap(bitmap, w, h, true).also { scaled = it }
      return recognize(recognizer, InputImage.fromBitmap(input, 0)) to (w to h)
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "OCR failed (${Log.brief(t)})")
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.OCR, t)
      return null
    } finally {
      scaled?.recycle()
    }
  }

  @Synchronized
  fun close() {
    closed = true
    recognizer?.close()
    recognizer = null
  }

  @Synchronized
  private fun recognizer(): TextRecognizer? {
    if (closed) return null
    return recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
  }

  private suspend fun recognize(recognizer: TextRecognizer, image: InputImage): Text =
    suspendCancellableCoroutine { cont ->
      recognizer.process(image)
        .addOnSuccessListener { cont.resume(it) }
        .addOnFailureListener { cont.resumeWithException(it) }
    }

  companion object {
    private const val MAX_LONG_SIDE = 2048

    /** ML Kit reads nothing useful below about this. */
    private const val MIN_SIDE = 32

    /** `w` x `h` shrunk so the long side is at most `maxLongSide`; unchanged when already small enough. */
    internal fun scaledSize(w: Int, h: Int, maxLongSide: Int): Pair<Int, Int> {
      val long = maxOf(w, h)
      if (long <= maxLongSide) return w to h
      val f = maxLongSide.toDouble() / long
      return Math.round(w * f).toInt().coerceAtLeast(1) to Math.round(h * f).toInt().coerceAtLeast(1)
    }
  }
}
