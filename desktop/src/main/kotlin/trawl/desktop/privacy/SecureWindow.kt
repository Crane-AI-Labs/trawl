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

  private val objc: ObjC by lazy { Native.load("objc", ObjC::class.java) }

  private fun mac(window: Window, on: Boolean): CaptureResult {
    val ns = nsWindowOf(window) ?: return CaptureResult.FAILED
    objc.objc_msgSend(ns, objc.sel_registerName("setSharingType:"), if (on) NS_WINDOW_SHARING_NONE else NS_WINDOW_SHARING_READ_ONLY)
    return CaptureResult.APPLIED
  }

  /** The NSWindow behind an AWT window, through the JDK's own peer classes (they need `--add-opens`, see build.gradle.kts). */
  private fun nsWindowOf(window: Window): Pointer? {
    val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
    val accessorType = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
    val peer = accessorType.getMethod("getPeer", java.awt.Component::class.java).invoke(accessor, window) ?: return null
    val platformWindow = peer.javaClass.getMethod("getPlatformWindow").apply { isAccessible = true }.invoke(peer) ?: return null
    val ptr = platformWindow.javaClass.getMethod("getNSWindowPtr").apply { isAccessible = true }.invoke(platformWindow) as? Long ?: return null
    return if (ptr == 0L) null else Pointer(ptr)
  }
}
