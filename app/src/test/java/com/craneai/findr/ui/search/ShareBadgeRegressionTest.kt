package com.craneai.findr.ui.search

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h780dp-xxhdpi")
class ShareBadgeRegressionTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  private fun assertBadge(painter: Painter?) {
    var shares = 0
    val label = "Share report.pdf"
    rule.setContent {
      FindrTheme(mode = ThemeMode.LIGHT) {
        Box(Modifier.fillMaxSize()) {
          ShareBadge(onClick = { shares++ }, painter = painter, fileName = "report.pdf")
        }
      }
    }
    rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).assertCountEquals(1)
    rule.onAllNodesWithContentDescription("Share", useUnmergedTree = true).assertCountEquals(0)
    val badge = rule.onNodeWithContentDescription(label)
    badge.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    assertEquals(label, badge.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    badge.performTouchInput { click(Offset(width * 0.125f, center.y)) }
    rule.runOnIdle { assertEquals(1, shares) }
  }

  @Test fun vectorBadgeHasOneNamedLabelAndRespondsOutsideItsDisc() {
    assertBadge(painter = null)
  }

  @Test fun painterBadgeHasOneNamedLabelAndRespondsOutsideItsDisc() {
    assertBadge(painter = ColorPainter(Color.White))
  }
}
