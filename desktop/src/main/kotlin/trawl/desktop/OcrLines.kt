package trawl.desktop

import com.craneai.findr.core.OcrLine
import org.json.JSONObject

/** Tesseract's TSV word confidences and line boxes, with the hOCR baseline's tilt, in the shared phone OCR contract. */
internal object TesseractLines {
  fun parse(tsv: String, hocr: String): List<OcrLine> {
    val angles = Regex("<span\\b[^>]*>").findAll(hocr).map { it.value }
      .filter { Regex("class=[\"']ocr_(?:line|header|caption|textfloat)[\"']").containsMatchIn(it) }
      .map { tag ->
        val title = Regex("title=[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1).orEmpty()
        val baseline = Regex("\\bbaseline\\s+([-+0-9.eE]+)").find(title)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() }
        val orientation = Regex("\\btextangle\\s+([-+0-9.eE]+)").find(title)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() }
        if (baseline == null) orientation ?: Float.POSITIVE_INFINITY
        else (orientation ?: 0f) + (kotlin.math.atan(baseline.toDouble()) * 180 / kotlin.math.PI).toFloat()
      }.toList()
    val pages = HashMap<String, Pair<Float, Float>>()
    val boxes = LinkedHashMap<String, List<String>>()
    val words = LinkedHashMap<String, MutableList<List<String>>>()
    for (row in tsv.lineSequence().drop(1)) {
      val columns = row.split('\t', limit = 12)
      if (columns.size != 12) continue
      val key = columns.subList(1, 5).joinToString("/")
      when (columns[0]) {
        "1" -> pages[columns[1]] = (columns[8].toFloatOrNull() ?: 0f) to (columns[9].toFloatOrNull() ?: 0f)
        "4" -> boxes[key] = columns
        "5" -> if (columns[11].isNotBlank()) words.getOrPut(key) { ArrayList() }.add(columns)
      }
    }
    val angleOf = if (words.size == angles.size) words.keys.zip(angles).toMap() else emptyMap()
    return words.map { (key, tokens) ->
      val box = boxes[key]
      val page = pages[tokens.first()[1]]
      val confidences = tokens.map { token ->
        token[10].toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }?.div(100f) ?: OcrLine.UNKNOWN
      }
      val confidence = if (OcrLine.UNKNOWN in confidences) OcrLine.UNKNOWN else confidences.average().toFloat()
      val height = box?.get(9)?.toFloatOrNull() ?: 0f
      val width = box?.get(8)?.toFloatOrNull() ?: 0f
      OcrLine(
        tokens.joinToString(" ") { it[11] }, confidence, angleOf[key] ?: Float.POSITIVE_INFINITY,
        if (page != null && page.second > 0f) height / page.second else 0f,
        if (page != null && page.first > 0f) width / page.first else 0f,
        null, confidences.minOrNull() ?: OcrLine.UNKNOWN, tokens.size,
      )
    }
  }
}

/** Apple Vision's line candidates; an older helper without metadata remains long-readings-only. */
internal object OcrJson {
  fun lines(reading: JSONObject): List<OcrLine> {
    val lines = reading.optJSONArray("lines") ?: return reading.optString("text", "").lines()
      .map { OcrLine(it, OcrLine.UNKNOWN, 0f, 0f, 0f, null) }
    return (0 until lines.length()).map { index ->
      val line = lines.getJSONObject(index)
      val confidence = line.optDouble("confidence", -1.0).toFloat().takeIf { it.isFinite() && it in 0f..1f } ?: OcrLine.UNKNOWN
      OcrLine(
        line.optString("text", ""), confidence, line.optDouble("angle", 0.0).toFloat(),
        line.optDouble("height", 0.0).toFloat(), line.optDouble("width", 0.0).toFloat(), null,
      )
    }
  }
}
