package com.craneai.findr.ui.background

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.craneai.findr.BuildConfig
import com.craneai.findr.R
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunType

/**
 * "Let Trawl keep reading when you're not using it": the one-tap way to take the app off Android's battery optimisation, asked
 * at setup (a page after All files access) and again from Settings. On Samsung this is what the Battery screen calls
 * "Unrestricted" (the Doze allow-list; [SamsungSleep] measured that the two are the same state), so the Samsung card on Home
 * hides itself as soon as the answer is yes: both read [PowerManager.isIgnoringBatteryOptimizations].
 *
 * Two routes, chosen at build time ([BuildConfig.BATTERY_EXEMPTION_REQUEST], gradle property `batteryExemptionRequest`, on by default):
 *  - [Route.SYSTEM_DIALOG]: ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. Android shows its own yes/no box; the app never sees more
 *    than the answer. It needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest, which a build with the flag off does not declare
 *    (app/src/batteryRequest/AndroidManifest.xml is added to the sideload build types only).
 *  - [Route.BATTERY_PAGE]: the app's own battery page ([SamsungSleep.open]: App info > Battery, then App info). This is the only
 *    route that is compatible with Google Play for an app like this one (internal/research/setup-background-step.md), and it is the
 *    fallback when the system box is missing or refuses.
 * The result is never taken from the activity result (it is unreliable across vendors); the state is read again, always.
 * "Not now" never blocks anything: the page is skippable and nothing here is a condition of Start.
 */
object BatteryExemption {
  private const val TAG = "FindrBatteryExemption"

  /** Where the Allow button sends the person. */
  enum class Route { SYSTEM_DIALOG, BATTERY_PAGE }

  /** What happened when the button was pressed, if anything: the box was shown, the battery page was opened, or neither could open. */
  enum class Tried { DIALOG, PAGE, NOTHING }

  /** What the page or row shows. */
  enum class State { ASK, ALLOWED, DENIED, PAGE_OPENED }

  /** The setup page is only for someone who is still optimised: a phone that already ignores optimisation skips it. */
  fun stepShown(exemptAtStart: Boolean): Boolean = !exemptAtStart

  fun route(requestBuilt: Boolean): Route = if (requestBuilt) Route.SYSTEM_DIALOG else Route.BATTERY_PAGE

  /**
   * The state to show: Allowed whenever Android says so (whatever was tried), otherwise what the last press led to.
   * [tried] is null before any press.
   */
  fun state(exempt: Boolean, tried: Tried?): State = when {
    exempt -> State.ALLOWED
    tried == null -> State.ASK
    tried == Tried.PAGE -> State.PAGE_OPENED
    else -> State.DENIED
  }

  /** True when Android lists this app as ignoring battery optimisations. A failure reads as "not allowed": the page shows, worst case once too often. */
  fun isExempt(context: Context): Boolean = SamsungSleep.isUnrestricted(context)

  fun requestIntent(packageName: String): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))

  /**
   * Presses the button: the system box when this build has it and the phone answers, else the app's battery page.
   * [launch] starts the system box (an activity-result launcher, so the app is re-checked when it closes).
   */
  fun request(context: Context, requestBuilt: Boolean = BuildConfig.BATTERY_EXEMPTION_REQUEST, launch: (Intent) -> Unit): Tried {
    if (route(requestBuilt) == Route.SYSTEM_DIALOG) {
      try {
        launch(requestIntent(context.packageName))
        Log.i(TAG, "battery exemption: system box shown")
        return Tried.DIALOG
      } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "battery exemption: no system box on this phone, opening the battery page")
      } catch (e: SecurityException) {
        Log.w(TAG, "battery exemption: system box refused, opening the battery page")
      }
    }
    return if (SamsungSleep.open(context) != null) Tried.PAGE else Tried.NOTHING
  }
}

/** The page or row's live state and its button. */
class BatteryExemptionControl(val state: BatteryExemption.State, val request: () -> Unit)

/**
 * Holds the state for one screen: read now, read again every time the app comes back (the system box and the battery page both
 * leave the app) and when the system box closes. [request] is the button.
 */
@Composable
fun rememberBatteryExemption(): BatteryExemptionControl {
  val context = LocalContext.current
  var exempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }
  var tried by rememberSaveable { mutableStateOf<String?>(null) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    exempt = BatteryExemption.isExempt(context)
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryExemption.isExempt(context) }
  val state = BatteryExemption.state(exempt, tried?.let { runCatching { BatteryExemption.Tried.valueOf(it) }.getOrNull() })
  return BatteryExemptionControl(state) {
    tried = BatteryExemption.request(context) { launcher.launch(it) }.name
  }
}

/** The result line: a tick and "Allowed" when on (an icon and a word, never colour alone), the plain reason after a no, the hint after the battery page opened. */
@Composable
fun BatteryExemptionStatus(state: BatteryExemption.State, modifier: Modifier = Modifier) {
  when (state) {
    BatteryExemption.State.ASK -> Unit
    BatteryExemption.State.ALLOWED -> Row(modifier.heightIn(min = Sun.Target), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = LocalSunlight.current.good, modifier = Modifier.size(Sun.Icon))
      Spacer(Modifier.width(Sun.S8))
      Text(stringResource(R.string.bgx_granted), style = SunType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
    BatteryExemption.State.DENIED ->
      Text(stringResource(R.string.bgx_denied), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
    BatteryExemption.State.PAGE_OPENED ->
      Text(stringResource(R.string.bgx_page_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
  }
}

/**
 * The body of the Settings row: the one-sentence explanation, the Allow button (until it is on) and the result line. The setup
 * page has its own layout (its Allow and Not now buttons sit in the pager's bottom bar, like the files page) and passes
 * [showButton] = false.
 */
@Composable
fun BatteryExemptionBody(
  control: BatteryExemptionControl,
  modifier: Modifier = Modifier,
  showBody: Boolean = true,
  showButton: Boolean = true,
) {
  Column(modifier) {
    if (showBody) {
      Text(appString(R.string.bgx_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S12))
    }
    if (showButton && control.state != BatteryExemption.State.ALLOWED) {
      SunButton(onClick = control.request, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bgx_allow), style = SunType.Label) }
      Spacer(Modifier.height(Sun.S8))
    }
    BatteryExemptionStatus(control.state)
  }
}

/** The Settings row (every phone): the title, the same sentence and button, and a way to the battery page once it is on. */
@Composable
fun BatteryExemptionSettingsRow() {
  val context = LocalContext.current
  val control = rememberBatteryExemption()
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S8)) {
    Text(
      appString(R.string.bgx_title),
      style = SunType.BodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Sun.S4))
    BatteryExemptionBody(control)
    if (control.state == BatteryExemption.State.ALLOWED) {
      Spacer(Modifier.height(Sun.S8))
      // Android has no call to take the exemption back: changing it later is done on the app's battery page.
      SunOutlinedButton(onClick = { SamsungSleep.open(context) }) { Text(stringResource(R.string.bgs_open), style = SunType.Label) }
    }
  }
}
