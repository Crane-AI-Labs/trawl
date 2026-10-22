package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SearchHit
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.PrivacyRules
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What the person marked sensitive stays out of results and the recent list, is counted, and survives a rebuild. */
class SensitiveSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private var nextMediaId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-sensitive").toFile()
    open()
  }

  private fun open() {
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun tearDown() {
    vectors.close()
    store.close()
    dir.deleteRecursively()
  }

  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }

  private fun add(name: String, path: String = "DCIM/Camera/", mtime: Long = 1_000, topic: Int? = null, vec: FloatArray? = null): Long {
    val id = nextMediaId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", path, name, FileKind.IMAGE, null, 50_000, mtime)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    val v = vec ?: topic?.let { topic(it) }
    if (v != null) {
      val seg = Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = v)
      val ids = store.saveSegments(fileId, listOf(seg), FileState.UNDERSTOOD)
      vectors.add(ids[0], fileId, FileKind.IMAGE, Modality.IMAGE, false, v)
    }
    return fileId
  }

  private fun hitNames(hits: List<SearchHit>) = hits.map { it.file.name }
  private fun fileNames(files: List<IndexedFile>) = files.map { it.name }
  private fun words(q: String, includeSensitive: Boolean = false, limit: Int = 20) =
    searcher.search(q, null, KindFilter.ALL, limit, includeSensitive = includeSensitive)

  @Test fun failsClosedByDefault() {
    val id = add("passport_scan.jpg")
    store.setMarked(id, true)
    val r = searcher.search("passport", null, KindFilter.ALL, 20)   // no includeSensitive argument
    assertTrue(r.hits.isEmpty())
    assertEquals(1, r.hiddenCount)
  }

  @Test fun aMarkedFileIsHiddenFromWordMatchesAndCounted() {
    add("holiday_beach.jpg")
    val secret = add("holiday_private.jpg")
    store.setMarked(secret, true)
    val hidden = words("holiday")
    assertEquals(listOf("holiday_beach.jpg"), hitNames(hidden.hits))
    assertEquals(1, hidden.hiddenCount)
    val shown = words("holiday", includeSensitive = true)
    assertEquals(setOf("holiday_beach.jpg", "holiday_private.jpg"), hitNames(shown.hits).toSet())
    assertEquals(0, shown.hiddenCount)
  }

  @Test fun theFilterRunsBeforeTheLimit() {
    // The sensitive file is the newest, so it would take the single slot. It must not.
    add("trip_a.jpg", mtime = 1_000)
    val secret = add("trip_b.jpg", mtime = 9_000)
    store.setMarked(secret, true)
    val r = words("trip", limit = 1)
    assertEquals(1, r.hits.size)
    assertTrue(r.hits[0].file.name != "trip_b.jpg")
  }

  /** Unrelated pictures, so the per-query scale is a realistic cloud and a planted match stands out (as in SearcherTest). */
  private fun addBackground(count: Int = 80) {
    val random = kotlin.random.Random(7)
    repeat(count) {
      val raw = FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() }
      val n = kotlin.math.sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
      add("bg$it.jpg", path = "Other/", vec = FloatArray(raw.size) { raw[it] / n })
    }
  }

  @Test fun aMarkedFileIsHiddenFromMeaningMatchesToo() {
    addBackground()
    val a = add("one.jpg", topic = 7)
    add("two.jpg", topic = 7)
    store.setMarked(a, true)
    val r = searcher.search("anything", topic(7), KindFilter.ALL, 20)
    assertEquals(listOf("two.jpg"), hitNames(r.hits))
    assertEquals(1, r.hiddenCount)
  }

  @Test fun recentLeavesSensitiveOutAndCountsThem() {
    add("a.jpg", mtime = 3_000)
    val b = add("b.jpg", mtime = 2_000)
    add("c.jpg", mtime = 1_000)
    store.setMarked(b, true)
    val kinds = KindFilter.ALL.kinds
    assertEquals(listOf("a.jpg", "c.jpg"), fileNames(store.recent(kinds, 50)))
    assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), fileNames(store.recent(kinds, 50, includeSensitive = true)))
    assertEquals(1, store.recentHiddenCount(kinds))
  }

  @Test fun markingAndUnmarkingFlipsTheFlagAndReturnsBothKeys() {
    val id = add("x.jpg")
    val rules = store.setMarked(id, true)!!
    assertEquals(
      setOf(PrivacyRules.key("external_primary", 1L), PrivacyRules.pathKey("external_primary", "DCIM/Camera/", "x.jpg")),
      rules.markedFiles,
    )
    assertTrue(store.filesByIds(listOf(id)).getValue(id).markedSensitive)
    assertTrue(store.setMarked(id, false)!!.markedFiles.isEmpty())
    assertFalse(store.filesByIds(listOf(id)).getValue(id).sensitive)
    assertNull(store.setMarked(9_999, true))
  }

  @Test fun filesInAPrivateFolderAreSensitiveAndTheFolderFlagSurvivesUnmarking() {
    val inside = add("a.jpg", path = "DCIM/Private/Trip/")
    add("b.jpg", path = "DCIM/Camera/")
    store.applyRules(PrivacyRules(privateFolders = setOf("DCIM/Private")))
    assertTrue(store.filesByIds(listOf(inside)).getValue(inside).inPrivateFolder)
    store.setMarked(inside, true)
    store.setMarked(inside, false)
    assertTrue("only the folder lifts the folder flag", store.filesByIds(listOf(inside)).getValue(inside).sensitive)
    assertEquals(listOf("b.jpg"), fileNames(store.recent(KindFilter.ALL.kinds, 10)))
  }

  @Test fun aFolderNamePrefixIsNotAFolder() {
    add("a.jpg", path = "DCIM/Private2/")
    store.applyRules(PrivacyRules(privateFolders = setOf("DCIM/Private")))
    assertEquals(0, store.sensitiveCount())
  }

  @Test fun aNewFileCrawledIntoAPrivateFolderIsStampedOnTheWay() {
    store.applyRules(PrivacyRules(privateFolders = setOf("Pictures/Hidden")))
    val id = add("new.jpg", path = "Pictures/Hidden/")
    assertTrue(store.filesByIds(listOf(id)).getValue(id).inPrivateFolder)
  }

  @Test fun marksComeBackAfterTheIndexIsThrownAwayAndRebuilt() {
    val id = add("keep_private.jpg")
    val rules = PrivacyRules(markedFiles = store.setMarked(id, true)!!.markedFiles)
    // "Delete index": the file and its flag are gone; the settings (the rules) are not.
    vectors.close()
    store.close()
    dir.deleteRecursively()
    dir.mkdirs()
    nextMediaId = 1L
    open()
    store.applyRules(rules)                  // the engine does this first thing after reopening
    val again = add("keep_private.jpg")      // the crawler finds the same file (same MediaStore id) again
    assertTrue(store.filesByIds(listOf(again)).getValue(again).markedSensitive)
    assertTrue(words("keep_private").hits.isEmpty())
    assertEquals(1, words("keep_private").hiddenCount)
  }

  /** Throws the index away the way "Delete everything Findr knows" does; only the rules (kept in the settings) survive. */
  private fun deleteEverything() {
    vectors.close()
    store.close()
    dir.deleteRecursively()
    dir.mkdirs()
    open()
  }

  /** The crawler finds a file again: the MediaStore id is [id], which may differ from last time. */
  private fun crawlAgain(name: String, id: Long, path: String = "DCIM/Camera/"): Long {
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", path, name, FileKind.IMAGE, null, 50_000, 1_000)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  @Test fun markedStaysHiddenAfterDeleteEverythingAndReindexEvenWithNewMediaStoreIds() {
    val id = add("passport_scan.jpg")
    add("holiday_beach.jpg")
    var saved = PrivacyRules(markedFiles = store.setMarked(id, true)!!.markedFiles)   // what the settings hold
    deleteEverything()
    store.applyRules(saved)                              // the engine does this when it reopens the index
    // The phone's MediaStore was rescanned meanwhile, so both files come back with different ids.
    val again = crawlAgain("passport_scan.jpg", 501)
    val beach = crawlAgain("holiday_beach.jpg", 502)
    assertTrue(store.filesByIds(listOf(again)).getValue(again).markedSensitive)
    assertFalse(store.filesByIds(listOf(beach)).getValue(beach).sensitive)
    assertTrue(words("passport").hits.isEmpty())
    assertEquals(1, words("passport").hiddenCount)
    assertEquals(listOf("holiday_beach.jpg"), hitNames(words("holiday").hits))
    // The store learned the new id and hands it back to be saved.
    val learned = store.takeLearnedRules()!!
    assertTrue(PrivacyRules.key("external_primary", 501) in learned.markedFiles)
    assertNull(store.takeLearnedRules())
    saved = learned
    // And a second wipe with a third set of ids still hides it.
    deleteEverything()
    store.applyRules(saved)
    val third = crawlAgain("passport_scan.jpg", 900)
    assertTrue(store.filesByIds(listOf(third)).getValue(third).markedSensitive)
  }

  @Test fun aRenamedMarkedFileStaysMarkedAndTheMarkFollowsTheNewName() {
    val id = add("scan.jpg")
    store.setMarked(id, true)
    val again = crawlAgain("scan_renamed.jpg", 1)          // same MediaStore id, new name
    assertEquals(id, again)
    assertTrue(store.filesByIds(listOf(id)).getValue(id).markedSensitive)
    val learned = store.takeLearnedRules()!!
    assertTrue(PrivacyRules.pathKey("external_primary", "DCIM/Camera/", "scan_renamed.jpg") in learned.markedFiles)
    assertFalse("the old name is dropped", PrivacyRules.pathKey("external_primary", "DCIM/Camera/", "scan.jpg") in learned.markedFiles)
    // After a wipe and new ids, the new name is what matches.
    deleteEverything()
    store.applyRules(learned)
    val back = crawlAgain("scan_renamed.jpg", 77)
    assertTrue(store.filesByIds(listOf(back)).getValue(back).markedSensitive)
  }

  @Test fun unmarkingRemovesBothKeysSoNothingResurrectsAfterAWipe() {
    val id = add("was_private.jpg")
    store.setMarked(id, true)
    val rules = store.setMarked(id, false)!!
    deleteEverything()
    store.applyRules(rules)
    val back = crawlAgain("was_private.jpg", 60)
    assertFalse(store.filesByIds(listOf(back)).getValue(back).sensitive)
  }

  @Test fun applyRulesOnlyWritesWhatChangedAndIsRepeatable() {
    add("a.jpg", path = "Secret/")
    add("b.jpg", path = "Other/")
    val rules = PrivacyRules(privateFolders = setOf("Secret"))
    assertEquals(1, store.applyRules(rules))
    assertEquals(0, store.applyRules(rules))
    assertEquals(1, store.applyRules(PrivacyRules()))   // removing the folder un-hides it again
    assertEquals(0, store.sensitiveCount())
  }

  @Test fun rulesMatchFoldersCaselessAndTolerateSlashes() {
    val r = PrivacyRules(privateFolders = setOf("/dcim/PRIVATE/"))
    assertEquals(2, r.folderFlag("DCIM/Private/sub"))
    assertEquals(2, r.folderFlag("dcim/private"))
    assertEquals(0, r.folderFlag("DCIM/Privately"))
    assertEquals(0, PrivacyRules.NONE.flagsFor("v", 1, "anything", "x.jpg"))
  }
}
