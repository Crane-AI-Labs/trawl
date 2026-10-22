package com.craneai.findr.diag

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.craneai.findr.core.FindrLog as Log

/**
 * Debug builds only (src/debug): makes Findr fail on purpose so the crash-report path can be checked on a phone.
 *   adb shell am start -n <pkg>/com.craneai.findr.diag.CrashTestActivity --es mode crash
 *   adb shell am start -n <pkg>/com.craneai.findr.diag.CrashTestActivity --es mode anr
 * The crash message holds a made-up file name and search on purpose: neither may appear in the report.
 * For the ANR, the main thread is blocked for a minute; tapping the screen meanwhile makes the system declare it.
 */
class CrashTestActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(TextView(this).apply { text = "Findr crash test: ${intent.getStringExtra("mode")}"; textSize = 20f; setPadding(48, 96, 48, 48) })
    Log.i("CrashTest", "mode ${intent.getStringExtra("mode")}")
    when (intent.getStringExtra("mode")) {
      "crash" -> Handler(Looper.getMainLooper()).postDelayed({
        throw IllegalStateException("could not open /storage/emulated/0/Download/Secret Budget 2026.pdf for the search \"my salary slip\"")
      }, 500)
      "anr" -> Handler(Looper.getMainLooper()).postDelayed({ Thread.sleep(60_000) }, 500)
    }
  }
}
