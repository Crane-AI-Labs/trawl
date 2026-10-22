package trawl.desktop.shell

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import trawl.desktop.AppController
import trawl.desktop.Os
import trawl.desktop.Phase
import trawl.desktop.Platform
import trawl.desktop.t

/**
 * Decides, once, when to tell the person "Trawl has read your files". Pure, so the rule can be tested: it fires on the first move to
 * "up to date" with files in the index, after this run saw reading under way, and never again ([done]).
 */
class FirstIndexWatcher(private var done: Boolean) {
  private var sawWork = false

  fun onUpdate(phase: Phase, total: Int): Boolean {
    if (done) return false
    if (phase == Phase.SCANNING || phase == Phase.INDEXING) sawWork = true
    if (phase == Phase.UP_TO_DATE && total > 0 && sawWork) { done = true; return true }
    return false
  }
}

/**
 * The shell around the window: the quick-search shortcut, the tray icon, the quick panel, hide-instead-of-quit, and hand-over from a
 * second launch. It owns no UI; `ui/App.kt` draws [mainVisible], [panelVisible] and the tray from its state. Package D.
 */
class Shell(
  val controller: AppController,
  private val dataDir: File,
  private val scope: CoroutineScope,
  val os: Os = Platform.os,
  /** False where the desktop shows no tray icons (some GNOME sessions): the window must then really close. */
  val trayAvailable: Boolean,
  nativeHotkey: GlobalHotkey? = runCatching { HotkeyService.nativeFor(os, dataDir) }.getOrNull(),
  private val instance: SingleInstanceShell? = null,
  /** False for `trawl --quick` as the first launch: only the panel shows. */
  startWithMain: Boolean = true,
) : AutoCloseable {
  private val store = ShellPrefsStore(dataDir)
  private val _prefs = MutableStateFlow(
    if (File(dataDir, "shell.json").isFile) store.load()
    // First run of the shell. An install that was already set up has long since had its first index; a new one has not.
    else ShellPrefs(firstIndexNotified = controller.settings.value.onboarded).also { runCatching { store.save(it) } },
  )
  val prefs: StateFlow<ShellPrefs> = _prefs.asStateFlow()

  val trayState = TrayState()

  private val _mainVisible = MutableStateFlow(startWithMain)
  val mainVisible: StateFlow<Boolean> = _mainVisible.asStateFlow()
  /** Bumped whenever something asks for the main window, so it comes to the front even if it is already visible. */
  private val _mainRaise = MutableStateFlow(0)
  val mainRaise: StateFlow<Int> = _mainRaise.asStateFlow()
  /** Set by the window: it has the keyboard focus now. */
  @Volatile var mainFocused: Boolean = false

  private val _panelVisible = MutableStateFlow(false)
  val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()
  private val _panelRaise = MutableStateFlow(0)
  val panelRaise: StateFlow<Int> = _panelRaise.asStateFlow()

  /** The system's own big preview (macOS Quick Look), or none on Windows and Linux. */
  val fullPreview: FullPreview = QuickLookMac.forOs(os, dataDir = { dataDir })

  val quickApi: QuickSearchApi = ControllerQuickSearch(controller, full = fullPreview)
  val panel = QuickPanelState(quickApi, scope, onClose = ::hidePanel)

  // The press arrives on a native thread; everything it touches is a StateFlow, so it is safe to act right there.
  val hotkey = HotkeyService(os, nativeHotkey) { togglePanel() }

  /** Called when Quit is chosen (set by the application loop). */
  @Volatile var onQuit: () -> Unit = {}

  private val firstIndex = FirstIndexWatcher(_prefs.value.firstIndexNotified)

  init {
    instance?.listen(::handle)
    if (!startWithMain) showPanel()
    scope.launch {
      var was = false
      fullPreview.open.collect { now ->
        if (was && !now && _panelVisible.value) _panelRaise.update { it + 1 }
        was = now
      }
    }
    scope.launch(Dispatchers.IO) { hotkey.apply(_prefs.value) }
    scope.launch {
      controller.index.collect { ui ->
        if (firstIndex.onUpdate(ui.phase, ui.total)) firstIndexDone()
      }
    }
  }

  /** The shortcut now working, as the person reads it, or null. */
  fun shortcutLabel(): String? = hotkey.active?.label(os)

  // ---- windows -------------------------------------------------------------------------------------------------

  fun showMain() {
    hidePanel()
    _mainVisible.value = true
    _mainRaise.update { it + 1 }
  }

  fun showPanel() {
    panel.reset()
    _panelVisible.value = true
    _panelRaise.update { it + 1 }
  }

  fun hidePanel() {
    panel.dismissPreviews()
    _panelVisible.value = false
  }

  /**
   * True while the system's preview (Quick Look) is on screen. It is another app's window, so the panel loses focus to it; that must not
   * close the panel (the person is still using it). When the system preview goes, the panel comes back to the front with the keyboard.
   */
  val autoHideSuspended: Boolean get() = fullPreview.open.value

  fun togglePanel() { if (_panelVisible.value) hidePanel() else showPanel() }

  fun openSettings() {
    controller.openSettings()
    showMain()
  }

  fun togglePause() = controller.setPaused(!controller.settings.value.indexPaused)

  /** The window's close button. Keeps running in the tray when the person wants that and there is a tray to come back from. */
  fun onMainCloseRequest() {
    if (_prefs.value.keepRunning && trayAvailable) _mainVisible.value = false else quit()
  }

  fun quit() {
    close()
    controller.close()
    onQuit()
  }

  /** A second launch, or a system shortcut running `trawl --quick`, tells the running Trawl what to do. */
  fun handle(command: String) {
    when (command) {
      "show" -> showMain()
      "quick" -> showPanel()
    }
  }

  // ---- settings ------------------------------------------------------------------------------------------------

  private fun save(p: ShellPrefs) {
    _prefs.value = p
    runCatching { store.save(p) }
  }

  fun setKeepRunning(on: Boolean) = save(_prefs.value.copy(keepRunning = on))

  /** Tries [h] for real; if it works it is saved and stays registered. Blocking (a native call): run it off the UI thread. */
  fun tryHotkey(h: Hotkey?): ShortcutCheck {
    val r = hotkey.probe(h)
    if (r is ShortcutCheck.Ok) save(_prefs.value.copy(hotkeyOn = true, hotkey = r.hotkey.spec))
    return r
  }

  /** Back to the default (with its fallbacks). Blocking. */
  fun resetHotkey() {
    save(_prefs.value.copy(hotkeyOn = true, hotkey = null))
    hotkey.apply(_prefs.value)
  }

  /** Blocking. */
  fun turnOffHotkey() {
    save(_prefs.value.copy(hotkeyOn = false))
    hotkey.apply(_prefs.value)
  }

  // ---- first index ---------------------------------------------------------------------------------------------

  private fun firstIndexDone() {
    save(_prefs.value.copy(firstIndexNotified = true))
    // Only worth a notification if the person is not looking at the window already.
    if (trayAvailable && (!_mainVisible.value || !mainFocused)) {
      val key = shortcutLabel()
      trayState.sendNotification(
        Notification(t("tray_first_title"), if (key != null) t("tray_first_text", key) else t("tray_first_text_plain"), Notification.Type.Info),
      )
    }
  }

  override fun close() {
    runCatching { hotkey.close() }
    runCatching { instance?.close() }
  }
}
