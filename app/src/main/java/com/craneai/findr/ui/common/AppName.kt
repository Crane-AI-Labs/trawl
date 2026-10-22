package com.craneai.findr.ui.common

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.craneai.findr.R

/**
 * The app's name lives in one string, `app_name`, so renaming the app is a one-file change. Copy that says the name carries it
 * as its LAST positional argument, marked `<xliff:g id="app_name">` so translators keep it (for example "%1$s is locked", or
 * "%2$s could not find %1$s in Downloads"). [appString] fills it in. It is safe for any string: one without the name
 * placeholder is returned exactly as [Resources.getString] would return it.
 */
fun Resources.appString(@StringRes id: Int, vararg args: Any): String {
  val raw = getString(id)
  val nameSlot = "%${args.size + 1}\$s"
  return when {
    raw.contains(nameSlot) -> getString(id, *args, getString(R.string.app_name))
    args.isNotEmpty() -> getString(id, *args)
    else -> raw
  }
}

fun Context.appString(@StringRes id: Int, vararg args: Any): String = resources.appString(id, *args)

/** [stringResource] with the app's name filled in (see [Resources.appString]). */
@Composable
@ReadOnlyComposable
fun appString(@StringRes id: Int, vararg args: Any): String {
  LocalConfiguration.current   // re-read when the language or the configuration changes, as stringResource does
  return LocalContext.current.resources.appString(id, *args)
}
