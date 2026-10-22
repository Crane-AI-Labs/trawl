package com.craneai.findr.ui.onboarding

import com.craneai.findr.ui.common.appString
import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.craneai.findr.ui.common.Wordmark
import com.craneai.findr.ui.language.AppLocale
import com.craneai.findr.ui.language.LanguageList
import com.craneai.findr.ui.language.applyLanguage
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.PackStatus
import com.craneai.findr.core.idsToOfferAtOnboarding
import com.craneai.findr.core.offeredAtOnboarding
import com.craneai.findr.ui.addons.AddOnsViewModel
import com.craneai.findr.ui.addons.AddPackDialog
import com.craneai.findr.ui.addons.InstallProgress
import com.craneai.findr.ui.addons.PackAdded
import com.craneai.findr.ui.addons.PackDescription
import com.craneai.findr.ui.addons.PackHeader
import com.craneai.findr.ui.addons.PackProblemText
import com.craneai.findr.ui.addons.sizeLabel
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.common.BrandMark
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.settings.FolderScopeEditor
import com.craneai.findr.ui.theme.LocalReduceMotion
import com.craneai.findr.ui.theme.Motion
import com.craneai.findr.ui.theme.motionTween
import kotlinx.coroutines.launch

private enum class Page { LANGUAGE, WELCOME, FILES, MODELS, EXTRAS }

/**
 * Four short pages (v2, the "find a middle ground" design): the language first (each language in its own name), then one
 * welcome page that says what Trawl does and makes the privacy promise in three points, then All files access with the honest
 * why right above the button (the page moves on by itself once access is on), then the search models, which "Start Trawl" does
 * not wait for. The models page is there only when the phone has no model, so a phone that has one (Play) gets three.
 *
 * What used to be here moved to the moment it matters: the folder choice is a "Choose folders" link on the files page and a
 * Settings section; notifications are asked after the first search; the app lock when a file is first marked sensitive; the
 * Transsion pause note is a card on Home while files are waiting (ui/background). The final button stays disabled until All
 * files access is on, because without it there is nothing to search. Sunlight: one idea per page, a Display title, one big
 * primary button at the thumb.
 */
@Composable
fun OnboardingScreen(
  addOns: AddOnsViewModel,
  delivery: com.craneai.findr.ui.delivery.DeliveryViewModel,
  index: IndexViewModel,
  access: AccessState,
  onOpenFilesSettings: () -> Unit,
  onFinish: () -> Unit,
) {
  // Quick look and speech are installed without a question; the extras page is only for the opt-in packs not on the phone yet.
  val offerIds = remember { idsToOfferAtOnboarding(addOns.rows.value) }
  // The models page is there when the phone has no model yet (read once, so the page does not vanish the moment it is installed).
  val needsModels = remember { delivery.noModel.value }
  val pages = remember { Page.entries.filter { (it != Page.MODELS || needsModels) && (it != Page.EXTRAS || offerIds.isNotEmpty()) } }
  val pager = rememberPagerState(pageCount = { pages.size })
  val scope = rememberCoroutineScope()
  val last = pager.currentPage == pages.size - 1
  val onFiles = pages[pager.currentPage] == Page.FILES
  var choosingFolders by rememberSaveable { mutableStateOf(false) }

  // Access that was already on when setup opened (a phone that kept it) does not skip the page behind the person's back.
  val grantedAtStart = rememberSaveable { access.allFiles }
  LaunchedEffect(access.allFiles) {
    val here = pages.indexOf(Page.FILES)
    if (access.allFiles && !grantedAtStart && pager.currentPage == here && here < pages.size - 1) pager.animateScrollToPage(here + 1)
  }

  if (choosingFolders) {
    BackHandler { choosingFolders = false }
    ChooseFolders(index, access.allFiles, onDone = { choosingFolders = false })
    return
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding()
      .navigationBarsPadding(),
  ) {
    Wordmark(Modifier.fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S16))

    HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
      Column(
        Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = Sun.S24, vertical = Sun.S8),
      ) {
        when (pages[page]) {
          Page.LANGUAGE -> PageLanguage()
          Page.WELCOME -> PageWelcome()
          Page.FILES -> PageFiles(access, onChooseFolders = { choosingFolders = true })
          Page.MODELS -> PageModels(delivery, access)
          Page.EXTRAS -> PageExtras(addOns, offerIds)
        }
      }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S16)) {
      PageDots(current = pager.currentPage, count = pages.size)
      Spacer(Modifier.height(Sun.S16))
      if (last) {
        // Without All files access the way forward is the settings page, not a dead button and a trip back to the files page.
        SunButton(
          onClick = if (access.allFiles) onFinish else onOpenFilesSettings,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(appString(if (access.allFiles) R.string.onb_start else R.string.onb_allow_access), style = SunType.Label)
        }
        // A slot that keeps its place, so granting access does not make the block below jump.
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
          if (!access.allFiles) {
            Text(
              appString(R.string.onb_start_locked),
              style = SunType.Caption,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        }
      } else if (onFiles && !access.allFiles) {
        // The ask itself: the explanation is on the page right above this button. Granting it moves the pager on.
        SunButton(onClick = onOpenFilesSettings, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.onb_allow_access), style = SunType.Label)
        }
      } else {
        SunButton(
          onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
          modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onb_next), style = SunType.Label) }
      }
      if (pager.currentPage > 0) {
        SunTextButton(
          onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
          modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(stringResource(R.string.onb_back)) }
      } else {
        Spacer(Modifier.height(Sun.Target))
      }
    }
  }
}

