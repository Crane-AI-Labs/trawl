package com.craneai.findr.ui.keeplearning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.FindrApp
import com.craneai.findr.R
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.index.etaLabel
import com.craneai.findr.ui.index.understoodFraction
import com.craneai.findr.ui.language.AppLocale
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/**
 * The keep-learning screen ([KeepLearning]): black, the window's brightness at its lowest (the phone's own setting is left
 * alone), the screen kept on, counts only. A tap, Back, or leaving the screen ends it. It stops by itself when the work is done
 * or when heat, the battery or the charger say so; it then says why, lets the screen turn off normally, and waits for Close.
 * While it shows, Trawl is "on screen" for the engine, so indexing runs in-process as it does with the app open.
 */
class KeepLearningActivity : ComponentActivity() {
  private val battery = mutableStateOf(BatteryState())
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { battery.value = BatteryState.of(intent) }
  }

  override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
      ?.let { battery.value = BatteryState.of(it) }
    awake(true)
    val engine = (application as FindrApp).engine
    val power = getSystemService(PowerManager::class.java)
    val startedOnCharger = battery.value.plugged
    engine.status.value.let { s ->
      Log.i(TAG, "keep learning: started (charging $startedOnCharger, battery ${battery.value.pct}%, ${s.filesUnderstood} of ${s.filesKnown} understood)")
    }
    setContent {
      FindrTheme(mode = ThemeMode.DARK) {
        val status by engine.status.collectAsStateWithLifecycle()
        var stopped by remember { mutableStateOf<KeepLearning.Stop?>(null) }
        // Each tick: how long the engine's state and its progress have held, and the battery and heat. Then the rules decide.
        LaunchedEffect(Unit) {
          var lastKey: Pair<IndexPhase, Any?>? = null
          var keySince = SystemClock.elapsedRealtime()
          var lastDone = -1
          var progressSince = SystemClock.elapsedRealtime()
          var unpluggedSince: Long? = null
          val started = SystemClock.elapsedRealtime()
          while (stopped == null) {
            val now = SystemClock.elapsedRealtime()
            val s = engine.status.value
            val key = s.phase to s.pauseReason
            if (key != lastKey) { lastKey = key; keySince = now }
            val done = s.filesUnderstood + s.filesSearchable
            if (done != lastDone) { lastDone = done; progressSince = now }
            val b = battery.value
            unpluggedSince = if (b.plugged) null else unpluggedSince ?: now
            val reason = KeepLearning.stopReason(
              KeepLearning.Inputs(
                phase = s.phase, pauseReason = s.pauseReason, needsCharger = s.needsCharger,
                sameStateForMs = now - keySince, noProgressForMs = now - progressSince,
                plugged = b.plugged, batteryPct = b.pct, batteryTempC = b.tempC, thermalStatus = power.currentThermalStatus,
                startedOnCharger = startedOnCharger, unpluggedForMs = unpluggedSince?.let { now - it } ?: 0L,
              ),
            )
            if (reason != null) {
              stopped = reason
              awake(false)
              Log.i(TAG, "keep learning: stopped $reason after ${(now - started) / 60_000} min (battery ${b.pct}%, ${b.tempC} °C, thermal ${power.currentThermalStatus}, ${s.filesUnderstood} of ${s.filesKnown} understood)")
            }
            delay(TICK_MS)
          }
        }
        val reason = stopped
        if (reason == null) Running(status, battery.value, onStop = ::finish) else Stopped(reason, onClose = ::finish)
      }
    }
  }

  /** Keep the screen on at the window's lowest brightness, or give the phone its normal screen timeout and brightness back. */
  private fun awake(on: Boolean) {
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.attributes = window.attributes.apply {
      screenBrightness = if (on) LOWEST_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
  }

  override fun onStop() {
    super.onStop()
    // Home, the recent apps, or the power button: the screen is no longer showing, so it has ended.
    if (!isChangingConfigurations && !isFinishing) {
      Log.i(TAG, "keep learning: left the screen")
      finish()
    }
  }

  override fun onDestroy() {
    runCatching { unregisterReceiver(receiver) }
    super.onDestroy()
  }

  private companion object {
    const val TAG = "FindrKeep"
    const val TICK_MS = 5_000L
    const val LOWEST_BRIGHTNESS = 0.01f
  }
}

