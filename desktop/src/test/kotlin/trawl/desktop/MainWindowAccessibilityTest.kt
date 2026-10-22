package trawl.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.core.KindFilter
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import trawl.desktop.ui.MainScreen
import trawl.desktop.ui.SettingsScreen
import trawl.desktop.ui.SunlightTheme

/**
 * The accessibility checks for the main window and Settings (audit rows H2 and H4), through Compose's own semantics tree: the tree the
 * platform accessibility bridges are fed from. They check what a screen reader is told and where Tab goes; they do not replace a pass with a
 * real screen reader, which the write-up says plainly. Made-up files only, and no model (the words pass finds them).
 */
@OptIn(ExperimentalTestApi::class)
class MainWindowAccessibilityTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val dirs = ArrayList<File>()
  private fun tmp() = Files.createTempDirectory("trawl-a11y").toFile().canonicalFile.also { dirs += it }

  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)
  @AfterTest fun clean() { scope.cancel(); dirs.forEach { it.deleteRecursively() } }

  private fun waitFor(what: String, ms: Long = 30_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  /** A controller over two made-up notes, read and searchable. */
  private fun controller(): AppController {
    val root = tmp()
    File(root, "notes").mkdirs()
    File(root, "notes/recipe.txt").writeText("Slow roasted tomatoes with a spoonful of orange marmalade and thyme.")
    File(root, "notes/diary.txt").writeText("The marmalade jar is where I hide the spare key.")
    val c = AppController(tmp(), Platform.UserFolders(tmp(), tmp(), tmp()), logSink = {}, scope = scope)
    c.addFolder(root)
    waitFor("both notes to be read") { c.service.search("marmalade", KindFilter.ALL).hits.size == 2 }
    return c
  }

  private fun live(mode: LiveRegionMode) = SemanticsMatcher("live region $mode") { it.config.getOrNull(SemanticsProperties.LiveRegion) == mode }
  private val heading = SemanticsMatcher("a heading") { SemanticsProperties.Heading in it.config }
  private fun chip(label: String) = hasText(label) and SemanticsMatcher("a choice") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }

  // ---- H2: what is said -----------------------------------------------------------------------------------------------

  @Test fun aResultRowIsOneSpokenSentenceNotAScatterOfFragments() = runComposeUiTest {
    val c = controller()
    setContent { SunlightTheme(trawl.desktop.ThemeChoice.LIGHT) { MainScreen(c) } }
    c.setQuery("marmalade")
    waitUntil(timeoutMillis = 30_000) { onAllNodes(hasContentDescription("recipe.txt", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    val said = onAllNodes(hasContentDescription("recipe.txt", substring = true)).fetchSemanticsNodes()
      .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") }
    assertTrue(said.isNotEmpty(), "the row has a spoken description")
    assertTrue(said.any { it.startsWith("recipe.txt. ") }, "name first, then the rest, pauses between: $said")
  }

  @Test fun theResultCountIsALiveRegionThatSaysTheListAndTheCount() = runComposeUiTest {
    val c = controller()
    setContent { SunlightTheme(trawl.desktop.ThemeChoice.LIGHT) { MainScreen(c) } }
    c.setQuery("marmalade")
    waitUntil(timeoutMillis = 30_000) { onAllNodes(live(LiveRegionMode.Polite)).fetchSemanticsNodes().any { n -> n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { "2 " in it } == true } }
    val said = onAllNodes(live(LiveRegionMode.Polite)).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
    assertTrue(said.any { it.endsWith(tn("search_count", 2, "2")) }, "the heading and the count, in words: $said")
    assertTrue(onAllNodes(heading).fetchSemanticsNodes().isNotEmpty(), "the list has a heading to jump to")
  }

  @Test fun theTextSizeChoicesAreOneGroupAndTheCurrentOneIsSelected() = runComposeUiTest {
    val c = controller()
    setContent { SunlightTheme(trawl.desktop.ThemeChoice.LIGHT) { SettingsScreen(c) } }
    onAllNodes(chip("Default")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty(), "Default is a choice") }
    onAllNodes(chip("Default"))[0].assertIsSelected()
    onAllNodes(chip("Large"))[0].performScrollTo().performClick()
    waitForIdle()
    assertEquals(TextSize.LARGE, c.settings.value.textSize)
    onAllNodes(chip("Large"))[0].assertIsSelected()
    onAllNodes(chip("Default"))[0].let { assertTrue(it.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == false) }
    assertTrue(onAllNodes(SemanticsMatcher("a selectable group") { SemanticsProperties.SelectableGroup in it.config }).fetchSemanticsNodes().isNotEmpty())
  }

  @Test fun everyFolderRemoveButtonSaysWhichFolder() = runComposeUiTest {
    val c = controller()
    setContent { SunlightTheme(trawl.desktop.ThemeChoice.LIGHT) { SettingsScreen(c) } }
    val folder = File(c.settings.value.folders.first())
    onNodeWithContentDescription("Remove ${folder.name}").assertExists()
  }

  // ---- H4: where Tab goes ---------------------------------------------------------------------------------------------

  /** A name for the focused thing: its description, else its text, with its role. */
  private fun androidx.compose.ui.test.ComposeUiTest.focusedName(): String? {
    val nodes = onAllNodes(isFocused()).fetchSemanticsNodes()
    val n = nodes.firstOrNull() ?: return null
    val cfg = n.config
    val said = cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
      ?: cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
      ?: cfg.getOrNull(SemanticsProperties.EditableText)?.text
    return "${cfg.getOrNull(SemanticsProperties.Role) ?: "-"}:${said ?: "?"}"
  }

  private fun androidx.compose.ui.test.ComposeUiTest.tabThrough(first: () -> Unit, presses: Int): List<String> {
    first()
    waitForIdle()
    val seen = ArrayList<String>()
    repeat(presses) {
      onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull() ?: return@repeat
      focusedName()?.let(seen::add)
      onAllNodes(isFocused())[0].performKeyInput { pressKey(Key.Tab) }
      waitForIdle()
    }
    return seen
  }

  @Test fun tabWalksTheSettingsScreenInOrderAndNeverGetsStuck() = runComposeUiTest {
    val c = controller()
    setContent { SunlightTheme(trawl.desktop.ThemeChoice.LIGHT) { SettingsScreen(c) } }
    val seen = tabThrough({ onAllNodes(chip("Default"))[0].performScrollTo().performClick() }, 40)
    // Every press moved to something new: a trap would repeat one name for good.
    val runs = seen.zipWithNext().count { (a, b) -> a == b }
    assertTrue(runs <= 2, "focus must keep moving, not sit on one control: $seen")
    assertTrue(seen.toSet().size >= 10, "Tab reaches many different controls on Settings (${seen.toSet().size}): $seen")
    assertTrue(seen.any { it.startsWith("RadioButton") }, "the chips are reached: $seen")
    assertTrue(seen.any { it.startsWith("Button") || it.startsWith("Checkbox") || it.startsWith("Switch") }, "buttons and switches are reached: $seen")
  }

  @Test fun tabWalksTheMainScreenFromTheSearchBox() = runComposeUiTest {
    val c = controller()
    setContent { SunlightTheme(trawl.desktop.ThemeChoice.LIGHT) { MainScreen(c) } }
    c.setQuery("marmalade")
    waitUntil(timeoutMillis = 30_000) { onAllNodes(hasContentDescription("recipe.txt", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    val seen = tabThrough({ onAllNodes(chip("All"))[0].performClick() }, 30)
    val runs = seen.zipWithNext().count { (a, b) -> a == b }
    assertTrue(runs <= 2, "focus must keep moving, not sit on one control: $seen")
    assertTrue(seen.toSet().size >= 6, "Tab reaches the chips, sort, date and results (${seen.toSet().size}): $seen")
  }
}
