package trawl.desktop

import com.craneai.findr.core.FormatNotReadable
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.PendingFile
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Reads one video or recording the way the phone's IndexWorker.understand does. A long file is read a window (one minute) at a time:
 * each window is saved when it is done, the file is PARTIAL with `next=N` in its detail between windows, and a restart goes on from the
 * first window not saved. Failures end the file, not the queue: a format the computer cannot open is FAILED with [IndexStore.CANT_OPEN],
 * a step that runs past [timeoutMs] is FAILED "took too long", and anything else is FAILED with its message.
 */
class MediaIndexer(
  private val store: IndexStore,
  private val vectors: VectorIndex,
  private val decoder: MediaDecoder,
  private val processor: MediaProcessor,
  private val log: (String) -> Unit = {},
  private val timeoutMs: Long = 10 * 60_000L,
) {
  /**
   * Reads [p] from the window it stands at, one step after another. After each step [onStep] hears how long it took and says whether to go
   * on (false: the person paused, or other files are owed their turn; the file stays PARTIAL and is picked up again). Never throws for a
   * bad file; a [CancellationException] means the read was stopped and the file stays as it was.
   */
  fun read(p: PendingFile, onStep: (ms: Long) -> Boolean) {
    val file0 = p.file
    val path: File = try { Paths.get(URI(file0.uri)).toFile() } catch (e: Exception) { return fail(file0.id, e) }
    var file = file0
    val info = try { decoder.probe(path) } catch (e: FormatNotReadable) { return cantOpen(file, e.why) } catch (e: Exception) { return fail(file.id, e) }
    if (file.durationMs == null && info.durationMs != null) {
      // The crawl does not know a length; the phone asks MediaStore. The length decides how the file is cut into windows.
      store.write { db -> db.run("UPDATE files SET duration_ms = ? WHERE id = ?", info.durationMs, file.id) }
      file = file.copy(durationMs = info.durationMs)
    }
    val windows = MediaSchedule.windowCount(file.durationMs)
    val windowed = windows > 1
    var from = MediaSchedule.nextWindow(p.detail)
    val carry = p.detail?.let { SKIPPED.find(it)?.value }
    while (true) {
      val window = if (windowed) from else null
      if (window != null && window >= windows) { store.setState(file.id, FileState.UNDERSTOOD, listOfNotNull("done", carry).joinToString(" ")); return }
      val began = System.nanoTime()
      store.markInflight(file.id, from, windowed)   // a process death inside this step is counted at the next start (kill-loop breaker)
      val step: MediaStep
      try {
        step = withTimeout { processor.step(file, path, info, window, windows) }
      } catch (e: CancellationException) {
        throw e   // stopped: the file stays as it was and is picked up next time
      } catch (e: TimeoutException) {
        store.setState(file.id, FileState.FAILED, "took too long")
        return
      } catch (e: FormatNotReadable) {
        return cantOpen(file, e.why)
      } catch (e: InterruptedException) {
        throw e
      } catch (t: Throwable) {
        return fail(file.id, t)
      } finally {
        runCatching { store.clearInflight() }
      }
      val state = if (step.next != null) FileState.PARTIAL else FileState.UNDERSTOOD
      val detail = listOfNotNull(step.detail, step.next?.let { "next=$it" }, carry).joinToString(" ").ifEmpty { null }
      val ids = store.saveSegments(file.id, step.segments, state, detail, replace = from == 0)
      if (from == 0) vectors.removeFiles(listOf(file.id))   // a read again replaces the old segments; their vectors go with them
      step.segments.forEachIndexed { i, s -> s.vector?.let { vectors.add(ids[i], file.id, file.kind, s.modality, s.late, it) } }
      val ms = (System.nanoTime() - began) / 1_000_000
      log("read ${file.kind.name.lowercase()} step $from/${if (windowed) windows else 1} in $ms ms: ${step.segments.size} pieces${detail?.let { ", $it" } ?: ""}")
      val goOn = onStep(ms)
      val next = step.next ?: return
      from = next
      if (!goOn) return
    }
  }

  private fun cantOpen(file: com.craneai.findr.core.IndexedFile, why: String?) {
    log("can't open a ${file.kind.name.lowercase()} .${file.name.substringAfterLast('.', "none").take(8)}")   // [why] can name the file, so it stays out of the log
    store.setState(file.id, FileState.FAILED, IndexStore.CANT_OPEN)
  }

  private fun fail(id: Long, t: Throwable) {
    log("failed a media file: ${t.javaClass.simpleName} ${t.message}")
    store.setState(id, FileState.FAILED, t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
  }

  /** Runs [block] on a helper thread and waits at most [timeoutMs]; a step that runs over is interrupted and abandoned. */
  private fun <T> withTimeout(block: () -> T): T {
    val future = POOL.submit(Callable { block() })
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
      throw e.cause ?: e
    } catch (e: TimeoutException) {
      future.cancel(true)
      throw e
    } catch (e: InterruptedException) {
      future.cancel(true)
      throw e
    }
  }

  companion object {
    private val SKIPPED = Regex("skipped=\\d+")
    private val POOL = Executors.newCachedThreadPool { r -> Thread(r, "trawl-media-step").apply { isDaemon = true } }
  }
}
