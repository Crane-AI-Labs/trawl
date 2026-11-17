package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimeoutRetryStoreTest {
  private lateinit var directory: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    directory = Files.createTempDirectory("findr-timeout-retry").toFile()
    store = IndexStore.open(directory, log = {})
  }

  @After fun tearDown() {
    store.close()
    directory.deleteRecursively()
  }

  private fun add(mediaId: Long, reason: String): Long {
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", mediaId, "content://media/external/file/$mediaId", "Documents/",
      "file$mediaId.txt", FileKind.DOCUMENT, "text/plain", 1_000, 1_000)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(mediaId)
    store.saveSegments(fileId, listOf(Segment(SegmentKind.CHUNK, text = "words $mediaId")), FileState.FAILED, reason)
    return fileId
  }

  @Test fun onlyLegacyTimeoutFailuresRejoinTheLaterPassQueueAndKeepTheirWords() {
    val timeout = add(1, "took too long")
    val broken = add(2, IndexStore.CANT_OPEN)
    val bounded = add(3, "took too long without progress (3 attempts)")
    val other = add(4, "IOException: took too long")
    assertEquals(1, store.retryTimedOut())
    assertEquals(FileState.PARTIAL to "next=0 timeouts=1", store.stateOf(timeout))
    assertEquals(listOf(null to "words 1"), store.storedChunks(timeout, 0, 10))
    assertEquals(FileState.FAILED, store.stateOf(broken)!!.first)
    assertEquals(FileState.FAILED, store.stateOf(bounded)!!.first)
    assertEquals(FileState.FAILED, store.stateOf(other)!!.first)
    assertTrue(store.nextPending(10, setOf(FileKind.DOCUMENT)).isEmpty())
    assertEquals(listOf(timeout), store.nextPartial(10, setOf(FileKind.DOCUMENT)).map { it.file.id })
    store.close()
    store = IndexStore.open(directory, log = {})
    assertEquals(0, store.retryTimedOut())
    assertEquals(FileState.PARTIAL to "next=0 timeouts=1", store.stateOf(timeout))
  }

  @Test fun theExistingUpdateRetryStillRequeuesTerminalFailures() {
    val bounded = add(1, "took too long without progress (3 attempts)")
    assertEquals(0, store.retryTimedOut())
    assertEquals(1, store.retryFailed())
    assertEquals(FileState.SEARCHABLE to null, store.stateOf(bounded))
  }
}
