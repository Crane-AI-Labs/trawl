package com.craneai.findr.extract

/**
 * CSV, TSV and their cousins (semicolon files from European Excel, pipe files from exports) as "cell | cell | cell"
 * lines, the same shape the spreadsheet readers give. The delimiter is found from the first lines, quoted fields
 * (with doubled quotes and line breaks inside) are honoured, and empty cells are left out. One pass over text that
 * is already bounded by the caller; the output is capped by characters and by non-empty cells.
 */
object DelimitedText {
  class Result(val text: String, val truncated: Boolean)

  private val candidates = charArrayOf(',', ';', '\t', '|')
  private const val SAMPLE_LINES = 30

  /** The delimiter that splits the first lines into the same number of columns most often, or null for a single column. */
  fun detectDelimiter(text: String): Char? {
    val lines = ArrayList<String>()
    var i = 0
    var start = 0
    var quotes = false
    // Lines as the parser sees them: a quoted field may hold a line break.
    while (i < text.length && lines.size < SAMPLE_LINES) {
      val c = text[i]
      if (c == '"') quotes = !quotes
      if (!quotes && (c == '\n' || c == '\r')) {
        if (i > start) lines.add(text.substring(start, i))
        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
        start = i + 1
      }
      i++
    }
    if (lines.size < SAMPLE_LINES && start < text.length) lines.add(text.substring(start))
    var best: Char? = null
    var bestLines = 0
    var bestColumns = 0
    for (d in candidates) {
      val counts = lines.map { countOutsideQuotes(it, d) }
      val mode = counts.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
      // A column count seen on most lines beats one seen on a few; more columns break a tie.
      if (mode.value > bestLines || (mode.value == bestLines && mode.key > bestColumns)) {
        best = d; bestLines = mode.value; bestColumns = mode.key
      }
    }
    // A comma in one sentence of prose is not a delimiter: it has to show up on more than one line, unless that is all there is.
    return if (best != null && (bestLines >= 2 || lines.size == 1)) best else null
  }

  private fun countOutsideQuotes(line: String, d: Char): Int {
    var n = 0
    var quotes = false
    for (c in line) if (c == '"') quotes = !quotes else if (!quotes && c == d) n++
    return n
  }

  fun toText(raw: String, maxChars: Int, maxCells: Int = ExtractLimits.MAX_CELLS): Result {
    val text = raw.removePrefix("﻿")
    val delimiter = detectDelimiter(text)
    val budget = Budget(maxChars)
    val sink = TextSink(budget)
    val field = StringBuilder()
    var inQuotes = false
    var wasQuoted = false
    var rowHasCell = false
    var cells = 0
    var capped = false

    fun endField() {
      val cell = field.toString().replace('\n', ' ').replace('\r', ' ').trim()
      field.setLength(0)
      wasQuoted = false
      if (cell.isEmpty()) return
      if (cells >= maxCells) { capped = true; return }
      cells++
      if (rowHasCell) sink.append(" | ")
      sink.append(cell)
      rowHasCell = true
    }

    fun endRow() {
      endField()
      if (rowHasCell) sink.newline()
      rowHasCell = false
    }

    var i = 0
    val n = text.length
    while (i < n && !budget.full && !capped) {
      val c = text[i]
      when {
        inQuotes -> when {
          c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i++ }
          c == '"' -> inQuotes = false
          else -> field.append(c)
        }
        c == '"' && (field.isEmpty() || field.isBlank()) && !wasQuoted -> { inQuotes = true; wasQuoted = true; field.setLength(0) }
        delimiter != null && c == delimiter -> endField()
        c == '\n' -> endRow()
        c == '\r' -> { endRow(); if (i + 1 < n && text[i + 1] == '\n') i++ }
        else -> field.append(c)
      }
      i++
    }
    if (!budget.full && !capped) endRow()
    return Result(sink.take(), budget.truncated || capped || (i < n))
  }
}
