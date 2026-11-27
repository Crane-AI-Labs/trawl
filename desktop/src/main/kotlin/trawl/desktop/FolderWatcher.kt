package trawl.desktop

import com.sun.nio.file.ExtendedWatchEventModifier
import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How one folder is being watched. */
enum class WatchMode {
  /** One registration covers the whole tree (Windows ReadDirectoryChangesW with FILE_TREE). */
  TREE,
  /** One registration per directory (Linux inotify), limited by `fs.inotify.max_user_watches`. */
  DIRS,
  /** No system watch: the folder is walked again every few seconds. Always correct, never instant. */
  POLLING,
}

/** A place that changed: look at [dir] again, all the way down when [recursive]. The watcher never says what changed, only where. */
data class Dirty(val dir: Path, val recursive: Boolean)

/** What the watcher is doing, for Settings. */
data class WatchInfo(
  val modes: Map<String, WatchMode> = emptyMap(),
  val watchedDirs: Int = 0,
  /** inotify's per-user limit on this machine (Linux), null elsewhere. */
  val inotifyLimit: Long? = null,
  val pollSeconds: Int = 0,
) {
  val anyPolling: Boolean get() = WatchMode.POLLING in modes.values
  val allPolling: Boolean get() = modes.isNotEmpty() && modes.values.all { it == WatchMode.POLLING }
}

/** The macOS seam: [MacFsEvents] (FSEvents through JNA). Where it cannot load, the JDK's own watcher runs, which polls every few seconds. */
interface FsEventsBackend {
  fun available(): Boolean
  /** Starts watching [root] and reports changed directories to [sink]; returns a handle that stops it. */
  fun watch(root: Path, sink: (Dirty) -> Unit): AutoCloseable
}

object NoFsEvents : FsEventsBackend {
  override fun available() = false
  override fun watch(root: Path, sink: (Dirty) -> Unit): AutoCloseable = error("FSEvents is not available")
}

/**
 * Watches the chosen folders and reports where files came, went or changed, after things go quiet (a copy of 300 photos is one
 * report, not 300). Per folder it uses the best thing the OS has and falls back to polling instead of failing:
 *
 *  - Windows: one WatchService registration with FILE_TREE per folder (ReadDirectoryChangesW underneath).
 *  - Linux: one registration per directory. The inotify limit is read first; a folder whose directories would use more than
 *    [budgetShare] of it is polled instead, and so is a folder the kernel refuses mid-way (ENOSPC "User limit of inotify watches
 *    reached"). New subdirectories are registered as they appear.
 *  - macOS: FSEvents ([MacFsEvents]) with one stream per folder, else the JDK's polling WatchService.
 *
 * The reports are only hints. The indexer reconciles the named directory against what it holds, so a missed or doubled event
 * costs a walk, never a wrong index. An OVERFLOW (the kernel dropped events) reports the whole folder.
 */
/** Internal event kind for delete-last grace and atomic-save coalescing. */
internal enum class WatchEventKind { CREATE_OR_MODIFY, DELETE }

