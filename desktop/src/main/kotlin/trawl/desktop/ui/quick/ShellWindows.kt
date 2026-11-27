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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import javax.swing.SwingUtilities
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
  val secure by shell.controller.privacy.secureWindow.collectAsState()
  val states by shell.controller.addOns.states.collectAsState()
  val blurOn = privacySettings.blurEnabled && states[com.craneai.findr.core.PackIds.PRIVACY] is trawl.desktop.AddOnState.Installed && trawl.desktop.privacy.PrivacyRuntime.usable()
  val label = shell.hotkey.active?.label(shell.os)   // read after `status` so a changed shortcut redraws the footer
  var kick by remember { mutableIntStateOf(0) }
  var panelWindow by remember { mutableStateOf<java.awt.Window?>(null) }
  val raiser = remember {
    PanelRaiser.forWindow(
      shell.os,
      onSettled = { n, ok ->
        val w = panelWindow
        val nativeDiag = if (shell.os == trawl.desktop.Os.MAC && trawl.desktop.shell.MacNative.available) {
          trawl.desktop.shell.MacNative.overlayDiagnostics()
        } else ""
        val awtOwner = w?.focusOwner?.let { c ->
          c.javaClass.simpleName.ifEmpty { c.javaClass.superclass?.simpleName ?: c.javaClass.name.substringAfterLast('.') }
        } ?: "null"
        shell.controller.appLog.log(
          "quick panel raise: $n attempts, focused ${if (ok) "yes" else "no"} " +
            "[awt=${w?.isFocused} own=$awtOwner${if (nativeDiag.isNotEmpty()) " $nativeDiag" else ""}]",
        )
        // macOS async activation may finish after the initial focus request; hand AWT focus to the Compose canvas and bump focusKick.
        if (ok && shell.panelVisible.value) {
          w?.let { win ->
            if (shell.os == trawl.desktop.Os.MAC && trawl.desktop.shell.MacNative.available) {
              trawl.desktop.shell.MacNative.notifyAwtPanelActivated(win)
            } else {
              focusNonMacPanelCanvas(win)
            }
          }
          kick++
        }
      },
    )
  }
  val nativeMacOverlay = shell.os == trawl.desktop.Os.MAC && trawl.desktop.shell.MacNative.available
  val titleText = t("quick_title")
  val handleEscape: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
    // Window-level Escape handler guarantees Esc ALWAYS stops preview/playback and closes Quick Panel even when
    // a disposed preview control (e.g., Play button) left no focused child inside the Compose tree.
    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
      shell.panel.onKey(trawl.desktop.shell.QuickKey.CLOSE)
      true
    } else false
  }
  val panelBody: @Composable androidx.compose.ui.window.FrameWindowScope.() -> Unit = {
    val armed = remember { booleanArrayOf(false) }
    // Shown: pick the screen the mouse is on, place the window, wait one Compose frame so the native window and focus tree exist,
    // then raise it and hand AWT component focus to the Compose canvas (fixes first-open over an already-active Trawl window).
    LaunchedEffect(raise, visible) {
      panelWindow = window
      if (!visible) {
        raiser.cancel()
        shell.controller.previewPlayer.stop()
        if (nativeMacOverlay) trawl.desktop.shell.MacNative.hideOverlay(window)
        return@LaunchedEffect
      }
      armed[0] = false
      screen = runCatching { MouseInfo.getPointerInfo().device.defaultConfiguration.bounds }.getOrNull()
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
      val nowSize = QuickPanelSizing.of(screen!!.width, screen!!.height, shell.panel.previewing.value)
      if (nativeMacOverlay) {
        placeWindowNow(window, screen!!, nowSize, inset)
      } else {
        placeNow(state, screen!!, nowSize, inset)
      }
      withFrameNanos { }
      if (!nativeMacOverlay) window.focusableWindowState = true
      if (shell.os == trawl.desktop.Os.MAC) {
        trawl.desktop.shell.MacNative.setJdkNonActivatingBit(window)
        trawl.desktop.shell.MacNative.configureOverlay(window)
      }
      raiser.raise(window)
      SwingUtilities.invokeLater {
        if (!shell.panelVisible.value) return@invokeLater
        if (nativeMacOverlay) {
          trawl.desktop.shell.MacNative.notifyAwtPanelActivated(window)
        } else {
          window.toFront()
          focusNonMacPanelCanvas(window)
        }
        kick++
      }
    }
    // The preview opening or closing changes the window's size: keep it centred and on screen.
    LaunchedEffect(screen, previewing) {
      screen?.let {
        if (nativeMacOverlay) placeWindowNow(window, it, size, inset) else placeNow(state, it, size, inset)
      }
    }
    DisposableEffect(window) {
      panelWindow = window
      if (nativeMacOverlay) {
        trawl.desktop.shell.MacNative.setOutsideClickListener {
          SwingUtilities.invokeLater {
            if (shell.panelVisible.value && !shell.autoHideSuspended) shell.hidePanel()
          }
        }
      }
      val l = object : WindowAdapter() {
        override fun windowGainedFocus(e: WindowEvent?) {
          armed[0] = true
          if (nativeMacOverlay) {
            trawl.desktop.shell.MacNative.notifyAwtPanelActivated(window)
          } else {
            focusNonMacPanelCanvas(window)
          }
          kick++
          shell.controller.privacy.onWindowFocus("panel", true)
        }
        // Quick Look (the system preview, a separate program's window) takes the focus on purpose: the panel waits behind it.
        override fun windowLostFocus(e: WindowEvent?) { shell.controller.privacy.onWindowFocus("panel", false); if (armed[0] && !shell.autoHideSuspended) shell.hidePanel() }
      }
      window.addWindowFocusListener(l)
      onDispose {
        if (nativeMacOverlay) trawl.desktop.shell.MacNative.setOutsideClickListener(null)
        shell.controller.previewPlayer.stop()
        window.removeWindowFocusListener(l)
      }
    }
    LaunchedEffect(secure, window, visible) {
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        trawl.desktop.privacy.SecureWindow.apply(window, secure)
      }
    }
    androidx.compose.runtime.key(settings.language) {
      SunlightTheme(settings) {
        trawl.desktop.ui.ProvideLayoutDirection(settings.language) {
          trawl.desktop.ui.ProvideFileDrag(
            window,
            onDragStart = { shell.dragInProgress = true },
            onDragEnd = { shell.dragInProgress = false },
          ) {
            Box { QuickPanel(shell.panel, shell.os, if (status is trawl.desktop.shell.HotkeyStatus.Active) label else null, focusNonce = raise, focusKick = kick, inset = inset, blurOn = blurOn, onDismiss = shell::hidePanel) }
          }
        }
      }
    }
  }
  if (nativeMacOverlay) {
    // On macOS, use SwingWindow(create = ...) so `type = Window.Type.UTILITY` is guaranteed to run BEFORE
    // Compose/AWT calls addNotify(), creating a genuine AWTWindow_Panel (NSPanel) instead of AWTWindow_Normal (NSWindow).
    // macOS WindowServer ONLY lets an NSPanel with NSWindowStyleMaskNonactivatingPanel float over Full-Screen Spaces (e.g., Ghostty).
    androidx.compose.ui.awt.SwingWindow(
      visible = visible,
      onPreviewKeyEvent = handleEscape,
      create = {
        androidx.compose.ui.awt.ComposeWindow().apply {
          type = java.awt.Window.Type.UTILITY
          isUndecorated = true
          isTransparent = seeThrough
          isResizable = false
          isAlwaysOnTop = false
          focusableWindowState = true
          defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
          title = titleText
          setSize(fallback.width + inset.value.toInt() * 2, fallback.height + inset.value.toInt() * 2)
          addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) { shell.hidePanel() }
          })
          runCatching {
            addNotify()
            trawl.desktop.shell.MacNative.setJdkNonActivatingBit(this)
            trawl.desktop.shell.MacNative.configureOverlay(this)
          }
        }
      },
      dispose = { it.dispose() },
      update = { win -> win.title = titleText },
      content = panelBody,
    )
  } else {
    Window(
      onCloseRequest = shell::hidePanel, state = state, visible = visible, title = titleText,
      undecorated = true, transparent = seeThrough, alwaysOnTop = true, resizable = false, focusable = true,
      onPreviewKeyEvent = handleEscape,
      content = panelBody,
    )
  }
}

/** Hands AWT keyboard focus directly to Skiko's Compose Canvas on Windows and Linux without stealing it back to JFrame. */
private fun focusNonMacPanelCanvas(window: java.awt.Window) {
  val canvas = trawl.desktop.shell.MacNative.findComposeCanvas(window)
  if (canvas != null) {
    if (!canvas.requestFocusInWindow()) canvas.requestFocus()
  } else {
    window.requestFocus()
    window.requestFocusInWindow()
  }
}

/** Places a custom macOS ComposeWindow directly (used by the NSPanel `Window(create = ...)` path). */
private fun placeWindowNow(window: java.awt.Window, screen: java.awt.Rectangle, size: QuickPanelSizing.Size, inset: androidx.compose.ui.unit.Dp) {
  val w = size.width + inset.value.toInt() * 2
  val h = size.height + inset.value.toInt() * 2
  val x = QuickPanelSizing.left(screen.x, screen.width, w)
  val y = QuickPanelSizing.top(screen.y, screen.height, size.height) - inset.value.toInt()
  window.setBounds(x, y, w, h)
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
