package trawl.desktop.shell

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Linux on X11: XGrabKey on the root window. No extension, no permission. The grab is made four times (plain, Caps Lock, Num Lock, both)
 * because X treats each lock state as a different modifier mask. If another client already holds the key, the server answers BadAccess
 * (error code 10) asynchronously; an error handler records it and an XSync right after the grabs turns that into a result we can return.
 *
 * Wayland is different and the limit is real: a Wayland client cannot listen for a key while another window has focus, by design.
 * Running under XWayland, XGrabKey "succeeds" but only sees keys while an X11 window is focused, which would be a shortcut that works
 * sometimes. So on a Wayland session this class does not try, and says so ([HotkeyResult.Unsupported] with wayland = true). The fallback
 * the Settings text gives is a system shortcut that runs `trawl --quick`, which the single-instance link turns into "open the panel".
 * (xdg-desktop-portal's GlobalShortcuts is the proper Wayland answer; support is still uneven across desktops.)
 */
class HotkeyX11(
  private val env: Map<String, String> = System.getenv(),
) : GlobalHotkey {
  interface Xlib : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XCloseDisplay(d: Pointer): Int
    fun XDefaultRootWindow(d: Pointer): Long
    fun XKeysymToKeycode(d: Pointer, keysym: Long): Int
    fun XGrabKey(d: Pointer, keycode: Int, modifiers: Int, grabWindow: Long, ownerEvents: Int, pointerMode: Int, keyboardMode: Int): Int
    fun XUngrabKey(d: Pointer, keycode: Int, modifiers: Int, grabWindow: Long): Int
    fun XSelectInput(d: Pointer, window: Long, mask: Long): Int
    fun XSync(d: Pointer, discard: Int): Int
    fun XPending(d: Pointer): Int
    fun XNextEvent(d: Pointer, event: Pointer): Int
    fun XConnectionNumber(d: Pointer): Int
    fun XSetErrorHandler(handler: ErrorHandler?): Pointer?
    /** Same symbol, to put back the handler Xlib returned (AWT installs its own; resetting to null would make Xlib exit() on any later error). */
    fun XSetErrorHandler(previous: Pointer?): Pointer?
  }

  fun interface ErrorHandler : Callback { fun invoke(display: Pointer?, event: Pointer?): Int }

  interface LibC : Library { fun poll(fds: PollFd, nfds: Long, timeoutMs: Int): Int }

  @Structure.FieldOrder("fd", "events", "revents")
  class PollFd : Structure() {
    @JvmField var fd: Int = 0
    @JvmField var events: Short = 0
    @JvmField var revents: Short = 0
  }

  private val x11: Xlib? = listOf("X11", "libX11.so.6").firstNotNullOfOrNull { n -> runCatching { Native.load(n, Xlib::class.java) }.getOrNull() }
  private val libc: LibC? = runCatching { Native.load("c", LibC::class.java) }.getOrNull()

  @Volatile private var thread: Thread? = null
  @Volatile private var stop = false

  /** A Wayland session, by the two variables a login manager sets. */
  fun isWayland(): Boolean = env["XDG_SESSION_TYPE"].equals("wayland", true) || !env["WAYLAND_DISPLAY"].isNullOrBlank()

  @Synchronized
  override fun register(h: Hotkey, onPressed: () -> Unit): HotkeyResult {
    if (isWayland()) return HotkeyResult.Unsupported(true, "Wayland has no global key grab")
    val x = x11 ?: return HotkeyResult.Unsupported(false, "libX11 is not installed")
    val c = libc ?: return HotkeyResult.Unsupported(false, "libc is not available")
    unregister()
    stop = false
    val answer = CompletableFuture<HotkeyResult>()
    val t = Thread({
      var failure: Int? = null
      // Xlib keeps a pointer to this callback: it lives as long as the thread does.
      val handler = ErrorHandler { _, event -> failure = event?.getByte(ERROR_CODE_OFFSET)?.toInt() ?: -1; 0 }
      var previous: Pointer? = null
      val d = x.XOpenDisplay(null)
      if (d == null) { answer.complete(HotkeyResult.Unsupported(false, "no X server to connect to")); return@Thread }
      try {
        previous = x.XSetErrorHandler(handler)
        val root = x.XDefaultRootWindow(d)
        val keycode = x.XKeysymToKeycode(d, HotkeyCodes.xKeysym(h.key))
        if (keycode == 0) { answer.complete(HotkeyResult.Failed("this keyboard has no ${h.key} key")); return@Thread }
        val base = HotkeyCodes.xMods(h)
        // No XSelectInput: a grab delivers its KeyPress to the grabbing client on its own. Selecting KeyPress on the root window would
        // also deliver EVERY key typed while no window has the focus (found with Xvfb), and the panel would open on any key.
        for (lock in HotkeyCodes.xLockVariants()) x.XGrabKey(d, keycode, base or lock, root, 0, GRAB_MODE_ASYNC, GRAB_MODE_ASYNC)
        x.XSync(d, 0)   // the server's BadAccess, if any, reaches the handler before this returns
        val failed = failure
        if (failed != null) {
          for (lock in HotkeyCodes.xLockVariants()) x.XUngrabKey(d, keycode, base or lock, root)
          x.XSync(d, 0)
          answer.complete(if (failed == BAD_ACCESS) HotkeyResult.Taken("BadAccess") else HotkeyResult.Failed("X error $failed"))
          return@Thread
        }
        answer.complete(HotkeyResult.Ok)
        val event = Memory(EVENT_BYTES)
        val fd = PollFd().also { it.fd = x.XConnectionNumber(d); it.events = POLLIN }
        var lastMs = 0L
        while (!stop) {
          fd.revents = 0
          c.poll(fd, 1, POLL_MS)
          while (x.XPending(d) > 0) {
            x.XNextEvent(d, event)
            // Belt and braces: only our chord (XKeyEvent: state at byte 80, keycode at 84), ignoring Caps Lock and Num Lock.
            if (event.getInt(0) == KEY_PRESS && event.getInt(KEYCODE_OFFSET) == keycode &&
              (event.getInt(STATE_OFFSET) and LOCKS.inv()) == base) {
              val now = System.nanoTime() / 1_000_000
              if (now - lastMs > DEBOUNCE_MS) { lastMs = now; runCatching(onPressed) }
            }
          }
        }
        for (lock in HotkeyCodes.xLockVariants()) x.XUngrabKey(d, keycode, base or lock, root)
        x.XSync(d, 0)
      } finally {
        x.XSetErrorHandler(previous)
        x.XCloseDisplay(d)
        if (!answer.isDone) answer.complete(HotkeyResult.Failed("the key grab stopped early"))
      }
    }, "trawl-hotkey")
    t.isDaemon = true
    t.start()
    val r = runCatching { answer.get(3, TimeUnit.SECONDS) }.getOrElse { HotkeyResult.Failed("no answer from the X server") }
    if (r == HotkeyResult.Ok) thread = t else { stop = true }
    return r
  }

  @Synchronized
  override fun unregister() {
    val t = thread ?: return
    stop = true
    runCatching { t.join(POLL_MS * 3L) }
    thread = null
  }

  private companion object {
    const val KEY_PRESS = 2
    const val STATE_OFFSET = 80L
    const val KEYCODE_OFFSET = 84L
    /** LockMask (Caps Lock) and Mod2Mask (Num Lock). */
    const val LOCKS = 0x2 or 0x10
    const val GRAB_MODE_ASYNC = 1
    const val BAD_ACCESS = 10
    /** XErrorEvent: type int, display ptr, resourceid ulong, serial ulong, error_code uchar. */
    const val ERROR_CODE_OFFSET = 8L + 8 + 8 + 8
    const val EVENT_BYTES = 192L
    const val POLLIN: Short = 1
    const val POLL_MS = 250
    const val DEBOUNCE_MS = 250
  }
}
