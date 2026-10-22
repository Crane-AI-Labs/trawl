package trawl.desktop.ui.quick

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import trawl.desktop.shell.PanelRaiser
import trawl.desktop.shell.QuickPanelSizing
import trawl.desktop.shell.Shell
import trawl.desktop.t
import trawl.desktop.ui.Brand
import trawl.desktop.ui.SunPanel
import trawl.desktop.ui.SunlightTheme

/** Whether this desktop can draw a window with see-through corners and a shadow (not on X11 without a compositor); if not, the panel is square. */
private val seeThrough: Boolean by lazy {
  runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
  }.getOrDefault(false) && System.getenv("TRAWL_PANEL_SQUARE").isNullOrBlank()
}

/**
 * The quick-search panel as its own small window: no frame, above other windows, never in the taskbar's way, closed by Esc, by opening a
 * file, by the shortcut again, or by clicking anywhere else (it closes when it loses focus, once it has had it).
 * It is always composed and only hidden, so showing it is instant.
 */
@Composable
fun QuickPanelWindow(shell: Shell) {
  val visible by shell.panelVisible.collectAsState()
  val raise by shell.panelRaise.collectAsState()
  val settings by shell.controller.settings.collectAsState()
  val status by shell.hotkey.status.collectAsState()
  // The window is the panel plus a transparent margin for the shadow (SunPanel in ui/Theme.kt holds the numbers). Its size follows the screen it
  // opens on and whether the preview is open (QuickPanelSizing): a list of results is Spotlight-wide, a preview widens it.
  val inset = if (seeThrough) SunPanel.ShadowInset else 0.dp
  val previewing by shell.panel.previewing.collectAsState()
  var screen by remember { mutableStateOf<java.awt.Rectangle?>(null) }
  val fallback = remember { QuickPanelSizing.of(1440, 900, false) }
  val size = screen?.let { QuickPanelSizing.of(it.width, it.height, previewing) } ?: fallback
  val state = rememberWindowState(size = DpSize(size.width.dp + inset * 2, size.height.dp + inset * 2), position = WindowPosition.PlatformDefault)
  val privacySettings by shell.controller.privacy.settings.collectAsState()
  val states by shell.controller.addOns.states.collectAsState()
  val blurOn = privacySettings.blurEnabled && states[com.craneai.findr.core.PackIds.PRIVACY] is trawl.desktop.AddOnState.Installed && trawl.desktop.privacy.PrivacyRuntime.usable()
  val label = shell.hotkey.active?.label(shell.os)   // read after `status` so a changed shortcut redraws the footer
  var kick by remember { mutableIntStateOf(0) }
  val raiser = remember { PanelRaiser.forWindow(shell.os, onSettled = { n, ok -> shell.controller.appLog.log("quick panel raise: $n attempts, focused ${if (ok) "yes" else "no"}") }) }
  Window(
    onCloseRequest = shell::hidePanel, state = state, visible = visible, title = t("quick_title"),
    undecorated = true, transparent = seeThrough, alwaysOnTop = true, resizable = false, focusable = true,
  ) {
    val armed = remember { booleanArrayOf(false) }
    // Shown: pick the screen the mouse is on, then size and place the window on it, then raise it and win the keyboard (PanelRaiser retries
    // with stronger steps if the first ones did not take the focus).
    LaunchedEffect(raise, visible) {
      if (!visible) { raiser.cancel(); return@LaunchedEffect }
      armed[0] = false
      screen = runCatching { MouseInfo.getPointerInfo().device.defaultConfiguration.bounds }.getOrNull()
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
      placeNow(state, screen!!, QuickPanelSizing.of(screen!!.width, screen!!.height, shell.panel.previewing.value), inset)
      raiser.raise(window)
    }
    // The preview opening or closing changes the window's size: keep it centred and on screen.
    LaunchedEffect(screen, previewing) { screen?.let { placeNow(state, it, size, inset) } }
    DisposableEffect(window) {
      val l = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent?) { armed[0] = true; kick++; shell.controller.privacy.onWindowFocus("panel", true) }
        // Quick Look (the system preview, a separate program's window) takes the focus on purpose: the panel waits behind it.
        override fun windowLostFocus(e: WindowEvent?) { shell.controller.privacy.onWindowFocus("panel", false); if (armed[0] && !shell.autoHideSuspended) shell.hidePanel() }
      }
      window.addWindowFocusListener(l)
      onDispose { window.removeWindowFocusListener(l) }
    }
    SunlightTheme(settings) {
      Box { QuickPanel(shell.panel, shell.os, if (status is trawl.desktop.shell.HotkeyStatus.Active) label else null, focusNonce = raise, focusKick = kick, inset = inset, blurOn = blurOn, onDismiss = shell::hidePanel) }
    }
  }
}

/** Sets the window's size and puts it centred in the upper part of [screen], inside the transparent margin the shadow needs. */
private fun placeNow(state: androidx.compose.ui.window.WindowState, screen: java.awt.Rectangle, size: QuickPanelSizing.Size, inset: androidx.compose.ui.unit.Dp) {
  val w = size.width + inset.value.toInt() * 2
  val h = size.height + inset.value.toInt() * 2
  state.size = DpSize(w.dp, h.dp)
  state.position = WindowPosition(
    x = QuickPanelSizing.left(screen.x, screen.width, w).dp,
    y = (QuickPanelSizing.top(screen.y, screen.height, size.height) - inset.value.toInt()).dp,
  )
}

/**
 * The tray (Windows, Linux) or menu-bar (macOS) icon. Left-click or double-click opens Trawl; the menu has the five actions. Where the
 * desktop shows no tray icons nothing is composed, and the window's close button quits ([Shell.trayAvailable] is false).
 */
@Composable
fun ApplicationScope.ShellTray(shell: Shell) {
  if (!shell.trayAvailable) return
  val settings by shell.controller.settings.collectAsState()
  val status by shell.hotkey.status.collectAsState()
  val icon = remember { BitmapPainter(Brand.icon(64).toComposeImageBitmap()) }
  val key = if (status is trawl.desktop.shell.HotkeyStatus.Active) shell.hotkey.active?.label(shell.os) else null
  Tray(
    icon = icon, state = shell.trayState, tooltip = t("tray_tooltip"), onAction = shell::showMain,
    menu = {
      Item(t("tray_open"), onClick = shell::showMain)
      Item(if (key != null) t("tray_quick_with", key) else t("tray_quick"), onClick = shell::showPanel)
      Item(if (settings.indexPaused) t("tray_resume") else t("tray_pause"), onClick = shell::togglePause)
      Item(t("tray_settings"), onClick = shell::openSettings)
      Separator()
      Item(t("tray_quit"), onClick = shell::quit)
    },
  )
}
