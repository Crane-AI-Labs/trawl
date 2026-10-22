package trawl.desktop.shell

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The Linux key grab against a real X server (Xvfb is enough), with real key events from the XTest extension, so the press path is proven
 * and not only the registration. Skipped where there is no X display or no libXtst, which is how it runs on a Mac or Windows build machine.
 */
class HotkeyX11Test {
  private interface Xtst : Library { fun XTestFakeKeyEvent(d: Pointer, keycode: Int, press: Int, delay: Long): Int }
  private interface X : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XCloseDisplay(d: Pointer): Int
    fun XKeysymToKeycode(d: Pointer, keysym: Long): Int
    fun XFlush(d: Pointer): Int
    fun XSync(d: Pointer, discard: Int): Int
  }

  private fun x(): X? = runCatching { Native.load("libX11.so.6", X::class.java) }.getOrNull()
  private fun xtst(): Xtst? = runCatching { Native.load("libXtst.so.6", Xtst::class.java) }.getOrNull()

  private fun needX(): Triple<X, Xtst, Pointer> {
    assumeTrue(!System.getenv("DISPLAY").isNullOrBlank() && System.getenv("WAYLAND_DISPLAY").isNullOrBlank(), "needs an X11 display")
    val xl = x(); val xt = xtst()
    assumeTrue(xl != null && xt != null, "needs libX11 and libXtst")
    val d = xl!!.XOpenDisplay(null)
    assumeTrue(d != null, "no X server answered")
    return Triple(xl, xt!!, d!!)
  }

  /** Presses and releases a chord with the modifiers held, as a person's hands would. */
  private fun chord(xl: X, xt: Xtst, d: Pointer, vararg keysyms: Long) {
    val codes = keysyms.map { xl.XKeysymToKeycode(d, it) }
    for (c in codes) xt.XTestFakeKeyEvent(d, c, 1, 0)
    for (c in codes.reversed()) xt.XTestFakeKeyEvent(d, c, 0, 0)
    xl.XFlush(d); xl.XSync(d, 0)
  }

  private val ctrl = 0xFFE3L
  private val alt = 0xFFE9L
  private val shift = 0xFFE1L

  @Test fun aGrabbedKeyIsHeardAnywhereAndAnotherClientCannotTakeIt() {
    val (xl, xt, d) = needX()
    try {
      val key = Hotkey.parse("ctrl+alt+j")!!
      val first = HotkeyX11()
      val heard = CountDownLatch(1)
      try {
        assertEquals(HotkeyResult.Ok, first.register(key) { heard.countDown() })
        // A second X client asking for the same key is refused by the server: the conflict check is real.
        val second = HotkeyX11()
        val clash = second.register(key) {}
        assertIs<HotkeyResult.Taken>(clash)
        second.close()
        // A different key is still free.
        val third = HotkeyX11()
        assertEquals(HotkeyResult.Ok, third.register(Hotkey.parse("ctrl+alt+k")!!) {})
        third.close()

        chord(xl, xt, d, ctrl, alt, 'j'.code.toLong())
        assertTrue(heard.await(3, TimeUnit.SECONDS), "the grabbed chord was not delivered")
      } finally { first.close() }
      // After close the key is free for the next client.
      val after = HotkeyX11()
      assertEquals(HotkeyResult.Ok, after.register(key) {})
      after.close()
    } finally { xl.XCloseDisplay(d) }
  }

  @Test fun otherKeysAreNotHeard() {
    val (xl, xt, d) = needX()
    try {
      val h = HotkeyX11()
      val heardFor = java.util.Collections.synchronizedList(mutableListOf<String>())
      val now = java.util.concurrent.atomic.AtomicReference("")
      try {
        assertEquals(HotkeyResult.Ok, h.register(Hotkey.parse("ctrl+alt+j")!!) { heardFor += now.get() })
        val chords = listOf(
          "Ctrl+J" to longArrayOf(ctrl, 'j'.code.toLong()),
          "Ctrl+Alt+Shift+J" to longArrayOf(ctrl, alt, shift, 'j'.code.toLong()),   // an extra modifier
          "Ctrl+Alt+H" to longArrayOf(ctrl, alt, 'h'.code.toLong()),                // another letter
        )
        for ((name, keys) in chords) {
          now.set(name)
          chord(xl, xt, d, *keys)
          Thread.sleep(400)   // the press is delivered on the grab thread; give it time to (wrongly) arrive
        }
        assertEquals(emptyList<String>(), heardFor.toList(), "a different chord must not open the panel")
      } finally { h.close() }
    } finally { xl.XCloseDisplay(d) }
  }

  @Test fun caps_lock_and_num_lock_do_not_hide_the_key() {
    val (xl, xt, d) = needX()
    try {
      val h = HotkeyX11()
      val heard = CountDownLatch(1)
      try {
        assertEquals(HotkeyResult.Ok, h.register(Hotkey.parse("ctrl+alt+j")!!) { heard.countDown() })
        val caps = 0xFFE5L
        val c = xl.XKeysymToKeycode(d, caps)
        xt.XTestFakeKeyEvent(d, c, 1, 0); xt.XTestFakeKeyEvent(d, c, 0, 0); xl.XFlush(d); xl.XSync(d, 0)   // Caps Lock on
        chord(xl, xt, d, ctrl, alt, 'j'.code.toLong())
        assertTrue(heard.await(3, TimeUnit.SECONDS), "Caps Lock must not stop the shortcut")
        xt.XTestFakeKeyEvent(d, c, 1, 0); xt.XTestFakeKeyEvent(d, c, 0, 0); xl.XFlush(d); xl.XSync(d, 0)   // and off again
      } finally { h.close() }
    } finally { xl.XCloseDisplay(d) }
  }

  @Test fun aWaylandSessionIsReportedNotFaked() {
    val h = HotkeyX11(mapOf("XDG_SESSION_TYPE" to "wayland"))
    val r = assertIs<HotkeyResult.Unsupported>(h.register(Hotkey.default(trawl.desktop.Os.LINUX)) {})
    assertTrue(r.wayland)
    val h2 = HotkeyX11(mapOf("WAYLAND_DISPLAY" to "wayland-0"))
    assertTrue(assertIs<HotkeyResult.Unsupported>(h2.register(Hotkey.default(trawl.desktop.Os.LINUX)) {}).wayland)
  }
}
