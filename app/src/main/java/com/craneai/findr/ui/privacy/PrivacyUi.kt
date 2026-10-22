package com.craneai.findr.ui.privacy

import com.craneai.findr.ui.common.appString
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.craneai.findr.R
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.privacy.PrivacyController
import com.craneai.findr.privacy.PrivacyPrefs
import com.craneai.findr.ui.common.relativeFolderFromDocumentId
import com.craneai.findr.ui.settings.SunErrorText
import com.craneai.findr.ui.settings.SwitchRow
import com.craneai.findr.ui.theme.FilterTile
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunDialog
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder
import com.craneai.findr.ui.theme.sunFocusRing

/** The process-wide lock and reveal state. Null in previews and tests, where nothing is ever locked or hidden. */
val LocalPrivacy = staticCompositionLocalOf<PrivacyController?> { null }

@Composable
private fun fragmentActivity(): FragmentActivity? = LocalActivity.current as? FragmentActivity

// ---- the lock screen ----------------------------------------------------------------------------------------------

/** Shown instead of the whole app while it is locked: no results, no file names, no thumbnails. */
@Composable
fun LockScreen(privacy: PrivacyController) {
  val activity = fragmentActivity()
  val title = appString(R.string.lock_prompt_title)
  val subtitle = stringResource(R.string.lock_prompt_subtitle)
  val owner = LocalLifecycleOwner.current
  // Ask once each time the screen comes to the front, unless the person just dismissed the prompt.
  LaunchedEffect(activity, owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (activity != null && privacy.shouldAutoPrompt()) privacy.requestUnlock(activity, title, subtitle)
    }
  }
  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(Sun.S32),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(Sun.S16))
    Text(
      appString(R.string.lock_title),
      style = SunType.Display,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Sun.S8))
    Text(
      stringResource(R.string.lock_body),
      style = SunType.Body,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Sun.S24))
    SunButton(onClick = { if (activity != null) privacy.requestUnlock(activity, title, subtitle) }) {
      Text(stringResource(R.string.lock_button), style = SunType.Label)
    }
  }
}

// ---- results: the "N hidden" chip ----------------------------------------------------------------------------------

/**
 * Under the meter on the home list. Hidden sensitive results are only counted: "3 hidden" with a Show button that asks for
 * the phone's unlock. Once shown, a quiet "Hide" puts them away again. Nothing is drawn when there is nothing to say.
 */
@Composable
fun SensitiveChip(hiddenCount: Int, shown: Boolean, modifier: Modifier = Modifier) {
  val privacy = LocalPrivacy.current ?: return
  val activity = fragmentActivity()
  val title = stringResource(R.string.privacy_reveal_title)
  val subtitle = stringResource(R.string.privacy_reveal_subtitle)
  if (hiddenCount > 0) {
    val label = stringResource(R.string.privacy_hidden_chip, hiddenCount)
    val description = pluralStringResource(R.plurals.privacy_hidden_chip_description, hiddenCount, hiddenCount)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      PrivacyChip(
        text = stringResource(R.string.chip_label_action, label, stringResource(R.string.privacy_hidden_action)),
        icon = Icons.Rounded.Lock,
        filled = false,
        onClick = { if (activity != null) privacy.requestReveal(activity, title, subtitle) },
        modifier = Modifier.semantics { contentDescription = description },
      )
    }
  } else if (shown) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      PrivacyChip(
        text = stringResource(R.string.chip_label_action, stringResource(R.string.privacy_shown_chip), stringResource(R.string.privacy_shown_action)),
        icon = Icons.Rounded.LockOpen,
        filled = true,
        onClick = { privacy.hideSensitive() },
        modifier = Modifier.semantics { selected = true },
      )
    }
  }
}

