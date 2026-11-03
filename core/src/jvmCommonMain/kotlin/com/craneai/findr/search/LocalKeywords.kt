package com.craneai.findr.search

import com.craneai.findr.core.AssetSource
import com.craneai.findr.engine.BundledAssets
import java.io.InputStream

/**
 * Luganda and Swahili words with the English words they stand for ("lisiiti" receipt, "embaga" wedding, "ddwaliro" hospital, "harusi"
 * wedding, "ada" fees), so a Luganda or Swahili query also finds the English file that says it (internal/research/languages-keywords-1.0.6.md).
 *
 * The table is built from licence-clean sources only (scripts/build_local_keywords_clean.py, asset [ASSET];
 * internal/research/languages-keywords-clean.md): the most frequent words of Common Voice 17 Luganda and Swahili sentences (CC0), their
 * English glosses by DeepSeek-V4-Pro (MIT) where samples agree, and word alignment of the Makerere MT and Gendered corpora and TICO-19
 * (CC BY 4.0, attributed on the Licences screens); no sentence ships. Only a query [Fusion.isLocal] calls Luganda or Swahili reaches it
 * ([Searcher.search]), so an English query is not touched and never loads it.
 *
 * Each language is a map from a word to its English words, best first. A query's language is the one whose table knows more of its
 * words; a tie uses both, Luganda's first ("baba" is "father" in Swahili and something else in Luganda).
 *
 * [more]: the same for the other local languages ([LocalLanguage]: Runyankole, Kinyarwanda, Acholi;
 * internal/research/languages-keywords-more.md). A query holding one of their grammar words may be read as that language
 * ([languageOf]); a Luganda or Swahili query that is not is searched exactly as without them.
 */
