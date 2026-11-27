package com.craneai.findr.engine

/**
 * Is a document's text something a person wrote and would ask about by meaning, or machine text (code, logs, JSON dumps, generated
 * tables, minified or encoded blobs) that is found by its words? Decided by WHAT the text is, never by the file's name or folder:
 * a budget CSV, a README full of commands and a mobile-money statement are a person's; a logcat, a results dump and a 5,000-row test
 * fixture are not. Machine text keeps every word in the keyword index and gets no meaning vectors ([MeaningBudget]).
 *
 * It reads the text the way the indexer has it (whitespace collapsed by [Chunker], so line shapes are gone) and looks at its tokens:
 * how many are plain words, how many are JSON keys (`"name":`), how many are code-shaped (`a.b(`, `x=1;`, camelCase, snake_case, `=>`), very long unbroken tokens
 * (minified code, base64; paths and links are not counted), timestamps, and the share of symbol and digit characters. A file
 * under [SMALL_BYTES] is a person's whatever it looks like: it costs a few vectors at most, and a short note misjudged is a real loss.
 *
 * Calibrated on 17 Nov 2026 against the Context Bench world (960 documents), eval-en's documents and five code repositories on
 * Crane (internal/research/heat-fixes.md): no README, docs, notes, Markdown or web page was judged machine text.
 */
object TextClass {
  enum class Kind { HUMAN, MACHINE }

  data class Verdict(val kind: Kind, val why: String)

  /** Below this a file is always a person's. */
  const val SMALL_BYTES = 4_096L

  /** A table (CSV, TSV) under this is a person's (a budget, a fee list, a contact sheet); larger ones are judged by their content. */
  const val SMALL_TABLE_BYTES = 1_048_576L

  private const val SAMPLE_TOKENS = 20_000
  private const val HEAD_TOKENS = 12_000

  /**
   * [text] is the document's extracted text (any whitespace), [sizeBytes] the file's size, [table] true when the reader took it as
   * delimited text.
   */
  fun of(text: String, sizeBytes: Long, table: Boolean = false): Verdict {
    if (text.isBlank()) return Verdict(Kind.HUMAN, "empty")
    if (sizeBytes < SMALL_BYTES) return Verdict(Kind.HUMAN, "small")
    if (table && sizeBytes < SMALL_TABLE_BYTES) return Verdict(Kind.HUMAN, "small table")
    val f = features(text)
    return when {
      // JSON is machine text when it is mostly structure, or records repeating a few keys (a results dump, however much prose its
      // values hold); notes written as JSON (few keys, long sentences) are a person's.
      f.keys > 0.08 && (f.word < 0.6 || f.distinctKeys < 0.02) -> Verdict(Kind.MACHINE, "json records")
      f.long > 0.05 -> Verdict(Kind.MACHINE, "long tokens")
      f.code > 0.25 && f.word < 0.65 -> Verdict(Kind.MACHINE, "code-shaped")
      f.symbols > 0.10 && f.word < 0.5 -> Verdict(Kind.MACHINE, "symbol-dense")
      f.timestamps > 0.03 && f.word < 0.7 -> Verdict(Kind.MACHINE, "timestamped")
      sizeBytes > 256 * 1024 && f.digits > 0.30 && f.word < 0.5 -> Verdict(Kind.MACHINE, "large numeric")
      sizeBytes > 4L * 1024 * 1024 && f.word < 0.6 -> Verdict(Kind.MACHINE, "very large, not prose")
      else -> Verdict(Kind.HUMAN, "prose")
    }
  }

  /** Shares over a sample of the tokens (the first [HEAD_TOKENS], then an even spread of the rest). */
  data class Features(val word: Double, val code: Double, val long: Double, val timestamps: Double, val symbols: Double, val digits: Double, val keys: Double, val distinctKeys: Double)

  fun features(text: String): Features {
    val all = text.split(WHITESPACE).filter { it.isNotEmpty() }
    val toks = if (all.size <= SAMPLE_TOKENS) all else {
      val rest = all.subList(HEAD_TOKENS, all.size)
      val step = maxOf(1, rest.size / (SAMPLE_TOKENS - HEAD_TOKENS))
      all.subList(0, HEAD_TOKENS) + rest.indices.step(step).map { rest[it] }.take(SAMPLE_TOKENS - HEAD_TOKENS)
    }
    val n = maxOf(1, toks.size).toDouble()
    var chars = 0; var words = 0; var code = 0; var long = 0; var ts = 0; var sym = 0; var digits = 0; var keys = 0
    val keyNames = HashSet<String>()
    for (t in toks) {
      chars += t.length
      if (t.length >= 2 && WORD.matches(t)) words++
      if (CODEY.containsMatchIn(t)) code++
      if (t.length > 40 && !linkOrPath(t)) long++
      if (TIMESTAMP.containsMatchIn(t)) ts++
      val key = t.indexOf("\":")   // a JSON key: "name": (prose almost never has a quote then a colon)
      if (key >= 0) { keys++; keyNames += t.substring(0, key) }
      for (c in t) { if (c in SYMBOLS) sym++; if (c.isDigit()) digits++ }
    }
    val c = maxOf(1, chars).toDouble()
    return Features(words / n, code / n, long / n, ts / n, sym / c, digits / c, keys / n, keyNames.size / maxOf(1, keys).toDouble())
  }

  /** A link or a file path is long but not an encoded blob: `https://…`, `www.…`, `/mnt/data/x/y`, `a/b/c.kt` (up to 200 chars). */
  private fun linkOrPath(t: String): Boolean {
    if ("://" in t || t.startsWith("www.")) return true
    val core = t.trim(*EDGE)
    return core.length <= 200 && core.count { it == '/' } >= 2 && PATH.matches(core)
  }

  private val WHITESPACE = Regex("\\s+")
  private val WORD = Regex("^[(\"'“‘]?[\\p{L}][\\p{L}'’-]*[.,;:!?)\"'”’]*$")
  private val CODEY = Regex("[(){};=<>\\[\\]]|[a-z][A-Z]|[A-Za-z]_[A-Za-z]|[A-Za-z]\\.[A-Za-z]{2,}\\(|::|->|=>|\\$\\{|&&|\\|\\|")
  private val TIMESTAMP = Regex("^\\[?\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?|^\\d{1,2}:\\d{2}:\\d{2}([.,]\\d+)?]?,?$")
  private val PATH = Regex("^[~.]?/?[\\w.@+-]+(/[\\w.@+-]+)+/?$")
  /** Not | # * _: Markdown tables and emphasis are prose. */
  private const val SYMBOLS = "{}[]()<>;=\"`$&^~@\\"
  private val EDGE = "`'\"()[]<>,.;:*".toCharArray()
}
