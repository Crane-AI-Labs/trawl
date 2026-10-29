package com.craneai.findr.engine

import com.craneai.findr.core.Refusal
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipException

/**
 * Is a zip finished? A zip file ends with an End Of Central Directory record (EOCD), written last, so a file with no EOCD in its
 * last bytes is a download that is still coming (or that stopped). Reading only the tail makes this cheap on a 670 MB file.
 * The record is 22 bytes plus a comment of up to 65,535 bytes, so the last [WINDOW] bytes always hold it when it exists.
 *
 * What this proves: the END of the file arrived. A downloader that fills the file in pieces (a preallocated file, several
 * connections) can write the end first, so a present EOCD is necessary but not enough: the install's own size and SHA-256
 * checks, and the file no longer changing, are the rest (see DownloadsWatch in the app).
 */
object ZipTail {
  /** 22-byte record + the longest comment a zip can carry. */
  const val WINDOW = 22 + 65_535 + 1

  private const val EOCD_LEN = 22
  private const val SIGNATURE = 0x06054b50

  /** True when [file] ends with a well-formed EOCD. A file that cannot be read counts as not finished. */
  fun hasEnd(file: File): Boolean = try {
    RandomAccessFile(file, "r").use { raf ->
      val size = raf.length()
      val n = minOf(size, WINDOW.toLong()).toInt()
      val tail = ByteArray(n)
      raf.seek(size - n)
      raf.readFully(tail)
      hasEnd(tail, size - n)
    }
  } catch (_: IOException) {
    false
  }

  /**
   * [hasEnd] over a seekable channel (a chosen file's descriptor). Null when the length or the tail cannot be read (a pipe, a provider
   * that streams): the caller cannot tell, and the install itself will say whether the data runs out.
   */
  fun hasEnd(ch: SeekableByteChannel): Boolean? = try {
    val size = ch.size()
    val n = minOf(size, WINDOW.toLong()).toInt()
    val buf = ByteBuffer.allocate(n)
    ch.position(size - n)
    while (buf.hasRemaining()) if (ch.read(buf) < 0) break
    if (buf.hasRemaining()) null else hasEnd(buf.array(), size - n)
  } catch (_: IOException) {
    null
  } catch (_: UnsupportedOperationException) {
    null
  }

  /**
   * [tail] is the last bytes of a file whose first [tailStart] bytes are not in it. The last EOCD signature counts only if its
   * comment length reaches exactly to the end of the file, and its central directory (when the sizes are not the zip64
   * markers) lies before the record: stray bytes that look like the signature inside the data do not pass.
   */
  fun hasEnd(tail: ByteArray, tailStart: Long = 0): Boolean {
    var i = tail.size - EOCD_LEN
    while (i >= 0) {
      if (u32(tail, i) == SIGNATURE) {
        val comment = u16(tail, i + 20)
        if (i + EOCD_LEN + comment == tail.size) {
          val cdSize = u32(tail, i + 12).toLong() and 0xFFFFFFFFL
          val cdOffset = u32(tail, i + 16).toLong() and 0xFFFFFFFFL
          val zip64 = cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL
          if (zip64 || cdSize + cdOffset <= tailStart + i) return true
        }
      }
      i--
    }
    return false
  }

  private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
  private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}

/**
 * Which [Refusal] an exception from reading or installing a bundle means. One place, so the installer and the app agree:
 * - a full disk is [Refusal.NO_SPACE] (checked first: Android words a failed create as "FileNotFoundException ... ENOSPC");
 * - a file that runs out is [Refusal.INCOMPLETE] (a download that stopped), not damage;
 * - a file that cannot be opened or read at all is [Refusal.UNREADABLE];
 * - everything else (a broken zip, an I/O error mid-read) is [Refusal.DAMAGED].
 */
object FailureKind {
  fun of(t: Throwable): Refusal {
    val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8).toList()
    return when {
      chain.any(::outOfSpace) -> Refusal.NO_SPACE
      chain.any(::truncated) -> Refusal.INCOMPLETE
      chain.any { it is SecurityException || it is FileNotFoundException } -> Refusal.UNREADABLE
      else -> Refusal.DAMAGED
    }
  }

  /** ENOSPC: Android words it "write failed: ENOSPC (No space left on device)", a JVM "No space left on device". */
  fun outOfSpace(t: Throwable): Boolean {
    val m = t.message.orEmpty()
    return t is IOException && (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true))
  }

  /** The stream ended early: ZipInputStream says "unexpected EOF" for a stored entry, and EOFException for a deflated one or a cut header. */
  fun truncated(t: Throwable): Boolean =
    t is EOFException || (t is ZipException && t.message.orEmpty().let { it.contains("unexpected EOF", ignoreCase = true) || it.contains("Unexpected end", ignoreCase = true) })
}
