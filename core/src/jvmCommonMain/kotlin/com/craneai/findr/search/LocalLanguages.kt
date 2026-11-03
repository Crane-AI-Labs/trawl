package com.craneai.findr.search

/**
 * The local languages the keyword table ([LocalKeywords]) knows besides Luganda and Swahili: Runyankole, Kinyarwanda and Acholi
 * (internal/research/languages-keywords-more.md). Lugbara is not here: no licence-clean Lugbara text exists to build a list or a table from.
 * Ateso was built and measured the same way and is not here either: its table did not measurably help Ateso searches (the model knows
 * too little Ateso to gloss it), so it was left out by the rule declared before the measurement.
 *
 * Each has its grammar words: closed-class words only (particles, prepositions, conjunctions, the "of" connectives by noun class,
 * pronouns, possessives, demonstratives, question words, quantifiers, copulas, negation), hand-tagged, each attested in the language's
 * licence-clean sentences (Common Voice 17 Kinyarwanda, CC0; the Makerere MPTC corpus and Busitema's BUAIR Q&A, CC BY 4.0), and none of
 * them a common English word (assets/common_words.txt.gz), so an English query never holds one. Built by
 * scripts/build_local_stopwords_more.py.
 *
 * They do two things, only for a query [LocalKeywords.languageOf] reads as that language: they say which language it is, and they leave
 * its search words, as [LocalStopwords] do for Luganda and Swahili. For every other query nothing here is read: English, Luganda and
 * Swahili queries are searched exactly as before.
 *
 * [bantu]: Runyankole and Kinyarwanda nouns change their first syllable with number and class as Luganda's do, so their words also
 * match the other Luganda noun-class forms ([Fusion.nounClassForms]); Acholi words do not.
 */
enum class LocalLanguage(val code: String, val bantu: Boolean) {
  NYN("nyn", true), KIN("kin", true), ACH("ach", false);

  val grammar: Set<String> get() = LocalLanguages.grammar(this)

  companion object {
    fun of(code: String): LocalLanguage? = entries.firstOrNull { it.code == code }
  }
}

object LocalLanguages {
  // nyn: 125 words (MPTC + BUAIR Runyankore, 13,552 sentences)
  val NYN: Set<String> = setOf(
    "abo", "aga", "aha", "ahabw", "ahabwa", "ahabwenki", "ahabwokuba", "ahagati", "ahaiguru", "ahansi", "ahari", "aheeru", "ahu", "aka",
    "baingi", "bingi", "biri", "buri", "bwa", "bwabo", "bwangye", "bwanyima", "bwona", "bya", "byabo", "byaitu", "byangye", "byawe",
    "byona", "ebi", "ebyo", "egi", "egyo", "eki", "ekyo", "enki", "eri", "eryo", "ezi", "ezo", "ga", "gari", "guri", "gwa", "gwabo",
    "gwaitu", "gwangye", "gwawe", "gwona", "haihi", "hamwe", "hariho", "itwe", "ka", "kandi", "kari", "ki", "kiri", "kuri", "kuruga",
    "kwa", "kwona", "kwonka", "kya", "kyabo", "kyaitu", "kyangye", "kyawe", "kyona", "mbwenu", "munonga", "na", "naaha", "nainga", "nari",
    "ngu", "ni", "nibo", "nikyo", "niyo", "nk", "nka", "nkahi", "nta", "nyingi", "obu", "obwo", "ogu", "ogwo", "oha", "omu", "omunda",
    "omuri", "oru", "orwo", "ota", "reero", "riri", "rwa", "rwabo", "rwaitu", "rwangye", "rwona", "rya", "ryaitu", "ryangye", "ryari",
    "ti", "twa", "wa", "waabo", "waawe", "waitu", "wangye", "yaabo", "yaawe", "yaitu", "yangye", "yanyu", "za", "zaabo", "zaawe", "zaitu",
    "zangye", "ziri",
  )

  // kin: 166 words (Common Voice 17 Kinyarwanda + TICO-19, 1,405,801 sentences)
  val KIN: Set<String> = setOf(
    "aba", "abandi", "abo", "aha", "aho", "ahubwo", "aka", "ako", "angahe", "ari", "ariko", "bamwe", "bari", "bariya", "benshi", "bimwe",
    "biriya", "buri", "bwa", "bwabo", "bwacu", "bwanjye", "bwawe", "bwe", "bwo", "bwose", "bya", "byabo", "byacu", "byanjye", "byanyu",
    "byawe", "byinshi", "byo", "byose", "cya", "cyabo", "cyacu", "cyane", "cyangwa", "cyanjye", "cyanyu", "cyawe", "cye", "cyo", "cyose",
    "ese", "gusa", "gute", "hafi", "hagati", "hamwe", "hari", "hariya", "hejuru", "ibi", "ibindi", "ibyo", "icyo", "iki", "imbere",
    "inyuma", "iri", "iriya", "iryo", "iyi", "iyo", "izi", "izo", "ka", "kandi", "kiriya", "ko", "kose", "kubera", "kugeza", "kugira",
    "kugirango", "kuki", "kuko", "kuri", "kuva", "kwa", "kwacu", "kwanjye", "kwawe", "kwe", "kwo", "mbere", "mu", "munsi", "muri", "mwe",
    "na", "nanone", "nde", "ndetse", "ndi", "nge", "ngo", "ni", "niba", "njye", "nk", "nka", "nko", "nta", "ntabwo", "nuko", "nyuma",
    "rero", "rwa", "rwabo", "rwacu", "rwanjye", "rwawe", "rwe", "rwo", "rwose", "rya", "ryabo", "ryacu", "ryanjye", "ryari", "ryawe",
    "ryo", "ryose", "turi", "twa", "twe", "ubu", "ubwo", "uko", "uku", "ukwo", "uri", "uriya", "uru", "urwo", "uwo", "uyu", "wa", "wabo",
    "wacu", "wanjye", "wanyu", "wawe", "wese", "wowe", "yabo", "yacu", "yanjye", "yanyu", "yari", "yawe", "yose", "za", "zabo", "zacu",
    "zanjye", "zanyu", "zawe", "ze", "ziriya", "zo", "zose",
  )

  // ach: 55 words (MPTC Acholi, 5,059 sentences)
  val ACH: Set<String> = setOf(
    "adada", "ango", "awene", "aye", "bene", "bot", "ca", "ci", "cok", "dok", "ducu", "eni", "ento", "gi", "gitye", "ikin", "ikom", "iwi",
    "iye", "ka", "kany", "keken", "ki", "kitye", "kun", "kunu", "kweda", "kwede", "kwedi", "kwene", "magi", "mapol", "matye", "megi",
    "meno", "mewa", "mogo", "moko", "mukene", "ngat", "ngo", "ni", "nining", "obedo", "onongo", "onyo", "pa", "pe", "pien", "pingo", "pud",
    "tutwal", "tye", "wek", "weng",
  )


  fun grammar(language: LocalLanguage): Set<String> = when (language) {
    LocalLanguage.NYN -> NYN
    LocalLanguage.KIN -> KIN
    LocalLanguage.ACH -> ACH
  }

  private val ANY: Set<String> by lazy { LocalLanguage.entries.flatMapTo(HashSet()) { it.grammar } }

  /**
   * Whether [query] holds a grammar word of any local language: Luganda or Swahili ([Fusion.isLocal]) or one of [LocalLanguage]. Only
   * then is the keyword table read; an English query never holds one, so it never reads it.
   */
  fun mayBeLocal(query: String): Boolean = Fusion.words(query).any { it in LocalStopwords.WORDS || it in ANY }
}
