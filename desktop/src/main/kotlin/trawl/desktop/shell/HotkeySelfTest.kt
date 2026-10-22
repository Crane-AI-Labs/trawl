package trawl.desktop.shell

import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import trawl.desktop.Os

/**
 * A command-line check of the Windows shell pieces on a real desktop session (no window, no app data, no index touched). It exists so the
 * hotkey, the shortcut conflict path, the tray and the second-launch hand-over can be proven on a machine we do not own a screen for:
 *
 *   java -cp ... trawl.desktop.shell.HotkeySelfTest hold <spec> <seconds>   another process that owns a key, as another app would
 *   java -cp ... trawl.desktop.shell.HotkeySelfTest run [<spec of a key a holder owns>]
 *   java -cp ... trawl.desktop.shell.HotkeySelfTest tray
 *   java -cp ... trawl.desktop.shell.HotkeySelfTest instance <dir> first <seconds> | second <dir>
 *
 * Every line it prints starts with "RESULT". It is not reachable from the app.
 */
object HotkeySelfTest {
  private interface User32 : Library { fun keybd_event(vk: Byte, scan: Byte, flags: Int, extra: Long) }

  private fun say(s: String) = println("RESULT $s")

  @JvmStatic fun main(args: Array<String>) {
    when (args.firstOrNull()) {
      "hold" -> hold(args[1], args.getOrNull(2)?.toLong() ?: 60)
      "run" -> run(args.getOrNull(1))
      "tray" -> tray()
      "instance" -> instance(args)
      else -> say("usage: hold <spec> <s> | run [heldSpec] | tray | instance <dir> first <s> | instance <dir> second")
    }
    System.out.flush()
    Runtime.getRuntime().halt(0)
  }

  private fun hold(spec: String, seconds: Long) {
    val h = Hotkey.parse(spec) ?: return say("hold: cannot parse $spec")
    val r = HotkeyWindows().register(h) {}
    say("hold ${h.label(Os.WINDOWS)} -> $r")
    Thread.sleep(seconds * 1000)
  }

  /** Presses and releases a chord with real synthetic input, as a person's hands would. */
  private fun press(h: Hotkey) {
    val u = Native.load("user32", User32::class.java)
    val vks = h.mods.map { when (it) { Mod.CTRL -> 0x11; Mod.ALT -> 0x12; Mod.SHIFT -> 0x10; Mod.META -> 0x5B } } + HotkeyCodes.winVk(h.key)
    for (v in vks) u.keybd_event(v.toByte(), 0, 0, 0)
    Thread.sleep(60)
    for (v in vks.reversed()) u.keybd_event(v.toByte(), 0, 2, 0)
  }

  private fun run(held: String?) {
    trawl.desktop.Strings.use(java.util.Locale.ENGLISH)
    val os = Os.WINDOWS
    val pressed = CountDownLatch(1)
    val svc = HotkeyService(os, HotkeyWindows()) { pressed.countDown() }
    svc.apply(ShellPrefs())
    val st = svc.status.value
    say("default order: ${Hotkey.candidates(os).joinToString(" , ") { it.label(os) }}")
    say("status after apply: $st")
    val active = svc.active
    say("active: ${active?.label(os)}")
    if (active != null) {
      val t0 = System.nanoTime()
      press(active)
      val got = pressed.await(3, TimeUnit.SECONDS)
      say("press of ${active.label(os)} delivered=$got in ${(System.nanoTime() - t0) / 1_000_000} ms")
      // Another key must not fire it.
      val h2 = HotkeyWindows()
      say("a different, free key registers: ${h2.register(Hotkey.parse("ctrl+alt+shift+f9")!!) {}}")
      h2.close()
      // Our own duplicate: the same key from a second registration in this process is refused.
      val dup = HotkeyWindows().register(active) {}
      say("same key registered again: $dup")
    }
    if (held != null) {
      val h = Hotkey.parse(held)!!
      val chk = svc.probe(h)
      say("recorder check of ${h.label(os)} (held by another process): $chk")
      say("shortcut after a refused change: ${svc.active?.label(os)}")
      val msg = ShortcutMessages.forCheck(chk, os)
      say("message shown: $msg")
    }
    val needMod = ShortcutRecorder.check(Hotkey.parse("space"), os) { HotkeyResult.Ok }
    say("a bare Space: $needMod")
    svc.close()
  }

  private fun tray() {
    say("headless=${java.awt.GraphicsEnvironment.isHeadless()} supported=${java.awt.SystemTray.isSupported()}")
    if (!java.awt.SystemTray.isSupported()) return
    val tray = java.awt.SystemTray.getSystemTray()
    val img = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics(); g.color = java.awt.Color(0xFFD400); g.fillOval(2, 2, 28, 28); g.dispose()
    val menu = java.awt.PopupMenu()
    for (name in listOf("Open Trawl", "Quick search", "Pause reading", "Settings", "Quit")) menu.add(java.awt.MenuItem(name))
    val icon = java.awt.TrayIcon(img, "Trawl", menu)
    icon.isImageAutoSize = true
    tray.add(icon)
    say("tray icons now: ${tray.trayIcons.size}, tray icon size ${tray.trayIconSize}")
    icon.displayMessage("Trawl", "Your files are read. Search them from here.", java.awt.TrayIcon.MessageType.NONE)
    Thread.sleep(4000)
    tray.remove(icon)
    say("tray icons after remove: ${tray.trayIcons.size}")
  }

  private fun instance(a: Array<String>) {
    val dir = File(a[1])
    when (a[2]) {
      "first" -> {
        val out = SingleInstanceShell.acquire(dir)
        if (out !is SingleInstanceShell.Outcome.First) return say("first: not first: $out")
        val got = CountDownLatch(1)
        out.instance.listen { cmd -> say("first heard: $cmd"); got.countDown() }
        say("first: listening on ${SingleInstanceShell.socketFor(dir)}")
        Thread.sleep((a.getOrNull(3)?.toLong() ?: 20) * 1000)
        out.instance.close()
      }
      "second" -> say("second: ${SingleInstanceShell.acquire(dir, "show")}")
    }
  }
}
