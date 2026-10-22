package com.craneai.findr.diag

import com.craneai.findr.core.FindrLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/** The privacy rule of problem reports: nothing about the person's files or searches gets into one. */
class RedactionTest {
  private val detailBefore = FindrLog.detail
  private val tapBefore = FindrLog.tap

  @get:Rule val tmp = TemporaryFolder()

  @Before fun releaseLikeLogging() { FindrLog.detail = false }

  @After fun restore() {
    FindrLog.detail = detailBefore
    FindrLog.tap = tapBefore
  }

  private val fileName = "Secret Budget 2026.pdf"
  private val query = "my salary slip"
  private val leaky = IllegalStateException(
    "could not open /storage/emulated/0/Download/$fileName for the search \"$query\"",
    FileNotFoundException("content://media/external/file/77?name=$fileName&q=$query"),
  )

  private fun assertNoLeak(text: String) {
    for (secret in listOf("Secret", "Budget", "2026", ".pdf", "salary", "slip", "/storage", "emulated", "content://", "Download")) {
      assertFalse("leaked '$secret' in:\n$text", text.contains(secret))
    }
  }

  @Test fun traceKeepsClassesAndFramesButNoFileNameOrQuery() {
    val t = Redaction.trace(leaky)
    assertNoLeak(t)
    assertTrue(t, t.startsWith("java.lang.IllegalStateException: <private>"))
    assertTrue(t, t.contains("Caused by: java.io.FileNotFoundException: <private>"))
    assertTrue("frames stay, they name code", t.contains("at com.craneai.findr.diag.RedactionTest"))
  }

  @Test fun traceIsStrictEvenInADetailBuild() {
    FindrLog.detail = true   // logcat shows messages here; a report that other people read must not
    assertNoLeak(Redaction.trace(leaky))
  }

  @Test fun aCrashEventHoldsNoFileNameOrQuery() {
    val ring = LogRing()
    val handler = CrashHandler(ReportStore(tmp.newFolder("r")), ring, previous = null, pid = 4242, clock = { 1_000L })
    val body = handler.eventFor(Thread.currentThread(), leaky).body
    assertNoLeak(body)
    assertTrue(body, body.contains("Event: crash (uncaught exception)"))
    assertTrue(body, body.contains("pid 4242"))
  }

  @Test fun theCrashHandlerWritesTheReportAndThenChains() {
    val store = ReportStore(tmp.newFolder("r"))
    var chained: Throwable? = null
    val handler = CrashHandler(store, LogRing(), { _, e -> chained = e }, pid = 7, clock = { 5_000L })
    handler.uncaughtException(Thread.currentThread(), leaky)
    assertEquals("the system still gets the crash", leaky, chained)
    val stored = store.events()
    assertEquals(1, stored.size)
    assertEquals(EventKind.CRASH, stored[0].kind)
    assertNoLeak(stored[0].body)
  }

  @Test fun aBrokenStoreNeverChangesHowTheAppCrashes() {
    val notADirectory = tmp.newFile("blocked")   // the store's folder cannot be made
    var chained = false
    val handler = CrashHandler(ReportStore(File(notADirectory, "reports")), LogRing(), { _, _ -> chained = true }, pid = 7)
    handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
    assertTrue(chained)
  }

  @Test fun logLinesOfARingAreScrubbedAndTheTapFeedsThemOnlyWithDetailOff() {
    val ring = LogRing(clock = { 0L })
    FindrLog.tap = { level, tag, line -> ring.add(level, tag, line) }
    FindrLog.detail = false
    FindrLog.w("Reader", "failed: ${FindrLog.redact("/storage/emulated/0/Download/$fileName")}", leaky)
    FindrLog.i("Reader", "opened content://media/external/file/9 and /storage/emulated/0/DCIM/a.jpg")   // log-safe: test
    val text = ring.tail().joinToString("\n")
    assertNoLeak(text)
    assertTrue(text, text.contains("W/Reader: failed: <private>"))
    assertTrue(text, text.contains("<uri>") && text.contains("<path>"))

    ring.clear()
    FindrLog.detail = true
    FindrLog.i("Reader", "detail build line")
    assertTrue("a detail build feeds nothing, so a report never holds names from it", ring.tail().isEmpty())
  }

