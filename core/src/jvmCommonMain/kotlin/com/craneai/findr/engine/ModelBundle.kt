package com.craneai.findr.engine

import java.io.File
import java.io.IOException
import java.util.zip.CRC32

/**
 * The one-file models bundle: `trawl-models.zip` (or the unzipped folder `trawl-models/`) in Downloads, made by
 * scripts/pack-bundle.sh. One file is what people pass around over WhatsApp or Xender, so it carries everything a new phone
 * needs: the embedding model at the archive's root and each pack in its own folder (`findr-speech-models/`,
 * `findr-quicklook-models/`), exactly the layout of the separate downloads. The separate files keep working; whatever is
 * found is checked the same way (size and SHA-256 for packs, size and the zip's own CRC-32 for the model).
 *
 * Pure JVM, so the lookups and the checked copy are unit-tested. The packs are read through [ZipModelSource] with a prefix
 * ([findPackSources]); this object is the model's half.
 */
object ModelBundle {
  const val FOLDER_NAME = "trawl-models"
  const val ARCHIVE_NAME = "trawl-models.zip"
  const val LITE_ARCHIVE_NAME = "trawl-models-lite.zip"
  /** The lean download's later pieces (scripts/pack-bundle.sh VARIANT=vision|speech); the core bundle is the lite one. */
  const val VISION_ARCHIVE_NAME = "trawl-models-vision.zip"
  const val SPEECH_ARCHIVE_NAME = "trawl-models-speech.zip"
  val ARCHIVE = Regex("""trawl-models.*\.zip""", RegexOption.IGNORE_CASE)

  /** The bundle archives in Downloads, newest first (a browser may have saved "trawl-models (1).zip"). */
  fun archives(inDownloads: List<File>): List<File> =
    inDownloads.filter { it.isFile && ARCHIVE.matches(it.name) }.sortedByDescending { it.lastModified() }

  /** The unzipped bundle folder, if a tester copied that instead. */
  fun folder(inDownloads: List<File>): File? = inDownloads.firstOrNull { it.isDirectory && it.name == FOLDER_NAME }

  /**
   * Copies the first of [wanted] (best first) that the bundle carries into [modelsDir], through `name.part`, renamed only
   * when the size (and, from an archive, the CRC-32) matches. Returns the installed file's name, or null when no bundle
   * carries a model. A bundle that can't be read, or whose model fails its check, is reported to [onProblem] and the next
   * one is tried, so a damaged transfer is never silent and never used.
   */
  fun installModel(
    inDownloads: List<File>,
    wanted: List<String>,
    modelsDir: File,
    minBytes: Long = MIN_MODEL_BYTES,
    onProblem: (File, Throwable) -> Unit = { _, _ -> },
  ): String? {
    folder(inDownloads)?.let { dir ->
      val file = wanted.map { File(dir, it) }.firstOrNull { it.isFile && it.length() > minBytes }
      if (file != null) {
        try {
          copyChecked(file.inputStream(), File(modelsDir, file.name), file.length(), null)
          return file.name
        } catch (e: IOException) {
          onProblem(dir, e)
        }
      }
    }
    for (archive in archives(inDownloads)) {
      try {
        val zip = ZipModelSource(archive)
        try {
          val (name, entry) = wanted.firstNotNullOfOrNull { n -> zip.entry(n)?.takeIf { it.size > minBytes }?.let { n to it } } ?: continue
          copyChecked(zip.stream(entry), File(modelsDir, name), entry.size, entry.crc.takeIf { it >= 0 })
          return name
        } finally {
          zip.close()
        }
      } catch (e: IOException) {   // ZipException is one: not a zip, truncated, a damaged entry
        onProblem(archive, e)
      }
    }
    return null
  }

  /** Copy to `name.part`, verify, rename: a half-copied model is never seen by the engine. */
  private fun copyChecked(input: java.io.InputStream, target: File, size: Long, crc: Long?) {
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    try {
      val check = CRC32()
      var n = 0L
      input.use { src ->
        part.outputStream().use { out ->
          val buffer = ByteArray(1 shl 20)
          while (true) {
            val read = src.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            check.update(buffer, 0, read)
            n += read
          }
        }
      }
      if (n != size) throw IOException("copied $n of $size bytes")
      if (crc != null && check.value != crc) throw IOException("checksum mismatch")
      target.delete()
      if (!part.renameTo(target)) throw IOException("could not move into place")
    } catch (t: Throwable) {
      part.delete()
      throw t
    }
  }

  /** The loose model is accepted from 100 MB up (ModelInstaller): the same floor for a model inside a bundle. */
  const val MIN_MODEL_BYTES = 100_000_000L
}
