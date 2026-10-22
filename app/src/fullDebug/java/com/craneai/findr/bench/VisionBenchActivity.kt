package com.craneai.findr.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FindrVBench"

/**
 * Where does an image's ~4 s go (the research notes)? Image embedding the app's way (text CPU, vision
 * GPU, 70 vision tokens), with each call's CPU time split by thread from /proc/self/task/<tid>/schedstat (ns on-CPU
 * and ns runnable-but-waiting), so the GPU wait, the text tower on CPU and the runtime's own decode can be told apart.
 *
 *   am start -n com.craneai.findr/.bench.VisionBenchActivity --es tag T [--es model …] [--es dir vimg] [--es n 10]
 *     [--es variants j768,p768,orig,j512,n70,j768@140] [--es threads 4] [--es act FLOAT16] [--es vbackend gpu|cpu]
 *     [--es rep 1] [--es vecs 1] [--es textprobe 5] [--es loglevel VERBOSE] [--es engines 2]
 *
 * Variants: j<side>[q<quality>] JPEG at that longest side, p<side> PNG, orig the file's own bytes, n70 a JPEG resized
 * to the 16-px patch grid that 70 tokens allow (≤630 patches, sides in multiples of 48 px); "@<tokens>" per call.
 * Every variant's vector is compared (cosine) with the first variant's, in the same process.
 * Result: files/results/<tag>.json, announced by FINDR_VBENCH_DONE in logcat (scripts/vision-bench.sh pulls it).
 */
class VisionBenchActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var log: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    log = TextView(this).apply { textSize = 11f; setPadding(24, 96, 24, 24) }
    setContentView(ScrollView(this).apply { addView(log) })
    val tag = intent.getStringExtra("tag") ?: "vbench"
    scope.launch {
      val out = File(filesDir, "results/$tag.json").apply { parentFile!!.mkdirs() }
      // Same as BenchActivity: the app's own indexer would compete for the CPU and the GPU, so pause it for the run.
      val engine = (application as? com.craneai.findr.FindrApp)?.engine as? com.craneai.findr.engine.FindrEngine
      val pausedHere = engine != null && !engine.paused
      if (pausedHere) {
        engine!!.pauseIndexing()
        val until = System.currentTimeMillis() + 120_000
        while (engine.workerState.running && System.currentTimeMillis() < until) kotlinx.coroutines.delay(500)
        say("indexing paused" + if (engine.workerState.running) " (worker still running after 120 s)" else "")
      }
      val result = try { run(tag) } catch (t: Throwable) {
        Log.e(TAG, "vbench failed", t); JSONObject().put("tag", tag).put("error", t.toString())
      }
      if (pausedHere) { engine!!.resumeIndexing(); kotlinx.coroutines.delay(1_000) }
      out.writeText(result.toString(1))
      say("done → ${out.name}")
      Log.i(TAG, "FINDR_VBENCH_DONE ${out.absolutePath}")
    }
  }

  override fun onDestroy() { scope.cancel(); super.onDestroy() }

  private fun s(key: String) = intent.getStringExtra(key)

  private class Variant(val spec: String) {
    val tokens: Int? = spec.substringAfter('@', "").toIntOrNull()
    val body = spec.substringBefore('@')
  }

  /**
   * fp=<dir>: FileProcessor.process() on every file of bench/<dir> (videos and images), the way the worker calls it but
   * without the worker's queue, using the app's own Embedder (indexing is paused). Per file: wall time, the processor's
   * detail line (video: sample / embed / ocr), and every vector for parity between builds.
   */
  private fun fileProcessorRun(tag: String, dir: String): JSONObject {
    val app = application as com.craneai.findr.FindrApp
    val engine = app.engine as com.craneai.findr.engine.FindrEngine
    val speech = s("speech") == "1"   // the soundtrack through the speech model too, concurrently with the frames (as in the app)
    if (s("vcpu") == "1") {   // vision on the CPU instead of the GPU (backend comparison on a new chip)
      kotlinx.coroutines.runBlocking { engine.embedder.release() }
      (engine.embedder as com.craneai.findr.engine.LiteRtEmbedder).visionOnGpu = false
    }
    val processor = com.craneai.findr.engine.FileProcessor(this, engine.embedder, asr = if (speech) engine.asr else null)
    val files = File(filesDir, "bench/$dir").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(s("n")?.toIntOrNull() ?: 1000).orEmpty()
    val out = JSONObject().put("tag", tag).put("fp", dir).put("speech", s("speech") == "1").put("cpu_cap_start", cpuCaps())
    val sampler = MetricsSampler(this).also { it.start(scope, intervalMs = 1000) }
    val rows = JSONArray()
    var total = 0.0
    // Load the vision engine first (as the worker has it loaded), so the first file doesn't pay for it.
    val warm = File(filesDir, "bench/vimg").listFiles()?.firstOrNull { it.isFile }
    if (warm != null) kotlinx.coroutines.runBlocking { engine.embedder.image(warm.readBytes()) }
    out.put("t0_epoch_ms", System.currentTimeMillis())
    for (f in files) {
      val ext = f.extension.lowercase()
      val video = ext in setOf("mp4", "mkv", "webm", "3gp", "mov")
      val kind = when {
        video -> com.craneai.findr.core.FileKind.VIDEO
        ext in setOf("wav", "mp3", "m4a", "ogg", "opus", "aac", "flac") -> com.craneai.findr.core.FileKind.AUDIO
        ext in setOf("jpg", "jpeg", "png", "webp", "heic") -> com.craneai.findr.core.FileKind.IMAGE
        ext == "pdf" -> com.craneai.findr.core.FileKind.PDF
        else -> com.craneai.findr.core.FileKind.DOCUMENT
      }
      val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
      val duration = if (kind == com.craneai.findr.core.FileKind.VIDEO || kind == com.craneai.findr.core.FileKind.AUDIO) android.media.MediaMetadataRetriever().run {
        try { setDataSource(f.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
      } else null
      val file = com.craneai.findr.core.IndexedFile(0, android.net.Uri.fromFile(f).toString(), "bench/$dir/${f.name}", f.name,
        kind, mime, f.length(), f.lastModified(), durationMs = duration)
      val t = SystemClock.elapsedRealtimeNanos()
      val r = kotlinx.coroutines.runBlocking { processor.process(file, withSpeech = speech && duration != null) }
      val wall = ms(t)
      total += wall
      val vecs = JSONObject()
      r.pieces.filter { it.vector != null }.forEach { vecs.put("${it.kind}|${it.locStart}", b64(it.vector!!)) }
      rows.put(JSONObject().put("file", f.name).put("kind", kind.name).put("ms", wall).put("duration_ms", duration ?: JSONObject.NULL).put("detail", r.detail ?: "").put("vecs", vecs))
      say("${f.name}: ${"%.1f".format(wall / 1000)} s ${r.detail ?: ""}")
    }
    out.put("t1_epoch_ms", System.currentTimeMillis())
    val m = sampler.stop()
    say("fp $dir: ${files.size} files in ${"%.1f".format(total / 1000)} s")
    return out.put("files", rows).put("total_ms", total).put("peak_pss_mb", m.peakPssMb).put("min_avail_mem_mb", m.minAvailMemMb).put("cpu_cap_end", cpuCaps())
      .put("vision_gpu", (engine.embedder as com.craneai.findr.engine.LiteRtEmbedder).visionOnGpu).put("max_thermal_status", m.maxThermalStatus)
      .put("max_thermal_headroom", m.maxThermalHeadroom.toDouble().takeIf { !it.isNaN() } ?: JSONObject.NULL)
  }

  private fun run(tag: String): JSONObject {
    s("fp")?.let { return fileProcessorRun(tag, it) }
    val model = s("model") ?: "embeddinggemma-2-740m.litertlm"
    val vbackend = (s("vbackend") ?: "gpu").lowercase()
    val threads = s("threads")?.toIntOrNull()
    val vthreads = s("vthreads")?.toIntOrNull()
    val act = s("act")?.uppercase()?.let { ActivationDataType.valueOf(it) }
    val dir = s("dir") ?: "vimg"
    val n = s("n")?.toIntOrNull() ?: 10
    val variants = (s("variants") ?: "j768").split(',').map { Variant(it.trim()) }
    val rep = s("rep")?.toIntOrNull() ?: 1
    val vecs = s("vecs") == "1"
    val textProbe = s("textprobe")?.toIntOrNull() ?: 5
    val engines = s("engines")?.toIntOrNull() ?: 1
    s("loglevel")?.let { Engine.setNativeMinLogSeverity(LogSeverity.valueOf(it.uppercase())) }
    val maxTokens = (variants.mapNotNull { it.tokens } + (s("vtok")?.toIntOrNull() ?: 70)).max()

    val result = JSONObject().put("tag", tag).put("model", model).put("vbackend", vbackend)
      .put("threads", threads ?: JSONObject.NULL).put("vthreads", vthreads ?: JSONObject.NULL).put("act", act?.name ?: JSONObject.NULL)
      .put("dir", dir).put("variants", JSONArray(variants.map { it.spec })).put("rep", rep).put("engine_vtok", maxTokens)
      .put("engines", engines).put("cpu_cap_start", cpuCaps()).put("cores", Runtime.getRuntime().availableProcessors())
    val sampler = MetricsSampler(this)
    sampler.start(scope, intervalMs = 1000)   // PSS reads cost ~100 ms of CPU each: 250 ms sampling kept a core 30% busy

    fun backend(name: String, t: Int?) = if (name == "gpu") Backend.GPU() else Backend.CPU(threadCount = t)
    fun newEngine() = EmbeddingEngine(
      EmbeddingEngineConfig(
        modelPath = File(filesDir, "models/$model").absolutePath,
        backend = Backend.CPU(threadCount = threads),
        visionBackend = backend(vbackend, vthreads),
        audioBackend = null,
        cacheDir = cacheDir.absolutePath,
        visionTokensPerImage = maxTokens,
        activationDataType = act,
      )
    )
    var t = SystemClock.elapsedRealtimeNanos()
    val engine = newEngine().also { it.initialize() }
    result.put("init_ms", ms(t)).put("pss_after_init_mb", sampler.snapshot().pssMb)
    say("init ${"%.0f".format(result.getDouble("init_ms"))} ms ($model, vision $vbackend, act ${act ?: "default"}, threads ${threads ?: "default"})")

    val files = File(filesDir, "bench/$dir").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(n).orEmpty()
    require(files.isNotEmpty()) { "no images in bench/$dir" }
    fun opts(tokens: Int?) = EmbeddingOptions(normalize = true, visionTokensPerImage = tokens ?: 70)

    engine.use { e ->
      // The first image after init pays any one-off GPU setup (kernel compile, tuning, uploads): measured on its own.
      val first = bytesFor(files.first(), variants.first())
      val p0 = Probe.start()
      t = SystemClock.elapsedRealtimeNanos()
      e.computeEmbedding(listOf(InputData.Image(first)), opts(variants.first().tokens))
      val firstMs = ms(t)
      result.put("first_image_ms", firstMs).put("first_image_cpu", p0.stop().json(1))
      say("first image ${"%.0f".format(firstMs)} ms")

      // The text tower alone at about the size of an image's token run (70 soft tokens + 4 markers: the 128 bucket).
      if (textProbe > 0) {
        val text = "title: none | text: The market in the town centre opens early on Saturday, when traders bring bananas, beans, " +
          "fresh fish from the lake and second-hand clothes from the coast. Buyers bargain hard, and the boda boda riders wait at the"
        // ttoks: ~N tokens of text instead (the sentence repeated; ~4 characters per token), e.g. 250 for the 256 bucket.
        val ttoks = s("ttoks")?.toIntOrNull()
        val probeText = if (ttoks == null) text else ("title: none | text: " + (text.substringAfter("text: ") + " ").repeat(ttoks * 4 / 200 + 1).take(ttoks * 4))
        val times = mutableListOf<Double>()
        var agg = Agg()
        repeat(textProbe) {
          val p = Probe.start()
          t = SystemClock.elapsedRealtimeNanos()
          e.computeEmbedding(listOf(InputData.Text(probeText)), opts(null))
          times += ms(t)
          agg += p.stop()
        }
        result.put("text_probe", stats(times).put("cpu", agg.json(times.size)))
        say("text probe (~${ttoks ?: 75} tokens): ${stats(times)}")
      }

      val perVariant = variants.associate { it.spec to mutableListOf<Double>() }
      val prepMs = variants.associate { it.spec to mutableListOf<Double>() }
      val aggs = variants.associate { it.spec to Agg() }.toMutableMap()
      val cosines = variants.associate { it.spec to mutableListOf<Double>() }
      val images = JSONArray()
      // A second engine (engines=2): two images in flight, so one engine's CPU part can overlap the other's GPU part.
      val e2 = if (engines >= 2) newEngine().also { it.initialize(); say("second engine up, PSS ${"%.0f".format(sampler.snapshot().pssMb)} MB") } else null
      if (e2 != null) result.put("pss_two_engines_mb", sampler.snapshot().pssMb)

      result.put("images_t0_epoch_ms", System.currentTimeMillis())   // to count a concurrent job's work inside this window
      if (e2 == null) {
        for ((i, f) in files.withIndex()) {
          val img = JSONObject().put("file", f.name)
          var base: FloatArray? = null
          for (v in variants) {
            val tp = SystemClock.elapsedRealtimeNanos()
            val bytes = bytesFor(f, v)
            prepMs.getValue(v.spec) += ms(tp)
            var vec: FloatArray? = null
            repeat(rep) {
              val p = Probe.start()
              t = SystemClock.elapsedRealtimeNanos()
              vec = e.computeEmbedding(listOf(InputData.Image(bytes)), opts(v.tokens)).embedding
              val wall = ms(t)
              val c = p.stop()
              perVariant.getValue(v.spec) += wall
              aggs[v.spec] = aggs.getValue(v.spec) + c
              if (i < 3 && it == 0) img.put(v.spec + "_cpu", c.json(1))
            }
            val cv = if (base == null) 1.0 else cos(base!!, vec!!)
            if (base == null) base = vec
            cosines.getValue(v.spec) += cv
            img.put(v.spec, JSONObject().put("bytes", bytes.size).put("cos", cv).apply { if (vecs) put("vec", b64(vec!!)) })
          }
          images.put(img)
          say("${f.name}: " + variants.joinToString(" ") { v -> "${v.spec} ${"%.0f".format(perVariant.getValue(v.spec).last())} ms" })
        }
      } else {
        // Two engines, two threads, each taking every other image (first variant only); wall time per image overall.
        val v = variants.first()
        val inputs = files.map { bytesFor(it, v) }
        val vecA = arrayOfNulls<FloatArray>(inputs.size)
        val p = Probe.start()
        t = SystemClock.elapsedRealtimeNanos()
        val workers = listOf(e, e2).mapIndexed { k, eng ->
          Thread {
            for (j in inputs.indices) if (j % 2 == k) vecA[j] = eng.computeEmbedding(listOf(InputData.Image(inputs[j])), opts(v.tokens)).embedding
          }.also { it.start() }
        }
        workers.forEach { it.join() }
        val wall = ms(t)
        val c = p.stop()
        // Same images again on one engine, for parity and a sequential reference in the same process.
        val t2 = SystemClock.elapsedRealtimeNanos()
        val seq = inputs.map { e.computeEmbedding(listOf(InputData.Image(it)), opts(v.tokens)).embedding }
        val seqWall = ms(t2)
        val cs = inputs.indices.map { cos(seq[it], vecA[it]!!) }
        result.put("two_engines", JSONObject().put("images", inputs.size).put("wall_ms", wall).put("ms_per_image", wall / inputs.size)
          .put("seq_ms_per_image", seqWall / inputs.size).put("min_cos", cs.min()).put("cpu", c.json(inputs.size)))
        say("two engines: ${"%.0f".format(wall / inputs.size)} ms/image vs one engine ${"%.0f".format(seqWall / inputs.size)}; min cos ${"%.6f".format(cs.min())}")
        e2.close()
      }

      result.put("images_t1_epoch_ms", System.currentTimeMillis())
      val summary = JSONObject()
      for (v in variants) {
        val times = perVariant.getValue(v.spec)
        if (times.isEmpty()) continue
        summary.put(v.spec, JSONObject().put("embed", stats(times)).put("prep", stats(prepMs.getValue(v.spec)))
          .put("cos_min", cosines.getValue(v.spec).min()).put("cos_mean", cosines.getValue(v.spec).average())
          .put("cpu", aggs.getValue(v.spec).json(times.size)))
        say("${v.spec}: ${stats(times)}; cos min ${"%.5f".format(cosines.getValue(v.spec).min())}; ${aggs.getValue(v.spec).line(times.size)}")
      }
      result.put("summary", summary).put("images", images)
    }
    val m = sampler.stop()
    result.put("peak_pss_mb", m.peakPssMb).put("min_avail_mem_mb", m.minAvailMemMb).put("cpu_cap_end", cpuCaps())
      .put("max_thermal_status", m.maxThermalStatus).put("max_thermal_headroom", m.maxThermalHeadroom.toDouble().takeIf { !it.isNaN() } ?: JSONObject.NULL)
    return result
  }

  /** The bytes the engine is handed for one image under one variant. */
  private fun bytesFor(f: File, v: Variant): ByteArray {
    val b = v.body
    if (b == "orig") return f.readBytes()
    if (b == "app") {
      // Exactly FileProcessor's photo path: decode for OCR at ≤2048 px (power-of-two sampling only), then the 768 px JPEG.
      val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(f.path, o)
      var sample = 1
      while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 2048) sample *= 2
      val full = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
      val sc = 768f / maxOf(full.width, full.height)
      val scaled = if (sc < 1f) Bitmap.createScaledBitmap(full, (full.width * sc).toInt(), (full.height * sc).toInt(), true) else full
      return ByteArrayOutputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    val target: Pair<Int, Int>
    val format: Bitmap.CompressFormat
    var quality = 90
    when {
      b.startsWith("n") || b.startsWith("jn") || b.startsWith("pn") -> {
        // The patch grid at this token budget: ≤ tokens*9 patches of 16 px, each side a multiple of 3 patches (48 px),
        // as LiteRT-LM's GetAspectRatioPreservingSize computes it (an input already at that size skips its resize).
        val tokens = b.substringAfter('n').toInt()
        val scale = sqrt(tokens * 9 * 256.0 / (bounds.outWidth.toDouble() * bounds.outHeight))
        target = (floor(bounds.outWidth * scale / 48).toInt().coerceAtLeast(1) * 48) to (floor(bounds.outHeight * scale / 48).toInt().coerceAtLeast(1) * 48)
        format = if (b.startsWith("pn")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
      }
      else -> {
        val side = b.drop(1).substringBefore('q').toInt()
        quality = b.substringAfter('q', "90").toInt()
        val sc = minOf(1.0, side.toDouble() / maxOf(bounds.outWidth, bounds.outHeight))
        target = (bounds.outWidth * sc).toInt() to (bounds.outHeight * sc).toInt()
        format = if (b.startsWith("p")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
      }
    }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxOf(target.first, target.second)) sample *= 2
    val decoded = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    val scaled = if (decoded.width != target.first || decoded.height != target.second) Bitmap.createScaledBitmap(decoded, target.first, target.second, true) else decoded
    return ByteArrayOutputStream().use { scaled.compress(format, quality, it); it.toByteArray() }
  }

  private fun cpuCaps(): JSONObject {
    val o = JSONObject()
    for (c in listOf(0, 7)) {
      for (k in listOf("scaling_max_freq", "scaling_cur_freq")) {
        try { o.put("cpu${c}_$k", File("/sys/devices/system/cpu/cpu$c/cpufreq/$k").readText().trim().toLong()) } catch (_: Throwable) {}
      }
    }
    return o
  }

  private fun say(line: String) {
    Log.i(TAG, line)
    runOnUiThread { log.append(line + "\n") }
  }
}

/** One thread's scheduler counters: on-CPU ns and runnable-but-waiting ns (/proc/self/task/<tid>/schedstat). */
private data class ThreadTimes(val name: String, val runNs: Long, val waitNs: Long)

private fun threadTimes(): Map<Int, ThreadTimes> {
  val out = HashMap<Int, ThreadTimes>()
  File("/proc/self/task").listFiles()?.forEach { t ->
    try {
      val ss = File(t, "schedstat").readText().trim().split(' ')
      out[t.name.toInt()] = ThreadTimes(File(t, "comm").readText().trim(), ss[0].toLong(), ss[1].toLong())
    } catch (_: Throwable) {}
  }
  return out
}

/** CPU used by every thread of this process during one call, grouped by thread name. */
private class Probe(private val before: Map<Int, ThreadTimes>, private val caller: Int) {
  fun stop(): Agg {
    val after = threadTimes()
    val byName = HashMap<String, LongArray>()
    var callerRun = 0L
    var threadsActive = 0
    for ((tid, a) in after) {
      val b = before[tid]
      val run = a.runNs - (b?.runNs ?: 0)
      val wait = a.waitNs - (b?.waitNs ?: 0)
      if (run <= 0 && wait <= 0) continue
      if (run > 1_000_000) threadsActive++
      if (tid == caller) callerRun = run
      val key = if (tid == caller) "<caller>" else a.name
      byName.getOrPut(key) { LongArray(3) }.let { it[0] += run; it[1] += wait; it[2]++ }
    }
    return Agg(byName, callerRun, threadsActive.toLong())
  }

  companion object {
    fun start() = Probe(threadTimes(), android.os.Process.myTid())
  }
}

/** Sum of Probe results over several calls. */
private class Agg(val byName: Map<String, LongArray> = emptyMap(), val callerRunNs: Long = 0, val activeThreads: Long = 0) {
  operator fun plus(o: Agg): Agg {
    val m = HashMap<String, LongArray>()
    for ((k, v) in byName) m[k] = v.copyOf()
    for ((k, v) in o.byName) m.getOrPut(k) { LongArray(3) }.let { it[0] += v[0]; it[1] += v[1]; it[2] += v[2] }
    return Agg(m, callerRunNs + o.callerRunNs, activeThreads + o.activeThreads)
  }

  private val runNs get() = byName.values.sumOf { it[0] }
  private val waitNs get() = byName.values.sumOf { it[1] }

  fun json(calls: Int): JSONObject {
    val top = JSONArray()
    byName.entries.sortedByDescending { it.value[0] }.take(10).forEach { (k, v) ->
      top.put(JSONObject().put("name", k).put("run_ms", v[0] / 1e6 / calls).put("wait_ms", v[1] / 1e6 / calls).put("threads", v[2].toDouble() / calls))
    }
    return JSONObject().put("run_ms", runNs / 1e6 / calls).put("wait_ms", waitNs / 1e6 / calls)
      .put("caller_run_ms", callerRunNs / 1e6 / calls).put("threads_over_1ms", activeThreads.toDouble() / calls).put("by_thread", top)
  }

  fun line(calls: Int) = "cpu ${"%.0f".format(runNs / 1e6 / calls)} ms (caller ${"%.0f".format(callerRunNs / 1e6 / calls)}, runnable-wait ${"%.0f".format(waitNs / 1e6 / calls)}); top " +
    byName.entries.sortedByDescending { it.value[0] }.take(5).joinToString(", ") { (k, v) -> "$k ${"%.0f".format(v[0] / 1e6 / calls)}" }
}

private fun b64(v: FloatArray): String {
  val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
  v.forEach { bb.putFloat(it) }
  return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
}

private fun ms(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1e6

private fun cos(a: FloatArray, b: FloatArray): Double {
  var dot = 0.0; var na = 0.0; var nb = 0.0
  for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
  return dot / sqrt(na * nb)
}

private fun stats(ms: List<Double>): JSONObject {
  if (ms.isEmpty()) return JSONObject().put("n", 0)
  val s = ms.sorted()
  fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
  return JSONObject().put("n", s.size).put("mean", s.average()).put("p50", pct(0.5)).put("p90", pct(0.9)).put("min", s.first()).put("max", s.last())
}
