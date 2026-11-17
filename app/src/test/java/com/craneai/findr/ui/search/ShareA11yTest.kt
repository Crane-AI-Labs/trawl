package com.craneai.findr.ui.search

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.ui.fake.FakeSearchEngine
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B7 (UX bugs 1.0.5): what TalkBack and a thumb get from the result screens.
 *  - every clickable control on a result screen is at least 48 dp in both directions (the share badge on a photo tile draws a
 *    30 dp disc but has to be touched through a 48 dp or larger area);
 *  - the share control on each photo tile says which file it shares ("Share meme_1.jpg"), so a list of them is not "Share, Share, Share";
 *  - a document row announces how old the file is, as well as its name, kind and snippet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class ShareA11yTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun search(query: String, fontScale: Float = 1.0f) {
    RuntimeEnvironment.setFontScale(fontScale)
    val engine = FakeSearchEngine(RuntimeEnvironment.getApplication())
    val vm = SearchViewModel(engine)
    val index = IndexViewModel(engine)
    val delivery = DeliveryViewModel(engine)
    rule.setContent {
      FindrTheme(mode = ThemeMode.LIGHT) {
        SearchScreen(vm, index, delivery, AccessState(allFiles = true, notifications = true), onOpenSettings = {}, onAllowAccess = {})
      }
    }
    fun pump(cond: () -> Boolean) {
      val end = System.nanoTime() + 20_000_000_000L
      while (!cond()) {
        check(System.nanoTime() < end) { "timed out waiting on '$query'" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
        rule.mainClock.advanceTimeBy(100)
      }
    }
    pump { vm.state.value.loaded }
    rule.waitForIdle()
    rule.onNode(hasContentDescription("Search your files by", substring = true)).performTextInput(query)
    pump { vm.state.value.mode == SearchMode.RESULTS && vm.state.value.resultsQuery == query && !vm.state.value.searching }
    rule.waitForIdle()
  }

  private fun nodes(): List<SemanticsNode> = rule.onRoot().fetchSemanticsNode().let { root ->
    buildList { fun walk(n: SemanticsNode) { add(n); n.children.forEach(::walk) }; walk(root) }
  }

  private fun desc(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ").orEmpty()

  private fun shareNodes() = nodes().filter { desc(it).startsWith("Share") }

  private fun dp(px: Float) = px / RuntimeEnvironment.getApplication().resources.displayMetrics.density

  @Test fun everyShareControlIsAtLeast48dp() {
    search("meme")
    val shares = shareNodes()
    assertTrue("no share controls found", shares.isNotEmpty())
    for (n in shares) {
      assertTrue("'${desc(n)}' is ${dp(n.size.width.toFloat())} x ${dp(n.size.height.toFloat())} dp", dp(n.size.width.toFloat()) >= 48f && dp(n.size.height.toFloat()) >= 48f)
    }
  }

  @Test fun tileSharesNameTheirFile() {
    search("meme")
    val labels = shareNodes().map { desc(it) }
    assertTrue("only ${labels.size} share controls: $labels", labels.size >= 2)
    assertEquals("labels repeat: $labels", labels.size, labels.toSet().size)
    for (l in labels) assertTrue("'$l' names no file", l.length > "Share".length + 1 && l.contains("."))
  }

  @Test fun tileSharesNameTheirFileAtLargeFont() {
    search("meme", fontScale = 2.0f)
    val labels = shareNodes().map { desc(it) }
    assertEquals("labels repeat: $labels", labels.size, labels.toSet().size)
  }

  @Test fun documentRowAnnouncesItsDate() {
    assertDocumentRowsAnnounceTheirDate()
  }

  @Test fun documentRowAnnouncesItsDateAtLargeFont() {
    assertDocumentRowsAnnounceTheirDate(fontScale = 2.0f)
  }

  private fun assertDocumentRowsAnnounceTheirDate(fontScale: Float = 1.0f) {
    search("school", fontScale)
    val rows = nodes().filter { it.config.contains(SemanticsActions.OnClick) && desc(it).contains(", ") && desc(it).contains("school", ignoreCase = true) && !desc(it).startsWith("Share") && !desc(it).startsWith("Copy") }
    assertTrue("no document row found in ${nodes().map { desc(it) }.filter { it.isNotEmpty() }}", rows.isNotEmpty())
    // The age the row shows in its subtitle ("PDF  ·  folder  ·  2 hours ago", merged into the row's node) is the one it has to announce.
    for (r in rows) {
      val texts = r.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
      val age = texts.firstOrNull { "  ·  " in it }?.substringAfterLast("  ·  ")
      assertTrue("no subtitle with an age in $texts", age != null)
      assertTrue("row announcement '${desc(r)}' lacks its age '$age'", desc(r).contains(age!!))
    }
  }
}
