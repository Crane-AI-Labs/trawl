package com.craneai.findr.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import com.craneai.findr.core.KindFilter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FindrFBench"

/**
 * Formats sweep (the research notes): runs the app's real search for every query in files/formats/<queries>.tsv
 * (id TAB query TAB type) and writes the top hits of each (file name, kind, why it matched) to files/results/<tag>.json,
 * so the sweep can say, per file, whether its known-content query finds it. Debug builds only; nothing here ships.
 *
 *   am start -n com.craneai.findr.formats/com.craneai.findr.bench.FormatsBenchActivity --es tag T [--es queries queries.tsv] [--ei top 20]
 *
 * Announces FINDR_FBENCH_DONE <path> in logcat when finished.
 */
class FormatsBenchActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(TextView(this).apply { text = "formats bench"; textSize = 14f; setPadding(24, 96, 24, 24) })
    val tag = intent.getStringExtra("tag") ?: "formats"
    val queries = intent.getStringExtra("queries") ?: "queries.tsv"
    val top = intent.getIntExtra("top", 20)
    scope.launch {
      val out = File(filesDir, "results/$tag.json").apply { parentFile!!.mkdirs() }
      val rows = JSONArray()
      try {
        val engine = (application as com.craneai.findr.FindrApp).engine as com.craneai.findr.engine.FindrEngine
        val lines = File(filesDir, "formats/$queries").readLines().filter { it.isNotBlank() }
        for (line in lines) {
          val f = line.split('\t')
          if (f.size < 2) continue
          val t0 = System.nanoTime()
          val res = try { engine.search(f[1], KindFilter.ALL, top) } catch (t: Throwable) { null }
          val hits = JSONArray()
          res?.hits?.forEach { h ->
            hits.put(JSONObject().put("name", h.file.name).put("kind", h.file.kind.name).put("why", h.reason.name)
              .put("rel", h.file.relativePath).put("snippet", h.snippet ?: ""))
          }
          rows.put(JSONObject().put("id", f[0]).put("q", f[1]).put("type", f.getOrElse(2) { "" })
            .put("ms", (System.nanoTime() - t0) / 1_000_000).put("hits", hits).put("error", if (res == null) "search threw" else ""))
        }
        out.writeText(JSONObject().put("tag", tag).put("rows", rows).toString(1))
      } catch (t: Throwable) {
        Log.e(TAG, "formats bench failed", t)
        out.writeText(JSONObject().put("tag", tag).put("error", t.toString()).put("rows", rows).toString(1))
      }
      Log.i(TAG, "FINDR_FBENCH_DONE ${out.absolutePath}")
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
  }
}
