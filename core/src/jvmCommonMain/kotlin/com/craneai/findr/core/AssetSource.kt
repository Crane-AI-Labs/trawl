package com.craneai.findr.core

import java.io.InputStream

/** Where bundled files (word lists, tokenizer merges, language centroids) are read from: the APK's assets on a phone, the classpath on the desktop. */
fun interface AssetSource {
  /** The file by its path under the assets root; throws [java.io.FileNotFoundException] when there is none. */
  fun open(name: String): InputStream
}
