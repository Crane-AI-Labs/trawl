package trawl.desktop.ui.quick

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
  // The window is the panel plus a transparent margin for the shadow (SunPanel in ui/Theme.kt holds the numbers).
  val inset = if (seeThrough) SunPanel.ShadowInset else 0.dp
  val winW = SunPanel.Width + inset * 2
  val winH = SunPanel.MaxHeight + inset * 2
  val state = rememberWindowState(size = DpSize(winW, winH), position = WindowPosition.PlatformDefault)
  val privacySettings by shell.controller.privacy.settings.collectAsState()
  val states by shell.controller.addOns.states.collectAsState()
  val blurOn = privacySettings.blurEnabled && states[com.craneai.findr.core.PackIds.PRIVACY] is trawl.desktop.AddOnState.Installed && trawl.desktop.privacy.PrivacyRuntime.usable()
  val label = shell.hotkey.active?.label(shell.os)   // read after `status` so a changed shortcut redraws the footer
  Window(
    onCloseRequest = shell::hidePanel, state = state, visible = visible, title = t("quick_title"),
    undecorated = true, transparent = seeThrough, alwaysOnTop = true, resizable = false, focusable = true,
  ) {
    val armed = remember { booleanArrayOf(false) }
    // Where it opens: upper third, centred, on the screen the mouse is on.
    LaunchedEffect(raise, visible) {
      if (!visible) return@LaunchedEffect
      armed[0] = false
      val bounds = runCatching { MouseInfo.getPointerInfo().device.defaultConfiguration.bounds }.getOrNull()
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
      state.position = WindowPosition(
        x = (bounds.x + (bounds.width - winW.value.toInt()) / 2).dp,
        y = (bounds.y + (bounds.height * 0.16).toInt() - inset.value.toInt()).dp,
      )
      bringForward(window)
    }
    DisposableEffect(window) {
      val l = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent?) { armed[0] = true; shell.controller.privacy.onWindowFocus("panel", true) }
        override fun windowLostFocus(e: WindowEvent?) { shell.controller.privacy.onWindowFocus("panel", false); if (armed[0]) shell.hidePanel() }
      }
      window.addWindowFocusListener(l)
      onDispose { window.removeWindowFocusListener(l) }
    }
    SunlightTheme(settings) {
      Box { QuickPanel(shell.panel, shell.os, if (status is trawl.desktop.shell.HotkeyStatus.Active) label else null, focusNonce = raise, inset = inset, blurOn = blurOn, onDismiss = shell::hidePanel) }
    }
  }
}

/** Brings an AWT window to the front and gives it the keyboard. On a Mac the app itself must be asked to come forward first. */
internal fun bringForward(window: java.awt.Window) {
  runCatching {
    if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
      java.awt.Desktop.getDesktop().requestForeground(true)
    }
  }
  window.toFront()
  window.requestFocus()
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
