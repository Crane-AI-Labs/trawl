package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.theme.LocalSunlight
import java.io.File
import trawl.desktop.AppController
import trawl.desktop.ModelUi
import trawl.desktop.t

/**
 * First run. On a wide window the welcome (what Trawl is, the animated demo, the two promises) sits on the left and the setup (folders,
 * models) on the right with Start pinned under it; narrower, one column with the same parts in the same order.
 */
@Composable
fun FirstRunScreen(c: AppController) {
  val choices by c.choices.collectAsState()
  val model by c.model.collectAsState()
  val settings by c.settings.collectAsState()
  val canStart = choices.any { it.checked && it.exists }
  if (LanguagePage.pending) { LanguagePageScreen(c); return }   // hook (Package C): the language page comes first
  // Once the person starts choosing folders the demo has done its job and stays still.
  var engaged by remember { mutableStateOf(false) }
  val setup = FirstSetup(choices, model, settings.modelsDeclined, canStart, onToggle = { engaged = true; c.toggleChoice(it) }, onAdd = { engaged = true; Pickers.folder()?.let(c::addChoice) },
    onStart = { engaged = true; if (model.installed == null) c.declineModels(); c.start() })

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = Layout.twoColumnFirstRun(maxWidth.value.toInt())
    if (wide) {
      Row(Modifier.fillMaxSize().widthIn(max = 1280.dp).align(Alignment.TopCenter)) {
        // Each side centres its content in the window's height when it fits, and scrolls when it does not.
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
          val h = maxHeight
          Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(start = Sun.S32, top = Sun.S24)) { Wordmark(markSize = 40.dp) }
            Column(Modifier.heightIn(min = h - 88.dp).padding(start = Sun.S32, end = Sun.S24, bottom = Sun.S24), verticalArrangement = Arrangement.spacedBy(Sun.S16, Alignment.CenterVertically)) {
              Welcome(c, engaged, wordmarkOutside = true)
            }
          }
        }
        Box(Modifier.width(Sun.Hairline).fillMaxHeight().background(LocalSunlight.current.line2))
        Column(Modifier.weight(1f).fillMaxHeight()) {
          BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val h = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
              Column(Modifier.heightIn(min = h).padding(start = Sun.S24, end = Sun.S32, top = Sun.S24, bottom = Sun.S16), verticalArrangement = Arrangement.Center) { Setup(c, setup) }
            }
          }
          StartBar(setup, Modifier.padding(horizontal = Sun.S32, vertical = Sun.S16))
        }
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Sun.S24, vertical = Sun.S24), horizontalAlignment = Alignment.CenterHorizontally) {
          Column(Modifier.widthIn(max = 640.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(Sun.S16)) { Welcome(c, engaged, compact = true) }
            Spacer(Modifier.height(Sun.S24))
            Setup(c, setup)
          }
        }
        StartBar(setup, Modifier.padding(horizontal = Sun.S24, vertical = Sun.S12))
      }
    }
  }
}

private class FirstSetup(
  val choices: List<trawl.desktop.FolderChoice>, val model: ModelUi, val modelsDeclined: Boolean, val canStart: Boolean,
  val onToggle: (String) -> Unit, val onAdd: () -> Unit, val onStart: () -> Unit,
)

@Composable
private fun Welcome(c: AppController, engaged: Boolean, wordmarkOutside: Boolean = false, compact: Boolean = false) {
  val tk = LocalSunlight.current
  if (!wordmarkOutside) Wordmark(markSize = 40.dp)
  Text(t("welcome_title"), style = SunType.Display, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.a11yHeading())
  Text(t("welcome_line"), style = SunType.BodyLarge, color = tk.ink)
  FirstRunDemo(engaged, compact = compact)
  Row(horizontalArrangement = Arrangement.spacedBy(Sun.S24)) {
    Point(Icons.Lock, t("welcome_point_private_title"), t("welcome_point_private_body"), Modifier.weight(1f))
    Point(Icons.Check, t("welcome_point_reads_title"), t("welcome_point_reads_body"), Modifier.weight(1f))
  }
  trawl.desktop.ui.quick.ShortcutLine()   // shell (Package D): the quick-search shortcut, shown only when it is really on
}

