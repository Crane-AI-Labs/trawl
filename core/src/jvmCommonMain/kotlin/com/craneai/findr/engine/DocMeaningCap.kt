package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Rules

/**
 * Shared ceiling on how many text chunks of a single document are embedded into meaning vectors (both Android and Desktop).
 *
 * Every chunk beyond the cap is STILL written to SQLite FTS5 so exact-word and phrase search cover the entire document; only
 * redundant vector embeddings are capped.
 *
 * Option 2 policy (validated on a real 30k-file Mac index where .json/.log/.csv dumps were 93.3% of 1.67M vectors while all
 * real prose PDFs/DOCX/MD/TXT combined were only 3.6%):
 *  - Human prose & source code: high 500-chunk ceiling (~125k words / ~350 pages) so real documents keep full semantic recall
 *    with zero mid-document RAG blind spots.
 *  - Machine-generated / structured data dumps (JSON, logs, CSV/TSV, XML/SVG): 8-chunk opening head for meaning while 100% of
 *    every line stays FTS5 keyword-searchable.
 */
object DocMeaningCap {
  /** High safety ceiling on meaning-embedded chunks for prose, code, and structured exports (PDF, DOCX, TXT, MD, JSON, CSV, etc.). */
  const val DOC_MAX_CHUNKS = 500

  /** Maximum meaning-embedded chunks for strictly non-prose machine formats (.log, .svg); rest remain 100% FTS5 keyword-searchable. */
  const val MACHINE_DATA_MAX_CHUNKS = 8

  // Only formats that are provably non-prose logs or vector-graphics XML; JSON/CSV can carry real prose (e.g. saved posts, chat exports)
  // and stay at DOC_MAX_CHUNKS so users never silently lose semantic recall.
  private val MACHINE_DATA_EXT = setOf("log", "svg", "svgz")

  /** True when [fileName] has a structured / machine-data extension whose tail is repetitive data rather than prose. */
  fun isMachineData(fileName: String): Boolean = Rules.extensionOf(fileName) in MACHINE_DATA_EXT

  /** Maximum number of chunks to embed with the meaning model for a document named [fileName] of [kind]. */
  fun maxEmbeddedChunks(fileName: String, kind: FileKind = FileKind.DOCUMENT): Int = when {
    kind != FileKind.DOCUMENT && kind != FileKind.PDF -> Int.MAX_VALUE
    isMachineData(fileName) -> MACHINE_DATA_MAX_CHUNKS
    else -> DOC_MAX_CHUNKS
  }
}
