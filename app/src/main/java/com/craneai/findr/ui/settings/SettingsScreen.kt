package com.craneai.findr.ui.settings

import com.craneai.findr.engine.Edition
import com.craneai.findr.ui.theme.Palette
import com.craneai.findr.ui.common.appString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.craneai.findr.R
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.ui.privacy.PrivacySettings
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.PackStatus
import com.craneai.findr.ui.addons.AddOnCard
import com.craneai.findr.quick.QuickPrefs
import com.craneai.findr.ui.addons.AddOnsViewModel
import com.craneai.findr.ui.addons.AddPackDialog
import com.craneai.findr.ui.addons.RemovePackDialog
import com.craneai.findr.ui.addons.title
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.language.AppLocale
import com.craneai.findr.ui.language.LanguageDialog
import com.craneai.findr.ui.theme.FilterTile
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunSectionHeading
import com.craneai.findr.ui.theme.SunSwitch
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.ThemeMode
import com.craneai.findr.ui.theme.sunBorder
import com.craneai.findr.ui.theme.sunFocusRing
import com.craneai.findr.ui.theme.sunContentWidth
import com.craneai.findr.ui.theme.sunSideInset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  indexViewModel: IndexViewModel,
  addOnsViewModel: AddOnsViewModel,
  deliveryViewModel: com.craneai.findr.ui.delivery.DeliveryViewModel,
  themeMode: ThemeMode,
  onThemeMode: (ThemeMode) -> Unit,
  onOpenLicences: () -> Unit,
  palette: Palette = Palette.SUNLIGHT,
  onPalette: (Palette) -> Unit = {},
  onBack: () -> Unit,
) {
  val settings by indexViewModel.settings.collectAsStateWithLifecycle()
  val status by indexViewModel.status.collectAsStateWithLifecycle()
  val deleting by indexViewModel.deleting.collectAsStateWithLifecycle()
  val forgotten by indexViewModel.forgotten.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var confirmDelete by rememberSaveable { mutableStateOf(false) }
  var pickingLanguage by rememberSaveable { mutableStateOf(false) }
  val addOnRows by addOnsViewModel.rows.collectAsStateWithLifecycle()
  var addingId by rememberSaveable { mutableStateOf<String?>(null) }
  var removingId by rememberSaveable { mutableStateOf<String?>(null) }

  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    SettingsTopBar(stringResource(R.string.settings_title), onBack, Modifier.sunContentWidth().statusBarsPadding())
    Column(
      Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = sunSideInset(Sun.Gutter), vertical = Sun.S8),
    ) {
      SunSectionHeading(appString(R.string.bg_settings_section))
      if (com.craneai.findr.ui.background.BackgroundAccess.appliesHere()) {   // TECNO, Infinix, itel: the phone may pause apps with the screen off
        com.craneai.findr.ui.background.BackgroundAccessSettingsRow()
      }
      // Every phone (setup asks the same thing once): let Trawl keep reading when it is not in front. Android's own yes/no box, or the
      // app's battery page where there is none; shows "Allowed" when it is on, read again when coming back. On Samsung this is the same
      // state as Battery > Unrestricted, so it replaces the old Samsung-only row (the Home card hides itself once it is on).
      com.craneai.findr.ui.background.BatteryExemptionSettingsRow()

      // Trawl Go reads words only, which is light enough to do on battery: it has no charger-only tier to choose.
      if (!Edition.GO) {
        SunSectionHeading(appString(R.string.settings_section_reading))
        LearningSpeedPicker(settings.learningSpeed, onSpeed = { v -> indexViewModel.update { it.copy(learningSpeed = v) } })
      }

      SunSectionHeading(appString(R.string.scope_title))
      FolderScopeEditor(indexViewModel)

      SunSectionHeading(appString(R.string.settings_section_kinds))
      KindSwitches(settings, indexViewModel)

      // Trawl Go has no blur scorer and no add-ons (they are models): these two sections do not exist there.
      if (Edition.MODELS) {
        SunSectionHeading(appString(R.string.settings_section_previews))
        // Blurring needs the "Blur private-looking previews" add-on (a pack, not in the app): without it these say so and stay off.
        val blurPack = addOnRows.firstOrNull { it.def.id == PackCatalog.PRIVACY }
        val packReady = blurPack == null || (blurPack.status == PackStatus.INSTALLED && blurPack.on)
        val needsPack = stringResource(R.string.settings_blur_needs_pack)
        SwitchRow(
          title = stringResource(R.string.settings_blur_title),
          hint = if (packReady) stringResource(R.string.settings_blur_hint) else needsPack,
          checked = settings.blurPrivatePreviews && packReady,
          enabled = packReady,
          icon = Icons.Rounded.VisibilityOff,
          onChange = { v -> indexViewModel.update { it.copy(blurPrivatePreviews = v) } },
        )
        val appContext = LocalContext.current
        val quickPrefs = remember { QuickPrefs(appContext) }
        var panelBlur by remember { mutableStateOf(quickPrefs.blurPrivate) }
        SwitchRow(
          title = stringResource(R.string.settings_blur_panel_title),
          hint = if (packReady) stringResource(R.string.settings_blur_panel_hint) else needsPack,
          checked = panelBlur && packReady,
          enabled = packReady,
          icon = Icons.Rounded.VisibilityOff,
          onChange = { v -> panelBlur = v; quickPrefs.blurPrivate = v },
        )

        SunSectionHeading(appString(R.string.settings_section_addons))
        Text(appString(R.string.addons_intro), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Sun.S12))
        Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
          com.craneai.findr.ui.delivery.ModelPackCard(deliveryViewModel)
          com.craneai.findr.ui.delivery.UpdateNoticeCard(deliveryViewModel)
          addOnRows.forEach { row ->
            AddOnCard(
              row = row,
              onAdd = { addingId = row.def.id },
              onRemove = { removingId = row.def.id },
              onSwitch = { on -> addOnsViewModel.setOn(row.def.id, on) },
            )
          }
        }

      }

      SunSectionHeading(appString(R.string.settings_section_privacy))
      PrivacySettings(settings) { transform -> indexViewModel.update(transform) }

      SunSectionHeading(appString(R.string.settings_section_storage))
      StorageRow(stringResource(R.string.settings_storage_index), formatBytes(status.indexBytes), hint = stringResource(R.string.settings_storage_index_hint))
      if (status.estimatedIndexBytes > 0 && status.phase != IndexPhase.UP_TO_DATE) {
        StorageRow(
          stringResource(R.string.settings_storage_estimate),
          stringResource(R.string.settings_storage_estimate_value, formatBytes(status.estimatedIndexBytes)),
        )
      }
      if (Edition.MODELS) {   // Trawl Go keeps no vectors, so there is no search memory to report
        StorageRow(
          stringResource(R.string.settings_storage_memory),
          formatBytes(status.searchMemoryBytes),
          hint = appString(R.string.settings_storage_memory_hint, formatBytes(status.estimatedSearchMemoryBytes)),
        )
      }
      if (Edition.MODELS) {
        StorageRow(stringResource(R.string.settings_storage_model), formatBytes(status.modelBytes), hint = stringResource(R.string.settings_storage_model_hint))
        addOnRows.filter { it.status == PackStatus.INSTALLED }.forEach { row ->
          StorageRow(row.def.title(), formatBytes(row.installedBytes), onRemove = { removingId = row.def.id })
        }
      }
      Spacer(Modifier.height(Sun.S8))
      Text(appString(R.string.settings_storage_no_data), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S16))
      Text(appString(R.string.settings_delete_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      SunOutlinedButton(
        onClick = { confirmDelete = true },
        enabled = !deleting,
        danger = true,
        modifier = Modifier.fillMaxWidth().padding(top = Sun.S12),
      ) {
        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Sun.S8))
        Text(appString(R.string.settings_delete), style = SunType.Label)
      }
      forgotten?.let {
        Text(
          appString(R.string.settings_delete_done, formatBytes(it.bytes)),
          style = SunType.Body,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = Sun.S12),
        )
      }

      SunSectionHeading(appString(R.string.settings_section_appearance))
      // Colours first (Sunlight or the wallpaper's), then light or dark, which applies to both.
      PalettePicker(palette, onPalette, themeMode)
      Spacer(Modifier.height(Sun.S16))
      Text(stringResource(R.string.settings_brightness), style = SunType.Label, color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = Sun.S8).semantics { heading() })
      // Theme: three radio choices; selected is the marker under ink (the same rule as every Sunlight choice). They wrap at large font.
      FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Sun.S8),
        verticalArrangement = Arrangement.spacedBy(Sun.S8),
        maxItemsInEachRow = 3,
      ) {
        ThemeMode.entries.forEach { mode ->
          FilterTile(
            label = stringResource(
              when (mode) {
                ThemeMode.SYSTEM -> R.string.settings_theme_system
                ThemeMode.LIGHT -> R.string.settings_theme_light
                ThemeMode.DARK -> R.string.settings_theme_dark
              },
            ),
            icon = when (mode) {
              ThemeMode.SYSTEM -> Icons.Rounded.PhoneAndroid
              ThemeMode.LIGHT -> Icons.Rounded.LightMode
              ThemeMode.DARK -> Icons.Rounded.DarkMode
            },
            selected = mode == themeMode,
            onClick = { onThemeMode(mode) },
            modifier = Modifier.weight(1f),
          )
        }
      }
      NavRow(
        icon = Icons.Rounded.Language,
        title = stringResource(R.string.settings_language),
        subtitle = AppLocale.effective(context).nativeName,
        onClick = { pickingLanguage = true },
        modifier = Modifier.padding(top = Sun.S8),
      )

      SunSectionHeading(appString(R.string.settings_section_report))
      com.craneai.findr.ui.report.ReportProblemSection()

      SunSectionHeading(appString(R.string.settings_section_about))
      About(onLicences = onOpenLicences)
      Spacer(Modifier.height(Sun.S24))
    }
  }

  if (pickingLanguage) LanguageDialog(onDismiss = { pickingLanguage = false })

  addOnRows.firstOrNull { it.def.id == addingId }?.let { row ->
    AddPackDialog(
      row = row,
      howTo = addOnsViewModel.howToAdd(row.def.id),
      onInstall = { addingId = null; addOnsViewModel.install(row.def.id) },
      onDismiss = { addingId = null },
    )
  }
  addOnRows.firstOrNull { it.def.id == removingId }?.let { row ->
    RemovePackDialog(
      row = row,
      onRemove = { removingId = null; addOnsViewModel.remove(row.def.id) },
      onDismiss = { removingId = null },
    )
  }

  if (confirmDelete) {
    // The body is long, so the dialog scrolls it instead of clipping it at large font sizes.
    SettingsDialog(
      title = appString(R.string.settings_delete_title),
      confirmLabel = stringResource(R.string.settings_delete_confirm),
      dismissLabel = stringResource(R.string.settings_cancel),
      danger = true,
      onConfirm = {
        confirmDelete = false
        // Thumbnails the image loader holds: in memory, and on disk if it ever caches there (the engine wipes the cache folder too).
        SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() }
        indexViewModel.deleteIndex()
      },
      onDismiss = { confirmDelete = false },
    ) {
      Text(appString(R.string.settings_delete_body), style = SunType.Body)
    }
  }
}

