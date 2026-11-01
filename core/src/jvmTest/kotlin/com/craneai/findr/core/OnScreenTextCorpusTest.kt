package com.craneai.findr.core

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the keep rule over an OCR probe dump (the app's own recogniser, run on a phone over a picture corpus by OcrProbeActivity) and
 * writes what the old rule and the new rule would each keep, so the search-quality and false-positive measurements use the SHIPPED
 * code and not a copy of it. Off unless the dump is named:
 *
 *   FINDR_OCR_PROBE=<probe.jsonl> FINDR_OCR_DECISIONS=<out.jsonl> ./gradlew :core:jvmTest --tests '*OnScreenTextCorpusTest'
 *
 * Each input line is {id, lines:[{t, c, a, hf, wf, l, m, n}]}; each output line is {id, old, new, long}, text or null.
 */
class OnScreenTextCorpusTest {
  @Test fun decisionsOverTheProbeDump() {
    val input = System.getenv("FINDR_OCR_PROBE")
    val output = System.getenv("FINDR_OCR_DECISIONS")
    assumeTrue("FINDR_OCR_PROBE and FINDR_OCR_DECISIONS name the dump and the output", !input.isNullOrEmpty() && !output.isNullOrEmpty())
    File(output).bufferedWriter().use { out ->
      File(input).forEachLine { raw ->
        if (raw.isBlank()) return@forEachLine
        val row = JSONObject(raw)
        if (row.has("err")) return@forEachLine
        val arr = row.optJSONArray("lines")
        val lines = (0 until (arr?.length() ?: 0)).map { i ->
          val x = arr!!.getJSONObject(i)
          OcrLine(
            text = x.getString("t"), confidence = x.optDouble("c", -1.0).toFloat(), angle = x.optDouble("a", 0.0).toFloat(),
            heightFraction = x.optDouble("hf", 0.0).toFloat(), widthFraction = x.optDouble("wf", 0.0).toFloat(),
            language = x.optString("l").ifEmpty { null }, minWordConfidence = x.optDouble("m", -1.0).toFloat(), words = x.optInt("n", 0),
          )
        }
        val old = OnScreenText.legacy(lines.joinToString("\n") { it.text })
        val new = OnScreenText.keep(lines)
        out.write(JSONObject().put("id", row.getInt("id")).put("old", old ?: JSONObject.NULL).put("new", new?.text ?: JSONObject.NULL)
          .put("long", new?.long ?: false).toString())
        out.newLine()
      }
    }
  }
}
