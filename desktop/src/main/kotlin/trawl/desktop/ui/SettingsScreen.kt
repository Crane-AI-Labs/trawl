package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import java.io.File
import kotlinx.coroutines.launch
import trawl.desktop.AppController
import trawl.desktop.AppInfo
import trawl.desktop.RuntimeInfo
import trawl.desktop.IndexUi
import trawl.desktop.Languages
import trawl.desktop.ContrastChoice
import trawl.desktop.Scope
import trawl.desktop.Settings
import trawl.desktop.TextSize
import trawl.desktop.ThemeChoice
import trawl.desktop.WatchInfo
import trawl.desktop.WatchMode
import trawl.desktop.t
import trawl.desktop.ui.settings.AddOnsSection
import trawl.desktop.tn

/** The Settings pages, in the order the section list shows them. Each is a group of related settings under one heading. */
private enum class SettingsSection(val navKey: String) {
  FILES("settings_nav_files"), READING("settings_nav_reading"), MODELS("settings_nav_models"),
  LOOK("settings_nav_look"), PRIVACY("settings_nav_privacy"), DATA("settings_nav_data"), HELP("settings_nav_help"),
}

/**
 * Settings. On a wide window a section list sits on the left (it follows the scroll and jumps to a section when chosen) and the page is on
 * the right; narrower, the same page is one column under a Back bar. Esc goes back (App.kt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(c: AppController, scroll: androidx.compose.foundation.ScrollState = rememberScrollState()) {
  val tk = LocalSunlight.current
  val settings by c.settings.collectAsState()
  val counts by c.folderCounts.collectAsState()
  val watch by c.watch.collectAsState()
  val model by c.model.collectAsState()
  val storage by c.storage.collectAsState()
  val forgot by c.forgot.collectAsState()
  val index by c.index.collectAsState()
  var skipProblem by remember { mutableStateOf<Scope.SkipProblem?>(null) }
  var removing by remember { mutableStateOf<String?>(null) }
  var forgetting by remember { mutableStateOf(false) }
  var reporting by remember { mutableStateOf(false) }

  val tops = remember { mutableStateMapOf<SettingsSection, Int>() }
  val scope = rememberCoroutineScope()
  val ordered = SettingsSection.entries
  val active by remember {
    derivedStateOf { Layout.activeSection(ordered.map { tops[it] ?: Int.MAX_VALUE }, scroll.value, scroll.maxValue) }
  }

  @Composable
  fun Section(section: SettingsSection, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().onGloballyPositioned { tops[section] = it.positionInParent().y.toInt() }.padding(bottom = Sun.S32)) {
      Text(t(section.navKey), style = SunType.Title, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
      SunDivider(Modifier.padding(top = Sun.S8))
      content()
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val rail = Layout.showRail(maxWidth.value.toInt())
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.fillMaxWidth().padding(horizontal = Sun.S16, vertical = Sun.S8), verticalAlignment = Alignment.CenterVertically) {
        SunTextButton(onClick = c::closeSettings) {
          Icon(Icons.Back, contentDescription = null, modifier = Modifier.size(Sun.Icon))
          Spacer(Modifier.width(Sun.S8))
          Text(t("action_back"), style = SunType.Label)
        }
        Spacer(Modifier.width(Sun.S12))
        Text(t("settings_title"), style = SunType.Title, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        if (rail) KeyCap("Esc")
      }
      SunDivider()
      Row(Modifier.fillMaxSize()) {
        if (rail) {
          Column(
            Modifier.width((236f * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.3f)).dp).fillMaxHeight().padding(Sun.S16).semantics { contentDescription = t("settings_nav_label") },
            verticalArrangement = Arrangement.spacedBy(Sun.S4),
          ) {
            ordered.forEachIndexed { i, section ->
              RailItem(t(section.navKey), selected = i == active) {
                val top = tops[section] ?: return@RailItem
                scope.launch { scroll.animateScrollTo(top) }
              }
            }
          }
          Box(Modifier.width(Sun.Hairline).fillMaxHeight().background(tk.line2.copy(alpha = 0.55f)))
        }
        Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
          Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S20)) {
            Section(SettingsSection.FILES) {
              Folders(c, settings.folders, counts, index.blocked, onRemove = { removing = it })
              WhatToRead(c, settings, index)
              SkippedFolders(c, settings, skipProblem, onProblem = { skipProblem = it })
            }

            Section(SettingsSection.READING) {
              SectionHeading(t("learn_speed_title"))
              FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
                FilterChip(t("learn_gentle"), null, settings.learningSpeed == LearningSpeed.GENTLE, { c.setSpeed(LearningSpeed.GENTLE) })
                FilterChip(t("learn_balanced"), null, settings.learningSpeed == LearningSpeed.BALANCED, { c.setSpeed(LearningSpeed.BALANCED) })
                FilterChip(t("learn_fast"), null, settings.learningSpeed == LearningSpeed.FAST, { c.setSpeed(LearningSpeed.FAST) })
              }
              Spacer(Modifier.height(Sun.S8))
              Text(
                t(when (settings.learningSpeed) { LearningSpeed.GENTLE -> "learn_gentle_hint"; LearningSpeed.BALANCED -> "learn_balanced_hint"; LearningSpeed.FAST -> "learn_fast_hint" }),
                style = SunType.Body, color = tk.ink,
              )
              index.ratePerMin?.let { Text(tn("settings_speed_rate", it.toInt(), formatCount(it.toInt())), style = SunType.Caption, color = tk.ink2) }
              Spacer(Modifier.height(Sun.S12))
              PauseButton(c, settings.indexPaused)

              SectionHeading(t("settings_watch_title"))
              watchLines(watch).forEach { Text(it, style = SunType.Body, color = tk.ink) }
            }

            Section(SettingsSection.MODELS) {
              SectionHeading(t("settings_models_title"))
              ModelsPanel(c, model)

              SectionHeading(t("settings_ocr_title"))
              Text(t("settings_ocr_${c.ocrKind}"), style = SunType.Body, color = tk.ink)
            }

            Section(SettingsSection.LOOK) {
              SectionHeading(t("settings_appearance_title"))
              Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
                FilterChip(t("settings_theme_system"), null, settings.theme == ThemeChoice.SYSTEM, { c.setTheme(ThemeChoice.SYSTEM) })
                FilterChip(t("settings_theme_light"), null, settings.theme == ThemeChoice.LIGHT, { c.setTheme(ThemeChoice.LIGHT) })
                FilterChip(t("settings_theme_dark"), null, settings.theme == ThemeChoice.DARK, { c.setTheme(ThemeChoice.DARK) })
              }

              SectionHeading(t("text_size_title"))
              FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
                TextSize.entries.forEach { size -> FilterChip(t("text_size_${size.id}"), null, settings.textSize == size, { c.setTextSize(size) }) }
              }
              Spacer(Modifier.height(Sun.S8))
              Text(t("text_size_hint"), style = SunType.Caption, color = tk.ink2)

              SectionHeading(t("contrast_title"))
              FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
                ContrastChoice.entries.forEach { ch -> FilterChip(t("contrast_${ch.id}"), null, settings.contrast == ch, { c.setContrast(ch) }) }
              }
              Spacer(Modifier.height(Sun.S8))
              Text(t("contrast_hint"), style = SunType.Caption, color = tk.ink2)

              SectionHeading(t("settings_language_title"))
              FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
                FilterChip(t("settings_language_system"), null, settings.language == null, { c.setLanguage(null) })
                Languages.shipped.forEach { l ->
                  val label = if (l.preview) "${l.nativeName} (${t("settings_language_preview")})" else l.nativeName
                  FilterChip(label, null, settings.language == l.tag, { c.setLanguage(l.tag) })
                }
              }

              if (c.autostartAvailable) {
                val startAtSignIn by c.autostart.collectAsState()
                SectionHeading(t("settings_startup_title"))
                CheckRow(t("settings_startup_label"), t("settings_startup_hint"), startAtSignIn, true, { c.setAutostart(!startAtSignIn) })
              }

              // Package D: the quick-search shortcut recorder, the tray icon and keep-running. It draws nothing when there is no shell (tests, demos).
              Spacer(Modifier.height(Sun.S24))
              trawl.desktop.ui.quick.ShellSettings()
            }

            Section(SettingsSection.PRIVACY) {
              val addOnStates by c.addOns.states.collectAsState()
              val canRun = trawl.desktop.privacy.PrivacyRuntime.usable()
              trawl.desktop.ui.privacy.LocalPrivacyUi.current?.let { pui ->
                trawl.desktop.ui.privacy.PrivacySection(pui, addOnStates[com.craneai.findr.core.PackIds.PRIVACY] is trawl.desktop.AddOnState.Installed && canRun, supported = canRun, roots = settings.folders.map(::File))
              }
              Spacer(Modifier.height(Sun.S24))
              AddOnsSection(c.addOns, c.privacy, c.modelsDir, speechInstalled = index.speechPack)
            }

            Section(SettingsSection.DATA) {
              SectionHeading(t("settings_section_storage"))
              StorageRow(t("settings_storage_index"), formatBytes(storage.indexBytes))
              StorageRow(t("settings_storage_model"), formatBytes(storage.modelBytes))
              if (storage.cacheBytes > 0) StorageRow(t("settings_storage_cache"), formatBytes(storage.cacheBytes))
              Spacer(Modifier.height(Sun.S8))
              Text(t("settings_storage_files", tn("count_photos", storage.photos), tn("count_screenshots", storage.screenshots), tn("count_documents_pdfs", storage.documents)) +
                if (storage.videos + storage.recordings > 0) " " + t("settings_storage_media", tn("count_videos", storage.videos), tn("count_recordings", storage.recordings)) else "", style = SunType.Body, color = tk.ink)
              Spacer(Modifier.height(Sun.S8))
              Text(t("settings_data_body", c.dataFolder), style = SunType.Caption, color = tk.ink2)
              Spacer(Modifier.height(Sun.S12))
              Text(t("settings_delete_hint"), style = SunType.Body, color = tk.ink)
              Spacer(Modifier.height(Sun.S12))
              SunOutlinedButton(onClick = { forgetting = true }, danger = true) { Text(t("settings_forget"), style = SunType.Label) }
              forgot?.let { Spacer(Modifier.height(Sun.S12)); Notice(trawl.desktop.ForgetNote.line(it), bad = false) }
            }

            Section(SettingsSection.HELP) {
              SectionHeading(t("settings_section_report"))
              Text(t("settings_report_open_hint"), style = SunType.Body, color = tk.ink)
              Spacer(Modifier.height(Sun.S12))
              FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S12), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
                SunOutlinedButton(onClick = { reporting = true }) { Text(t("settings_report_open"), style = SunType.Label) }
                SunOutlinedButton(onClick = c::openLogFolder) { Text(t("settings_report_logs"), style = SunType.Label) }
              }

              SectionHeading(t("settings_section_about"))
              Text(t("settings_about_version", AppInfo.VERSION), style = SunType.Body, color = tk.ink)
              Text(t("settings_about_maker"), style = SunType.Body, color = tk.ink)
              Text(t("settings_about_runtime", RuntimeInfo.display), style = SunType.Body, color = tk.ink)
              Text(t("settings_about_model"), style = SunType.Body, color = tk.ink)
              Spacer(Modifier.height(Sun.S12))
              SunOutlinedButton(onClick = c::openLicences) { Text(t("licences_title"), style = SunType.Label) }
              Spacer(Modifier.height(Sun.S16))
              Notice(t("settings_about_private"), bad = false)
            }
          }
        }
      }
    }
  }

  removing?.let { path ->
    SunDialog(
      title = t("dialog_remove_folder_title"), body = t("dialog_remove_folder_body", File(path).name.ifBlank { path }),
      confirmLabel = t("dialog_remove_confirm"), dismissLabel = t("action_cancel"), danger = true,
      onConfirm = { c.removeFolder(path); removing = null }, onDismiss = { removing = null },
    )
  }
  if (reporting) {
    trawl.desktop.ui.report.ReportDialog(c.reporter, onClose = { reporting = false }, openLogFolder = c::openLogFolder)
  }
  if (forgetting) {
    SunDialog(
      title = t("dialog_forget_title"), body = t("dialog_forget_body"), confirmLabel = t("dialog_forget_confirm"), dismissLabel = t("action_cancel"), danger = true,
      onConfirm = { c.forgetEverything(); forgetting = false }, onDismiss = { forgetting = false },
    )
  }
}

/** One entry of the section list: marker-filled when it is the section on screen, with a keyboard focus ring like every control. */
@Composable
private fun RailItem(label: String, selected: Boolean, onClick: () -> Unit) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(10.dp)
  Row(
    Modifier.fillMaxWidth().heightIn(min = Sun.Button).sunFocus(shape).clip(shape).pointerHoverIcon(PointerIcon.Hand)
      .background(if (selected) tk.marker else Color.Transparent, shape)
      .border(if (selected) Sun.Border else 0.dp, if (selected) tk.onMarker else Color.Transparent, shape)
      .selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(horizontal = Sun.S12, vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = if (selected) SunType.Label else SunType.Body, color = if (selected) tk.onMarker else tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** The chosen folders, each with its file count and a Remove button; Add a folder and Rescan underneath. */
@Composable
private fun Folders(c: AppController, folders: List<String>, counts: Map<String, Int>, blocked: trawl.desktop.BlockedSummary, onRemove: (String) -> Unit) {
  val tk = LocalSunlight.current
  SectionHeading(t("settings_folders_title"))
  Text(t("settings_folders_body"), style = SunType.Body, color = tk.ink)
  Spacer(Modifier.height(Sun.S8))
  if (folders.isEmpty()) Text(t("settings_folders_empty"), style = SunType.Body, color = tk.ink2)
  folders.forEach { path ->
    SunCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
      Row(Modifier.padding(start = Sun.S16, top = 6.dp, bottom = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Folder, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(Sun.S12))
        Column(Modifier.weight(1f)) {
          Text(path, style = SunType.Body, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
          counts[path]?.let { Text(tn("settings_folder_files", it, formatCount(it)), style = SunType.Caption, color = tk.ink2) }
        }
        // Every folder has the same visible word "Remove"; the spoken name says which one.
        SunTextButton(onClick = { onRemove(path) }, modifier = Modifier.semantics { contentDescription = t("speak_remove_named", File(path).name.ifBlank { path }) }) { Text(t("action_remove"), style = SunType.Label) }
      }
    }
  }
  trawl.desktop.BlockedNotice.lines(blocked).forEachIndexed { i, line ->
    Spacer(Modifier.height(Sun.S8))
    if (i == 0 || blocked.other > 0 && blocked.system == 0) Notice(line, bad = true) else Text(line, style = SunType.Caption, color = tk.ink2)
  }
  Spacer(Modifier.height(Sun.S8))
  Row(horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
    SunOutlinedButton(onClick = { Pickers.folder()?.let(c::addFolder) }) {
      Icon(Icons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
      Text(t("action_add_folder"), style = SunType.Label)
    }
    SunTextButton(onClick = c::rescan) { Text(t("action_rescan"), style = SunType.Label) }
  }
}

/**
 * Which kinds of file Trawl reads. Its own composable on purpose: the lines inside are the original ones, so the work that adds videos and
 * recordings here merges as a few added lines rather than a rewrite of the page.
 */
@Composable
private fun WhatToRead(c: AppController, settings: Settings, index: IndexUi) {
  val tk = LocalSunlight.current
  SectionHeading(t("settings_kinds_title"))
  Text(t("scope_intro"), style = SunType.Body, color = tk.ink)
  Spacer(Modifier.height(Sun.S8))
  CheckRow(t("kind_plural_photos"), null, settings.indexPhotos, true, { c.setIndexPhotos(!settings.indexPhotos) })
  CheckRow(t("kind_plural_screenshots"), null, settings.indexScreenshots, true, { c.setIndexScreenshots(!settings.indexScreenshots) })
  CheckRow(t("kind_plural_documents"), null, settings.indexDocuments, true, { c.setIndexDocuments(!settings.indexDocuments) })
  CheckRow(t("kind_plural_videos"), null, settings.indexVideos, true, { c.setIndexVideos(!settings.indexVideos) })
  CheckRow(t("kind_plural_audio"), t("settings_recordings_hint"), settings.indexAudio, true, { c.setIndexAudio(!settings.indexAudio) })
  trawl.desktop.ui.settings.HeicRouteNote(settings.indexPhotos)
  if ((settings.indexVideos || settings.indexAudio) && index.mediaDecoder == "none") {
    Spacer(Modifier.height(Sun.S8))
    Notice(t(if (trawl.desktop.Platform.os == trawl.desktop.Os.LINUX) "settings_media_no_ffmpeg" else "settings_media_no_decoder"), bad = true)
  }
  if (settings.indexAudio && !index.speechPack && index.mediaDecoder != null) {
    Spacer(Modifier.height(Sun.S8))
    Text(t("settings_media_no_speech"), style = SunType.Caption, color = tk.ink2)
  }
  if (!settings.indexPhotos && !settings.indexScreenshots && !settings.indexDocuments && !settings.indexVideos && !settings.indexAudio) {
    Spacer(Modifier.height(Sun.S8))
    Notice(t("settings_kinds_all_off"), bad = true)
  }
}

/** Folders inside the chosen ones that Trawl leaves alone, and the add button with its plain-words problem line. */
@Composable
private fun SkippedFolders(c: AppController, settings: Settings, problem: Scope.SkipProblem?, onProblem: (Scope.SkipProblem?) -> Unit) {
  val tk = LocalSunlight.current
  SectionHeading(t("settings_section_excluded"))
  Text(t("settings_excluded_hint"), style = SunType.Body, color = tk.ink)
  Spacer(Modifier.height(Sun.S8))
  if (settings.excludedFolders.isEmpty()) Text(t("settings_excluded_empty"), style = SunType.Body, color = tk.ink2)
  settings.excludedFolders.forEach { path ->
    SunCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
      Row(Modifier.padding(start = Sun.S16, top = 6.dp, bottom = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.FolderOpen, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(Sun.S12))
        Text(path, style = SunType.Body, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SunTextButton(onClick = { c.unskipFolder(path); onProblem(null) }) {
          Text(t("settings_excluded_remove", File(path).name.ifBlank { path }), style = SunType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
  Spacer(Modifier.height(Sun.S8))
  SunOutlinedButton(onClick = { onProblem(Pickers.folder(settings.folders.firstOrNull()?.let(::File), t("settings_excluded_pick"))?.let(c::skipFolder)) }, enabled = settings.folders.isNotEmpty()) {
    Icon(Icons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(t("settings_excluded_add"), style = SunType.Label)
  }
  problem?.let {
    Spacer(Modifier.height(Sun.S8))
    Notice(
      t(when (it) {
        Scope.SkipProblem.NOT_INSIDE -> "settings_excluded_not_inside"
        Scope.SkipProblem.IS_CHOSEN_FOLDER -> "settings_excluded_is_chosen"
        Scope.SkipProblem.NOT_A_FOLDER -> "settings_excluded_not_folder"
      }),
      bad = true,
    )
  }
}

@Composable
private fun StorageRow(label: String, value: String) {
  val tk = LocalSunlight.current
  Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = SunType.Body, color = tk.ink, modifier = Modifier.weight(1f))
    Text(value, style = SunType.Label, color = tk.ink)
  }
}

/** Pause or Resume reading; the same choice the meter on the main screen offers. */
@Composable
fun PauseButton(c: AppController, paused: Boolean) {
  SunOutlinedButton(onClick = { c.setPaused(!paused) }) { Text(t(if (paused) "action_resume" else "action_pause"), style = SunType.Label) }
}

/** What the watcher is doing, in words: the system watching, the inotify limit that forced polling, or plain polling. */
fun watchLines(w: WatchInfo): List<String> {
  if (w.modes.isEmpty()) return emptyList()
  val out = mutableListOf<String>()
  val modes = w.modes.values
  if (WatchMode.TREE in modes) out += t("settings_watch_native")
  if (WatchMode.DIRS in modes) out += tn("settings_watch_dirs", w.watchedDirs, formatCount(w.watchedDirs))
  if (w.anyPolling) {
    val limit = w.inotifyLimit
    out += if (limit != null) t("settings_watch_limit", w.pollSeconds, formatCount(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
    else tn("settings_watch_polling", w.pollSeconds, w.pollSeconds)
  }
  if (out.isEmpty()) out += t("settings_watch_native")
  return out
}
