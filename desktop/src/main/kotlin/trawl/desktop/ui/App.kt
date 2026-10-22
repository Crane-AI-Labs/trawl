package trawl.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import trawl.desktop.ui.quick.ShellTray   // shell (Package D)
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import trawl.desktop.AppController
import trawl.desktop.Platform
import trawl.desktop.Screen
import trawl.desktop.Strings
import trawl.desktop.t
import com.craneai.findr.core.PackIds
import trawl.desktop.AddOnState
import trawl.desktop.privacy.PrivacyRuntime
import trawl.desktop.privacy.SecureWindow
import trawl.desktop.ui.privacy.LockScreen
import trawl.desktop.ui.privacy.PrivacyUi
import trawl.desktop.ui.privacy.WithPrivacy
import androidx.compose.ui.window.WindowPlacement as ComposeWindowPlacement

/** The window's content: the screen the controller says, the Sunlight theme the person chose, and a notice line over the bottom. */
@Composable
fun App(c: AppController) {
  val settings by c.settings.collectAsState()
  // Words are looked up when drawn, so a new language (Settings) draws the whole window again from the top.
  androidx.compose.runtime.key(settings.language) { AppContent(c) }
}

@Composable
private fun AppContent(c: AppController) {
  val settings by c.settings.collectAsState()
  val screen by c.screen.collectAsState()
  val notice by c.notice.collectAsState()
  val good by c.noticeGood.collectAsState()
  // Privacy (package B): the lock, the blur and the dialogs. One PrivacyUi for the window, shared by every screen through LocalPrivacyUi.
  val locked by c.privacy.locked.collectAsState()
  val addOnStates by c.addOns.states.collectAsState()
  val packInstalled = addOnStates[PackIds.PRIVACY] is AddOnState.Installed && PrivacyRuntime.usable()
  val privacyUi = remember(c) { PrivacyUi(c.privacy, c::say) }
  SunlightTheme(settings) {
   ProvideLayoutDirection(settings.language) {   // hook (Package C): right-to-left for Arabic
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
          // Which input was last used, so keyboard focus on a button is told apart from the button having been clicked.
          awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); InputModality.keyboard = false; c.privacy.onInput() } }
        }.onPreviewKeyEvent { e ->
          InputModality.keyboard = true
          c.privacy.onInput()
          val k = e.toStroke() ?: return@onPreviewKeyEvent false
          val ctx = KeyContext(screen, FocusZone.OTHER, quickLook = false, queryBlank = true, hasResults = false, isMac = MainUi.isMac)
          val action = KeyMap.map(k, ctx)
          // Text size (Cmd/Ctrl + Plus, Minus, 0) works on every screen, so it is answered here before any screen sees the key.
          if (action is KeyAction.Zoom) { c.zoom(action.step); return@onPreviewKeyEvent true }
          // Esc goes back from Settings and Licences; the main screen has its own keys (MainScreen).
          if (screen == Screen.MAIN) return@onPreviewKeyEvent false
          if (action != KeyAction.Back) return@onPreviewKeyEvent false
          if (screen == Screen.LICENCES) c.closeLicences() else if (screen == Screen.SETTINGS) c.closeSettings()
          true
        },
      ) {
        // Locked: the lock screen replaces every screen (the quick panel and the tray's "Open Trawl" ask the same state, which lives in the
        // controller). Nothing behind it is drawn, so no name, count or picture can show through.
        WithPrivacy(privacyUi, packInstalled) {
          if (locked) LockScreen(c.privacy)
          else when (screen) {
            Screen.FIRST_RUN -> FirstRunScreen(c)
            Screen.MAIN -> MainScreen(c)
            Screen.SETTINGS -> SettingsScreen(c)
            Screen.LICENCES -> LicencesScreen(c)
          }
          privacyUi.Dialogs()
        }
        notice?.takeIf { !locked }?.let { message ->
          // Over the bottom of the window; on the main screen it clears the status bar.
          val lift = if (screen == Screen.MAIN) 56.dp else Sun.S24
          Box(Modifier.align(Alignment.BottomCenter).padding(start = Sun.S24, end = Sun.S24, top = Sun.S24, bottom = lift).widthIn(max = 720.dp)) {
            SunCard(color = MaterialTheme.colorScheme.background) {
              Row(Modifier.padding(Sun.S16), horizontalArrangement = Arrangement.spacedBy(Sun.S12), verticalAlignment = Alignment.CenterVertically) {
                Notice(message, bad = !good, modifier = Modifier.weight(1f))
                SunTextButton(onClick = c::dismissNotice) { Text(t("action_done"), style = SunType.Label) }
              }
            }
          }
        }
      }
    }
   }
  }
}

