package com.craneai.findr.ui.addons

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackDef
import com.craneai.findr.core.sizeCopy
import com.craneai.findr.ui.common.appString
import java.text.NumberFormat

/**
 * Reads a pack's copy in the current language. The words are resources and the numbers come from `PackCatalog.Facts`
 * (core/Packs.kt); this file puts them together. The app's name is filled in after the sentence's own arguments, as
 * everywhere (ui/common/AppName.kt), so any pack sentence may mention it.
 *
 * In a screen: `row.def.title()`, `row.def.benefitText()`, `row.def.costText()`, `row.def.recommendedText()`,
 * `row.def.sizeLabel()`, `packCopy.text()`. Outside a screen, pass a Context (or Resources).
 */
fun PackCopy.resolve(resources: Resources): String {
  if (res == 0) return ""
  val values = args.map { a ->
    when (a) {
      is PackCopy -> a.resolve(resources)
      is PackCopy.Grouped -> NumberFormat.getIntegerInstance(currentLocale(resources)).format(a.n)
      else -> a
    }
  }.toTypedArray()
  val q = quantity
  return if (q != null) resources.getQuantityString(res, q, *values, resources.getString(com.craneai.findr.R.string.app_name))
  else resources.appString(res, *values)
}

fun PackCopy.resolve(context: Context): String = resolve(context.resources)

private fun currentLocale(resources: Resources): java.util.Locale = resources.configuration.locales.get(0)

/** A [PackCopy] in the current language. Null (a pack with no such copy) reads as an empty string. */
@Composable
@ReadOnlyComposable
fun PackCopy?.text(): String {
  LocalConfiguration.current   // re-read when the language changes
  return this?.resolve(LocalContext.current.resources).orEmpty()
}

// ---- A pack's title, description, cost, who it is for, and size: in a screen ---------------------------------------

@Composable @ReadOnlyComposable fun PackDef.title(): String = PackCopy(nameRes).text()
@Composable @ReadOnlyComposable fun PackDef.benefitText(): String = benefit.text()
@Composable @ReadOnlyComposable fun PackDef.costText(): String = cost.text()
/** "Recommended if ...", or an empty string when the pack has no such line. */
@Composable @ReadOnlyComposable fun PackDef.recommendedText(): String = recommendedFor.text()
/** "142 MB". */
@Composable @ReadOnlyComposable fun PackDef.sizeLabel(): String = sizeCopy.text()

// ---- The same, from a Context ---------------------------------------------------------------------------------------

fun PackDef.title(context: Context): String = nameCopy.resolve(context)
fun PackDef.benefitText(context: Context): String = benefit.resolve(context)
fun PackDef.costText(context: Context): String = cost.resolve(context)
fun PackDef.recommendedText(context: Context): String = recommendedFor?.resolve(context).orEmpty()
fun PackDef.sizeLabel(context: Context): String = sizeCopy.resolve(context)
