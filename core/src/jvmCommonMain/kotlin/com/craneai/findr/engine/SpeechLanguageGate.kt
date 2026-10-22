package com.craneai.findr.engine

import com.craneai.findr.core.AssetSource
import java.util.Locale

/**
 * Is what the English speech model wrote down English? Parakeet and Moonshine only know English, so on Luganda or Swahili they
 * do not stay quiet: they write English-looking junk. Indexing that junk drags retrieval below what the audio vector alone gets
 * (the research notes: Luganda -0.196, Swahili -0.055 r@1 on read speech with the first gate), so a file whose
 * transcript fails this test gets no transcript pieces, and its audio is embedded instead when the model can.
 *
 * The rule, version [VERSION] (the Python twin is the experiment code, checked against a golden file in the unit tests):
 *  - words are [a-z]+('[a-z]+)? in the lower-cased text;
 *  - a word is COMMON when the list of words seen at least 30 times in a million Common Voice English sentences has it exactly
 *    (assets/common_words.txt.gz, scripts/build_common_words.py). The first gate counted any web2 dictionary word, and junk made of
 *    short real words ("is the on a cama feel and") hit that list by luck;
 *  - the whole transcript is kept when at least [THRESHOLD] of its words are common. There is no short-transcript exemption any more:
 *    "fewer than 5 words are kept" let 57% of the Luganda clips of one corpus through (a one-word junk transcript proves nothing);
 *  - otherwise only the runs of at least [RUN_MIN] consecutive common words are kept ([Verdict.partial]), so the English part of a
 *    note that switches languages survives while the Luganda or Swahili around it (and Parakeet's junk) does not;
 *  - no words at all (music, silence): nothing to judge, nothing to keep, and no "not English" flag.
 *
 * Measured with the app's Fusion on held-out clips: the old gate scored 0.160 mean r@1 over Luganda, Swahili, Acholi, Runyankole and
 * Kinyarwanda test speech, this one 0.263 (no speech text at all: 0.323; a perfect language ID: 0.322), English 0.892 -> 0.901.
 * What is left is Parakeet writing real English words for a language it does not know; only an acoustic signal can catch that
 * (sherpa-onnx 1.13.8 returns no CTC confidence, and VoxLingua107 calls Ugandan English Swahili), see the research notes.
 */
class SpeechLanguageGate(val words: Lazy<EnglishWords?>, val common: Lazy<EnglishWords?> = lazyOf(null)) {
  /**
   * [share]: fraction of the [wordCount] words that are common English. [english]: keep the whole transcript.
   * [partial]: when it is not kept whole, the English runs worth keeping (space-joined, lower case), else empty.
   */
  class Verdict(val share: Double, val wordCount: Int, val english: Boolean, val partial: String = "")

  fun judge(text: String): Verdict {
    val tokens = tokens(text)
    // No list (asset missing or unreadable) means no judgement: keep what the speech model heard, as before the gate existed.
    val list = common.value
    if (list == null) return Verdict(if (tokens.isEmpty()) 0.0 else 1.0, tokens.size, true)
    if (tokens.isEmpty()) return Verdict(0.0, 0, true)
    val isCommon = tokens.map { list.contains(it) }
    val share = isCommon.count { it }.toDouble() / tokens.size
    if (share >= THRESHOLD) return Verdict(share, tokens.size, true)
    return Verdict(share, tokens.size, false, runs(tokens, isCommon))
  }

  /** The maximal runs of [RUN_MIN] or more consecutive common words, joined by spaces. */
  private fun runs(tokens: List<String>, isCommon: List<Boolean>): String {
    val out = StringBuilder()
    var from = -1
    for (i in 0..tokens.size) {
      val c = i < tokens.size && isCommon[i]
      if (c && from < 0) from = i
      if (!c && from >= 0) {
        if (i - from >= RUN_MIN) {
          if (out.isNotEmpty()) out.append(' ')
          out.append(tokens.subList(from, i).joinToString(" "))
        }
        from = -1
      }
    }
    return out.toString()
  }

  /**
   * Is a search query English enough for an English-only model (the quick-look pass's CLIP text tower)? True when at least
   * [QUERY_THRESHOLD] of its words are English. Measured with this word list (the research notes): 95% of the 324 English eval
   * queries pass (the rest are codes such as "KDC-260914-0072", where meaning search adds nothing), against 1% of Luganda and
   * 3% of Swahili FLORES/SALT sentences and none of 36 single Luganda or Swahili words. No words at all (a number): false.
   * No list: true, English first.
   */
  fun isEnglishQuery(text: String): Boolean {
    val list = words.value ?: return true
    val tokens = tokens(text)
    if (tokens.isEmpty()) return false
    return tokens.count { list.isEnglish(it) }.toDouble() / tokens.size >= QUERY_THRESHOLD
  }

  companion object {
    /** The index-detail line for a file's speech: what was kept, or why it was not and what stood in for it. */
    fun note(v: Verdict, speechPieces: Int, audioVectors: Boolean): String {
      if (v.english) return "$speechPieces speech pieces"
      val head = "speech not English (${Math.round(v.share * 100)}% common words)"
      if (speechPieces > 0) return "$head: kept its English parts ($speechPieces pieces)" + (if (audioVectors) ", audio vector" else "")
      return head + ": " + (if (audioVectors) "audio vector" else "transcript dropped")
    }

    /** Bumped whenever the rule changes: files read under an older rule are read again (IndexWorker). 1 = web2 share, 2 = common-word share + runs. */
    const val VERSION = 2
    const val THRESHOLD = 0.8
    const val RUN_MIN = 7
    /** Queries are short and a tester's names ("Nakato birthday cake") aren't in the list, so half is enough. */
    const val QUERY_THRESHOLD = 0.5
    const val ASSET = "english_words.txt.gz"
    const val COMMON_ASSET = "common_words.txt.gz"
    /** What [note] writes into `files.detail`, which is how files read under an older rule are found (IndexStore.requeueSpeechRead). */
    const val NOTE_ENGLISH = "speech pieces"
    const val NOTE_NOT_ENGLISH = "speech not English"
    private val TOKEN = Regex("[a-z]+(?:'[a-z]+)?")

    fun tokens(text: String): List<String> =
      TOKEN.findAll(text.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')).map { it.value }.toList()

    private fun load(assets: AssetSource, asset: String, what: String) = lazy {
      runCatching { BundledAssets.open(assets, asset).use { EnglishWords.fromGzip(it) } }
        .onSuccess { com.craneai.findr.core.FindrLog.i("FindrGate", "$what list loaded (${it.sizeBytes / 1024} KB)") }
        .onFailure { com.craneai.findr.core.FindrLog.w("FindrGate", "$what list unavailable: every text counts as English", it) }
        .getOrNull()
    }

    /**
     * Loads the lists from the APK assets on first use (2.4 MB and 0.16 MB heap, ~0.1 s) and keeps them for the process. The packager
     * stores an asset decompressed and without its .gz ([BundledAssets]); a failure is logged, because the gate then keeps everything.
     */
    fun fromAssets(assets: AssetSource) = SpeechLanguageGate(load(assets, ASSET, "English word"), load(assets, COMMON_ASSET, "Common word"))
  }
}
