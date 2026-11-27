package trawl.desktop.shell

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import java.awt.Window
import java.io.File
import trawl.desktop.Os
import trawl.desktop.Platform
import trawl.desktop.privacy.SecureWindow

/**
 * Loads `libtrawl_mac.dylib` (compiled from `desktop/native/trawl-mac.m` on macOS builds) for:
 *  1. In-process Carbon `RegisterEventHotKey` on the AppKit main thread (macOS 14-26 grants activation authority
 *     directly to Trawl when the hotkey arrives in-process, so Quick Search opens above Terminal and full-screen apps).
 *  2. Spotlight-true `NSWindow`/`NSPanel` overlay configuration and raise without dragging Spaces.
 *  3. Native Cocoa `NSDraggingSession` carrying genuine `NSURL` (`public.file-url`) items so WhatsApp, Chrome, Safari,
 *     Slack, Mail, Messages, and Finder accept dropped files identically to Finder.
 */
internal object MacNative {
  fun interface HotkeyCb : Callback { fun invoke() }
  fun interface DragEndCb : Callback { fun invoke(accepted: Int) }
  fun interface OutsideClickCb : Callback { fun invoke() }

  private interface Lib : Library {
    fun trawl_mac_is_secure_input_enabled(): Int
    fun trawl_mac_install_drag_monitor()
    fun trawl_mac_set_accessory_policy(accessory: Int)
    fun trawl_mac_hotkey_register(keyCode: Int, carbonMods: Int, cb: HotkeyCb): Int
    fun trawl_mac_hotkey_unregister()
    fun trawl_mac_system_hotkeys(buf: ByteArray, bufSize: Int): Int
    fun trawl_mac_configure_main(nsWindowPtr: Pointer)
    fun trawl_mac_configure_overlay(nsWindowPtr: Pointer)
    fun trawl_mac_raise_overlay(nsWindowPtr: Pointer, activateApp: Int)
    fun trawl_mac_hide_overlay(nsWindowPtr: Pointer)
    fun trawl_mac_is_overlay_key(): Int
    fun trawl_mac_overlay_diagnostics(buf: ByteArray, bufSize: Int): Int
    fun trawl_mac_set_outside_click_callback(cb: OutsideClickCb?)
    fun trawl_mac_raise_main(nsWindowPtr: Pointer)
    fun trawl_mac_set_sharing_type(nsWindowPtr: Pointer, allowCapture: Int)
    fun trawl_mac_copy_files(paths: StringArray, count: Int): Int
    fun trawl_mac_start_file_drag(
      nsWindowPtr: Pointer,
      paths: StringArray,
      count: Int,
      pngBytes: ByteArray?,
      pngLen: Int,
      onEnd: DragEndCb,
    ): Int
  }