class FolderWatcher(
  private val os: Os = Platform.os,
  private val maxWatches: () -> Long? = { inotifyLimit() },
  private val pollSeconds: Int = BackgroundTuning.pollSeconds,
  private val debounceMs: Long = BackgroundTuning.debounceMs,
  private val maxWaitMs: Long = BackgroundTuning.maxWaitMs,
  private val deleteGraceMs: Long = BackgroundTuning.deleteGraceMs,
  private val budgetShare: Double = 0.5,
  private val fsEvents: FsEventsBackend = platformFsEvents(),
  private val log: (String) -> Unit = {},
  /** Folders the person skips: nothing under them is watched or reported. Read at each event, so a change applies at once. */
  private val ignore: (Path) -> Boolean = { false },
  private val onDirty: (List<Dirty>) -> Unit,
) : AutoCloseable {

  private class PendingEntry(
    val firstAt: Long,
    var lastAt: Long,
    /** Earliest time a directory with a pending delete may flush (0 when no delete is active). */
    var holdUntil: Long = 0L,
  )

  private val lock = Any()
  @Volatile private var service: WatchService? = null
  private val modes = ConcurrentHashMap<String, WatchMode>()
  private val keyDirs = ConcurrentHashMap<WatchKey, Path>()
  private val rootOfKey = ConcurrentHashMap<WatchKey, Path>()
  private val handles = ConcurrentHashMap<String, AutoCloseable>()
  private val pending = HashMap<Dirty, PendingEntry>()
  /** Exact file paths with a pending DELETE in the current window: a matching CREATE/MODIFY cancels the delete-last grace. */
  private val pendingDeletes = HashMap<Path, Dirty>()
  private var flushTask: ScheduledFuture<*>? = null
  private var scheduledAt = 0L
  private var flushSeq = 0L
  @Volatile private var closed = false
  @Volatile private var scheduler: ScheduledExecutorService? = null
  private var pump: Thread? = null
  private val limit: Long? = if (os == Os.LINUX) maxWatches() else null

  /** Number of flush ticks executed (0 when completely idle — verified by tests). */
  internal val tickCount = AtomicInteger(0)

  val info: WatchInfo get() = WatchInfo(modes.toMap().toSortedMap(), keyDirs.size, limit, pollSeconds)

  /** Replaces the watched folders. Safe to call again whenever the person adds or removes one. */
  fun watch(roots: List<File>) = synchronized(lock) {
    stopRoots()
    if (closed) return@synchronized
    if (roots.isEmpty()) return@synchronized
    val newSched = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trawl-watch-flush").apply { isDaemon = true } }
    synchronized(pending) {
      scheduler = newSched
      if (pending.isNotEmpty()) scheduleFlushLocked(System.currentTimeMillis())
    }
    for (f in roots) {
      val root = SafePath.of(f) ?: continue
      if (!Files.isDirectory(root)) { modes[root.toString()] = WatchMode.POLLING; continue }   // gone or unplugged: polling notices it coming back
      val mode = try { register(root) } catch (e: Exception) {
        log("watch $root: ${e.javaClass.simpleName} ${e.message}; polling instead"); WatchMode.POLLING
      }
      modes[root.toString()] = mode
    }
    // Zero-Hz idle: flush timers are scheduled strictly on demand when an event arrives, and the poll timer only runs if a folder actually polls.
    if (modes.values.any { it == WatchMode.POLLING }) ensurePollTaskLocked()
  }

  private var pollTask: ScheduledFuture<*>? = null

  private fun ensurePollTaskLocked() {
    if (closed || pollTask != null) return
    val sched = scheduler ?: return
    pollTask = sched.scheduleWithFixedDelay({ pollRoots() }, pollSeconds.toLong(), pollSeconds.toLong(), TimeUnit.SECONDS)
  }

  private fun ensureWatchService(): WatchService =
    service ?: FileSystems.getDefault().newWatchService().also { service = it; startPump(it) }

  private fun stopRoots() {
    keyDirs.keys.forEach { it.cancel() }
    keyDirs.clear(); rootOfKey.clear(); modes.clear(); pollNotBefore.clear()
    handles.values.forEach { runCatching { it.close() } }
    handles.clear()
    val oldSched: ScheduledExecutorService?
    synchronized(pending) {
      oldSched = scheduler
      scheduler = null
      flushSeq++
      flushTask?.cancel(false); flushTask = null; scheduledAt = 0L
      pending.clear(); pendingDeletes.clear()
    }
    pollTask?.cancel(false); pollTask = null
    oldSched?.shutdownNow()
  }

  private fun register(root: Path): WatchMode = when (os) {
    Os.WINDOWS -> {
      val ws = ensureWatchService()
      val key = root.register(ws, arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), ExtendedWatchEventModifier.FILE_TREE)
      keyDirs[key] = root; rootOfKey[key] = root
      WatchMode.TREE
    }
    Os.MAC -> {
      val stream = if (fsEvents.available()) runCatching { fsEvents.watch(root, ::report) }.onFailure { log("watch $root: FSEvents ${it.message}; polling") }.getOrNull() else null
      if (stream != null) {
        handles[root.toString()] = stream
        WatchMode.TREE
      } else {
        registerDirs(ensureWatchService(), root, cap = 2_000)   // the JDK polls on macOS: every directory is polled, so cap it like inotify
      }
    }
    Os.LINUX -> registerDirs(ensureWatchService(), root, cap = limit?.let { (it * budgetShare).toLong() } ?: 4_000L)
  }

  /** Registers [root] and every directory under it, unless that is more than [cap]: then the folder is polled and nothing stays registered. */
  private fun registerDirs(ws: WatchService, root: Path, cap: Long): WatchMode {
    val dirs = ArrayList<Path>()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (dir != root && (isHidden(dir) || DependencyFolders.isSkipped(dir) || ignore(dir))) return FileVisitResult.SKIP_SUBTREE
        dirs.add(dir)
        return if (dirs.size > cap) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
      }
      override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
    })
    if (dirs.size > cap) {
      log("watch $root: ${dirs.size}+ directories, over the budget of $cap inotify watches; polling")
      return WatchMode.POLLING
    }
    val mine = ArrayList<WatchKey>()
    try {
      for (d in dirs) {
        val key = d.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
        keyDirs[key] = d; rootOfKey[key] = root; mine.add(key)
      }
    } catch (e: IOException) {   // "User limit of inotify watches reached": give back what was taken and poll this folder
      mine.forEach { it.cancel(); keyDirs.remove(it); rootOfKey.remove(it) }
      log("watch $root: ${e.message}; polling")
      return WatchMode.POLLING
    }
    return WatchMode.DIRS
  }

  private fun isHidden(p: Path): Boolean = p.fileName?.toString()?.startsWith(".") == true

  private fun startPump(ws: WatchService) {
    pump = Thread({
      while (!closed) {
        // Blocking take() instead of fixed-delay poll(500ms): 0 Hz wakeups while idle; close() closes the WatchService and interrupts this thread.
        val key = try { ws.take() } catch (_: ClosedWatchServiceException) { return@Thread } catch (_: InterruptedException) { return@Thread }
        try {
          val dir = keyDirs[key]
          val root = rootOfKey[key]
          if (dir != null && root != null) {
            for (ev in key.pollEvents()) {
              if (ev.kind() == OVERFLOW) { report(Dirty(root, true)); continue }
              val rel = ev.context() as? Path ?: continue
              val full = dir.resolve(rel)
              if (rel.fileName?.toString()?.startsWith(".") == true) continue
              val isDir = ev.kind() == ENTRY_CREATE && Files.isDirectory(full, LinkOption.NOFOLLOW_LINKS)
              if (isDir) {
                if (os != Os.WINDOWS) newDirectory(ws, full, root)
                recordEvent(Dirty(full, true), full, WatchEventKind.CREATE_OR_MODIFY)
              } else {
                val kind = if (ev.kind() == ENTRY_DELETE) WatchEventKind.DELETE else WatchEventKind.CREATE_OR_MODIFY
                recordEvent(Dirty(full.parent ?: dir, false), full, kind)
              }
            }
          }
          if (!key.reset()) { keyDirs.remove(key); rootOfKey.remove(key) }
        } catch (_: ClosedWatchServiceException) {
          return@Thread
        } catch (t: Throwable) {
          log("watch pump: ${t.message}")
        }
      }
    }, "trawl-watch").apply { isDaemon = true; start() }
  }

  /** A directory appeared under a per-directory watch (Linux, macOS poller): watch it too, or fall back to polling for its folder. */
  private fun newDirectory(ws: WatchService, dir: Path, root: Path) {
    try {
      Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (isHidden(d) && d != dir || DependencyFolders.isSkipped(d) || ignore(d)) return FileVisitResult.SKIP_SUBTREE
          if (limit != null && keyDirs.size >= (limit * budgetShare).toLong()) throw IOException("over the watch budget")
          val key = d.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
          keyDirs[key] = d; rootOfKey[key] = root
          return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
      })
    } catch (e: IOException) {
      log("watch $root: ${e.message}; this folder is polled from now on")
      modes[root.toString()] = WatchMode.POLLING
      synchronized(lock) { ensurePollTaskLocked() }
    }
  }

  private fun report(d: Dirty) = recordEvent(d, path = null, kind = WatchEventKind.CREATE_OR_MODIFY)

  /**
   * Records a filesystem change for debounced flushing.
   *
   * Delete-last grace (Syncthing pattern): a `DELETE` holds its directory for [deleteGraceMs] so an atomic editor save
   * (`tmp -> rename`, which produces `DELETE` then `CREATE`/`MODIFY` on the same path) cancels the delete hold and flushes
   * as a single modify/upsert instead of walking while the file is momentarily missing and dropping its embeddings.
   */
  internal fun recordEvent(d: Dirty, path: Path?, kind: WatchEventKind, now: Long = System.currentTimeMillis()) {
    if (ignore(d.dir)) return   // inside a skipped folder: the index does not read it, so a change there is not news
    val under = modes.keys.map { File(it).toPath() }.firstOrNull { d.dir.startsWith(it) }
    if (under != null && Crawler.insideIgnored(under, d.dir)) return   // nor is a hidden folder (".git") or a dependency/build folder ("node_modules"): never read, and the busiest
    synchronized(pending) {
      val entry = pending.getOrPut(d) { PendingEntry(firstAt = now, lastAt = now) }
      entry.lastAt = now
      when (kind) {
        WatchEventKind.DELETE -> {
          if (path != null) pendingDeletes[path] = d
          if (deleteGraceMs > 0L) entry.holdUntil = maxOf(entry.holdUntil, now + deleteGraceMs)
        }
        WatchEventKind.CREATE_OR_MODIFY -> {
          if (path != null && pendingDeletes.remove(path) != null) {
            if (pendingDeletes.values.none { it == d }) entry.holdUntil = 0L
          }
        }
      }
      scheduleFlushLocked(now)
    }
  }

  private fun readyAt(e: PendingEntry): Long = minOf(maxOf(e.lastAt + debounceMs, e.holdUntil), e.firstAt + maxWaitMs)

  /** For tests: earliest timestamp when any pending directory becomes eligible to flush, or null when idle. */
  internal fun nextFlushAt(): Long? = synchronized(pending) {
    if (pending.isEmpty()) null else pending.values.minOf { readyAt(it) }
  }

  /** For tests: whether [path] currently has an active delete-last grace hold. */
  internal fun hasPendingDelete(path: Path): Boolean = synchronized(pending) { path in pendingDeletes }

  /** For tests: current flush sequence counter. */
  internal fun currentFlushSeq(): Long = synchronized(pending) { flushSeq }

  private fun scheduleFlushLocked(now: Long) {
    if (closed || pending.isEmpty()) return
    val earliest = pending.values.minOf { readyAt(it) }
    val cur = flushTask
    if (cur != null && !cur.isDone && scheduledAt <= earliest) return
    cur?.cancel(false)
    flushTask = null
    scheduledAt = 0L
    val sched = scheduler ?: return
    val delayMs = (earliest - now).coerceAtLeast(0L)
    val seq = ++flushSeq
    val task = runCatching { sched.schedule({ tick(expectedSeq = seq) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull() ?: return
    flushTask = task
    scheduledAt = now + delayMs
  }

  /** When each polled folder may be reported again: set by [walked], so a folder that is dear to walk is not walked every [pollSeconds]. */
  private val pollNotBefore = ConcurrentHashMap<String, Long>()

  /** The indexer says how long its walk of [dir] took. Only a polled folder cares: it is not reported again for [PollPacing] after that. */
  fun walked(dir: Path, tookMs: Long, now: Long = System.currentTimeMillis()) {
    val key = dir.toString()
    if (modes[key] == WatchMode.POLLING) pollNotBefore[key] = PollPacing.notBefore(now, tookMs)
  }

  /** Reports the folders that are polled, in full: nothing told us where they changed. */
  private fun pollRoots(now: Long = System.currentTimeMillis()) {
    if (closed) return
    for ((root, mode) in modes) if (mode == WatchMode.POLLING && now >= (pollNotBefore[root] ?: 0L)) report(Dirty(File(root).toPath(), true))
  }

  internal fun tick(now: Long = System.currentTimeMillis(), expectedSeq: Long? = null) {
    if (closed) return
    val batch = ArrayList<Dirty>()
    synchronized(pending) {
      if (closed) return
      if (expectedSeq != null && expectedSeq != flushSeq) return
      tickCount.incrementAndGet()
      flushTask = null
      scheduledAt = 0L
      if (pending.isEmpty()) return
      val it = pending.entries.iterator()
      while (it.hasNext()) {
        val (dirty, entry) = it.next()
        if (readyAt(entry) <= now) {
          batch += dirty
          it.remove()
        }
      }
      if (batch.isNotEmpty()) {
        val flushed = batch.toHashSet()
        pendingDeletes.entries.removeIf { it.value in flushed }
      }
      if (pending.isNotEmpty()) scheduleFlushLocked(now)
    }
    if (batch.isEmpty()) return
    // A recursive report covers everything under it.
    val recursive = batch.filter { it.recursive }.map { it.dir }
    val merged = batch.filter { d -> recursive.none { r -> d.dir != r && d.dir.startsWith(r) } && !(!d.recursive && d.dir in recursive) }.distinct()
    try { onDirty(merged) } catch (t: Throwable) { log("onDirty failed: ${t.message}") }
  }

  override fun close() {
    closed = true
    synchronized(lock) {
      stopRoots()
      runCatching { service?.close() }
      service = null
      pump?.interrupt()
      pump = null
    }
  }

  companion object {
    /** `fs.inotify.max_user_watches`, or null when it cannot be read (not Linux, or /proc is hidden). */
    fun inotifyLimit(file: File = File("/proc/sys/fs/inotify/max_user_watches")): Long? =
      runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.toLong() }.getOrNull()
  }
}

/**
 * How long a polled folder is left alone after its walk: [BackgroundTuning.pollFactor] times as long as the walk took, at most
 * [BackgroundTuning.pollMaxMs]. A walk is as dear as the folder is big, so the dear ones come less often and the cost of polling stays
 * under a CPU-second a minute whatever the size; a folder that walks in under a quarter of a second still comes every poll interval.
 */
object PollPacing {
  fun notBefore(now: Long, tookMs: Long, factor: Long = BackgroundTuning.pollFactor, maxMs: Long = BackgroundTuning.pollMaxMs): Long =
    if (factor <= 0L) 0L else now + minOf(tookMs * factor, maxMs)
}