/** The page bar of Settings and Licences: a 56 dp Back arrow and the page title, which TalkBack reads as a heading. */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(horizontal = Sun.S4, vertical = Sun.S4), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack, modifier = Modifier.size(Sun.Target)) {
      Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back), modifier = Modifier.size(Sun.Icon))
    }
    Text(
      title,
      style = SunType.Title,
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.weight(1f).padding(end = Sun.S16).semantics { heading() },
    )
  }
}

/**
 * A settings row that switches something: the whole row is the switch (Role.Switch, 64 dp at least), the title in Body large,
 * the one-sentence hint in Body ink-2, and the On/Off track drawn at the end. [icon] is the 24 dp lead where a page shows one.
 */
@Composable
internal fun SwitchRow(
  title: String,
  hint: String?,
  checked: Boolean,
  enabled: Boolean = true,
  icon: ImageVector? = null,
  onChange: (Boolean) -> Unit,
) {
  val shape = MaterialTheme.shapes.small
  Row(
    Modifier
      .fillMaxWidth()
      .sunFocusRing(shape)
      .clip(shape)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
      .heightIn(min = Sun.SettingsRow)
      .padding(vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f), modifier = Modifier.size(Sun.Icon))
      Spacer(Modifier.width(Sun.S16))
    }
    Column(Modifier.weight(1f).padding(end = Sun.S12)) {
      Text(title, style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f))
      if (hint != null) Text(hint, style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SunSwitch(checked = checked, enabled = enabled)
  }
}