/** The 44 dp full-radius chip: a plain edge while results are hidden, the marker fill under ink while they are shown. */
@Composable
private fun PrivacyChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val t = LocalSunlight.current
  val fg = if (filled) t.onMarker else t.ink
  Row(
    modifier
      .minimumInteractiveComponentSize()   // 44 dp to look at, 56 dp to touch
      .sunFocusRing(CircleShape)
      .clip(CircleShape)
      .clickable(role = Role.Button, onClick = onClick)
      .background(if (filled) t.marker else t.paper)
      .sunBorder(CircleShape, color = if (filled) t.onMarker else t.line)
      .heightIn(min = Sun.Chip)
      .padding(horizontal = Sun.S16, vertical = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(Sun.S8))
    Text(text, style = SunType.Label, color = fg)
  }
}

/** Asked before a sensitive item leaves the phone (Share) or goes on the clipboard (Copy). Nothing is sent until it is accepted. */
@Composable
fun SensitiveLeaveDialog(copy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, many: Boolean = false) {
  SunDialog(
    title = stringResource(if (copy) R.string.sensitive_copy_title else R.string.sensitive_share_title),
    body = stringResource(
      if (copy) (if (many) R.string.sensitive_copy_body_many else R.string.sensitive_copy_body)
      else (if (many) R.string.sensitive_share_body_many else R.string.sensitive_share_body),
    ),
    confirmLabel = stringResource(if (copy) R.string.sensitive_copy_confirm else R.string.sensitive_share_confirm),
    dismissLabel = stringResource(R.string.sensitive_cancel),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
  )
}

// ---- the lock offer -------------------------------------------------------------------------------------------------

/**
 * Offered once, the first time a file is marked sensitive: that is when "someone holding my phone shouldn't see this" means
 * something (it used to be a card in onboarding, before there was any reason to want it). Turning it on asks for the phone's
 * own unlock. The caller offers it only on a phone that has a screen lock; either button closes it.
 */
@Composable
fun LockOfferDialog(onDone: () -> Unit) {
  val privacy = LocalPrivacy.current ?: return onDone()
  val activity = fragmentActivity()
  val prompt = appString(R.string.privacy_lock_on_prompt)
  SunDialog(
    title = appString(R.string.onb_lock_title),
    body = appString(R.string.onb_lock_body),
    confirmLabel = stringResource(R.string.onb_lock_action),
    dismissLabel = stringResource(R.string.models_update_not_now),
    onConfirm = { if (activity != null) privacy.setLockEnabled(activity, true, prompt, null); onDone() },
    onDismiss = onDone,
  )
}

// ---- settings --------------------------------------------------------------------------------------------------------

