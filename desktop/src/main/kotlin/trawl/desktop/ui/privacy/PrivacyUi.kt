package trawl.desktop.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.IndexedFile
import trawl.desktop.privacy.OsAvailability
import trawl.desktop.privacy.Passphrase
import trawl.desktop.privacy.PreviewBlur
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.privacy.Purpose
import trawl.desktop.tn
import trawl.desktop.t
import trawl.desktop.ui.CheckRow
import trawl.desktop.ui.FilterChip
import trawl.desktop.ui.Notice
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType

/**
 * The privacy hooks the result list and the preview pane call, in one place so those screens need a single line each:
 *
 *  - `ui.mark(ids, on)`: Mark as sensitive / Not sensitive. The first mark offers the lock, as the phone does.
 *  - `ui.leave(ids) { copy / share / open elsewhere }`: runs the action at once, or after the leave dialog when any file is sensitive.
 *  - `ui.revealSensitive()` / `ui.hideSensitive()`: show or put away sensitive results (asks for proof when a lock or prompt exists).
 *  - `ui.blur`: the [PreviewBlur] to hand to rows and the preview ([LocalPreviewBlur]).
 *  - `ui.Dialogs()`: once, at the window's top, draws whichever of these dialogs is open.
 */
@Stable
class PrivacyUi(val c: PrivacyController, private val say: (String, Boolean) -> Unit) {
  private class Leaving(val sensitive: Int, val action: () -> Unit)

  private var leaving by mutableStateOf<Leaving?>(null)
  private var offer by mutableStateOf(false)
  private var enable by mutableStateOf(false)
  private var asking by mutableStateOf<Pair<Purpose, () -> Unit>?>(null)

  /** Marks or unmarks [ids]; the first time, offers a lock. */
  fun mark(ids: List<Long>, on: Boolean) {
    val found = c.mark(ids, on)
    if (found > 0) say(if (on) t("sensitive_marked") else t("sensitive_unmarked"), true)
    if (on && found > 0 && c.shouldOfferLock()) {
      c.lockOfferShown()
      offer = true
    }
  }

  /** Runs [action] (copy, share, open with another app) now, or after the leave dialog when any of [ids] is sensitive. */
  fun leave(ids: Collection<Long>, action: () -> Unit) {
    val n = c.countSensitive(ids)
    if (n == 0) action() else leaving = Leaving(n, action)
  }

  /** Asks for proof if the person set one up, then shows sensitive results. [onShown] runs after. */
  fun revealSensitive(onShown: () -> Unit = {}) {
    if (!c.revealNeedsProof()) {
      c.revealWithoutProof()
      onShown()
    } else {
      asking = Purpose.REVEAL to onShown
    }
  }

  fun hideSensitive() = c.hideSensitive()

  /** Asks for proof before changing the lock (turning it off, a new passphrase), then runs [onProved]. */
  fun proveChange(onProved: () -> Unit) {
    asking = Purpose.CHANGE to onProved
  }

  /**
   * Runs [onProved] now when there is nothing to prove against (no passphrase and no system prompt), otherwise after the person proves it is
   * them. Lifting a private folder makes its pictures searchable again, which is the unlocking direction, so it asks; hiding more never does.
   */
  fun proveUnhide(onProved: () -> Unit) {
    if (c.authMethods().none) onProved() else asking = Purpose.UNHIDE to onProved
  }

  fun startEnableLock() {
    enable = true
  }

  /**
   * The blur for the window's rows and preview: on when the setting is on, [pack] is installed and the app is unlocked.
   * [pack] is "the Privacy pack is installed" (the add-ons state).
   */
  @Composable
  fun rememberBlur(pack: Boolean): PreviewBlur {
    val settings by c.settings.collectAsState()
    val locked by c.locked.collectAsState()
    // Keyed on `locked` too: locking drops every picture the person had shown, so the next unlock starts blurred again.
    return remember(settings.blurEnabled, pack, locked) {
      PreviewBlur(
        enabled = settings.blurEnabled && pack,
        allowReveal = { !c.locked.value },
        onDenied = { say(t("hidden_denied"), false) },
        onFirstBlur = { if (!c.settings.value.blurHintShown) { c.blurHintShown(); say(t("hidden_hint"), true) } },
      )
    }
  }

  @Composable
  fun Dialogs() {
    leaving?.let { l ->
      LeaveDialog(l.sensitive, onContinue = { leaving = null; l.action() }, onCancel = { leaving = null })
    }
    if (offer) {
      LockOfferDialog(onSetUp = { offer = false; enable = true }, onLater = { offer = false })
    }
    if (enable) {
      EnableLockDialog(c, onDone = { ok -> enable = false; if (ok) say(t("privacy_lock_enabled"), true) })
    }
    asking?.let { (purpose, then) ->
      AuthDialog(c, purpose, onGranted = { asking = null; then() }, onDismiss = { asking = null })
    }
  }
}

val LocalPrivacyUi = staticCompositionLocalOf<PrivacyUi?> { null }

// ---- pieces ------------------------------------------------------------------------------------------------------------------

/** "Take a sensitive file out of Trawl?" Asked before Copy, Share and Open with another app when any file is sensitive. */
@Composable
fun LeaveDialog(sensitive: Int, onContinue: () -> Unit, onCancel: () -> Unit) {
  AlertDialog(
    onDismissRequest = onCancel,
    containerColor = MaterialTheme.colorScheme.background,
    title = { Text(t("leave_title"), style = SunType.Heading) },
    text = { Text(tn("leave_body", sensitive), style = SunType.Body) },
    confirmButton = { SunButton(onClick = onContinue) { Text(t("leave_confirm")) } },
    dismissButton = { SunTextButton(onClick = onCancel) { Text(t("leave_cancel")) } },
  )
}

