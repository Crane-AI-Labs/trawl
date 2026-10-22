package com.craneai.findr.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class FindrLogTest {
  private val before = FindrLog.detail

  @After fun restore() {
    FindrLog.detail = before
  }

  @Test fun releaseRedactsPrivateValues() {
    FindrLog.detail = false
    assertEquals(FindrLog.PRIVATE, FindrLog.redact("HIV result.pdf"))
    assertEquals(FindrLog.PRIVATE, FindrLog.redact(null))
    assertEquals("FileNotFoundException: <private>", FindrLog.brief(FileNotFoundException("/storage/emulated/0/Download/HIV result.pdf")))
    assertEquals("IOException", FindrLog.brief(IOException()))
  }

  @Test fun detailBuildsShowThem() {
    FindrLog.detail = true
    assertEquals("HIV result.pdf", FindrLog.redact("HIV result.pdf"))
    assertEquals("FileNotFoundException: /x/a.pdf", FindrLog.brief(FileNotFoundException("/x/a.pdf")))
  }

  @Test fun releaseTracesKeepClassesAndFramesButNoMessages() {
    FindrLog.detail = false
    val t = IllegalStateException("could not read /storage/emulated/0/DCIM/private.jpg", FileNotFoundException("/storage/emulated/0/DCIM/private.jpg"))
    val trace = FindrLog.trace(t)
    assertFalse(trace, trace.contains("private.jpg"))
    assertFalse(trace, trace.contains("/storage"))
    assertTrue(trace, trace.startsWith("java.lang.IllegalStateException: <private>"))
    assertTrue(trace, trace.contains("Caused by: java.io.FileNotFoundException: <private>"))
    assertTrue("frames are kept for diagnosis: $trace", trace.contains("\tat com.craneai.findr.core.FindrLogTest"))
  }

  @Test fun traceStopsOnACycle() {
    val a = RuntimeException("a")
    val b = RuntimeException("b", a)
    a.initCause(b)
    assertEquals(1, Regex("Caused by").findAll(FindrLog.trace(b, framesEach = 0)).count())
  }

  @Test fun theDebugBuildHasDetailOn() {
    // Unit tests run against the debug variant; a release build gets LOG_DETAIL = false unless -PfindrLogDetail=true.
    assertTrue(com.craneai.findr.BuildConfig.LOG_DETAIL)
  }
}
