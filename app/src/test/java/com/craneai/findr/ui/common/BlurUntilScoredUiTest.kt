package com.craneai.findr.ui.common

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.PrivacyCheck
import com.craneai.findr.core.PrivateLook
import com.craneai.findr.core.SearchHit
import com.craneai.findr.ui.search.MediaCell
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Blur until scored, drawn by the real grid tile ([MediaCell] and [FileThumbnail]) with the real strings: a picture with no score yet
 * shows blurred with "Checking", asks for its score while it is on screen, and drops the ask when it leaves; a safe score clears it,
 * a private one turns it into the usual "Looks private" tile (internal/research/blur-until-scored.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h720dp-xxhdpi")
class BlurUntilScoredUiTest {
  @get:Rule val rule = createComposeRule()

  private val checkingWords = "Checking · tap to see"
  private val privateWords = "Looks private · tap to see"

  /** A scorer the test answers by hand; it records what was asked and what was dropped. */
  private class HandScorer : PrivacyCheck {
    val asked = mutableListOf<Long>()
    val dropped = mutableListOf<Long>()
    val answers = HashMap<Long, CompletableDeferred<Float?>>()
    override fun available() = true
    override suspend fun score(file: IndexedFile): Float? {
      asked += file.id
      val answer = answers.getOrPut(file.id) { CompletableDeferred() }
      try {
        return answer.await()
      } catch (e: CancellationException) {
        dropped += file.id
        throw e
      }
    }
  }

  private fun picture(id: Long, score: Float? = null, unscored: Boolean = score == null) = IndexedFile(
    id = id, uri = "fake://photo$id", relativePath = "DCIM/Camera/IMG_$id.jpg", name = "IMG_$id.jpg", kind = FileKind.IMAGE,
    mimeType = "image/jpeg", sizeBytes = 2_000_000, modifiedMillis = 1_000, looksPrivate = score, privacyUnscored = unscored,
  )

  private fun show(blur: PreviewBlur, vararg files: IndexedFile, visible: androidx.compose.runtime.State<Boolean> = mutableStateOf(true)) {
    rule.setContent {
      FindrTheme(mode = ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalPreviewBlur provides blur) {
          if (visible.value) androidx.compose.foundation.layout.Row {
            for (f in files) MediaCell(SearchHit(f, 1.0, MatchReason.MEANING), onOpen = {}, onShare = {}, onToggle = {}, modifier = Modifier.width(110.dp))
          }
        }
      }
    }
    rule.waitForIdle()
  }

  private fun count(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true)

  @Test fun anUnscoredPictureRendersBlurredAndASafeScoreClearsIt() {
    val scorer = HandScorer()
    show(PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = scorer), picture(1))
    count(checkingWords).assertCountEquals(1)
    assertEquals("it asks for its score because it is on screen", listOf(1L), scorer.asked)
    scorer.answers.getValue(1).complete(0.12f)
    rule.waitForIdle()
    count(checkingWords).assertCountEquals(0)
    count(privateWords).assertCountEquals(0)
  }

  @Test fun aPrivateScoreKeepsItBlurredAsPrivate() {
    val scorer = HandScorer()
    show(PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = scorer), picture(1))
    scorer.answers.getValue(1).complete(0.97f)
    rule.waitForIdle()
    count(checkingWords).assertCountEquals(0)
    count(privateWords).assertCountEquals(1)
  }

  @Test fun scoredPicturesRenderClearOrPrivateAndAskForNothing() {
    val scorer = HandScorer()
    show(PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = scorer), picture(1, 0.05f), picture(2, 0.97f))
    count(checkingWords).assertCountEquals(0)
    count(privateWords).assertCountEquals(1)
    assertEquals(emptyList<Long>(), scorer.asked)
  }

  @Test fun aPictureThatLeavesTheScreenDropsItsAsk() {
    val scorer = HandScorer()
    val visible = mutableStateOf(true)
    show(PreviewBlur(enabled = true, check = scorer), picture(1), picture(2), visible = visible)
    assertEquals(setOf(1L, 2L), scorer.asked.toSet())
    visible.value = false   // scrolled away
    rule.waitForIdle()
    assertEquals(setOf(1L, 2L), scorer.dropped.toSet())
  }

  @Test fun withoutAScorerAnUnscoredPictureIsDrawnAsBefore() {
    show(PreviewBlur(enabled = true), picture(1))
    count(checkingWords).assertCountEquals(0)
    count(privateWords).assertCountEquals(0)
  }
}
