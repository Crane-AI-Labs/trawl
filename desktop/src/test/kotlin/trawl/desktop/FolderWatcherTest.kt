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
}
