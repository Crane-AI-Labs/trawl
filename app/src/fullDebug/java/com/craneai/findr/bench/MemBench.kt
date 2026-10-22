package com.craneai.findr.bench

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The low-RAM memory budget (`--es mem <parts>`): what each part of Findr costs in memory on this phone, measured in a fresh
 * process per run, so parts can be added up and the low-memory killer's margin read off directly.
 *
 * Parts (comma-separated; several are loaded together and used in turn, as the index worker would):
 *  - `idle`     the bench app's own process (the app's engine object, no model)
 *  - `eg`       EmbeddingGemma on LiteRT-LM: `--es model`, `--es maxlen`, `--es vision none|gpu|cpu`, `--es vtok`, `--es dim`;
 *               the app's own 400-char chunks of bench/corpus.txt (`--es ntext`), short queries, `--es nimg` pictures
 *  - `appeg`    the app's own [com.craneai.findr.engine.Embedder] (whatever profile the build picks), text then images
 *  - `ocr`      ML Kit text recognition on bench/ocr-images (`--es nocr`)
 *  - `onnx`     a quick-look model on ONNX Runtime with the app's session options: `--es onnx_image`, `--es onnx_text` (paths
 *               under files/), image tower on 2 threads over bench/images, text tower on 4 threads over short queries
 *  - `asr`      the app's speech engine (sherpa-onnx, files/models/asr) over bench/videos (`--es nasr`)
 *  - `vecidx`   the search index over a real synthetic store of `--es nvec` EmbeddingGemma text vectors (768-d, three to a file):
 *               `--es vmode build|heap|mapped`; `build` writes the store under files/bench/vec-<n> (once; minutes), `heap` is the
 *               index with its vectors on the Java heap (the previous design), `mapped` keeps them in a memory-mapped file
 *               (`--es vfresh 1` deletes the file first: a cold start that rebuilds; otherwise it is adopted if settled).
 *               Reports cold start to the first search, Java heap, and keystroke-to-results p50/p90 through the real Searcher.
 *  - `vectors`  the search matrix: `--es nvec` vectors of `--es vdim` dims in a [com.craneai.findr.search.VectorIndex]
 *
 * Every 300 ms: total PSS with its breakdown (Java heap, native heap, code, graphics, other), RssAnon / RssFile / VmSwap from
 * /proc/self/status (anonymous memory is what the kernel can't drop, only compress into zram), and the system's
 * availMem against ActivityManager's own low-memory threshold (where it starts killing background work).
 */
object MemBench {
  fun run(context: Context, base: File, cacheDir: File, scope: CoroutineScope, arg: (String) -> String?, say: (String) -> Unit): JSONObject {
    val parts = (arg("mem") ?: "idle").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val sampler = MemSampler(context)
    val out = JSONObject().put("parts", JSONArray(parts))
    out.put("before", sampler.sample().json())
    sampler.start(scope)
    val loaded = mutableListOf<Part>()
    val marks = JSONObject()
    try {
      for (p in parts) {
        val part = when (p) {
          "idle" -> Idle()
          "eg" -> Eg(base, cacheDir, arg)
          "appeg" -> AppEg(context, base)
          "appfiles" -> AppFiles(context, base, arg("nfiles")?.toIntOrNull() ?: 10)
          "ocr" -> Ocr(base, arg("nocr")?.toIntOrNull() ?: 20)
          "onnx" -> Onnx(context, base, arg)
          "asr" -> Asr(context, base, arg("nasr")?.toIntOrNull() ?: 2, arg("asr_media"), arg("asr_windows")?.toIntOrNull() ?: 6, arg("asr_win_s")?.toIntOrNull() ?: 60)
          "priv" -> Priv(context, base, arg("npriv")?.toIntOrNull() ?: 20)
          "vecidx" -> VecIdx(base, arg("nvec")?.toIntOrNull() ?: 10_000, arg("vmode") ?: "heap", arg("vfresh") == "1", arg("nq")?.toIntOrNull() ?: 120, arg("vts"))
          "vectors" -> Vectors(arg("nvec")?.toIntOrNull() ?: 20_000, arg("vdim")?.toIntOrNull() ?: 768)
          else -> error("unknown part $p")
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        part.load()
        marks.put("$p.loaded", sampler.sample().json().put("load_ms", ms(t0)))
        say("$p loaded in ${ms(t0).toInt()} ms, PSS ${"%.0f".format(sampler.sample().pssMb)} MB")
        loaded += part
      }
      // Use every loaded part in turn, twice over: the worst case is everything resident and warmed up at once.
      repeat(2) { round ->
        for ((i, part) in loaded.withIndex()) {
          val t0 = SystemClock.elapsedRealtimeNanos()
          val work = part.work()
          marks.put("${parts[i]}.work$round", sampler.sample().json().put("work_ms", ms(t0)).put("work", work))
          say("${parts[i]} round $round: $work, PSS ${"%.0f".format(sampler.sample().pssMb)} MB")
        }
      }
      Thread.sleep(1_000)
    } finally {
      val peak = sampler.stop()
      out.put("peak", peak)
      for (part in loaded.asReversed()) runCatching { part.close() }
      System.gc()
      Thread.sleep(1_500)
      out.put("after_close", sampler.sample().json())
    }
    return out.put("marks", marks)
  }

  private interface Part {
    fun load()
    fun work(): JSONObject
    fun close()
  }

  private class Idle : Part {
    override fun load() = Thread.sleep(3_000)
    override fun work() = JSONObject()
    override fun close() {}
  }

  /** EmbeddingGemma the way the app runs it: text on CPU, vision executor per `vision` (the app's idle CPU one for text only). */
  private class Eg(private val base: File, private val cacheDir: File, private val arg: (String) -> String?) : Part {
    private val model = arg("model") ?: "embeddinggemma-2-text-270m.litertlm"
    private val vision = arg("vision") ?: "none"
    private val vtok = arg("vtok")?.toIntOrNull()
    private val nText = arg("ntext")?.toIntOrNull() ?: 30
    private val nImg = arg("nimg")?.toIntOrNull() ?: 0
    private val long = arg("long") == "1"
    private val longN = arg("longn")?.toIntOrNull() ?: 8
    private val nAudio = arg("naudio")?.toIntOrNull() ?: 0   // 10 s windows (bench/audio10/*.wav) through the audio encoder on the CPU
    private val opts = EmbeddingOptions(normalize = true, outputSize = arg("dim")?.toIntOrNull(), visionTokensPerImage = vtok)
    private var engine: EmbeddingEngine? = null
    private val chunks by lazy {
      val raw = File(base, "bench/corpus.txt").readText()
      com.craneai.findr.engine.Chunker.chunks(raw.substring(raw.length / 3)).take(400)
    }
    private var k = 0

    override fun load() {
      val multimodal = "text-270m" !in model
      val vb = when (vision) {
        "gpu" -> Backend.GPU()
        "cpu" -> Backend.CPU()
        else -> if (multimodal) Backend.CPU() else null
      }
      engine = EmbeddingEngine(
        EmbeddingEngineConfig(
          modelPath = File(base, "models/$model").absolutePath,
          backend = Backend.CPU(),
          visionBackend = vb,
          audioBackend = if (nAudio > 0) Backend.CPU(threadCount = Runtime.getRuntime().availableProcessors()) else null,
          cacheDir = cacheDir.absolutePath,
          maxInputLength = arg("maxlen")?.toIntOrNull(),
          visionTokensPerImage = vtok,
          activationDataType = arg("act")?.let { com.google.ai.edge.litertlm.ActivationDataType.valueOf(it) },
        )
      ).also { it.initialize() }
    }

    override fun work(): JSONObject {
      val e = engine!!
      val text = ArrayList<Double>()
      repeat(nText) {
        val t = SystemClock.elapsedRealtimeNanos()
        e.computeEmbedding(listOf(InputData.Text("title: none | text: " + chunks[k++ % chunks.size])), opts)
        text += ms(t)
      }
      // Parity: a fixed sample (the first 20 chunks, the first 8 images) as raw float32 in results/<tag>.vec, compared offline.
      if (arg("vecdump") == "1") {
        val out = java.io.DataOutputStream(java.io.BufferedOutputStream(File(base, "results/${arg("tag")}.vec").outputStream()))
        fun put(v: FloatArray) { out.writeInt(v.size); for (x in v) out.writeFloat(x) }
        for (i in 0 until 20) put(e.computeEmbedding(listOf(InputData.Text("title: none | text: " + chunks[i])), opts).embedding)
        if (vision != "none") File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(8)?.forEach { f ->
          put(e.computeEmbedding(listOf(InputData.Image(jpeg(f, 768))), opts).embedding)
        }
        out.close()
      }
      val query = ArrayList<Double>()
      for (q in QUERIES) {
        val t = SystemClock.elapsedRealtimeNanos()
        e.computeEmbedding(listOf(InputData.Text("task: search result | query: $q")), opts)
        query += ms(t)
      }
      // A long input (a transcript or an unchunked text): does a bigger sequence bucket grow memory, and does maxlen cap it?
      val longMs: Any? = if (long) {
        val t = SystemClock.elapsedRealtimeNanos()
        try {
          e.computeEmbedding(listOf(InputData.Text("title: none | text: " + chunks.take(longN).joinToString(" "))), opts)
          ms(t)
        } catch (x: Exception) {
          "error: $x"
        }
      } else null
      val img = ArrayList<Double>()
      if (nImg > 0 && vision != "none") {
        val files = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(nImg).orEmpty()
        for (f in files) {
          val jpeg = jpeg(f, 768)
          val t = SystemClock.elapsedRealtimeNanos()
          e.computeEmbedding(listOf(InputData.Image(jpeg)), opts)
          img += ms(t)
        }
      }
      val audio = ArrayList<Double>()
      if (nAudio > 0) {
        val wavs = File(base, "bench/audio10").listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        repeat(nAudio) { i ->
          val t = SystemClock.elapsedRealtimeNanos()
          e.computeEmbedding(listOf(InputData.Audio(wavs[i % wavs.size].readBytes())), opts)
          audio += ms(t)
        }
      }
      return JSONObject().put("text_ms", stats(text)).put("query_ms", stats(query)).put("image_ms", stats(img))
        .put("audio_ms", stats(audio)).put("long_ms", longMs ?: JSONObject.NULL)
    }

    override fun close() { engine?.close(); engine = null }
  }

  /** The app's own Embedder (its profile decides the model file and the levels): what the app will really hold. */
  private class AppEg(private val context: Context, private val base: File) : Part {
    private val e = com.craneai.findr.engine.LiteRtEmbedder(context)
    private val chunks by lazy {
      val raw = File(base, "bench/corpus.txt").readText()
      com.craneai.findr.engine.Chunker.chunks(raw.substring(raw.length / 3)).take(200)
    }
    private var k = 0
    override fun load() { runBlocking { e.warm() } }
    override fun work(): JSONObject = runBlocking {
      val text = ArrayList<Double>()
      repeat(20) {
        val t = SystemClock.elapsedRealtimeNanos(); e.document(chunks[k++ % chunks.size]); text += ms(t)
      }
      for (q in QUERIES) { val t = SystemClock.elapsedRealtimeNanos(); e.query(q); text += ms(t) }
      val img = ArrayList<Double>()
      if (e.imagesByEmbeddingGemma) {
        File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(5)?.forEach { f ->
          val jpeg = jpeg(f, 768); val t = SystemClock.elapsedRealtimeNanos(); e.image(jpeg); img += ms(t)
        }
      }
      JSONObject().put("model_file", e.modelFile?.name).put("text_ms", stats(text)).put("image_ms", stats(img))
    }
    override fun close() = runBlocking { e.release() }
  }

  /**
   * The app's own path, whole: the engine's Embedder (with this process's memory profile) and its quick-look model, through
   * FileProcessor, over pictures, screenshots and a document, the way the index worker reads them. Standard: EmbeddingGemma
   * text CPU + vision GPU, OCR beside it. Lite: OCR, the text's vectors, the quick-look picture vector, one model at a time.
   */
  private class AppFiles(private val context: Context, private val base: File, private val n: Int) : Part {
    private val engine = (context.applicationContext as com.craneai.findr.FindrApp).engine as com.craneai.findr.engine.FindrEngine
    private val processor = com.craneai.findr.engine.FileProcessor(context, engine.embedder, quick = engine.liteQuickImages)
    private var id = 1L
    private fun indexed(f: File, kind: com.craneai.findr.core.FileKind, mime: String) = com.craneai.findr.core.IndexedFile(
      id++, Uri.fromFile(f).toString(), "bench/", f.name, kind, mime, f.length(), f.lastModified())

    override fun load() { runBlocking { engine.embedder.warm() } }

    override fun work(): JSONObject = runBlocking {
      val doc = File(base, "bench/doc.txt").apply { if (!isFile) writeText(File(base, "bench/corpus.txt").readText().take(24_000)) }
      val files = listOf(indexed(doc, com.craneai.findr.core.FileKind.DOCUMENT, "text/plain")) +
        File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(n).orEmpty()
          .map { indexed(it, com.craneai.findr.core.FileKind.IMAGE, "image/jpeg") } +
        File(base, "bench/ocr-images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(n).orEmpty()
          .map { indexed(it, com.craneai.findr.core.FileKind.SCREENSHOT, "image/png") }
      val times = JSONObject()
      val modalities = HashMap<String, Int>()
      for (f in files) {
        val t = SystemClock.elapsedRealtimeNanos()
        val r = processor.process(f)
        times.put(f.name, ms(t))
        r.pieces.filter { it.vector != null }.forEach { modalities.merge(it.modality.name, 1, Int::plus) }
      }
      JSONObject().put("profile", engine.profile.name).put("model_file", engine.embedder.modelFile?.name)
        .put("quick_ready", engine.liteQuickImages.ready).put("vectors_by_modality", JSONObject(modalities as Map<*, *>)).put("ms", times)
    }

    override fun close() {
      runBlocking { engine.embedder.release() }
      engine.clip.releaseImage()
    }
  }

  private class Ocr(private val base: File, private val n: Int) : Part {
    private val ocr = com.craneai.findr.extract.MlKitOcrEngine()
    override fun load() {}
    override fun work(): JSONObject = runBlocking {
      val files = File(base, "bench/ocr-images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(n).orEmpty()
      val times = ArrayList<Double>()
      var chars = 0
      for (f in files) {
        val bmp = BitmapFactory.decodeFile(f.path) ?: continue
        val t = SystemClock.elapsedRealtimeNanos()
        chars += ocr.read(bmp).length
        times += ms(t)
        bmp.recycle()
      }
      JSONObject().put("ocr_ms", stats(times)).put("chars", chars)
    }
    override fun close() {}
  }

  /** A quick-look model with ClipEncoder's own session options (image tower 2 threads, text tower 4, no spinning). */
  private class Onnx(private val context: Context, private val base: File, private val arg: (String) -> String?) : Part {
    private val env = com.craneai.findr.engine.ClipEncoder.environment()
    private var image: ai.onnxruntime.OrtSession? = null
    private var text: ai.onnxruntime.OrtSession? = null
    private fun session(path: String, threads: Int) = env.createSession(File(base, path).absolutePath,
      ai.onnxruntime.OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads); setInterOpNumThreads(1)
        setExecutionMode(ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        // Memory levers to compare (`--es ort_arena 0`, `--es ort_pattern 0`): the CPU arena keeps its peak allocation for the
        // session's life; memory patterns pre-plan (and hold) every intermediate buffer.
        if (arg("ort_arena") == "0") setCPUArenaAllocator(false)
        if (arg("ort_pattern") == "0") setMemoryPatternOptimization(false)
      })
    override fun load() {
      arg("onnx_image")?.let { image = session(it, 2) }
      arg("onnx_text")?.let { text = session(it, 4) }
    }
    override fun work(): JSONObject {
      val out = JSONObject()
      image?.let { s ->
        val files = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(20).orEmpty()
        val times = ArrayList<Double>()
        for (f in files) {
          val bmp = com.craneai.findr.engine.QuickLookPass.decode(context, Uri.fromFile(f)) ?: continue
          val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
          val x = com.craneai.findr.engine.ClipImage.prepare(argb, bmp.width, bmp.height)
          bmp.recycle()
          val t = SystemClock.elapsedRealtimeNanos()
          ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(x), longArrayOf(1, 3, 224, 224)).use { input ->
            s.run(mapOf("pixel_values" to input)).use { }
          }
          times += ms(t)
        }
        out.put("image_ms", stats(times))
      }
      text?.let { s ->
        val tok = com.craneai.findr.engine.BundledAssets.open(com.craneai.findr.engine.ContextAssets(context), com.craneai.findr.engine.ClipTokenizer.ASSET)
          .use { com.craneai.findr.engine.ClipTokenizer.fromGzip(it) }
        val times = ArrayList<Double>()
        for (q in QUERIES) {
          val t = SystemClock.elapsedRealtimeNanos()
          ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(tok.encode(q)), longArrayOf(1, 77)).use { input ->
            s.run(mapOf("input_ids" to input)).use { }
          }
          times += ms(t)
        }
        out.put("query_ms", stats(times))
      }
      return out
    }
    override fun close() { image?.close(); text?.close() }
  }

  /** The privacy scorer (nsfw-384, fp32 ONNX) with its own session options, so Tuning's ORT memory knobs apply. */
  private class Priv(private val context: Context, private val base: File, private val n: Int) : Part {
    private val scorer = com.craneai.findr.engine.PrivateScorer(context)
    override fun load() { check(scorer.available()) { "privacy model is not in files/models/private" } }
    override fun work(): JSONObject {
      val files = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(n).orEmpty()
      val times = ArrayList<Double>()
      val scores = JSONArray()
      for (f in files) {
        val bmp = BitmapFactory.decodeFile(f.path) ?: continue
        val t = SystemClock.elapsedRealtimeNanos()
        val p = scorer.score(bmp)
        times += ms(t)
        scores.put(Math.round(p * 1_000_000.0) / 1_000_000.0)
        bmp.recycle()
      }
      return JSONObject().put("priv_ms", stats(times)).put("scores", scores)
    }
    override fun close() = scorer.release()
  }

  /**
   * [media]: a long recording (an absolute path) read the way the worker does, in [windows] windows of [winS] s each (startMs moves on),
   * so the arena growth over a long recording shows. Without it, the short clips in bench/asr.
   */
  private class Asr(
    private val context: Context, private val base: File, private val n: Int,
    private val media: String? = null, private val windows: Int = 6, private val winS: Int = 60,
  ) : Part {
    private val asr = com.craneai.findr.extract.SherpaAsrEngine(File(context.filesDir, "models/asr"))
    override fun load() { check(asr.available()) { "speech models are not in files/models/asr" } }
    override fun work(): JSONObject = runBlocking {
      if (media != null) {
        val times = ArrayList<Double>()
        var chars = 0
        for (w in 0 until windows) {
          val t = SystemClock.elapsedRealtimeNanos()
          val tr = asr.transcribe(context, Uri.fromFile(File(media)), winS, w * winS * 1000L)
          times += ms(t)
          chars += tr.speech.sumOf { it.text.length }
          Log.i("FindrBench", "asr window $w: ${times.last().toLong()} ms, ${tr.speech.size} segments, ${tr.sounds.size} sound windows; cap ${com.craneai.findr.engine.Tuning.current.asrMaxSpeechS} s, segment s ${tr.speech.map { (it.endMs - it.startMs) / 1000 }}, chars ${tr.speech.map { it.text.length }}")
        }
        return@runBlocking JSONObject().put("asr_window_ms", stats(times)).put("windows", windows).put("chars", chars)
      }
      val files = File(base, "bench/asr").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(n).orEmpty()
      val times = ArrayList<Double>()
      var words = 0
      for (f in files) {
        val t = SystemClock.elapsedRealtimeNanos()
        val tr = asr.transcribe(context, Uri.fromFile(f), 120)
        times += ms(t)
        words += tr.speech.sumOf { it.text.split(' ').size }
      }
      JSONObject().put("asr_ms", stats(times)).put("files", files.size).put("words", words)
    }
    override fun close() = asr.release()
  }

  /** The whole search path over a store of [n] synthetic vectors, to compare the heap index with the mapped one. See the `vecidx` part. */
  private class VecIdx(
    private val base: File, private val n: Int, private val mode: String, private val fresh: Boolean, private val nq: Int,
    /** `stageDim:candidates:minRows:scaleSample` turns the two-stage scan on (mapped mode; its own file, so the exact file stays as it was); absent: exact. */
    private val twoStageArg: String? = null,
  ) : Part {
    private val dim = com.craneai.findr.index.Fp16.DIM
    private val dir = File(base, "bench/vec-$n/index")
    private val words = List(200) { "w$it" }
    private var store: com.craneai.findr.index.IndexStore? = null
    private var index: com.craneai.findr.search.VectorIndex? = null
    private var searcher: com.craneai.findr.search.Searcher? = null
    private val info = JSONObject()

    private fun unit(rnd: java.util.Random): FloatArray {
      val v = FloatArray(dim) { rnd.nextGaussian().toFloat() }
      var s = 0.0
      for (x in v) s += x * x
      val norm = Math.sqrt(s).toFloat()
      for (i in v.indices) v[i] /= norm
      return v
    }

    private fun build() {
      dir.deleteRecursively()
      val st = com.craneai.findr.index.IndexStore.open(dir, log = {})
      val rnd = java.util.Random(7)
      val files = (n + 2) / 3
      val t0 = SystemClock.elapsedRealtimeNanos()
      var id = 1L
      while (id <= files) {
        val batch = (id until minOf(id + 500, files + 1L)).map {
          com.craneai.findr.index.CrawlRecord("external_primary", it, "content://media/external/file/$it", "Documents/", "doc$it.txt",
            com.craneai.findr.core.FileKind.DOCUMENT, null, 1_000, 1_000)
        }
        st.upsertFromCrawl(batch)
        id += batch.size
      }
      val ids = st.knownMediaStoreIds("external_primary")
      var made = 0
      for (media in 1L..files) {
        val file = ids.getValue(media)
        val k = minOf(3, n - made)
        val segs = List(k) { com.craneai.findr.index.Segment(com.craneai.findr.index.SegmentKind.CHUNK, com.craneai.findr.index.Modality.TEXT,
          text = (0 until 6).joinToString(" ") { words[rnd.nextInt(words.size)] }, vector = unit(rnd)) }
        st.saveSegments(file, segs, com.craneai.findr.index.FileState.UNDERSTOOD)
        made += k
      }
      st.close()
      info.put("build_s", ms(t0) / 1000).put("built_vectors", made)
    }

    override fun load() {
      if (mode == "build") { build(); return }
      require(File(dir, com.craneai.findr.index.IndexStore.FILE_NAME).isFile) { "build the store first: --es vmode build --es nvec $n" }
      val twoStage = twoStageArg?.split(":")?.map { it.toInt() }?.let { com.craneai.findr.search.TwoStage(it[0], it[1], it[2], it.getOrElse(3) { 2048 }) }
      val vectorFile = File(dir, if (twoStage != null) "vectors-ts.bin" else "vectors.bin")
      info.put("two_stage", twoStage?.toString() ?: "off")
      if (mode == "mapped" && fresh) { vectorFile.delete() }
      System.gc()
      info.put("heap_before_mb", usedHeapMb()).put("heap_limit_mb", Runtime.getRuntime().maxMemory() / 1048576.0)
      val rnd = java.util.Random(99)
      val q0 = unit(rnd)
      val t0 = SystemClock.elapsedRealtimeNanos()
      try {
        val st = com.craneai.findr.index.IndexStore.open(dir, log = {})
        store = st
        val idx = com.craneai.findr.search.VectorIndex(backing = if (mode == "mapped") vectorFile else null, twoStage = if (mode == "mapped") twoStage else null)
        idx.load(st)
        index = idx
        val loaded = ms(t0)
        val sr = com.craneai.findr.search.Searcher(st, idx)
        searcher = sr
        val hits = sr.search("${words[3]} ${words[4]}", q0, com.craneai.findr.core.KindFilter.ALL, 20, prefixLast = true)
        info.put("load_ms", loaded).put("cold_to_first_search_ms", ms(t0)).put("first_hits", hits.hits.size)
        info.put("rows", idx.size).put("off_heap", idx.offHeap)
        if (mode == "mapped") info.put("settled_now", idx.settleNow())
      } catch (e: OutOfMemoryError) {
        info.put("oom", true).put("oom_after_ms", ms(t0))
        index = null; searcher = null
      }
    }

    override fun work(): JSONObject {
      val sr = searcher
      val idx = index
      if (sr == null || idx == null) return JSONObject(info.toString()).put("note", if (mode == "build") "built" else "no index (out of memory)")
      System.gc()
      val out = JSONObject(info.toString())
      val rnd = java.util.Random(5)
      val times = ArrayList<Double>()
      repeat(10 + nq) { i ->
        val q = unit(rnd)
        val text = "${words[rnd.nextInt(words.size)]} ${words[rnd.nextInt(words.size)]}"
        val t = SystemClock.elapsedRealtimeNanos()
        sr.search(text, q, com.craneai.findr.core.KindFilter.ALL, 20, prefixLast = true)
        if (i >= 10) times += ms(t)
      }
      out.put("phases_ms", phases(idx, sr))
      System.gc()
      return out.put("search_ms", stats(times)).put("heap_used_mb", usedHeapMb()).put("index_heap_mb", idx.heapBytes() / 1048576.0)
        .put("index_mapped_mb", idx.mappedBytes() / 1048576.0)
    }

    /** Median ms of the pieces a query is made of, in this process and on this phone right now: the full exact scan, and the two-stage scan's parts. */
    private fun phases(idx: com.craneai.findr.search.VectorIndex, sr: com.craneai.findr.search.Searcher): JSONObject {
      val q = unit(java.util.Random(6))
      fun median(reps: Int, block: () -> Unit): Double {
        repeat(3) { block() }
        val v = ArrayList<Double>()
        repeat(reps) { val t = SystemClock.elapsedRealtimeNanos(); block(); v += ms(t) }
        return v.sorted()[v.size / 2]
      }
      val out = JSONObject().put("exact_scan", median(9) { idx.scores(q) })
      store?.let { st -> out.put("store_stats", median(9) { st.stats() }); out.put("store_waiting", median(9) { st.waiting(com.craneai.findr.core.FileKind.entries.toSet()) }) }
      out.put("row_facts_loop", median(9) { idx.read { var a = 0L; for (r in 0 until idx.size) a += idx.modalityOrdinal(r) + idx.fileId(r) + (if (idx.isLate(r)) 1 else 0) + (if (idx.isStale(r)) 1 else 0); if (a == 42L) println(a) } })
      // A whole query with one half taken away: meaning only (no words), and words only (no vector). Their sum against search_ms shows where a keystroke goes.
      val all = com.craneai.findr.core.KindFilter.ALL
      out.put("search_meaning_only", median(9) { sr.search("", q, all, 20, prefixLast = true) })
      out.put("search_words_only", median(9) { sr.search("${words[11]} ${words[12]}", null, all, 20, prefixLast = true) })
      out.put("search_words_only_exact", median(9) { sr.search("${words[11]} ${words[12]}", null, all, 20, prefixLast = false) })
      val two = idx.twoStage ?: return out
      val allowed = BooleanArray(com.craneai.findr.core.FileKind.entries.size) { true }
      val n = idx.size
      val sample = IntArray(minOf(two.scaleSample, n)) { (it.toLong() * n / minOf(two.scaleSample, n)).toInt() }
      var candidates = IntArray(0)
      out.put("stage1", median(9) { candidates = idx.stageCandidates(q, allowed) })
      out.put("scale_sample_rows", sample.size).put("scale_sample", median(9) { idx.scoresAt(q, sample) })
      out.put("rescore_candidates", median(9) { idx.scoresAt(q, candidates) })
      return out
    }

    private fun usedHeapMb() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0

    override fun close() { index?.close(); store?.close(); index = null; store = null }
  }

  private class Vectors(private val n: Int, private val dim: Int) : Part {
    private var index: com.craneai.findr.search.VectorIndex? = null
    override fun load() {
      val idx = com.craneai.findr.search.VectorIndex(dim = dim, initialCapacity = 1024)
      val rnd = java.util.Random(7)
      val v = FloatArray(dim)
      for (i in 0 until n) {
        var s = 0.0
        for (j in 0 until dim) { v[j] = rnd.nextGaussian().toFloat(); s += v[j] * v[j] }
        val norm = Math.sqrt(s).toFloat()
        for (j in 0 until dim) v[j] /= norm
        idx.add(i.toLong(), (i / 3).toLong(), com.craneai.findr.core.FileKind.DOCUMENT, com.craneai.findr.index.Modality.TEXT, false, v)
      }
      index = idx
    }
    override fun work(): JSONObject {
      val idx = index!!
      val q = FloatArray(dim) { if (it == 0) 1f else 0f }
      val t = SystemClock.elapsedRealtimeNanos()
      repeat(5) { idx.scores(q) }
      return JSONObject().put("rows", idx.size).put("dim", dim).put("score_ms", ms(t) / 5).put("bytes_in_memory", idx.bytesInMemory())
    }
    override fun close() { index?.close(); index = null }
  }

  // ---- sampling -------------------------------------------------------------------------------------------------

  class Sample(
    val pssMb: Double,
    val breakdown: Map<String, Double>,
    val rssAnonMb: Double,
    val rssFileMb: Double,
    val swapMb: Double,
    val availMb: Double,
    val thresholdMb: Double,
    val lowMemory: Boolean,
    val smaps: JSONObject = JSONObject(),
  ) {
    fun json(): JSONObject = JSONObject().put("pss_mb", pssMb).put("rss_anon_mb", rssAnonMb).put("rss_file_mb", rssFileMb)
      .put("swap_mb", swapMb).put("avail_mb", availMb).put("threshold_mb", thresholdMb).put("low_memory", lowMemory)
      .put("breakdown_mb", JSONObject(breakdown as Map<*, *>)).put("smaps_mb", smaps)
  }

  class MemSampler(context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var job: Job? = null
    @Volatile private var peak: Sample? = null
    @Volatile private var peakAnon = 0.0
    @Volatile private var peakSwap = 0.0
    @Volatile private var minAvail = Double.MAX_VALUE
    @Volatile private var minMargin = Double.MAX_VALUE
    @Volatile private var low = false
    @Volatile private var samples = 0

    fun sample(): Sample {
      val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
      val stats = mi.memoryStats.mapValues { (it.value.toDoubleOrNull() ?: 0.0) / 1024.0 }
        .filterKeys { it.startsWith("summary.") }.mapKeys { it.key.removePrefix("summary.") }
      val status = File("/proc/self/status").readLines().associate { l -> l.substringBefore(':') to l.substringAfter(':').trim() }
      fun kb(k: String) = (status[k]?.substringBefore(' ')?.toDoubleOrNull() ?: 0.0) / 1024.0
      val m = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
      // smaps by group: pss / private dirty / private clean in MB (dirty anonymous is the real cost, clean file pages are cheap).
      val groups = JSONObject()
      try {
        val r = com.craneai.findr.diag.SmapsGroups.readSelf()
        for (g in r.groups.values) if (g.pssKb >= 1024 || g.privateDirtyKb >= 1024) {
          groups.put(g.name, JSONObject().put("pss", g.pssKb / 1024.0).put("dirty", g.privateDirtyKb / 1024.0)
            .put("clean", g.privateCleanKb / 1024.0).put("swap", g.swapKb / 1024.0))
        }
      } catch (_: Exception) {}
      return Sample(mi.totalPss / 1024.0, stats, kb("RssAnon"), kb("RssFile"), kb("VmSwap"),
        m.availMem / 1048576.0, m.threshold / 1048576.0, m.lowMemory, groups)
    }

    fun start(scope: CoroutineScope) {
      job = scope.launch {
        while (isActive) {
          val s = sample()
          synchronized(this@MemSampler) {
            samples++
            if (peak == null || s.pssMb > peak!!.pssMb) peak = s
            peakAnon = maxOf(peakAnon, s.rssAnonMb)
            peakSwap = maxOf(peakSwap, s.swapMb)
            minAvail = minOf(minAvail, s.availMb)
            minMargin = minOf(minMargin, s.availMb - s.thresholdMb)
            low = low || s.lowMemory
          }
          delay(300)
        }
      }
    }

    fun stop(): JSONObject {
      job?.cancel()
      val s = sample()
      synchronized(this) {
        if (peak == null || s.pssMb > peak!!.pssMb) peak = s
        return JSONObject().put("samples", samples).put("pss_mb", peak!!.pssMb).put("at_peak", peak!!.json())
          .put("peak_rss_anon_mb", maxOf(peakAnon, s.rssAnonMb)).put("peak_swap_mb", maxOf(peakSwap, s.swapMb))
          .put("min_avail_mb", minOf(minAvail, s.availMb)).put("min_margin_over_threshold_mb", minOf(minMargin, s.availMb - s.thresholdMb))
          .put("low_memory_seen", low || s.lowMemory)
      }
    }
  }

  private val QUERIES = listOf("receipt from the supermarket", "dog on the beach", "wedding photos", "school fees balance",
    "bus ticket to gulu", "screenshot of a bank message", "my passport photo", "minutes of the meeting", "a red car", "birthday cake")

  private fun jpeg(f: File, maxSide: Int): ByteArray {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    return ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
  }

  private fun ms(t0: Long) = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

  private fun stats(v: List<Double>): JSONObject {
    if (v.isEmpty()) return JSONObject().put("n", 0)
    val s = v.sorted()
    return JSONObject().put("n", s.size).put("p50", s[(s.size - 1) / 2]).put("p90", s[((s.size - 1) * 0.9).toInt()]).put("max", s.last())
  }
}
