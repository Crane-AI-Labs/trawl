package com.craneai.findr.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.craneai.findr.R

/**
 * Atkinson Hyperlegible (Braille Institute, SIL OFL 1.1; assets/licenses), bundled in res/font, never downloaded. Two weights
 * only. It was drawn so that b d p q, I l 1 and 0 O cannot be confused, which is what a file name needs.
 */
val Atkinson = FontFamily(
  Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
  Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

private fun t(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
  TextStyle(fontFamily = Atkinson, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)

/** Sunlight's seven styles (sp, so the phone's font size works up to 200%). Caption, 14 sp, is the smallest text in the app. */
object SunType {
  val Display = t(32, 38, FontWeight.Bold)     // onboarding titles, the lock screen; one per screen
  val Title = t(24, 30, FontWeight.Bold)       // page titles
  val Heading = t(20, 26, FontWeight.Bold)     // section headings, dialog titles, the meter headline
  val BodyLarge = t(18, 26)                    // the default: search text, result names, onboarding body, switch labels
  val Body = t(16, 24)                         // secondary lines: paths, dates, hints under a switch
  val Label = t(16, 20, FontWeight.Bold)       // buttons, chips, the words under icons
  val Caption = t(14, 18)                      // counts
  val CaptionBold = t(14, 18, FontWeight.Bold) // the same size, for a count that must stand out
}

/** Every Material role is one of the seven, so a component's default style is always Sunlight's (none below 14 sp). */
val FindrTypography = Typography(
  displayLarge = SunType.Display,
  displayMedium = SunType.Display,
  displaySmall = SunType.Display,
  headlineLarge = SunType.Title,
  headlineMedium = SunType.Title,
  headlineSmall = SunType.Title,
  titleLarge = SunType.Heading,
  titleMedium = SunType.Heading,
  titleSmall = SunType.Label,
  bodyLarge = SunType.BodyLarge,
  bodyMedium = SunType.Body,
  bodySmall = SunType.Caption,
  labelLarge = SunType.Label,
  labelMedium = SunType.Label,
  labelSmall = SunType.CaptionBold,
)
