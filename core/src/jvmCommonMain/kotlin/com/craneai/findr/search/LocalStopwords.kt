package com.craneai.findr.search

/**
 * Luganda and Swahili function words: the grammar words a query in those languages is full of ("mu", "ku", "nga", "ya", "na", "wa",
 * "kwenye", "yangu"), which occur in nearly every Luganda or Swahili file. [Fusion.terms] leaves them out of a query's words the way it
 * leaves out the English stopwords, so a same-language file no longer earns word score for sharing them, and photos and English files are
 * not pushed down by it (internal/research/languages-text-1.0.6.md). [Fusion.isLocal] reads them as the sign of a Luganda or Swahili query.
 *
 * Closed-class words only (particles, conjunctions, prepositions, the "of" and "with" connectives by noun class, demonstratives,
 * pronouns, possessives, relatives, question words, quantifiers), each attested in Mozilla Common Voice 17 Luganda or Swahili sentences
 * (CC0). A word that is common English (assets/common_words.txt.gz) is left out, so English queries keep "we", "be", "by", "la", "lake",
 * "wake", "pale", "ago"; "ya" and "ku" are kept despite a rare English use. Built by scripts/build_local_stopwords.py.
 */
object LocalStopwords {
  val WORDS: Set<String> = setOf(
    "ab", "aba", "abo", "ag", "aga", "ak", "aka", "ako", "ama", "ambacho", "ambako", "ambalo", "ambamo", "ambao", "ambapo", "ambavyo",
    "ambaye", "ambayo", "ambazo", "ani", "atya", "au", "awo", "ba", "baabwe", "baada", "baadhi", "baffe", "bano", "bila", "bino", "biri",
    "bonna", "buli", "buno", "bw", "bwa", "bwabwe", "bwaffe", "bwange", "bwe", "bwonna", "bya", "byabwe", "byaffe", "byammwe", "byange",
    "byonna", "chake", "chako", "changu", "chao", "chenye", "chetu", "chote", "ddala", "ddi", "eby", "ebya", "ebyo", "egy", "egya", "egyo",
    "eky", "ekya", "ekyo", "eno", "eri", "ery", "erya", "eryo", "ey", "eya", "eyo", "ez", "eza", "ezo", "ffe", "ga", "gali", "gani", "gano",
    "ge", "ggwe", "gino", "giri", "guli", "guno", "gw", "gwa", "gwabwe", "gwaffe", "gwange", "gwe", "gwonna", "gy", "gya", "gyabwe",
    "gyaffe", "gyange", "gye", "gyonna", "hadi", "hao", "hapa", "hapo", "hata", "hawa", "haya", "hayo", "hicho", "hii", "hiki", "hili",
    "hilo", "hivi", "hivyo", "hiyo", "hizi", "hizo", "huko", "huku", "humo", "humu", "huo", "huu", "huyo", "huyu", "ila", "ile", "ili",
    "ingawa", "je", "ka", "kaabwe", "kabla", "kaffe", "kama", "kange", "kano", "kati", "katika", "ke", "ki", "kiki", "kila", "kile", "kino",
    "kiri", "kote", "ku", "kubanga", "kuhusu", "kule", "kuli", "kuno", "kutoka", "kuwa", "kw", "kwa", "kwake", "kwako", "kwamba", "kwangu",
    "kwani", "kwao", "kwe", "kwenu", "kwenye", "kwetu", "kwonna", "ky", "kya", "kyabwe", "kyaffe", "kyammwe", "kyange", "kye", "kyokka",
    "kyonna", "lakini", "lako", "langu", "lenu", "lenye", "letu", "lile", "lini", "lino", "liri", "lote", "luli", "luno", "lw", "lwa",
    "lwabwe", "lwaffe", "lwaki", "lwange", "lwe", "lwonna", "ly", "lya", "lyabwe", "lyaffe", "lyange", "lye", "lyonna", "mbu", "meka",
    "mimi", "mle", "mmeka", "mmwe", "mu", "mwa", "mwenye", "na", "naawe", "nabo", "naddala", "nange", "nani", "naye", "ndio", "ndiye",
    "ndiyo", "ne", "newankubadde", "ng", "nga", "ni", "nini", "ninyi", "nno", "nnyo", "nti", "nyinyi", "nze", "oba", "obw", "obwa", "obwo",
    "ogw", "ogwa", "ogwo", "okw", "okwa", "okwo", "oli", "olw", "olwa", "olwo", "ono", "otya", "ow", "owa", "oyo", "pa", "pamoja", "penye",
    "pia", "pote", "sana", "singa", "sisi", "tangu", "tena", "tu", "ule", "vile", "vipi", "vya", "vyake", "vyako", "vyangu", "vyao", "vyenu",
    "vyenye", "vyetu", "vyote", "wa", "waabwe", "wabula", "wadde", "waffe", "wako", "wala", "wale", "wali", "wammwe", "wange", "wangu",
    "wano", "wao", "wapi", "wenu", "wenye", "wetu", "wewe", "wonna", "wote", "ya", "yaabwe", "yaani", "yaffe", "yake", "yako", "yammwe",
    "yange", "yangu", "yao", "yenna", "yenu", "yenye", "yetu", "yeye", "yonna", "yote", "yule", "za", "zaabwe", "zaffe", "zake", "zako",
    "zammwe", "zange", "zangu", "zao", "ze", "zenu", "zenye", "zetu", "zile", "zino", "ziri", "ziriwa", "zonna", "zote",
  )

  /**
   * "Picture" in Luganda (singular, plural, with and without the initial vowel, and the common one-a spelling) and Swahili. Like English
   * "photo": not a search word ([Fusion.terms]), and a hint that pictures are wanted ([Fusion.kindHints]).
   */
  val PICTURE_WORDS: Set<String> = setOf("ekifaananyi", "ebifaananyi", "kifaananyi", "bifaananyi", "ekifananyi", "ebifananyi", "picha")

  /** "Message" in Luganda and Swahili. Like English "message": still a search word, and a hint that a screenshot is wanted. */
  val MESSAGE_WORDS: Set<String> = setOf("obubaka", "ujumbe")
}
