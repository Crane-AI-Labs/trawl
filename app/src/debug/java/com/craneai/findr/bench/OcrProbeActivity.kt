package com.craneai.findr.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.craneai.findr.engine.FileProcessor
import com.craneai.findr.engine.frameSignatureOf
import com.craneai.findr.extract.MlKitOcrEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FindrOcrProbe"

/**
 * OCR probe (the video on-screen text work): reads every picture listed in files/ocrprobe/list.txt (id TAB file name inside
 * files/ocrprobe/img) with the app's real ML Kit engine and writes, per picture, each line's text, confidence, angle, box and language,
 * with the engine's own time for the call ("ms") and the frame signature's ("sg"). [side] is the long side the picture is read at: 2048 is the photo/screenshot path, 768 is
 * what a video frame is (the sampler's size). One JSON object per line in files/results/<tag>.jsonl; a restart skips the ids already
 * there. Debug builds only; nothing here ships, and it keeps no names or content beyond the files the caller pushed.
 *
 *   am start -n <pkg>/com.craneai.findr.bench.OcrProbeActivity --es tag T [--ei side 2048] [--ei from 0] [--ei count 100000]
 *
 * Announces FINDR_OCRPROBE_DONE <path> in logcat when finished.
 */
class OcrProbeActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val status = TextView(this).apply { text = "ocr probe"; textSize = 14f; setPadding(24, 96, 24, 24) }
    setContentView(status)
    val tag = intent.getStringExtra("tag") ?: "ocrprobe"
    val side = intent.getIntExtra("side", 2048)
    val from = intent.getIntExtra("from", 0)
    val count = intent.getIntExtra("count", Int.MAX_VALUE)
    scope.launch {
      val out = File(filesDir, "results/$tag.jsonl").apply { parentFile!!.mkdirs() }
      val done = if (out.exists()) out.readLines().mapNotNull { runCatching { JSONObject(it).getString("id") }.getOrNull() }.toSet() else emptySet()
      val ocr = MlKitOcrEngine()
      try {
        val list = File(filesDir, "ocrprobe/list.txt").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        // The first call loads the recogniser; it is not a timing.
        list.firstOrNull()?.let { load(File(filesDir, "ocrprobe/img/${it[1]}"), side)?.let { b -> ocr.readLines(b); b.recycle() } }
        var n = 0
        for ((index, row) in list.withIndex()) {
          if (index < from || index >= from + count) continue
          val id = row[0]
          if (id in done) continue
          val rec = JSONObject().put("id", id)
          try {
            val bitmap = load(File(filesDir, "ocrprobe/img/${row[1]}"), side)
            if (bitmap == null) rec.put("err", "decode") else {
              rec.put("w", bitmap.width).put("h", bitmap.height)
              // what the video indexer adds per frame besides the read: the frame signature (its own call, as FileProcessor makes it)
              val ts = System.nanoTime()
              frameSignatureOf(bitmap)
              rec.put("sg", (System.nanoTime() - ts) / 1_000_000.0)
              val t0 = System.nanoTime()
              val lines = ocr.readLines(bitmap)
              rec.put("ms", (System.nanoTime() - t0) / 1_000_000.0)
              val arr = JSONArray()
              for (l in lines) arr.put(JSONObject().put("t", l.text).put("c", l.confidence.toDouble()).put("a", l.angle.toDouble())
                .put("hf", l.heightFraction.toDouble()).put("wf", l.widthFraction.toDouble()).put("l", l.language ?: "")
                .put("m", l.minWordConfidence.toDouble()).put("n", l.words))
              rec.put("lines", arr)
              bitmap.recycle()
            }
          } catch (t: Throwable) {
            rec.put("err", t.toString())
          }
          out.appendText(rec.toString() + "\n")
          if (++n % 50 == 0) { runOnUiThread { status.text = "ocr probe $tag: $index / ${list.size}" }; Log.i(TAG, "progress $index / ${list.size}") }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "ocr probe failed", t)
        File(filesDir, "results/$tag.error").writeText(t.toString())
      } finally {
        ocr.close()
      }
      Log.i(TAG, "FINDR_OCRPROBE_DONE ${out.absolutePath}")
    }
  }

  /** The picture as the app reads it: decoded at a power-of-two reduction, then, for a video-frame probe (768), scaled to that long side. */
  private fun load(file: File, side: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    val sample = FileProcessor.sampleSize(bounds.outWidth, bounds.outHeight, maxOf(side, 768))
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val long = maxOf(decoded.width, decoded.height)
    if (side >= 2048 || long <= side) return decoded
    val f = side.toDouble() / long
    return Bitmap.createScaledBitmap(decoded, Math.round(decoded.width * f).toInt().coerceAtLeast(1), Math.round(decoded.height * f).toInt().coerceAtLeast(1), true)
      .also { if (it !== decoded) decoded.recycle() }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
  }
}
