package trawl.desktop

import com.craneai.findr.core.FileKind
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `.ts` is TypeScript or video: the first bytes decide, and only for the three transport-stream extensions. */
class TransportStreamTest {
  private fun fixture(name: String): ByteArray {
    val text = TransportStreamTest::class.java.getResourceAsStream("/transport/$name")!!.use { it.readBytes() }.toString(Charsets.US_ASCII).trim()
    return Base64.getDecoder().decode(text)
  }

  /** The first 600 bytes of a `.ts` that ffmpeg made (`-f mpegts`): 188-byte packets, sync byte 0x47 at 0, 188 and 376. */
  private val realTs = fixture("ffmpeg-ts-head.b64")

  /** The first 600 bytes of an AVCHD / Blu-ray `.m2ts` that ffmpeg made (`-mpegts_m2ts_mode 1`): 192-byte packets, a 4-byte timestamp first, sync at 4, 196, 388. */
  private val realM2ts = fixture("ffmpeg-m2ts-head.b64")

  private val typeScript = (
    "import { Component } from '@angular/core';\n" +
      "export interface Props { id: number; name: string }\n" +
      "export function greet(p: Props): string { return \"Hello, \" + p.name + \"!\"; }\n" +
      "// " + "G".repeat(300) + "\n" +
      "export const answer = 42;\n"
    ).toByteArray()

  private fun tmp(name: String, bytes: ByteArray): File = File(Files.createTempDirectory("trawl-ts").toFile().canonicalFile, name).apply { writeBytes(bytes) }

  @Test fun theFixturesAreWhatTheyClaimToBe() {
    assertEquals(600, realTs.size); assertEquals(600, realM2ts.size)
    for (at in listOf(0, 188, 376)) assertEquals(0x47, realTs[at].toInt() and 0xff, "ts sync at $at")
    for (at in listOf(4, 196, 388)) assertEquals(0x47, realM2ts[at].toInt() and 0xff, "m2ts sync at $at")
  }

  @Test fun aRealMpegTransportStreamIsOne() {
    assertTrue(TransportStream.looksLike(realTs))
    assertTrue(TransportStream.check(tmp("camera.ts", realTs).toPath()))
    assertTrue(TransportStream.check(tmp("holiday.mts", realTs).toPath()))
  }

  @Test fun anAvchdM2tsWith192BytePacketsIsOne() {
    assertTrue(TransportStream.looksLike(realM2ts))
    assertTrue(TransportStream.check(tmp("00001.m2ts", realM2ts).toPath()))
  }

  @Test fun aTypeScriptFileIsNot() {
    assertFalse(TransportStream.looksLike(typeScript))
    assertFalse(TransportStream.check(tmp("index.ts", typeScript).toPath()))
    // A 0x47 ("G") here and there by bad luck is not a stream: the packets must agree at the right distances.
    val unlucky = ByteArray(600) { 'a'.code.toByte() }.also { it[0] = 0x47; it[400] = 0x47 }
    assertFalse(TransportStream.looksLike(unlucky))
    val unlucky2 = ByteArray(600) { 'a'.code.toByte() }.also { it[0] = 0x47; it[188] = 0x47 }   // two agree, the third place does not
    assertFalse(TransportStream.looksLike(unlucky2))
  }

  @Test fun shortAndEmptyFilesAreJudgedOnWhatTheyHold() {
    assertFalse(TransportStream.looksLike(ByteArray(0)))
    assertFalse(TransportStream.looksLike(ByteArray(1) { 0x47 }))
    assertFalse(TransportStream.check(tmp("empty.ts", ByteArray(0)).toPath()))
    // One whole packet and a bit of a second: two sync bytes agree and the head is too short for a third.
    val two = ByteArray(190).also { it[0] = 0x47; it[188] = 0x47 }
    assertTrue(TransportStream.looksLike(two))
    assertFalse(TransportStream.check(File("/no/such/file.ts").toPath()), "a file that cannot be read says nothing")
  }

  @Test fun onlyTheThreeExtensionsAreLookedInside() {
    for (n in listOf("a.ts", "A.TS", "x.mts", "x.M2TS", "00001.m2ts")) assertTrue(TransportStream.isAmbiguous(n), n)
    for (n in listOf("a.mp4", "a.tsx", "a.txt", "ts", "mts", "a.ts.bak", "a.mov")) assertFalse(TransportStream.isAmbiguous(n), n)
  }

  @Test fun readsNoMoreThanTheHead() {
    // A 50 MB file whose first 600 bytes are a stream: the answer comes from the head alone.
    val big = File(Files.createTempDirectory("trawl-ts").toFile().canonicalFile, "big.ts")
    big.outputStream().use { o -> o.write(realTs); val chunk = ByteArray(1 shl 20); repeat(50) { o.write(chunk) } }
    val t0 = System.nanoTime()
    assertTrue(TransportStream.check(big.toPath()))
    assertTrue((System.nanoTime() - t0) / 1e6 < 500, "reads the head only")
  }

  // ---- the crawl's hook: Rules.classify then the sniff ----------------------------------------------------------

  @Test fun theCrawlKeepsRealVideosAndLeavesTypeScriptOut() {
    val dir = Files.createTempDirectory("trawl-ts").toFile().canonicalFile
    val ts = File(dir, "app.ts").apply { writeBytes(typeScript) }
    val mts = File(dir, "types.mts").apply { writeBytes(typeScript) }
    val real = File(dir, "camera.ts").apply { writeBytes(realTs) }
    val avchd = File(dir, "00001.m2ts").apply { writeBytes(realM2ts) }
    val mp4 = File(dir, "clip.mp4").apply { writeBytes(ByteArray(64)) }
    fun kind(f: File) = DocFormats.classify(f.toPath(), f.name, "")
    assertEquals(FileKind.OTHER, kind(ts)); assertEquals(FileKind.OTHER, kind(mts))
    assertEquals(FileKind.VIDEO, kind(real)); assertEquals(FileKind.VIDEO, kind(avchd))
    assertEquals(FileKind.VIDEO, kind(mp4), "other video types are not sniffed")
    assertTrue(FileKind.OTHER !in Crawler.KINDS, "OTHER is not kept, so a skipped file never reaches the index or the decoder")
    dir.deleteRecursively()
  }

  /** The bytes ffmpeg writes today, when ffmpeg is installed (Crane has it; a computer without it skips this). */
  @Test fun aTransportStreamMadeByFfmpegRightNowIsRecognised() {
    val dir = Files.createTempDirectory("trawl-ts").toFile().canonicalFile
    fun make(out: File, vararg extra: String): Boolean = try {
      val p = ProcessBuilder(listOf("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10", "-pix_fmt", "yuv420p") + extra + out.path)
        .redirectErrorStream(true).start()
      p.inputStream.readBytes(); p.waitFor(60, TimeUnit.SECONDS) && out.length() > 0
    } catch (_: Exception) { false }
    val ts = File(dir, "live.ts"); val m2ts = File(dir, "live.m2ts")
    if (!make(ts, "-f", "mpegts")) { dir.deleteRecursively(); return }   // no ffmpeg here
    assertTrue(TransportStream.check(ts.toPath()), "a .ts from ffmpeg")
    if (make(m2ts, "-f", "mpegts", "-mpegts_m2ts_mode", "1")) assertTrue(TransportStream.check(m2ts.toPath()), "an .m2ts from ffmpeg")
    dir.deleteRecursively()
  }
}
