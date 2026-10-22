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
import java.util.concurrent.TimeUnit

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
class FolderWatcher(
  private val os: Os = Platform.os,
  private val maxWatches: () -> Long? = { inotifyLimit() },
  private val pollSeconds: Int = 30,
  private val debounceMs: Long = 1500,
  private val maxWaitMs: Long = 10_000,
  private val budgetShare: Double = 0.5,
  private val fsEvents: FsEventsBackend = platformFsEvents(),
  private val log: (String) -> Unit = {},
  /** Folders the person skips: nothing under them is watched or reported. Read at each event, so a change applies at once. */
  private val ignore: (Path) -> Boolean = { false },
  private val onDirty: (List<Dirty>) -> Unit,
) : AutoCloseable {

  private val lock = Any()
  private var service: WatchService? = null
  private val modes = ConcurrentHashMap<String, WatchMode>()
  private val keyDirs = ConcurrentHashMap<WatchKey, Path>()
  private val rootOfKey = ConcurrentHashMap<WatchKey, Path>()
  private val handles = ConcurrentHashMap<String, AutoCloseable>()
  private val pending = ConcurrentHashMap<Dirty, Long>()
  private var firstPendingAt = 0L
  private var lastPendingAt = 0L
  @Volatile private var closed = false
  private var scheduler: ScheduledExecutorService? = null
  private var pump: Thread? = null
  private val limit: Long? = if (os == Os.LINUX) maxWatches() else null

  val info: WatchInfo get() = WatchInfo(modes.toMap().toSortedMap(), keyDirs.size, limit, pollSeconds)

  /** Replaces the watched folders. Safe to call again whenever the person adds or removes one. */
  fun watch(roots: List<File>) = synchronized(lock) {
    stopRoots()
    if (closed) return@synchronized
    val ws = service ?: FileSystems.getDefault().newWatchService().also { service = it; startPump(it) }
    val sched = scheduler ?: Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trawl-watch-flush").apply { isDaemon = true } }.also { scheduler = it }
    for (f in roots) {
      val root = f.toPath().toAbsolutePath().normalize()
      if (!Files.isDirectory(root)) { modes[root.toString()] = WatchMode.POLLING; continue }   // gone or unplugged: polling notices it coming back
      val mode = try { register(ws, root) } catch (e: Exception) {
        log("watch $root: ${e.javaClass.simpleName} ${e.message}; polling instead"); WatchMode.POLLING
      }
      modes[root.toString()] = mode
    }
    // One flush-and-poll tick for everything: flushes quiet reports and, for polled folders, reports the whole folder.
    sched.scheduleWithFixedDelay({ tick() }, 500, 500, TimeUnit.MILLISECONDS)
    pollTask?.cancel(false)
    pollTask = sched.scheduleWithFixedDelay({ pollRoots() }, pollSeconds.toLong(), pollSeconds.toLong(), TimeUnit.SECONDS)
  }

  private var pollTask: java.util.concurrent.ScheduledFuture<*>? = null

  private fun stopRoots() {
    keyDirs.keys.forEach { it.cancel() }
    keyDirs.clear(); rootOfKey.clear(); modes.clear()
    handles.values.forEach { runCatching { it.close() } }
    handles.clear()
    scheduler?.shutdownNow(); scheduler = null
  }

  private fun register(ws: WatchService, root: Path): WatchMode = when (os) {
    Os.WINDOWS -> {
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
        registerDirs(ws, root, cap = 2_000)   // the JDK polls on macOS: every directory is polled, so cap it like inotify
      }
    }
    Os.LINUX -> registerDirs(ws, root, cap = limit?.let { (it * budgetShare).toLong() } ?: 4_000L)
  }

  /** Registers [root] and every directory under it, unless that is more than [cap]: then the folder is polled and nothing stays registered. */
  private fun registerDirs(ws: WatchService, root: Path, cap: Long): WatchMode {
    val dirs = ArrayList<Path>()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (dir != root && (isHidden(dir) || ignore(dir))) return FileVisitResult.SKIP_SUBTREE
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
        val key = try { ws.poll(500, TimeUnit.MILLISECONDS) } catch (_: ClosedWatchServiceException) { return@Thread } catch (_: InterruptedException) { return@Thread } ?: continue
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
              if (os != Os.WINDOWS) newDirectory(full, root)
              report(Dirty(full, true))
            } else {
              report(Dirty(full.parent ?: dir, false))
            }
          }
        }
        if (!key.reset()) { keyDirs.remove(key); rootOfKey.remove(key) }
      }
    }, "trawl-watch").apply { isDaemon = true; start() }
  }

  /** A directory appeared under a per-directory watch (Linux, macOS poller): watch it too, or fall back to polling for its folder. */
  private fun newDirectory(dir: Path, root: Path) {
    val ws = service ?: return
    try {
      Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
          if ((d != dir && isHidden(d)) || ignore(d)) return FileVisitResult.SKIP_SUBTREE
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
    }
  }

  private fun report(d: Dirty) {
    if (ignore(d.dir)) return   // inside a skipped folder: the index does not read it, so a change there is not news
    val under = modes.keys.map { File(it).toPath() }.firstOrNull { d.dir.startsWith(it) }
    if (under != null && Crawler.insideHidden(under, d.dir)) return   // nor is a hidden folder (".git"): never read, and often the busiest
    val now = System.currentTimeMillis()
    synchronized(pending) {
      if (pending.isEmpty()) firstPendingAt = now
      pending[d] = now
      lastPendingAt = now
    }
  }

  /** Reports the folders that are polled, in full: nothing told us where they changed. */
  private fun pollRoots() {
    if (closed) return
    for ((root, mode) in modes) if (mode == WatchMode.POLLING) report(Dirty(File(root).toPath(), true))
  }

  private fun tick() {
    if (closed) return
    val batch: List<Dirty>
    synchronized(pending) {
      if (pending.isEmpty()) return
      val now = System.currentTimeMillis()
      if (now - lastPendingAt < debounceMs && now - firstPendingAt < maxWaitMs) return
      batch = pending.keys.toList()
      pending.clear()
    }
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
    }
    pump?.interrupt()
  }

  companion object {
    /** `fs.inotify.max_user_watches`, or null when it cannot be read (not Linux, or /proc is hidden). */
    fun inotifyLimit(file: File = File("/proc/sys/fs/inotify/max_user_watches")): Long? =
      runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.toLong() }.getOrNull()
  }
}