/** A settings row that opens something (Language, Licences): lead icon, title, optional subtitle, and a chevron that points the reading way. */
@Composable
internal fun NavRow(icon: ImageVector?, title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val shape = MaterialTheme.shapes.small
  Row(
    modifier
      .fillMaxWidth()
      .sunFocusRing(shape)
      .clip(shape)
      .clickable(role = Role.Button, onClick = onClick)
      .heightIn(min = Sun.SettingsRow)
      .padding(vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(Sun.Icon))
      Spacer(Modifier.width(Sun.S16))
    }
    Column(Modifier.weight(1f).padding(end = Sun.S12)) {
      Text(title, style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface)
      if (subtitle != null) Text(subtitle, style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(Sun.Icon))
  }
}

/** An error is always an icon and a word in the danger colour, never a colour alone. */
@Composable
internal fun SunErrorText(text: String, modifier: Modifier = Modifier) {
  val danger = MaterialTheme.colorScheme.error
  Row(modifier, verticalAlignment = Alignment.Top) {
    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = danger, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S8))
    Text(text, style = SunType.Body, color = danger, modifier = Modifier.weight(1f, fill = false))
  }
}

/**
 * A Sunlight dialog with custom content (SunDialog takes only a sentence): 2 dp edge, 12 dp corners, paper ground, the safe answer a
 * text button and the action the primary (or the danger fill). The content scrolls, so a long body or 200% font never clips.
 */
