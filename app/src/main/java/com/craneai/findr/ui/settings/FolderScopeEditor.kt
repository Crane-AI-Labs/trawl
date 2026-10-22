package com.craneai.findr.ui.settings

import com.craneai.findr.ui.common.appString
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.R
import com.craneai.findr.index.FolderGroups
import com.craneai.findr.index.folderScope
import com.craneai.findr.ui.common.relativeFolderFromDocumentId
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunType
import java.util.Locale

/**
 * "What should it search?": one switch per place people keep files (with how many files are there), the folders
 * added by hand, and the folders to leave out. Shared by onboarding and Settings, which put their own heading above it.
 * Switching a place off takes its files out of search straight away; switching it on reads them again.
 */
@Composable
fun FolderScopeEditor(vm: IndexViewModel, modifier: Modifier = Modifier, refreshKey: Any? = Unit) {
  val settings by vm.settings.collectAsStateWithLifecycle()
  val counts by vm.groupCounts.collectAsStateWithLifecycle()
  val scope = settings.folderScope()
  var pickerError by remember { mutableStateOf(false) }

  // Counted again when [refreshKey] changes (onboarding passes whether file access is on).
  androidx.compose.runtime.LaunchedEffect(refreshKey) { vm.refreshGroupCounts() }

  fun pickedFolder(uri: android.net.Uri?, then: (String) -> Unit) {
    if (uri == null) return
    val folder = runCatching { relativeFolderFromDocumentId(DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
    pickerError = folder == null
    if (folder != null) then(folder)
  }
  val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pickedFolder(it, vm::addFolder) }
  val excludePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pickedFolder(it, vm::excludeFolder) }

  Column(modifier) {
    Text(stringResource(R.string.scope_intro), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sun.S8))
    FolderGroups.ALL.forEach { group ->
      val n = counts?.get(group.id)
      // Once the phone has been counted, a place with nothing in it is left out of the list instead of showing "0 files".
      if (counts != null && (n ?: 0) == 0) return@forEach
      val label = groupLabel(group.id) ?: return@forEach
      SwitchRow(
        title = stringResource(label),
        hint = n?.let { filesLabel(it) },
        checked = scope.isOn(group),
        icon = groupIcon(group.id),
        onChange = { vm.setGroup(group.id, it) },
      )
    }
    if (counts == null) {
      Text(appString(R.string.scope_counts_unknown), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sun.S4))
    }
    Text(stringResource(R.string.scope_private), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Sun.S8))

    Spacer(Modifier.height(Sun.S24))
    Text(stringResource(R.string.scope_added_title), style = SunType.Label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
    settings.includedFolders.sorted().forEach { folder ->
      FolderRow(Icons.Rounded.Folder, folder, stringResource(R.string.scope_added_remove, folder)) { vm.removeAddedFolder(folder) }
    }
    SunOutlinedButton(onClick = { addPicker.launch(null) }, modifier = Modifier.fillMaxWidth().padding(top = Sun.S8)) {
      Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(Sun.S8))
      Text(stringResource(R.string.scope_add), style = SunType.Label)
    }

    Spacer(Modifier.height(Sun.S24))
    Text(stringResource(R.string.settings_section_excluded), style = SunType.Label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
    Text(appString(R.string.settings_excluded_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    settings.excludedFolders.sorted().forEach { folder ->
      FolderRow(Icons.Rounded.FolderOff, folder, stringResource(R.string.settings_excluded_remove, folder)) { vm.stopExcluding(folder) }
    }
    if (pickerError) {
      SunErrorText(stringResource(R.string.settings_excluded_bad), Modifier.padding(vertical = Sun.S8))
    }
    SunOutlinedButton(onClick = { excludePicker.launch(null) }, modifier = Modifier.fillMaxWidth().padding(top = Sun.S8)) {
      Icon(Icons.Rounded.FolderOff, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(Sun.S8))
      Text(stringResource(R.string.scope_exclude), style = SunType.Label)
    }
  }
}

/** The icon that goes with a place, so a switch row says what it is by picture as well as by word. */
internal fun groupIcon(id: String): ImageVector = when (id) {
  FolderGroups.CAMERA -> Icons.Rounded.PhotoCamera
  FolderGroups.SCREENSHOTS -> Icons.Rounded.Screenshot
  FolderGroups.WHATSAPP_IMAGES, FolderGroups.WHATSAPP_VIDEO, FolderGroups.WHATSAPP_VOICE, FolderGroups.WHATSAPP_STATUS -> Icons.AutoMirrored.Rounded.Chat
  FolderGroups.WHATSAPP_DOCUMENTS, FolderGroups.DOCUMENTS -> Icons.Rounded.Description
  FolderGroups.DOWNLOADS -> Icons.Rounded.Download
  FolderGroups.MUSIC -> Icons.Rounded.MusicNote
  else -> Icons.Rounded.Folder
}

/** The label of a folder group, or null for an id this build has no words for (a group added by a newer catalogue). */
@StringRes
internal fun groupLabel(id: String): Int? = when (id) {
  FolderGroups.CAMERA -> R.string.scope_group_camera
  FolderGroups.SCREENSHOTS -> R.string.scope_group_screenshots
  FolderGroups.WHATSAPP_IMAGES -> R.string.scope_group_whatsapp_images
  FolderGroups.WHATSAPP_VIDEO -> R.string.scope_group_whatsapp_video
  FolderGroups.WHATSAPP_DOCUMENTS -> R.string.scope_group_whatsapp_documents
  FolderGroups.WHATSAPP_VOICE -> R.string.scope_group_whatsapp_voice
  FolderGroups.WHATSAPP_STATUS -> R.string.scope_group_whatsapp_status
  FolderGroups.DOWNLOADS -> R.string.scope_group_downloads
  FolderGroups.DOCUMENTS -> R.string.scope_group_documents
  FolderGroups.MUSIC -> R.string.scope_group_music
  FolderGroups.OTHER -> R.string.scope_group_other
  else -> null
}

@Composable
private fun filesLabel(n: Int): String {
  @PluralsRes val plural = R.plurals.scope_files
  return LocalContext.current.resources.getQuantityString(plural, n, String.format(Locale.getDefault(), "%,d", n))
}

@Composable
private fun FolderRow(icon: ImageVector, folder: String, removeLabel: String, onRemove: () -> Unit) {
  // A folder's name may ellipsize (its middle, so the last part stays); nothing else here is cut.
  Row(Modifier.fillMaxWidth().heightIn(min = Sun.Target), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S12))
    Text(folder, style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
    IconButton(onClick = onRemove, modifier = Modifier.size(Sun.Target)) {
      Icon(Icons.Rounded.Close, contentDescription = removeLabel, tint = MaterialTheme.colorScheme.onSurface)
    }
  }
}
