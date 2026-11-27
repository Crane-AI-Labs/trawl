package com.craneai.findr.search

/**
 * A number in a query written the way people say it ("50,000", "50k") read as the digits a spreadsheet cell holds ("50000"), so one
 * search term finds both. Measured in internal/research/spreadsheet-search.md: spreadsheet queries P@1 +4.2 points (95% CI +1.8 to +7.0),
 * nothing else moves (eval-en and Context Bench v1 unchanged). Needs no re-reading of any file: it only changes how the query is split.
 */
object QueryNumbers {
  private val GROUPED = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
  private val THOUSANDS = Regex("(?<![\\w.,])(\\d{1,9})(?:\\.(\\d{1,3}))?\\s?[kK](?![\\p{L}\\d])")

  fun normalise(query: String): String {
    if (query.none { it.isDigit() }) return query
    val grouped = if (',' in query) GROUPED.replace(query, "") else query
    return THOUSANDS.replace(grouped) { m ->
      val whole = m.groupValues[1].toLong()
      val frac = m.groupValues[2]
      val value = whole * 1000 + (if (frac.isEmpty()) 0 else (frac + "00").take(3).toLong())
      value.toString()
    }
  }
}
