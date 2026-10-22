package trawl.desktop.ui.quick

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import trawl.desktop.shell.HotkeyStatus
import trawl.desktop.shell.RecorderKeys
import trawl.desktop.shell.Shell
import trawl.desktop.shell.ShortcutCheck
import trawl.desktop.shell.ShortcutMessages
import trawl.desktop.t
import trawl.desktop.ui.Notice
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.a11yHeading
import trawl.desktop.ui.a11yLive
import trawl.desktop.ui.sunFocus

/** The running [Shell], for the two pieces below that sit inside other screens without changing those screens' signatures. Provided in ui/App.kt. */
val LocalShell = compositionLocalOf<Shell?> { null }

/**
 * Settings, "Quick search and background": the shortcut recorder and the keep-running switch. One call in SettingsScreen's column:
 * `trawl.desktop.ui.quick.ShellSettings()` (marked in the write-up as the single merge line).
 */
@Composable
fun ShellSettings(modifier: Modifier = Modifier) {
  val shell = LocalShell.current ?: return
  val tk = LocalSunlight.current
  val status by shell.hotkey.status.collectAsState()
  val prefs by shell.prefs.collectAsState()
  var recording by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val (line, good) = ShortcutMessages.forStatus(status, shell.os)
  val wayland = status is HotkeyStatus.Wayland || status is HotkeyStatus.Portal   // the desktop owns the key: no recorder

  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    Text(t("shell_section_title"), style = SunType.Heading, color = tk.ink, modifier = Modifier.a11yHeading())
    SunCard {
      Column(Modifier.fillMaxWidth().padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
        Text(t("shell_hotkey_title"), style = SunType.Label, color = tk.ink)
        Text(t("shell_hotkey_desc"), style = SunType.Caption, color = tk.ink2)
        Notice(line, bad = !good, modifier = Modifier.a11yLive())
        if (!wayland) {
          if (recording) {
            ShortcutRecorderBox(shell, onFinished = { recording = false })
          } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalAlignment = Alignment.CenterVertically) {
              SunOutlinedButton(onClick = { recording = true }) { Text(t("shell_hotkey_change"), style = SunType.Label) }
              SunTextButton(onClick = { scope.launch(Dispatchers.IO) { shell.resetHotkey() } }) { Text(t("shell_hotkey_reset"), style = SunType.Label) }
              SunTextButton(onClick = { scope.launch(Dispatchers.IO) { shell.turnOffHotkey() } }, enabled = prefs.hotkeyOn) {
                Text(t("shell_hotkey_off_button"), style = SunType.Label)
              }
            }
          }
        }
      }
    }
    SunCard {
      Column(Modifier.fillMaxWidth().padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S4)) {
        val on = prefs.keepRunning && shell.trayAvailable
        Row(
          Modifier.fillMaxWidth().heightIn(min = Sun.Target)
            .toggleable(value = on, enabled = shell.trayAvailable, role = Role.Switch, onValueChange = shell::setKeepRunning)
            .sunFocus(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) { contentDescription = t("shell_keep_running") },
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12),
        ) {
          Text(t("shell_keep_running"), style = SunType.Label, color = tk.ink, modifier = Modifier.weight(1f))
          Switch(checked = on, onCheckedChange = null, enabled = shell.trayAvailable)
        }
        Text(if (shell.trayAvailable) t("shell_keep_running_desc") else t("shell_tray_unavailable"), style = SunType.Caption, color = tk.ink2)
      }
    }
  }
}

/**
 * The line on the first-run screen: "Open quick search from any app with Control+Shift+Space", with a Change link that opens the
 * recorder in place. One call where the first-run screen wants it: `trawl.desktop.ui.quick.ShortcutLine()`.
 */
@Composable
fun ShortcutLine(modifier: Modifier = Modifier) {
  val shell = LocalShell.current ?: return
  val tk = LocalSunlight.current
  val status by shell.hotkey.status.collectAsState()
  var editing by remember { mutableStateOf(false) }
  val text = when (status) {
    is HotkeyStatus.Active -> t("shell_first_line", (status as HotkeyStatus.Active).hotkey.label(shell.os))
    HotkeyStatus.AllTaken -> t("shell_hotkey_all_taken")
    else -> return   // off, or this desktop cannot listen globally: nothing to promise on the first screen
  }
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
      Text(text, style = SunType.Body, color = tk.ink, modifier = Modifier.weight(1f, fill = false).a11yLive())
      if (!editing) SunTextButton(onClick = { editing = true }) { Text(t("shell_first_change"), style = SunType.Label) }
    }
    if (editing) ShortcutRecorderBox(shell, onFinished = { editing = false })
  }
}

/**
 * Press the keys you want. Everything the keyboard sends is taken while this has the focus (so Cmd+Q-like keys are recorded, not run),
 * except Tab (leave) and Esc (cancel). The pressed combination is tried for real; a taken one is explained and recording continues.
 */
@Composable
fun ShortcutRecorderBox(shell: Shell, onFinished: () -> Unit) {
  val tk = LocalSunlight.current
  val focus = remember { FocusRequester() }
  val scope = rememberCoroutineScope()
  var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
  var busy by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Box(
      Modifier.fillMaxWidth().heightIn(min = Sun.Field).border(Sun.Border, tk.ink, shape).padding(horizontal = Sun.S16, vertical = Sun.S12)
        .focusRequester(focus)
        .onFocusChanged { if (!it.isFocused && !busy) onFinished() }
        .focusable()
        .semantics { contentDescription = t("shell_hotkey_press") }
        .onPreviewKeyEvent { e ->
          val code = e.key.nativeKeyCode
          when {
            code == TAB -> false
            e.type != KeyEventType.KeyDown -> true
            code == RecorderKeys.VK_ESCAPE -> { onFinished(); true }
            else -> {
              RecorderKeys.fromEvent(code, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed, e.isMetaPressed)?.let { h ->
                if (!busy) scope.launch {
                  busy = true
                  val r = withContext(Dispatchers.IO) { shell.tryHotkey(h) }
                  message = ShortcutMessages.forCheck(r, shell.os)
                  busy = false
                  if (r is ShortcutCheck.Ok) onFinished()
                }
              }
              true
            }
          }
        },
      contentAlignment = Alignment.CenterStart,
    ) { Text(t("shell_hotkey_press"), style = SunType.Body, color = tk.ink) }
    message?.let { (m, good) -> Notice(m, bad = !good, modifier = Modifier.a11yLive()) }
  }
}

private const val TAB = 9

/** Provides the shell to everything below (App.kt wraps the window content in this). */
@Composable
fun WithShell(shell: Shell, content: @Composable () -> Unit) = CompositionLocalProvider(LocalShell provides shell, content = content)
