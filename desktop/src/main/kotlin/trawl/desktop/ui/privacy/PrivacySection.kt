package trawl.desktop.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import trawl.desktop.Os
import trawl.desktop.Platform
import trawl.desktop.privacy.LoadState
import trawl.desktop.privacy.OsAvailability
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.privacy.PrivacySettings
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.CheckRow
import trawl.desktop.ui.FilterChip
import trawl.desktop.ui.Notice
import trawl.desktop.ui.SectionHeading
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunType

/**
 * Settings, Privacy: the app lock, the blur, keeping the window out of screenshots, and what is marked. It says in its first lines
 * what the lock covers and what it does not, so nobody turns it on believing the index is encrypted.
 *
 * @param packInstalled the Privacy pack is on this computer (without it the blur has nothing to read)
 * @param roots the folders Trawl searches (the private-folders list is relative to them)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacySection(
  ui: PrivacyUi, packInstalled: Boolean, os: Os = Platform.os, supported: Boolean = true,
  roots: List<java.io.File> = emptyList(), pickFolder: (() -> java.io.File?)? = null,
) {
  val c: PrivacyController = ui.c
  val s: PrivacySettings by c.settings.collectAsState()
  val availability by c.osAvailability.collectAsState()
  val prompt = (availability as? OsAvailability.Available)?.prompt
  val tk = MaterialTheme.colorScheme

  SectionHeading(t("settings_privacy_title"))
  Text(t("settings_privacy_intro"), style = SunType.Body, color = tk.onBackground)
  when (c.loadState) {
    LoadState.DAMAGED -> Notice(t("privacy_damaged"), bad = true, modifier = Modifier.padding(top = Sun.S8))
    LoadState.RECOVERED -> Notice(t("privacy_recovered"), bad = false, modifier = Modifier.padding(top = Sun.S8))
    else -> Unit
  }
  Spacer(Modifier.height(Sun.S12))

  SunCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
      Text(t("privacy_lock_label"), style = SunType.Label)
      Text(t("privacy_lock_hint"), style = SunType.Body)
      // The plain statement, always visible next to the switch.
      Text(t("lock_scope_doesnt"), style = SunType.Caption)
      if (!s.lockEnabled) {
        SunButton(onClick = ui::startEnableLock) { Text(t("privacy_lock_enable")) }
        if (prompt == null) Text(t("privacy_os_none"), style = SunType.Caption)
      } else {
        Notice(t("privacy_lock_on"), bad = false)
        Text(t("privacy_lock_after"), style = SunType.Label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
          for (minutes in PrivacySettings.LOCK_AFTER_CHOICES) {
            FilterChip(
              if (minutes == 0) t("privacy_after_0") else tn("privacy_after", minutes), null, selected = s.lockAfterMinutes == minutes,
              onClick = { c.setLockAfterMinutes(minutes) },
            )
          }
        }
        if (prompt != null) CheckRow(t("privacy_use_os_label", osPromptName(prompt)), null, s.useOsPrompt, true, { c.setUseOsPrompt(!s.useOsPrompt) })
        else Text(t("privacy_os_none"), style = SunType.Caption)
        Text(t("privacy_pass_forgot"), style = SunType.Caption)
        SunOutlinedButton(onClick = { ui.proveChange { c.disableLock() } }, danger = true) { Text(t("privacy_lock_disable")) }
      }
    }
  }

  Spacer(Modifier.height(Sun.S12))
  // Where the scoring engine cannot load (an Intel Mac), the blur is off and says so, instead of promising something that cannot work.
  val canBlur = packInstalled && supported
  CheckRow(
    t("privacy_blur_label"),
    t(when { !supported -> "privacy_blur_unsupported"; packInstalled -> "privacy_blur_hint"; else -> "privacy_blur_needs_pack" }),
    s.blurEnabled && canBlur, canBlur, { c.setBlurEnabled(!s.blurEnabled) },
  )

  val captureHint = when (os) {
    Os.WINDOWS -> "privacy_capture_hint_windows"
    Os.MAC -> "privacy_capture_hint_mac"
    Os.LINUX -> "privacy_capture_hint_linux"
  }
  CheckRow(t("privacy_capture_label"), t(captureHint), s.hideFromCapture && os != Os.LINUX, os != Os.LINUX, { c.setHideFromCapture(!s.hideFromCapture) })

  val marked = s.rules.markedFiles.size
  if (marked > 0) Text(tn("privacy_marked", marked), style = SunType.Body, modifier = Modifier.padding(top = Sun.S8))
  if (pickFolder != null) PrivateFolders(ui, roots, pickFolder) else PrivateFolders(ui, roots)
}