@Composable
private fun Setup(c: AppController, s: FirstSetup) {
  val tk = LocalSunlight.current
  Text(t("first_folders_title"), style = SunType.Title, color = tk.ink, modifier = Modifier.a11yHeading())
  Spacer(Modifier.height(Sun.S4))
  Text(t("first_folders_body"), style = SunType.Body, color = tk.ink2)
  Spacer(Modifier.height(Sun.S12))
  SunCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S8)) {
      s.choices.forEach { ch ->
        CheckRow(File(ch.path).let { it.name.ifBlank { it.path } }, if (ch.exists) ch.path else "${ch.path} · ${t("first_folders_missing")}", ch.checked, ch.exists, { s.onToggle(ch.path) })
      }
    }
  }
  Spacer(Modifier.height(Sun.S12))
  SunOutlinedButton(onClick = s.onAdd) {
    Icon(Icons.Add, contentDescription = null, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(Sun.S8))
    Text(t("action_add_folder"), style = SunType.Label)
  }

  if (!s.modelsDeclined || s.model.installed != null) {
    Spacer(Modifier.height(Sun.S32))
    Text(t("first_models_title"), style = SunType.Title, color = tk.ink, modifier = Modifier.a11yHeading())
    Spacer(Modifier.height(Sun.S4))
    Text(t("first_models_body"), style = SunType.Body, color = tk.ink2)
    Spacer(Modifier.height(Sun.S12))
    ModelsPanel(c, s.model)
    if (s.model.installed == null && !s.model.installing) {
      Spacer(Modifier.height(Sun.S8))
      Text(t("first_models_skip_note"), style = SunType.Caption, color = tk.ink2)
    }
  }
  // F4: the optional extras (the Privacy pack), the same cards as Settings. Nothing here is required.
  trawl.desktop.ui.settings.FirstRunAddOns(c.addOns, c.privacy)
}

/** Start sits under the setup, always in view, with the hint beside it while nothing is picked. */
@Composable
private fun StartBar(s: FirstSetup, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Column {
    SunDivider()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
      SunButton(onClick = s.onStart, enabled = s.canStart && !s.model.installing) { Text(t("action_start"), style = SunType.Label) }
      if (!s.canStart) {
        Spacer(Modifier.width(Sun.S16))
        Text(t("first_start_hint"), style = SunType.Body, color = tk.ink2)
      }
    }
  }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(Sun.S12), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = SunType.Label, color = tk.ink)
      Text(body, style = SunType.Caption, color = tk.ink2)
    }
  }
}

/** The models card, shared by first run and Settings: installed, found in Downloads, installing with progress, or choose a file. */
@Composable
fun ModelsPanel(c: AppController, model: ModelUi) {
  val tk = LocalSunlight.current
  SunCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
      when {
        model.installing -> {
          Text(t("first_models_installing", formatBytes(model.done), formatBytes(model.total)), style = SunType.Body, color = tk.ink)
          SunProgress(if (model.total > 0) (model.done.toFloat() / model.total) else null)
        }
        model.installed != null -> Notice(t("settings_models_ready", model.installed), bad = false)
        else -> {
          val found = model.found
          if (found != null) {
            Text(t("first_models_found", found.name, found.parentFile?.name ?: "", formatBytes(found.length())), style = SunType.Body, color = tk.ink)
            SunButton(onClick = { c.installModels(found) }) { Text(t("action_install"), style = SunType.Label) }
          } else {
            Text(t("first_models_none"), style = SunType.Body, color = tk.ink)
          }
          SunOutlinedButton(onClick = { Pickers.modelsZip(found?.parentFile)?.let(c::installModels) }) {
            Icon(Icons.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(t("action_choose_file"), style = SunType.Label)
          }
        }
      }
      model.message?.let { Notice(it, bad = model.failed) }
      model.zipOffer?.takeIf { !model.installing }?.let { zip ->
        Text(t("first_zip_question", zip.name, formatBytes(zip.length())), style = SunType.Body, color = tk.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          SunButton(onClick = c::deleteModelZip) { Text(t("first_zip_delete"), style = SunType.Label) }
          SunOutlinedButton(onClick = c::keepModelZip) { Text(t("first_zip_keep"), style = SunType.Label) }
        }
      }
    }
  }
}
