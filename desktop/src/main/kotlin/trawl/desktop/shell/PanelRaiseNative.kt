package trawl.desktop.shell

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Desktop
import java.awt.Window
import trawl.desktop.Os
import trawl.desktop.privacy.SecureWindow

/**
 * The system calls behind [RaiseStep], one per step. Each call can only be proven on its own system with a second app in front (macOS and
 * Windows are checked by hand; Linux X11 runs against Xvfb in PanelRaiseX11Test). Nothing here throws: a step that cannot be made is false.
 * No networking, no child process, no permission prompt: these are the same calls an ordinary windowing toolkit makes.
 */
internal object PanelRaiseNative {
  fun run(step: RaiseStep, window: Window, os: Os): Boolean = try {
    when (step) {
      RaiseStep.AWT_FRONT -> awtFront(window)
      RaiseStep.MAC_JOIN_SPACES -> onMainThread { macJoinSpaces(window) }
      RaiseStep.MAC_ACTIVATE -> onMainThread { macMsg(macApp(), "activateIgnoringOtherApps:", 1L) }
      RaiseStep.MAC_ACTIVATE_WORKSPACE -> onMainThread { macActivateRunningApp() }
      RaiseStep.MAC_ORDER_FRONT -> onMainThread { macOrderFront(window) }
      RaiseStep.WIN_ALLOW_AND_FOREGROUND -> winAllowAndForeground(window)
      RaiseStep.WIN_ATTACH_INPUT -> winAttachInput(window)
      RaiseStep.WIN_INPUT_NUDGE -> winInputNudge(window)
      RaiseStep.WIN_TOPMOST -> winTopmost(window)
      RaiseStep.X11_ACTIVATE_MESSAGE -> x11Activate(window)
      RaiseStep.X11_RAISE_AND_FOCUS -> x11RaiseAndFocus(window)
    }
  } catch (_: Throwable) {
    false
  }

