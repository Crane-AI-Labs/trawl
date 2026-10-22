package trawl.desktop.shell

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import trawl.desktop.Os
import trawl.desktop.Strings

class HotkeyPortalPureTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  @Test fun aHotkeyIsWrittenAsAShortcutsSpecTrigger() {
    assertEquals("CTRL+SHIFT+space", HotkeyPortal.portalTrigger(Hotkey(setOf(Mod.CTRL, Mod.SHIFT), "SPACE")))
    assertEquals("CTRL+ALT+SHIFT+LOGO+k", HotkeyPortal.portalTrigger(Hotkey(setOf(Mod.META, Mod.SHIFT, Mod.ALT, Mod.CTRL), "K")))
    assertEquals("ALT+F7", HotkeyPortal.portalTrigger(Hotkey(setOf(Mod.ALT), "F7")))
    assertEquals("LOGO+5", HotkeyPortal.portalTrigger(Hotkey(setOf(Mod.META), "5")))
  }

  @Test fun theRequestPathDropsTheColonAndTurnsDotsIntoUnderscores() {
    assertEquals("/org/freedesktop/portal/desktop/request/1_42/tok", HotkeyPortal.requestPath(":1.42", "tok"))
  }

  @Test fun waylandIsRecognisedFromTheSessionVariables() {
    assertTrue(HotkeyPortal.isWayland(mapOf("XDG_SESSION_TYPE" to "wayland")))
    assertTrue(HotkeyPortal.isWayland(mapOf("WAYLAND_DISPLAY" to "wayland-0")))
    assertFalse(HotkeyPortal.isWayland(mapOf("XDG_SESSION_TYPE" to "x11", "DISPLAY" to ":0")))
    assertFalse(HotkeyPortal.isWayland(emptyMap()))
  }

  @Test fun noSessionBusMeansUnsupportedNotAnError() {
    val r = HotkeyPortal(env = emptyMap(), busAddress = { null }).register(Hotkey.default(Os.LINUX)) {}
    assertEquals(true, assertIs<HotkeyResult.Unsupported>(r).wayland)
  }

  @Test fun anAbstractOnlyBusAddressIsUnsupportedToo() {
    val r = HotkeyPortal(env = emptyMap(), busAddress = { "unix:abstract=/tmp/dbus-x" }).register(Hotkey.default(Os.LINUX)) {}
    assertIs<HotkeyResult.Unsupported>(r)
  }

  @Test fun aPortalResultBecomesAStatusThatTheDesktopOwns() {
    val n = FakeNative(always = HotkeyResult.Portal("Press Ctrl+Alt+Space", 2))
    val s = HotkeyService(Os.LINUX, n) {}.also { it.apply(ShellPrefs()) }
    assertEquals(HotkeyStatus.Portal("Press Ctrl+Alt+Space"), s.status.value)
    assertNull(s.active)
    assertEquals(1, n.log.count { it.startsWith("reg ") })
    val (msg, good) = ShortcutMessages.forStatus(s.status.value, Os.LINUX)
    assertTrue(good)
    assertTrue(msg.contains("Press Ctrl+Alt+Space"))
  }

  @Test fun aPortalWithNoTriggerYetSaysSoAndKeepsTheFallback() {
    val (msg, good) = ShortcutMessages.forStatus(HotkeyStatus.Portal(null), Os.LINUX)
    assertFalse(good)
    assertTrue(msg.contains("trawl --quick"))
  }

  @Test fun theWaylandFallbackStillNamesTheTrayAndTheCommand() {
    val msg = ShortcutMessages.forStatus(HotkeyStatus.Wayland, Os.LINUX).first
    assertTrue(msg.contains("trawl --quick"))
    assertTrue(msg.contains("tray"))
  }

  @Test fun theRecorderNeverClaimsAKeyOnAPortalDesktop() {
    val c = ShortcutRecorder.check(Hotkey.parse("ctrl+alt+j"), Os.LINUX) { HotkeyResult.Portal("x") }
    assertIs<ShortcutCheck.Failed>(c)
  }
}

