package com.craneai.findr.bench

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.craneai.findr.engine.PrivacyPass
import com.craneai.findr.engine.PrivateScorer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FindrPBench"

/**
 * What does the "looks private" score cost on this phone, and does it agree with the host? Scores every picture in
 * files/bench/<dir> (default `vimg`) with the real PrivateScorer, splits decode, resize and model time per picture, and
 * records memory and the scores themselves (compared with the host's onnxruntime on the same files by the caller).
 *
 *   am start -n com.craneai.findr.<suffix>/com.craneai.findr.bench.PrivacyBenchActivity --es tag T [--es dir vimg] [--ei reps 2]
 *
 * Result: files/results/<tag>.json, announced by FINDR_PBENCH_DONE in logcat.
 */
class PrivacyBenchActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(TextView(this).apply { text = "privacy bench"; textSize = 14f; setPadding(24, 96, 24, 24) })
    val tag = intent.getStringExtra("tag") ?: "pbench"
    val dir = intent.getStringExtra("dir") ?: "vimg"
    val reps = intent.getIntExtra("reps", 2)
    val exact = intent.getBooleanExtra("exact", false)
    scope.launch {
      val out = File(filesDir, "results/$tag.json").apply { parentFile!!.mkdirs() }
      val engine = (application as? com.craneai.findr.FindrApp)?.engine as? com.craneai.findr.engine.FindrEngine
      val pausedHere = engine != null && !engine.paused
      if (pausedHere) {
        engine!!.pauseIndexing()
        val until = System.currentTimeMillis() + 60_000
        while (engine.workerState.running && System.currentTimeMillis() < until) delay(500)
      }
      val result = try { run(tag, File(filesDir, "bench/$dir"), reps, exact) } catch (t: Throwable) {
        Log.e(TAG, "pbench failed", t); JSONObject().put("tag", tag).put("error", t.toString())
      }
      if (pausedHere) engine!!.resumeIndexing()
      out.writeText(result.toString(1))
      Log.i(TAG, "FINDR_PBENCH_DONE ${out.absolutePath}")
    }
  }

  private fun pssMb(): Double {
    val m = Debug.MemoryInfo(); Debug.getMemoryInfo(m); return m.totalPss / 1024.0
  }

  private fun run(tag: String, dir: File, reps: Int, exact: Boolean): JSONObject {
    val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }
    val scorer = PrivateScorer(applicationContext)
    check(scorer.available()) { "the privacy model is not in this build" }
    val pssBefore = pssMb()
    val rows = JSONArray()
    var loadMs = 0L
    val decodes = ArrayList<Long>(); val preps = ArrayList<Long>(); val models = ArrayList<Long>(); val totals = ArrayList<Long>()
    var pssPeak = pssBefore
    val wall0 = SystemClock.elapsedRealtime()
    for (rep in 0 until reps) {
      for ((i, f) in files.withIndex()) {
        val t0 = System.nanoTime()
        val bitmap = PrivacyPass.decode(applicationContext, Uri.fromFile(f)) ?: continue
        val t1 = System.nanoTime()
        val first = rep == 0 && i == 0
        val p = scorer.score(bitmap, exact)
        val t2 = System.nanoTime()
        bitmap.recycle()
        if (first) loadMs = (t2 - t1) / 1_000_000 - scorer.lastPrepMs - scorer.lastModelMs
        if (!(rep == 0 && i == 0)) {
          decodes += (t1 - t0) / 1_000_000; preps += scorer.lastPrepMs; models += scorer.lastModelMs; totals += (t2 - t0) / 1_000_000
        }
        if (rep == 0) rows.put(JSONObject().put("name", f.name).put("p", p.toDouble()))
        pssPeak = maxOf(pssPeak, pssMb())
      }
    }
    val wallMs = SystemClock.elapsedRealtime() - wall0
    val pssLoaded = pssMb()
    scorer.release()
    System.gc()
    val pssAfter = pssMb()
    fun med(l: List<Long>) = l.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2].toDouble() }
    fun mean(l: List<Long>) = if (l.isEmpty()) 0.0 else l.average()
    return JSONObject()
      .put("tag", tag).put("exact", exact).put("images", files.size).put("reps", reps).put("sessionLoadMs", loadMs)
      .put("decodeMsMean", mean(decodes)).put("decodeMsMedian", med(decodes))
      .put("prepMsMean", mean(preps)).put("prepMsMedian", med(preps))
      .put("modelMsMean", mean(models)).put("modelMsMedian", med(models))
      .put("totalMsMean", mean(totals)).put("totalMsMedian", med(totals))
      .put("wallMs", wallMs)
      .put("pssMbBefore", pssBefore).put("pssMbPeak", pssPeak).put("pssMbLoaded", pssLoaded).put("pssMbAfterRelease", pssAfter)
      .put("scores", rows)
  }
}