  @Test fun ringKeepsOnlyTheNewestLines() {
    val ring = LogRing(capacity = 5, clock = { 0L })
    repeat(20) { ring.add('I', "T", "line $it") }
    val lines = ring.tail(100)
    assertEquals(5, lines.size)
    assertTrue(lines.last().endsWith("line 19"))
    assertTrue(lines.first().endsWith("line 15"))
  }

  // ---- scrub ---------------------------------------------------------------------------------------------------------

  @Test fun scrubRemovesPathsAndUris() {
    assertEquals("read <path> now", Redaction.scrub("read /storage/emulated/0/DCIM/a.jpg now"))
    assertEquals("see <uri>", Redaction.scrub("see content://media/external/images/media/9"))
    assertEquals("see <uri>", Redaction.scrub("see file:///sdcard/Download/x.pdf"))
    assertEquals("at <path>", Redaction.scrub("at C:\\Users\\me\\Docs\\x.docx"))
  }

  @Test fun scrubLeavesFramesAndShortSlashesAlone() {
    val frame = "\tat com.craneai.findr.engine.FileProcessor.process(FileProcessor.kt:325)"
    assertEquals(frame, Redaction.scrub(frame))
    assertEquals("on/off and 3/4 done", Redaction.scrub("on/off and 3/4 done"))
    assertEquals("<private>", Redaction.scrub("<private>"))
  }

  // ---- what the system says ------------------------------------------------------------------------------------

  @Test fun exitDescriptionDropsQuotedTextAndPaths() {
    val d = Redaction.exitDescription("input dispatching timed out (Window 'Findr - Secret Budget' not responding) at /data/user/0/x/files/y")
    assertNotNull(d)
    assertFalse(d!!, d.contains("Secret"))
    assertFalse(d, d.contains("/data/user"))
    assertTrue(d, d.contains("input dispatching timed out"))
    assertNull(Redaction.exitDescription("   "))
    assertNull(Redaction.exitDescription(null))
    assertTrue(Redaction.exitDescription("x".repeat(500))!!.length <= 160)
  }

  private val dump = """
    ----- pid 4321 at 2026-10-15 10:00:00 -----
    "Signal Catcher" daemon prio=10 tid=3 Runnable
      native: #00 pc 0000000000012345  /apex/com.android.art/lib64/libart.so (art::Foo+1)

    "main" prio=5 tid=1 Sleeping
      | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72c3 self=0xb4000
      native: #00 pc 000000000009c5f4  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+4) (BuildId: abc123)
      native: #01 pc 0000000000012bb0  /data/app/~~x==/com.craneai.findr-y==/base.apk!libfoo.so (offset 0x1000)
      at java.lang.Thread.sleep(Native method)
      - sleeping on <0x0a1b2c3d> (a java.lang.Object)
      at com.craneai.findr.diag.CrashTestActivity${'$'}onCreate${'$'}1.run(CrashTestActivity.kt:30)
      at android.os.Handler.handleCallback(Handler.java:958)

    "Binder:4321_1" prio=5 tid=7 Native
      at should.not.Appear.here(X.java:1)
  """.trimIndent()

  @Test fun anrMainThreadKeepsFramesAndNothingElse() {
    val main = Redaction.anrMainThread(dump)
    assertNotNull(main)
    assertTrue(main!!, main.startsWith("main thread: Sleeping"))
    assertTrue(main, main.contains("#00 libc.so (__epoll_pwait+4)"))
    assertTrue(main, main.contains("at java.lang.Thread.sleep(Native method)"))
    assertTrue(main, main.contains("CrashTestActivity"))
    assertFalse(main, main.contains("should.not.Appear"))
    assertFalse(main, main.contains("/apex"))
    assertFalse(main, main.contains("sleeping on"))
    assertNull(Redaction.anrMainThread("nothing here"))
  }
}
