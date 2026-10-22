package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.core.Locator
import java.io.File

private val MODEL = File(System.getenv("TRAWL_MODEL") ?: "bench-data/models/embeddinggemma-2-text-vision-440m.litertlm")

/**
 * Reads the videos and recordings of a folder through the app's own IndexService (videos and recordings only), then asks each query and
 * prints where in the file the best hits are. [speechPack] is a folder holding the speech pack contents (copied into the data folder as
 * the installer would). Prints seconds, so seconds per media minute can be worked out against the length of the media read.
 */
object MediaBench {
  fun run(folder: File, speed: LearningSpeed, dataDir: File, speechPack: File?, queries: List<String>) {
    dataDir.deleteRecursively(); dataDir.mkdirs()
    if (speechPack != null) speechPack.copyRecursively(File(File(dataDir, "models"), ModelSetup.ASR_DIR), overwrite = true)
    IndexService(dataDir, log = {}, ocrChoice = { null }).use { ix ->
      ix.configure(setOf(FileKind.VIDEO, FileKind.AUDIO), emptyList(), speed, paused = false)
      ix.setFolders(listOf(folder))
      ix.attachModel(MODEL)
      var t0 = 0L
      val limit = System.currentTimeMillis() + 90 * 60_000
      while (System.currentTimeMillis() < limit) {
        val u = ix.ui.value
        if (u.error != null) error("index error: ${u.error}")
        if (u.modelReady && t0 == 0L) t0 = System.nanoTime()
        if (u.modelReady && u.phase == Phase.UP_TO_DATE && u.total > 0 && u.understood >= u.total) break
        Thread.sleep(50)
      }
      val secs = (System.nanoTime() - t0) / 1e9
      val u = ix.ui.value
      println("media speed=${speed.key} decoder=${u.mediaDecoder} speechPack=${u.speechPack} files=${u.total} understood=${u.understood} unreadable=${u.unreadable} seconds=%.1f rss_mb=${rssMb()}".format(secs))
      for (q in queries) {
        val r = ix.search(q, KindFilter.ALL)
        println("\n\"$q\": ${r.hits.size} hits (${r.strongCount} strong)")
        r.hits.take(5).forEach { h ->
          val at = (h.locator as? Locator.Time)?.startMs?.let { " at %d.%03d s".format(it / 1000, it % 1000) } ?: ""
          println("  %6.2f  %s%s  [%s]%s".format(h.score, h.file.relativePath, h.file.name, h.reason, at))
        }
      }
    }
  }
}
