package com.craneai.findr.extract

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Bytes to text without a user-supplied encoding: BOM first, then UTF-16 without BOM (lots of NULs), then strict
 * UTF-8, and Windows-1252 when the bytes aren't valid UTF-8 (the usual fate of old Western files).
 */
object TextDecoding {
  private val cp1252: Charset = Charset.forName("windows-1252")

  /**
   * Decodes `bytes[0, length)`. Set `cut` when the buffer is the head of a longer file, so a multi-byte character
   * split at the end isn't mistaken for invalid UTF-8.
   */
  fun decode(bytes: ByteArray, length: Int = bytes.size, cut: Boolean = false): String {
    if (length <= 0) return ""
    fun b(i: Int) = bytes[i].toInt() and 0xFF
    if (length >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return utf8OrCp1252(bytes, 3, length, cut)
    if (length >= 2 && b(0) == 0xFF && b(1) == 0xFE) return utf16(bytes, 2, length, Charsets.UTF_16LE)
    if (length >= 2 && b(0) == 0xFE && b(1) == 0xFF) return utf16(bytes, 2, length, Charsets.UTF_16BE)
    guessUtf16(bytes, length)?.let { return utf16(bytes, 0, length, it) }
    return utf8OrCp1252(bytes, 0, length, cut)
  }

  /** A PDF wearing a text file's name ("report.pdf.txt"): read it as the PDF it is. */
  fun isPdf(bytes: ByteArray, length: Int = bytes.size): Boolean =
    length >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

  /**
   * Binary data with a text file's name (random bytes, a database, an archive): decoding it would put pages of garbage
   * into the index (the messy corpus had a PDF named .txt become 6,535 chunks). UTF-16 is text despite its zeros; other
   * text has no NUL bytes and few control characters.
   */
  fun looksBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
    val n = minOf(length, 8192)
    if (n == 0) return false
    fun b(i: Int) = bytes[i].toInt() and 0xFF
    if (n >= 2 && ((b(0) == 0xFF && b(1) == 0xFE) || (b(0) == 0xFE && b(1) == 0xFF))) return false
    if (guessUtf16(bytes, n) != null) return false
    var control = 0
    for (i in 0 until n) {
      val c = b(i)
      if (c == 0) return true
      if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0C && c != 0x0D && c != 0x1A) control++
    }
    return control > n * 0.05
  }

  /** ASCII-heavy UTF-16 without a BOM has a zero in every other byte. Plain text never has that many NULs. */
  private fun guessUtf16(bytes: ByteArray, length: Int): Charset? {
    val n = minOf(length, 512) / 2
    if (n < 8) return null
    var evenZeros = 0
    var oddZeros = 0
    for (i in 0 until n) {
      if (bytes[2 * i].toInt() == 0) evenZeros++
      if (bytes[2 * i + 1].toInt() == 0) oddZeros++
    }
    return when {
      oddZeros >= n * 0.3 && evenZeros < n * 0.05 -> Charsets.UTF_16LE
      evenZeros >= n * 0.3 && oddZeros < n * 0.05 -> Charsets.UTF_16BE
      else -> null
    }
  }

  private fun utf16(bytes: ByteArray, from: Int, length: Int, charset: Charset): String {
    val count = (length - from) and 1.inv()
    return String(bytes, from, count, charset)
  }

  private fun utf8OrCp1252(bytes: ByteArray, from: Int, length: Int, cut: Boolean): String {
    val count = length - from
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val out = CharBuffer.allocate(count + 1)
    val result = decoder.decode(ByteBuffer.wrap(bytes, from, count), out, !cut)
    val ok = !result.isError && (cut || !decoder.flush(out).isError)
    if (ok) {
      out.flip()
      return out.toString().removePrefix("﻿")
    }
    return String(bytes, from, count, cp1252)
  }
}
