package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InflightDeathTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private var fileId = 0L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-inflight").toFile()
    store = IndexStore.open(dir)
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/1", "Documents/", "notes.pdf",
      FileKind.PDF, null, 50_000, 1_000)))
    fileId = store.nextPending(1, setOf(FileKind.PDF)).single().file.id
  }

  @After fun tearDown() { store.close(); dir.deleteRecursively() }

  private fun processDies() { store.close(); store = IndexStore.open(dir) }

  private fun repeatedDeaths(step: Int = 0, windowed: Boolean = false): IndexStore.Recovery {
    store.markInflight(fileId, step, windowed); processDies()
    assertNull(store.recoverInflight())
    store.markInflight(fileId, step, windowed); processDies()
    return store.recoverInflight()!!
  }

  @Test fun repeatedDeathsWithoutMemoryEvidenceHaveANeutralLabel() {
    val recovery = repeatedDeaths()
    assertFalse(recovery.skippedWindow)
    assertEquals(FileState.FAILED, store.stateOf(fileId)!!.first)
    assertEquals("reading interrupted repeatedly; will retry after an app update", store.stateOf(fileId)!!.second)
    assertEquals(0, store.stats().tooLarge)
    assertTrue(store.nextPending(1, setOf(FileKind.PDF)).isEmpty())
    assertNull(store.recoverInflight())
  }

  @Test fun legacyMarksHaveNoMemoryEvidence() {
    store.setMeta("inflight_file", "$fileId:0:1:f")
    processDies()
    assertFalse(store.recoverInflight()!!.skippedWindow)
    assertEquals("reading interrupted repeatedly; will retry after an app update", store.stateOf(fileId)!!.second)
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun interruptionsDoNotDiscardAWindow() {
    store.setState(fileId, FileState.PARTIAL, "next=3")
    assertFalse(repeatedDeaths(step = 3, windowed = true).skippedWindow)
    assertEquals(FileState.FAILED, store.stateOf(fileId)!!.first)
    assertEquals("reading interrupted repeatedly; will retry after an app update", store.stateOf(fileId)!!.second)
    assertEquals(0, store.stats().tooLarge)
  }

  private fun memoryDeath(step: Int = 0, windowed: Boolean = false) {
    store.markInflight(fileId, step, windowed)
    store.noteInflightMemoryPressure()
    processDies()
  }

  @Test fun memoryEvidenceSurvivesDeathAndIsRequiredOnBothAttempts() {
    memoryDeath(); assertNull(store.recoverInflight())
    memoryDeath()
    val recovery = store.recoverInflight()!!
    assertEquals(IndexStore.TOO_LARGE_DETAIL, recovery.detail)
    assertEquals(IndexStore.TOO_LARGE_DETAIL, store.stateOf(fileId)!!.second)
    assertEquals(1, store.stats().tooLarge)
  }

  @Test fun aMemoryDeathFollowedByAnExternalStopIsNeutral() {
    memoryDeath(); assertNull(store.recoverInflight())
    store.markInflight(fileId); processDies()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.recoverInflight()!!.detail)
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun anExternalStopFollowedByAMemoryDeathIsNeutral() {
    store.markInflight(fileId); processDies(); assertNull(store.recoverInflight())
    memoryDeath()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.recoverInflight()!!.detail)
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun repeatedSignalsDuringOneAttemptAreStillOnlyOneMemoryDeath() {
    store.markInflight(fileId)
    repeat(4) { store.noteInflightMemoryPressure() }
    processDies(); assertNull(store.recoverInflight())
    store.markInflight(fileId); processDies()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.recoverInflight()!!.detail)
  }

  @Test fun pressureOutsideAReadingAttemptIsNotEvidenceForTheNextFile() {
    store.noteInflightMemoryPressure()
    assertNull(store.meta("inflight_file"))
    repeatedDeaths()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.stateOf(fileId)!!.second)
  }

  @Test fun pressureInTheRestartedProcessCannotRewriteThePreviousDeathsEvidence() {
    store.markInflight(fileId); processDies(); store.noteInflightMemoryPressure()
    assertNull(store.recoverInflight())
    store.markInflight(fileId); processDies(); store.noteInflightMemoryPressure()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.recoverInflight()!!.detail)
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun completingAnAttemptClearsItsEvidence() {
    store.markInflight(fileId); store.noteInflightMemoryPressure(); store.clearInflight()
    processDies(); assertNull(store.recoverInflight())
    repeatedDeaths()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.stateOf(fileId)!!.second)
  }

  @Test fun aDifferentStepDoesNotInheritMemoryDeaths() {
    memoryDeath(step = 1); assertNull(store.recoverInflight())
    memoryDeath(step = 2); assertNull(store.recoverInflight())
    store.markInflight(fileId, step = 2); processDies()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.recoverInflight()!!.detail)
  }

  @Test fun aDifferentFileDoesNotInheritMemoryDeaths() {
    memoryDeath(); assertNull(store.recoverInflight())
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 2, "content://media/2", "Documents/", "other.pdf",
      FileKind.PDF, null, 50_000, 2_000)))
    fileId = store.nextPending(2, setOf(FileKind.PDF)).single { it.file.name == "other.pdf" }.file.id
    memoryDeath(); assertNull(store.recoverInflight())
    store.markInflight(fileId); processDies()
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.recoverInflight()!!.detail)
  }

  @Test fun onlyRepeatedMemoryDeathsSkipAWindow() {
    store.setState(fileId, FileState.PARTIAL, "next=3")
    memoryDeath(step = 3, windowed = true); assertNull(store.recoverInflight())
    memoryDeath(step = 3, windowed = true)
    assertTrue(store.recoverInflight()!!.skippedWindow)
    assertEquals("next=4 skipped=1", store.stateOf(fileId)!!.second)
    assertEquals(0, store.stats().tooLarge)
    memoryDeath(step = 4, windowed = true); assertNull(store.recoverInflight())
    memoryDeath(step = 4, windowed = true)
    assertFalse(store.recoverInflight()!!.skippedWindow)
    assertEquals(IndexStore.TOO_LARGE_DETAIL, store.stateOf(fileId)!!.second)
  }

  @Test fun interruptionsAfterASkippedWindowStillHaveANeutralLabel() {
    store.setState(fileId, FileState.PARTIAL, "next=4 skipped=1")
    repeatedDeaths(step = 4, windowed = true)
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.stateOf(fileId)!!.second)
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun interruptedFilesRetryOncePerInstalledBuildNotAtEveryWorkerStart() {
    assertEquals(0, store.retryFailedAfterUpdate("installed"))
    repeatedDeaths()
    assertEquals(0, store.retryFailedAfterUpdate("installed"))
    processDies()
    assertEquals(0, store.retryFailedAfterUpdate("installed"))
    assertEquals(FileState.FAILED, store.stateOf(fileId)!!.first)
    assertEquals(1, store.retryFailedAfterUpdate("updated"))
    assertEquals(FileState.SEARCHABLE, store.stateOf(fileId)!!.first)
    assertNull(store.stateOf(fileId)!!.second)
    assertNull(store.recoverInflight())
    repeatedDeaths()
    assertEquals(0, store.retryFailedAfterUpdate("updated"))
    assertEquals(1, store.retryFailedAfterUpdate("next-update"))
  }

  @Test fun theExistingBuildRetryStampSurvivesTheChange() {
    store.setMeta("retried_failures_for_build", "installed")
    repeatedDeaths()
    assertEquals(0, store.retryFailedAfterUpdate("installed"))
    assertEquals(1, store.retryFailedAfterUpdate("updated"))
  }

  @Test fun retryingDoesNotLoseLearnedContentOrKeepOldMemoryEvidence() {
    store.saveSegments(fileId, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 0, 1, text = "learned words")),
      FileState.PARTIAL, "next=1")
    memoryDeath(step = 1); assertNull(store.recoverInflight())
    memoryDeath(step = 1); store.recoverInflight()
    assertEquals(1, store.retryFailedAfterUpdate("updated"))
    assertEquals("learned words", store.read {
      it.text("SELECT x.body FROM fts x JOIN segments s ON s.id = x.rowid WHERE s.file = ? AND s.kind = ?", fileId, SegmentKind.CHUNK.db)
    })
    repeatedDeaths(step = 1)
    assertEquals(IndexStore.INTERRUPTED_DETAIL, store.stateOf(fileId)!!.second)
  }
}
