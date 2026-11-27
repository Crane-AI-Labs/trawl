package trawl.desktop

import com.craneai.findr.core.FileKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LargeFolderWatchTest {
  private fun found(root: Path, relPath: String, kind: FileKind = FileKind.DOCUMENT, size: Long = 1024L): Found {
    val p = root.resolve(relPath).toAbsolutePath().normalize()
    val rel = Crawler.relOf(root, p)
    return Found(p, rel, p.fileName.toString(), kind, size, 1_000L)
  }

  @Test fun triggersOnceWhenSubfolderCrossesFileCountThreshold() {
    val root = Files.createTempDirectory("trawl-lf-count")
    try {
      val det = LargeFolderDetector(fileThreshold = 5, docBytesThreshold = 500L * 1024 * 1024)
      var alert: LargeFolderAlert? = null
      for (i in 1..4) {
        assertNull(det.observe(root, found(root, "dump/f$i.json", FileKind.DOCUMENT, 100L)))
      }
      alert = det.observe(root, found(root, "dump/f5.json", FileKind.DOCUMENT, 100L))
      assertNotNull(alert)
      assertEquals("dump", alert.name)
      assertEquals(5, alert.fileCount)
      assertEquals(500L, alert.totalBytes)
      // Further files in the same subfolder never re-alert
      assertNull(det.observe(root, found(root, "dump/f6.json", FileKind.DOCUMENT, 100L)))
      assertTrue(det.finalizeSubtrees(root).isEmpty())
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test fun triggersOnDocumentBytesThresholdButNotOnLargeVideos() {
    val root = Files.createTempDirectory("trawl-lf-bytes")
    try {
      val det = LargeFolderDetector(fileThreshold = 2_000, docBytesThreshold = 10_000L)
      // Two 100 MB videos should NOT trigger the document/JSON byte threshold
      assertNull(det.observe(root, found(root, "movies/clip1.mp4", FileKind.VIDEO, 100_000_000L)))
      assertNull(det.observe(root, found(root, "movies/clip2.mp4", FileKind.VIDEO, 100_000_000L)))
      assertTrue(det.finalizeSubtrees(root).isEmpty())

      // Large JSON dump crosses 10 KB doc threshold immediately
      assertNull(det.observe(root, found(root, "json-dump/part1.json", FileKind.DOCUMENT, 6_000L)))
      val alert = det.observe(root, found(root, "json-dump/part2.json", FileKind.DOCUMENT, 5_000L))
      assertNotNull(alert)
      assertEquals("json-dump", alert.name)
      assertEquals(2, alert.fileCount)
      assertEquals(11_000L, alert.totalBytes)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test fun neverAlertsOnChosenRootItselfAndRollsUpSplitSubtreesToDeepestFolder() {
    val root = Files.createTempDirectory("trawl-lf-rollup")
    try {
      val det = LargeFolderDetector(fileThreshold = 4, docBytesThreshold = 500L * 1024 * 1024)
      // Files directly inside root never trigger (excluding a chosen root uses Remove in Settings)
      for (i in 1..6) assertNull(det.observe(root, found(root, "root$i.txt", FileKind.DOCUMENT, 100L)))
      // Split dump across sub-shards under datasets/huge-dump/shard1 & shard2
      for (i in 1..2) assertNull(det.observe(root, found(root, "datasets/huge-dump/shard1/a$i.json", FileKind.DOCUMENT, 100L)))
      for (i in 1..2) assertNull(det.observe(root, found(root, "datasets/huge-dump/shard2/b$i.json", FileKind.DOCUMENT, 100L)))
      val rolled = det.finalizeSubtrees(root)
      assertEquals(1, rolled.size)
      // Must suggest deepest offending folder (`huge-dump`), not ancestor `datasets`
      assertEquals("huge-dump", rolled.first().name)
      assertEquals(4, rolled.first().fileCount)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test fun controllerExcludesOrDismissesLargeFolderAndPersistsNeverNaggingTwice() {
    val dataDir = Files.createTempDirectory("trawl-lf-ctrl-data").toFile()
    val chosen = Files.createTempDirectory("trawl-lf-ctrl-root").toFile()
    val bigA = File(chosen, "massive-json").apply { mkdirs() }
    val bigB = File(chosen, "keep-logs").apply { mkdirs() }
    val notified = mutableListOf<Pair<String, String>>()
    val c = AppController(dataDir, logSink = {})
    try {
      c.notifyLargeFolder = { title, body -> notified += title to body }
      c.addFolder(chosen)

      val alertA = LargeFolderAlert(bigA.absolutePath, bigA.name, 2_500, 600L * 1024 * 1024)
      val alertB = LargeFolderAlert(bigB.absolutePath, bigB.name, 2_100, 520L * 1024 * 1024)

      c.onLargeFolderDetected(alertA)
      c.onLargeFolderDetected(alertA) // duplicate in same run must not re-notify or re-queue
      c.onLargeFolderDetected(alertB)
      assertEquals(2, notified.size)
      assertEquals(alertA.name, c.largeFolderAlert.value?.name)

      // One-click Exclude adds bigA to excludedFolders & dismissedLargeFolders and advances banner to bigB
      c.excludeLargeFolder(c.largeFolderAlert.value!!)
      assertTrue(c.settings.value.excludedFolders.any { SafePath.of(it) == SafePath.of(bigA) })
      assertTrue(c.settings.value.dismissedLargeFolders.any { SafePath.of(it) == SafePath.of(bigA) })
      assertEquals(alertB.name, c.largeFolderAlert.value?.name)

      // One-click Keep reading persists bigB in dismissedLargeFolders without excluding it
      c.keepReadingLargeFolder(c.largeFolderAlert.value!!)
      assertTrue(c.settings.value.excludedFolders.none { SafePath.of(it) == SafePath.of(bigB) })
      assertTrue(c.settings.value.dismissedLargeFolders.any { SafePath.of(it) == SafePath.of(bigB) })
      assertNull(c.largeFolderAlert.value)

      // Persisted across restarts: fresh SettingsStore retains dismissedLargeFolders
      val reloaded = SettingsStore(dataDir).load()
      assertEquals(2, reloaded.dismissedLargeFolders.size)
    } finally {
      c.close()
      dataDir.deleteRecursively()
      chosen.deleteRecursively()
    }
  }

  @Test fun removeFromIndexNeverDeletesDiskFilePersistsExclusionAndSkipsFutureScans() {
    val dataDir = Files.createTempDirectory("trawl-rfi-data").toFile()
    val chosen = Files.createTempDirectory("trawl-rfi-root").toFile()
    val f1 = File(chosen, "secret-notes.txt").apply { writeText("quarterly revenue report alpha") }
    val f2 = File(chosen, "keep-me.txt").apply { writeText("quarterly revenue report beta") }
    val c = AppController(dataDir, logSink = {})
    try {
      c.addFolder(chosen)
      val waitScan = System.currentTimeMillis() + 8_000
      while (c.service.countUnder(chosen) < 2 && System.currentTimeMillis() < waitScan) Thread.sleep(40)
      assertEquals(2, c.service.countUnder(chosen))

      val indexed = c.service.recent(com.craneai.findr.core.KindFilter.ALL, limit = 10)
      val target = indexed.first { it.name == f1.name }

      c.removeFromIndex(listOf(target))
      // 1. NEVER delete the user's file on disk!
      assertTrue(f1.isFile && f1.readText() == "quarterly revenue report alpha", "Remove from index must NEVER touch the file on disk")
      // 2. Persisted in Settings.excludedFiles
      val reloaded = SettingsStore(dataDir).load()
      assertTrue(reloaded.excludedFiles.any { SafePath.of(it) == SafePath.of(f1) })
      // 3. Dropped from the index while sibling f2 remains
      val waitDrop = System.currentTimeMillis() + 5_000
      while (c.service.countUnder(chosen) != 1 && System.currentTimeMillis() < waitDrop) Thread.sleep(30)
      assertEquals(1, c.service.countUnder(chosen))
      // 4. Crawler.walk skips excluded file on rescans
      val seen = mutableListOf<String>()
      Crawler.walk(chosen.toPath(), chosen.toPath(), recursive = true, skippedFiles = Scope.paths(reloaded.excludedFiles).toSet()) { seen += it.name }
      assertEquals(listOf(f2.name), seen)
      // 5. Reversible via Settings ("Include again")
      c.restoreExcludedFile(f1.absolutePath)
      assertTrue(SettingsStore(dataDir).load().excludedFiles.isEmpty())
    } finally {
      c.close()
      dataDir.deleteRecursively()
      chosen.deleteRecursively()
    }
  }

  @Test fun removeFromIndexPreservesSiblingRowFtsTokensAndVectorIndexIntact() {
    val dir = Files.createTempDirectory("trawl-rfi-integrity").toFile()
    val store = com.craneai.findr.index.IndexStore.open(File(dir, "index"), log = {})
    val vectors = com.craneai.findr.search.VectorIndex(parallelism = 1, backing = File(dir, "vectors.bin"))
    try {
      val records = buildList {
        add(com.craneai.findr.index.CrawlRecord("desktop", 101L, "file:///tmp/remove-me.txt", "", "remove-me.txt", FileKind.DOCUMENT, "text/plain", 120L, 1_000L))
        add(com.craneai.findr.index.CrawlRecord("desktop", 202L, "file:///tmp/keep-sibling.txt", "", "keep-sibling.txt", FileKind.DOCUMENT, "text/plain", 240L, 2_000L))
        for (k in 0 until 8) {
          add(com.craneai.findr.index.CrawlRecord("desktop", 300L + k, "file:///tmp/bg-$k.txt", "", "bg-$k.txt", FileKind.DOCUMENT, "text/plain", 300L + k, 3_000L + k))
        }
      }
      store.upsertFromCrawl(records)
      val idMap = store.knownMediaStoreIds("desktop")
      val idA = idMap.getValue(101L)
      val idB = idMap.getValue(202L)
      val dim = com.craneai.findr.index.Fp16.DIM
      val vecA = FloatArray(dim) { if (it == 0) 1f else 0f }
      val vecB = FloatArray(dim) { if (it == 1) 1f else 0f }
      val segA = com.craneai.findr.index.Segment(com.craneai.findr.index.SegmentKind.CHUNK, com.craneai.findr.index.Modality.TEXT, text = "zxqalpha unique token alpha", vector = vecA)
      val segB = com.craneai.findr.index.Segment(com.craneai.findr.index.SegmentKind.CHUNK, com.craneai.findr.index.Modality.TEXT, text = "zxqbeta unique token beta", vector = vecB)
      val segIdsA = store.saveSegments(idA, listOf(segA), com.craneai.findr.index.FileState.UNDERSTOOD)
      val segIdsB = store.saveSegments(idB, listOf(segB), com.craneai.findr.index.FileState.UNDERSTOOD)
      vectors.add(segIdsA[0], idA, FileKind.DOCUMENT, segA.modality, segA.late, vecA)
      vectors.add(segIdsB[0], idB, FileKind.DOCUMENT, segB.modality, segB.late, vecB)
      for (k in 0 until 8) {
        val idBg = idMap.getValue(300L + k)
        val vecBg = FloatArray(dim) { if (it == k + 2) 1f else 0f }
        val segBg = com.craneai.findr.index.Segment(com.craneai.findr.index.SegmentKind.CHUNK, com.craneai.findr.index.Modality.TEXT, text = "background corpus item $k", vector = vecBg)
        val sids = store.saveSegments(idBg, listOf(segBg), com.craneai.findr.index.FileState.UNDERSTOOD)
        vectors.add(sids[0], idBg, FileKind.DOCUMENT, segBg.modality, segBg.late, vecBg)
      }
      val searcher = com.craneai.findr.search.Searcher(store, vectors)

      // Pre-check: both target and sibling searchable by FTS5 and VectorIndex
      assertEquals(listOf(idA), searcher.search("zxqalpha", null, com.craneai.findr.core.KindFilter.ALL, 10).hits.map { it.file.id })
      assertEquals(listOf(idB), searcher.search("zxqbeta", null, com.craneai.findr.core.KindFilter.ALL, 10).hits.map { it.file.id })
      assertEquals(10, vectors.size)

      // Remove ONLY idA (exact same VectorIndex + IndexStore path as IndexService.removeFromIndex -> drop(ids))
      vectors.removeFiles(listOf(idA))
      store.markDeleted(listOf(idA))

      // idA is completely gone from SQLite, FTS5, and VectorIndex
      assertTrue(searcher.search("zxqalpha", null, com.craneai.findr.core.KindFilter.ALL, 10).hits.isEmpty())
      assertEquals(9, vectors.size)

      // Sibling idB's SQLite row, FTS5 tokens, and VectorIndex vector remain 100% intact and searchable
      val recentIds = store.recent(com.craneai.findr.core.KindFilter.ALL.kinds, 20).map { it.id }.toSet()
      assertTrue(idA !in recentIds && idB in recentIds && recentIds.size == 9)
      val ftsHit = searcher.search("zxqbeta", null, com.craneai.findr.core.KindFilter.ALL, 10)
      assertEquals(listOf(idB), ftsHit.hits.map { it.file.id })
      assertTrue(vectors.rowsOfFiles(longArrayOf(idA)).isEmpty(), "removed file must have zero rows in VectorIndex")
      val rowsB = vectors.rowsOfFiles(longArrayOf(idB))
      assertEquals(1, rowsB.size, "sibling file must keep its VectorIndex row intact")
      val scoreB = vectors.scoresAt(vecB, rowsB)[0]
      assertTrue(scoreB > 0.99f, "sibling vector score must remain intact (got $scoreB)")
      val vecHit = searcher.search("semantic meaning query", vecB, com.craneai.findr.core.KindFilter.ALL, 10)
      assertEquals(idB, vecHit.hits.firstOrNull()?.file?.id, "semantic VectorIndex search must still rank sibling idB #1")
    } finally {
      vectors.close()
      store.close()
      dir.deleteRecursively()
    }
  }
}
