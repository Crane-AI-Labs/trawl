package com.craneai.findr.extract

import com.craneai.findr.core.ExtractedText
import com.craneai.findr.core.FindrLog as Log
import java.io.File
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import java.util.TreeMap

/**
 * Text from a legacy Excel workbook (.xls, BIFF8: Excel 97 to 2003): the OLE2 container, the "Workbook" stream in it, and its cell records
 * (shared strings, numbers, RK numbers, formula results). Written for this app in plain Kotlin: no library, no native code, nothing to
 * license. Reads what [OfficeText] reads from an xlsx: the sheet's name, then one line per row ("cell | cell"), empty cells left out,
 * numbers as the file holds them (a date cell is its serial day, as in an xlsx). Anything it does not understand (Excel 5/95 workbooks,
 * an encrypted file, a damaged container) gives no text, never an exception.
 */
object XlsText {
  private const val TAG = "FindrExtract"
  private const val MAX_STREAM_BYTES = ExtractLimits.MAX_ENTRY_BYTES.toInt()
  private val whitespaceRun = Regex("\\s+")
  private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

  fun isOle(head: ByteArray, length: Int = head.size): Boolean = length >= 8 && (0 until 8).all { head[it] == OLE_MAGIC[it] }

  fun extract(file: File, maxChars: Int, tick: () -> Unit = {}): ExtractedText {
    val budget = Budget(minOf(maxChars, ExtractLimits.TABULAR_MAX_CHARS))
    return try {
      if (file.length() > MAX_STREAM_BYTES) return ExtractedText(emptyList())   // read whole: a legacy workbook is far smaller than this
      val cfb = Cfb(file.readBytes())
      val stream = cfb.stream("Workbook") ?: cfb.stream("Book") ?: return ExtractedText(emptyList())
      val text = Workbook(stream, budget, tick).read()
      ExtractedText(if (text.isEmpty()) emptyList() else listOf(text), budget.truncated, budget.stopped)
    } catch (e: ExtractTimeout) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "xls: no text (${Log.brief(t)})")
      ExtractedText(emptyList())
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // OLE2 compound file: header, FAT, directory, mini stream
  // ---------------------------------------------------------------------------------------------------------------------------
  private class Cfb(private val d: ByteArray) {
    private val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSize: Int
    private val fat: IntArray
    private val miniCutoff: Int
    private val dirStart: Int
    private val miniFatStart: Int

    init {
      require(isOle(d)) { "not an OLE2 file" }
      sectorSize = 1 shl b.getShort(30).toInt()
      miniSize = 1 shl b.getShort(32).toInt()
      require(sectorSize in 512..4096 && miniSize in 16..512) { "odd sector sizes" }
      dirStart = b.getInt(48)
      miniCutoff = b.getInt(56)
      miniFatStart = b.getInt(60)
      val difat = ArrayList<Int>()
      for (i in 0 until 109) difat.add(b.getInt(76 + 4 * i))
      var next = b.getInt(68)
      val seenDifat = HashSet<Int>()
      var guard = 0
      while (next >= 0 && guard++ < 10_000) {
        val off = sectorOffset(next)
        if (!seenDifat.add(next)) break
        for (i in 0 until sectorSize / 4 - 1) difat.add(b.getInt(off + 4 * i))
        next = b.getInt(off + sectorSize - 4)
      }
      val ids = ArrayList<Int>()
      for (s in difat) {
        if (s < 0) continue
        val off = sectorOffset(s)
        for (i in 0 until sectorSize / 4) ids.add(b.getInt(off + 4 * i))
      }
      fat = ids.toIntArray()
    }

    private fun sectorOffset(sid: Int): Int = (sid + 1).toLong().times(sectorSize).also { require(it + sectorSize <= d.size) { "sector past the end" } }.toInt()

