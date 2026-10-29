package com.craneai.findr.engine

import com.craneai.findr.core.ModelPlan
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.Refusal
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real published `trawl-models.zip` (BUNDLE_FILE, about 670 MB) through the app's own checks: whole it installs, cut short it
 * is refused as incomplete (not damaged) and has no zip end, and a copy grown in place in slow chunks is never installed until
 * it is complete and has held still. Skipped unless BUNDLE_FILE is set, so the normal gate does not need the file.
 */
class PublishedBundleDeliveryTest {
  @get:Rule val tmp = TemporaryFolder()
  private val bundle = System.getenv("BUNDLE_FILE")?.let(::File)

  private fun installer(dir: File) = SignedBundleInstaller(
    modelsDir = dir,
    phoneClass = BundleClass.STANDARD,
    modelNames = Embedder.modelNames(MemoryProfile.LITE),
    packDirs = listOf(PackCatalog.SPEECH, PackCatalog.QUICK_LOOK, PackCatalog.PRIVACY)
      .mapNotNull { PackFilesRegistry.of(it) }.associate { it.folderName to it.dir },
    acceptClasses = ModelPlan.acceptClasses(BundleClass.STANDARD),
  )

  private fun copyPrefix(src: File, dst: File, bytes: Long, append: Boolean = false) {
    RandomAccessFile(src, "r").use { r ->
      r.seek(if (append) dst.length() else 0)
      java.io.FileOutputStream(dst, append).use { out ->
        val buf = ByteArray(1 shl 20)
        var left = bytes
        while (left > 0) {
          val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
          if (n < 0) break
          out.write(buf, 0, n); left -= n
        }
      }
    }
  }

  @Test fun theWholeFileHasAnEndAndInstalls() {
    assumeTrue(bundle?.isFile == true)
    val f = bundle!!
    assertTrue("a finished bundle ends like a zip", ZipTail.hasEnd(f))
    val r = installer(tmp.newFolder()).install(f.inputStream())
    println("REAL WHOLE: ${r::class.simpleName} (${f.length()} bytes)")
    assertTrue(r.toString().take(300), r is BundleResult.Installed)
  }

  @Test fun theFileCutShortHasNoEndAndIsIncompleteNeverDamaged() {
    assumeTrue(bundle?.isFile == true)
    val f = bundle!!
    for (share in listOf(0.05, 0.3, 0.6, 0.999)) {
      val cut = tmp.newFile().also { copyPrefix(f, it, (f.length() * share).toLong()) }
      val hasEnd = ZipTail.hasEnd(cut)
      val r = installer(tmp.newFolder()).install(cut.inputStream()) as BundleResult.Refused
      println("REAL CUT $share (${cut.length() / 1_000_000} of ${f.length() / 1_000_000} MB): hasEnd=$hasEnd, ${r.reason} (${r.detail})")
      assertFalse("a cut file has no end", hasEnd)
      assertEquals("cut at $share", Refusal.INCOMPLETE, r.reason)
      cut.delete()
    }
  }

  @Test fun aDownloadGrownInPlaceInSlowChunksIsNotInstalledUntilCompleteAndStill() {
    assumeTrue(bundle?.isFile == true)
    val f = bundle!!
    val total = f.length()
    val dir = tmp.newFolder()
    val file = File(dir, "trawl-models.zip")
    val chunk = total / 24 + 1
    var previous = emptyMap<String, Seen>()
    val skip = HashSet<String>()
    var simNow = 1_000_000L                    // a simulated clock, so the 3 to 5 s pauses cost nothing; the file is real
    var nextWrite = simNow + 3_000
    var pause = 3_000L
    var lastWrite = simNow
    var installedAt = -1L
    var looks = 0
    while (installedAt < 0 && simNow < 1_000_000L + 400_000) {
      if (file.length() < total && simNow >= nextWrite) {
        copyPrefix(f, file, minOf(chunk, total - file.length()), append = true)
        file.setLastModified(simNow); lastWrite = simNow
        nextWrite = simNow + pause; pause = if (pause >= 5_000) 3_000 else pause + 1_000
      }
      if (file.exists()) {
        val listing = listOf(DownloadEntry(file.name, file.length(), file.lastModified()))
        val step = DownloadsWatch.step(listing, previous, simNow, skip) { ZipTail.hasEnd(file) }
        previous = DownloadsWatch.look(listing, previous, simNow)
        looks++
        when (step) {
          is DownloadStep.Install -> { installedAt = simNow; skip += DownloadsWatch.key(step.entry) }
          is DownloadStep.Downloading -> Unit
          else -> throw AssertionError("t=$simNow length=${file.length()} of $total: $step")
        }
        if (installedAt >= 0) assertEquals("installed only when complete", total, file.length())
      }
      simNow += 1_500
    }
    println("REAL IN-PLACE: $looks looks, last write at +${(lastWrite - 1_000_000) / 1000} s, install decided at +${(installedAt - 1_000_000) / 1000} s")
    assertEquals(total, file.length())
    assertTrue("installed ${installedAt - lastWrite} ms after the last write", installedAt - lastWrite >= DownloadsWatch.STABLE_MS)
    val r = installer(tmp.newFolder()).install(file.inputStream())
    assertTrue(r.toString().take(300), r is BundleResult.Installed)
  }
}
