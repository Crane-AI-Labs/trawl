package com.craneai.findr.core

import java.io.File

/**
 * What "Delete everything Findr knows" removed. [bytes] is what Findr had learned about the person's files (the index
 * and any temporary copy), the number Settings shows; [cacheBytes] is the rest of the cache folder that went with it.
 */
data class Forgotten(val files: Int, val bytes: Long, val cacheBytes: Long = 0)

/**
 * Everything Findr derives from the user's files lives in two app-private places (the research notes has
 * the inventory, checked on a phone):
 *  - the index directory (`no_backup/index`): file names and folders, document text, OCR text, transcripts, sound
 *    labels and vectors, in one SQLite file plus its write-ahead log;
 *  - the cache directory: [scratchDir] (a document copied for random access while it is read), and the runtimes'
 *    own caches.
 * Settings (which kinds to read, excluded and private folders, private marks) and the AI models are not about the
 * user's files and stay, so a rebuild never makes a private file findable again.
 */
object LocalData {
  /** Temporary copies of documents being read. Each is deleted when its file is done; [sweepScratch] catches a killed process. */
  fun scratchDir(cacheDir: File): File = File(cacheDir, SCRATCH)

  /** Deletes what a killed process left in [scratchDir]. Run once at start, before anything reads a file. Returns files deleted. */
  fun sweepScratch(cacheDir: File): Int {
    val dir = scratchDir(cacheDir)
    var n = 0
    dir.walkBottomUp().forEach { f ->
      val wasFile = f.isFile
      if (f != dir && f.delete() && wasFile) n++
    }
    // Older builds wrote their copies straight into the cache directory.
    cacheDir.listFiles { f -> f.isFile && f.name.startsWith(LEGACY_PREFIX) }?.forEach { if (it.delete()) n++ }
    return n
  }

  /**
   * Deletes every file under [indexDir] (the index, its WAL and shared-memory files, any old `.corrupt-` copy) and
   * everything under [cacheDir] except [isModelCache] files. The caller closes the index first. The directories stay.
   */
  fun wipe(indexDir: File, cacheDir: File): Forgotten {
    var files = 0
    var learned = 0L
    var cache = 0L
    val scratch = scratchDir(cacheDir)
    for (root in listOf(indexDir, cacheDir)) {
      root.walkBottomUp().forEach { f ->
        if (f == root || (root == cacheDir && isModelCache(f))) return@forEach
        val size = if (f.isFile) f.length() else 0L
        val wasFile = f.isFile
        if (f.delete() && wasFile) {
          files++
          if (root == indexDir || f.startsWith(scratch)) learned += size else cache += size
        }
      }
    }
    return Forgotten(files, learned, cache)
  }

  /**
   * The model runtime's compiled caches, which LiteRT names after the model file ("….litertlm….xnnpack_cache",
   * "….litertlm.vision_encoder_…_weight_cache.bin"): 444 MB on the A06. Built from the model alone, they hold nothing
   * about the person's files, so they stay; deleting them only made the next model load rebuild them.
   */
  fun isModelCache(f: File): Boolean = f.isFile && f.name.contains(".litertlm")

  private const val SCRATCH = "scratch"
  private const val LEGACY_PREFIX = "extract"
}
