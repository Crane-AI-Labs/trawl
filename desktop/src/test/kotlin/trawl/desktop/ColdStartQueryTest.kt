package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import trawl.desktop.ui.ResultNav
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColdStartQueryTest {
  @Test fun aColdWordsListGetsMeaningOnTheReadinessGenerationWithoutPublishingInterimWords() = runBlocking {
    val directory = Files.createTempDirectory("trawl-cold-query").toFile()
    val store = IndexStore.open(directory, log = {})
    val vectors = VectorIndex(parallelism = 1)
    try {
      fun topic(index: Int) = FloatArray(Fp16.DIM) { if (it == index) 1f else 0f }
      val names = listOf("money statement.txt", "farming notes.txt") + List(8) { "unrelated notes $it.txt" }
      val records = names.mapIndexed { index, name ->
        CrawlRecord("desktop", index.toLong(), "file:///test/$name", "test/", name, FileKind.DOCUMENT, null, 10, 10)
      }
      store.upsertFromCrawl(records)
      val ids = store.knownMediaStoreIds("desktop")
      val wordsId = ids.getValue(0)
      val meaningId = ids.getValue(1)
      store.saveSegments(wordsId, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "money transfer", vector = topic(4))), FileState.UNDERSTOOD)
      store.saveSegments(meaningId, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "robusta crop profits", vector = topic(3))), FileState.UNDERSTOOD)
      records.indices.drop(2).forEach { index ->
        store.saveSegments(ids.getValue(index.toLong()), listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "unrelated notes", vector = topic(4))), FileState.UNDERSTOOD)
      }
      vectors.load(store)
      val readiness = MutableStateFlow(IndexUi())
      val searcher = Searcher(store, vectors, indexedKinds = { Crawler.KINDS })
      val api = SearchApi(SearchHooks(
        store = { store }, searcher = { searcher }, modelReady = { readiness.value.modelReady }, vectorCount = { vectors.size },
        embed = { topic(3) }, wordsWeight = { 5.0 }, meaningWeight = { _, _ -> 5.0 }, kinds = { Crawler.KINDS },
      ))
      val session = SearchSession(api)
      val answers = Channel<SessionAnswer>(Channel.UNLIMITED)
      var loads = 0
      val query = "money harvest"
      val collector = launch {
        readiness.map { it.generation }.distinctUntilChanged().collectLatest {
          session.load(query, KindFilter.ALL, 0, quiet = loads++ > 0) { answers.trySend(it) }
        }
      }
      try {
        val cold = withTimeout(10_000) { answers.receive() }.hits!!
        assertTrue(cold.complete)
        assertEquals(listOf(wordsId), cold.hits.map { it.file.id })
        val generation = readiness.value.generation
        readiness.value = readiness.value.copy(modelReady = true, generation = generation + 1)
        val warm = withTimeout(10_000) { answers.receive() }.hits!!
        assertTrue(warm.complete, "a quiet refresh does not publish the interim words answer")
        assertEquals(meaningId, warm.hits.first().file.id)
        assertEquals(2, warm.strongCount)
        assertEquals(generation + 1, readiness.value.generation)
        readiness.value = readiness.value.copy(phase = Phase.UP_TO_DATE)
        yield()
        assertEquals(2, loads, "readiness refreshes once; unrelated status changes do not reload")
        assertTrue(answers.tryReceive().isFailure)
        val warmIds = warm.hits.map { it.file.id }
        assertEquals(meaningId, ResultNav.reconcile(wordsId, query, query, warmIds, userMoved = false), "the untouched selection follows the best meaning hit")
        assertEquals(wordsId, ResultNav.reconcile(wordsId, query, query, warmIds, userMoved = true), "a row the person chose stays chosen")
      } finally {
        collector.cancel()
      }
    } finally {
      vectors.close()
      store.close()
      directory.deleteRecursively()
    }
  }
}