@Composable
private fun PageDots(current: Int, count: Int) {
  val reduce = LocalReduceMotion.current
  val description = stringResource(R.string.onb_page_of, current + 1, count)
  Row(
    Modifier.fillMaxWidth().semantics { contentDescription = description },
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(count) { i ->
      val selected = i == current
      val w by animateDpAsState(if (selected) 22.dp else 8.dp, motionTween(reduce, Motion.Quick), label = "dotWidth")
      val c by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant,
        motionTween(reduce, Motion.Quick),
        label = "dotColor",
      )
      Box(
        Modifier
          .padding(horizontal = 3.dp)
          .size(width = w, height = 8.dp)
          .clip(CircleShape)
          .background(c),
      )
    }
  }
}

/** A short line above the title. Sentence case: all-caps is slower to read, much slower for someone still learning English. */
@Composable
private fun Eyebrow(text: String) {
  Text(text, style = SunType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Sun.S8))
}

@Composable
private fun PageTitle(text: String) {
  Text(text, style = SunType.Display, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
  Spacer(Modifier.height(Sun.S12))
}

/**
 * First, the language: every language in its own name, the phone's own one chosen when the app has it. Choosing one switches
 * the screens at once (Android 13+ recreates them; before that we do), so the person sees the rest of setup in it.
 */
@Composable
private fun PageLanguage() {
  val activity = LocalActivity.current
  val chosen = activity?.let { AppLocale.current(it) } ?: activity?.let { AppLocale.effective(it).tag }
  PageTitle(stringResource(R.string.language_title))
  Text(appString(R.string.language_intro), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Sun.S24))
  LanguageList(selectedTag = chosen, onSelect = { tag -> applyLanguage(activity, tag) })
}

/** Page 2: what Trawl does in one line, and the privacy promise in three points. Short on purpose: the proof is the no-internet point. */
@Composable
private fun PageWelcome() {
  Eyebrow(stringResource(R.string.onb1_eyebrow))
  PageTitle(stringResource(R.string.onb1_title))
  Text(appString(R.string.welcome_line), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(Sun.S24))
  Point(Icons.Rounded.CloudOff, stringResource(R.string.onb1_point_internet_title), appString(R.string.onb1_point_internet_body))
  Point(Icons.Rounded.Memory, stringResource(R.string.onb1_point_model_title), stringResource(R.string.welcome_point_ai_body))
  Point(Icons.Rounded.Visibility, stringResource(R.string.welcome_point_reads_title), appString(R.string.welcome_point_reads_body))
}

/**
 * Page 3: All files access. The reason sits right above the button (which is in the pager's bar), and the page moves on by
 * itself once access is on. Folders are not part of this flow: "Choose folders" is a small link for the careful.
 */