    private fun chain(start: Int, table: IntArray = fat): List<Int> {
      val out = ArrayList<Int>()
      val seen = BitSet(table.size)
      var s = start
      while (s >= 0 && s < table.size && !seen.get(s)) {
        seen.set(s)
        out.add(s)
        s = table[s]
      }
      return out
    }

    private fun readChain(start: Int, size: Int): ByteArray {
      if (size <= 0) return ByteArray(0)
      val cap = minOf(size, MAX_STREAM_BYTES)
      val out = java.io.ByteArrayOutputStream()
      for (s in chain(start)) {
        if (out.size() >= cap) break
        out.write(d, sectorOffset(s), sectorSize)
      }
      return out.toByteArray().copyOf(minOf(cap, out.size()))
    }

    fun stream(name: String): ByteArray? {
      val dir = readChain(dirStart, Int.MAX_VALUE)
      var root: Pair<Int, Int>? = null
      var wanted: Pair<Int, Int>? = null
      val e = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until dir.size / 128) {
        val at = i * 128
        val nameLen = (e.getShort(at + 64).toInt() and 0xFFFF).coerceIn(0, 64)
        val n = String(dir, at, maxOf(0, nameLen - 2), Charsets.UTF_16LE)
        val type = dir[at + 66].toInt()
        val start = e.getInt(at + 116)
        val size = e.getInt(at + 120)
        if (type == 5) root = start to size
        if (type == 2 && n == name && wanted == null) wanted = start to size
      }
      val (start, size) = wanted ?: return null
      require(size in 0..MAX_STREAM_BYTES) { "stream too large" }
      if (size >= miniCutoff) return readChain(start, size)
      val (rootStart, rootSize) = root ?: return null
      val mini = readChain(rootStart, rootSize)
      val miniFat = readChain(miniFatStart, Int.MAX_VALUE).let { bytes -> IntArray(bytes.size / 4) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) } }
      val out = java.io.ByteArrayOutputStream()
      for (s in chain(start, miniFat)) { if (out.size() >= size) break; out.write(mini, s * miniSize, minOf(miniSize, mini.size - s * miniSize)) }
      return out.toByteArray().copyOf(minOf(size, out.size()))
    }
  }

  // ---------------------------------------------------------------------------------------------------------------------------
  // BIFF8 workbook stream
  // ---------------------------------------------------------------------------------------------------------------------------
  private class Workbook(private val s: ByteArray, private val budget: Budget, private val tick: () -> Unit) {
    private val sheetNames = ArrayList<String>()
    private val shared = ArrayList<String>()

    private fun u16(a: ByteArray, o: Int) = (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(a: ByteArray, o: Int) = u16(a, o).toLong() or (u16(a, o + 2).toLong() shl 16)

    /** Record ids [read] looks into; every other record is stepped over without being copied (a hostile file may hold millions of them). */
    private val wanted = setOf(0x2F, 0x85, 0xFC, 0x809, 0xFD, 0x204, 0x203, 0x27E, 0xBD, 0x06, 0x207)
    private val none = ByteArray(0)
    private var collected = 0
    private var sharedChars = 0

    fun read(): String {
      if (s.size < 8 || u16(s, 0) != 0x809 || u16(s, 2) < 4 || u16(s, 4) != 0x0600) return ""     // BIFF8 only
      val worksheetNames = ArrayList<String>()
      val cellsBySheet = ArrayList<TreeMap<Int, TreeMap<Int, String>>>()
      var current: TreeMap<Int, TreeMap<Int, String>>? = null
      var pendingFormula: Pair<Int, Int>? = null
      var p = 0
      var records = 0
      var inGlobals = true
      while (p + 4 <= s.size && collected < ExtractLimits.MAX_CELLS) {
        val id = u16(s, p)
        val len = u16(s, p + 2)
        if (p + 4 + len > s.size) break
        if ((++records and 0x3FF) == 0) tick()
        val d = if (id in wanted) s.copyOfRange(p + 4, p + 4 + len) else none
        var next = p + 4 + len
        when (id) {
          0x2F -> return ""                                                                  // FILEPASS: encrypted
          0x85 -> if (d.size >= 8 && (d[5].toInt() and 0xFF) == 0) worksheetNames.add(shortUnicode(d, 6))   // BOUNDSHEET: worksheets only
          0xFC -> {                                                                          // SST and its CONTINUEs
            val segments = arrayListOf(d)
            while (next + 4 <= s.size && u16(s, next) == 0x3C && next + 4 + u16(s, next + 2) <= s.size) {
              val l = u16(s, next + 2)
              segments.add(s.copyOfRange(next + 4, next + 4 + l))
              next += 4 + l
            }
            readSharedStrings(segments)
          }
          0x809 -> if (d.size >= 4) {
            val type = u16(d, 2)
            if (type == 0x0010) { current = TreeMap(); cellsBySheet.add(current); inGlobals = false }
            else if (type != 0x0005) current = null                                            // a chart, a macro sheet: no cells
            if (type == 0x0005) inGlobals = true
          }
          0x0A -> if (!inGlobals) current = null
          0xFD -> current?.let { if (d.size >= 10) put(it, u16(d, 0), u16(d, 2), shared.getOrNull(u32(d, 6).toInt()) ?: "") }
          0x204 -> current?.let { if (d.size >= 9) put(it, u16(d, 0), u16(d, 2), unicodeString(d, 6, lengthBytes = 2).first) }
          0x203 -> current?.let { if (d.size >= 14) put(it, u16(d, 0), u16(d, 2), number(java.lang.Double.longBitsToDouble(long(d, 6)), u16(d, 4))) }
          0x27E -> current?.let { if (d.size >= 10) put(it, u16(d, 0), u16(d, 2), number(rk(u32(d, 6)), u16(d, 4))) }
          0xBD -> current?.let {                                                             // MULRK: a run of RK cells in one row
            if (d.size >= 6) {
              val row = u16(d, 0)
              val first = u16(d, 2)
              val count = (d.size - 6) / 6
              for (k in 0 until count) put(it, row, first + k, number(rk(u32(d, 6 + 6 * k)), u16(d, 4 + 6 * k)))
            }
          }
          0x06 -> current?.let {                                                             // FORMULA: the value it last had
            if (d.size >= 14) {
              if (u16(d, 12) == 0xFFFF && (d[6].toInt() and 0xFF) == 0) pendingFormula = u16(d, 0) to u16(d, 2)
              else if (u16(d, 12) != 0xFFFF) put(it, u16(d, 0), u16(d, 2), number(java.lang.Double.longBitsToDouble(long(d, 6)), u16(d, 4)))
            }
          }
          0x207 -> current?.let { cells -> pendingFormula?.let { (row, col) -> if (d.size >= 3) put(cells, row, col, unicodeString(d, 0, lengthBytes = 2).first); pendingFormula = null } }
        }
        p = next
      }
      if (collected >= ExtractLimits.MAX_CELLS) budget.truncated = true
      return render(worksheetNames, cellsBySheet)
    }

    private fun long(a: ByteArray, o: Int): Long = u32(a, o) or (u32(a, o + 4) shl 32)

    private fun put(cells: TreeMap<Int, TreeMap<Int, String>>, row: Int, col: Int, text: String) {
      if (text.isBlank() || collected >= ExtractLimits.MAX_CELLS) return
      collected++
      cells.getOrPut(row) { TreeMap() }[col] = text
    }

    private fun number(v: Double, @Suppress("UNUSED_PARAMETER") xf: Int): String = plain(v)

    private fun rk(raw: Long): Double {
      val v = if (raw and 2L != 0L) (raw.toInt() shr 2).toDouble() else java.lang.Double.longBitsToDouble((raw and 0xFFFFFFFCL) shl 32)
      return if (raw and 1L != 0L) v / 100.0 else v
    }

    private fun plain(v: Double): String =
      if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

    private fun render(names: List<String>, sheets: List<TreeMap<Int, TreeMap<Int, String>>>): String {
      val parts = ArrayList<String>()
      var cellsLeft = ExtractLimits.MAX_CELLS
      for ((idx, cells) in sheets.withIndex()) {
        tick()
        if (budget.full || cellsLeft <= 0) { budget.truncated = true; break }
        val sink = TextSink(budget)
        outer@ for ((_, row) in cells) {
          var first = true
          for ((_, raw) in row) {
            if (cellsLeft <= 0) { budget.truncated = true; break@outer }
            cellsLeft--
            if (!first) sink.append(" | ")
            first = false
            sink.append(raw.replace(whitespaceRun, " ").trim())
          }
          sink.newline()
          if (budget.full) break
        }
        val text = sink.take()
        if (text.isNotEmpty()) parts.add(names.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { "$it\n$text" } ?: text)
      }
      return parts.joinToString("\n\n")
    }

    // ---- strings ----
    private fun shortUnicode(a: ByteArray, o: Int): String = unicodeString(a, o, lengthBytes = 1).first

    /** A BIFF8 unicode string at [o]: length (1 or 2 bytes), flags, characters. Returns the text and the offset after it. */
    private fun unicodeString(a: ByteArray, o: Int, lengthBytes: Int): Pair<String, Int> {
      var p = o
      val cch = if (lengthBytes == 1) a[p].toInt() and 0xFF else u16(a, p)
      p += lengthBytes
      val flags = a[p++].toInt() and 0xFF
      val wide = flags and 1 != 0
      if (flags and 8 != 0) p += 2
      if (flags and 4 != 0) p += 4
      val bytes = if (wide) cch * 2 else cch
      val end = minOf(a.size, p + bytes)
      val text = if (wide) String(a, p, end - p, Charsets.UTF_16LE) else String(a, p, end - p, Charsets.ISO_8859_1)
      return text to end
    }

    /** The shared string table, whose strings may run across CONTINUE records (each continuation of character data starts with a width flag). */
    private fun readSharedStrings(segments: List<ByteArray>) {
      var seg = 0
      var pos = 8                                          // total and unique counts
      val unique = if (segments[0].size >= 8) u32(segments[0], 4).toInt() else 0
      fun cur() = segments[seg]
      fun ensure(): Boolean { while (seg < segments.size && pos >= segments[seg].size) { seg++; pos = 0 }; return seg < segments.size }
      fun byte(): Int = if (ensure()) (cur()[pos++].toInt() and 0xFF) else 0
      fun skip(n: Int) { var left = n; while (left > 0 && ensure()) { val take = minOf(left, cur().size - pos); pos += take; left -= take } }
      var count = 0
      while (count < unique && ensure()) {
        val cch = byte() or (byte() shl 8)
        val flags = byte()
        var wide = flags and 1 != 0
        val runs = if (flags and 8 != 0) byte() or (byte() shl 8) else 0
        val ext = if (flags and 4 != 0) byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24) else 0
        val sb = StringBuilder(cch)
        var done = 0
        while (done < cch) {
          if (pos >= cur().size) { seg++; pos = 0; if (seg >= segments.size) break; wide = (cur()[pos++].toInt() and 1) != 0 }
          if (wide) {
            if (pos + 1 >= cur().size) { pos = cur().size; continue }
            sb.append((u16(cur(), pos)).toChar()); pos += 2
          } else sb.append((cur()[pos++].toInt() and 0xFF).toChar())
          done++
        }
        skip(runs * 4 + ext)
        sharedChars += sb.length
        shared.add(if (sharedChars > ExtractLimits.MAX_SHARED_STRING_CHARS) "" else sb.toString())
        if ((count and 0x3FF) == 0x3FF) tick()
        count++
      }
    }
  }
}
