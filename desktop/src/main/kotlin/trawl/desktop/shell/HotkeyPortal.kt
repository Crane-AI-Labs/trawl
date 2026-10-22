package trawl.desktop.shell

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Linux on Wayland: the xdg-desktop-portal GlobalShortcuts interface (org.freedesktop.portal.GlobalShortcuts, version 1 and 2).
 *
 * A Wayland app cannot grab a key itself, so it asks the desktop: CreateSession, then BindShortcuts with one shortcut (id "quick") and a
 * *preferred* trigger. The desktop decides: GNOME and KDE show their own window the first time, and the trigger the person ends up with is
 * theirs, reported back as `trigger_description`. Later runs bind silently. Each press arrives as the `Activated` signal.
 * Desktops without the interface (wlroots/Sway, GTK, COSMIC at the time of writing) answer "no such interface" and this class returns
 * [HotkeyResult.Unsupported], so Trawl keeps the tray and `trawl --quick` fallback and says so.
 *
 * An unsandboxed app has no app id the portal can see, and GlobalShortcuts is per app, so the connection first calls
 * org.freedesktop.host.portal.Registry.Register with the basename of Trawl's .desktop file. Where the portal predates the Registry
 * the call fails and is ignored; the bind then either works or the portal's own error is shown.
 */
