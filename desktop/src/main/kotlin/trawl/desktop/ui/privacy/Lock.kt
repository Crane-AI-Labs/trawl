package trawl.desktop.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import trawl.desktop.privacy.OsOutcome
import trawl.desktop.privacy.OsPrompt
import trawl.desktop.privacy.PassResult
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.privacy.Purpose
import trawl.desktop.t
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.Notice

/** What to call the computer's own unlock in a sentence: "Touch ID", "Windows Hello", "your Mac password". */
fun osPromptName(prompt: OsPrompt?): String = when (prompt) {
  OsPrompt.TOUCH_ID -> t("privacy_os_touchid")
  OsPrompt.MAC_PASSWORD -> t("privacy_os_macpassword")
  OsPrompt.WINDOWS_HELLO -> t("privacy_os_hello")
  null -> t("privacy_os_generic")
}

/**
 * "Is it you?": the computer's own prompt when there is one, and the passphrase otherwise or as a second choice. The work runs off
 * the UI thread (the prompt waits for the person, the passphrase takes about half a second on purpose). [onGranted] runs on the UI
 * thread once the controller has already done what [purpose] means. The typed passphrase never leaves this function except as a
 * `CharArray` the controller wipes.
 *
 * @param autoPrompt start the system prompt as soon as this appears (the lock screen does; a dialog the person opened does too)
 */
@Composable
fun AuthPanel(c: PrivacyController, purpose: Purpose, reason: String, onGranted: () -> Unit, autoPrompt: Boolean, modifier: Modifier = Modifier) {
  val settings by c.settings.collectAsState()
  val availability by c.osAvailability.collectAsState()
  val methods = remember(settings, availability) { c.authMethods() }
  var message by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var pass by remember { mutableStateOf("") }
  var typing by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val showPass = methods.passphrase && (typing || methods.os == null)

  fun runOs() {
    if (busy || methods.os == null) return
    busy = true
    message = null
    scope.launch {
      val outcome = withContext(Dispatchers.IO) { c.authWithOs(purpose, reason) }
      busy = false
      when (outcome) {
        OsOutcome.OK -> onGranted()
        OsOutcome.CANCELLED -> message = t("lock_os_cancelled")
        OsOutcome.DENIED -> message = t("lock_os_denied")
        OsOutcome.UNAVAILABLE, OsOutcome.ERROR -> { message = t("lock_os_error"); typing = true }
      }
    }
  }

  fun submit() {
    if (busy || pass.isEmpty()) return
    val chars = pass.toCharArray()
    pass = ""
    busy = true
    message = null
    scope.launch {
      val result = withContext(Dispatchers.IO) { c.authWithPassphrase(purpose, chars) }
      busy = false
      when (result) {
        PassResult.Ok -> onGranted()
        PassResult.Wrong -> message = t("lock_wrong")
        is PassResult.Wait -> message = t("lock_wait", ((result.ms + 999) / 1000).toString())
        PassResult.NotSet -> message = t("lock_not_set")
      }
    }
  }

  // One automatic prompt per time this appears and per prompt kind: a cancel is not answered with another prompt.
  LaunchedEffect(methods.os, autoPrompt) { if (autoPrompt && methods.os != null && c.canAutoPrompt()) runOs() }

  Column(modifier, verticalArrangement = Arrangement.spacedBy(Sun.S12), horizontalAlignment = Alignment.CenterHorizontally) {
    if (methods.os != null) {
      SunButton(onClick = ::runOs, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy && !showPass) t("lock_os_waiting", osPromptName(methods.os)) else t("lock_unlock_with", osPromptName(methods.os)))
      }
    }
    if (methods.passphrase && methods.os != null && !showPass) {
      SunTextButton(onClick = { typing = true }) { Text(t("lock_use_passphrase")) }
    }
    if (showPass) {
      OutlinedTextField(
        value = pass, onValueChange = { pass = it }, label = { Text(t("lock_passphrase")) }, singleLine = true, enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth(),
      )
      SunButton(onClick = ::submit, enabled = !busy && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(t("lock_unlock")) }
    }
    if (methods.none) Notice(t("lock_not_set"), bad = true)
    message?.let { Notice(it, bad = true) }
  }
}

/** Shown instead of the results while the app is locked. It shows no result, no name and no count. */
@Composable
fun LockScreen(c: PrivacyController, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 420.dp).padding(Sun.S24), horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(Icons.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(Sun.S16))
      Text(t("lock_title"), style = SunType.Title, color = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(Sun.S8))
      Text(t("lock_body"), style = SunType.Body, color = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(Sun.S24))
      AuthPanel(c, Purpose.UNLOCK, t("auth_reason_unlock"), onGranted = {}, autoPrompt = true, modifier = Modifier.fillMaxWidth())
    }
  }
}

/** A small window that asks for proof before something hidden is shown or the lock is changed. Closes itself when the proof is given. */
@Composable
fun AuthDialog(c: PrivacyController, purpose: Purpose, onGranted: () -> Unit, onDismiss: () -> Unit) {
  val reveal = purpose == Purpose.REVEAL
  val unhide = purpose == Purpose.UNHIDE
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.background,
    title = { Text(t(if (reveal) "auth_title_reveal" else if (unhide) "auth_title_unhide" else "auth_title_change"), style = SunType.Heading) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(t(if (reveal) "auth_body_reveal" else if (unhide) "auth_body_unhide" else "auth_body_change"), style = SunType.Body)
        AuthPanel(
          c, purpose, t(if (reveal) "auth_reason_reveal" else if (unhide) "auth_reason_unhide" else "auth_reason_change"),
          onGranted = onGranted, autoPrompt = true, modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {},
    dismissButton = { SunOutlinedButton(onClick = onDismiss) { Text(t("auth_cancel")) } },
  )
}