@Composable
internal fun SettingsDialog(
  title: String,
  confirmLabel: String,
  dismissLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  danger: Boolean = false,
  confirmEnabled: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.sunBorder(MaterialTheme.shapes.medium),
    shape = MaterialTheme.shapes.medium,
    containerColor = scheme.background,
    titleContentColor = scheme.onBackground,
    textContentColor = scheme.onBackground,
    tonalElevation = 0.dp,
    title = { Text(title, style = SunType.Heading) },
    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Sun.S8), content = content) },
    confirmButton = {
      if (danger) {
        Button(
          onClick = onConfirm,
          enabled = confirmEnabled,
          modifier = Modifier.heightIn(min = Sun.Target),
          shape = MaterialTheme.shapes.small,
          colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
        ) { Text(confirmLabel) }
      } else {
        SunButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
      }
    },
    dismissButton = { SunTextButton(onClick = onDismiss) { Text(dismissLabel) } },
  )
}

@Composable
private fun KindSwitches(settings: FindrSettings, vm: IndexViewModel) {
  SwitchRow(stringResource(R.string.kind_plural_photos), null, settings.indexPhotos) { v -> vm.update { it.copy(indexPhotos = v) } }
  SwitchRow(stringResource(R.string.kind_plural_screenshots), null, settings.indexScreenshots) { v -> vm.update { it.copy(indexScreenshots = v) } }
  SwitchRow(stringResource(R.string.kind_plural_videos), null, settings.indexVideos) { v -> vm.update { it.copy(indexVideos = v) } }
  SwitchRow(stringResource(R.string.kind_plural_documents), null, settings.indexDocuments) { v -> vm.update { it.copy(indexDocuments = v) } }
  // Sound is read by a speech model and an audio model: Trawl Go has neither, so its sound files are found by name only.
  if (Edition.SPEECH) {
    SwitchRow(stringResource(R.string.settings_recordings), stringResource(R.string.settings_recordings_hint), settings.indexRecordings) { v -> vm.update { it.copy(indexRecordings = v) } }
    SwitchRow(stringResource(R.string.settings_deeper), stringResource(R.string.settings_deeper_hint), settings.indexAudio) { v -> vm.update { it.copy(indexAudio = v) } }
  }
}

@Composable
private fun StorageRow(label: String, value: String, hint: String? = null, onRemove: (() -> Unit)? = null) {
  val removeDescription = if (onRemove != null) stringResource(R.string.settings_storage_remove_named, label) else ""
  Row(Modifier.fillMaxWidth().heightIn(min = Sun.SettingsRow), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f).padding(vertical = Sun.S8)) {
      Text(label, style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface)
      if (hint != null) Text(hint, style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(value, style = SunType.Label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = Sun.S12))
    if (onRemove != null) {
      SunTextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = removeDescription }) {
        Text(stringResource(R.string.settings_storage_remove), style = SunType.Label)
      }
    }
  }
}

@Composable
private fun About(onLicences: () -> Unit) {
  val context = LocalContext.current
  val version = remember {
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
  }
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Text(stringResource(R.string.settings_about_version, version), style = SunType.Label, color = MaterialTheme.colorScheme.onSurface)
    Text(stringResource(R.string.settings_about_model), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(appString(R.string.settings_about_private), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.settings_about_maker), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  NavRow(icon = null, title = stringResource(R.string.settings_licences), subtitle = null, onClick = onLicences, modifier = Modifier.padding(top = Sun.S8))
}
