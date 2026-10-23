package com.craneai.findr.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The speech models Findr needs, as the app expects to find them in `files/models/asr/`, with the size and SHA-256 of
 * each file so a truncated or swapped copy is never used. Pure JVM (no Android types) so it is unit-tested off the phone.
 *
 * The same relative layout is used everywhere: inside the folder or zip a tester copies into Downloads, in app storage,
 * and (later) inside a Play Asset Delivery pack. A pack is therefore just another [AsrModelSource].
 */
data class AsrModelFile(val path: String, val size: Long, val sha256: String, val required: Boolean = true)

object AsrModelPack {
  /** What to look for in Downloads: a folder with this name, or an archive named like this (a browser may add " (1)"). */
  const val FOLDER_NAME = "findr-speech-models"
  const val ARCHIVE_NAME = "trawl-speech-models.zip"

  val files: List<AsrModelFile> = listOf(
    // Parakeet-TDT-CTC 110M int8 (the default recogniser; CC-BY-4.0, NVIDIA)
    AsrModelFile("parakeet-tdt-ctc-110m-int8/model.int8.onnx", 131_652_171, "9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a"),
    AsrModelFile("parakeet-tdt-ctc-110m-int8/tokens.txt", 9_953, "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"),
    // Silero VAD (MIT)
    AsrModelFile("silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
    // CED-mini AudioSet sound labels (Apache-2.0)
    AsrModelFile("ced-mini-int8/model.int8.onnx", 10_451_715, "ff29f39f9fbe637f72535160e9d006d61d872fdab0fce838672265b9b38cf946"),
    AsrModelFile("ced-mini-int8/class_labels_indices.csv", 14_675, "cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429"),
    // Moonshine tiny int8 (MIT): the selectable alternative (engine.txt = "moonshine"). Optional, left out of the default pack.
    AsrModelFile("moonshine-tiny-en-int8/preprocess.onnx", 6_800_738, "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e", required = false),
    AsrModelFile("moonshine-tiny-en-int8/encode.int8.onnx", 18_249_187, "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb", required = false),
    AsrModelFile("moonshine-tiny-en-int8/uncached_decode.int8.onnx", 53_216_096, "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41", required = false),
    AsrModelFile("moonshine-tiny-en-int8/cached_decode.int8.onnx", 45_264_830, "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6", required = false),
    AsrModelFile("moonshine-tiny-en-int8/tokens.txt", 436_688, "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea", required = false),
  )
}

/** Where model files come from. `null` means "this source does not have that file". */
interface AsrModelSource {
  val label: String
  fun open(path: String): InputStream?
  fun close() {}
}

/** A folder laid out like `files/models/asr/` (a tester copied `findr-speech-models/` into Downloads, or adb pushed it). */
class FolderModelSource(private val dir: File) : AsrModelSource {
  override val label get() = dir.path
  override fun open(path: String): InputStream? = File(dir, path).takeIf { it.isFile }?.inputStream()
}

/**
 * An archive with the same layout, either at the zip's root or under one wrapping folder (what "Compress" on a Mac makes).
 * Entries are only ever looked up by the manifest's own paths, so a hostile archive cannot write anywhere.
 *
 * [prefix] is for the one-file bundle (`trawl-models.zip`, [ModelBundle]), where each pack sits in its own folder inside the
 * archive: the pack's files are then looked up under `findr-speech-models/` and so on.
 */
class ZipModelSource(private val archive: File, private val prefix: String = "") : AsrModelSource {
  private val zip = ZipFile(archive)
  /** The one top-level folder every entry sits under, if there is exactly one ("" otherwise). */
  private val wrapper: String = run {
    val tops = zip.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") }
      .map { if (it.name.contains('/')) it.name.substringBefore('/') + "/" else "" }.toSet()
    tops.singleOrNull().orEmpty()
  }
  override val label get() = archive.path

