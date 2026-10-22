package com.craneai.findr.bench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * The export-path bench (the experiment code): an exported text model on the phone, measured the way the app would use it.
 *
 *   --es export <texts file under files/>   one line per input, prefixes included (parity.py make-set)
 *   --es model <bundle>                      the text model (files/models/<bundle>)
 *   --es vb none|cpu                         vision executor for the text engine (a multimodal bundle needs one, idle)
 *   --es mm <bundle> --es images N           setup A: also load the stock multimodal bundle (vision on GPU, 70 tokens),
 *                                            embed N images with it while the text engine stays loaded
 *   --es passes P                            timed passes over the texts after the vector pass (default 2)
 *
 *   --es appembedder 1                       instead: the app's own Embedder (engine/), as built (-PfindrFinetuned),
 *                                            on the first 30 lines and 3 images: which bundle it picked, and its vectors
 *
 * Writes results/<tag>.f32 (the vectors of the first pass, rows in file order) next to results/<tag>.json.
 */
internal object ExportBench {
  fun run(
    base: File,
    cacheDir: File,
    tag: String,
    args: (String) -> String?,
    sampler: MetricsSampler,
    say: (String) -> Unit,
  ): JSONObject {
    val out = JSONObject()
    val texts = File(base, args("export")!!).readLines().filter { it.isNotBlank() }
    val model = File(base, "models/${args("model")}")
    val threads = args("threads")?.toIntOrNull()
    val vb = args("vb") ?: "none"
    out.put("model", model.name).put("model_bytes", model.length()).put("texts", texts.size).put("vb", vb)
    out.put("pss_before_mb", sampler.snapshot().pssMb)

    var t = SystemClock.elapsedRealtimeNanos()
    val engine = EmbeddingEngine(
      EmbeddingEngineConfig(
        modelPath = model.absolutePath,
        backend = Backend.CPU(threadCount = threads),
        visionBackend = if (vb == "cpu") Backend.CPU() else null,
        cacheDir = cacheDir.absolutePath,
      )
    )
    engine.initialize()
    out.put("init_ms", ms(t)).put("pss_after_init_mb", sampler.snapshot().pssMb)
    say("text engine ${model.name} init ${"%.0f".format(out.getDouble("init_ms"))} ms")
    val opts = EmbeddingOptions(normalize = true)
    fun embed(e: EmbeddingEngine, s: String): Pair<FloatArray, Double> {
      val start = SystemClock.elapsedRealtimeNanos()
      return e.computeEmbedding(listOf(InputData.Text(s)), opts).embedding to ms(start)
    }

    engine.use {
      // Pass 1: the vectors (first call is the cold one), written for parity.py compare.
      val vecs = ArrayList<FloatArray>()
      val first = mutableListOf<Double>()
      for (s in texts) embed(engine, s).let { (v, m) -> vecs += v; first += m }
      out.put("first_call_ms", first.first())
      val buf = ByteBuffer.allocate(vecs.size * vecs[0].size * 4).order(ByteOrder.LITTLE_ENDIAN)
      vecs.forEach { v -> v.forEach { buf.putFloat(it) } }
      File(base, "results/$tag.f32").writeBytes(buf.array())
      out.put("dim", vecs[0].size)

      // Timed passes: documents ("title: none | text: …", ~100 tokens) and queries separately.
      val docs = mutableListOf<Double>()
      val queries = mutableListOf<Double>()
      repeat(args("passes")?.toIntOrNull() ?: 2) {
        for (s in texts) {
          val m = embed(engine, s).second
          if (s.startsWith("task:")) queries += m else docs += m
        }
      }
      out.put("doc_ms", stats(docs)).put("query_ms", stats(queries)).put("pss_text_mb", sampler.snapshot().pssMb)
      say("text: docs ${stats(docs)} queries ${stats(queries)}")

      // Setup A: the stock multimodal bundle for images next to the text engine, both resident.
      val mm = args("mm")
      if (mm != null) {
        t = SystemClock.elapsedRealtimeNanos()
        val mmEngine = EmbeddingEngine(
          EmbeddingEngineConfig(
            modelPath = File(base, "models/$mm").absolutePath,
            backend = Backend.CPU(),
            visionBackend = Backend.GPU(),
            cacheDir = cacheDir.absolutePath,
            visionTokensPerImage = 70,
          )
        )
        mmEngine.initialize()
        val mmInit = ms(t)
        say("second engine $mm init ${"%.0f".format(mmInit)} ms, PSS ${"%.0f".format(sampler.snapshot().pssMb)} MB")
        val imgMs = mutableListOf<Double>()
        val both = mutableListOf<Double>()
        mmEngine.use {
          val mmOpts = EmbeddingOptions(normalize = true, visionTokensPerImage = 70)
          val files = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?.take(args("images")?.toIntOrNull() ?: 4).orEmpty()
          for ((i, f) in files.withIndex()) {
            val jpeg = downscale(f, 768)
            val start = SystemClock.elapsedRealtimeNanos()
            mmEngine.computeEmbedding(listOf(InputData.Image(jpeg)), mmOpts)
            imgMs += ms(start)
            both += embed(engine, texts[i % texts.size]).second   // interleaved, as the indexer would
          }
          out.put("mm", JSONObject().put("model", mm).put("init_ms", mmInit).put("image_ms", stats(imgMs))
            .put("text_ms_while_both", stats(both)).put("pss_both_mb", sampler.snapshot().pssMb))
        }
      }
    }
    return out
  }

