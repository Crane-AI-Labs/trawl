package trawl.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import java.util.Locale
import trawl.desktop.AppController
import trawl.desktop.AppLanguage
import trawl.desktop.Languages
import trawl.desktop.t

/**
 * The first thing a new person sees: the language, before anything is explained in a language they may not read. It lists the 14 languages
 * in their own words and preselects the one the computer is set to (English when the computer's language is not one of the 14). Choosing
 * one redraws the window in it at once, so the person sees the effect; Continue goes on to the usual first-run page.
 */
object LanguagePage {
  /** Whether the page still has to be shown this run. Held here, not in a `remember`: choosing a language redraws the whole window from the top. */
  var pending: Boolean by mutableStateOf(true)

  /** The language the computer is set to, among the shipped ones; English when it is none of them. */
  fun preselect(computer: Locale = Locale.getDefault(), shipped: List<AppLanguage> = Languages.shipped): AppLanguage =
    shipped.firstOrNull { it.tag.equals(computer.language, ignoreCase = true) } ?: shipped.first { it.tag == "en" }

  /** The language ticked on the page: the person's pick when there is one, else the computer's. */
  fun selected(saved: String?, computer: Locale = Locale.getDefault()): AppLanguage = Languages.byTag(saved) ?: preselect(computer)
}

@Composable
fun LanguagePageScreen(c: AppController) {
  val settings by c.settings.collectAsState()
  LanguagePageContent(LanguagePage.selected(settings.language), LanguagePage.preselect(), { c.setLanguage(it) }, { LanguagePage.pending = false })
}

/** The page itself, with no controller, so it can be drawn on its own (the screenshots do). */
@Composable
fun LanguagePageContent(selected: AppLanguage, computer: AppLanguage, onPick: (String) -> Unit, onContinue: () -> Unit) {
  val tk = LocalSunlight.current
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(horizontal = Sun.S24, vertical = Sun.S32)) {
      Wordmark(markSize = 48.dp)
      Spacer(Modifier.height(Sun.S16))
      Text(t("language_title"), style = SunType.Display, color = MaterialTheme.colorScheme.onBackground)
      Spacer(Modifier.height(Sun.S12))
      Text(t("language_intro", t("app_name")), style = SunType.BodyLarge, color = tk.ink)
      Spacer(Modifier.height(Sun.S16))
      Languages.shipped.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(bottom = Sun.S8), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          pair.forEach { l ->
            val label = if (l.preview) "${l.nativeName} (${t("settings_language_preview")})" else l.nativeName
            FilterChip(label, null, l.tag == selected.tag, { onPick(l.tag) }, Modifier.weight(1f))
          }
          if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
      }
      Spacer(Modifier.height(Sun.S4))
      // Said in the language on screen; it names the computer's language in its own words so it reads in any of them.
      Text("${computer.nativeName}: ${t("language_this_computer")}", style = SunType.Caption, color = tk.ink2)
      Spacer(Modifier.height(Sun.S24))
      SunButton(onClick = onContinue) { Text(t("language_continue"), style = SunType.Label) }
    }
  }
}