/** After the first "Mark as sensitive": a lock would hide the view. Says what it is not. */
@Composable
fun LockOfferDialog(onSetUp: () -> Unit, onLater: () -> Unit) {
  AlertDialog(
    onDismissRequest = onLater,
    containerColor = MaterialTheme.colorScheme.background,
    title = { Text(t("lock_offer_title"), style = SunType.Heading) },
    text = { Text(t("lock_offer_body"), style = SunType.Body) },
    confirmButton = { SunButton(onClick = onSetUp) { Text(t("lock_offer_set")) } },
    dismissButton = { SunTextButton(onClick = onLater) { Text(t("lock_offer_later")) } },
  )
}

/**
 * Turns the lock on: a new passphrase (twice) that is always set, because a computer may have no fingerprint or face unlock, and
 * the system prompt when this computer has one. It states what the lock covers before the person agrees to it.
 */
@Composable
fun EnableLockDialog(c: PrivacyController, onDone: (Boolean) -> Unit) {
  val availability by c.osAvailability.collectAsState()
  val prompt = (availability as? OsAvailability.Available)?.prompt
  var one by remember { mutableStateOf("") }
  var two by remember { mutableStateOf("") }
  var useOs by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  fun go() {
    when {
      one != two -> error = t("privacy_pass_mismatch")
      else -> when (Passphrase.problem(one.toCharArray())) {
        Passphrase.Problem.TOO_SHORT -> error = t("privacy_pass_min", Passphrase.MIN_LENGTH)
        Passphrase.Problem.BLANK -> error = t("privacy_pass_blank")
        null -> {
          val chars = one.toCharArray()
          one = ""; two = ""
          when (c.enableLock(chars, useOsPrompt = useOs && prompt != null)) {
            PrivacyController.ChangeResult.OK -> onDone(true)
            PrivacyController.ChangeResult.NEEDS_PROOF -> error = t("privacy_need_proof")
            PrivacyController.ChangeResult.WEAK -> error = t("privacy_pass_min", Passphrase.MIN_LENGTH)
            PrivacyController.ChangeResult.BLANK -> error = t("privacy_pass_blank")
          }
        }
      }
    }
  }

  AlertDialog(
    onDismissRequest = { onDone(false) },
    containerColor = MaterialTheme.colorScheme.background,
    title = { Text(t("privacy_lock_enable"), style = SunType.Heading) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(t("lock_scope_does"), style = SunType.Body)
        Text(t("lock_scope_doesnt"), style = SunType.Body)
        OutlinedTextField(
          value = one, onValueChange = { one = it; error = null }, label = { Text(t("privacy_pass_new")) }, singleLine = true,
          visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = two, onValueChange = { two = it; error = null }, label = { Text(t("privacy_pass_again")) }, singleLine = true,
          visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
        )
        Text(t("privacy_pass_min", Passphrase.MIN_LENGTH), style = SunType.Caption)
        if (prompt != null) CheckRow(t("privacy_use_os_label", osPromptName(prompt)), null, useOs, true, { useOs = !useOs })
        else Text(t("privacy_os_none"), style = SunType.Caption)
        Text(t("privacy_pass_forgot"), style = SunType.Caption)
        error?.let { Notice(it, bad = true) }
      }
    },
    confirmButton = { SunButton(onClick = ::go, enabled = one.isNotEmpty()) { Text(t("privacy_lock_enable")) } },
    dismissButton = { SunTextButton(onClick = { onDone(false) }) { Text(t("auth_cancel")) } },
  )
}

/**
 * The "N hidden" chip beside the filters: how many pictures are blurred right now. Pressing it shows them all (when the app is
 * unlocked); once some are shown it reads "Hide again". It is not a filter: nothing leaves the results.
 */
@Composable
fun HiddenChip(blur: PreviewBlur, files: Collection<IndexedFile>, modifier: Modifier = Modifier) {
  val hidden = blur.hiddenCount(files)
  if (hidden > 0) {
    FilterChip(tn("hidden_chip", hidden), PrivacyIcons.EyeOff, selected = false, onClick = { blur.revealAll(files) }, modifier = modifier)
  } else if (blur.anyRevealed(files)) {
    FilterChip(t("hidden_cover"), PrivacyIcons.Eye, selected = true, onClick = { blur.coverAll(files) }, modifier = modifier)
  }
}

/** A chip that shows or puts away sensitive files (the ones marked by hand or in a private folder); asking for proof to show. */
@Composable
fun SensitiveChip(ui: PrivacyUi, modifier: Modifier = Modifier) {
  val revealed by ui.c.revealed.collectAsState()
  val settings by ui.c.settings.collectAsState()
  if (!revealed && settings.rules.markedFiles.isEmpty() && settings.rules.privateFolders.isEmpty()) return
  if (revealed) FilterChip(t("sensitive_hide"), PrivacyIcons.EyeOff, selected = true, onClick = ui::hideSensitive, modifier = modifier)
  else FilterChip(t("sensitive_show"), PrivacyIcons.Eye, selected = false, onClick = { ui.revealSensitive() }, modifier = modifier)
}

/** The row of a hidden picture's eye: shows this one picture, or covers it again. */
@Composable
fun RevealEye(file: IndexedFile, blur: PreviewBlur, modifier: Modifier = Modifier) {
  if (!blur.looksPrivate(file)) return
  val hidden = blur.hides(file)
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    SunTextButton(onClick = { if (hidden) blur.reveal(file) else blur.cover(file) }) {
      Icon(if (hidden) PrivacyIcons.Eye else PrivacyIcons.EyeOff, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(Sun.S8))
      Text(if (hidden) t("hidden_eye_show") else t("hidden_eye_hide"))
    }
  }
}