/** A stand-in for xdg-desktop-portal on a private bus: just enough of Registry, Properties and GlobalShortcuts for one client. */
private class FakePortal(
  addr: String,
  private val version: Long = 2,
  private val hasInterface: Boolean = true,
  private val bindCode: Long = 0,
  private val description: String? = "Press Ctrl+Alt+Space",
  private val refuseRegister: Boolean = false,
) : AutoCloseable {
  val conn = DBusConnection.connect(addr)
  val calls = ConcurrentLinkedQueue<String>()
  @Volatile var appId: String? = null
  @Volatile var preferredTrigger: String? = null
  @Volatile var shortcutId: String? = null
  @Volatile var session: String? = null
  @Volatile var closedSession: String? = null

  init {
    conn.onMethodCall = { m -> handle(m) }
    conn.requestName(HotkeyPortal.PORTAL)
  }

  private fun requestHandle(m: DBusMessage, options: Map<*, *>): String =
    HotkeyPortal.requestPath(m.sender!!, (options["handle_token"] as Variant).value as String)

  private fun respond(path: String, code: Long, results: Map<String, Variant>) =
    conn.emitSignal(path, "org.freedesktop.portal.Request", "Response", "ua{sv}", listOf(code, results))

  private fun handle(m: DBusMessage): Pair<String, List<Any?>> {
    calls += "${m.iface}.${m.member}"
    when ("${m.iface}.${m.member}") {
      "org.freedesktop.host.portal.Registry.Register" -> {
        if (refuseRegister) throw DBusError("org.freedesktop.DBus.Error.UnknownMethod", "no registry")
        appId = m.body[0] as String
        return "" to emptyList()
      }
      "org.freedesktop.DBus.Properties.Get" -> {
        if (!hasInterface) throw DBusError("org.freedesktop.DBus.Error.UnknownInterface", "no such interface")
        return "v" to listOf(Variant.u(version))
      }
      "${HotkeyPortal.IFACE}.CreateSession" -> {
        val handle = requestHandle(m, m.body[0] as Map<*, *>)
        val token = ((m.body[0] as Map<*, *>)["session_handle_token"] as Variant).value as String
        val s = "/org/freedesktop/portal/desktop/session/${m.sender!!.removePrefix(":").replace('.', '_')}/$token"
        session = s
        Thread { Thread.sleep(40); respond(handle, 0, mapOf("session_handle" to Variant.s(s))) }.start()   // after the reply
        return "o" to listOf(handle)
      }
      "${HotkeyPortal.IFACE}.BindShortcuts" -> {
        val handle = requestHandle(m, m.body[3] as Map<*, *>)
        @Suppress("UNCHECKED_CAST") val shortcuts = m.body[1] as List<List<Any?>>
        shortcutId = shortcuts[0][0] as String
        preferredTrigger = ((shortcuts[0][1] as Map<*, *>)["preferred_trigger"] as Variant).value as String
        val granted = if (bindCode != 0L) emptyMap() else {
          val props = if (description == null) emptyMap() else mapOf("trigger_description" to Variant.s(description))
          mapOf("shortcuts" to Variant("a(sa{sv})", listOf(listOf(shortcutId, props))))
        }
        respond(handle, bindCode, granted)   // before the reply: the client must not lose a Response that beats it
        return "o" to listOf(handle)
      }
      "${HotkeyPortal.IFACE}.ConfigureShortcuts" -> return "" to emptyList()
      "org.freedesktop.portal.Session.Close" -> { closedSession = m.path; return "" to emptyList() }
    }
    throw DBusError("org.freedesktop.DBus.Error.UnknownMethod", "${m.iface}.${m.member}")
  }

  fun activate(id: String = HotkeyPortal.SHORTCUT_ID, sessionPath: String? = session) =
    conn.emitSignal(HotkeyPortal.PATH, HotkeyPortal.IFACE, "Activated", "osta{sv}", listOf(sessionPath, id, 1234L, emptyMap<String, Variant>()))

  override fun close() = conn.close()
}

/** The client against a real `dbus-daemon` on a private socket and the fake portal above. Skipped where dbus-daemon is not installed. */
class HotkeyPortalBusTest {
  private var daemon: Process? = null
  private var dir: File? = null
  private lateinit var addr: String
  private val closeables = mutableListOf<AutoCloseable>()

  @BeforeTest fun start() {
    Strings.use(Locale.ENGLISH)
    val found = System.getenv("PATH").orEmpty().split(':').map { File(it, "dbus-daemon") }.firstOrNull { it.canExecute() }
    assumeTrue(found != null, "needs dbus-daemon")
    val d = Files.createTempDirectory("tbus").toFile().also { dir = it }
    val conf = File(d, "bus.conf")
    conf.writeText(
      """<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
         <busconfig><type>session</type><listen>unix:path=${d.path}/bus</listen><auth>EXTERNAL</auth>
         <policy context="default"><allow send_destination="*" eavesdrop="true"/><allow eavesdrop="true"/><allow own="*"/></policy></busconfig>""",
    )
    val p = ProcessBuilder(found!!.path, "--nofork", "--print-address", "--config-file=${conf.path}").redirectErrorStream(true).start()
    daemon = p
    val line = p.inputStream.bufferedReader().readLine()
    assumeTrue(line?.startsWith("unix:") == true, "dbus-daemon did not start")
    addr = line!!
  }

  @AfterTest fun stop() {
    closeables.forEach { runCatching { it.close() } }
    daemon?.destroy()
    daemon?.waitFor(5, TimeUnit.SECONDS)
    dir?.deleteRecursively()
  }

  private fun fake(
    version: Long = 2, hasInterface: Boolean = true, bindCode: Long = 0, description: String? = "Press Ctrl+Alt+Space", refuseRegister: Boolean = false,
  ) = FakePortal(addr, version, hasInterface, bindCode, description, refuseRegister).also { closeables += it }

  private fun portal(app: String = "trawl-Trawl") = HotkeyPortal(env = emptyMap(), appId = app, dialogTimeoutMs = 5_000, busAddress = { addr }).also { closeables += AutoCloseable { it.unregister() } }

