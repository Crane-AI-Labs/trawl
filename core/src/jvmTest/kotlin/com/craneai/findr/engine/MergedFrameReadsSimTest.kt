package com.craneai.findr.engine

import com.craneai.findr.core.OcrLine
import com.craneai.findr.core.OnScreenText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * How many extra reads of merged video frames the old rule (6160acd0: a budget per video, no text gate) and the shipped rule
 * ([MergedFrameReads]) claim over real clips, with the SHIPPED [FrameSignature], [MergedFrameReads] and [OnScreenText] (and a port of
 * the video loop's 9x8 difference hash). Off unless the inputs are named:
 *
 *   FINDR_CLAIM_SIM=<dir with manifest.json and frames/<n>.jpg>  FINDR_CLAIM_PROBE=<OCR probe dump over the frames, id = n>
 *   FINDR_CLAIM_OUT=<out.jsonl>  ./gradlew :core:jvmTest --tests '*MergedFrameReadsSimTest'
 *
 * The manifest is [{clip, kind, variant, windows:[{startMs, seconds, frames:[{file, timeMs}]}]}] (frames in play order, one list per
 * window as the app reads a long video a window at a time). Each output line is one window: frame counts, how many looks (signature
 * calls) and extra reads each rule made, and the OCR time (from the probe, on the phone that ran it) of the frames each rule read.
 */
class MergedFrameReadsSimTest {

  /** The rule at 6160acd0, kept here as the baseline the shipped rule is measured against. */
  private class OldRule(seconds: Int, unbounded: Boolean = false) {
    private val budget = if (unbounded) Int.MAX_VALUE else ((seconds * 6 + 59) / 60).coerceAtLeast(1)
    private val read = HashMap<Int, MutableList<FrameSignature>>()
    var used = 0
    val spent: Boolean get() = used >= budget
    fun covered(lineage: Int, signature: FrameSignature) {
      val list = read.getOrPut(lineage) { mutableListOf() }
      if (list.size < 12) list += signature
    }
    fun claim(lineage: Int, signature: FrameSignature): Boolean {
      if (spent) return false
      if (read[lineage].orEmpty().any { !signature.mayShowOtherText(it) }) return false
      used++
      covered(lineage, signature)
      return true
    }
  }

  private class Frame(val signature: FrameSignature, val hash: Long, val hadText: Boolean, val ocrMs: Double, val letters: Int) {
    /** The text-load class of the cost tables: 0 no letters, 1 under 100, 2 from 100 to 399 (a slide), 3 from 400. */
    val load: Int get() = when { letters == 0 -> 0; letters < 100 -> 1; letters < 400 -> 2; else -> 3 }
  }

  @Test fun claimRatesOverTheSampledClips() {
    val dir = System.getenv("FINDR_CLAIM_SIM")
    val probe = System.getenv("FINDR_CLAIM_PROBE")
    val output = System.getenv("FINDR_CLAIM_OUT")
    assumeTrue("FINDR_CLAIM_SIM, FINDR_CLAIM_PROBE and FINDR_CLAIM_OUT name the inputs and the output", !dir.isNullOrEmpty() && !probe.isNullOrEmpty() && !output.isNullOrEmpty())
    val rows = HashMap<Int, JSONObject>()
    File(probe).forEachLine { if (it.isNotBlank()) JSONObject(it).let { r -> if (!r.has("err")) rows[r.getString("id").toInt()] = r } }
    val cache = HashMap<Int, Frame?>()
    fun frame(n: Int): Frame? = cache.getOrPut(n) {
      val row = rows[n] ?: return@getOrPut null
      val image = ImageIO.read(File(dir, "frames/$n.jpg")) ?: return@getOrPut null
      val w = image.width; val h = image.height
      val rgb = image.getRGB(0, 0, w, h, null, 0, w)
      val luma = IntArray(w * h) { lumaOf(rgb[it]) }
      val arr = row.optJSONArray("lines") ?: JSONArray()
      val lines = (0 until arr.length()).map { i ->
        val x = arr.getJSONObject(i)
        OcrLine(
          text = x.getString("t"), confidence = x.optDouble("c", -1.0).toFloat(), angle = x.optDouble("a", 0.0).toFloat(),
          heightFraction = x.optDouble("hf", 0.0).toFloat(), widthFraction = x.optDouble("wf", 0.0).toFloat(),
          language = x.optString("l").ifEmpty { null }, minWordConfidence = x.optDouble("m", -1.0).toFloat(), words = x.optInt("n", 0),
        )
      }
      Frame(
        FrameSignature.of(luma, w, h), differenceHash(rgb, w, h), OnScreenText.keep(lines) != null, row.optDouble("ms", 0.0),
        lines.sumOf { l -> l.text.count { it.isLetter() } },
      )
    }

    File(output).bufferedWriter().use { out ->
      val manifest = JSONArray(File(dir, "manifest.json").readText())
      for (r in 0 until manifest.length()) {
        val run = manifest.getJSONObject(r)
        val windows = run.getJSONArray("windows")
        for (wi in 0 until windows.length()) {
          val window = windows.getJSONObject(wi)
          val startMs = window.getLong("startMs")
          val seconds = window.getInt("seconds")
          val old = OldRule(seconds)
          val wanted = OldRule(seconds, unbounded = true)   // the old rule with no budget: every merged frame its look says to read
          val new = MergedFrameReads(seconds)
          val kept = mutableListOf<Long>()
          var frames = 0; var distinct = 0; var merged = 0; var textFrames = 0
          var oldLooks = 0; var newLooks = 0; var oldClaims = 0; var newClaims = 0; var oldWanted = 0
          var oldExtraMs = 0.0; var newExtraMs = 0.0; var distinctMs = 0.0
          var oldExtraLetters = 0; var newExtraLetters = 0; var distinctLetters = 0
          val distinctLoad = IntArray(4); val oldClaimLoad = IntArray(4); val newClaimLoad = IntArray(4); var newCoveredLooks = 0
          val fs = window.getJSONArray("frames")
          for (i in 0 until fs.length()) {
            val f = fs.getJSONObject(i)
            val fr = frame(f.getInt("file")) ?: continue
            frames++
            if (fr.hadText) textFrames++
            val rel = f.getLong("timeMs") - startMs
            val lineage = kept.indexOfFirst { java.lang.Long.bitCount(it xor fr.hash) <= SAME_FRAME_BITS }
            if (lineage >= 0) {
              merged++
              if (wanted.claim(lineage, fr.signature)) oldWanted++
              if (!old.spent) { oldLooks++; if (old.claim(lineage, fr.signature)) { oldClaims++; oldClaimLoad[fr.load]++; oldExtraMs += fr.ocrMs; oldExtraLetters += fr.letters } }
              if (new.mayClaim(lineage, rel)) { newLooks++; if (new.claim(lineage, fr.signature, rel)) { newClaims++; newClaimLoad[fr.load]++; newExtraMs += fr.ocrMs; newExtraLetters += fr.letters } }
            } else {
              distinct++
              distinctMs += fr.ocrMs; distinctLetters += fr.letters; distinctLoad[fr.load]++
              kept += fr.hash
              oldLooks++; old.covered(kept.lastIndex, fr.signature); wanted.covered(kept.lastIndex, fr.signature)
              if (fr.hadText && new.enabled) { newLooks++; newCoveredLooks++; new.covered(kept.lastIndex, fr.signature) }
            }
          }
          out.write(
            JSONObject().put("clip", run.getString("clip")).put("kind", run.getString("kind")).put("variant", run.getString("variant"))
              .put("window", wi).put("seconds", seconds).put("frames", frames).put("distinct", distinct).put("merged", merged)
              .put("textFrames", textFrames).put("oldLooks", oldLooks).put("newLooks", newLooks).put("oldClaims", oldClaims).put("oldWanted", oldWanted)
              .put("newClaims", newClaims).put("distinctMs", distinctMs).put("oldExtraMs", oldExtraMs).put("newExtraMs", newExtraMs)
              .put("distinctLoad", JSONArray(distinctLoad.toList())).put("oldClaimLoad", JSONArray(oldClaimLoad.toList()))
              .put("newClaimLoad", JSONArray(newClaimLoad.toList())).put("newCoveredLooks", newCoveredLooks)
              .put("distinctLetters", distinctLetters).put("oldExtraLetters", oldExtraLetters).put("newExtraLetters", newExtraLetters)
              .toString(),
          )
          out.newLine()
        }
      }
    }
  }

  private fun lumaOf(c: Int) = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000

  /**
   * FileProcessor.differenceHash: the bitmap scaled to 9 x 8 with a bilinear filter (Android samples the four neighbours of each
   * destination pixel's centre), each pixel's brightness against its right neighbour.
   */
  private fun differenceHash(rgb: IntArray, w: Int, h: Int): Long {
    val grey = Array(8) { IntArray(9) }
    for (y in 0 until 8) for (x in 0 until 9) {
      val sx = (x + 0.5) * w / 9 - 0.5
      val sy = (y + 0.5) * h / 8 - 0.5
      val x0 = Math.floor(sx).toInt().coerceIn(0, w - 1); val y0 = Math.floor(sy).toInt().coerceIn(0, h - 1)
      val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
      val fx = (sx - x0).coerceIn(0.0, 1.0); val fy = (sy - y0).coerceIn(0.0, 1.0)
      fun channel(shift: Int): Int {
        fun at(xx: Int, yy: Int) = (rgb[yy * w + xx] shr shift and 0xff).toDouble()
        return (at(x0, y0) * (1 - fx) * (1 - fy) + at(x1, y0) * fx * (1 - fy) + at(x0, y1) * (1 - fx) * fy + at(x1, y1) * fx * fy).toInt()
      }
      grey[y][x] = (channel(16) * 299 + channel(8) * 587 + channel(0) * 114) / 1000
    }
    var hash = 0L
    for (y in 0 until 8) for (x in 0 until 8) if (grey[y][x] > grey[y][x + 1]) hash = hash or (1L shl (y * 8 + x))
    return hash
  }

  private companion object {
    const val SAME_FRAME_BITS = 6   // FileProcessor.SAME_FRAME_BITS
  }
}
