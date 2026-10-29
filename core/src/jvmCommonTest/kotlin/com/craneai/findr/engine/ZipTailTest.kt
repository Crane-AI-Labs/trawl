package com.craneai.findr.engine

import com.craneai.findr.core.Refusal
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ZipTail]: does the file end like a zip; [FailureKind]: which refusal an exception means. */
class ZipTailTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun zip(comment: String? = null, deflate: Boolean = false, size: Int = 200_000): ByteArray {
    val data = ByteArray(size) { (it * 31 % 251).toByte() }
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
      if (comment != null) z.setComment(comment)
      val e = ZipEntry("a.bin")
      if (!deflate) {
        e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
        e.crc = java.util.zip.CRC32().also { it.update(data) }.value
      }
      z.putNextEntry(e); z.write(data); z.closeEntry()
    }
    return bos.toByteArray()
  }

  @Test fun aWholeZipEndsLikeOneAndEveryCutDoesNot() {
    val z = zip()
    assertTrue(ZipTail.hasEnd(z))
    for (cut in listOf(1, 5, 21, 22, 100, z.size / 2)) assertFalse("cut $cut", ZipTail.hasEnd(z.copyOf(z.size - cut)))
  }

  @Test fun aCommentAtTheEndIsFoundAndATruncatedCommentIsNot() {
    val z = zip(comment = "x".repeat(5_000))
    assertTrue(ZipTail.hasEnd(z))
    assertFalse(ZipTail.hasEnd(z.copyOf(z.size - 10)))
  }

  @Test fun aSignatureLookingByteRunInsideTheDataIsNotAnEnd() {
    // The EOCD signature written into the data, with a comment length that does not reach the end of the file.
    val fake = byteArrayOf(0x50, 0x4b, 0x05, 0x06) + ByteArray(18) + ByteArray(100)
    assertFalse(ZipTail.hasEnd(ByteArray(1_000) + fake))
  }

  @Test fun aPreallocatedFileOfZerosIsNotAnEnd() {
    assertFalse(ZipTail.hasEnd(ByteArray(300_000)))
  }

  @Test fun theFileFormReadsOnlyTheTailAndHandlesSmallAndMissingFiles() {
    val z = zip(size = 3_000_000)
    val f = tmp.newFile("a.zip").also { it.writeBytes(z) }
    assertTrue(ZipTail.hasEnd(f))
    f.writeBytes(z.copyOf(z.size - 1))
    assertFalse(ZipTail.hasEnd(f))
    f.writeBytes(ByteArray(0))
    assertFalse(ZipTail.hasEnd(f))
    f.writeBytes(ByteArray(7))
    assertFalse(ZipTail.hasEnd(f))
    assertFalse(ZipTail.hasEnd(File(tmp.root, "nope.zip")))
  }

  @Test fun theChannelFormAgreesWithTheFileForm() {
    val z = zip(size = 1_000_000)
    val whole = tmp.newFile("w.zip").also { it.writeBytes(z) }
    val cut = tmp.newFile("c.zip").also { it.writeBytes(z.copyOf(z.size - 3)) }
    FileChannel.open(whole.toPath(), StandardOpenOption.READ).use { assertEquals(true, ZipTail.hasEnd(it)) }
    FileChannel.open(cut.toPath(), StandardOpenOption.READ).use { assertEquals(false, ZipTail.hasEnd(it)) }
  }

  @Test fun aBigFileOnlyNeedsTheLastWindow() {
    // 5 MB of zeros then the real end: the central directory offset points far back, outside the window, which must still pass.
    val z = zip(size = 100)
    val big = ByteArray(5_000_000) + z
    assertTrue(ZipTail.hasEnd(big.copyOfRange(big.size - ZipTail.WINDOW.coerceAtMost(big.size), big.size), (big.size - ZipTail.WINDOW).toLong()))
  }

  // ---- FailureKind ------------------------------------------------------------------------------------------------

  private fun readFrom(bytes: ByteArray): Throwable {
    val t = try {
      java.util.zip.ZipInputStream(bytes.inputStream()).use { zin ->
        while (zin.nextEntry != null) zin.readBytes()
      }
      null
    } catch (e: Throwable) { e }
    return t ?: error("a cut zip must not read cleanly")
  }

  @Test fun aStoredEntryCutShortAndADeflatedOneCutShortBothMeanIncomplete() {
    val stored = zip()
    assertEquals(Refusal.INCOMPLETE, FailureKind.of(readFrom(stored.copyOf(stored.size / 2))))
    val deflated = zip(deflate = true)
    assertEquals(Refusal.INCOMPLETE, FailureKind.of(readFrom(deflated.copyOf(deflated.size / 2))))
    assertEquals(Refusal.INCOMPLETE, FailureKind.of(EOFException()))
    assertEquals(Refusal.INCOMPLETE, FailureKind.of(ZipException("unexpected EOF")))
  }

  @Test fun aFullDiskMeansNoSpaceEvenWhenWrappedInAFileNotFound() {
    assertEquals(Refusal.NO_SPACE, FailureKind.of(IOException("write failed: ENOSPC (No space left on device)")))
    assertEquals(Refusal.NO_SPACE, FailureKind.of(FileNotFoundException("/data/x.part: open failed: ENOSPC (No space left on device)")))
    assertEquals(Refusal.NO_SPACE, FailureKind.of(RuntimeException("wrapper", IOException("No space left on device"))))
  }

  @Test fun aFileThatCannotBeOpenedIsUnreadableAndAnythingElseIsDamaged() {
    assertEquals(Refusal.UNREADABLE, FailureKind.of(SecurityException("permission denied")))
    assertEquals(Refusal.UNREADABLE, FailureKind.of(FileNotFoundException("gone")))
    assertEquals(Refusal.DAMAGED, FailureKind.of(ZipException("invalid entry CRC")))
    assertEquals(Refusal.DAMAGED, FailureKind.of(IOException("Input/output error")))
    assertEquals(Refusal.DAMAGED, FailureKind.of(IllegalStateException("boom")))
  }

  @Test fun aCauseChainThatLoopsDoesNotHang() {
    val a = RuntimeException("a")
    val b = RuntimeException("b", a)
    a.initCause(b)
    assertEquals(Refusal.DAMAGED, FailureKind.of(a))
  }
}
