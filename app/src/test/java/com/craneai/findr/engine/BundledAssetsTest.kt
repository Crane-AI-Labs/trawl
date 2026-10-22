package com.craneai.findr.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Word lists are gzipped in the source tree, but the APK packager stores them decompressed under the name without ".gz"
 * (assets/english_words.txt in the release APK). The loaders must read both forms; on the phone the gate's log line
 * "English word list loaded" and "Common word list loaded" prove the asset name half.
 */
class BundledAssetsTest {
  private val gz = File("src/main/assets/${SpeechLanguageGate.ASSET}")

  @Test fun theWordListReadsTheSameGzippedOrAsPackaged() {
    val fromSource = gz.inputStream().use { EnglishWords.fromGzip(it) }
    val packaged = GZIPInputStream(gz.inputStream()).use { it.readBytes() }   // what the APK holds
    val fromApk = ByteArrayInputStream(packaged).use { EnglishWords.fromGzip(it) }
    assertEquals(fromSource.sizeBytes, fromApk.sizeBytes)
    listOf("market", "dawn", "receipts", "embwa", "mbwa").forEach { assertEquals(it, fromSource.isWord(it), fromApk.isWord(it)) }
    // the common-word list the gate judges with is packaged the same way
    val commonGz = File("src/main/assets/${SpeechLanguageGate.COMMON_ASSET}")
    val common = commonGz.inputStream().use { EnglishWords.fromGzip(it) }
    val commonApk = ByteArrayInputStream(GZIPInputStream(commonGz.inputStream()).use { it.readBytes() }).use { EnglishWords.fromGzip(it) }
    assertEquals(common.sizeBytes, commonApk.sizeBytes)
    listOf("market", "the", "mbwa", "ekku").forEach { assertEquals(it, common.contains(it), commonApk.contains(it)) }
    val gate = SpeechLanguageGate(lazyOf(fromApk), lazyOf(commonApk))
    assertEquals(false, gate.judge("Ekku Kamoldavva Kitiwa Tishin Shina Holy de me They won get of dom Nala").english)
  }

  @Test fun onlyGzipContentIsUngzipped() {
    val text = "hello\nworld\n".toByteArray()
    assertArrayEquals(text, BundledAssets.ungzipIfNeeded(ByteArrayInputStream(text)).readBytes())
    val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text) } }.toByteArray()
    assertArrayEquals(text, BundledAssets.ungzipIfNeeded(ByteArrayInputStream(zipped)).readBytes())
    assertArrayEquals(ByteArray(0), BundledAssets.ungzipIfNeeded(ByteArrayInputStream(ByteArray(0))).readBytes())
  }
}
