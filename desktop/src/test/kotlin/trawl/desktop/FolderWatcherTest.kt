package trawl.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderWatcherTest {
  private fun tmp() = Files.createTempDirectory("trawl-watch").toFile().canonicalFile

  private fun waitFor(ms: Long = 15_000, ok: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return true; Thread.sleep(100) }
    return ok()
  }

  private fun watcher(got: MutableList<Dirty>, os: Os = Platform.os, maxWatches: () -> Long? = { FolderWatcher.inotifyLimit() }, pollSeconds: Int = 30) =
    FolderWatcher(os = os, maxWatches = maxWatches, pollSeconds = pollSeconds, debounceMs = 300, maxWaitMs = 3000, onDirty = { got.addAll(it) })

  private fun covers(got: List<Dirty>, dir: File) = got.any { d -> dir.toPath().startsWith(d.dir) && (d.recursive || d.dir == dir.toPath()) }

  @Test fun reportsANewChangedAndDeletedFile() {
    val root = tmp(); val sub = File(root, "a").apply { mkdirs() }
    val got = CopyOnWriteArrayList<Dirty>()
    watcher(got).use { w ->
      w.watch(listOf(root))
      assertTrue(w.info.modes.values.none { it == WatchMode.POLLING }, "expected a system watch, got ${w.info}")
      val f = File(sub, "new.txt").apply { writeText("hello") }
      assertTrue(waitFor { covers(got, sub) }, "no report for a new file: $got")
      got.clear(); Thread.sleep(500)
      f.appendText(" more")
      assertTrue(waitFor { covers(got, sub) }, "no report for a changed file: $got")
      got.clear(); Thread.sleep(500)
      f.delete()
      assertTrue(waitFor { covers(got, sub) }, "no report for a deleted file: $got")
    }
    root.deleteRecursively()
  }

  @Test fun picksUpFoldersCreatedLater() {
    val root = tmp()
    val got = CopyOnWriteArrayList<Dirty>()
    watcher(got).use { w ->
      w.watch(listOf(root))
      val deep = File(root, "x/y").apply { mkdirs() }
      Thread.sleep(1500)
      got.clear()
      File(deep, "late.txt").writeText("late")
      assertTrue(waitFor { covers(got, deep) }, "no report from a folder made after watching began: $got")
    }
    root.deleteRecursively()
  }

  @Test fun fallsBackToPollingWhenTheLimitIsTooLow() {
    val root = tmp(); File(root, "a/b/c").mkdirs(); File(root, "d").mkdirs()
    val got = CopyOnWriteArrayList<Dirty>()
    watcher(got, os = Os.LINUX, maxWatches = { 1L }, pollSeconds = 1).use { w ->
      w.watch(listOf(root))
      assertEquals(WatchMode.POLLING, w.info.modes[root.toString()])
      assertTrue(w.info.allPolling)
      assertTrue(waitFor { got.any { it.dir == root.toPath() && it.recursive } }, "polling reported nothing: $got")
    }
    root.deleteRecursively()
  }

  @Test fun aPolledFolderThatIsDearToWalkIsPolledLessOften() {
    val root = tmp(); File(root, "a/b/c").mkdirs()
    val got = CopyOnWriteArrayList<Dirty>()
    watcher(got, os = Os.LINUX, maxWatches = { 1L }, pollSeconds = 1).use { w ->
      w.watch(listOf(root))
      assertTrue(waitFor { got.isNotEmpty() }, "polling reported nothing: $got")
      w.walked(root.toPath(), tookMs = 60_000)   // a minute-long walk: the folder is left alone for a long time
      Thread.sleep(1_500)                         // (a report that was already on its way still arrives)
      got.clear()
      Thread.sleep(2_600)
      assertTrue(got.isEmpty(), "reported again at once after a dear walk: $got")
      w.walked(root.toPath(), tookMs = 0)        // a free walk: polled at the usual rhythm again
      assertTrue(waitFor { got.isNotEmpty() }, "polling did not resume after a cheap walk")
    }
    root.deleteRecursively()
  }

  @Test fun pollPacingWaitsInProportionToTheWalkAndNeverTooLong() {
    assertEquals(1_000L + 144_000L, PollPacing.notBefore(1_000L, 2_400L, factor = 60, maxMs = 300_000))
    assertEquals(1_000L + 300_000L, PollPacing.notBefore(1_000L, 20_000L, factor = 60, maxMs = 300_000))
    assertEquals(0L, PollPacing.notBefore(1_000L, 2_400L, factor = 0, maxMs = 300_000))   // off: no extra wait
    assertEquals(1_000L, PollPacing.notBefore(1_000L, 0L, factor = 60, maxMs = 300_000))   // a free walk waits for nothing
  }

  @Test fun aMissingFolderIsPolledUntilItReturns() {
    val root = File(tmp(), "gone")
    watcher(CopyOnWriteArrayList()).use { w ->
      w.watch(listOf(root))
      assertEquals(WatchMode.POLLING, w.info.modes[root.absoluteFile.toPath().normalize().toString()])
    }
  }

  @Test fun readsTheInotifyLimit() {
    val f = File.createTempFile("max_user_watches", ""); f.writeText("65536\n")
    assertEquals(65536L, FolderWatcher.inotifyLimit(f))
    f.writeText("lots"); assertNull(FolderWatcher.inotifyLimit(f))
    assertNull(FolderWatcher.inotifyLimit(File("/definitely/not/here")))
    f.delete()
  }

  @Test fun idleWatcherDoesZeroFixedDelayWakeups() {
    val root = tmp()
    val got = CopyOnWriteArrayList<Dirty>()
    watcher(got).use { w ->
      w.watch(listOf(root))
      assertTrue(w.info.modes.values.none { it == WatchMode.POLLING }, "expected a system watch, got ${w.info}")
      Thread.sleep(1_200)
      assertEquals(0, w.tickCount.get(), "an idle watcher must wake at 0 Hz (0 flush ticks when no events arrived)")
      assertTrue(got.isEmpty())
    }
    root.deleteRecursively()
  }

  @Test fun deleteLastGraceHoldsDeleteAndAtomicSaveCoalescesIntoModify() {
    val root = tmp()
    val dirA = File(root, "a").apply { mkdirs() }.toPath()
    val dirB = File(root, "b").apply { mkdirs() }.toPath()
    val docA = dirA.resolve("notes.md")
    val docB = dirB.resolve("other.md")
    val got = ArrayList<Dirty>()
    FolderWatcher(debounceMs = 200, deleteGraceMs = 500, maxWaitMs = 3_000, onDirty = { got.addAll(it) }).use { w ->
      // 1. Pure DELETE holds dirA for deleteGraceMs (500ms) while CREATE in dirB flushes on normal debounceMs (200ms).
      w.recordEvent(Dirty(dirA, false), docA, WatchEventKind.DELETE, now = 1_000L)
      w.recordEvent(Dirty(dirB, false), docB, WatchEventKind.CREATE_OR_MODIFY, now = 1_000L)
      assertTrue(w.hasPendingDelete(docA))
      assertEquals(1_200L, w.nextFlushAt())

      w.tick(now = 1_200L)
      assertEquals(listOf(Dirty(dirB, false)), got, "dirB flushes at debounceMs while dirA waits out deleteGraceMs")
      assertTrue(w.hasPendingDelete(docA))
      assertEquals(1_500L, w.nextFlushAt())

      // 2. Atomic save (tmp -> rename): CREATE_OR_MODIFY arrives on the exact same path before 1500ms.
      got.clear()
      w.recordEvent(Dirty(dirA, false), docA, WatchEventKind.CREATE_OR_MODIFY, now = 1_250L)
      assertTrue(!w.hasPendingDelete(docA), "matching CREATE/MODIFY must cancel pending delete hold")
      assertEquals(1_450L, w.nextFlushAt(), "coalesced modify flushes on normal debounce (1250 + 200 = 1450) instead of delete grace")

      w.tick(now = 1_450L)
      assertEquals(listOf(Dirty(dirA, false)), got, "single coalesced dirty report once replacement file is present")
      assertNull(w.nextFlushAt())

      // 3. Unmatched DELETE eventually flushes after deleteGraceMs expires.
      got.clear()
      w.recordEvent(Dirty(dirA, false), docA, WatchEventKind.DELETE, now = 2_000L)
      w.tick(now = 2_250L)
      assertTrue(got.isEmpty(), "real delete stays held during grace window")
      w.tick(now = 2_500L)
      assertEquals(listOf(Dirty(dirA, false)), got, "real delete flushes once grace window expires")
    }
    root.deleteRecursively()
  }

  @Test fun staleFlushSeqTickIsIgnoredAndReconfigurationDoesNotStrandEvents() {
    val rootA = tmp()
    val rootB = tmp()
    val got = CopyOnWriteArrayList<Dirty>()
    FolderWatcher(debounceMs = 200, deleteGraceMs = 500, maxWaitMs = 3_000, onDirty = { got.addAll(it) }).use { w ->
      w.watch(listOf(rootA))
      val dirA = rootA.toPath()
      w.recordEvent(Dirty(dirA, false), dirA.resolve("a.txt"), WatchEventKind.CREATE_OR_MODIFY, now = 1_000L)
      val seq1 = w.currentFlushSeq()
      // Reconfigure to rootB: increments flushSeq, clears rootA pending state, and starts a fresh scheduler.
      w.watch(listOf(rootB))
      assertTrue(w.currentFlushSeq() > seq1, "reconfiguration must advance flushSeq")
      val ticksBefore = w.tickCount.get()

      // A late/stale tick carrying seq1 must be completely ignored without touching tickCount or pending state.
      w.recordEvent(Dirty(rootB.toPath(), false), rootB.toPath().resolve("b.txt"), WatchEventKind.CREATE_OR_MODIFY, now = 1_000L)
      w.tick(now = 5_000L, expectedSeq = seq1)
      assertEquals(ticksBefore, w.tickCount.get(), "stale expectedSeq tick must not increment tickCount")
      assertTrue(got.isEmpty(), "stale expectedSeq tick must not flush newer pending events")

      // Real file event under rootB after reconfiguration still flushes reliably (no stranded events).
      File(rootB, "live.txt").writeText("after-reconfig")
      assertTrue(waitFor { covers(got, rootB) }, "events after watch() reconfiguration must flush reliably: $got")
    }
    rootA.deleteRecursively()
    rootB.deleteRecursively()
  }
}
