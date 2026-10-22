package com.craneai.findr.ui.background

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.craneai.findr.R
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.ui.common.HomeBanner
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType

/**
 * Samsung (One UI) closes apps that the person has not opened for a while, and "Auto optimisation" in Device care can close
 * background apps overnight. On the owner's S24 the system process force-stopped Trawl at 03:04 and 21:05; after a force stop
 * Android runs none of the app's jobs or alarms until the app is opened again, so overnight indexing is lost (measured on a
 * Samsung tablet: 4 registered jobs, 0 after a force stop, and none come back by themselves).
 *
 * Unlike TECNO/Infinix (see [BackgroundAccess]) Samsung's own setting works, and it is plain Android underneath: Battery >
 * Unrestricted puts the app on the Doze allow-list (so [PowerManager.isIgnoringBatteryOptimizations] reads it, no special
 * permission), lifts RUN_ANY_IN_BACKGROUND and moves it to the exempted standby bucket. The app only reads that state and opens
 * the settings page. It never asks for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (a restricted permission on Google Play).
 * Samsung's "Never auto sleeping apps" list is not enough on its own: on the tablet it only took the app out of Sleeping apps
 * (back to the Optimised state) and did not touch the Doze allow-list. Research notes: internal/research/samsung-sleep.md.
 */
object SamsungSleep {
  private const val TAG = "FindrSamsungSleep"

  /** True on Samsung phones and tablets, by Build.MANUFACTURER ("samsung"). */
  fun applies(manufacturer: String?): Boolean = manufacturer?.trim().equals("samsung", ignoreCase = true)

  fun appliesHere(): Boolean = applies(Build.MANUFACTURER)

  /**
   * The show-or-hide rule for the Home card and the Settings row. Only on Samsung, only once there is something to learn
   * ([waiting]: files known and not yet done), only while the app is not already Unrestricted, and never again after OK.
   * [allFiles] is the same gate the Transsion card uses: before the person has granted access nothing is being indexed.
   */
  fun shouldShow(samsung: Boolean, allFiles: Boolean, waiting: Boolean, unrestricted: Boolean, dismissed: Boolean): Boolean =
    samsung && allFiles && waiting && !unrestricted && !dismissed

  /** Settings row: the same, without the "files are waiting" and the dismissed conditions (it is the place to come back to). */
  fun settingsRowShown(samsung: Boolean, unrestricted: Boolean): Boolean = samsung && !unrestricted

  /**
   * Whether the app is on the Doze allow-list, which is what Battery > Unrestricted does on One UI 6, 7 and 8 (measured on 8).
   * Optimised, Restricted and the Sleeping apps list all read false. A failure reads as "not unrestricted": the card shows,
   * and the worst case is one note too many.
   */
  fun isUnrestricted(context: Context): Boolean = try {
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
  } catch (e: RuntimeException) {
    false
  }

  /** Where the button can go, best first. */
  sealed interface Target {
    val label: String

    /** Android's "Battery" page for one app (App info > Battery): the Unrestricted / Optimised / Restricted choice is on it, or one tap in. */
    data class Action(val action: String, override val label: String) : Target

    /** App info, the last resort: it always exists, and Battery is one row down. */
    data object AppDetails : Target {
      override val label = "app details"
    }
  }

  /** The Settings action that opens an app's battery page; Samsung's Settings answers it (the APP_BATTERY_SETTINGS action does not resolve there). */
  const val POWER_USAGE_DETAIL = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"
  val TARGETS: List<Target> = listOf(Target.Action(POWER_USAGE_DETAIL, "app battery page"), Target.AppDetails)

  fun intentFor(target: Target, packageName: String): Intent = when (target) {
    is Target.Action -> Intent(target.action, Uri.fromParts("package", packageName, null))
    Target.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
  }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** Opens the first target this phone has. Returns the one that opened, or null when none did. */
  fun open(context: Context): Target? {
    for (target in TARGETS) {
      try {
        context.startActivity(intentFor(target, context.packageName))
        Log.i(TAG, "samsung battery setting: opened ${target.label}")   // log-safe: a fixed screen name
        return target
      } catch (e: ActivityNotFoundException) {
        continue
      } catch (e: SecurityException) {
        continue
      }
    }
    Log.w(TAG, "samsung battery setting: nothing could be opened")
    return null
  }
}

/** The Home card (Samsung only), in the same two forms as [BackgroundPauseCard]: a strip when the window is short or wide, else a card. */
@Composable
fun SamsungSleepCard(onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
  if (compact) {
    HomeBanner(
      title = stringResource(R.string.bgs_title),
      modifier = modifier,
      body = appString(R.string.bgs_body),
      dismissLabel = stringResource(R.string.models_ok),
      onDismiss = onDismiss,
    ) { SunButton(onClick = onOpen) { Text(stringResource(R.string.bgs_open), style = SunType.Label) } }
    return
  }
  SunCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      Text(
        stringResource(R.string.bgs_title),
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.height(Sun.S4))
      Text(appString(R.string.bgs_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S12))
      SunButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bgs_open), style = SunType.Label) }
      SunTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.models_ok)) }
    }
  }
}

/** The same setting from Settings, for someone who put the card away (Samsung, not yet Unrestricted). */
@Composable
fun SamsungSleepSettingsRow() {
  val context = LocalContext.current
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S8)) {
    Text(stringResource(R.string.bgs_title), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface)
    Text(appString(R.string.bgs_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sun.S8))
    SunOutlinedButton(onClick = { SamsungSleep.open(context) }) { Text(stringResource(R.string.bgs_open), style = SunType.Label) }
  }
}
