package com.craneai.findr.engine

import android.content.Context
import android.net.Uri
import com.craneai.findr.core.ExtractedText
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.TextExtractor
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.PendingFile
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UnderstandingStepTest {
  private lateinit var directory: File
  private lateinit var store: IndexStore
  private var fileId = 0L
  private val kinds = setOf(FileKind.DOCUMENT, FileKind.PDF)
  private var pages = List(7) { "chunk${it + 1} words" }
  private var reads = 0
  private var extractionDelayMs = 0L
  private val embedder = SlowEmbedder()

  @Before fun setUp() {
    directory = Files.createTempDirectory("findr-understand-step").toFile()
    store = IndexStore.open(directory, log = {})
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/external/file/1", "Documents/", "slow.txt",
      FileKind.DOCUMENT, "text/plain", 1_000, 1_000)))
    fileId = store.knownMediaStoreIds("external_primary").getValue(1)
  }

  @After fun tearDown() {
    store.close()
    directory.deleteRecursively()
  }

  private class SlowEmbedder : Embedder {
    var delayMs = 100L
    var failure: Exception? = null
    var nestedTimeout = false
    val completed = ArrayList<String>()
    override val profile = MemoryProfile.STANDARD
    override val repairing = MutableStateFlow(false)
    override val modelNames = emptyList<String>()
    override val modelFile: File? = null
    override val supportsAudio = false
    override val imagesByEmbeddingGemma = true
    override val imageFingerprint: String? = null
    override val textFingerprint: String? = "test"
    override suspend fun query(text: String): FloatArray = error("not a query")
    override suspend fun warm() {}
    override suspend fun document(text: String, title: String?): FloatArray {
      failure?.let { throw it }
      if (nestedTimeout) withTimeout(25) { delay(100) }
      delay(delayMs)
      completed += text
      val page = text.substringAfter("chunk").substringBefore(" ").toInt()
      return vector(page)
    }
    override suspend fun image(jpeg: ByteArray): FloatArray = error("not an image")
    override suspend fun audio(wav: ByteArray): FloatArray = error("not audio")
    override suspend fun release() {}
    override suspend fun dropAudioEncoder() {}
  }

  private fun processor() = FileProcessor(RuntimeEnvironment.getApplication(), embedder, text = object : TextExtractor {
    override fun canRead(mimeType: String?, name: String) = true
    override suspend fun extract(context: Context, uri: Uri, mimeType: String?, name: String, maxChars: Int): ExtractedText {
      reads++
      delay(extractionDelayMs)
      return ExtractedText(pages)
    }
  }, speechGate = SpeechLanguageGate(lazyOf(null)),
    storedChunks = { id, from, limit -> store.storedChunks(id, from, limit) to store.storedChunkCount(id) })

  private fun pending(): PendingFile = (store.nextPending(10, kinds) + store.nextPartial(10, kinds)).single { it.file.id == fileId }

  private suspend fun step(timeoutMs: Long = 250): UnderstandingStep.Outcome {
    val result = UnderstandingStep(store, processor()).process(pending(), false, false, timeoutMs, true)
    println("understanding-step: state=${state().first} detail=${state().second} vectors=${store.vectorCount()} words=${store.storedChunkCount(fileId)} timedOut=${result.timedOut}")
    return result
  }

  private fun reopen() {
    store.close()
    store = IndexStore.open(directory, log = {})
  }

  private fun state() = store.stateOf(fileId)!!

  private fun assertWords() {
    assertEquals(pages, store.storedChunks(fileId, 0, pages.size).map { it.second })
    assertEquals(pages.size, store.storedChunkCount(fileId))
  }

  private fun assertVectors(count: Int) {
    assertEquals(count, store.vectorCount())
    var seen = 0
    store.forEachVectorOfFile(fileId) { _, _, _, modality, _, blob ->
      assertEquals(Modality.TEXT, modality)
      seen++
      assertArrayEquals(vector(seen), Fp16.blobToFloats(blob), 0f)
    }
    assertEquals(count, seen)
  }

  @Test fun completedChunksSurviveTimeoutAndLaterPassesFinishWithoutReadingAgain() = runTest {
    val first = step()
    assertTrue(first.timedOut)
    assertFalse(first.finished)
    assertNull(first.next)
    assertEquals(FileState.PARTIAL to "chunks 2/7 next=2", state())
    assertVectors(2)
    assertWords()
    assertTrue(store.nextPending(10, kinds).isEmpty())
    reopen()
    step()
    assertEquals(FileState.PARTIAL to "chunks 4/7 next=4", state())
    assertVectors(4)
    assertWords()
    reopen()
    step()
    val last = step()
    assertFalse(last.timedOut)
    assertTrue(last.finished)
    assertEquals(FileState.UNDERSTOOD to "chunks 7/7", state())
    assertVectors(pages.size)
    assertWords()
    assertEquals(pages, embedder.completed)
    assertEquals(1, reads)
    assertTrue(store.nextPartial(10, kinds).isEmpty())
  }

  @Test fun ordinaryLongDocumentsKeepTheirStepSizeAndLateVectorFlags() = runTest {
    pages = List(FileProcessor.CHUNKS_PER_STEP + 5) { "chunk${it + 1} words" }
    embedder.delayMs = 1
    val first = step(1_000)
    assertFalse(first.timedOut)
    assertEquals(FileProcessor.CHUNKS_PER_STEP, first.next)
    assertFalse(first.finished)
    assertWords()
    assertVectors(FileProcessor.CHUNKS_PER_STEP)
    assertTrue(step(1_000).finished)
    assertVectors(pages.size)
    assertWords()
    var early = 0
    var late = 0
    store.forEachVectorOfFile(fileId) { _, _, _, _, flag, _ -> if (flag == 0) early++ else late++ }
    assertEquals(FileProcessor.CHUNKS_PER_STEP, early)
    assertEquals(5, late)
    assertEquals(1, reads)
  }

  @Test fun repeatedTimeoutsWithoutAnyProgressFailWithAReasonAcrossRestarts() = runTest {
    embedder.delayMs = 1_000
    repeat(UnderstandingStep.MAX_STALLED_TIMEOUTS) { attempt ->
      assertTrue(step().timedOut)
      assertVectors(0)
      assertWords()
      if (attempt + 1 < UnderstandingStep.MAX_STALLED_TIMEOUTS) {
        assertEquals(FileState.PARTIAL to "chunks 0/7 next=0 timeouts=${attempt + 1}", state())
      }
      reopen()
    }
    assertEquals(FileState.FAILED to "took too long without progress (${UnderstandingStep.MAX_STALLED_TIMEOUTS} attempts)", state())
    assertEquals(0, store.retryTimedOut())
    assertTrue(store.nextPending(10, kinds, includePartial = true).isEmpty())
  }

  @Test fun stalledLaterPassesKeepEarlierVectorsAndAllWords() = runTest {
    step()
    embedder.delayMs = 1_000
    repeat(UnderstandingStep.MAX_STALLED_TIMEOUTS) { step(); reopen() }
    assertEquals(FileState.FAILED, state().first)
    assertVectors(2)
    assertWords()
    assertEquals(pages.size.toLong(), store.read { it.long("SELECT COUNT(*) FROM segments WHERE file = ? AND kind != 'name'", fileId) })
  }

  @Test fun makingProgressResetsTheConsecutiveStallBudget() = runTest {
    embedder.delayMs = 1_000
    repeat(UnderstandingStep.MAX_STALLED_TIMEOUTS - 1) { step() }
    embedder.delayMs = 100
    step()
    assertEquals(FileState.PARTIAL to "chunks 2/7 next=2", state())
    embedder.delayMs = 1_000
    step()
    assertEquals(FileState.PARTIAL to "chunks 2/7 next=2 timeouts=1", state())
    embedder.delayMs = 1
    assertTrue(step().finished)
    assertVectors(pages.size)
    assertWords()
  }

  @Test fun extractionTimeoutPreservesExistingWordsAndStillHasABoundedRetry() = runTest {
    store.saveSegments(fileId, listOf(Segment(SegmentKind.CHUNK, text = "old words")), FileState.SEARCHABLE, "words")
    extractionDelayMs = 1_000
    repeat(UnderstandingStep.MAX_STALLED_TIMEOUTS) { step() }
    assertEquals(FileState.FAILED, state().first)
    assertEquals(listOf(null to "old words"), store.storedChunks(fileId, 0, 10))
    assertVectors(0)
  }

  @Test fun aLegacyTimeoutOnlyFailureCanFinishWithoutAnAppUpdate() = runTest {
    store.setState(fileId, FileState.FAILED, "took too long")
    assertEquals(1, store.retryTimedOut())
    assertEquals(FileState.PARTIAL to "next=0 timeouts=1", state())
    embedder.delayMs = 1
    assertTrue(step().finished)
    assertVectors(pages.size)
    assertWords()
  }

  @Test fun aLegacyTimeoutCountsTowardTheStallLimitInsteadOfStartingAnEndlessRetry() = runTest {
    store.setState(fileId, FileState.FAILED, "took too long")
    store.retryTimedOut()
    embedder.delayMs = 1_000
    repeat(UnderstandingStep.MAX_STALLED_TIMEOUTS - 1) { step(); reopen() }
    assertEquals(FileState.FAILED, state().first)
    assertEquals(0, store.retryTimedOut())
    assertVectors(0)
    assertWords()
  }

  @Test fun pdfChunksUseTheSameResumablePath() = runTest {
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/external/file/1", "Documents/", "slow.pdf",
      FileKind.PDF, "application/pdf", 1_000, 2_000)))
    step()
    assertEquals(FileState.PARTIAL to "chunks 2/7 next=2", state())
    assertVectors(2)
    assertWords()
    reopen()
    embedder.delayMs = 1
    assertTrue(step().finished)
    assertVectors(pages.size)
    assertWords()
    assertEquals(1, reads)
  }

  @Test fun switchingAFileOffDuringEmbeddingDoesNotBringItBackOnTimeout() = runTest {
    val before = store.storedChunkCount(fileId)
    val off = "turned off"
    val worker = processor()
    val found = pending()
    store.setState(fileId, FileState.SKIPPED, off)
    UnderstandingStep(store, worker).process(found, false, false, 250, true)
    assertEquals(FileState.SKIPPED to off, state())
    assertVectors(0)
    assertEquals(before, store.storedChunkCount(fileId))
  }

  @Test fun anOuterTimeoutDoesNotBecomeAFileTimeoutOrSaveUnfinishedWork() = runTest {
    val before = state()
    var cancelled = false
    try { withTimeout(150) { step(1_000) } } catch (expected: TimeoutCancellationException) { cancelled = true }
    assertTrue(cancelled)
    assertEquals(before, state())
    assertVectors(0)
  }

  @Test fun anEmbedderTimeoutDoesNotConsumeTheStepRetryBudget() = runTest {
    val before = state()
    embedder.nestedTimeout = true
    var cancelled = false
    try { step() } catch (expected: TimeoutCancellationException) { cancelled = true }
    assertTrue(cancelled)
    assertEquals(before, state())
    assertVectors(0)
  }

  @Test fun otherFailuresAreNotMisclassifiedAsTimeouts() = runTest {
    val before = state()
    val failure = IllegalStateException("embedder unavailable")
    embedder.failure = failure
    var caught: Exception? = null
    try { step() } catch (expected: IllegalStateException) { caught = expected }
    assertEquals(failure.javaClass, caught?.javaClass)
    assertEquals(failure.message, caught?.message)
    assertEquals(before, state())
    assertVectors(0)
  }

  @Test fun aSuccessfulEmptyDocumentStillReplacesItsOldReading() = runTest {
    store.saveSegments(fileId, listOf(Segment(SegmentKind.CHUNK, text = "old words", vector = vector(1))), FileState.SEARCHABLE)
    pages = emptyList()
    assertTrue(step().finished)
    assertEquals(FileState.UNDERSTOOD to "no text", state())
    assertVectors(0)
    assertWords()
  }

  companion object {
    private fun vector(page: Int) = FloatArray(Fp16.DIM) { if (it == page) 1f else 0f }
  }
}