  private val lib: Lib? by lazy {
    if (Platform.os != Os.MAC) return@lazy null
    runCatching {
      val bytes = MacNative::class.java.classLoader.getResourceAsStream("libtrawl_mac.dylib")?.use { it.readBytes() }
        ?: return@runCatching null
      val dir = File(Platform.dataDir(), "helpers").apply { mkdirs() }
      val out = File(dir, "libtrawl_mac.dylib")
      // Rewrite whenever size differs or when content differs so upgrades immediately load the newest dylib.
      val same = out.isFile && out.length() == bytes.size.toLong() && out.readBytes().contentEquals(bytes)
      if (!same) {
        val tmp = File(dir, "libtrawl_mac.${ProcessHandle.current().pid()}.tmp.dylib")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
          out.writeBytes(bytes)
          tmp.delete()
        }
      }
      Native.load(out.absolutePath, Lib::class.java)
    }.getOrNull()
  }

  val available: Boolean get() = lib != null

  fun isSecureInputEnabled(): Boolean = lib?.trawl_mac_is_secure_input_enabled() == 1

  fun installDragMonitor() {
    lib?.trawl_mac_install_drag_monitor()
  }

  fun setAccessoryPolicy(accessory: Boolean) {
    lib?.trawl_mac_set_accessory_policy(if (accessory) 1 else 0)
  }

  /** Writes standardized NSURL objects to NSPasteboard.generalPasteboard for native paste into WhatsApp/Safari/Finder. */
  fun copyFiles(files: List<File>): Boolean {
    val l = lib ?: return false
    val paths = files.map { it.absolutePath }
    if (paths.isEmpty()) return false
    return l.trawl_mac_copy_files(StringArray(paths.toTypedArray(), "UTF-8"), paths.size) != 0
  }

  /**
   * Permanent JNA callback trampoline + volatile Kotlin listener so unregistering or replacing a hotkey
   * can NEVER let GC free the native libffi trampoline while a Carbon event or global-queue dispatch is in flight.
   */
  @Volatile private var hotkeyListener: (() -> Unit)? = null
  private val permanentHotkeyCb = HotkeyCb { runCatching { hotkeyListener?.invoke() } }
  @Volatile private var outsideClickListener: (() -> Unit)? = null
  private val permanentOutsideClickCb = OutsideClickCb { runCatching { outsideClickListener?.invoke() } }
  private val activeDragCbs = java.util.Collections.synchronizedSet(mutableSetOf<DragEndCb>())

  /** Returns 0 = ok, 1 = taken, negative = OSStatus error, null = dylib unavailable. */
  fun registerHotkey(keyCode: Int, carbonMods: Int, onPressed: () -> Unit): Int? {
    val l = lib ?: return null
    hotkeyListener = onPressed
    return l.trawl_mac_hotkey_register(keyCode, carbonMods, permanentHotkeyCb)
  }

  fun unregisterHotkey(): Boolean {
    val l = lib ?: return false
    hotkeyListener = null
    l.trawl_mac_hotkey_unregister()
    return true
  }

  fun systemHotkeys(): Set<Pair<Int, Int>>? {
    val l = lib ?: return null
    val buf = ByteArray(16_384)
    val n = l.trawl_mac_system_hotkeys(buf, buf.size)
    if (n <= 0) return emptySet()
    val text = String(buf, 0, n, Charsets.UTF_8)
    return text.lineSequence().mapNotNull { line ->
      val parts = line.trim().split(' ')
      if (parts.size == 2) (parts[0].toIntOrNull() ?: return@mapNotNull null) to (parts[1].toIntOrNull() ?: return@mapNotNull null)
      else null
    }.toSet()
  }

  fun configureMain(window: Window): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    l.trawl_mac_configure_main(ns)
    return true
  }

  fun configureOverlay(window: Window): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    l.trawl_mac_configure_overlay(ns)
    return true
  }

  fun raiseOverlay(window: Window, activateApp: Boolean = false): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    l.trawl_mac_raise_overlay(ns, if (activateApp) 1 else 0)
    return true
  }

  fun hideOverlay(window: Window): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    l.trawl_mac_hide_overlay(ns)
    return true
  }

  /** Lock-free check of whether macOS WindowServer currently has the Quick Panel NSPanel as keyWindow. */
  fun isOverlayKeyWindow(): Boolean = lib?.trawl_mac_is_overlay_key() == 1

  /** Returns the latest AppKit-side Quick Panel focus diagnostic snapshot (zero dispatch_sync). */
  fun overlayDiagnostics(): String {
    val l = lib ?: return ""
    val buf = ByteArray(512)
    val n = l.trawl_mac_overlay_diagnostics(buf, buf.size)
    return if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
  }

  fun setOutsideClickListener(onClickOutside: (() -> Unit)?) {
    val l = lib ?: return
    outsideClickListener = onClickOutside
    l.trawl_mac_set_outside_click_callback(if (onClickOutside != null) permanentOutsideClickCb else null)
  }

  /**
   * Sets OpenJDK 21's internal `CPlatformWindow.NONACTIVATING` style bit so `CPlatformWindow.setVisible(true)` and
   * `updateFocusableWindowState()` NEVER strip `NSWindowStyleMaskNonactivatingPanel` when mapping the Quick Panel
   * over another app's Full-Screen Space (e.g., Ghostty). Uses `--add-opens` already shipped in `Trawl.cfg`.
   */
  fun setJdkNonActivatingBit(window: Window): Boolean = runCatching {
    val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
    val accessorType = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
    val peer = accessorType.getMethod("getPeer", java.awt.Component::class.java).invoke(accessor, window) ?: return@runCatching false
    val pw = peer.javaClass.getMethod("getPlatformWindow").apply { isAccessible = true }.invoke(peer) ?: return@runCatching false
    val bit = pw.javaClass.getDeclaredField("NONACTIVATING").apply { isAccessible = true }.getInt(null)
    val setBits = pw.javaClass.getDeclaredMethod("setStyleBits", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
    setBits.invoke(pw, bit, true)
    true
  }.getOrDefault(false)

  /**
   * Bridges OpenJDK 21's focus routing for the macOS Quick Panel NSPanel:
   * 1. Calls `LWWindowPeer.notifyActivation(true, null)` if the peer is not yet marked active.
   * 2. Focuses the actual Skiko/Compose `Canvas` (`HardwareLayer` — which owns Compose's `KeyListener` and
   *    `InputMethodListener`), NEVER calling `window.requestFocusInWindow()` afterwards (which would steal
   *    AWT keyboard focus off the canvas back onto `ComposeWindow` and drop keystrokes/Esc until clicked).
   * 3. Runs a short follow-up check after AppKit's async activation settles (~45ms & ~120ms).
   */
  fun notifyAwtPanelActivated(window: Window): Boolean = runCatching {
    fun requestCanvasFocus() {
      if (!window.isVisible) return
      val canvas = findComposeCanvas(window)
      if (canvas != null) {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (owner !== canvas) {
          if (!canvas.requestFocusInWindow(java.awt.event.FocusEvent.Cause.MOUSE_EVENT)) {
            canvas.requestFocus(java.awt.event.FocusEvent.Cause.MOUSE_EVENT)
          }
        }
      } else {
        window.requestFocusInWindow(java.awt.event.FocusEvent.Cause.MOUSE_EVENT)
      }
    }
    fun activatePeerIfNeeded() {
      if (!window.isVisible || window.isFocused) return
      val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
      val accessorType = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
      val peer = accessorType.getMethod("getPeer", java.awt.Component::class.java).invoke(accessor, window) ?: return
      val notify = peer.javaClass.getMethod("notifyActivation", Boolean::class.javaPrimitiveType, peer.javaClass)
      notify.isAccessible = true
      notify.invoke(peer, true, null)
    }
    activatePeerIfNeeded()
    // Queue AFTER WINDOW_GAINED_FOCUS posted by notifyActivation so AWT accepts focus ownership on the Compose canvas.
    javax.swing.SwingUtilities.invokeLater { requestCanvasFocus() }
    for (delayMs in intArrayOf(45, 120)) {
      javax.swing.Timer(delayMs) {
        if (window.isVisible && (!window.isFocused || window.focusOwner !is java.awt.Canvas)) {
          runCatching { activatePeerIfNeeded() }
          javax.swing.SwingUtilities.invokeLater { runCatching { requestCanvasFocus() } }
        }
      }.apply { isRepeats = false; start() }
    }
    true
  }.getOrDefault(false)

  /**
   * Locates the Skiko `HardwareLayer` (`java.awt.Canvas`) inside a `ComposeWindow`. That canvas owns Compose's
   * `KeyListener`; handing AWT focus to a parent `JFrame` or `JPanel` drops keystrokes until the user clicks.
   */
  internal fun findComposeCanvas(window: Window): java.awt.Component? =
    findComposeCanvasIn(window) ?: window.focusTraversalPolicy?.getInitialComponent(window) ?: findDeepestFocusableLeaf(window)

  internal fun findComposeCanvasIn(container: java.awt.Container): java.awt.Component? =
    findCanvas(container) ?: findByClassName(container, "org.jetbrains.skiko.SkiaLayer")

  private fun findCanvas(c: java.awt.Container): java.awt.Component? {
    for (child in c.components) {
      if (child is java.awt.Canvas && child.isVisible && child.isFocusable && child.isEnabled) return child
      if (child is java.awt.Container) {
        findCanvas(child)?.let { return it }
      }
    }
    return null
  }

  private fun findByClassName(c: java.awt.Container, name: String): java.awt.Component? {
    for (child in c.components) {
      if (child.javaClass.name == name && child.isVisible && child.isFocusable && child.isEnabled) return child
      if (child is java.awt.Container) {
        findByClassName(child, name)?.let { return it }
      }
    }
    return null
  }

  private fun findDeepestFocusableLeaf(c: java.awt.Container): java.awt.Component? {
    for (child in c.components) {
      if (child is java.awt.Container) {
        findDeepestFocusableLeaf(child)?.let { return it }
      }
      if (child.isVisible && child.isFocusable && child.isEnabled) return child
    }
    return null
  }

  fun raiseMain(window: Window): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    l.trawl_mac_raise_main(ns)
    return true
  }

  fun setSharingType(window: Window, allowCapture: Boolean): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    l.trawl_mac_set_sharing_type(ns, if (allowCapture) 1 else 0)
    return true
  }

  /**
   * Starts a genuine macOS Cocoa `NSDraggingSession` carrying `NSURL` (`public.file-url`) items.
   * Returns true if the native session was started; false falls back to AWT DragSource.
   */
  fun startFileDrag(window: Window, files: List<File>, pngBytes: ByteArray?, onEnd: (Boolean) -> Unit): Boolean {
    val l = lib ?: return false
    val ns = SecureWindow.nsWindowOf(window) ?: return false
    val paths = files.map { it.absolutePath }
    if (paths.isEmpty()) return false
    lateinit var cb: DragEndCb
    cb = DragEndCb { accepted ->
      activeDragCbs.remove(cb)
      runCatching { onEnd(accepted != 0) }
    }
    activeDragCbs += cb
    val started = l.trawl_mac_start_file_drag(ns, StringArray(paths.toTypedArray(), "UTF-8"), paths.size, pngBytes, pngBytes?.size ?: 0, cb)
    if (started == 0) {
      activeDragCbs.remove(cb)
      return false
    }
    return true
  }
}
