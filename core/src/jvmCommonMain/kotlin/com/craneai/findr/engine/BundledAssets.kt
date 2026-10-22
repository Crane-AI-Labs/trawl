package com.craneai.findr.engine

import com.craneai.findr.core.AssetSource
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Word lists kept gzipped in the source tree (english_words.txt.gz, clip_bpe_merges.txt.gz) reach the phone differently:
 * the APK packager stores a `.gz` asset decompressed, under its name without `.gz` (seen in the AGP 9 release APK:
 * `assets/english_words.txt`, 2.48 MB stored deflated), so `assets.open("x.txt.gz")` finds nothing on the phone while the
 * JVM tests, which read the source files, see gzip. These two helpers make both work.
 */
object BundledAssets {
  /** The asset by its source name, or by that name without `.gz`, as packaged. */
  fun open(assets: AssetSource, name: String): InputStream = try {
    assets.open(name)
  } catch (e: FileNotFoundException) {
    assets.open(name.removeSuffix(".gz"))
  }

  /** [stream] un-gzipped when it is gzip (the source file), as it is otherwise (the packaged asset). */
  fun ungzipIfNeeded(stream: InputStream): InputStream {
    val s = if (stream.markSupported()) stream else BufferedInputStream(stream)
    s.mark(2)
    val a = s.read()
    val b = s.read()
    s.reset()
    return if (a == 0x1f && b == 0x8b) GZIPInputStream(s) else s
  }
}
