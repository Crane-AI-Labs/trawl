package com.craneai.findr.search

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Optional ARM64 NEON `SDOT` native acceleration for SQ8 (`int8`) vector scanning.
 *
 * Loads `libtrawl_vec_neon.dylib` (macOS ARM64) or `libtrawl_vec_neon.so` (Linux ARM64) when present via
 * `trawl.vec.native.path`, classpath resource, or `java.library.path`. On x86_64, Windows, Android without
 * the shared library, or if loading fails for any reason, [available] is `false` and [VectorIndex] runs
 * its identical pure-JVM SQ8 path without error.
 */
object Sq8Native {
  /** Probe result: 0 = unavailable, 1 = native loaded (scalar C), 2 = ARM64 NEON SDOT active. */
  val mode: Int = tryLoad()

  /** True when the native SQ8 kernel loaded and passed its symbol probe. */
  val available: Boolean get() = mode > 0

  /** True when hardware ARM64 NEON `SDOT` (`vdotq_s32`) is compiled into the loaded library. */
  val neonDotProd: Boolean get() = mode == 2

  @JvmStatic
  external fun probe(): Int

  @JvmStatic
  external fun scoreChunkDirect(
    chunk: ByteBuffer,
    vecsOffset: Int,
    scalesOffset: Int,
    rowFrom: Int,
    rowTo: Int,
    dim: Int,
    qHi: ByteArray,
    qHiScale: Float,
    qLo: ByteArray,
    qLoScale: Float,
    out: FloatArray,
    outOffset: Int,
  )

  @JvmStatic
  external fun scoreChunkDirectFiltered(
    chunk: ByteBuffer,
    vecsOffset: Int,
    scalesOffset: Int,
    rowFrom: Int,
    rowTo: Int,
    dim: Int,
    qHi: ByteArray,
    qHiScale: Float,
    qLo: ByteArray,
    qLoScale: Float,
    kinds: ByteArray,
    kindsOffset: Int,
    allowedKinds: BooleanArray,
    out: FloatArray,
    outOffset: Int,
  )

  @JvmStatic
  external fun scoreRowsDirect(
    chunk: ByteBuffer,
    vecsOffset: Int,
    scalesOffset: Int,
    chunkRows: IntArray,
    rowsFrom: Int,
    rowsTo: Int,
    dim: Int,
    qHi: ByteArray,
    qHiScale: Float,
    qLo: ByteArray,
    qLoScale: Float,
    out: FloatArray,
    outOffset: Int,
  )

  @JvmStatic
  external fun scoreHeap(
    vecs: ByteArray,
    scales: FloatArray,
    rowFrom: Int,
    rowTo: Int,
    dim: Int,
    qHi: ByteArray,
    qHiScale: Float,
    qLo: ByteArray,
    qLoScale: Float,
    out: FloatArray,
    outOffset: Int,
  )

  private fun tryLoad(): Int {
    if (System.getProperty("trawl.vec.native.disable") == "true") return 0
    return try {
      val explicit = System.getProperty("trawl.vec.native.path")?.takeIf { it.isNotBlank() }
      if (explicit != null && File(explicit).isFile) {
        System.load(File(explicit).absolutePath)
        return probe()
      }
      val libName = resourceNameForHost()
      if (libName != null) {
        val stream = Sq8Native::class.java.getResourceAsStream("/$libName")
        if (stream != null) {
          val suffix = if (libName.endsWith(".dylib")) ".dylib" else ".so"
          val tmp = Files.createTempFile("trawl-vec-neon-", suffix).toFile()
          tmp.deleteOnExit()
          stream.use { Files.copy(it, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING) }
          System.load(tmp.absolutePath)
          return probe()
        }
      }
      // Fallback for platforms that place libtrawl_vec_neon on java.library.path (e.g. Android jniLibs).
      if (isArm64Host()) {
        System.loadLibrary("trawl_vec_neon")
        return probe()
      }
      0
    } catch (_: Throwable) {
      0
    }
  }

  private fun isArm64Host(): Boolean {
    val arch = (System.getProperty("os.arch") ?: "").lowercase()
    return arch == "aarch64" || arch == "arm64"
  }

  private fun resourceNameForHost(): String? {
    if (!isArm64Host()) return null
    val os = (System.getProperty("os.name") ?: "").lowercase()
    return when {
      os.startsWith("mac") -> "libtrawl_vec_neon.dylib"
      os.startsWith("linux") -> "libtrawl_vec_neon.so"
      else -> null
    }
  }
}
