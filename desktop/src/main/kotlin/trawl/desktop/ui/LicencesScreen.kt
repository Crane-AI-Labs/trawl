package trawl.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.LicenceNotice
import trawl.desktop.LicenceText
import trawl.desktop.Licences
import trawl.desktop.RuntimeInfo
import trawl.desktop.t

/** The notices screen: what the licences require (the model's terms, the NOTICE files), then everything else, then the full texts. */
@Composable
fun LicencesScreen(c: AppController) {
  val book = remember { Licences.load() }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Sun.S16, vertical = Sun.S8), verticalAlignment = Alignment.CenterVertically) {
      SunTextButton(onClick = c::closeLicences) {
        Icon(Icons.Back, contentDescription = null, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(Sun.S8))
        Text(t("action_back"), style = SunType.Label)
      }
      Spacer(Modifier.width(Sun.S12))
      Text(t("licences_title"), style = SunType.Title, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
      Spacer(Modifier.weight(1f))
      KeyCap("Esc")
    }
    SunDivider()
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      SelectionContainer(Modifier.widthIn(max = 880.dp).fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Sun.S24, end = Sun.S24, top = Sun.S8, bottom = Sun.S32)) {
          item {
            Text(
              t("licences_intro", t("app_name")), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = Sun.S8),
            )
          }
          item {
            Text(
              t("settings_about_runtime", RuntimeInfo.display), style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = Sun.S8),
            )
          }
          item { SectionHeading(t("licences_section_required")) }
          items(book.required) { NoticeRow(it) }
          item { SectionHeading(t("licences_section_components", t("app_name"))) }
          items(book.components) { NoticeRow(it) }
          item { SectionHeading(t("licences_section_texts")) }
          items(book.texts) { FullText(it) }
        }
      }
    }
  }
}

/** One credit: the name and its licence on one line, the link under it, the words the licence needs in the quieter ink. */
@Composable
private fun NoticeRow(n: LicenceNotice) {
  val tk = LocalSunlight.current
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S8)) {
    Text(n.name, style = SunType.Label, color = tk.ink)
    if (n.meta.isNotEmpty()) Text(n.meta, style = SunType.Caption, color = tk.ink2)
    if (n.link.isNotEmpty()) Text(n.link, style = SunType.Caption, color = tk.ink2)
    if (n.text.isNotEmpty()) {
      Spacer(Modifier.height(Sun.S4))
      Text(n.text, style = SunType.Body, color = tk.ink)
    }
    if (n.includes.isNotEmpty()) {
      Spacer(Modifier.height(Sun.S4))
      Text(t("licences_includes", n.includes), style = SunType.Caption, color = tk.ink2)
    }
  }
  SunDivider()
}

@Composable
private fun FullText(text: LicenceText) {
  val tk = LocalSunlight.current
  var open by remember(text.resource) { mutableStateOf(false) }
  val body = remember(text.resource, open) { if (open) Licences.read(text.resource).orEmpty() else "" }
  val toggleDescription = t(if (open) "licences_hide_named" else "licences_show_named", text.title)
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S8)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text.title, style = SunType.Label, color = tk.ink, modifier = Modifier.weight(1f))
      SunOutlinedButton(onClick = { open = !open }, modifier = Modifier.semantics { contentDescription = toggleDescription }) {
        Text(t(if (open) "licences_hide" else "licences_show"), style = SunType.Label)
      }
    }
    if (open) {
      Spacer(Modifier.height(Sun.S8))
      Text(body, style = SunType.Caption, color = tk.ink2)
    }
  }
  SunDivider()
}