  /** The entry for a manifest path, at the archive's root or under its one wrapping folder; null when absent or a folder. */
  fun entry(path: String): ZipEntry? =
    (zip.getEntry(prefix + path) ?: zip.getEntry(wrapper + prefix + path))?.takeIf { !it.isDirectory }

  /** True when the archive has anything under [prefix]: a bundle that does not carry a pack is not a failed source for it. */
  fun carriesPrefix(): Boolean =
    zip.entries().asSequence().any { !it.isDirectory && it.name.removePrefix(wrapper).startsWith(prefix) }

  fun stream(entry: ZipEntry): InputStream = zip.getInputStream(entry)

  override fun open(path: String): InputStream? = entry(path)?.let { zip.getInputStream(it) }
  override fun close() = zip.close()
}

/**
 * Copies model files from a source into [destDir], checking size and SHA-256 on the way. Each file is copied to `name.part`
 * and renamed only when it checks out, so a half-copied or corrupt file is never seen by the engine (same rule as the
 * embedding model's installer). A file already in place with the right size is left alone.
 */
class AsrModelInstaller(
  private val destDir: File,
  private val manifest: List<AsrModelFile> = AsrModelPack.files,
) {
  sealed interface Outcome {
    /** Everything required is in place. [copied] lists the files this call wrote. */
    data class Ready(val copied: List<String>, val bytes: Long) : Outcome
    /** The source lacked some required files, or a file failed its check: [problems] says which and why. */
    data class Incomplete(val copied: List<String>, val problems: List<String>) : Outcome
  }

  private fun installed(f: AsrModelFile) = File(destDir, f.path).let { it.isFile && it.length() == f.size }

  /** True when every required file is installed (a size check only: files are renamed into place after their hash passed). */
  fun complete(): Boolean = manifest.filter { it.required }.all(::installed)

  /** Bytes of manifest files in place (what removing the pack frees). */
  fun installedBytes(): Long = manifest.filter(::installed).sumOf { it.size }

  /** Required files not yet installed. */
  fun missing(): List<AsrModelFile> = manifest.filter { it.required && !installed(it) }

  /** [onProgress] gets (bytes copied so far, bytes expected) as data moves, for a progress bar; it runs on the copying thread. */
  fun install(source: AsrModelSource, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Outcome {
    val copied = mutableListOf<String>()
    val problems = mutableListOf<String>()
    var bytes = 0L
    val total = missing().sumOf { it.size }
    onProgress(0, total)
    for (f in manifest) {
      if (installed(f)) continue
      val input = source.open(f.path)
      if (input == null) {
        if (f.required) problems += "${f.path}: not in ${source.label}"
        continue
      }
      try {
        val before = bytes
        // Optional files are not in the total, so a pack that carries them tops out at the total instead of going past it.
        copyChecked(input, f) { n -> onProgress(minOf(before + n, total), total) }
        copied += f.path
        bytes += f.size
      } catch (e: IOException) {
        problems += "${f.path}: ${e.message}"
      }
    }
    return if (complete()) Outcome.Ready(copied, bytes) else Outcome.Incomplete(copied, problems)
  }

  private fun copyChecked(input: InputStream, f: AsrModelFile, onBytes: (Long) -> Unit) {
    val target = File(destDir, f.path)
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      var n = 0L
      val buffer = ByteArray(1 shl 20)
      DigestInputStream(input, digest).use { src ->
        part.outputStream().use { out ->
          while (true) {
            val read = src.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            n += read
            onBytes(n)
          }
        }
      }
      if (n != f.size) throw IOException("size $n, expected ${f.size}")
      val got = digest.digest().joinToString("") { "%02x".format(it) }
      if (got != f.sha256) throw IOException("checksum mismatch")
      target.delete()
      if (!part.renameTo(target)) throw IOException("could not move into place")
    } finally {
      part.delete()   // gone after a rename; removes a failed copy
    }
  }
}
