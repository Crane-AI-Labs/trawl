package com.craneai.findr.ui.search

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.craneai.findr.core.KindFilter
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.ui.fake.FakeSearchEngine
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import android.os.Looper
import java.io.File
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B1 (UX bugs 1.0.5): search, then clear it with the X. The home screen has to come back exactly as it was: one row of kind
 * filters (the six tiles, nothing left over from the results line), the Newest / Any time chips under them, and the grid of recent
 * files under those, with nothing drawn on top of anything. Run through the real [SearchScreen], view models and the sample engine,
 * at font scales 1.0, 1.3 and 2.0, and on a phone with its display size set to Large (320 dp wide).
 *
 * Writes PNGs to `$UX_SHOTS_DIR` (default app/build/ux-shots).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class ClearSearchLayoutTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun save(name: String) {
    val dir = File(System.getenv("UX_SHOTS_DIR") ?: "build/ux-shots").also { it.mkdirs() }
    File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun boundsOfAll(description: String): List<Rect> =
    rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().map { it.boundsInRoot }

  private fun boundsOfText(text: String): List<Rect> = rule.onAllNodesWithText(text).fetchSemanticsNodes().map { it.boundsInRoot }

  /** The soft keyboard coming up or going away, as the window reports it (what WindowInsets.isImeVisible reads). */
  private fun setIme(visible: Boolean) {
    rule.runOnUiThread {
      val insets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, if (visible) 700 else 0))
        .setVisible(WindowInsets.Type.ime(), visible)
        .build()
      rule.activity.window.decorView.dispatchApplyWindowInsets(insets)
    }
    rule.waitForIdle()
  }

  private fun waitFor(what: String, search: SearchViewModel, cond: (SearchUiState) -> Boolean) {
    try {
      // The view model's delays run on the (paused) main looper and its engine work on Dispatchers.Default: move the looper's
      // clock on by hand while real time passes for the background threads.
      val end = System.nanoTime() + 20_000_000_000L
      while (!cond(search.state.value)) {
        check(System.nanoTime() < end) { "timed out" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
        rule.mainClock.advanceTimeBy(100)
      }
    } catch (e: Throwable) {
      val st = search.state.value
      throw AssertionError("waiting for $what: mode=${st.mode} query='${st.query}' resultsQuery='${st.resultsQuery}' searching=${st.searching} feed=${st.feed.size} nothing=${st.nothingFound}", e)
    }
  }

  /** search "school", wait for the results, clear with the X, wait for the home list, and check the layout. */
  private fun searchThenClear(name: String, fontScale: Float, query: String = "meme", keyboard: Boolean = true, early: Boolean = false, photosOnly: Boolean = false) {
    RuntimeEnvironment.setFontScale(fontScale)
    val app = RuntimeEnvironment.getApplication()
    val engine = FakeSearchEngine(app)
    val search = SearchViewModel(engine)
    val index = IndexViewModel(engine)
    val delivery = DeliveryViewModel(engine)
    rule.setContent {
      FindrTheme(mode = ThemeMode.LIGHT) {
        SearchScreen(search, index, delivery, AccessState(allFiles = true, notifications = true), onOpenSettings = {}, onAllowAccess = {})
      }
    }
    waitFor("loaded", search) { it.loaded }
    rule.waitForIdle()
    save("$name-1-home")
    assertOneFilterBlock("home before the search")

    // A phone whose newest things are pictures: the grid of photo cells sits right under the filters, the same cells the answers show.
    if (photosOnly) {
      rule.onAllNodesWithText("Photos")[0].performClick()
      waitFor("photo grid", search) { it.filter == KindFilter.PHOTOS && it.mode == SearchMode.RECENT && it.feed.any { f -> f is FeedItem.Cell } }
      rule.waitForIdle()
      save("$name-1b-photos")
    }
    rule.onNode(hasContentDescription("Search your files by", substring = true)).performTextInput(query)
    if (keyboard) setIme(true)
    waitFor("results", search) { it.mode == SearchMode.RESULTS && it.resultsQuery == query && (early || !it.searching) }
    if (!early) rule.waitForIdle()
    save("$name-2-results")

    rule.onNodeWithContentDescription("Clear search").performClick()
    waitFor("recent again", search) { it.mode == SearchMode.RECENT && it.feed.isNotEmpty() }
    save("$name-3-cleared-keyboard")
    // The keyboard goes away (Back or a tap outside the box) once the box is empty; every frame of whatever the screen animates, then rest.
    if (keyboard) setIme(false)
    rule.mainClock.autoAdvance = false
    repeat(6) { rule.mainClock.advanceTimeBy(60); save("$name-3-clear-frame$it") }
    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    save("$name-4-cleared")
    assertOneFilterBlock("home after clearing")
  }

  /** One group of kind filters on the screen, the Newest chip under it and not over anything. */
  private fun assertOneFilterBlock(where: String) {
    val groups = boundsOfAll("Show which files")
    assertEquals("$where: kind filter groups on screen: $groups", 1, groups.size)
    val sort = boundsOfText("Date modified (newest)")
    assertTrue("$where: sort chip count ${sort.size}", sort.isNotEmpty())
    val group = groups.single()
    val sections = rule.onAllNodes(hasText("Recent", substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }
    assertTrue("$where: a recent-files heading ($sections) is drawn over the filters ($group)", sections.all { it.top >= group.bottom - 1f })
    assertTrue("$where: the Newest chip ($sort) overlaps the filters ($group)", sort.all { it.top >= group.bottom - 1f })
  }

  @Test fun fontScale1() = searchThenClear("font1.0", 1.0f)
  @Test fun fontScale1_3() = searchThenClear("font1.3", 1.3f)
  @Test fun fontScale2() = searchThenClear("font2.0", 2.0f)
  @Config(qualifiers = "w320dp-h693dp-xxhdpi")
  @Test fun largeDisplayAtFont2() = searchThenClear("large-display-font2.0", 2.0f)
  /** The X is tapped the moment the first answer is drawn, while the meaning step is still landing and the rows are still moving. */
  @Test fun clearedWhileAnswersStillMove() = searchThenClear("early-font2.0", 2.0f, early = true)
  @Test fun clearedWhileAnswersStillMoveKeyboardDown() = searchThenClear("early-nokb-font2.0", 2.0f, keyboard = false, early = true)
  @Test fun photoGridAtFont2() = searchThenClear("photos-font2.0", 2.0f, photosOnly = true)
  @Test fun photoGridClearedEarlyAtFont2() = searchThenClear("photos-early-font2.0", 2.0f, early = true, photosOnly = true)
  @Test fun keyboardDownFontScale2() = searchThenClear("nokb-font2.0", 2.0f, keyboard = false)
}
