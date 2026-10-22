package com.craneai.findr.extract

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * The words in a vector picture (SVG, SVGZ): what its `<title>`, `<desc>` and `<text>` elements say. A logo, a diagram or a
 * chart exported as SVG is found by its labels, although the phone has no bitmap to look at. Bounded: the input is cut at
 * [MAX_BYTES] (gzip output too, so a compression bomb cannot grow), and a file that stops being well-formed keeps the words
 * read before the break.
 */
object SvgText {
  const val MAX_BYTES = 4 * 1024 * 1024
  private val WORDS = setOf("title", "desc", "text", "tspan", "textPath", "flowPara", "flowRoot", "flowSpan")
  private val BREAKS = setOf("title", "desc", "text", "flowPara")

  /** [bytes] is the head of the file, gzip-compressed or not. */
  fun toText(bytes: ByteArray, maxChars: Int, newParser: () -> XmlPullParser): String {
    val plain = if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) gunzip(bytes) else bytes
    val out = StringBuilder()
    var depth = 0
    try {
      val parser = newParser()
      parser.setInput(ByteArrayInputStream(plain), null)
      var event = parser.eventType
      while (event != XmlPullParser.END_DOCUMENT && out.length < maxChars) {
        when (event) {
          XmlPullParser.START_TAG -> if (parser.name in WORDS) depth++
          XmlPullParser.END_TAG -> if (parser.name in WORDS) {
            if (depth > 0) depth--
            if (parser.name in BREAKS && out.isNotEmpty() && out.last() != '\n') out.append('\n')
          }
          XmlPullParser.TEXT -> if (depth > 0) {
            val t = parser.text.replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) {
              if (out.isNotEmpty() && out.last() != '\n' && out.last() != ' ') out.append(' ')
              out.append(t)
            }
          }
        }
        event = parser.next()
      }
    } catch (e: XmlPullParserException) {
      // cut off or malformed: keep what was read
    } catch (e: java.io.IOException) {
    }
    return out.toString().trim().let { if (it.length > maxChars) it.substring(0, maxChars) else it }
  }

  /** Whatever inflates before the end or the first error: a truncated or damaged file still gives its head. */
  private fun gunzip(bytes: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    try {
      GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val buf = ByteArray(16 * 1024)
        while (out.size() < MAX_BYTES) {
          val n = input.read(buf, 0, minOf(buf.size, MAX_BYTES - out.size()))
          if (n < 0) break
          out.write(buf, 0, n)
        }
      }
    } catch (e: java.io.IOException) {
      // damaged or cut off: keep what inflated
    }
    return out.toByteArray()
  }
}