  private val hk = Hotkey(setOf(Mod.CTRL, Mod.SHIFT), "SPACE")

  @Test fun createSessionBindShortcutsAndActivatedReachOnPressed() {
    val f = fake()
    val pressed = CountDownLatch(1)
    val p = portal()
    val r = p.register(hk) { pressed.countDown() }
    assertEquals(HotkeyResult.Portal("Press Ctrl+Alt+Space", 2), r)
    assertEquals("Press Ctrl+Alt+Space", p.triggerDescription)
    assertEquals("trawl-Trawl", f.appId)
    assertEquals("quick", f.shortcutId)
    assertEquals("CTRL+SHIFT+space", f.preferredTrigger)
    val order = f.calls.toList().filter { it.startsWith(HotkeyPortal.IFACE) }
    assertEquals(listOf("${HotkeyPortal.IFACE}.CreateSession", "${HotkeyPortal.IFACE}.BindShortcuts"), order)

    f.activate("somethingelse")
    f.activate(sessionPath = "/org/freedesktop/portal/desktop/session/other/x")
    assertFalse(pressed.await(300, TimeUnit.MILLISECONDS), "another shortcut or another session must not fire")
    f.activate()
    assertTrue(pressed.await(5, TimeUnit.SECONDS), "Activated did not reach onPressed")
  }

  @Test fun anOlderPortalWithoutTheRegistryStillBinds() {
    fake(refuseRegister = true)
    val r = portal().register(hk) {}
    assertIs<HotkeyResult.Portal>(r)
  }

  @Test fun aDesktopThatChoosesNoTriggerYetIsStillAPortalResult() {
    fake(description = null)
    assertEquals(HotkeyResult.Portal(null, 2), portal().register(hk) {})
  }

  @Test fun dismissingTheDesktopsWindowIsAFailureInPlainWords() {
    fake(bindCode = 1)
    val r = portal().register(hk) {}
    assertTrue(assertIs<HotkeyResult.Failed>(r).detail.contains("closed"))
  }

  @Test fun aPortalWithoutTheShortcutsInterfaceIsUnsupported() {
    fake(hasInterface = false)
    val r = portal().register(hk) {}
    assertEquals(true, assertIs<HotkeyResult.Unsupported>(r).wayland)
  }

  @Test fun noPortalOnTheBusIsUnsupported() {
    val r = portal().register(hk) {}
    assertEquals(true, assertIs<HotkeyResult.Unsupported>(r).wayland)
  }

  @Test fun unregisterClosesTheSessionAndStopsActivations() {
    val f = fake()
    val pressed = CountDownLatch(1)
    val p = portal()
    p.register(hk) { pressed.countDown() }
    val session = f.session
    p.unregister()
    val deadline = System.currentTimeMillis() + 3_000
    while (f.closedSession == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals(session, f.closedSession)
    assertFalse(pressed.await(200, TimeUnit.MILLISECONDS))
  }

  @Test fun configureOpensTheDesktopsOwnShortcutSettings() {
    val f = fake()
    val p = portal()
    assertFalse(p.configure())   // no session yet
    p.register(hk) {}
    assertTrue(p.configure())
    assertTrue(f.calls.contains("${HotkeyPortal.IFACE}.ConfigureShortcuts"))
  }

  @Test fun theServiceReportsTheDesktopsTriggerThroughTheWholeStack() {
    fake()
    val s = HotkeyService(Os.LINUX, portal()) {}
    s.apply(ShellPrefs())
    assertEquals(HotkeyStatus.Portal("Press Ctrl+Alt+Space"), s.status.value)
    assertTrue(ShortcutMessages.forStatus(s.status.value, Os.LINUX).first.contains("Press Ctrl+Alt+Space"))
  }

  @Test fun theConnectionAuthenticatesAndGetsAUniqueName() {
    val c = DBusConnection.connect(addr).also { closeables += it }
    assertTrue(c.uniqueName.startsWith(":1."), c.uniqueName)
    val names = c.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames")[0] as List<*>
    assertTrue("org.freedesktop.DBus" in names)
  }
}

/** Against a portal stand-in written by someone else (the dbus-python fake in a private session bus), so the client is not only tested against its own fake. */
class HotkeyPortalExternalTest {
  @Test fun anExternalPortalBindsAndItsActivationsReachOnPressed() {
    assumeTrue(System.getenv("TRAWL_EXTERNAL_PORTAL_TEST") == "1", "set TRAWL_EXTERNAL_PORTAL_TEST=1 with DBUS_SESSION_BUS_ADDRESS pointing at the fake")
    val pressed = CountDownLatch(1)
    val p = HotkeyPortal(dialogTimeoutMs = 10_000)
    try {
      val r = p.register(Hotkey(setOf(Mod.CTRL, Mod.ALT), "SPACE")) { pressed.countDown() }
      assertEquals(HotkeyResult.Portal("Press CTRL+ALT+space", 2), r)
      assertTrue(pressed.await(10, TimeUnit.SECONDS), "no Activated reached onPressed")
    } finally { p.unregister() }
  }
}
