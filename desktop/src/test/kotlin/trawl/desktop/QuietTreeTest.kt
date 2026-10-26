package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tree stays quiet: the watcher does not report or register dependency folders, a paused index does no background walking and
 * walks once on resume, a burst of reports is one walk, and the rows an older build made for TypeScript files are cleaned up.
 */
class QuietTreeTest {
  private fun tmp() = Files.createTempDirectory("trawl-quiet").toFile().canonicalFile
  private fun file(root: File, rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
  private val docsOnly = setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.IMAGE, FileKind.SCREENSHOT)

  private fun waitFor(what: String, ms: Long = 30_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun IndexService.known() = ui.value.byKind[FileKind.DOCUMENT] ?: 0
  private fun IndexService.idle() = ui.value.phase != Phase.SCANNING && ui.value.phase != Phase.NOT_STARTED && !ui.value.opening

  private fun IndexService.start(r: File, paused: Boolean = false): IndexService {
    configure(docsOnly, emptyList(), LearningSpeed.BALANCED, paused = paused)
    setFolders(listOf(r))
    return this
  }

  private fun service(data: File) = IndexService(data, decoderChoice = { null })

  // ---- the watcher ------------------------------------------------------------------------------------------------

  @Test fun theWatcherNeitherRegistersNorReportsDependencyFolders() {
    val root = tmp()
    file(root, "proj/package.json", "{}"); file(root, "proj/src/a.txt")
    for (p in 0 until 20) file(root, "proj/node_modules/pkg$p/lib/f.ts")
    val got = CopyOnWriteArrayList<Dirty>()
    FolderWatcher(debounceMs = 300, maxWaitMs = 3000, onDirty = { got.addAll(it) }).use { w ->
      w.watch(listOf(root))
      if (w.info.modes.values.any { it == WatchMode.POLLING }) { root.deleteRecursively(); return }   // polling reports the whole root by design
      // Directories registered per folder (Linux): root, proj, proj/src, and none of the 41 under node_modules. FSEvents and Windows register none.
      assertTrue(w.info.watchedDirs <= 3, "watched ${w.info.watchedDirs}")
      Thread.sleep(1500)
      got.clear()
      file(root, "proj/node_modules/pkg3/lib/new.ts")                       // a change in a dependency folder: not news
      file(root, "proj/node_modules/fresh/lib/new.ts")                      // a new package folder: not news, and not registered either
      Thread.sleep(500)
      file(root, "proj/node_modules/fresh/lib/more.ts")
      file(root, "proj/src/real.txt", "a note")                            // a change next to it: news
      waitFor("the report for proj/src") { got.any { it.dir == File(root, "proj/src").toPath() || (it.recursive && File(root, "proj/src").toPath().startsWith(it.dir)) } }
      Thread.sleep(1500)
      assertTrue(got.none { it.dir.toString().contains("node_modules") }, "reported inside a dependency folder: $got")
    }
    root.deleteRecursively()
  }

  // ---- pause ------------------------------------------------------------------------------------------------------

  @Test fun pauseStopsTheBackgroundWalksAndResumeWalksOnce() {
    val r = tmp(); val data = tmp()
    file(r, "real/note.txt", "first note")
    service(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 1 && ix.idle() }
      ix.configure(docsOnly, emptyList(), LearningSpeed.BALANCED, paused = true)
      Thread.sleep(500)
      val walks = Crawler.Counters.walks.sum()
      for (i in 0 until 30) {
        file(r, "real/n$i.txt", "note $i")
        ix.reconcile(listOf(Dirty(File(r, "real").toPath(), false), Dirty(r.toPath(), true)))   // the watcher keeps reporting, even a whole root
      }
      Thread.sleep(2000)
      assertEquals(walks, Crawler.Counters.walks.sum(), "no walk while paused")
      assertEquals(1, ix.known(), "nothing was read while paused")

      ix.configure(docsOnly, emptyList(), LearningSpeed.BALANCED, paused = false)
      waitFor("the notes that came while paused") { ix.known() == 31 }
      // One walk of the root (it covers "real"), not one per report: the reports were merged while paused.
      waitFor("the walk to finish") { ix.idle() }
      val used = Crawler.Counters.walks.sum() - walks
      assertTrue(used in 1..3, "$used walks after resume")
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aPausedIndexStillAnswersAnExplicitRescan() {
    val r = tmp(); val data = tmp()
    file(r, "a.txt", "one")
    service(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 1 && ix.idle() }
      ix.configure(docsOnly, emptyList(), LearningSpeed.BALANCED, paused = true)
      file(r, "b.txt", "two")
      ix.rescan()   // the person asked for it: that is not background walking
      waitFor("the rescan") { ix.known() == 2 }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  // ---- debounce ---------------------------------------------------------------------------------------------------

  @Test fun fiveHundredReportsForOneFolderAreAFewWalksNotFiveHundred() {
    val r = tmp(); val data = tmp()
    file(r, "real/note.txt", "real note")
    service(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 1 && ix.idle() }
      val walks = Crawler.Counters.walks.sum()
      file(r, "real/second.txt", "second note")
      repeat(500) { ix.reconcile(listOf(Dirty(File(r, "real").toPath(), false))) }
      waitFor("the second note") { ix.known() == 2 }
      Thread.sleep(1000)
      val used = Crawler.Counters.walks.sum() - walks
      assertTrue(used in 1..10, "$used walks for 500 reports")
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun reportsInsideADependencyFolderStartNoWalk() {
    val r = tmp(); val data = tmp()
    file(r, "proj/package.json", "{}"); file(r, "proj/node_modules/x/readme.txt", "docs of a package"); file(r, "real/note.txt", "real note")
    service(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 2 && ix.idle() }   // package.json is a document too; node_modules/x/readme.txt is not read
      val walks = Crawler.Counters.walks.sum()
      file(r, "proj/node_modules/x/more.txt", "more docs")
      ix.reconcile(listOf(Dirty(File(r, "proj/node_modules/x").toPath(), false), Dirty(File(r, "proj/node_modules").toPath(), true)))
      Thread.sleep(1500)
      assertEquals(walks, Crawler.Counters.walks.sum())
      assertEquals(2, ix.known())
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theWalksAfterALongOneWaitInProportionButAShortOneIsFree() {
    assertEquals(0L, ReconcilePacing.notBefore(10_000, 0))
    assertEquals(0L, ReconcilePacing.notBefore(10_000, ReconcilePacing.FREE_MS), "a quick walk is not slowed")
    assertEquals(10_000L + 4_000, ReconcilePacing.notBefore(10_000, 1_000))
    assertEquals(10_000L + 60_000, ReconcilePacing.notBefore(10_000, 600_000), "never more than a minute")
    // At most one walk in five of the time: the worker spends at most 1/5 of its time on watcher-driven walks of a tree that never stops changing.
    val took = 3_000L; val gap = ReconcilePacing.notBefore(0, took)
    assertTrue(took.toDouble() / (took + gap) <= 0.2 + 1e-9)
  }

  // ---- the clean-up of rows an older build made --------------------------------------------------------------------

  private val realTsHead: ByteArray = Base64.getDecoder().decode(QuietTreeTest::class.java.getResourceAsStream("/transport/ffmpeg-ts-head.b64")!!.use { it.readBytes() }.toString(Charsets.US_ASCII).trim())

  private fun seed(store: IndexStore, f: File, kind: FileKind, state: FileState, detail: String?, n: Long): Long {
    store.upsertFromCrawl(listOf(CrawlRecord(Crawler.VOLUME, n, f.toPath().toUri().toString(), "x/", f.name, kind, null, f.length(), 1L)))
    val id = store.read { db -> db.long("SELECT id FROM files WHERE mediastore_id = ?", n) }!!
    store.setState(id, state, detail)
    return id
  }

  private fun rows(store: IndexStore): Set<String> = store.read { db -> val s = HashSet<String>(); db.query("SELECT name FROM files") { s += it.getText(0) }; s }

  private fun seedAll(store: IndexStore, files: File) {
    var n = 1L
    for (i in 0 until 40) seed(store, file(files, "node_modules/p$i/ts$i.ts", "export const a = $i;"), FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, n++)
    seed(store, file(files, "src/types.MTS", "export type A = 1"), FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, n++)
    seed(store, File(files, "camera.ts").apply { writeBytes(realTsHead) }, FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, n++)       // a real stream that failed: stays
    seed(store, file(files, "other.ts", "export {}"), FileKind.VIDEO, FileState.FAILED, "took too long", n++)                                    // failed for another reason: stays
    seed(store, file(files, "clip.mp4", "not a real mp4"), FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, n++)                          // not a .ts name: stays
    seed(store, file(files, "notes.txt", "a note"), FileKind.DOCUMENT, FileState.UNDERSTOOD, null, n++)
    seed(store, File(files, "vanished.ts"), FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, n++)                                          // not on disk any more: the folder's walk forgets it
  }

  private val kept = setOf("camera.ts", "other.ts", "clip.mp4", "notes.txt", "vanished.ts")

  @Test fun failedTypeScriptRowsAreRemovedOnceAndEverythingElseStays() {
    val dir = tmp(); val files = tmp()
    val store = IndexStore.open(dir)
    try {
      seedAll(store, files)
      assertEquals(46, rows(store).size, "the seed: 41 TypeScript rows and 5 that must stay")
      val log = ArrayList<String>()
      val gone = ArrayList<Long>()
      assertEquals(41, IndexAdmin.dropFalseTransportStreams(store, { log += it }, { gone += it }))
      assertEquals(41, gone.size)
      assertEquals(kept, rows(store))
      assertEquals(listOf("41 TypeScript files no longer counted as videos were removed from the index"), log)
      assertNotNull(store.meta(IndexAdmin.TRANSPORT_KEY))
      // Once per index: a second call reads nothing and removes nothing.
      seed(store, file(files, "later.ts", "export {}"), FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, 999L)
      assertEquals(0, IndexAdmin.dropFalseTransportStreams(store))
      assertTrue("later.ts" in rows(store))
    } finally { store.close(); dir.deleteRecursively(); files.deleteRecursively() }
  }

  /** The index a developer's Mac really had: about twenty thousand failed `.ts` rows. The clean-up must be a moment, not a crawl. */
  @Test fun twentyThousandFailedTypeScriptRowsGoQuickly() {
    val dir = tmp(); val files = tmp()
    val store = IndexStore.open(dir)
    try {
      val n = 20_000
      val records = ArrayList<CrawlRecord>(n)
      for (i in 0 until n) {
        val f = File(files, "node_modules/p${i % 400}/m$i.ts").apply { parentFile.mkdirs(); writeText("export const a = $i;\n") }
        records += CrawlRecord(Crawler.VOLUME, i + 1L, f.toPath().toUri().toString(), "node_modules/", f.name, FileKind.VIDEO, null, f.length(), 1L)
      }
      store.upsertFromCrawl(records)
      store.read { db -> val ids = ArrayList<Long>(); db.query("SELECT id FROM files") { ids += it.getLong(0) }; ids }.forEach { store.setState(it, FileState.FAILED, IndexStore.CANT_OPEN) }
      assertEquals(n.toLong(), store.read { db -> db.long("SELECT count(*) FROM files WHERE state = 'failed'") })
      val t0 = System.nanoTime()
      val dropped = IndexAdmin.dropFalseTransportStreams(store)
      val ms = (System.nanoTime() - t0) / 1_000_000
      println("TRAWL-SCALE: dropped $dropped of $n failed .ts rows in $ms ms")
      assertEquals(n, dropped)
      assertTrue(ms < 30_000, "took $ms ms")
    } finally { store.close(); dir.deleteRecursively(); files.deleteRecursively() }
  }

  /** The 1.0.0 index also holds the `.ts` rows that were never probed (state new): the first scan of the folder forgets them. */
  @Test fun theFirstScanForgetsUnprobedTypeScriptRowsInsideNodeModules() {
    val data = tmp(); val files = tmp()
    file(files, "proj/package.json", "{}"); file(files, "notes/a.txt", "a note about the harvest")
    IndexStore.open(File(data, "index")).also { st ->
      for (i in 0 until 60) seed(st, file(files, "proj/node_modules/p$i/m$i.ts", "export const a = $i;"), FileKind.VIDEO, FileState.NEW, null, 100L + i)
      st.close()
    }
    IndexService(data, decoderChoice = { null }).use { ix ->
      ix.configure(docsOnly + FileKind.VIDEO, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(files))
      waitFor("the scan") { ix.idle() && ix.known() >= 2 }
      waitFor("the old rows to go") { (ix.ui.value.byKind[FileKind.VIDEO] ?: 0) == 0 }
    }
    files.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aFailedCleanUpLeavesNoNoteSoItRunsAgainNextStart() {
    val dir = tmp(); val files = tmp()
    val store = IndexStore.open(dir)
    try {
      seed(store, file(files, "a.ts", "export {}"), FileKind.VIDEO, FileState.FAILED, IndexStore.CANT_OPEN, 1L)
      val log = ArrayList<String>()
      assertEquals(0, IndexAdmin.dropFalseTransportStreams(store, { log += it }, check = { throw IllegalStateException("boom") }))
      assertNull(store.meta(IndexAdmin.TRANSPORT_KEY))
      assertTrue(log.single().startsWith("transport-stream clean-up skipped"), "$log")
      assertEquals(1, IndexAdmin.dropFalseTransportStreams(store))
    } finally { store.close(); dir.deleteRecursively(); files.deleteRecursively() }
  }

  @Test fun theIndexServiceCleansUpAtStartWithNoWalkOfTheTree() {
    val data = tmp(); val files = tmp()
    // The index a 1.0.0 run left: written with the same store the service opens.
    IndexStore.open(File(data, "index")).also { seedAll(it, files); it.close() }
    val walks = Crawler.Counters.walks.sum()
    service(data).use { ix ->
      waitFor("the index to open and the clean-up to run") { !ix.ui.value.opening && ix.detail() != null }
      // 4 failed rows are left: the real stream, the other-reason failure, the mp4 and the vanished file. The 41 TypeScript ones are gone.
      assertEquals(4, ix.detail()!!.failedTotal)
      assertEquals(walks, Crawler.Counters.walks.sum(), "no walk of any tree was needed")
    }
    files.deleteRecursively(); data.deleteRecursively()
  }
}