  private fun awtFront(window: Window): Boolean {
    runCatching {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) Desktop.getDesktop().requestForeground(true)
    }
    window.toFront()
    window.requestFocus()
    return true
  }

  // ---- macOS: AppKit wants the main thread ---------------------------------------------------------------------------------

  private interface ObjC : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer
    fun objc_msgSend(receiver: Pointer, selector: Pointer): Long
    fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Long): Long
  }

  fun interface MainWork : Callback { fun invoke(context: Pointer?) }

  private interface Dispatch : Library {
    fun dispatch_async_f(queue: Pointer, context: Pointer?, work: MainWork)
  }

  private val objc: ObjC by lazy { Native.load("objc", ObjC::class.java) }
  private val dispatch: Dispatch by lazy { Native.load("System", Dispatch::class.java) }
  private val mainQueue: Pointer by lazy { NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q") }

  /** JNA callbacks must stay referenced until the system has called them. */
  private val inFlight: MutableSet<MainWork> = java.util.Collections.synchronizedSet(mutableSetOf())

  private fun onMainThread(block: () -> Unit): Boolean {
    val work = object : MainWork {
      override fun invoke(context: Pointer?) { try { block() } catch (_: Throwable) { } finally { inFlight.remove(this) } }
    }
    inFlight += work
    dispatch.dispatch_async_f(mainQueue, null, work)
    return true
  }

  private fun cls(name: String): Pointer = objc.objc_getClass(name) ?: error("no class $name")
  private fun sel(name: String): Pointer = objc.sel_registerName(name)
  private fun macMsg(receiver: Pointer, selector: String): Long = objc.objc_msgSend(receiver, sel(selector))
  private fun macMsg(receiver: Pointer, selector: String, arg: Long): Long = objc.objc_msgSend(receiver, sel(selector), arg)
  private fun macApp(): Pointer = Pointer(macMsg(cls("NSApplication"), "sharedApplication"))

  /** NSWindowCollectionBehaviorCanJoinAllSpaces (1 << 0) and FullScreenAuxiliary (1 << 8), at NSStatusWindowLevel (25). */
  private const val JOIN_ALL_SPACES = 1L shl 0
  private const val FULL_SCREEN_AUXILIARY = 1L shl 8
  private const val STATUS_WINDOW_LEVEL = 25L

  private fun macJoinSpaces(window: Window) {
    val ns = SecureWindow.nsWindowOf(window) ?: return
    val now = macMsg(ns, "collectionBehavior")
    macMsg(ns, "setCollectionBehavior:", now or JOIN_ALL_SPACES or FULL_SCREEN_AUXILIARY)
    macMsg(ns, "setLevel:", STATUS_WINDOW_LEVEL)
    macMsg(ns, "setHidesOnDeactivate:", 0L)
  }

  private fun macActivateRunningApp() {
    val me = Pointer(macMsg(cls("NSRunningApplication"), "currentApplication"))
    // NSApplicationActivateAllWindows (1) | NSApplicationActivateIgnoringOtherApps (2)
    macMsg(me, "activateWithOptions:", 3L)
  }

  private fun macOrderFront(window: Window) {
    val ns = SecureWindow.nsWindowOf(window) ?: return
    macMsg(ns, "makeKeyAndOrderFront:", 0L)
    macMsg(ns, "orderFrontRegardless")
  }

  // ---- Windows ---------------------------------------------------------------------------------------------------------------

  private interface User32 : Library {
    fun GetForegroundWindow(): Pointer?
    fun SetForegroundWindow(h: Pointer): Boolean
    fun AllowSetForegroundWindow(pid: Int): Boolean
    fun GetWindowThreadProcessId(h: Pointer, pid: IntByReference?): Int
    fun AttachThreadInput(from: Int, to: Int, attach: Boolean): Boolean
    fun BringWindowToTop(h: Pointer): Boolean
    fun SetFocus(h: Pointer): Pointer?
    fun SetWindowPos(h: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun ShowWindow(h: Pointer, cmd: Int): Boolean
    fun IsIconic(h: Pointer): Boolean
    fun keybd_event(vk: Byte, scan: Byte, flags: Int, extra: Long)
  }

  private interface Kernel32 : Library {
    fun GetCurrentThreadId(): Int
    fun GetCurrentProcessId(): Int
  }

  private val user32: User32 by lazy { Native.load("user32", User32::class.java) }
  private val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

  private const val SW_SHOW = 5
  private const val SW_RESTORE = 9
  private const val SWP_NOSIZE = 0x1
  private const val SWP_NOMOVE = 0x2
  private const val SWP_NOACTIVATE = 0x10
  private const val SWP_SHOWWINDOW = 0x40
  private const val KEYEVENTF_KEYUP = 0x2
  /** VK 0xE8 is unassigned: a press and release of it does nothing in any app, but counts as input for the foreground rule. */
  private const val VK_UNASSIGNED: Byte = 0xE8.toByte()

  private fun hwnd(window: Window): Pointer = Pointer(Native.getWindowID(window))

  private fun winAllowAndForeground(window: Window): Boolean {
    val h = hwnd(window)
    user32.AllowSetForegroundWindow(kernel32.GetCurrentProcessId())
    if (user32.IsIconic(h)) user32.ShowWindow(h, SW_RESTORE)
    return user32.SetForegroundWindow(h)
  }

  private fun winAttachInput(window: Window): Boolean {
    val h = hwnd(window)
    val fg = user32.GetForegroundWindow()
    val mine = kernel32.GetCurrentThreadId()
    val theirs = if (fg != null) user32.GetWindowThreadProcessId(fg, null) else 0
    val attached = theirs != 0 && theirs != mine && user32.AttachThreadInput(mine, theirs, true)
    try {
      user32.ShowWindow(h, SW_SHOW)
      user32.BringWindowToTop(h)
      val ok = user32.SetForegroundWindow(h)
      user32.SetFocus(h)
      return ok
    } finally {
      if (attached) user32.AttachThreadInput(mine, theirs, false)
    }
  }

  private fun winInputNudge(window: Window): Boolean {
    user32.keybd_event(VK_UNASSIGNED, 0, 0, 0)
    user32.keybd_event(VK_UNASSIGNED, 0, KEYEVENTF_KEYUP, 0)
    return user32.SetForegroundWindow(hwnd(window))
  }

  /**
   * Topmost lifts the window over everything, including windows another program keeps on top. The panel is always-on-top and stays so; the
   * main window is not, so it is dropped back to a normal window straight away (it stays in front, but later windows can cover it again).
   */
  private fun winTopmost(window: Window): Boolean {
    val h = hwnd(window)
    val ok = user32.SetWindowPos(h, Pointer.createConstant(-1L), 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_SHOWWINDOW)
    if (!window.isAlwaysOnTop) user32.SetWindowPos(h, Pointer.createConstant(-2L), 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE)
    return ok
  }

  // ---- Linux X11 ---------------------------------------------------------------------------------------------------------------

  internal interface Xlib : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XCloseDisplay(d: Pointer): Int
    fun XDefaultRootWindow(d: Pointer): Long
    fun XInternAtom(d: Pointer, name: String, onlyIfExists: Int): Long
    fun XSendEvent(d: Pointer, w: Long, propagate: Int, mask: Long, event: Pointer): Int
    fun XRaiseWindow(d: Pointer, w: Long): Int
    fun XSetInputFocus(d: Pointer, w: Long, revertTo: Int, time: Long): Int
    fun XQueryTree(d: Pointer, w: Long, root: LongByReference, parent: LongByReference, children: PointerByReference, n: IntByReference): Int
    fun XFree(p: Pointer): Int
    fun XFlush(d: Pointer): Int
    fun XSync(d: Pointer, discard: Int): Int
    fun XSetErrorHandler(handler: XErrorHandler?): Pointer?
    /** Same symbol, to put back the handler Xlib returned (AWT installs its own; null would make Xlib exit() on a later error). */
    fun XSetErrorHandler(previous: Pointer?): Pointer?
  }

  fun interface XErrorHandler : Callback { fun invoke(display: Pointer?, event: Pointer?): Int }

  /** An error from these calls (a window not yet mapped, say) must never reach Xlib's default handler, which ends the process. */
  private val ignoreErrors = XErrorHandler { _, _ -> 0 }

  internal val xlib: Xlib? by lazy {
    listOf("X11", "libX11.so.6").firstNotNullOfOrNull { n -> runCatching { Native.load(n, Xlib::class.java) }.getOrNull() }
  }

  /** The window the window manager manages for an AWT window: the ancestor that is a direct child of the root. */
  internal fun x11TopLevel(x: Xlib, d: Pointer, id: Long): Long {
    val root = x.XDefaultRootWindow(d)
    var w = id
    repeat(8) {
      val r = LongByReference(); val p = LongByReference(); val kids = PointerByReference(); val n = IntByReference()
      if (x.XQueryTree(d, w, r, p, kids, n) == 0) return w
      kids.value?.let { x.XFree(it) }
      if (p.value == 0L || p.value == root) return w
      w = p.value
    }
    return w
  }

  private fun x11(window: Window, action: (Xlib, Pointer, Long, Long) -> Unit): Boolean {
    val x = xlib ?: return false
    if (System.getenv("DISPLAY").isNullOrBlank()) return false
    val d = x.XOpenDisplay(null) ?: return false
    val previous = x.XSetErrorHandler(ignoreErrors)
    try {
      val top = x11TopLevel(x, d, Native.getWindowID(window))
      action(x, d, x.XDefaultRootWindow(d), top)
      x.XFlush(d)
      x.XSync(d, 0)
      return true
    } finally {
      x.XSetErrorHandler(previous)
      x.XCloseDisplay(d)
    }
  }

  private const val CLIENT_MESSAGE = 33
  private const val SUBSTRUCTURE_NOTIFY_OR_REDIRECT = (1L shl 19) or (1L shl 20)
  private const val EVENT_BYTES = 192L

  private fun x11Activate(window: Window): Boolean = x11(window) { x, d, root, top ->
    val atom = x.XInternAtom(d, "_NET_ACTIVE_WINDOW", 0)
    if (atom == 0L) return@x11
    val ev = Memory(EVENT_BYTES).also { it.clear() }
    // XClientMessageEvent on 64-bit: type int @0, send_event @16, window @32, message_type @40, format @48, data.l[5] @56.
    ev.setInt(0, CLIENT_MESSAGE); ev.setInt(16, 1); ev.setLong(32, top); ev.setLong(40, atom); ev.setInt(48, 32)
    ev.setLong(56, 2L)   // source indication: a pager, which window managers let through focus-stealing prevention
    ev.setLong(64, 0L)   // timestamp: unknown
    ev.setLong(72, 0L)   // currently active window: unknown
    x.XSendEvent(d, root, 0, SUBSTRUCTURE_NOTIFY_OR_REDIRECT, ev)
  }

  private fun x11RaiseAndFocus(window: Window): Boolean = x11(window) { x, d, _, top ->
    x.XRaiseWindow(d, top)
    x.XSetInputFocus(d, top, REVERT_TO_PARENT, CURRENT_TIME)
  }

  private const val REVERT_TO_PARENT = 2
  private const val CURRENT_TIME = 0L
}