class HotkeyPortal(
  private val env: Map<String, String> = System.getenv(),
  private val appId: String = env["TRAWL_PORTAL_APP_ID"]?.takeIf { it.isNotBlank() } ?: DEFAULT_APP_ID,
  /** How long to wait for the person to answer the desktop's own shortcut window. */
  private val dialogTimeoutMs: Long = 120_000,
  private val busAddress: () -> String? = { DBusConnection.sessionAddress(env) },
) : GlobalHotkey {
  @Volatile private var conn: DBusConnection? = null
  @Volatile private var sessionHandle: String? = null

  /** What the desktop said the shortcut is, or null before a successful bind. */
  @Volatile var triggerDescription: String? = null
    private set

  @Synchronized
  override fun register(h: Hotkey, onPressed: () -> Unit): HotkeyResult {
    unregister()
    val addr = busAddress() ?: return HotkeyResult.Unsupported(true, "no session bus")
    val c = try { DBusConnection.connect(addr) } catch (e: IOException) { return HotkeyResult.Unsupported(true, "cannot reach the session bus (${e.message})") }
    try {
      // Unsandboxed apps tell the portal who they are. Errors (an older portal, already registered) are not fatal.
      runCatching { c.call(PORTAL, PATH, "org.freedesktop.host.portal.Registry", "Register", "sa{sv}", listOf(appId, emptyMap<String, Variant>())) }

      val version = try {
        (c.call(PORTAL, PATH, "org.freedesktop.DBus.Properties", "Get", "ss", listOf(IFACE, "version"))[0] as Variant).value as Long
      } catch (e: DBusError) {
        c.close()
        return HotkeyResult.Unsupported(true, "this desktop's portal has no global shortcuts (${e.name})")
      }

      val responses = ConcurrentHashMap<String, CompletableFuture<Pair<Long, Map<*, *>>>>()
      c.onSignal { m ->
        when {
          m.iface == "org.freedesktop.portal.Request" && m.member == "Response" ->
            m.path?.let { responses.remove(it) }?.complete((m.body[0] as Long) to (m.body[1] as Map<*, *>))
          m.iface == IFACE && m.member == "Activated" && m.body.getOrNull(0) == sessionHandle && m.body.getOrNull(1) == SHORTCUT_ID -> onPressed()
        }
      }
      c.addMatch("type='signal',interface='org.freedesktop.portal.Request',member='Response'")
      c.addMatch("type='signal',interface='$IFACE',member='Activated'")

      /** One portal request: the Response signal can race the method reply, so the wait is registered before the call. */
      fun request(timeoutMs: Long, method: (token: String) -> String): Pair<Long, Map<*, *>> {
        val token = "trawl_" + java.lang.Long.toHexString(System.nanoTime()) + java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() ushr 20)
        val expected = requestPath(c.uniqueName, token)
        val f = CompletableFuture<Pair<Long, Map<*, *>>>()
        responses[expected] = f
        val actual = method(token)
        if (actual != expected) responses.remove(expected)?.let { responses[actual] = it }   // a portal that picks its own handle
        return try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: TimeoutException) { throw DBusError("trawl.Timeout", "the desktop did not answer in time") }
      }

      val (code, results) = request(10_000) { token ->
        c.call(PORTAL, PATH, IFACE, "CreateSession", "a{sv}", listOf(mapOf("handle_token" to Variant.s(token), "session_handle_token" to Variant.s("${token}_s"))))[0] as String
      }
      if (code != 0L) { c.close(); return HotkeyResult.Failed("the desktop refused a shortcut session (code $code)") }
      val session = (results["session_handle"] as Variant).value as String
      sessionHandle = session

      val trigger = portalTrigger(h)
      val (bindCode, bound) = request(dialogTimeoutMs) { token ->
        c.call(
          PORTAL, PATH, IFACE, "BindShortcuts", "oa(sa{sv})sa{sv}",
          listOf(
            session,
            listOf(listOf(SHORTCUT_ID, mapOf("description" to Variant.s("Open Trawl quick search"), "preferred_trigger" to Variant.s(trigger)))),
            "",
            mapOf("handle_token" to Variant.s(token)),
          ),
          timeoutMs = 10_000,
        )[0] as String
      }
      when (bindCode) {
        0L -> {}
        1L -> { close(c, session); return HotkeyResult.Failed("you closed the desktop's shortcut window without choosing one") }
        else -> { close(c, session); return HotkeyResult.Failed("the desktop could not bind the shortcut (code $bindCode)") }
      }
      @Suppress("UNCHECKED_CAST")
      val shortcuts = (bound["shortcuts"] as? Variant)?.value as? List<List<Any?>> ?: emptyList()
      val mine = shortcuts.firstOrNull { it[0] == SHORTCUT_ID }
      val desc = (((mine?.get(1) as? Map<*, *>)?.get("trigger_description") as? Variant)?.value as? String)?.takeIf { it.isNotBlank() }
      triggerDescription = desc
      conn = c
      return HotkeyResult.Portal(desc, version)
    } catch (e: DBusError) {
      c.close(); sessionHandle = null
      return HotkeyResult.Failed(e.message ?: e.name)
    } catch (e: IOException) {
      c.close(); sessionHandle = null
      return HotkeyResult.Failed(e.message ?: "the D-Bus connection failed")
    }
  }

  private fun close(c: DBusConnection, session: String) {
    runCatching { c.call(PORTAL, session, "org.freedesktop.portal.Session", "Close", timeoutMs = 1_000) }
    c.close()
    sessionHandle = null
  }

  /**
   * Opens the desktop's own shortcut settings for Trawl (ConfigureShortcuts, portal version 2). False when there is no session or the
   * desktop has only version 1; the caller then points the person at the system keyboard settings.
   */
  @Synchronized
  fun configure(): Boolean {
    val c = conn ?: return false
    val s = sessionHandle ?: return false
    return runCatching { c.call(PORTAL, PATH, IFACE, "ConfigureShortcuts", "osa{sv}", listOf(s, "", emptyMap<String, Variant>())); true }.getOrDefault(false)
  }

  @Synchronized
  override fun unregister() {
    val c = conn ?: return
    conn = null
    triggerDescription = null
    sessionHandle?.let { close(c, it) } ?: c.close()
  }

  companion object {
    const val PORTAL = "org.freedesktop.portal.Desktop"
    const val PATH = "/org/freedesktop/portal/desktop"
    const val IFACE = "org.freedesktop.portal.GlobalShortcuts"
    const val SHORTCUT_ID = "quick"

    /** The basename of the .desktop file jpackage writes for the .deb (package "trawl", launcher "Trawl"). Override with TRAWL_PORTAL_APP_ID. */
    const val DEFAULT_APP_ID = "trawl-Trawl"

    /** The Request object a call with [token] will answer on: the caller's unique name without its colon, dots as underscores. */
    fun requestPath(uniqueName: String, token: String) = "/org/freedesktop/portal/desktop/request/${uniqueName.removePrefix(":").replace('.', '_')}/$token"

    /** A [Hotkey] as the Shortcuts specification writes a trigger: CTRL, ALT, SHIFT, LOGO, then an xkb key name ("CTRL+ALT+space"). */
    fun portalTrigger(h: Hotkey): String {
      val mods = buildList {
        if (Mod.CTRL in h.mods) add("CTRL")
        if (Mod.ALT in h.mods) add("ALT")
        if (Mod.SHIFT in h.mods) add("SHIFT")
        if (Mod.META in h.mods) add("LOGO")
      }
      val key = if (h.key == "SPACE") "space" else if (h.key.length == 1) h.key.lowercase() else h.key
      return (mods + key).joinToString("+")
    }

    /** True on a session that says it is Wayland (login manager variables). */
    fun isWayland(env: Map<String, String> = System.getenv()): Boolean =
      env["XDG_SESSION_TYPE"].equals("wayland", true) || !env["WAYLAND_DISPLAY"].isNullOrBlank()
  }
}
