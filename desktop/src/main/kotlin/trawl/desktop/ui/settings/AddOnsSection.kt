package trawl.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.craneai.findr.ui.common.formatBytes
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import trawl.desktop.AddOnCatalog
import trawl.desktop.AddOnDef
import trawl.desktop.AddOnManager
import trawl.desktop.AddOnOffer
import trawl.desktop.AddOnProblem
import trawl.desktop.AddOnState
import trawl.desktop.AddOnUpdate
import trawl.desktop.Platform
import trawl.desktop.UpdateNotice
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.ui.Badge
import trawl.desktop.ui.Notice
import trawl.desktop.ui.SectionHeading
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunCard
import trawl.desktop.ui.SunDialog
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunProgress
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType

/** The words for why an add-on was refused: `addon_problem_<name>`. */
fun addOnProblemText(p: AddOnProblem): String = when (p) {
  AddOnProblem.NOT_FOUND -> t("addon_problem_not_found")
  AddOnProblem.BAD_FILES -> t("addon_problem_bad_files")
  AddOnProblem.NOT_SIGNED -> t("addon_problem_not_signed")
  AddOnProblem.BAD_SIGNATURE -> t("addon_problem_bad_signature")
  AddOnProblem.DAMAGED -> t("addon_problem_damaged")
  AddOnProblem.NOT_THIS_ADDON -> t("addon_problem_not_this_addon")
  AddOnProblem.OLDER_VERSION -> t("addon_problem_older_version")
  AddOnProblem.NO_SPACE -> t("addon_problem_no_space")
  AddOnProblem.CANCELLED -> t("addon_problem_cancelled")
  AddOnProblem.UNAVAILABLE -> t("addon_problem_unavailable")
}

/**
 * Settings, Add-ons: one card per optional pack. Add looks in Downloads (and the usual folders); Choose a file takes one the person
 * picks, a zip, a signed bundle or an unzipped folder. Every file is checked before use (a signature, or a fixed fingerprint
 * compiled into Trawl), and Trawl adds nothing the person did not ask for. A removed add-on stays removed.
 *
 * @param modelsDir the folder add-ons install into (for the update notice)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddOnsSection(manager: AddOnManager, privacy: PrivacyController, modelsDir: File, speechInstalled: Boolean = false, searchFolders: () -> List<File> = { Platform.userFolders().let { listOf(it.downloads, it.documents) } }) {
  val states by manager.states.collectAsState()
  val settings by privacy.settings.collectAsState()
  var removing by remember { mutableStateOf<AddOnDef?>(null) }

  // The update notice reads a few zip headers in the usual folders: off the UI thread, and again after any change.
  val updates by produceState(emptyList<AddOnUpdate>(), states, settings.dismissedUpdates) {
    value = withContext(Dispatchers.IO) { runCatching { UpdateNotice.find(modelsDir, searchFolders(), dismissed = privacy::updateDismissed) }.getOrDefault(emptyList()) }
  }

  SectionHeading(t("settings_addons_title"))
  Text(t("addons_intro"), style = SunType.Body, color = MaterialTheme.colorScheme.onBackground)
  Spacer(Modifier.height(Sun.S12))
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    for (def in AddOnCatalog.all) {
      val update = updates.firstOrNull { it.packId == def.id }
      if (def.bundled) BundledCard(def, speechInstalled)
      else AddOnCard(def, states[def.id] ?: AddOnState.NotInstalled, declined = privacy.declined(def.id), update = update, manager = manager, privacy = privacy, onRemove = { removing = def })
    }
    Text(t("addons_checked"), style = SunType.Caption)
  }
  removing?.let { def ->
    val size = def.shownSize(states[def.id] ?: AddOnState.NotInstalled)
    SunDialog(
      title = t("addons_remove_title", t(def.nameKey)), body = t("addons_remove_body", size),
      confirmLabel = t("addons_remove_confirm"), dismissLabel = t("auth_cancel"),
      onConfirm = { manager.remove(def.id); removing = null }, onDismiss = { removing = null }, danger = true,
    )
  }
}

/**
 * F4: the first-run page's "Optional extras": the same cards as Settings, for the add-ons that can be added on this computer now (taken
 * once when the page opens). Nothing is required and nothing is added on its own; the line under the cards says they can wait.
 */