@Composable
private fun PageFiles(access: AccessState, onChooseFolders: () -> Unit) {
  PageTitle(appString(R.string.onb3_title))
  Point(Icons.Rounded.FolderOpen, stringResource(R.string.onb3_files_title), appString(R.string.onb3_files_body))
  Spacer(Modifier.height(Sun.S8))
  if (access.allFiles) {
    // Done is an icon and a word, in the "good" colour; never the colour alone.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = Sun.Target)) {
      Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = LocalSunlight.current.good, modifier = Modifier.size(Sun.Icon))
      Spacer(Modifier.width(Sun.S8))
      Text(stringResource(R.string.onb3_files_granted), style = SunType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
  } else {
    Text(stringResource(R.string.onb3_files_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
  }
  Spacer(Modifier.height(Sun.S16))
  SunTextButton(onClick = onChooseFolders) { Text(stringResource(R.string.onb_choose_folders)) }
}

/**
 * "Choose folders", reached from the files page: the same editor as Settings (private places and WhatsApp Status are off by
 * default). Choices made here apply to the first crawl, which starts when the person taps Start Trawl.
 */
@Composable
private fun ChooseFolders(index: IndexViewModel, hasAccess: Boolean, onDone: () -> Unit) {
  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .statusBarsPadding()
      .navigationBarsPadding(),
  ) {
    Wordmark(Modifier.fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S16))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Sun.S24, vertical = Sun.S8)) {
      PageTitle(appString(R.string.scope_title))
      FolderScopeEditor(index, refreshKey = hasAccess)
    }
    SunButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S16)) {
      Text(stringResource(R.string.models_ok), style = SunType.Label)
    }
  }
}

/**
 * "Get the search models": shown only when the phone has none. The person can start at once ("Start Trawl" does not wait): names
 * and words work without the model, and the download goes on, watched by the card on Home.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageModels(delivery: com.craneai.findr.ui.delivery.DeliveryViewModel, access: AccessState) {
  val delivered by delivery.state.collectAsStateWithLifecycle()
  Eyebrow(stringResource(R.string.models_eyebrow))
  PageTitle(stringResource(R.string.models_title))
  Text(appString(R.string.models_body, delivered.sizeMb), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(20.dp))
  com.craneai.findr.ui.delivery.GetModelsCard(delivery, access, framed = false, showBody = false)
}

/**
 * "Add extras?": one card per opt-in pack that can be added today and is not on the phone yet (quick look and speech are
 * installed without a question). "Not now" is remembered (the engine will not install a pack behind the user's back); both
 * choices can be changed in Settings → Add-ons. No pack is on offer today, so this page does not show.
 */
@Composable
private fun PageExtras(addOns: AddOnsViewModel, offerIds: List<String>) {
  val rows by addOns.rows.collectAsStateWithLifecycle()
  var skipped by rememberSaveable { mutableStateOf("") }
  var adding by rememberSaveable { mutableStateOf<String?>(null) }
  val offered = offeredAtOnboarding(rows).filter { it.def.id in offerIds }

  Eyebrow(stringResource(R.string.onb4_eyebrow))
  PageTitle(stringResource(R.string.onb4_title))
  Text(appString(R.string.onb4_body), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(20.dp))
  offered.forEach { row ->
    val isSkipped = row.def.id in skipped.split(',')
    SunCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(Sun.S16)) {
        PackHeader(row)
        PackDescription(row)
        Spacer(Modifier.height(14.dp))
        when {
          row.status == PackStatus.INSTALLED -> PackAdded()
          row.status == PackStatus.INSTALLING -> InstallProgress(row)
          else -> {
            PackProblemText(row)
            if (isSkipped) {
              Text(stringResource(R.string.onb4_skipped), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
              SunOutlinedButton(onClick = { adding = row.def.id }) {
                Text(stringResource(R.string.addons_add_with_size, row.def.sizeLabel()))
              }
            } else {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
                SunOutlinedButton(onClick = { adding = row.def.id }) {
                  Text(stringResource(R.string.addons_add_with_size, row.def.sizeLabel()))
                }
                SunTextButton(
                  onClick = {
                    addOns.notNow(row.def.id)
                    skipped = (skipped.split(',').filter { it.isNotEmpty() } + row.def.id).joinToString(",")
                  },
                ) { Text(stringResource(R.string.onb4_not_now)) }
              }
            }
          }
        }
      }
    }
    Spacer(Modifier.height(Sun.S16))
  }
  Text(stringResource(R.string.onb4_footer), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)

  offered.firstOrNull { it.def.id == adding }?.let { row ->
    AddPackDialog(
      row = row,
      howTo = addOns.howToAdd(row.def.id),
      onInstall = { adding = null; addOns.install(row.def.id) },
      onDismiss = { adding = null },
    )
  }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = Sun.S8), verticalAlignment = Alignment.Top) {
    Box(
      Modifier.size(Sun.Target).sunBorder(CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Sun.Icon))
    }
    Spacer(Modifier.width(Sun.S16))
    Column(Modifier.weight(1f)) {
      Text(title, style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onBackground)
      Text(body, style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
