package com.craneai.findr.ui.background

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.craneai.findr.R
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.ui.common.HomeBanner
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType

/**
 * Transsion phones (TECNO, Infinix, itel: most phones sold in Uganda) freeze an app a few seconds after the screen goes off
 * (HiOS/XOS "Hiber"). On a TECNO KN4 indexing stopped until the app was opened again, even on the charger, with its foreground
 * service running and with Android's battery-optimisation exemption (the research notes). The phone's own per-app switch
 * (Phone Master > Auto-start management) did not stop it either: it was on and the app was still 97% frozen over 7 minutes
 * (the research notes). So the copy is honest: the phone pauses the app, it learns while open, and
 * Keep learning (ui/keeplearning) on the charger is what works. The switch stays as a secondary action, only for starting again
 * after a restart. These phones get a card on Home while files are waiting and a Settings row; others see neither.
 */
object BackgroundAccess {
  private const val TAG = "FindrBackground"
  private val TRANSSION = setOf("tecno", "infinix", "itel", "transsion")

  /** True on TECNO, Infinix and itel phones, by manufacturer or brand ("INFINIX", "TECNO", "itel", "INFINIX MOBILITY LIMITED"). */
  fun applies(manufacturer: String?, brand: String?): Boolean =
    listOf(manufacturer, brand).any { value -> val v = value?.trim()?.lowercase().orEmpty(); TRANSSION.any { v.startsWith(it) } }

  fun appliesHere(): Boolean = applies(Build.MANUFACTURER, Build.BRAND)

  /** Where the button can go, best first. The app's own system settings page always exists, so it is the last resort. */
  sealed interface Target {
    val label: String

    /** An intent action Phone Master exports for its auto-start screen (HiOS 16 and XOS 16 both declare it). */
    data class Action(val action: String, val pkg: String, override val label: String) : Target

    /** The same screen by class name, for Phone Master builds without the action. */
    data class Component(val pkg: String, val cls: String, override val label: String) : Target

    /** Android's page for this app (Battery > Allow background usage). */
    data object AppDetails : Target {
      override val label = "app details"
    }
  }

  const val PHONE_MASTER = "com.transsion.phonemaster"
  val TARGETS: List<Target> = listOf(
    Target.Action("com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY", PHONE_MASTER, "Phone Master auto-start"),
    Target.Component(PHONE_MASTER, "com.cyin.himgr.autostart.AutoStartActivity", "Phone Master auto-start (by class)"),
    Target.AppDetails,
  )

  fun intentFor(target: Target, packageName: String): Intent = when (target) {
    is Target.Action -> Intent(target.action).setPackage(target.pkg)   // pinned to Phone Master: no other app can answer it
    is Target.Component -> Intent().setComponent(ComponentName(target.pkg, target.cls))
    Target.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
  }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** Opens the first target this phone has. Returns the one that opened, or null when none did. */
  fun open(context: Context): Target? {
    for (target in TARGETS) {
      try {
        context.startActivity(intentFor(target, context.packageName))
        Log.i(TAG, "background setting: opened ${target.label}")   // log-safe: a fixed screen name, not the person's data
        return target
      } catch (e: ActivityNotFoundException) {
        continue
      } catch (e: SecurityException) {   // a vendor screen that is not exported on this build
        continue
      }
    }
    Log.w(TAG, "background setting: nothing could be opened")
    return null
  }
}

/**
 * The Home card (Transsion phones only), shown while files are still waiting to be learned. It used to be an onboarding page, but
 * at setup there was nothing yet to act on; here the person has just started, the meter is moving, and "Keep learning now" is
 * one tap. It explains the pausing and points at Keep learning; OK dismisses it for good. The phone-settings button stays in
 * Settings → On this phone (the Auto-start switch only lets the app start again after a restart).
 */
@Composable
fun BackgroundPauseCard(onKeepLearning: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
  if (compact) {
    HomeBanner(
      title = stringResource(R.string.bg_title),
      modifier = modifier,
      body = appString(R.string.bg_body),
      dismissLabel = stringResource(R.string.models_ok),
      onDismiss = onDismiss,
    ) { SunButton(onClick = onKeepLearning) { Text(stringResource(R.string.keep_action), style = SunType.Label) } }
    return
  }
  SunCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      Text(
        stringResource(R.string.bg_title),
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.height(Sun.S4))
      Text(appString(R.string.bg_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S12))
      SunButton(onClick = onKeepLearning, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_action), style = SunType.Label) }
      SunTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.models_ok)) }
    }
  }
}

/** The same setting from Settings, for someone who chose Later (Transsion phones only). */
@Composable
fun BackgroundAccessSettingsRow() {
  val context = LocalContext.current
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S8)) {
    Text(stringResource(R.string.bg_title), style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface)
    Text(appString(R.string.bg_settings_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sun.S8))
    SunOutlinedButton(onClick = { BackgroundAccess.open(context) }) { Text(stringResource(R.string.bg_open), style = SunType.Label) }
  }
}
