package trawl.desktop.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.KindFilter
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.ResultsUi
import trawl.desktop.t
import trawl.desktop.ui.MainUi
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunType
import trawl.desktop.ui.a11yHeading

/** Which empty state to show, apart from how it is drawn (A19, A20, E14), so the choice is tested. */
enum class EmptyKind { SIMILAR, FRESH, FILTERED, IN_DATES, NO_MATCH }

fun emptyKind(r: ResultsUi, filter: KindFilter): EmptyKind = when {
  r.similar != null -> EmptyKind.SIMILAR
  r.date != null && (r.browse || r.rows.isEmpty()) -> EmptyKind.IN_DATES
  r.browse && filter != KindFilter.ALL -> EmptyKind.FILTERED
  r.browse -> EmptyKind.FRESH
  else -> EmptyKind.NO_MATCH
}

/**
 * What the list says when it has no rows. Each case names the reason and the next step: a date that excludes everything offers "Any time", a
 * kind filter that finds nothing offers "All", a new library says Trawl has not seen the files yet, and an unfinished index says the answer may
 * change (so an empty list is never read as "your file does not exist").
 */
@Composable
fun EmptyResults(results: ResultsUi, filter: KindFilter, c: AppController, ui: MainUi, modifier: Modifier = Modifier) {
  val tk = LocalSunlight.current
  Column(modifier.padding(horizontal = Sun.S20, vertical = Sun.S24)) {
    when (emptyKind(results, filter)) {
      EmptyKind.SIMILAR -> {
        Text(t("similar_chip", results.similar?.name.orEmpty()), style = SunType.Heading, color = tk.ink, modifier = Modifier.a11yHeading())
        Spacer(Modifier.height(6.dp))
        Text(t("similar_nothing_body", results.similar?.name.orEmpty()), style = SunType.Body, color = tk.ink2)
        if (results.indexIncomplete) Note(t("search_nothing_incomplete"))
      }
      EmptyKind.IN_DATES -> {
        val label = results.date?.filter?.label?.text().orEmpty()
        Text(t("search_nothing_title"), style = SunType.Heading, color = tk.ink, modifier = Modifier.a11yHeading())
        Spacer(Modifier.height(6.dp))
        Text(t("nothing_in_dates", label), style = SunType.Body, color = tk.ink2)
        Spacer(Modifier.height(Sun.S12))
        SunOutlinedButton(onClick = { c.clearDate(); ui.focusSearch() }) { Text(t("date_any_time"), style = SunType.Label) }
        if (results.indexIncomplete) Note(t("search_nothing_incomplete"))
      }
      EmptyKind.FILTERED -> Text(t("search_recent_empty_filter"), style = SunType.BodyLarge, color = tk.ink2)
      EmptyKind.FRESH -> {
        Text(t("search_fresh_title"), style = SunType.Heading, color = tk.ink, modifier = Modifier.a11yHeading())
        Spacer(Modifier.height(6.dp))
        Text(t("search_fresh_body"), style = SunType.Body, color = tk.ink2)
      }
      EmptyKind.NO_MATCH -> {
        Text(t("search_nothing_title"), style = SunType.Heading, color = tk.ink, modifier = Modifier.a11yHeading())
        Spacer(Modifier.height(6.dp))
        Text(t("search_nothing_body", results.words.ifBlank { results.query }), style = SunType.Body, color = tk.ink2)
        Spacer(Modifier.height(6.dp))
        Text(t("search_nothing_try_all"), style = SunType.Body, color = tk.ink2)
        if (filter != KindFilter.ALL) {
          Spacer(Modifier.height(Sun.S12))
          SunOutlinedButton(onClick = { c.setFilter(KindFilter.ALL); ui.focusSearch() }) { Text(t("search_all_button"), style = SunType.Label) }
        }
        if (results.indexIncomplete) Note(t("search_nothing_incomplete"))
      }
    }
  }
}

@Composable
private fun Note(text: String) {
  val tk = LocalSunlight.current
  Spacer(Modifier.height(Sun.S12))
  Text(text, style = SunType.Caption, color = tk.ink2)
}
