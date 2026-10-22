package com.craneai.findr.bench

import kotlinx.coroutines.async
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "FindrBench"

/**
 * Milestone 1: how fast, how big and how hot is EmbeddingGemma 2 on this phone?
 *
 * Started by scripts/bench.sh (`am start -n com.craneai.findr/.bench.BenchActivity --es model … --es backend cpu|gpu`).
 * Inputs live in the app-private files dir: models/, bench/corpus.txt, bench/images/. The result is
 * results/<tag>.json there, announced by a FINDR_BENCH_DONE line in logcat.
 */
class BenchActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var log: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    log = TextView(this).apply {
      textSize = 11f
      setPadding(24, 96, 24, 24)
    }
    setContentView(ScrollView(this).apply { addView(log) })
    val args = Args(intent)
    // `--es set quickLook=false`: change one setting and stop, without pausing indexing (the D2 gates flip it mid-run).
    args.set?.let { kv ->
      scope.launch {
        applySetting(kv)
        finish()
      }
      return
    }
    // `--es search "q1|q2"`: each query through the engine's real search (searchProgressive), so the log shows whether the
    // quick-look modality took part ("quick look: N ms over M photos") or sat out ("skipped, not English"). Indexing keeps running.
    // `--es searchfile bench/q.txt`: the same, one query per line from a file in the app's files (no shell quoting to survive).
    (args.search ?: args.raw("searchfile")?.let { File(filesDir, it).readLines().joinToString("|") })?.let { qs ->
      scope.launch {
        val engine = (application as com.craneai.findr.FindrApp).engine
        for (q in qs.split("|").map { it.trim() }.filter { it.isNotEmpty() }) {
          var last: com.craneai.findr.core.SearchResults? = null
          engine.searchProgressive(q).collect { last = it }
          Log.i(TAG, "FINDR_SEARCH \"$q\": ${last?.hits?.size ?: 0} hits, top ${last?.hits?.firstOrNull()?.file?.name}")
        }
        finish()
      }
      return
    }
    // `--es index start`: what the app does once onboarding is done (crawl, then schedule the worker), without the UI, so a
    // timed run doesn't depend on this phone's onboarding state.
    if (args.index == "start") {
      (application as com.craneai.findr.FindrApp).engine.startIndexing()
      Log.i(TAG, "FINDR_INDEX_STARTED")
      finish()
      return
    }
    scope.launch {
      val base = filesDir   // app-private, where the real app keeps its model and index
      val out = File(base, "results/${args.tag}.json").apply { parentFile!!.mkdirs() }
      // The bench runs in the app's process, so a pending index worker (a long PDF's later steps, say) would compete for
      // the CPU: it skewed a decode bench 2.3x. Pause indexing for the run, wait for the worker to stop, restore after.
      val engine = (application as? com.craneai.findr.FindrApp)?.engine as? com.craneai.findr.engine.FindrEngine
      val pausedHere = engine != null && !engine.paused
      if (pausedHere) {
        engine!!.pauseIndexing()
        val until = System.currentTimeMillis() + 120_000
        while (engine.workerState.running && System.currentTimeMillis() < until) kotlinx.coroutines.delay(500)
        say("indexing paused for the bench" + if (engine.workerState.running) " (worker still running after 120 s)" else "")
      }
      val result =
        try {
          run(args, base)
        } catch (t: Throwable) {
          Log.e(TAG, "bench failed", t)
          JSONObject().put("tag", args.tag).put("error", t.toString())
        }
      if (pausedHere) {
        engine!!.resumeIndexing()
        kotlinx.coroutines.delay(1_000)   // let the preference write land before bench.sh force-stops the app
      }
      out.writeText(result.toString(2))
      say("done → ${out.name}")
      Log.i(TAG, "FINDR_BENCH_DONE ${out.absolutePath}")
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  /** Every extra is a string (`--es`), so the adb side stays one flag type. */
  private class Args(private val i: Intent) {
    fun s(key: String) = i.getStringExtra(key)
    val model = s("model") ?: "embeddinggemma-2-text-270m.litertlm"
    val backend = (s("backend") ?: "cpu").lowercase()
    val visionBackend = (s("vbackend") ?: backend).lowercase()   // images can run on another backend than text
    val threads = s("threads")?.toIntOrNull()
    val tokens = (s("tokens") ?: "128,256,512").split(',').mapNotNull { it.trim().toIntOrNull() }
    val n = s("n")?.toIntOrNull() ?: 30
    val batch = s("batch")?.toIntOrNull() ?: 8
    val images = s("images")?.toIntOrNull() ?: 0
    val visionTokens = s("vtok")?.toIntOrNull()
    val dim = s("dim")?.toIntOrNull()
    val maxLen = s("maxlen")?.toIntOrNull()
    val activations = s("act")?.uppercase()?.let { ActivationDataType.valueOf(it) }   // FLOAT32 FLOAT16 INT16 INT8
    val sustainSec = s("sustain")?.toIntOrNull() ?: 0
    val sustainImages = s("sustainmode") == "image"   // default: 256-token text chunks
    val tag = s("tag") ?: "run"
    val set = s("set")         // "quickLook=false": one setting, then stop (no bench, indexing keeps running)
    val index = s("index")     // "start": engine.startIndexing(), then stop
    val search = s("search")   // "q1|q2": queries through the engine's real search, then stop (indexing keeps running)
    val clipDir = s("clip")    // bench/<dir>: the app's quick-look path over every picture there (+ queries.txt), vectors to results/
    val costDir = s("d2cost")  // bench/<dir>: gate a, EmbeddingGemma's per-photo time alone / beside the quick-look lane / alone
    val costN = s("costn")?.toIntOrNull() ?: 12
    // "priority:threads[:duty[:kind]],...": lane settings to compare (5:2 = what the app runs: nice 5, 2 ONNX Runtime threads).
    // kind: ql (quick look, the default), priv (the privacy scoring pass as shipped: its own decode near 768 px), shared (one
    // quick-look decode near 448 px feeding both CLIP and the privacy scorer), or both (the ql lane at its shipped duty and a
    // priv lane at the duty given, at once: the cost of the pass on top of quick look).
    val costLanes = (s("lanes") ?: "5:2").split(",").map {
      it.split(":").let { p -> LaneSpec(p[0].trim().toInt(), p[1].trim().toInt(), p.getOrNull(2)?.trim()?.toDouble() ?: 1.0, p.getOrNull(3)?.trim() ?: "ql") }
    }
    val costVideoDir = s("d2costv")  // bench/<dir>: gate a for videos with speech (videos for EG, pictures for the lane)
    val textDir = s("cliptext")   // bench/<dir>: every .onnx text tower there over queries.txt with the app's tokenizer (parity of variants)
    val imageDir = s("clipimage")  // bench/<dir>: every .onnx image tower there over the pictures in bench/<pics> (default d2parity)
    val imagePics = s("pics") ?: "d2parity"
    val queryLatency = s("querylat") != null   // keystroke to quick-look query vector: a fresh encoder (cold), then warm
    val check = s("check")   // "sqlite": which SQLite on this phone has FTS5, then stop
    val decodeDir = s("decode")   // bench/<dir>: decode every file's soundtrack (no model), time per audio second, then stop
    val decodePriority = s("dec_prio")?.toIntOrNull()
    val decodeRate = s("dec_rate")?.toIntOrNull()
    val decodeParallel = s("dec_par") == "1"   // all files at once: does throughput scale (codec latency-bound)?
    val videos = s("videos")?.toIntOrNull() ?: 0      // bench/videos: frame sampling, frame embedding, frame OCR
    val framesPerVideo = s("frames")?.toIntOrNull() ?: 3   // 0 = auto: one per 15 s, at least 1, at most 12
    val videoDir = s("vdir") ?: "videos"
    val videoAudio = s("vaudio") == "1"                   // also embed the video's soundtrack (bench/videowav/<name>_wNN.wav)
    val audioBackend = (s("abackend") ?: "cpu").lowercase()
    val audioDir = s("adir") ?: "audio"                      // audio windows to embed (bench/<adir>/*.wav)
    val parallel = s("parallel") == "1" || s("parallel") == "2"   // frames (GPU) and audio (CPU) at the same time
    val secondEngine = s("parallel") == "2"
    val imageBatch = s("ibatch")?.toIntOrNull() ?: 1             // images per computeEmbeddingBatch call (1 = one at a time)                     // …with audio on its own engine instead of the shared one
    val ocr = s("ocr")?.toIntOrNull() ?: 0            // bench/ocr: ML Kit text recognition on screenshots and memes
    val audio = s("audio")?.toIntOrNull() ?: 0        // bench/audio: 30 s WAV windows through the audio encoder
    val export = s("export")                          // ExportBench: an exported text model's vectors, speed, memory
    fun raw(key: String) = s(key)

    fun json() =
      JSONObject()
        .put("model", model).put("backend", backend).put("vbackend", visionBackend).put("threads", threads ?: JSONObject.NULL)
        .put("tokens", JSONArray(tokens)).put("n", n).put("batch", batch).put("images", images)
        .put("vtok", visionTokens ?: JSONObject.NULL).put("dim", dim ?: JSONObject.NULL)
        .put("maxlen", maxLen ?: JSONObject.NULL).put("act", activations?.name ?: JSONObject.NULL).put("sustain_s", sustainSec)
        .put("sustain_mode", if (sustainImages) "image" else "text")
        .put("videos", videos).put("frames", framesPerVideo).put("ocr", ocr).put("audio", audio)
        .put("vdir", videoDir).put("vaudio", videoAudio).put("abackend", audioBackend).put("adir", audioDir).put("parallel", parallel)
  }

  private fun run(args: Args, base: File): JSONObject {
    val result = JSONObject().put("tag", args.tag).put("args", args.json()).put("device", device())
    if (args.check == "sqlite") return result.put("sqlite", sqliteCheck())
    if (args.raw("mem") != null) return result.put("mem", MemBench.run(this, base, cacheDir, scope, args::raw, ::say))
    if (args.clipDir != null) return result.put("clip", clipBench(base, args.clipDir, args.tag))
    if (args.costDir != null) return result.put("d2cost", d2Cost(base, args.costDir, args.costN, args.costLanes))
    if (args.costVideoDir != null) return result.put("d2costv", d2CostVideo(base, args.costVideoDir, (args.s("lanes") ?: "5:2:0.6,5:2:0.6:p").split(",")))
    if (args.textDir != null) return result.put("cliptext", clipTextVariants(base, args.textDir, args.tag))
    if (args.imageDir != null) return result.put("clipimage", clipImageVariants(base, args.imageDir, args.imagePics, args.tag))
    if (args.queryLatency) return result.put("querylat", queryLatency())
    if (args.decodeDir != null) return result.put("decode", decodeBench(base, args.decodeDir, args.decodePriority, args.decodeRate, args.decodeParallel))
    val sampler = MetricsSampler(this)
    if (args.export != null) {
      sampler.start(scope)
      result.put("export",
        if (args.raw("appembedder") == "1") ExportBench.appEmbedder(this@BenchActivity, base, args.tag, args::raw, ::say)
        else ExportBench.run(base, cacheDir, args.tag, args::raw, sampler, ::say))
      val m = sampler.stop()
      return result.put("peak_pss_mb", m.peakPssMb).put("min_avail_mem_mb", m.minAvailMemMb)
    }
    if (args.ocr > 0) result.put("ocr", ocrBench(base, args.ocr))
    result.put("battery_start_pct", sampler.batteryPercent()).put("charging", sampler.isCharging())
      .put("thermal_status_start", sampler.thermalStatus())
    result.put("pss_before_mb", sampler.snapshot().pssMb)
    sampler.start(scope)

    fun backendFor(name: String): Backend = if (name == "gpu") Backend.GPU() else Backend.CPU(threadCount = args.threads)
    val backend = backendFor(args.backend)
    val config =
      EmbeddingEngineConfig(
        modelPath = File(base, "models/${args.model}").absolutePath,
        backend = backend,
        visionBackend = if (args.images > 0 || args.videos > 0 || args.parallel) backendFor(args.visionBackend) else null,
        audioBackend = if (args.audio > 0 || args.videoAudio || args.parallel) backendFor(args.audioBackend) else null,
        cacheDir = cacheDir.absolutePath,
        maxInputLength = args.maxLen,
        visionTokensPerImage = args.visionTokens,
        activationDataType = args.activations,
      )
    say("init ${args.model} on ${args.backend}${if (args.images > 0) " (images on ${args.visionBackend})" else ""}…")
    var t = SystemClock.elapsedRealtimeNanos()
    val engine = EmbeddingEngine(config)
    engine.initialize()
    result.put("init_ms", ms(t)).put("pss_after_init_mb", sampler.snapshot().pssMb)
    say("init ${"%.0f".format(result.getDouble("init_ms"))} ms")

    val opts = EmbeddingOptions(normalize = true, outputSize = args.dim, visionTokensPerImage = args.visionTokens)
    fun embed(inputs: List<InputData>): Pair<FloatArray, Double> {
      val start = SystemClock.elapsedRealtimeNanos()
      val v = engine.computeEmbedding(inputs, opts).embedding
      return v to ms(start)
    }
    fun text(s: String) = listOf(InputData.Text(s))

    engine.use {
      // Sanity first: a broken pipeline benchmarks beautifully. The paraphrase query must beat the unrelated one.
      val (doc, firstMs) = embed(text("title: none | text: The cat slept all afternoon on the warm mat by the kitchen window."))
      val (near, _) = embed(text("task: search result | query: where did the cat sleep"))
      val (far, _) = embed(text("task: search result | query: interest rates and the stock market"))
      val sanity =
        JSONObject().put("dim", doc.size).put("norm", norm(doc)).put("cos_paraphrase", cos(doc, near))
          .put("cos_unrelated", cos(doc, far)).put("ok", cos(doc, near) > cos(doc, far) + 0.05)
      result.put("first_call_ms", firstMs).put("sanity", sanity)
      say("sanity $sanity")

      val corpus = Corpus(File(base, "bench/corpus.txt"))
      repeat(3) { embed(text(corpus.chunk(256, 1000 + it))) } // warm-up

      val textResults = JSONObject()
      for (tokens in args.tokens) {
        val times = (0 until args.n).map { embed(text(corpus.chunk(tokens, it))).second }
        textResults.put(tokens.toString(), stats(times))
        say("text $tokens tok: ${stats(times)}")
      }
      result.put("text", textResults)

      if (args.batch > 1) {
        try {
          val rounds = maxOf(1, args.n / args.batch)
          var k = 0
          t = SystemClock.elapsedRealtimeNanos()
          repeat(rounds) {
            engine.computeEmbeddingBatch(List(args.batch) { text(corpus.chunk(256, 2000 + k++)) }, opts)
          }
          val perItem = ms(t) / (rounds * args.batch)
          result.put("batch_256", JSONObject().put("batch", args.batch).put("ms_per_item", perItem))
          say("batch ${args.batch} @256: ${"%.1f".format(perItem)} ms/item")
        } catch (e: Throwable) {
          result.put("batch_256", JSONObject().put("error", e.toString()))
        }
      }

      if (args.images > 0 && args.imageBatch > 1) result.put("images_batched", imagesBatched(base, args.images, args.imageBatch) { batch ->
        val start = SystemClock.elapsedRealtimeNanos()
        engine.computeEmbeddingBatch(batch, opts)
        ms(start)
      })
      else if (args.images > 0) result.put("images", images(base, args.images, ::embed))
      if (args.videos > 0) result.put("videos", videos(base, args, ::embed))
      if (args.audio > 0) result.put("audio", audioWindows(base, args.audioDir, args.audio, ::embed))
      if (args.parallel) {
        // parallel=2: audio gets its own engine (text CPU, vision CPU idle, audio CPU), so the GPU and CPU can overlap.
        val audioEngine = if (args.secondEngine) EmbeddingEngine(
          EmbeddingEngineConfig(
            modelPath = File(base, "models/${args.model}").absolutePath,
            backend = Backend.CPU(threadCount = args.threads),
            visionBackend = Backend.CPU(),
            audioBackend = Backend.CPU(threadCount = args.threads),
            cacheDir = cacheDir.absolutePath,
          )
        ).also { it.initialize(); say("second engine loaded, PSS ${"%.0f".format(sampler.snapshot().pssMb)} MB") } else null
        val audioEmbed: (List<InputData>) -> Pair<FloatArray, Double> = audioEngine?.let { e ->
          { inputs -> val start = SystemClock.elapsedRealtimeNanos(); e.computeEmbedding(inputs, opts).embedding to ms(start) }
        } ?: ::embed
        result.put("parallel", parallelBench(base, ::embed, audioEmbed).put("second_engine", args.secondEngine))
        audioEngine?.close()
      }

      if (args.sustainSec > 0) {
        val images = if (args.sustainImages) imageInputs(base) else emptyList()
        var k = 5000
        val next: () -> List<InputData> =
          if (args.sustainImages) { { listOf(InputData.Image(images[k++ % images.size])) } }
          else { { listOf(InputData.Text(corpus.chunk(256, k++))) } }
        result.put("sustained", sustained(args.sustainSec, sampler, next, ::embed))
      }
    }

    val m = sampler.stop()
    result.put("peak_pss_mb", m.peakPssMb).put("min_avail_mem_mb", m.minAvailMemMb)
      .put("avg_current_ma", m.avgCurrentMa).put("max_thermal_headroom", num(m.maxThermalHeadroom.toDouble()))
      .put("max_thermal_status", m.maxThermalStatus).put("battery_end_pct", sampler.batteryPercent())
    say("peak PSS ${"%.0f".format(m.peakPssMb)} MB, min avail ${"%.0f".format(m.minAvailMemMb)} MB")
    return result
  }

  /** Each image the way the app will feed it: decoded small (≤768 px long side), re-encoded as JPEG. */
  private fun images(base: File, count: Int, embed: (List<InputData>) -> Pair<FloatArray, Double>): JSONObject {
    val files = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(count).orEmpty()
    val prep = mutableListOf<Double>()
    val times = mutableListOf<Double>()
    val perKind = mutableMapOf<String, MutableList<Double>>()
    for (f in files) {
      val start = SystemClock.elapsedRealtimeNanos()
      val bytes = downscale(f, 768)
      prep += ms(start)
      val ms = embed(listOf(InputData.Image(bytes))).second
      times += ms
      perKind.getOrPut(f.name.substringBefore('_')) { mutableListOf() } += ms
    }
    val kinds = JSONObject().apply { perKind.forEach { (k, v) -> put(k, stats(v)) } }
    say("images ${files.size}: embed ${stats(times)} prep ${stats(prep)}")
    return JSONObject().put("count", files.size).put("embed", stats(times)).put("prep", stats(prep)).put("by_kind", kinds)
  }

  /** The bench images, downscaled once up front, so a sustained run measures only the model. */
  private fun imageInputs(base: File): List<ByteArray> =
    File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.map { downscale(it, 768) }.orEmpty()

  /** Images in batches of `size` per call: does the GPU (or CPU) get more done per image when fed several at once? */
  private fun imagesBatched(base: File, count: Int, size: Int, run: (List<List<InputData>>) -> Double): JSONObject {
    val jpegs = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(count).orEmpty().map { downscale(it, 768) }
    run(listOf(listOf(InputData.Image(jpegs.first()))))   // warm-up
    val perImage = jpegs.chunked(size).map { chunk -> run(chunk.map { listOf(InputData.Image(it)) }) / chunk.size }
    say("images batched ×$size: ${stats(perImage)} ms per image")
    return JSONObject().put("batch", size).put("ms_per_image", stats(perImage))
  }

  /**
   * Video the way the indexer will do it: tiny frames once a second (shot detection input), then one full frame per
   * chosen moment at ≤768 px, embedded as an image and read by OCR (burned-in meme captions).
   */
  private fun videos(base: File, args: Args, embed: (List<InputData>) -> Pair<FloatArray, Double>): JSONObject {
    val files = File(base, "bench/${args.videoDir}").listFiles()?.filter { it.isFile }?.sortedBy { it.length() }?.take(args.videos).orEmpty()
    val out = JSONObject()
    for (f in files) {
      val start = SystemClock.elapsedRealtimeNanos()
      val r = android.media.MediaMetadataRetriever()
      var t = SystemClock.elapsedRealtimeNanos()
      r.setDataSource(f.path)
      val durationMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
      val openMs = ms(t)
      t = SystemClock.elapsedRealtimeNanos()
      val seconds = (durationMs / 1000).toInt().coerceAtLeast(1)
      for (sec in 0 until seconds) {
        r.getScaledFrameAtTime(sec * 1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 64)
      }
      val tinyMs = ms(t)
      val frames = if (args.framesPerVideo > 0) args.framesPerVideo else (seconds / 15).coerceIn(1, 12)
      val grab = mutableListOf<Double>(); val emb = mutableListOf<Double>(); val ocrMs = mutableListOf<Double>()
      var ocrChars = 0
      for (i in 0 until frames) {
        val at = durationMs * 1000 * (2 * i + 1) / (2 * frames)   // middle of each equal slice, in µs
        t = SystemClock.elapsedRealtimeNanos()
        val frame = r.getScaledFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 768, 768) ?: continue
        val jpeg = ByteArrayOutputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        grab += ms(t)
        emb += embed(listOf(InputData.Image(jpeg))).second
        t = SystemClock.elapsedRealtimeNanos()
        ocrChars += recognize(frame).length
        ocrMs += ms(t)
      }
      r.release()
      val audioMs = mutableListOf<Double>()
      if (args.videoAudio) {
        val windows = File(base, "bench/videowav").listFiles()?.filter { it.name.startsWith(f.nameWithoutExtension + "_w") }?.sortedBy { it.name }.orEmpty()
        for (w in windows) audioMs += embed(listOf(InputData.Audio(w.readBytes()))).second
      }
      val totalMs = ms(start)
      val v = JSONObject().put("duration_s", durationMs / 1000.0).put("open_ms", openMs).put("frames", frames)
        .put("audio_windows", audioMs.size).put("audio_embed", stats(audioMs)).put("total_ms", totalMs)
        .put("realtime_factor", totalMs / durationMs.coerceAtLeast(1))
        .put("tiny_sampling_ms", tinyMs).put("tiny_per_second_ms", tinyMs / seconds)
        .put("frame_grab", stats(grab)).put("frame_embed", stats(emb)).put("frame_ocr", stats(ocrMs)).put("ocr_chars", ocrChars)
      say("video ${f.name} (${seconds}s): total ${"%.1f".format(totalMs / 1000)} s = sampling ${"%.1f".format(tinyMs / 1000)} s + $frames frames × ${"%.1f".format((grab.average() + emb.average() + ocrMs.average()) / 1000)} s + ${audioMs.size} audio × ${"%.1f".format(if (audioMs.isEmpty()) 0.0 else audioMs.average() / 1000)} s")
      out.put(f.name, v)
    }
    return out
  }

  private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null

  private fun recognize(bitmap: Bitmap): String {
    val rec = recognizer ?: com.google.mlkit.vision.text.TextRecognition
      .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    return com.google.android.gms.tasks.Tasks.await(rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0))).text
  }

  /** ML Kit on full-size screenshots and memes, as decoded from disk (first call includes loading the model). */
  private fun ocrBench(base: File, count: Int): JSONObject {
    val files = File(base, "bench/ocr").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(count).orEmpty()
    val times = mutableListOf<Double>(); val decode = mutableListOf<Double>()
    var first = -1.0; var chars = 0
    for (f in files) {
      var t = SystemClock.elapsedRealtimeNanos()
      val bmp = BitmapFactory.decodeFile(f.path) ?: continue
      decode += ms(t)
      t = SystemClock.elapsedRealtimeNanos()
      chars += recognize(bmp).length
      val m = ms(t)
      if (first < 0) first = m else times += m
    }
    say("ocr ${files.size}: first ${"%.0f".format(first)} ms, then ${stats(times)}")
    return JSONObject().put("count", files.size).put("first_ms", first).put("ocr", stats(times)).put("decode", stats(decode)).put("chars", chars)
  }

  /** 30 s windows of 16 kHz mono WAV through the audio encoder (740m model only). */
  private fun audioWindows(base: File, dir: String, count: Int, embed: (List<InputData>) -> Pair<FloatArray, Double>): JSONObject {
    val files = File(base, "bench/$dir").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    val out = JSONObject()
    val all = mutableListOf<Double>()
    // Each file `count` times, so window lengths (len_05s … len_30s) can be compared per second of audio.
    for (f in files) {
      val times = (0 until count).map { embed(listOf(InputData.Audio(f.readBytes()))).second }
      val seconds = (f.length() - 44) / 32_000.0   // 16 kHz mono 16-bit after the WAV header
      all += times
      out.put(f.name, stats(times).put("audio_s", seconds).put("ms_per_audio_s", times.average() / seconds))
      say("audio ${f.name} (${"%.0f".format(seconds)} s): ${"%.0f".format(times.average())} ms = ${"%.0f".format(times.average() / seconds)} ms per second of audio")
    }
    return out.put("all", stats(all))
  }

  /**
   * Can the GPU (frames) and the CPU (audio) work at the same time on one engine? Sequential first, then the same
   * work on two threads; vectors are compared to make sure concurrency doesn't change them.
   */
  private fun parallelBench(
    base: File,
    embed: (List<InputData>) -> Pair<FloatArray, Double>,
    audioEmbed: (List<InputData>) -> Pair<FloatArray, Double> = embed,
  ): JSONObject {
    val imgs = (File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(4).orEmpty()).map { downscale(it, 768) }
    val wavs = File(base, "bench/audio").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(2).orEmpty().map { it.readBytes() }
    var t = SystemClock.elapsedRealtimeNanos()
    val seqImg = imgs.map { embed(listOf(InputData.Image(it))).first }
    val imgMs = ms(t)
    t = SystemClock.elapsedRealtimeNanos()
    val seqAud = wavs.map { embed(listOf(InputData.Audio(it))).first }
    val audMs = ms(t)
    say("sequential: images ${"%.0f".format(imgMs)} ms + audio ${"%.0f".format(audMs)} ms = ${"%.0f".format(imgMs + audMs)} ms")
    val parImg = arrayOfNulls<FloatArray>(imgs.size)
    val parAud = arrayOfNulls<FloatArray>(wavs.size)
    var error: Throwable? = null
    t = SystemClock.elapsedRealtimeNanos()
    val a = Thread { try { imgs.forEachIndexed { i, b -> parImg[i] = embed(listOf(InputData.Image(b))).first } } catch (e: Throwable) { error = e } }
    val b = Thread { try { wavs.forEachIndexed { i, w -> parAud[i] = audioEmbed(listOf(InputData.Audio(w))).first } } catch (e: Throwable) { error = e } }
    a.start(); b.start(); a.join(); b.join()
    val parMs = ms(t)
    val parity = (seqImg.indices.map { cos(seqImg[it], parImg[it] ?: FloatArray(seqImg[it].size)) } +
      seqAud.indices.map { cos(seqAud[it], parAud[it] ?: FloatArray(seqAud[it].size)) }).minOrNull() ?: 0.0
    say("parallel: ${"%.0f".format(parMs)} ms (vs ${"%.0f".format(imgMs + audMs)} sequential), min parity cos ${"%.6f".format(parity)}${error?.let { ", error $it" } ?: ""}")
    return JSONObject().put("images", imgs.size).put("audio_windows", wavs.size).put("seq_images_ms", imgMs).put("seq_audio_ms", audMs)
      .put("parallel_ms", parMs).put("speedup", (imgMs + audMs) / parMs).put("min_parity_cos", parity).put("error", error?.toString() ?: JSONObject.NULL)
  }

  /** Inputs back to back for `seconds`; throughput per 10 s window shows thermal throttling. */
  private fun sustained(
    seconds: Int,
    sampler: MetricsSampler,
    next: () -> List<InputData>,
    embed: (List<InputData>) -> Pair<FloatArray, Double>,
  ): JSONObject {
    val windows = JSONArray()
    val rates = mutableListOf<Double>()
    val end = SystemClock.elapsedRealtime() + seconds * 1000L
    var windowStart = SystemClock.elapsedRealtime()
    var count = 0
    while (SystemClock.elapsedRealtime() < end) {
      embed(next())
      count++
      val now = SystemClock.elapsedRealtime()
      if (now - windowStart >= 10_000) {
        val rate = count * 1000.0 / (now - windowStart)
        rates += rate
        windows.put(JSONObject().put("t_s", (seconds * 1000L - (end - now)) / 1000).put("items_per_s", rate)
          .put("thermal_status", sampler.thermalStatus()).put("thermal_headroom", num(sampler.lastHeadroom.toDouble())))
        windowStart = now
        count = 0
      }
    }
    val slowdown = if (rates.size >= 2) 1 - rates.drop(1).min() / rates.first() else Double.NaN
    say("sustained ${seconds}s: ${"%.3f".format(rates.firstOrNull() ?: 0.0)} → ${"%.3f".format(rates.lastOrNull() ?: 0.0)} items/s")
    return JSONObject().put("windows", windows).put("slowdown", num(slowdown))
  }

  private fun downscale(f: File, maxSide: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
    val bitmap =
      if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
      else decoded
    return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
  }

  /** Does the phone's own SQLite have FTS5 (keyword search), or does Findr have to bundle SQLite? */
  /** Soundtrack decoding alone (MediaAudioDecoder, as speech-to-text uses it): where the speech stage's decode time goes. */
  private fun decodeBench(base: File, dir: String, priority: Int?, rate: Int?, parallel: Boolean): JSONObject {
    val files = File(base, "bench/$dir").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
    val out = JSONObject().put("priority", priority ?: JSONObject.NULL).put("rate", rate ?: JSONObject.NULL).put("parallel", parallel)
    var totalMs = 0.0
    var totalS = 0.0
    fun decodeOne(f: File) = kotlinx.coroutines.runBlocking {
      com.craneai.findr.extract.MediaAudioDecoder(priority, rate).windows(this@BenchActivity, android.net.Uri.fromFile(f), 10_000, 10_000, 1000)
    }
    if (parallel) {
      val t0 = System.nanoTime()
      val all = kotlinx.coroutines.runBlocking {
        val scope = this
        files.map { f -> scope.async(kotlinx.coroutines.Dispatchers.IO) { decodeOne(f) } }.map { it.await() }
      }
      val ms = (System.nanoTime() - t0) / 1e6
      val seconds = all.sumOf { w -> w.sumOf { (it.endMs - it.startMs) / 1000.0 } }
      say("decode ${files.size} files at once: ${"%.0f".format(ms)} ms wall for ${"%.0f".format(seconds)} s of audio")
      return out.put("all", JSONObject().put("ms", ms).put("audio_s", seconds).put("ms_per_audio_s", if (seconds > 0) ms / seconds else JSONObject.NULL))
    }
    for (f in files) {
      val t0 = System.nanoTime()
      val windows = decodeOne(f)
      val ms = (System.nanoTime() - t0) / 1e6
      val seconds = windows.sumOf { (it.endMs - it.startMs) / 1000.0 }
      totalMs += ms; totalS += seconds
      out.put(f.name, JSONObject().put("ms", ms).put("audio_s", seconds).put("ms_per_audio_s", if (seconds > 0) ms / seconds else JSONObject.NULL))
      say("decode ${f.name}: ${"%.0f".format(ms)} ms for ${"%.0f".format(seconds)} s of audio")
    }
    return out.put("all", JSONObject().put("ms", totalMs).put("audio_s", totalS).put("ms_per_audio_s", if (totalS > 0) totalMs / totalS else JSONObject.NULL))
  }

  private fun sqliteCheck(): JSONObject {
    val out = JSONObject()
    try {
      val db = android.database.sqlite.SQLiteDatabase.create(null)
      out.put("framework_version", db.compileStatement("select sqlite_version()").simpleQueryForString())
      try {
        db.execSQL("CREATE VIRTUAL TABLE t USING fts5(x, tokenize = 'porter unicode61')")
        out.put("framework_fts5", true)
      } catch (e: Throwable) {
        out.put("framework_fts5", false).put("framework_error", e.message)
      }
      db.close()
    } catch (e: Throwable) {
      out.put("framework_error", e.toString())
    }
    try {
      val conn = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(":memory:")
      conn.prepare("select sqlite_version()").use { it.step(); out.put("bundled_version", it.getText(0)) }
      conn.prepare("CREATE VIRTUAL TABLE t USING fts5(x, tokenize = 'porter unicode61')").use { it.step() }
      out.put("bundled_fts5", true)
      conn.close()
    } catch (e: Throwable) {
      out.put("bundled_fts5", false).put("bundled_error", e.toString())
    }
    say("sqlite $out")
    return out
  }

  /** One setting from adb (`--es set quickLook=false`), applied through the engine like the Settings screen does. */
  private suspend fun applySetting(kv: String) {
    val engine = (application as com.craneai.findr.FindrApp).engine
    val (key, value) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    // `profile=lite|standard|auto`: the debug setting that forces the memory profile; it takes effect at the next app start.
    if (key == "profile") {
      require(value in setOf("lite", "standard", "auto")) { "profile must be lite, standard or auto" }
      getSharedPreferences("findr", MODE_PRIVATE).edit().apply {
        if (value == "auto") remove(com.craneai.findr.engine.FindrEngine.KEY_PROFILE_OVERRIDE)
        else putString(com.craneai.findr.engine.FindrEngine.KEY_PROFILE_OVERRIDE, value)
      }.commit()
      Log.i(TAG, "FINDR_SET $kv (restart the app to apply)")
      return
    }
    if (key == "quickWordsDuty") {   // a tuning knob, not a user setting: QuickLookPass.wordsDuty reads it
      getSharedPreferences(com.craneai.findr.engine.QuickLookPass.TUNING, MODE_PRIVATE).edit().putFloat(key, value.toFloat()).commit()
      Log.i(TAG, "FINDR_SET $kv")
      return
    }
    if (key == "learningSpeed") {   // gentle | balanced | fast
      val speed = com.craneai.findr.core.LearningSpeed.of(value) ?: error("unknown learning speed $value")
      engine.updateSettings { it.copy(learningSpeed = speed) }
      Log.i(TAG, "FINDR_SET $kv")
      return
    }
    val on = value.toBooleanStrict()
    engine.updateSettings { s ->
      when (key) {
        "quickLook" -> s.copy(quickLook = on)
        "onlyWhileCharging" -> s.copy(learningSpeed = if (on) com.craneai.findr.core.LearningSpeed.GENTLE else com.craneai.findr.core.LearningSpeed.BALANCED)
        "speechToText" -> s.copy(speechToText = on)
        else -> error("unknown setting $key")
      }
    }
    Log.i(TAG, "FINDR_SET $kv")
  }

  /**
   * The quick-look path exactly as the app runs it (QuickLookPass.decode, ClipEncoder on 2 threads) over every picture in
   * bench/<dir>, and the text tower over bench/<dir>/queries.txt. Vectors go to results/<tag>.image.f32 and .text.f32
   * (float32 little-endian, 512 per row) for the Mac to compare against the fp32 graphs (phone parity, D2).
   */
  private fun clipBench(base: File, dirName: String, tag: String): JSONObject {
    val engine = (application as com.craneai.findr.FindrApp).engine as com.craneai.findr.engine.FindrEngine
    val clip = engine.clip
    check(clip.available()) { "quick-look models are not installed" }
    val dir = File(base, "bench/$dirName")
    val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }.orEmpty().sortedBy { it.name }
    val names = JSONArray()
    val decode = ArrayList<Double>(); val prep = ArrayList<Double>(); val model = ArrayList<Double>()
    fun write(out: java.io.OutputStream, v: FloatArray) {
      val b = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      v.forEach { b.putFloat(it) }
      out.write(b.array())
    }
    File(base, "results/$tag.image.f32").outputStream().buffered().use { out ->
      for (f in files) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val bmp = com.craneai.findr.engine.QuickLookPass.decode(this, android.net.Uri.fromFile(f)) ?: continue
        decode += ms(t0)
        val v = clip.image(bmp)
        bmp.recycle()
        prep += clip.lastPrepMs.toDouble(); model += clip.lastModelMs.toDouble()
        write(out, v)
        names.put(f.name)
      }
    }
    clip.releaseImage()
    say("quick look: ${names.length()} pictures, decode ${stats(decode).optDouble("p50")} ms, prep ${stats(prep).optDouble("p50")} ms, model ${stats(model).optDouble("p50")} ms (p50)")
    val queries = File(dir, "queries.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    val queryMs = ArrayList<Double>()
    File(base, "results/$tag.text.f32").outputStream().buffered().use { out ->
      kotlinx.coroutines.runBlocking {
        for (q in queries) {
          val t0 = SystemClock.elapsedRealtimeNanos()
          val v = clip.query(q) ?: error("tokenizer unavailable")
          queryMs += ms(t0)
          write(out, v)
        }
      }
    }
    // The word list through the production loader (assets are packaged without their .gz): logs "English word list loaded".
    val gate = com.craneai.findr.engine.SpeechLanguageGate.fromAssets(com.craneai.findr.engine.ContextAssets(this))
    val gateChecks = JSONObject()
    listOf("dog on the beach", "receipt from the supermarket", "embwa ku lubalama", "mbwa ufukweni").forEach { gateChecks.put(it, gate.isEnglishQuery(it)) }
    say("query gate on the phone: $gateChecks")
    return JSONObject().put("images", names).put("decode_ms", stats(decode)).put("prep_ms", stats(prep)).put("model_ms", stats(model))
      .put("queries", JSONArray(queries)).put("query_ms", stats(queryMs)).put("gate", gateChecks)
  }

  /**
   * Text-tower variants on this phone's CPU (the int8 graph gave wrong vectors on ARM): every .onnx file in bench/<dir>, at full and
   * basic graph optimisation, over queries.txt tokenised by the app's ClipTokenizer. Vectors to results/<tag>.<file>.<opt>.f32.
   */
  private fun clipTextVariants(base: File, dirName: String, tag: String): JSONObject {
    val dir = File(base, "bench/$dirName")
    val tok = com.craneai.findr.engine.BundledAssets.open(com.craneai.findr.engine.ContextAssets(this), com.craneai.findr.engine.ClipTokenizer.ASSET)
      .use { com.craneai.findr.engine.ClipTokenizer.fromGzip(it) }
    val queries = File(dir, "queries.txt").readLines().filter { it.isNotBlank() }
    val env = com.craneai.findr.engine.ClipEncoder.environment()
    val out = JSONObject().put("queries", JSONArray(queries))
    for (model in dir.listFiles { f -> f.name.endsWith(".onnx") }.orEmpty().sortedBy { it.name }) {
      for (opt in listOf(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT, ai.onnxruntime.OrtSession.SessionOptions.OptLevel.BASIC_OPT)) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val options = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4); setOptimizationLevel(opt) }
        val session = env.createSession(model.absolutePath, options)
        val loadMs = ms(t0)
        val pss = MetricsSampler(this).snapshot().pssMb
        val times = ArrayList<Double>()
        val name = "${model.nameWithoutExtension}.${opt.name}"
        File(base, "results/$tag.$name.f32").outputStream().buffered().use { o ->
          for (q in queries) {
            val t1 = SystemClock.elapsedRealtimeNanos()
            val v = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(tok.encode(q)), longArrayOf(1, 77)).use { input ->
              session.run(mapOf("input_ids" to input)).use { r -> (r.get(0).value as Array<*>)[0] as FloatArray }
            }
            times += ms(t1)
            val b = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            v.forEach { b.putFloat(it) }
            o.write(b.array())
          }
        }
        session.close()
        say("text $name: load ${loadMs.toInt()} ms, p50 ${stats(times).optDouble("p50")} ms, pss $pss MB")
        out.put(name, JSONObject().put("load_ms", loadMs).put("query_ms", stats(times)).put("pss_mb", pss))
      }
    }
    return out
  }

  /**
   * Image-tower variants on this phone's CPU, fed exactly what the app feeds (QuickLookPass.decode + ClipImage.prepare), on the
   * pass's 2 threads with spinning off. The prepared inputs of every picture go to results/<tag>.inputs.f32, so the
   * Mac can run its fp32 graph on the very same input and tell preprocessing apart from the model and the ARM kernels.
   */
  private fun clipImageVariants(base: File, dirName: String, picsDir: String, tag: String): JSONObject {
    val pics = File(base, "bench/$picsDir").listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
      .orEmpty().sortedBy { it.name }
    val inputs = pics.map { f ->
      val bmp = com.craneai.findr.engine.QuickLookPass.decode(this, android.net.Uri.fromFile(f))!!
      val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
      com.craneai.findr.engine.ClipImage.prepare(argb, bmp.width, bmp.height).also { bmp.recycle() }
    }
    fun write(f: File, vs: List<FloatArray>) = f.outputStream().buffered().use { o ->
      for (v in vs) {
        val b = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        o.write(b.array())
      }
    }
    write(File(base, "results/$tag.inputs.f32"), inputs)
    val env = com.craneai.findr.engine.ClipEncoder.environment()
    val out = JSONObject().put("images", JSONArray(pics.map { it.name }))
    for (model in File(base, "bench/$dirName").listFiles { f -> f.name.endsWith(".onnx") }.orEmpty().sortedBy { it.name }) {
      val options = ai.onnxruntime.OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2); setInterOpNumThreads(1)
        setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addConfigEntry("session.intra_op.allow_spinning", "0")
      }
      val t0 = SystemClock.elapsedRealtimeNanos()
      val session = env.createSession(model.absolutePath, options)
      val loadMs = ms(t0)
      val pss = MetricsSampler(this).snapshot().pssMb
      val times = ArrayList<Double>()
      val vs = inputs.map { x ->
        val t1 = SystemClock.elapsedRealtimeNanos()
        val v = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(x), longArrayOf(1, 3, 224, 224)).use { input ->
          session.run(mapOf("pixel_values" to input)).use { r -> (r.get(0).value as Array<*>)[0] as FloatArray }
        }
        times += ms(t1)
        v
      }
      session.close()
      write(File(base, "results/$tag.${model.nameWithoutExtension}.f32"), vs)
      say("image ${model.nameWithoutExtension}: load ${loadMs.toInt()} ms, p50 ${stats(times).optDouble("p50")} ms on 2 threads, pss $pss MB")
      out.put(model.nameWithoutExtension, JSONObject().put("load_ms", loadMs).put("image_ms", stats(times)).put("pss_mb", pss))
    }
    return out
  }

  /**
   * How long a search waits for its quick-look vector: a fresh ClipEncoder exactly as the engine builds it (the tokenizer read
   * from the APK, the text session created on the first query), so the first query is the cold cost a user pays if the search
   * box's prewarm hasn't run, then warm queries. Repeated after a cold load done by warmText() (what prewarm does).
   */
  private fun queryLatency(): JSONObject {
    val queries = listOf("dog on the beach", "receipt from the supermarket", "wedding photos at the church", "a red car parked outside",
      "children playing football", "birthday cake with candles", "screenshot of a bank message", "sunset over the lake", "my passport photo",
      "people dancing at a party", "a plate of food")
    fun encoder() = com.craneai.findr.engine.ClipEncoder(
      File(filesDir, "models/${com.craneai.findr.engine.QuickLookPack.DIR}"),
      lazy { com.craneai.findr.engine.BundledAssets.open(com.craneai.findr.engine.ContextAssets(this), com.craneai.findr.engine.ClipTokenizer.ASSET).use { com.craneai.findr.engine.ClipTokenizer.fromGzip(it) } },
      scope,
    )
    val out = JSONObject()
    kotlinx.coroutines.runBlocking {
      val cold = encoder()
      val t0 = SystemClock.elapsedRealtimeNanos()
      cold.query(queries[0])
      val coldMs = ms(t0)
      val warm = queries.drop(1).map { q -> val t = SystemClock.elapsedRealtimeNanos(); cold.query(q); ms(t) }
      cold.release()
      val pre = encoder()
      val t1 = SystemClock.elapsedRealtimeNanos()
      pre.warmText()
      val prewarmMs = ms(t1)
      val t2 = SystemClock.elapsedRealtimeNanos()
      pre.query(queries[0])
      val afterPrewarmMs = ms(t2)
      pre.release()
      say("query latency: cold ${coldMs.toInt()} ms, warm p50 ${stats(warm).optDouble("p50")} ms; prewarm ${prewarmMs.toInt()} ms, then ${afterPrewarmMs.toInt()} ms")
      out.put("cold_first_query_ms", coldMs).put("warm_ms", stats(warm)).put("prewarm_ms", prewarmMs).put("first_query_after_prewarm_ms", afterPrewarmMs)
    }
    return out
  }

  /**
   * Gate a (D2): what the quick-look lane costs EmbeddingGemma, with the app's own code. Each EmbeddingGemma "photo" is the
   * index worker's per-photo work (FileProcessor.process: decode, ML Kit OCR, the GPU image embedding); the quick-look lane
   * is QuickLookPass's per-photo work (its decode, ClipEncoder on 2 threads) on its own nice-5 thread, cycling over the same
   * pictures. Arms: alone, beside the lane, alone again (the A06 drifts as it heats, so the two "alone" arms bracket it).
   */
  private fun d2Cost(base: File, dirName: String, n: Int, lanes: List<LaneSpec>): JSONObject {
    val engine = (application as com.craneai.findr.FindrApp).engine as com.craneai.findr.engine.FindrEngine
    check(engine.clip.available() || lanes.all { it.kind == "priv" }) { "quick-look models are not installed" }
    val scorerFile = File(filesDir, "models/${com.craneai.findr.engine.PrivatePack.DIR}/${com.craneai.findr.engine.PrivatePack.MODEL}")
    check(scorerFile.isFile || lanes.none { it.kind == "priv" || it.kind == "shared" || it.kind == "both" }) { "the privacy pack is not installed" }
    val files = File(base, "bench/$dirName").listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
      .orEmpty().sortedBy { it.name }
    check(files.size >= 3) { "need pictures in bench/$dirName" }
    val processor = com.craneai.findr.engine.FileProcessor(this, engine.embedder)
    fun photo(i: Int): com.craneai.findr.core.IndexedFile {
      val f = files[i % files.size]
      return com.craneai.findr.core.IndexedFile(i.toLong(), android.net.Uri.fromFile(f).toString(), "bench/", f.name,
        com.craneai.findr.core.FileKind.IMAGE, "image/jpeg", f.length(), f.lastModified())
    }
    fun arm(from: Int): List<Double> = (0 until n).map { i ->
      val t0 = SystemClock.elapsedRealtimeNanos()
      kotlinx.coroutines.runBlocking { processor.process(photo(from + i)) }
      ms(t0)
    }
    val sampler = MetricsSampler(this)
    kotlinx.coroutines.runBlocking { processor.process(photo(0)) }   // loads the GPU engine (~10 s), untimed
    sampler.start(scope)
    val alone = arm(1)
    say("d2cost: alone p50 ${stats(alone).optDouble("p50")} ms")
    // A/B/A per lane: an "alone" arm before it and one after it, so each lane is compared with the clock of its own neighbours
    // (the A06 drifts as it heats), not with a single bracket around all of them.
    class Lane(val spec: LaneSpec, val before: List<Double>, val beside: List<Double>, val photos: Int, val photoMs: List<Double>, val wallMs: Double, val cpuset: String) {
      var after: List<Double> = emptyList()
    }
    val aloneArms = arrayListOf(alone)
    var nextFrom = 1 + n
    fun aloneNext(): List<Double> = arm(nextFrom).also { nextFrom += n; say("d2cost: alone p50 ${stats(it).optDouble("p50")} ms") }
    val results = ArrayList<Lane>()
    for (spec in lanes) {
      if (results.isNotEmpty()) aloneArms += aloneNext()
      val closers = ArrayList<() -> Unit>()
      val uriOf = { f: File -> android.net.Uri.fromFile(f) }
      // One lane step = one picture, decoded as the real pass decodes it, then the model(s). Sessions open lazily on the lane's own
      // thread, so the ONNX Runtime pool inherits its nice level.
      fun qlStep(): (File) -> Boolean {
        val encoder = com.craneai.findr.engine.ClipEncoder(File(filesDir, "models/${com.craneai.findr.engine.QuickLookPack.DIR}"), lazyOf(null), scope, spec.threads)
        closers += { encoder.releaseImage() }
        return { f ->
          val bmp = com.craneai.findr.engine.QuickLookPass.decode(this@BenchActivity, uriOf(f))
          if (bmp != null) { encoder.image(bmp); bmp.recycle() }
          bmp != null
        }
      }
      fun privStep(): (File) -> Boolean {
        val scorer = com.craneai.findr.engine.PrivateScorer(scorerFile)
        closers += { scorer.release() }
        return { f ->
          val bmp = com.craneai.findr.engine.PrivacyPass.decode(this@BenchActivity, uriOf(f))
          if (bmp != null) { scorer.score(bmp); bmp.recycle() }
          bmp != null
        }
      }
      fun sharedStep(): (File) -> Boolean {
        val encoder = com.craneai.findr.engine.ClipEncoder(File(filesDir, "models/${com.craneai.findr.engine.QuickLookPack.DIR}"), lazyOf(null), scope, spec.threads)
        val scorer = com.craneai.findr.engine.PrivateScorer(scorerFile)
        closers += { encoder.releaseImage(); scorer.release() }
        return { f ->
          val bmp = com.craneai.findr.engine.QuickLookPass.decode(this@BenchActivity, uriOf(f))   // one decode feeds both models
          if (bmp != null) { encoder.image(bmp); scorer.score(bmp); bmp.recycle() }
          bmp != null
        }
      }
      val steps = when (spec.kind) {
        "ql" -> listOf(qlStep())
        "priv" -> listOf(privStep())
        "shared" -> listOf(sharedStep())
        "both" -> listOf(qlStep(), privStep())
        else -> error("unknown lane kind ${spec.kind}")
      }
      val executors = ArrayList<java.util.concurrent.ExecutorService>()
      val done = java.util.concurrent.atomic.AtomicInteger()
      val running = java.util.concurrent.atomic.AtomicBoolean(true)
      val laneMs = java.util.Collections.synchronizedList(ArrayList<Double>())
      var cpuset = "?"
      // "both": the quick-look lane as shipped (its own duty) beside the privacy lane at the spec's duty.
      val duties = if (spec.kind == "both") listOf(com.craneai.findr.engine.QuickLookPass.BUSY_DUTY, spec.duty) else listOf(spec.duty)
      val futures = steps.mapIndexed { k, step ->
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(spec.priority); r.run() }, "d2lane$k") }
        executors += executor
        executor.submit(Runnable {
          if (k == 0) cpuset = runCatching { File("/proc/self/task/${android.os.Process.myTid()}/cpuset").readText().trim() }.getOrDefault("?")
          var i = k * 7
          while (running.get()) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            if (!step(files[i++ % files.size])) continue
            if (k == 0) { laneMs += ms(t0); done.incrementAndGet() }
            if (duties[k] < 1.0) Thread.sleep((ms(t0) * (1 / duties[k] - 1)).toLong())   // part time, as the passes while EmbeddingGemma embeds
          }
        })
      }
      while (done.get() == 0) Thread.sleep(50)   // the lane's session(s) are loaded and running
      val before = done.get()
      val t0 = SystemClock.elapsedRealtimeNanos()
      val beside = arm(nextFrom); nextFrom += n
      val laneDone = done.get() - before
      val wall = ms(t0)
      running.set(false)
      futures.forEach { it.get() }
      closers.forEach { it() }
      executors.forEach { it.shutdown() }
      say("d2cost: lane ${spec.label}: EG p50 ${stats(beside).optDouble("p50")} ms, lane $laneDone photos ($cpuset)")
      results += Lane(spec, aloneArms.last(), beside, laneDone, laneMs.toList(), wall, cpuset)
    }
    aloneArms += aloneNext()
    results.forEachIndexed { i, l -> l.after = aloneArms[i + 1] }
    val mem = sampler.stop()
    fun median(x: List<Double>) = x.sorted().let { if (it.isEmpty()) Double.NaN else (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    val arms = JSONArray()
    for (l in results) {
      val around = l.before + l.after
      val slowdownMean = l.beside.average() / around.average() - 1
      val slowdownP50 = median(l.beside) / median(around) - 1
      val driftPct = (l.after.average() / l.before.average() - 1) * 100
      say("d2cost: ${l.spec.label}: slowdown mean ${"%+.1f".format(slowdownMean * 100)}%, p50 ${"%+.1f".format(slowdownP50 * 100)}% " +
        "(alone before ${"%.0f".format(l.before.average())}, after ${"%.0f".format(l.after.average())} ms, drift ${"%+.1f".format(driftPct)}%), " +
        "${"%.1f".format(l.photos.toDouble() / n)} lane photos per EG photo")
      arms.put(JSONObject().put("lane", l.spec.label).put("kind", l.spec.kind).put("priority", l.spec.priority).put("threads", l.spec.threads).put("duty", l.spec.duty)
        .put("cpuset", l.cpuset).put("alone_before_ms", stats(l.before)).put("beside_ms", stats(l.beside)).put("alone_after_ms", stats(l.after))
        .put("slowdown_mean", slowdownMean).put("slowdown_p50", slowdownP50).put("drift_pct", driftPct)
        .put("lane_photos", l.photos).put("lane_per_eg_photo", l.photos.toDouble() / n)
        .put("lane_per_hour", l.photos * 3_600_000.0 / l.wallMs).put("lane_photo_ms", stats(l.photoMs)))
    }
    return JSONObject().put("n", n).put("pictures", files.size)
      .put("alone_arms", JSONArray(aloneArms.map { stats(it) })).put("lanes", arms)
      .put("peak_pss_mb", mem.peakPssMb).put("min_avail_mb", mem.minAvailMemMb)
  }

  /**
   * Gate a for videos with speech (the vision agent's finding: a quick-look lane cost EG +13% per frame while a soundtrack's
   * speech recognition ran). EG arms: every video in bench/<dir> through FileProcessor.process with speech on, as the worker
   * runs it; the cost is EG's embed seconds per distinct frame, read from each video's own detail line. The lane cycles over
   * the pictures in the same folder. Lanes: "priority:threads:duty[:p]", p = wait while speech recognition runs (the rule
   * QuickLookPass ships). Arms: alone, each lane, alone again.
   */
  private fun d2CostVideo(base: File, dirName: String, lanes: List<String>): JSONObject {
    val engine = (application as com.craneai.findr.FindrApp).engine as com.craneai.findr.engine.FindrEngine
    check(engine.clip.available()) { "quick-look models are not installed" }
    check(engine.asr.available()) { "speech models are not installed" }
    val all = File(base, "bench/$dirName").listFiles().orEmpty().sortedBy { it.name }
    val videos = all.filter { it.extension.lowercase() == "mp4" }
    val pictures = all.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
    check(videos.isNotEmpty() && pictures.isNotEmpty()) { "need videos and pictures in bench/$dirName" }
    val speech = java.util.concurrent.atomic.AtomicInteger()
    val processor = com.craneai.findr.engine.FileProcessor(this, engine.embedder, asr = engine.asr, speechActive = speech)
    val frameLine = Regex("""(\d+) frames, (\d+) distinct \(sample [\d.]+ s embed ([\d.]+) s""")
    fun video(f: File): com.craneai.findr.core.IndexedFile {
      val ms = runCatching {
        android.media.MediaMetadataRetriever().let { r -> try { r.setDataSource(f.path); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } finally { r.release() } }
      }.getOrNull()
      return com.craneai.findr.core.IndexedFile(f.name.hashCode().toLong(), android.net.Uri.fromFile(f).toString(), "bench/", f.name,
        com.craneai.findr.core.FileKind.VIDEO, "video/mp4", f.length(), f.lastModified(), durationMs = ms)
    }
    class Arm(val embedSec: Double, val frames: Int, val wallMs: Double, val speechMs: Double) { val perFrame get() = embedSec / maxOf(frames, 1) }
    fun arm(): Arm {
      var embed = 0.0; var frames = 0; var speechMs = 0.0
      val t0 = SystemClock.elapsedRealtimeNanos()
      for (v in videos) {
        val r = kotlinx.coroutines.runBlocking { processor.process(video(v), withSpeech = true) }
        frameLine.find(r.detail.orEmpty())?.let { m -> frames += m.groupValues[2].toInt(); embed += m.groupValues[3].toDouble() }
        Regex("""speech ([\d.]+) s""").find(r.detail.orEmpty())?.let { speechMs += it.groupValues[1].toDouble() * 1000 }
      }
      return Arm(embed, frames, ms(t0), speechMs)
    }
    val sampler = MetricsSampler(this)
    kotlinx.coroutines.runBlocking { processor.process(video(videos.first()), withSpeech = true) }   // loads EG and the speech models, untimed
    sampler.start(scope)
    val alone = arm()
    say("d2costv: alone ${"%.2f".format(alone.perFrame)} s per frame (${alone.frames} frames, speech ${(alone.speechMs / 1000).toInt()} s)")
    val results = JSONArray()
    for (spec in lanes) {
      val p = spec.split(":")
      val priority = p[0].toInt(); val threads = p[1].toInt(); val duty = p.getOrNull(2)?.toDouble() ?: 1.0; val pause = p.getOrNull(3) == "p"
      val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread({ android.os.Process.setThreadPriority(priority); r.run() }, "d2lane") }
      val encoder = com.craneai.findr.engine.ClipEncoder(File(filesDir, "models/${com.craneai.findr.engine.QuickLookPack.DIR}"), lazyOf(null), scope, threads)
      val done = java.util.concurrent.atomic.AtomicInteger()
      val running = java.util.concurrent.atomic.AtomicBoolean(true)
      val lane = executor.submit {
        var i = 0
        while (running.get()) {
          if (pause) while (speech.get() > 0 && running.get()) Thread.sleep(100)
          if (!running.get()) break
          val t0 = SystemClock.elapsedRealtimeNanos()
          val bmp = com.craneai.findr.engine.QuickLookPass.decode(this@BenchActivity, android.net.Uri.fromFile(pictures[i++ % pictures.size])) ?: continue
          encoder.image(bmp)
          bmp.recycle()
          done.incrementAndGet()
          if (duty < 1.0) Thread.sleep((ms(t0) * (1 / duty - 1)).toLong())
        }
      }
      while (done.get() == 0) Thread.sleep(50)
      val before = done.get()
      val beside = arm()
      val laneDone = done.get() - before
      running.set(false)
      lane.get()
      encoder.releaseImage()
      executor.shutdown()
      say("d2costv: lane $spec: ${"%.2f".format(beside.perFrame)} s per frame, lane $laneDone photos")
      results.put(JSONObject().put("lane", spec).put("eg_s_per_frame", beside.perFrame).put("frames", beside.frames)
        .put("wall_ms", beside.wallMs).put("lane_photos", laneDone).put("lane_per_eg_frame", laneDone.toDouble() / maxOf(beside.frames, 1)))
    }
    val again = arm()
    val mem = sampler.stop()
    val base2 = (alone.perFrame + again.perFrame) / 2
    say("d2costv: alone again ${"%.2f".format(again.perFrame)} s per frame")
    for (i in 0 until results.length()) {
      val r = results.getJSONObject(i)
      val slowdown = r.getDouble("eg_s_per_frame") / base2 - 1
      r.put("slowdown_vs_both_alone", slowdown)
      say("d2costv: ${r.getString("lane")}: EG per frame ${"%+.1f".format(slowdown * 100)}%, ${"%.1f".format(r.getDouble("lane_per_eg_frame"))} quick per EG frame")
    }
    return JSONObject().put("videos", JSONArray(videos.map { it.name })).put("pictures", pictures.size)
      .put("alone_s_per_frame", alone.perFrame).put("alone_again_s_per_frame", again.perFrame).put("alone_frames", alone.frames)
      .put("alone_speech_ms", alone.speechMs).put("lanes", results).put("peak_pss_mb", mem.peakPssMb).put("min_avail_mb", mem.minAvailMemMb)
  }

  private fun device(): JSONObject {
    val mem = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
    return JSONObject().put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
      .put("soc", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
      .put("ram_total_mb", mem.totalMem / (1024 * 1024))
  }

  private fun say(line: String) {
    Log.i(TAG, line)
    runOnUiThread { log.append(line + "\n") }
  }

  /** Public-domain prose cut into chunks of about N tokens (≈4 chars/token for English with Gemma's tokenizer). */
  private class Corpus(file: File) {
    private val text: String =
      file.readText().let { raw ->
        val start = raw.indexOf("*** START OF").let { if (it < 0) 0 else raw.indexOf('\n', it) + 1 }
        val end = raw.indexOf("*** END OF").let { if (it < 0) raw.length else it }
        raw.substring(start, end).replace(Regex("\\s+"), " ").trim()
      }

    fun chunk(tokens: Int, k: Int): String {
      val chars = tokens * 4
      val offset = ((k.toLong() * 7919 * 13) % (text.length - chars)).toInt()
      return "title: none | text: " + text.substring(offset, offset + chars)
    }
  }
}

/** org.json refuses NaN: an unknown reading is written as null. */
/** One d2cost lane: `priority:threads[:duty[:kind]]` (see Args.costLanes). */
private data class LaneSpec(val priority: Int, val threads: Int, val duty: Double, val kind: String) {
  val label: String get() = "$kind nice $priority/$threads thr/duty $duty"
}

private fun num(x: Double): Any = if (x.isNaN() || x.isInfinite()) JSONObject.NULL else x

private fun ms(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1e6

private fun norm(v: FloatArray) = sqrt(v.fold(0.0) { a, x -> a + x * x })

private fun cos(a: FloatArray, b: FloatArray): Double {
  var dot = 0.0
  for (i in a.indices) dot += a[i] * b[i]
  return dot / (norm(a) * norm(b))
}

private fun stats(ms: List<Double>): JSONObject {
  if (ms.isEmpty()) return JSONObject().put("n", 0)
  val s = ms.sorted()
  fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
  return JSONObject().put("n", s.size).put("mean", s.average()).put("p50", pct(0.5)).put("p90", pct(0.9))
    .put("min", s.first()).put("max", s.last())
}
