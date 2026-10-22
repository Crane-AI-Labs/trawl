package trawl.desktop.shell

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Windows: user32 RegisterHotKey. No hook, no admin rights, no permission prompt. A hotkey belongs to the thread that registered it and is
 * delivered to that thread's message queue as WM_HOTKEY, so each registration gets its own thread running a plain GetMessage loop;
 * unregistering posts WM_QUIT to it. RegisterHotKey fails with ERROR_HOTKEY_ALREADY_REGISTERED (1409) when another app holds the key,
 * which is exactly the conflict test the Settings recorder needs. MOD_NOREPEAT stops a held key from firing again and again.
 */
class HotkeyWindows : GlobalHotkey {
  @Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY")
  class Msg : Structure() {
    @JvmField var hwnd: Pointer? = null
    @JvmField var message: Int = 0
    @JvmField var wParam: Pointer? = null
    @JvmField var lParam: Pointer? = null
    @JvmField var time: Int = 0
    @JvmField var ptX: Int = 0
    @JvmField var ptY: Int = 0
  }

  interface User32 : Library {
    fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun GetMessageW(msg: Msg, hWnd: Pointer?, min: Int, max: Int): Int
    fun PeekMessageW(msg: Msg, hWnd: Pointer?, min: Int, max: Int, remove: Int): Boolean
    fun PostThreadMessageW(threadId: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
  }

  interface Kernel32 : Library { fun GetCurrentThreadId(): Int }

  private val user32: User32? = runCatching { Native.load("user32", User32::class.java) }.getOrNull()
  private val kernel32: Kernel32? = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()

  @Volatile private var thread: Thread? = null
  @Volatile private var threadId = 0

  @Synchronized
  override fun register(h: Hotkey, onPressed: () -> Unit): HotkeyResult {
    val u = user32 ?: return HotkeyResult.Unsupported(false, "user32 is not available")
    val k = kernel32 ?: return HotkeyResult.Unsupported(false, "kernel32 is not available")
    unregister()
    val answer = CompletableFuture<HotkeyResult>()
    val t = Thread({
      val tid = k.GetCurrentThreadId()
      val msg = Msg()
      // Force this thread's message queue into existence before anyone can post to it.
      u.PeekMessageW(msg, null, WM_USER, WM_USER, PM_NOREMOVE)
      val ok = u.RegisterHotKey(null, ID, HotkeyCodes.winMods(h), HotkeyCodes.winVk(h.key))
      if (!ok) {
        val err = Native.getLastError()
        answer.complete(if (err == ERROR_HOTKEY_ALREADY_REGISTERED) HotkeyResult.Taken("error $err") else HotkeyResult.Failed("RegisterHotKey error $err"))
        return@Thread
      }
      threadId = tid
      answer.complete(HotkeyResult.Ok)
      try {
        while (u.GetMessageW(msg, null, 0, 0) > 0) {
          if (msg.message == WM_HOTKEY) runCatching(onPressed)
        }
      } finally {
        u.UnregisterHotKey(null, ID)
      }
    }, "trawl-hotkey")
    t.isDaemon = true
    t.start()
    val r = runCatching { answer.get(3, TimeUnit.SECONDS) }.getOrElse { HotkeyResult.Failed("no answer from Windows") }
    if (r == HotkeyResult.Ok) thread = t
    return r
  }

  @Synchronized
  override fun unregister() {
    val t = thread ?: return
    user32?.PostThreadMessageW(threadId, WM_QUIT, null, null)
    runCatching { t.join(2000) }
    thread = null
    threadId = 0
  }

  private companion object {
    const val ID = 0x7a77
    const val WM_HOTKEY = 0x0312
    const val WM_QUIT = 0x0012
    const val WM_USER = 0x0400
    const val PM_NOREMOVE = 0
    const val ERROR_HOTKEY_ALREADY_REGISTERED = 1409
  }
}