class LocalKeywords(
  private val lg: Map<String, List<String>>,
  private val sw: Map<String, List<String>>,
  private val more: Map<LocalLanguage, Map<String, List<String>>> = emptyMap(),
) {
  val size: Int get() = lg.size + sw.size + more.values.sumOf { it.size }

  /**
   * The query's words as the table reads them: [Fusion.words], except that a word with an apostrophe inside it ([APOSTROPHES]) which
   * the table holds whole stays one word, spelled with a plain `'`: "picha za ng’ombe" -> picha, za, ng'ombe (Swahili, cow), not
   * "ng" + "ombe" ("ombe" alone is "pray"). In Swahili the apostrophe is part of the letter ng' (one sound inside one word); in Luganda
   * it marks a vowel dropped between two words ("n'abantu" is "na abantu", "z'essomero" "za essomero"), so a word the table does not
   * hold whole is split exactly as [Fusion.words] splits it, and the parts are looked up as before. The builder keeps Swahili
   * apostrophe words whole and splits Luganda ones (scripts/build_local_keywords_clean.py), so with a table that holds no apostrophe
   * word (or no table) this is [Fusion.words], word for word.
   *
   * Only a query [Fusion.isLocal] calls Luganda or Swahili gets here. The FTS index is not changed: its tokenizer (unicode61) splits at
   * the apostrophe as [Fusion.words] does, and a word kept whole goes to FTS5 as the quoted string `"ng'ombe"`, which FTS5 splits the
   * same way and matches as the phrase "ng ombe": the same files as before for the local word, plus the English words for it.
   */
  fun words(query: String): List<String> {
    val text = query.lowercase()
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
      if (!Character.isLetterOrDigit(text[i])) { i++; continue }
      // a run of letters and digits, with an apostrophe allowed between two of them
      var j = i + 1
      var apostrophe = false
      while (j < text.length) {
        val c = text[j]
        if (Character.isLetterOrDigit(c)) j++
        else if (c in APOSTROPHES && j + 1 < text.length && Character.isLetterOrDigit(text[j + 1])) { apostrophe = true; j++ }
        else break
      }
      val run = text.substring(i, j)
      if (!apostrophe) out += run
      else {
        val whole = buildString(run.length) { for (c in run) append(if (c in APOSTROPHES) '\'' else c) }
        if (whole in lg || whole in sw) out += whole else out += Fusion.words(run)
      }
      i = j
    }
    return out
  }

  /** [Fusion.terms] over [words]: the query's content words, a Swahili apostrophe word the table knows kept whole. */
  fun terms(query: String): List<String> = Fusion.termsOf(words(query))

  /** [terms] of a query read as [language]: its grammar words leave too, as Luganda and Swahili ones do. */
  fun terms(query: String, language: LocalLanguage): List<String> = terms(query).filter { it !in language.grammar }

  /** A word's forms as [language] inflects it: the Luganda noun-class forms for a Bantu language ([LocalLanguage.bantu]), else itself. */
  fun forms(term: String, language: LocalLanguage): List<String> = if (language.bantu) Fusion.nounClassForms(term) else listOf(term)

  /**
   * Which of the other local languages ([LocalLanguage]) [query] is in, or null: Luganda or Swahili ([Fusion.isLocal]; [english]), or
   * not a local query at all (English, untouched).
   *
   * A language is a candidate when the query holds one of its grammar words (Luganda and Swahili as one, by [LocalStopwords]) and, for
   * the other languages, its table knows at least one of the query's words (so grammar words alone never change a query). Each
   * candidate scores its grammar words in the query plus the query's words its table knows (Luganda and Swahili: the larger of the two,
   * as [english] picks); the highest wins, a tie goes to Luganda/Swahili first, then [LocalLanguage] in order. So a Kinyarwanda query
   * that shares "na" and "mu" with Swahili and Luganda goes to Kinyarwanda when the Kinyarwanda table knows more of it, and a query
   * with no local grammar word is never read as local.
   */
  fun languageOf(query: String): LocalLanguage? {
    val words = Fusion.words(query)
    var best: LocalLanguage? = null
    var bestScore = -1
    val lgsw = words.count { it in LocalStopwords.WORDS }
    if (lgsw > 0) {
      val groups = terms(query).map { Fusion.nounClassForms(it) }
      bestScore = lgsw + maxOf(known(lg, groups), known(sw, groups))
    }
    for (language in LocalLanguage.entries) {
      val grammar = language.grammar
      val hits = words.count { it in grammar }
      if (hits == 0) continue
      val table = more[language].orEmpty()
      // A language with no English word to add is no candidate: grammar words alone never change how a query is searched
      // (an English query naming a place or a dojo can hold one of them: "okinawa goju ryo dojo").
      val known = known(table, terms(query, language).map { forms(it, language) })
      if (known == 0) continue
      val score = hits + known
      if (score > bestScore) { best = language; bestScore = score }
    }
    return best
  }

  private fun known(table: Map<String, List<String>>, groups: List<List<String>>) = groups.count { g -> g.any { it in table } }

  /** [english] for a query read as [language] ([languageOf]): each word group's English words in that language's table only. */
  fun english(language: LocalLanguage, groups: List<List<String>>): List<List<String>> {
    val table = more[language].orEmpty()
    return groups.map { g ->
      val out = LinkedHashSet<String>()
      g.firstNotNullOfOrNull { table[it] }?.let { out += it }
      out.removeAll(g.toSet())
      out.toList()
    }
  }

  /**
   * For each word group of a Luganda or Swahili query (a word and its other noun-class forms, [Fusion.nounClassForms]), the English words
   * it stands for in the query's language: the first form the table knows wins (as typed, then the other forms). An empty list for a
   * group the table does not know. Never one of the group's own forms.
   */
  fun english(groups: List<List<String>>): List<List<String>> {
    val nLg = known(lg, groups)
    val nSw = known(sw, groups)
    val tables = when {
      nLg == 0 && nSw == 0 -> return groups.map { emptyList() }
      nLg > nSw -> listOf(lg)
      nSw > nLg -> listOf(sw)
      else -> listOf(lg, sw)
    }
    return groups.map { g ->
      val out = LinkedHashSet<String>()
      for (t in tables) g.firstNotNullOfOrNull { t[it] }?.let { out += it }
      out.removeAll(g.toSet())
      out.toList()
    }
  }

  /**
   * The query's English gloss, word by word, for the query vector ([embedText]): a grammar word or a single letter is left out, a picture
   * word becomes "photo", a word the table knows becomes its English words ([english], the same lookup the words side makes), any other
   * word (a name, a number, an English word) stays as typed. Null when the table knows none of the query's words: nothing to add.
   * "lisiiti ya ddwaliro e Nsambya" -> "receipt hospital nsambya". A query [languageOf] reads as another local language is glossed from
   * that language's table, its grammar words left out as well ([embedText] asks only for a local query).
   */
  fun gloss(query: String): String? = gloss(query, languageOf(query))

  private fun gloss(query: String, language: LocalLanguage?): String? {
    val words = words(query)
    val terms = if (language == null) Fusion.termsOf(words) else Fusion.termsOf(words).filter { it !in language.grammar }
    val english = if (language == null) english(terms.map { Fusion.nounClassForms(it) }) else english(language, terms.map { forms(it, language) })
    if (english.all { it.isEmpty() }) return null
    val of = HashMap<String, List<String>>()
    terms.forEachIndexed { i, t -> of[t] = english[i] }
    val grammar = language?.grammar.orEmpty()
    val out = ArrayList<String>()
    for (w in words) {
      if (w in LocalStopwords.WORDS || w in grammar || w.length < 2) continue
      if (w in LocalStopwords.PICTURE_WORDS) { out += "photo"; continue }
      val e = of[w]
      if (e.isNullOrEmpty()) out += w else out += e
    }
    return out.joinToString(" ")
  }

  companion object {
    const val ASSET = "local_keywords.txt.gz"

    /**
     * What a phone keyboard types for an apostrophe inside a word: the plain `'`, the curly `’` (and its opening twin `‘`, which
     * autocorrect sometimes picks) and the backtick. All four are separators to FTS5's unicode61 tokenizer and to [Fusion.words].
     * (U+02BC, the modifier-letter apostrophe, is a letter to both, so a word typed with it is already one word everywhere.)
     */
    const val APOSTROPHES = "'’‘`"

    /**
     * The text to embed for [query]: a Luganda or Swahili query ([Fusion.isLocal]), or one in another local language
     * ([LocalLanguages.mayBeLocal], [languageOf]), followed by its English [gloss], one text, so the model reads both
     * (internal/research/languages-keywords-1.0.6.md: better than the gloss alone or the two vectors averaged, and one embed); any other
     * query exactly as typed. An English query never reads the table.
     */
    fun embedText(query: String, keywords: Lazy<LocalKeywords?>?): String {
      if (keywords == null || !LocalLanguages.mayBeLocal(query)) return query
      val table = keywords.value ?: return query
      // another local language, or Luganda/Swahili exactly as before; a query that is neither (a grammar word of another language
      // its table has nothing for) is embedded as typed
      val language = table.languageOf(query)
      if (language == null && !Fusion.isLocal(query)) return query
      val gloss = table.gloss(query, language) ?: return query
      return "$query $gloss"
    }

    /**
     * The table: `lang<TAB>word<TAB>english english ...` lines (lang lg, sw or a [LocalLanguage] code: nyn, kin, ach), `#` comments.
     * A line of any other language is skipped. Gzipped (the source) or plain (the APK).
     */
    fun parse(stream: InputStream): LocalKeywords {
      val lg = HashMap<String, List<String>>()
      val sw = HashMap<String, List<String>>()
      val more = HashMap<LocalLanguage, HashMap<String, List<String>>>()
      BundledAssets.ungzipIfNeeded(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
        for (line in lines) {
          if (line.isEmpty() || line[0] == '#') continue
          val a = line.indexOf('\t')
          val b = line.indexOf('\t', a + 1)
          if (a <= 0 || b <= a + 1) continue
          val english = line.substring(b + 1).split(' ').filter { w -> w.isNotEmpty() && w.all(Character::isLetterOrDigit) }
          if (english.isEmpty()) continue
          when (val code = line.substring(0, a)) {
            "lg" -> lg[line.substring(a + 1, b)] = english
            "sw" -> sw[line.substring(a + 1, b)] = english
            else -> LocalLanguage.of(code)?.let { more.getOrPut(it) { HashMap() }[line.substring(a + 1, b)] = english }
          }
        }
      }
      return LocalKeywords(lg, sw, more)
    }

    /** Reads [ASSET] on first use (about 120 KB, once per process); null, logged, when it cannot be read: the queries then go without. */
    fun fromAssets(assets: AssetSource): Lazy<LocalKeywords?> = lazy {
      val started = System.nanoTime()
      runCatching { BundledAssets.open(assets, ASSET).use { parse(it) } }
        .onSuccess {
          // the read's cost, once per process, on the first Luganda or Swahili query (the phone check reads this line)
          com.craneai.findr.core.FindrLog.i("FindrSearch", "local keyword table loaded (${it.size} words) in ${(System.nanoTime() - started) / 1_000_000} ms " +
            "on ${Thread.currentThread().name}")
        }
        .onFailure { com.craneai.findr.core.FindrLog.w("FindrSearch", "local keyword table unavailable", it) }
        .getOrNull()
    }
  }
}