/** The usable area of every screen (the screen minus the menu bar, Dock or taskbar), primary first, in the units AWT windows use. */
internal fun usableScreens(): List<Rect> = runCatching {
  val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
  val primary = ge.defaultScreenDevice
  val tk = java.awt.Toolkit.getDefaultToolkit()
  ge.screenDevices.sortedByDescending { it == primary }.map { d ->
    val b = d.defaultConfiguration.bounds
    val i = tk.getScreenInsets(d.defaultConfiguration)
    Rect(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
  }
}.getOrDefault(emptyList())

/** Opens the window. Nothing here touches the network: the app has no network code at all. */
@OptIn(FlowPreview::class)
fun runWindow(args: List<String> = emptyList()) {
  Strings.use(Locale.getDefault())
  val dataDir = Platform.dataDir()
  // --- shell (Package D): one Trawl per data folder. A second launch (or `trawl --quick`) hands over to the running one and exits. ---
  val quickLaunch = "--quick" in args
  val instance = when (val o = trawl.desktop.shell.SingleInstanceShell.acquire(dataDir, if (quickLaunch) "quick" else "show")) {
    is trawl.desktop.shell.SingleInstanceShell.Outcome.First -> o.instance
    trawl.desktop.shell.SingleInstanceShell.Outcome.Forwarded -> return
    trawl.desktop.shell.SingleInstanceShell.Outcome.Unreachable -> {
      System.err.println("Trawl is already running but not answering. Close it from the tray or the task manager, then start Trawl again.")
      return
    }
  }
  // --- end shell ---
  val controller = AppController(dataDir)
  runCatching { controller.reporter.installCrashHandler() }   // a crash is saved for the next start, to be reported if the person says so
  application {
    // --- shell (Package D): the hotkey, tray and quick panel live in one Shell, which the window, the tray and the panel all draw from. ---
    val shell = remember {
      trawl.desktop.shell.Shell(
        controller, dataDir, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
        trayAvailable = isTraySupported, instance = instance, startWithMain = !quickLaunch,
      ).also { it.onQuit = ::exitApplication }
    }
    val mainVisible by shell.mainVisible.collectAsState()
    val mainRaise by shell.mainRaise.collectAsState()
    // --- end shell ---
    // Where the window was last time if that is still on a screen; otherwise the roomy 1120 x 800 (or less, on a small screen), centred.
    // TRAWL_WINDOW=1120x940 forces a size (screenshots, demos); it is never clamped and never remembered.
    val screens = remember { usableScreens() }
    val first = remember { WindowSizing.plan(screens.firstOrNull()?.w ?: 0, screens.firstOrNull()?.h ?: 0) }
    val forced = remember { System.getenv("TRAWL_WINDOW")?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 } }
    val store = remember { WindowStore(dataDir) }
    val place = remember { if (forced != null) null else WindowMemory.plan(store.load(), screens, first) }
    val px = place?.x
    val py = place?.y
    val state = rememberWindowState(
      size = when {
        forced != null -> DpSize(forced[0].dp, forced[1].dp)
        place != null -> DpSize(place.width.dp, place.height.dp)
        else -> DpSize(first.width.dp, first.height.dp)
      },
      // Centred, not at the platform's cascade point (the top-left corner on a Mac), so the margin is even all round.
      position = when {
        forced != null -> WindowPosition.PlatformDefault
        px != null && py != null -> WindowPosition(px.dp, py.dp)
        else -> WindowPosition.Aligned(Alignment.Center)
      },
      placement = if (place?.maximized == true) ComposeWindowPlacement.Maximized else ComposeWindowPlacement.Floating,
    )
    // The first-screen demo does no work while the window is minimised.
    LaunchedEffect(state) { snapshotFlow { state.isMinimized }.collect { WindowActivity.minimized = it } }
    if (forced == null) {
      // Save a moment after the window stops moving or resizing.
      LaunchedEffect(state) {
        snapshotFlow { Triple(state.size, state.position, state.placement) }.debounce(600).collect { (size, pos, placement) ->
          if (pos is WindowPosition.Absolute && placement != ComposeWindowPlacement.Fullscreen && !size.width.value.isNaN() && !size.height.value.isNaN()) {
            withContext(Dispatchers.IO) {
              store.save(SavedWindow(pos.x.value.toInt(), pos.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt(), placement == ComposeWindowPlacement.Maximized))
            }
          }
        }
      }
    }
    Window(onCloseRequest = shell::onMainCloseRequest, visible = mainVisible, title = t("app_name"), state = state) {
      LaunchedEffect(Unit) {
        window.minimumSize = Dimension(first.minWidth, first.minHeight)
        window.iconImages = Brand.icons()   // the title bar, taskbar and alt-tab show the Trawl mark, not the Java cup
        // macOS shows the Dock icon through the Taskbar API; elsewhere it is unsupported and harmless to skip.
        runCatching { if (java.awt.Taskbar.isTaskbarSupported()) java.awt.Taskbar.getTaskbar().iconImage = Brand.icon(512) }
      }
      // --- shell (Package D): come to the front when asked, know whether we have the focus, and make Cmd+Q / Dock "Quit" really quit. ---
      // The same verified raise as the quick panel (tray click, a second launch, "Open Trawl" from the panel), minus the parts that float it
      // over full-screen apps: this is an ordinary window.
      val mainRaiser = remember { trawl.desktop.shell.PanelRaiser.forWindow(shell.os, onSettled = { n, ok -> controller.appLog.log("main window raise: $n attempts, focused ${if (ok) "yes" else "no"}") }, overlay = false) }
      LaunchedEffect(mainRaise) {
        if (mainRaise > 0) { state.isMinimized = false; mainRaiser.raise(window) }
      }
      DisposableEffect(window) {
        val focus = object : java.awt.event.WindowAdapter() {
          override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { shell.mainFocused = true; controller.privacy.onWindowFocus("main", true) }
          override fun windowLostFocus(e: java.awt.event.WindowEvent?) { shell.mainFocused = false; controller.privacy.onWindowFocus("main", false) }
        }
        window.addWindowFocusListener(focus)
        runCatching {
          val d = java.awt.Desktop.getDesktop()
          if (d.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler { _, r -> r.cancelQuit(); shell.quit() }
          if (d.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED)) d.addAppEventListener(java.awt.desktop.AppReopenedListener { shell.showMain() })
        }
        onDispose { window.removeWindowFocusListener(focus) }
      }
      // --- end shell ---
      // Privacy: keep the window out of screenshots and screen sharing while hidden or sensitive things show (or when the person asked). A request
      // to the system; what it answered is not shown anywhere but the Settings line, and a failure leaves the window as it was.
      val secure by controller.privacy.secureWindow.collectAsState()
      LaunchedEffect(secure, window) { withContext(Dispatchers.IO) { SecureWindow.apply(window, secure) } }
      System.getenv("TRAWL_DEMO_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
        LaunchedEffect(Unit) {
          withContext(Dispatchers.Default) { Demo(controller, window, File(dir)).run() }
          // Screenshot runs only: a lingering non-daemon thread (or a close that waits on one) must not keep a finished run (and its Xvfb)
          // alive for the build box's timeout, so the exit is armed before anything that can block.
          Thread { Thread.sleep(8_000); Runtime.getRuntime().halt(0) }.apply { isDaemon = true }.start()
          controller.close()
          exitApplication()
        }
      }
      // The zero-network release gate's scripted session (desktop/scripts/net-gate.py); it never runs unless the variable is set.
      System.getenv("TRAWL_NETGATE_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
        LaunchedEffect(Unit) {
          withContext(Dispatchers.Default) {
            runCatching { NetGate(controller, shell, File(dir), dataDir).run() }.onFailure { System.err.println("[netgate] failed: ${it.javaClass.simpleName} ${it.message}") }
          }
          Thread { Thread.sleep(8_000); Runtime.getRuntime().halt(0) }.apply { isDaemon = true }.start()
          controller.close()
          exitApplication()
        }
      }
      trawl.desktop.ui.quick.WithShell(shell) { App(controller) }   // shell (Package D)
    }
    // --- shell (Package D) ---
    ShellTray(shell)
    trawl.desktop.ui.quick.QuickPanelWindow(shell)
    // --- end shell ---
  }
}
