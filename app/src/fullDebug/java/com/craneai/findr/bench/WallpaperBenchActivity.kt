package com.craneai.findr.bench

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import com.craneai.findr.core.FindrLog as Log
import java.io.File

/**
 * Debug builds only: sets the TEST phone's home-screen wallpaper so wallpaper colours can be checked and photographed, and puts
 * it back afterwards. Never on the S24 (the phone lock refuses it; this activity is not in release APKs).
 *   adb shell am start -n <pkg>/com.craneai.findr.bench.WallpaperBenchActivity --es cmd save
 *   ... --es cmd set --es color '#0D2A6B'        a soft vertical gradient of that colour
 *   ... --es cmd restore                          the saved one, or the phone's default when nothing was saved
 * Only the home screen (FLAG_SYSTEM); the lock screen is left alone.
 */
class WallpaperBenchActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val wm = WallpaperManager.getInstance(this)
    val backup = File(filesDir, "wallpaper-system-backup")
    val cmd = intent.getStringExtra("cmd")
    val result = runCatching {
      when (cmd) {
        "save" -> {
          val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
          if (pfd == null) { backup.delete(); "no wallpaper file to save (the default is in use)" } else {
            pfd.use { p -> java.io.FileInputStream(p.fileDescriptor).use { input -> backup.outputStream().use { input.copyTo(it) } } }
            "saved ${backup.length()} bytes"
          }
        }
        "set" -> {
          val c = android.graphics.Color.parseColor(intent.getStringExtra("color") ?: "#0D2A6B")
          val w = 1080
          val h = 2400
          val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
          val darker = android.graphics.Color.rgb(
            (android.graphics.Color.red(c) * 0.75).toInt(), (android.graphics.Color.green(c) * 0.75).toInt(), (android.graphics.Color.blue(c) * 0.75).toInt(),
          )
          Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), c, darker, Shader.TileMode.CLAMP) })
          wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
          "set ${intent.getStringExtra("color")}"
        }
        "restore" -> if (backup.isFile && backup.length() > 0) {
          backup.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
          "restored the saved wallpaper (${backup.length()} bytes)"
        } else {
          wm.clear(WallpaperManager.FLAG_SYSTEM)
          "restored the default wallpaper"
        }
        else -> "unknown cmd $cmd"
      }
    }
    Log.i("FindrWallpaperBench", result.getOrElse { "failed: ${it.javaClass.simpleName}" })   // log-safe: our own status words
    finish()
  }
}
