package com.craneai.findr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit
import com.craneai.findr.ui.common.labelRes
import com.craneai.findr.ui.index.IndexMeterCard
import com.craneai.findr.ui.language.LanguageList
import com.craneai.findr.ui.search.FileRow
import com.craneai.findr.ui.search.FilterRow
import com.craneai.findr.ui.search.FilterTiles
import com.craneai.findr.ui.search.SearchDock
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.settings.PalettePicker
import com.craneai.findr.ui.settings.SwitchRow
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Palette
import com.craneai.findr.ui.theme.SunlightLightTokens
import com.craneai.findr.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshot-free checks of Sunlight's accessibility promises (the design notes): 56 dp targets, words that TalkBack
 * hears once and with the file's name, selected and on/off states that are semantics and not only colour, and text that does
 * not clip at 200% font size on a 360 dp wide phone. Runs on a phone: ./gradlew :app:connectedDebugAndroidTest
 * -PappIdSuffix=.sunlightui (a side build, never the tester's app).
 */
@RunWith(AndroidJUnit4::class)
class SunlightUiTest {
  @get:Rule val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun show(fontScale: Float = 1f, dark: Boolean = false, rtl: Boolean = false, content: @Composable () -> Unit) {
    rule.setContent {
      val d = LocalDensity.current
      CompositionLocalProvider(
        LocalDensity provides Density(d.density, fontScale),
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
      ) {
        FindrTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
          Box(Modifier.width(360.dp)) { content() }
        }
      }
    }
  }

  private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
    val out = ArrayList<TextLayoutResult>()
    fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
    return out.single()
  }

  private fun SemanticsNodeInteraction.role(): Role? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

  private val file = IndexedFile(
    id = 7, uri = "fake://7", relativePath = "Documents/school_fees_term2.pdf", name = "school_fees_term2.pdf",
    kind = FileKind.PDF, mimeType = "application/pdf", sizeBytes = 120_000, modifiedMillis = System.currentTimeMillis() - 86_400_000,
    pageCount = 3,
  )

  @Test fun filterTilesAreRadioButtonsWith56dpTargetsAndOneSelected() {
    var chosen by mutableStateOf(KindFilter.ALL)
    show { FilterTiles(chosen, { chosen = it }) }
    for (f in KindFilter.entries) {
      val tile = rule.onNodeWithText(context.getString(f.labelRes()))
      tile.assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp).assertHasClickAction()
      assertEquals(Role.RadioButton, tile.role())
      if (f == KindFilter.ALL) tile.assertIsSelected() else tile.assertIsNotSelected()
    }
    rule.onNodeWithText(context.getString(R.string.filter_photos)).performClick()
    rule.runOnIdle { assertEquals(KindFilter.PHOTOS, chosen) }
    rule.onNodeWithText(context.getString(R.string.filter_photos)).assertIsSelected()
    rule.onNodeWithText(context.getString(R.string.filter_all)).assertIsNotSelected()
  }

  @Test fun filterLabelsFitAt200PercentFontSize() {
    show(fontScale = 2f) { FilterTiles(KindFilter.ALL, {}) }
    for (f in KindFilter.entries) {
      val l = rule.onNodeWithText(context.getString(f.labelRes()), useUnmergedTree = true).layout()
      assertFalse(
        "${context.getString(f.labelRes())} clips at 200%: box ${l.size}, text ${l.multiParagraph.width} x ${l.multiParagraph.height}, " +
          "lines ${l.lineCount}, ellipsis ${l.isLineEllipsized(l.lineCount - 1)}",
        l.isLineEllipsized(l.lineCount - 1),
      )
    }
  }

  @Test fun theCompactFilterLineKeepsItsTargetsAndStates() {
    show { FilterRow(KindFilter.VIDEOS, {}) }
    val videos = rule.onNodeWithText(context.getString(R.string.filter_videos)).assertIsSelected()
    // 44 dp to see, 56 dp to touch (minimumInteractiveComponentSize widens the touch area around the chip).
    assertTrue(with(rule.density) { videos.fetchSemanticsNode().touchBoundsInRoot.height.toDp() } >= 55.5.dp)
    rule.onNodeWithText(context.getString(R.string.filter_all)).assertIsNotSelected()
  }

  @Test fun aSwitchRowIsOneSwitchThatSaysOnOrOffWithoutColour() {
    var on by mutableStateOf(true)
    show { SwitchRow(title = "Lock", hint = "Fingerprint or PIN first", checked = on, onChange = { on = it }) }
    val row = rule.onNodeWithText("Lock")
    assertEquals(Role.Switch, row.role())
    row.assertIsOn().assertHeightIsAtLeast(56.dp)
    // The word in the track is drawn for the eye, not read twice: the row's state is what TalkBack hears.
    rule.onNodeWithText(context.getString(R.string.switch_on), useUnmergedTree = true).assertExists()
    row.performClick()
    rule.onNodeWithText("Lock").assertIsOff()
    rule.onNodeWithText(context.getString(R.string.switch_off), useUnmergedTree = true).assertExists()
  }

  @Test fun shareAndCopyNameTheFileAndAre56dp() {
    var shared = 0
    show { FileRow(SearchHit(file, 1.0, MatchReason.WORDS), onOpen = {}, onShare = { shared++ }, onCopy = {}, onToggle = {}) }
    val share = rule.onNodeWithContentDescription(context.getString(R.string.action_share_file, file.name))
    share.assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp).assertHasClickAction()
    rule.onNodeWithContentDescription(context.getString(R.string.action_copy_file, file.name)).assertHeightIsAtLeast(56.dp)
    share.performClick()
    rule.runOnIdle { assertEquals(1, shared) }
    // The open target reads the name and the kind first.
    rule.onNodeWithContentDescription("${file.name}, ${context.getString(R.string.kind_pdf)}", substring = true).assertHasClickAction()
  }

  @Test fun aResultRowGrowsInsteadOfClippingAt200Percent() {
    show(fontScale = 2f) { FileRow(SearchHit(file, 1.0, MatchReason.WORDS), onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}) }
    // At a large font size the actions move under the name: the name keeps the row's width and the buttons stay whole.
    val name = rule.onNodeWithText(file.name, useUnmergedTree = true)
    assertFalse("the name is cut off", name.layout().didOverflowWidth)
    val share = rule.onNodeWithContentDescription(context.getString(R.string.action_share_file, file.name)).fetchSemanticsNode().boundsInRoot
    val nameBox = name.fetchSemanticsNode().boundsInRoot
    assertTrue("Share sits under the name at 200%", share.top >= nameBox.bottom)
    assertTrue("Share is inside the 360 dp screen", with(rule.density) { share.right.toDp() } <= 360.5.dp)
  }

  @Test fun theSearchBoxIs60dpAndItsClearButtonIsLabelled() {
    show {
      val state = rememberTextFieldState()
      SearchDock(textState = state, status = null)
    }
    val box = rule.onNodeWithContentDescription(context.getString(R.string.search_hint_a11y))
    box.assertHeightIsAtLeast(60.dp)
    box.performTextInput("receipt")
    val clear = rule.onNodeWithContentDescription(context.getString(R.string.search_clear)).assertHasClickAction()
    assertTrue(with(rule.density) { clear.fetchSemanticsNode().touchBoundsInRoot.height.toDp() } >= 55.5.dp)
  }

  @Test fun theMeterIsOneButtonThatReadsItsSentenceAndNumbers() {
    val status = IndexStatus(phase = IndexPhase.INDEXING, filesKnown = 3420, filesSearchable = 3420, filesUnderstood = 1079)
    show { IndexMeterCard(status, compact = false, onClick = {}) }
    val meter = rule.onNodeWithContentDescription("percent", substring = true)
    meter.assertHasClickAction().assertHeightIsAtLeast(56.dp)
    val description = meter.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
    assertTrue(description, description.contains("31"))   // 1,079 of 3,420 understood
  }

  @Test fun languagesAreRadioRowsInTheirOwnNames() {
    var picked: String? = "en"
    show { LanguageList(selectedTag = "en", onSelect = { picked = it }, systemOption = true) }
    val english = rule.onNodeWithText("English")
    assertEquals(Role.RadioButton, english.role())
    english.assertIsSelected().assertHeightIsAtLeast(56.dp)
    val system = rule.onNodeWithText(context.getString(R.string.language_system))
    system.assertIsNotSelected()
    system.performClick()
    rule.runOnIdle { assertEquals(null, picked) }
  }

  @Test fun darkThemeRendersTheSameControls() {
    show(dark = true) { FilterTiles(KindFilter.DOCUMENTS, {}) }
    rule.onNodeWithText(context.getString(R.string.filter_documents)).assertIsSelected()
  }

  /** Arabic reads right to left: the row mirrors (actions on the left, the name to their right), nothing is pinned to "left". */
  @Test fun aResultRowMirrorsRightToLeft() {
    show(rtl = true) { FileRow(SearchHit(file, 1.0, MatchReason.WORDS), onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}) }
    val share = rule.onNodeWithContentDescription(context.getString(R.string.action_share_file, file.name)).fetchSemanticsNode().boundsInRoot
    val name = rule.onNodeWithText(file.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertTrue("Share ($share) should sit left of the name ($name) in RTL", share.right <= name.left)
  }

  @Test fun theFiltersAndTheBoxMirrorRightToLeft() {
    show(rtl = true) { FilterTiles(KindFilter.ALL, {}) }
    val all = rule.onNodeWithText(context.getString(R.string.filter_all)).fetchSemanticsNode().boundsInRoot
    val photos = rule.onNodeWithText(context.getString(R.string.filter_photos)).fetchSemanticsNode().boundsInRoot
    assertTrue("the first tile is on the right in RTL", all.left > photos.left)
  }

  /** While typing (keyboard up) a row is one line each for name, meta and snippet: shorter, and the targets stay 56 dp. */
  @Test fun aCompactRowIsShorterAndKeepsItsTargets() {
    val long = file.copy(name = "school_fees_receipt_nile_valley_primary_term2_2026_final_copy.pdf")
    val hit = SearchHit(long, 1.0, MatchReason.WORDS, snippet = "BRIGHT HILL PRIMARY SCHOOL OFFICIAL «RECEIPT» Receipt No: BH-2026-0412 received from")
    var compact by mutableStateOf(false)
    show { FileRow(hit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, compact = compact) }
    val fullHeight = rule.onNodeWithContentDescription("${long.name}, ", substring = true).fetchSemanticsNode().boundsInRoot.height
    compact = true
    rule.waitForIdle()
    val row = rule.onNodeWithContentDescription("${long.name}, ", substring = true).fetchSemanticsNode().boundsInRoot
    assertTrue("compact ${row.height} vs full $fullHeight", row.height < fullHeight * 0.75f)
    assertEquals(1, rule.onNodeWithText(long.name, useUnmergedTree = true).layout().lineCount)
    val share = rule.onNodeWithContentDescription(context.getString(R.string.action_share_file, long.name))
    share.assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp)
  }

  /**
   * Settings, Appearance: Sunlight and Wallpaper colours are two radio rows, 56 dp or more, and choosing one re-colours the app at
   * once (the theme reads the choice, no restart). Android 12 and later, which every test phone is.
   */
  @Test fun thePalettePickerIsTwoRadioRowsThatApplyAtOnce() {
    var palette by mutableStateOf(Palette.SUNLIGHT)
    var paper: Color? = null
    rule.setContent {
      FindrTheme(ThemeMode.LIGHT, palette) {
        paper = LocalSunlight.current.paper
        Box(Modifier.width(360.dp)) { PalettePicker(palette, { palette = it }, ThemeMode.LIGHT) }
      }
    }
    val sunlight = rule.onNodeWithText(context.getString(R.string.colours_sunlight))
    val wallpaper = rule.onNodeWithText(context.getString(R.string.colours_wallpaper))
    for (row in listOf(sunlight, wallpaper)) {
      assertEquals(Role.RadioButton, row.role())
      row.assertHeightIsAtLeast(56.dp).assertHasClickAction()
    }
    sunlight.assertIsSelected(); wallpaper.assertIsNotSelected()
    rule.runOnIdle { assertEquals(SunlightLightTokens.paper, paper) }
    wallpaper.performClick()
    rule.runOnIdle { assertEquals(Palette.WALLPAPER, palette) }
    wallpaper.assertIsSelected(); sunlight.assertIsNotSelected()
    // The page now wears the wallpaper's surface (Material You's light scheme on this phone).
    rule.runOnIdle { assertEquals(dynamicLightColorScheme(context).surface, paper) }
  }

  /** The choice is kept with the light/dark one (UiPrefs), so it survives the app being closed. Restores what was there. */
  @Test fun thePaletteChoiceIsKept() {
    val before = UiPrefs(context).palette.value
    try {
      UiPrefs(context).setPalette(Palette.WALLPAPER)
      assertEquals(Palette.WALLPAPER, UiPrefs(context).palette.value)
      UiPrefs(context).setPalette(Palette.SUNLIGHT)
      assertEquals(Palette.SUNLIGHT, UiPrefs(context).palette.value)
    } finally {
      UiPrefs(context).setPalette(before)
    }
  }
}
