package com.craneai.findr.extract

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.craneai.findr.core.ExtractedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A legacy .xls read without a library. The workbook streams are written here by hand (BIFF8 records, then an OLE2 container around them),
 * once small enough for the container's mini stream and once large enough for its ordinary sectors.
 */
class XlsTextTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun le(vararg parts: Pair<Int, Long>): ByteArray {
    val out = ByteArrayOutputStream()
    for ((bytes, v) in parts) for (i in 0 until bytes) out.write(((v shr (8 * i)) and 0xFF).toInt())
    return out.toByteArray()
  }

  private fun rec(id: Int, data: ByteArray) = le(2 to id.toLong(), 2 to data.size.toLong()) + data
  private fun str16(s: String) = le(2 to s.length.toLong(), 1 to 0L) + s.toByteArray(Charsets.ISO_8859_1)

  /** A BIFF8 workbook: sheet "Orders" with Name | Qty | Paid on (a date-formatted serial) / Atim | 42 | 2026-03-14 / Okello | 7.5 (RK, /100 flag). */
  private fun workbookStream(sharedExtra: List<String> = emptyList()): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(rec(0x809, le(2 to 0x600, 2 to 5, 2 to 0, 2 to 0, 4 to 0, 4 to 6)))
    out.write(rec(0x85, le(4 to 0, 1 to 0, 1 to 0) + le(1 to 6, 1 to 0) + "Orders".toByteArray()))
    out.write(rec(0xE0, le(2 to 0, 2 to 0, 2 to 0)))                       // xf 0: General
    out.write(rec(0xE0, le(2 to 0, 2 to 14, 2 to 0)))                      // xf 1: built-in date format 14
    val strings = listOf("Name", "Qty", "Paid on", "Atim", "Okello") + sharedExtra
    val sst = le(4 to strings.size.toLong(), 4 to strings.size.toLong()) + strings.fold(ByteArray(0)) { a, s -> a + str16(s) }
    out.write(rec(0xFC, sst))
    out.write(rec(0x0A, ByteArray(0)))
    out.write(rec(0x809, le(2 to 0x600, 2 to 0x10, 2 to 0, 2 to 0, 4 to 0, 4 to 6)))
    for ((c, i) in listOf(0, 1, 2).zip(listOf(0L, 1L, 2L))) out.write(rec(0xFD, le(2 to 0, 2 to c.toLong(), 2 to 0, 4 to i)))
    out.write(rec(0xFD, le(2 to 1, 2 to 0, 2 to 0, 4 to 3)))
    out.write(rec(0x203, le(2 to 1, 2 to 1, 2 to 0) + java.nio.ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(42.0).array()))
    out.write(rec(0x203, le(2 to 1, 2 to 2, 2 to 1) + java.nio.ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(46095.0).array()))
    out.write(rec(0xFD, le(2 to 2, 2 to 0, 2 to 0, 4 to 4)))
    out.write(rec(0x27E, le(2 to 2, 2 to 1, 2 to 0, 4 to ((750L shl 2) or 3L))))   // RK integer 750 with the /100 flag = 7.5
    out.write(rec(0x0A, ByteArray(0)))
    return out.toByteArray()
  }

  /** An OLE2 file (512-byte sectors) holding [stream] as "Workbook": in the mini stream when it is under 4096 bytes, else in ordinary sectors. */
  private fun ole(stream: ByteArray): File {
    val sector = 512
    val mini = stream.size < 4096
    val dataSectors = (stream.size + sector - 1) / sector
    // layout: 0 = FAT, 1 = directory, 2 = mini FAT (mini case), then the data sectors (or the mini stream container)
    val dataStart = if (mini) 3 else 2
    val nData = if (mini) 1 else dataSectors
    val fat = IntArray(128) { -1 }
    fat[0] = -3; fat[1] = -2
    if (mini) { fat[2] = -2; fat[3] = -2 } else for (k in 0 until nData) fat[dataStart + k] = if (k == nData - 1) -2 else dataStart + k + 1
    val header = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
    header.put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
    header.putShort(24, 0x3E); header.putShort(26, 3); header.putShort(28, 0xFFFE.toShort()); header.putShort(30, 9); header.putShort(32, 6)
    header.putInt(44, 1); header.putInt(48, 1); header.putInt(56, 4096); header.putInt(60, if (mini) 2 else -2); header.putInt(64, if (mini) 1 else 0)
    header.putInt(68, -2); header.putInt(72, 0)
    for (k in 0 until 109) header.putInt(76 + 4 * k, if (k == 0) 0 else -1)
    fun dirEntry(buf: ByteBuffer, at: Int, name: String, type: Int, start: Int, size: Int) {
      for ((k, ch) in name.withIndex()) buf.putShort(at + 2 * k, ch.code.toShort())
      buf.putShort(at + 64, ((name.length + 1) * 2).toShort()); buf.put(at + 66, type.toByte()); buf.putInt(at + 116, start); buf.putInt(at + 120, size)
    }
    val dir = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
    dirEntry(dir, 0, "Root Entry", 5, if (mini) 3 else -2, if (mini) ((stream.size + 63) / 64) * 64 else 0)
    dirEntry(dir, 128, "Workbook", 2, if (mini) 0 else dataStart, stream.size)
    val out = ByteArrayOutputStream()
    out.write(header.array())
    out.write(ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).also { b -> fat.forEach { b.putInt(it) } }.array())
    out.write(dir.array())
    if (mini) {
      val miniFat = IntArray(128) { -1 }
      val n = (stream.size + 63) / 64
      for (k in 0 until n) miniFat[k] = if (k == n - 1) -2 else k + 1
      out.write(ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).also { b -> miniFat.forEach { b.putInt(it) } }.array())
    }
    val padded = stream.copyOf(maxOf(nData * sector, sector))
    out.write(padded)
    return tmp.newFile().also { it.writeBytes(out.toByteArray()) }   // a fresh name each time: the fuzz tests build many files in one test
  }

  @Test fun aSmallWorkbookInTheMiniStreamReadsAsRows() {
    val stream = workbookStream()
    assertTrue(stream.size < 4096)
    assertEquals("Orders\nName | Qty | Paid on\nAtim | 42 | 46095\nOkello | 7.5", XlsText.extract(ole(stream), 1_000_000).pages.single())
  }

  @Test fun aLargerWorkbookInOrdinarySectorsReadsTheSame() {
    val stream = workbookStream(sharedExtra = (1..400).map { "padding string number $it" })
    assertTrue(stream.size > 4096)
    assertEquals("Orders\nName | Qty | Paid on\nAtim | 42 | 46095\nOkello | 7.5", XlsText.extract(ole(stream), 1_000_000).pages.single())
  }

  @Test fun anythingElseGivesNoTextAndNoException() {
    assertEquals(emptyList<String>(), XlsText.extract(tmp.newFile("junk.xls").also { it.writeBytes(ByteArray(2000) { 7 }) }, 1000).pages)
    assertEquals(emptyList<String>(), XlsText.extract(tmp.newFile("empty.xls"), 1000).pages)
    val encrypted = workbookStream().let { s -> s.copyOfRange(0, 20) + rec(0x2F, ByteArray(6)) + s.copyOfRange(20, s.size) }
    assertEquals(emptyList<String>(), XlsText.extract(ole(encrypted), 1000).pages)
  }

  @Test fun theNamesTellXlsFromTheRest() {
    assertEquals(DocFormat.XLS, DocFormat.detect(null, "Budget.XLS"))
    assertEquals(DocFormat.XLS, DocFormat.detect("application/vnd.ms-excel", "download"))
  }

  // ---- corrupt and hostile files: they must end quickly, without an exception, inside the char and cell budgets ----

  /** An OLE2 file with as many FAT sectors as the header can name (109), so a stream of several MB fits; ordinary sectors only. */
  private fun oleBig(stream: ByteArray): File {
    val sector = 512
    val nData = (stream.size + sector - 1) / sector
    var nf = 1
    while (nf * 128 < nf + 1 + nData) nf++
    require(nf <= 109) { "stream too large for the test container" }
    val fat = IntArray(nf * 128) { -1 }
    for (k in 0 until nf) fat[k] = -3
    fat[nf] = -2
    for (k in 0 until nData) fat[nf + 1 + k] = if (k == nData - 1) -2 else nf + 2 + k
    val header = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
    header.put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
    header.putShort(24, 0x3E); header.putShort(26, 3); header.putShort(28, 0xFFFE.toShort()); header.putShort(30, 9); header.putShort(32, 6)
    header.putInt(44, nf); header.putInt(48, nf); header.putInt(56, 4096); header.putInt(60, -2); header.putInt(64, 0); header.putInt(68, -2); header.putInt(72, 0)
    for (k in 0 until 109) header.putInt(76 + 4 * k, if (k < nf) k else -1)
    val dir = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
    for ((k, ch) in "Root Entry".withIndex()) dir.putShort(2 * k, ch.code.toShort())
    dir.putShort(64, 22); dir.put(66, 5); dir.putInt(116, -2)
    for ((k, ch) in "Workbook".withIndex()) dir.putShort(128 + 2 * k, ch.code.toShort())
    dir.putShort(128 + 64, 18); dir.put(128 + 66, 2); dir.putInt(128 + 116, nf + 1); dir.putInt(128 + 120, stream.size)
    val out = ByteArrayOutputStream(sector * (1 + nf + 1 + nData))
    out.write(header.array())
    out.write(ByteBuffer.allocate(nf * 512).order(ByteOrder.LITTLE_ENDIAN).also { b -> fat.forEach { b.putInt(it) } }.array())
    out.write(dir.array())
    out.write(stream.copyOf(nData * sector))
    return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
  }

  private fun bof(type: Int, version: Int = 0x600) = rec(0x809, le(2 to version.toLong(), 2 to type.toLong(), 2 to 0, 2 to 0, 4 to 0, 4 to 6))

  private fun readWithinBudget(file: File, maxChars: Int = 1_000_000): ExtractedText {
    val t0 = System.nanoTime()
    val r = XlsText.extract(file, maxChars, tick = { if (System.nanoTime() - t0 > 20_000_000_000L) throw ExtractTimeout() })
    assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 20_000_000_000L)
    assertTrue(r.pages.sumOf { it.length } <= maxOf(maxChars, 0) + 64)
    return r
  }

  @Test fun everyTruncationOfAValidFileEndsWithoutAnException() {
    for (stream in listOf(workbookStream(), workbookStream(sharedExtra = (1..400).map { "padding string number $it" }))) {
      val bytes = ole(stream).readBytes()
      var length = 0
      while (length < bytes.size) {
        val f = tmp.newFile().also { it.writeBytes(bytes.copyOf(length)) }
        readWithinBudget(f)
        length += if (length < 700) 13 else 211
      }
    }
  }

  @Test fun truncatedBiffRecordsKeepWhatWasRead() {
    val stream = workbookStream()
    for (cut in 0 until stream.size step 7) readWithinBudget(ole(stream.copyOf(cut)))
  }

  @Test fun randomByteFlipsNeverThrowOrHang() {
    val rnd = java.util.Random(20261117)
    val files = listOf(ole(workbookStream()).readBytes(), ole(workbookStream(sharedExtra = (1..400).map { "padding $it" })).readBytes())
    repeat(400) { n ->
      val bytes = files[n % 2].copyOf()
      repeat(1 + rnd.nextInt(24)) { bytes[rnd.nextInt(bytes.size)] = rnd.nextInt(256).toByte() }
      readWithinBudget(tmp.newFile().also { it.writeBytes(bytes) })
    }
  }

  @Test fun aWorkbookThatDoesNotStartWithABiff8BofGivesNothing() {
    assertEquals(emptyList<String>(), readWithinBudget(ole(rec(0x0A, ByteArray(0)) + workbookStream())).pages)
    assertEquals(emptyList<String>(), readWithinBudget(ole(bof(5, version = 0x0500) + workbookStream().copyOfRange(20, workbookStream().size))).pages)
    assertEquals(emptyList<String>(), readWithinBudget(ole(ByteArray(300) { 0x42 })).pages)
  }

  @Test fun aSheetWithFarMoreCellsThanTheCapStopsAtTheCapAndSaysSo() {
    val out = ByteArrayOutputStream()
    out.write(bof(5)); out.write(rec(0x85, le(4 to 0, 1 to 0, 1 to 0) + le(1 to 1, 1 to 0) + "S".toByteArray())); out.write(rec(0x0A, ByteArray(0)))
    out.write(bof(0x10))
    // A one-character value: 200,000 cells print about 800,000 chars ("1 | "), inside the 1,000,000-char tabular budget
    // (ExtractLimits.TABULAR_MAX_CHARS, which also bounds any larger maxChars), so the cell cap is what stops the read.
    val value = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(1.0).array()
    for (k in 0 until 380_000) out.write(rec(0x203, le(2 to (k / 256).toLong(), 2 to (k % 256).toLong(), 2 to 0) + value))
    out.write(rec(0x0A, ByteArray(0)))
    val r = readWithinBudget(oleBig(out.toByteArray()))
    assertTrue(r.truncated)
    assertEquals(ExtractLimits.MAX_CELLS, r.pages.single().split(" | ", "\n").count { it == "1" })
  }

  @Test fun millionsOfEmptyRecordsAreSteppedOverQuickly() {
    val out = ByteArrayOutputStream()
    out.write(bof(5)); out.write(rec(0x0A, ByteArray(0))); out.write(bof(0x10))
    val empty = rec(0x1B, ByteArray(0))
    repeat(1_700_000) { out.write(empty) }
    assertEquals(emptyList<String>(), readWithinBudget(oleBig(out.toByteArray())).pages)
  }

  @Test fun aSharedStringTableClaimingBillionsOfStringsIsReadAsFarAsItGoes() {
    val sst = le(4 to 0xFFFFFFFFL, 4 to 0xFFFFFFFFL) + (1..50).fold(ByteArray(0)) { a, i -> a + str16("s$i") }
    val stream = bof(5) + rec(0xFC, sst) + rec(0x0A, ByteArray(0))
    readWithinBudget(ole(stream))
  }

  @Test fun aContainerWhoseChainsLoopOrPointOutsideTheFileGivesNothing() {
    val bytes = ole(workbookStream()).readBytes()
    val looped = bytes.copyOf()
    ByteBuffer.wrap(looped).order(ByteOrder.LITTLE_ENDIAN).putInt(512 + 4, 1)          // FAT: the directory sector points at itself
    readWithinBudget(tmp.newFile().also { it.writeBytes(looped) })
    val outside = bytes.copyOf()
    ByteBuffer.wrap(outside).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 100_000)        // directory starts far past the end
    assertEquals(emptyList<String>(), readWithinBudget(tmp.newFile().also { it.writeBytes(outside) }).pages)
  }

  @Test fun theCharBudgetCapsTheText() {
    val r = readWithinBudget(ole(workbookStream()), maxChars = 20)
    assertTrue(r.pages.sumOf { it.length } <= 20 + 64)
  }

  @Test fun cyclicDifatDoesNotAmplifyIntoOom() {
    val bytes = ole(workbookStream()).readBytes()
    val cyclicDifat = bytes.copyOf()
    // Point header DIFAT continuation (offset 68) at sector 0, and sector 0's tail next-DIFAT pointer back to sector 0.
    val bb = ByteBuffer.wrap(cyclicDifat).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(68, 0)
    bb.putInt(512 + 512 - 4, 0)
    val t0 = System.nanoTime()
    readWithinBudget(tmp.newFile().also { it.writeBytes(cyclicDifat) })
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertTrue("cyclic DIFAT should terminate immediately, took ${ms}ms", ms < 1_000)

    // Crafted huge DIFAT sector index (0x7FFFFFF0) must fail bounds check without allocating a huge BitSet.
    val hugeDifat = bytes.copyOf()
    ByteBuffer.wrap(hugeDifat).order(ByteOrder.LITTLE_ENDIAN).putInt(68, 0x7FFFFFF0)
    assertEquals(emptyList<String>(), readWithinBudget(tmp.newFile().also { it.writeBytes(hugeDifat) }).pages)
  }
}
