package com.craneai.findr.core

/**
 * The build-time switches the shared code reads. On a phone they are the app's `BuildConfig` fields, copied here once by
 * `FindrApp` before anything else runs; the desktop sets its own. Every default is the release value, so code that runs
 * before (or without) a platform entry point behaves like a release build.
 */
object BuildFlags {
  /** Detail logging: file names, paths and queries may be logged. Debug builds and `-PfindrLogDetail=true` only. */
  @Volatile var logDetail: Boolean = false

  /** A debug build: some profiling overrides are honoured only here. */
  @Volatile var debug: Boolean = false

  /** The language-tuned model bundles are preferred (`-PfindrFinetuned=true`). */
  @Volatile var finetuned: Boolean = false

  /** Trawl Go (the words-only edition). */
  @Volatile var go: Boolean = false
}
