package trawl.desktop.privacy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import trawl.desktop.Os
import trawl.desktop.Platform

/** What asking the system to keep a window out of screenshots and screen sharing came to. */
enum class CaptureResult {
  /** The system accepted it. It is a request: some tools can still see the window (macOS), and a photo of the screen always can. */
  APPLIED,

  /** This system gives apps no way to do it (Linux), or no window is open yet. Nothing was changed. */
  UNSUPPORTED,

  /** The system refused or the call could not be made. Nothing was changed. */
  FAILED,
}

/**
 * D6: keep Trawl's window out of screenshots and screen sharing while hidden or sensitive things are showing (`PrivacyController.secureWindow`).
 *
 *  - Windows: `SetWindowDisplayAffinity`. `WDA_EXCLUDEFROMCAPTURE` (Windows 10 2004 and later) leaves the window out of a capture; older
 *    builds get `WDA_MONITOR`, which captures it as a black rectangle.
 *  - macOS: `NSWindow.sharingType = NSWindowSharingNone`, asked through the Objective-C runtime. macOS treats this as a request and some
 *    capture tools ignore it, so the settings text says so. Reaching the NSWindow takes a look inside the JDK; if the JDK does not allow it
 *    the answer is [CaptureResult.FAILED] and the window stays as it was.
 *  - Linux: nothing reliable exists (X11 and Wayland leave it to the compositor), so [CaptureResult.UNSUPPORTED], and the setting is greyed out.
 *
 * It never throws, and it never claims what it could not do: the caller can show [CaptureResult.FAILED] instead of a tick.
 * (The two native paths need a real window of that system, so they are checked by hand on Windows and macOS; the rest by unit tests.)
 */
object SecureWindow {
  fun apply(window: Window?, on: Boolean, os: Os = Platform.os): CaptureResult {
    if (window == null || !window.isDisplayable) return CaptureResult.UNSUPPORTED
    return try {
      when (os) {
        Os.WINDOWS -> windows(window, on)
        Os.MAC -> mac(window, on)
        Os.LINUX -> CaptureResult.UNSUPPORTED
      }
    } catch (_: Throwable) {
      CaptureResult.FAILED
    }
  }

  // ---- Windows ----------------------------------------------------------------------------------------------------------

  private const val WDA_NONE = 0x0
  private const val WDA_MONITOR = 0x1
  private const val WDA_EXCLUDEFROMCAPTURE = 0x11

  private interface User32 : Library {
    fun SetWindowDisplayAffinity(hwnd: Pointer, affinity: Int): Boolean
  }

  private val user32: User32 by lazy { Native.load("user32", User32::class.java) }

  private fun windows(window: Window, on: Boolean): CaptureResult {
    val hwnd = Pointer(Native.getWindowID(window))
    if (!on) return if (user32.SetWindowDisplayAffinity(hwnd, WDA_NONE)) CaptureResult.APPLIED else CaptureResult.FAILED
    if (user32.SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)) return CaptureResult.APPLIED
    return if (user32.SetWindowDisplayAffinity(hwnd, WDA_MONITOR)) CaptureResult.APPLIED else CaptureResult.FAILED
  }

  // ---- macOS ------------------------------------------------------------------------------------------------------------

  private const val NS_WINDOW_SHARING_NONE = 0L
  private const val NS_WINDOW_SHARING_READ_ONLY = 1L

  private interface ObjC : Library {
    fun sel_registerName(name: String): Pointer
    fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Long): Pointer
  }

  fun interface MainWork : com.sun.jna.Callback { fun invoke(context: Pointer?) }

  private interface Dispatch : Library {
    fun dispatch_async_f(queue: Pointer, context: Pointer?, work: MainWork)
  }

  private val objc: ObjC by lazy { Native.load("objc", ObjC::class.java) }
  private val dispatch: Dispatch by lazy { Native.load("System", Dispatch::class.java) }
  private val mainQueue: Pointer by lazy { com.sun.jna.NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q") }
  private val inFlight: MutableSet<MainWork> = java.util.Collections.synchronizedSet(mutableSetOf())

  private fun mac(window: Window, on: Boolean): CaptureResult {
    // Prefer libtrawl_mac.dylib (retains NSWindow under ARC and dispatches asynchronously onto the AppKit main queue).
    if (trawl.desktop.shell.MacNative.setSharingType(window, allowCapture = !on)) return CaptureResult.APPLIED
    val ns = nsWindowOf(window) ?: return CaptureResult.FAILED
    val sharing = if (on) NS_WINDOW_SHARING_NONE else NS_WINDOW_SHARING_READ_ONLY
    val work = object : MainWork {
      override fun invoke(context: Pointer?) {
        try {
          // Retain/send on AppKit main thread only (never off-main from Dispatchers.IO).
          objc.objc_msgSend(ns, objc.sel_registerName("setSharingType:"), sharing) // garden-lint:allow
        } catch (_: Throwable) {
        } finally {
          inFlight.remove(this)
        }
      }
    }
    inFlight += work
    dispatch.dispatch_async_f(mainQueue, null, work)
    return CaptureResult.APPLIED
  }

  /** The NSWindow behind an AWT window, through the JDK's own peer classes (they need `--add-opens`, see build.gradle.kts). */
  internal fun nsWindowOf(window: Window): Pointer? {
    val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
    val accessorType = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
    val peer = accessorType.getMethod("getPeer", java.awt.Component::class.java).invoke(accessor, window) ?: return null
    val platformWindow = peer.javaClass.getMethod("getPlatformWindow").apply { isAccessible = true }.invoke(peer) ?: return null
    // OpenJDK 21's CPlatformWindow extends CFRetainedResource, which stores the NSWindow* in private field `ptr`
    // (there is no public `getNSWindowPtr()` method on JDK 21). Check `ptr` across superclasses first, then fall back.
    var cur: Class<*>? = platformWindow.javaClass
    while (cur != null && cur != Any::class.java) {
      val field = runCatching { cur.getDeclaredField("ptr") }.getOrNull()
      if (field != null) {
        field.isAccessible = true
        val ptr = (field.get(platformWindow) as? Long) ?: 0L
        return if (ptr == 0L) null else Pointer(ptr)
      }
      cur = cur.superclass
    }
    val method = runCatching { platformWindow.javaClass.getDeclaredMethod("getNSWindowPtr").apply { isAccessible = true } }.getOrNull()
      ?: return null
    val ptr = method.invoke(platformWindow) as? Long ?: return null
    return if (ptr == 0L) null else Pointer(ptr)
  }
}