@Composable
fun FirstRunAddOns(manager: AddOnManager, privacy: PrivacyController) {
  val ids by produceState<List<String>?>(null) {
    withContext(Dispatchers.IO) { runCatching { manager.refresh() } }
    value = AddOnOffer.idsAtFirstRun(AddOnCatalog.all, manager.states.value)
  }
  val offered = ids?.mapNotNull(AddOnCatalog::byId).orEmpty()
  if (offered.isEmpty()) return
  val states by manager.states.collectAsState()
  val tk = com.craneai.findr.ui.theme.LocalSunlight.current
  Spacer(Modifier.height(Sun.S32))
  Text(t("first_addons_title"), style = SunType.Title, color = tk.ink)
  Spacer(Modifier.height(Sun.S4))
  Text(t("first_addons_body"), style = SunType.Body, color = tk.ink2)
  Spacer(Modifier.height(Sun.S12))
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    for (def in offered) AddOnCard(def, states[def.id] ?: AddOnState.NotInstalled, declined = privacy.declined(def.id), update = null, manager = manager, privacy = privacy, onRemove = {})
    Text(t("first_addons_skip"), style = SunType.Caption)
  }
}

/** An add-on that comes with the models file (Speech): says whether it is there and where it comes from; nothing to add or remove here. */
@Composable
internal fun BundledCard(def: AddOnDef, installed: Boolean) {
  SunCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
      Row(horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(t(def.nameKey), style = SunType.Heading, modifier = Modifier.weight(1f))
        Badge(t(if (installed) "addons_status_included" else "addons_status_not_added"), marker = installed)
      }
      Text(t(def.benefitKey), style = SunType.Body)
      Text(t(if (installed) "addons_bundled_on" else "addons_bundled_off"), style = SunType.Caption)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddOnCard(def: AddOnDef, state: AddOnState, declined: Boolean, update: AddOnUpdate?, manager: AddOnManager, privacy: PrivacyController, onRemove: () -> Unit) {
  SunCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
      Row(horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(t(def.nameKey), style = SunType.Heading, modifier = Modifier.weight(1f))
        Badge(
          when {
            !def.available -> t("addons_status_soon")
            !def.supported() -> t("addons_status_unsupported")
            state is AddOnState.Installed -> t("addons_status_added")
            state is AddOnState.Installing -> t("addons_status_adding")
            else -> t("addons_status_not_added")
          },
          marker = state is AddOnState.Installed,
        )
      }
      Text(t(def.benefitKey), style = SunType.Body)
      Text(t(def.costKey, def.shownSize(state)), style = SunType.Caption)

      when {
        !def.available -> Text(t("addons_soon_hint"), style = SunType.Caption)
        !def.supported() -> Text(t("addons_unsupported_hint"), style = SunType.Caption)
        state is AddOnState.Installing -> {
          SunProgress(state.fraction)
          Text(state.fraction?.let { t("addons_adding_pct", (it * 100).toInt().toString()) } ?: t("addons_adding"), style = SunType.Caption)
        }
        state is AddOnState.Installed -> SunOutlinedButton(onClick = onRemove, danger = true) { Text(t("addons_remove", def.shownSize(state))) }
        else -> {
          if (state is AddOnState.Failed) Notice(addOnProblemText(state.problem), bad = true)
          else if (declined) Text(t("addons_declined"), style = SunType.Caption)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
            SunButton(onClick = { manager.install(def.id) }) {
              Text(if (state is AddOnState.Failed) t("addons_try_again") else t("addons_add_with_size", formatBytes(def.sizeBytes)))
            }
            SunOutlinedButton(onClick = { chooseAddOnFile()?.let { manager.install(def.id, it) } }) { Text(t("addons_add_from_file")) }
          }
        }
      }

      if (update != null && state is AddOnState.Installed) {
        Notice(t("addons_update_title", t(def.nameKey)), bad = false)
        Text(t("addons_update_body", update.newVersion.toString(), update.installedVersion.toString()), style = SunType.Caption)
        Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          SunButton(onClick = { manager.install(def.id, update.file) }) { Text(t("addons_update_install")) }
          SunTextButton(onClick = { privacy.dismissUpdate(def.id, update.newVersion) }) { Text(t("addons_update_later")) }
        }
      }
    }
  }
}

/** The system's chooser for an add-on file: a zip, a signed bundle, or an unzipped pack folder. Starts in Downloads. Blocking: call from a click. */
private fun chooseAddOnFile(): File? {
  val start = Platform.userFolders().downloads.takeIf { it.isDirectory }
  val chooser = JFileChooser(start).apply {
    fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
    dialogTitle = t("addons_add_from_file")
  }
  return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
