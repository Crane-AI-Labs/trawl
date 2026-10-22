package trawl.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * Right-to-left layout for the languages that read that way. Of the 14 Trawl ships only Arabic does; the others in the set are here so a
 * language added later needs no change. Compose mirrors rows, paddings and alignment from [LayoutDirection]; the text direction inside a
 * paragraph is set by the text itself.
 */
object Rtl {
  private val RIGHT_TO_LEFT = setOf("ar", "he", "fa", "ur", "ps", "sd", "ug", "yi", "dv", "ckb")

  fun isRtl(tag: String): Boolean = tag.substringBefore('-').substringBefore('_').lowercase() in RIGHT_TO_LEFT

  /** The direction for the window: the person's pick, or the computer's language when they follow it. */
  fun directionFor(saved: String?, computer: Locale = Locale.getDefault()): LayoutDirection =
    if (isRtl(saved ?: computer.language)) LayoutDirection.Rtl else LayoutDirection.Ltr
}

/** Draws [content] in the direction of the language the window is in. */
@Composable
fun ProvideLayoutDirection(language: String?, content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalLayoutDirection provides Rtl.directionFor(language), content = content)
}