  /** The app's Embedder end to end: model choice (flag + installed files), document()/query() prefixes, image(). */
  fun appEmbedder(context: android.content.Context, base: File, tag: String, args: (String) -> String?, say: (String) -> Unit): JSONObject =
    kotlinx.coroutines.runBlocking {
      val e = com.craneai.findr.engine.LiteRtEmbedder(context)
      val out = JSONObject().put("use_finetuned", com.craneai.findr.engine.Embedder.USE_FINETUNED)
        .put("model_file", e.modelFile?.name).put("supports_audio", e.supportsAudio)
      say("app Embedder picked ${e.modelFile?.name} (flag ${com.craneai.findr.engine.Embedder.USE_FINETUNED}, audio ${e.supportsAudio})")
      var t = SystemClock.elapsedRealtimeNanos()
      e.warm()
      out.put("warm_ms", ms(t))
      val lines = File(base, args("export")!!).readLines().filter { it.isNotBlank() }.take(30)
      val vecs = ArrayList<FloatArray>()
      val times = mutableListOf<Double>()
      for (s in lines) {
        t = SystemClock.elapsedRealtimeNanos()
        vecs += when {
          s.startsWith("task: search result | query: ") -> e.query(s.removePrefix("task: search result | query: "))
          s.startsWith("title: none | text: ") -> e.document(s.removePrefix("title: none | text: "))
          else -> error("unexpected prefix: ${s.take(40)}")
        }
        times += ms(t)
      }
      val buf = ByteBuffer.allocate(vecs.size * vecs[0].size * 4).order(ByteOrder.LITTLE_ENDIAN)
      vecs.forEach { v -> v.forEach { buf.putFloat(it) } }
      File(base, "results/$tag.f32").writeBytes(buf.array())
      val imgs = File(base, "bench/images").listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(3).orEmpty()
      val imgMs = imgs.map { f -> val jpeg = downscale(f, 768); t = SystemClock.elapsedRealtimeNanos(); e.image(jpeg); ms(t) }
      e.release()
      out.put("text_ms", stats(times)).put("image_ms", stats(imgMs)).put("rows", vecs.size)
    }

  private fun downscale(f: File, maxSide: Int): ByteArray {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    return ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
  }

  private fun ms(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1e6

  private fun stats(v: List<Double>): JSONObject {
    if (v.isEmpty()) return JSONObject().put("n", 0)
    val s = v.sorted()
    fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
    return JSONObject().put("n", s.size).put("p50", pct(0.5)).put("p90", pct(0.9)).put("mean", s.average()).put("min", s.first())
  }
}
