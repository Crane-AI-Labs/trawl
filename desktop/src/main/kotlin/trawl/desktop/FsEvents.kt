package trawl.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * macOS FSEvents through JNA (JNA is already a dependency; no new library). The JDK's own watcher polls on a Mac, which is slow and
 * walks every directory; FSEvents is the kernel's change journal, one stream per chosen folder covers the whole tree and costs
 * nothing while idle.
 *
 * Only the dozen C calls that are needed are declared, by hand, from the CoreServices and CoreFoundation headers. The stream asks
 * for per-file events (so a new folder can be told apart from a new file) with a 0.5 s latency; [FolderWatcher] debounces on top.
 * Paths from the kernel are real paths (`/var` arrives as `/private/var`), so they are mapped back under the root the person
 * chose, which is what the index holds.
 */
object MacFsEvents : FsEventsBackend {
  private interface CoreFoundation : Library {
    fun CFStringCreateWithCString(alloc: Pointer?, cstr: String, encoding: Int): Pointer
    fun CFArrayCreate(alloc: Pointer?, values: Array<Pointer>, count: Long, callbacks: Pointer?): Pointer
    fun CFRelease(cf: Pointer)
  }

  /** `void callback(ConstFSEventStreamRef, void *info, size_t numEvents, void *eventPaths, const FSEventStreamEventFlags[], const FSEventStreamEventId[])` */
  private fun interface StreamCallback : Callback {
    fun invoke(stream: Pointer?, info: Pointer?, numEvents: Long, eventPaths: Pointer, eventFlags: Pointer, eventIds: Pointer)
  }

  private interface CoreServices : Library {
    fun FSEventStreamCreate(alloc: Pointer?, callback: StreamCallback, context: Pointer?, paths: Pointer, sinceWhen: Long, latency: Double, flags: Int): Pointer?
    fun FSEventStreamSetDispatchQueue(stream: Pointer, queue: Pointer)
    fun FSEventStreamStart(stream: Pointer): Boolean
    fun FSEventStreamStop(stream: Pointer)
    fun FSEventStreamInvalidate(stream: Pointer)
    fun FSEventStreamRelease(stream: Pointer)
  }

  private interface LibSystem : Library {
    fun dispatch_queue_create(label: String, attr: Pointer?): Pointer?
    fun dispatch_release(obj: Pointer)
  }

  private class Libs(val cf: CoreFoundation, val cs: CoreServices, val sys: LibSystem, val arrayCallbacks: Pointer?)

  private val libs: Libs? by lazy {
    if (Platform.os != Os.MAC) return@lazy null
    runCatching {
      Libs(
        Native.load("CoreFoundation", CoreFoundation::class.java),
        Native.load("CoreServices", CoreServices::class.java),
        Native.load("System", LibSystem::class.java),
        NativeLibrary.getInstance("CoreFoundation").getGlobalVariableAddress("kCFTypeArrayCallBacks"),
      )
    }.getOrNull()
  }

  // FSEventStreamCreateFlags / FSEventStreamEventFlags (CoreServices/FSEvents.h)
  private const val NO_DEFER = 0x2
  private const val FILE_EVENTS = 0x10
  private const val MUST_SCAN_SUBDIRS = 0x1
  private const val ROOT_CHANGED = 0x20
  private const val ITEM_IS_DIR = 0x20000
  private const val SINCE_NOW = -1L
  private const val UTF8 = 0x08000100
  private const val LATENCY_S = 0.5

  private val queueNumber = AtomicInteger()

  override fun available(): Boolean = libs != null

  override fun watch(root: Path, sink: (Dirty) -> Unit): AutoCloseable {
    val l = libs ?: error("FSEvents is not available on this computer")
    val real = root.toRealPath()
    // Kept in a field of the handle: JNA frees a callback that nothing refers to, and the kernel would call a dead address.
    val callback = StreamCallback { _, _, n, paths, flags, _ ->
      val count = n.toInt()
      val strings = paths.getStringArray(0, count)
      for (i in 0 until count) {
        val f = flags.getInt(i * 4L)
        val p = strings[i]
        report(real, root, p, f, sink)
      }
    }
    val str = l.cf.CFStringCreateWithCString(null, real.toString(), UTF8)
    val array = l.cf.CFArrayCreate(null, arrayOf(str), 1, l.arrayCallbacks)
    val stream = l.cs.FSEventStreamCreate(null, callback, null, array, SINCE_NOW, LATENCY_S, FILE_EVENTS or NO_DEFER)
    l.cf.CFRelease(array); l.cf.CFRelease(str)
    if (stream == null) error("FSEventStreamCreate failed for $root")
    val queue = l.sys.dispatch_queue_create("trawl.fsevents.${queueNumber.incrementAndGet()}", null) ?: error("no dispatch queue")
    l.cs.FSEventStreamSetDispatchQueue(stream, queue)
    if (!l.cs.FSEventStreamStart(stream)) {
      l.cs.FSEventStreamInvalidate(stream); l.cs.FSEventStreamRelease(stream); l.sys.dispatch_release(queue)
      error("FSEventStreamStart failed for $root")
    }
    return object : AutoCloseable {
      @Volatile private var open = true
      @Suppress("unused") private val keep = callback
      override fun close() {
        if (!open) return
        open = false
        l.cs.FSEventStreamStop(stream); l.cs.FSEventStreamInvalidate(stream); l.cs.FSEventStreamRelease(stream)
        l.sys.dispatch_release(queue)
      }
    }
  }

  /** One kernel event to one [Dirty], the way the inotify pump does it: a folder is looked at all the way down, a file only in its folder. */
  internal fun report(real: Path, root: Path, eventPath: String, flags: Int, sink: (Dirty) -> Unit) {
    val p = java.nio.file.Paths.get(eventPath)
    if (!p.startsWith(real)) return
    val rel = real.relativize(p)
    val mapped = root.resolve(rel.toString()).normalize()
    // Anything inside a hidden folder (a ".git", a ".cache") is never read, and such folders are the busiest on a developer's disk: not news.
    if (rel.any { it.toString().startsWith(".") && it.toString() != ".." }) return
    if (flags and (MUST_SCAN_SUBDIRS or ROOT_CHANGED) != 0) { sink(Dirty(mapped, true)); return }
    if (flags and ITEM_IS_DIR != 0) sink(Dirty(mapped, true))
    else sink(Dirty(mapped.parent ?: root, false))
  }
}

/** The best FSEvents backend this computer has: the real one on a Mac, otherwise none. */
fun platformFsEvents(): FsEventsBackend = if (MacFsEvents.available()) MacFsEvents else NoFsEvents