/** Settings, "Privacy": the lock, recents, and what counts as sensitive. [update] changes the engine's settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacySettings(settings: FindrSettings, update: ((FindrSettings) -> FindrSettings) -> Unit) {
  val privacy = LocalPrivacy.current ?: return
  val activity = fragmentActivity()
  val lockOn by privacy.prefs.lockEnabled.collectAsStateWithLifecycle()
  val lockAfter by privacy.prefs.lockAfterMinutes.collectAsStateWithLifecycle()
  val hideRecents by privacy.prefs.hideInRecents.collectAsStateWithLifecycle()
  val deviceSecure = remember(lockOn) { privacy.deviceSecure() }
  val screenLock = deviceSecure && remember(lockOn) { privacy.deviceLockAvailable() }
  var folderError by remember { mutableStateOf(false) }
  val revealTitle = stringResource(R.string.privacy_show_prompt)
  val lockOnPrompt = appString(R.string.privacy_lock_on_prompt)
  val lockOffPrompt = appString(R.string.privacy_lock_off_prompt)
  val folderPrompt = stringResource(R.string.privacy_folders_remove_prompt)

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    val folder = runCatching { relativeFolderFromDocumentId(DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
    if (folder == null) folderError = true else {
      folderError = false
      update { it.copy(privateFolders = it.privateFolders + folder) }   // hiding more never needs the unlock
    }
  }

  Column {
    // Said first, because it changes what every other switch here is worth.
    if (!deviceSecure) {
      SunErrorText(appString(R.string.privacy_no_screen_lock_note), Modifier.padding(bottom = Sun.S8))
    }
    PrivacySwitch(
      title = appString(R.string.privacy_lock_title),
      hint = appString(if (screenLock || lockOn) R.string.privacy_lock_hint else R.string.privacy_lock_needs_screen_lock),
      checked = lockOn,
      enabled = screenLock || lockOn,
      icon = Icons.Rounded.Fingerprint,
      onChange = { want -> if (activity != null) privacy.setLockEnabled(activity, want, if (want) lockOnPrompt else lockOffPrompt, null) },
    )
    if (lockOn) {
      Text(
        stringResource(R.string.privacy_lock_after_title),
        style = SunType.BodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Sun.S8).semantics { heading() },
      )
      FlowRow(
        Modifier.fillMaxWidth().padding(top = Sun.S8).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Sun.S8),
        verticalArrangement = Arrangement.spacedBy(Sun.S4),
      ) {
        PrivacyPrefs.LOCK_AFTER_CHOICES.forEach { minutes ->
          FilterTile(
            label = if (minutes == 0) stringResource(R.string.privacy_lock_after_now) else stringResource(R.string.privacy_lock_after_minutes, minutes),
            icon = Icons.Rounded.Schedule,
            selected = minutes == lockAfter,
            onClick = { privacy.setLockAfterMinutes(minutes) },
            compact = true,
            modifier = Modifier.minimumInteractiveComponentSize(),   // 44 dp to look at, 56 dp to touch
          )
        }
      }
      Text(appString(R.string.privacy_lock_after_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sun.S4, bottom = Sun.S4))
    }
    PrivacySwitch(
      title = appString(R.string.privacy_recents_title),
      hint = appString(R.string.privacy_recents_hint),
      checked = hideRecents,
      enabled = true,
      icon = Icons.Rounded.VisibilityOff,
      onChange = privacy::setHideInRecents,
    )
    PrivacySwitch(
      title = stringResource(R.string.privacy_show_title),
      hint = stringResource(R.string.privacy_show_hint),
      checked = settings.showSensitive,
      enabled = true,
      icon = Icons.Rounded.Lock,
      onChange = { want ->
        // Showing is the step that undoes the hiding, so it needs the phone's unlock. Hiding again needs nothing.
        if (!want) update { it.copy(showSensitive = false) }
        else if (activity != null) privacy.requestReveal(activity, revealTitle, null) { ok -> if (ok) update { it.copy(showSensitive = true) } }
      },
    )

    Row(Modifier.padding(top = Sun.S12), verticalAlignment = Alignment.Top) {
      Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp).size(Sun.Icon))
      Spacer(Modifier.width(Sun.S16))
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.privacy_folders_title), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.privacy_folders_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    if (settings.privateFolders.isEmpty()) {
      Text(stringResource(R.string.privacy_folders_empty), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = Sun.S8))
    } else {
      settings.privateFolders.sorted().forEach { folder ->
        Row(Modifier.fillMaxWidth().heightIn(min = Sun.Target), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sun.Icon))
          Spacer(Modifier.width(Sun.S12))
          Text(folder, style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
          IconButton(
            onClick = {
              // Making a folder searchable again is the unlocking direction: it needs the phone's unlock.
              if (activity != null) privacy.requestReveal(activity, folderPrompt, null) { ok ->
                if (ok) update { it.copy(privateFolders = it.privateFolders - folder) }
              }
            },
            modifier = Modifier.size(Sun.Target),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.privacy_folders_remove, folder), tint = MaterialTheme.colorScheme.onSurface)
          }
        }
      }
    }
    if (folderError) SunErrorText(stringResource(R.string.privacy_folders_bad), Modifier.padding(vertical = Sun.S4))
    SunOutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth().padding(top = Sun.S8)) {
      Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(Sun.S8))
      Text(stringResource(R.string.privacy_folders_add), style = SunType.Label)
    }

    if (deviceSecure) {
      Text(
        appString(R.string.privacy_pin_note),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Sun.S16),
      )
    }
  }
}

@Composable
private fun PrivacySwitch(title: String, hint: String, checked: Boolean, enabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onChange: (Boolean) -> Unit) {
  SwitchRow(title = title, hint = hint, checked = checked, enabled = enabled, icon = icon, onChange = onChange)
}
