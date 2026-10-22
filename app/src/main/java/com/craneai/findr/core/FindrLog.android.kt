package com.craneai.findr.core

import android.util.Log

/** The phone's log sink: logcat. The only place in the app that calls `android.util.Log` (LogHygieneTest allows this file). */
object AndroidLogSink : LogSink {
  override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
    when (level) {
      'I' -> Log.i(tag, msg)
      'W' -> if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
      'E' -> if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
      else -> Log.d(tag, msg)
    }
  }
}