/** The battery as the screen needs it: plugged in, percentage, temperature. */
internal data class BatteryState(val plugged: Boolean = true, val pct: Int = 100, val tempC: Double = 0.0) {
  companion object {
    fun of(i: Intent): BatteryState {
      val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
      return BatteryState(
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        pct = if (level < 0) -1 else level * 100 / scale,
        tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
      )
    }
  }
}

private val Ink = Color(0xFFB8B8B8)
private val InkDim = Color(0xFF7A7A7A)

@Composable
private fun Running(status: IndexStatus, battery: BatteryState, onStop: () -> Unit) {
  // The block drifts a little each minute, so no pixel shows the same thing all night (OLED screens).
  var drift by remember { mutableStateOf(0) }
  LaunchedEffect(Unit) { while (true) { delay(60_000); drift = (drift + 1) % DRIFT.size } }
  val (dx, dy) = DRIFT[drift]
  Box(
    Modifier
      .fillMaxSize()
      .background(Color.Black)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStop),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      Modifier.offset(dx.dp, dy.dp).padding(horizontal = Sun.S32).widthIn(max = 480.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Sun.S16),
    ) {
      Text(appString(R.string.keep_heading), style = SunType.Heading, color = Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
      Text(
        stringResource(R.string.keep_counts, formatCount(status.filesUnderstood), formatCount(status.filesReadable)),
        style = SunType.BodyLarge, color = Ink, textAlign = TextAlign.Center,
      )
      LinearProgressIndicator(
        progress = { status.understoodFraction },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
        color = InkDim,
        trackColor = Color(0xFF262626),
        drawStopIndicator = {},
      )
      val eta = if (status.phase == IndexPhase.UP_TO_DATE) null else etaLabel(status.etaSeconds)
      Text(
        if (eta != null) stringResource(R.string.keep_time_left, eta) else stringResource(R.string.keep_time_unknown),
        style = SunType.Body, color = InkDim, textAlign = TextAlign.Center,
      )
      Text(
        stringResource(if (battery.plugged) R.string.keep_charging else R.string.keep_on_battery),
        style = SunType.Body, color = InkDim, textAlign = TextAlign.Center,
      )
      Text(appString(R.string.keep_unlocked), style = SunType.Caption, color = InkDim, textAlign = TextAlign.Center)
      Text(stringResource(R.string.keep_tap_to_stop), style = SunType.Caption, color = InkDim, textAlign = TextAlign.Center)
    }
  }
}

@Composable
private fun Stopped(reason: KeepLearning.Stop, onClose: () -> Unit) {
  val (title, body) = when (reason) {
    KeepLearning.Stop.DONE -> R.string.keep_stop_done_title to R.string.keep_stop_done
    KeepLearning.Stop.LOW_BATTERY -> R.string.keep_stop_battery_title to R.string.keep_stop_battery
    KeepLearning.Stop.HOT -> R.string.keep_stop_hot_title to R.string.keep_stop_hot
    KeepLearning.Stop.UNPLUGGED -> R.string.keep_stop_unplugged_title to R.string.keep_stop_unplugged
    KeepLearning.Stop.NEEDS_CHARGER -> R.string.keep_stop_charger_title to R.string.keep_stop_charger
    KeepLearning.Stop.CANT_CONTINUE -> R.string.keep_stop_paused_title to R.string.keep_stop_paused
  }
  Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    Column(
      Modifier.padding(horizontal = Sun.S32).widthIn(max = 480.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Sun.S16),
    ) {
      Text(stringResource(title), style = SunType.Heading, color = Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
      Text(appString(body), style = SunType.Body, color = InkDim, textAlign = TextAlign.Center)
      Text(stringResource(R.string.keep_screen_will_sleep), style = SunType.Caption, color = InkDim, textAlign = TextAlign.Center)
      SunOutlinedButton(onClick = onClose) { Text(stringResource(R.string.keep_close), style = SunType.Label) }
    }
  }
}

private val DRIFT = listOf(0 to 0, 6 to -4, -5 to 6, 4 to 5, -6 to -5, 0 to 6)
