package com.craneai.findr.engine

import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchResults
import com.craneai.findr.search.Fusion
import com.craneai.findr.search.Searcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The query in both vector spaces: EmbeddingGemma's [eg] and the quick-look model's [quick] (D2, for pictures
 * EmbeddingGemma has not understood yet). Either can be null when that modality sits the query out; [note] says why, for the log.
 */
class QueryVectors(val eg: FloatArray?, val quick: FloatArray?, val note: String = "")

/**
 * The two-step search behind [FindrEngine.searchProgressive]: words first, then meaning.
 *
 * The words step reads only SQLite (FTS5), so it must not wait for the in-memory vector matrix. A cold process used to
 * run `ensureVectors()` before anything was shown, which put the whole vector load (it grows with the index) in front
 * of the first result. Now the words answer goes out first, the matrix loads next, and the final answer is the one the
 * old order produced: same query, same vectors, same ranking.
 *
 * Typing (type-to-filter): the words answer goes out on every keystroke, but the meaning step waits [run]'s `meaningDelayMs`
 * first. A next keystroke cancels the flow during that wait, so no query embed starts for a word that is still being typed
 * (an embed cannot be interrupted once it runs, and the engine runs one model call at a time). Same answers, same order.
 *
 * Meaning is wanted when EmbeddingGemma's vectors are loaded (and its model is there) OR quick-look vectors exist and
 * quick look is on. While the first photos are still being understood, only the quick-look vectors exist, and the
 * words answer must not claim to be complete then.
 *
 * @param searcher the current searcher (the engine swaps its store when the index is deleted)
 * @param meaningPossible a model that can produce a meaning vector is there: EmbeddingGemma installed, or quick look on
 * @param storedVectors cheap check on the store: is there any vector (of either kind) to search by meaning, no matrix needed
 * @param ensureVectors loads the matrices once; later calls return at once
 * @param wantsMeaning the engine's rule, valid after [ensureVectors]: the query is long enough and a loaded matrix can use a model
 * @param embed the query's vectors (null parts when they could not be made; the caller logs why)
 * @param includeSensitive whether sensitive files may appear; read once per query so both answers use the same policy.
 *   Fails closed: the default hides them.
 * @param wordsWeight the word weight for the words-first answer, which has only the typed text and must not wait for anything
 *   ([WordWeightPolicy.wordsWeight])
 * @param meaningWeight the word weight for the final answer, asked once AFTER the embed with the query's own vector (D19c reads the
 *   query's language from it; [WordWeightPolicy.meaningWeight]). The ranking of the final answer uses this weight.
 * @param onMeaning timing hook for the log
 */
class ProgressiveSearch(
  private val searcher: () -> Searcher,
  private val meaningPossible: () -> Boolean,
  private val storedVectors: () -> Boolean,
  private val ensureVectors: suspend () -> Unit,
  private val wantsMeaning: (String) -> Boolean,
  private val embed: suspend (String) -> QueryVectors,
  private val includeSensitive: () -> Boolean = { false },
  private val wordsWeight: (query: String) -> Double = { Fusion.WORD_WEIGHT },
  private val meaningWeight: (query: String, vector: FloatArray?) -> Double = { _, _ -> Fusion.WORD_WEIGHT },
  private val onMeaning: (query: String, embedMs: Long, results: SearchResults, note: String) -> Unit = { _, _, _, _ -> },
) {
  fun run(query: String, filter: KindFilter, limit: Int, meaningDelayMs: Long = 0, view: ResultView = ResultView.DEFAULT): Flow<SearchResults> = flow {
    val q = query.trim()
    if (q.isEmpty()) {
      emit(SearchResults(query, emptyList(), 0, 0, indexIncomplete = false))
      return@flow
    }
    // What the matrices will say once loaded, read from the store so the words answer need not wait for them. The final
    // answer below re-checks against the loaded matrices; this only decides the first answer's `complete` flag.
    val sensitive = includeSensitive()
    val expectMeaning = q.length >= MIN_MEANING_CHARS && meaningPossible() && storedVectors()
    val words = searcher().search(q, null, filter, limit, prefixLast = true, includeSensitive = sensitive, wordWeight = wordsWeight(q), view = view).copy(complete = !expectMeaning)
    emit(words)
    // Typing: cancelled here by the next keystroke, so no embed starts for a half-typed word. Only when meaning is expected,
    // so a words-only answer is final as fast as before.
    if (meaningDelayMs > 0 && !words.complete) delay(meaningDelayMs)
    ensureVectors()
    if (wantsMeaning(q)) {
      val t0 = System.nanoTime()
      val vectors = embed(q)
      val embedMs = (System.nanoTime() - t0) / 1_000_000
      val weight = meaningWeight(q, vectors.eg)
      val results = searcher().search(q, vectors.eg, filter, limit, prefixLast = true, quickVector = vectors.quick, includeSensitive = sensitive, wordWeight = weight, view = view)
      onMeaning(q, embedMs, results, vectors.note)
      emit(results)
    } else if (expectMeaning) {
      emit(words.copy(complete = true))   // the store listed vectors the matrices could not use: the words answer is the answer
    }
  }

  companion object {
    const val MIN_MEANING_CHARS = 3
  }
}
