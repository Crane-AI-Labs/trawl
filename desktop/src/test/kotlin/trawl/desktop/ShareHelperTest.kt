package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.privacy.HelperRunner

/** B4: Share to another app. The system's own sheet is shown by a small helper per OS; here the helper is stood in for. */
class ShareHelperTest {
  private val dirs = mutableListOf<File>()
  @AfterTest fun clean() { dirs.forEach { it.deleteRecursively() }; Share.facility = Share.forOs(Platform.os) }

  private fun dir() = Files.createTempDirectory("trawl-b4").toFile().also { dirs += it }
  private fun file(d: File, name: String) = File(d, name).also { it.writeText("x") }

  private class Recorder(val answer: String?) : HelperRunner {
    var command: List<String>? = null
    var timeout = 0L
    override fun run(command: List<String>, timeoutMs: Long): String? { this.command = command; timeout = timeoutMs; return answer }
  }

  @Test fun linuxHasNoShareSheetAndItIsNeverOffered() = assertNull(Share.forOs(Os.LINUX))

  @Test fun aMacOffersShareOnlyWhenItsHelperIsInTheBuild() {
    assertNull(Share.forOs(Os.MAC, hasResource = { false }), "a build made off a Mac has no helper: nothing is faked")
    assertNotNull(Share.forOs(Os.MAC, hasResource = { it == "share-apple" }))
  }

  @Test fun windowsAlwaysOffersItAndRunsItsScriptThroughPowerShell() {
    val home = dir(); val r = Recorder("ok")
    val f = Share.forOs(Os.WINDOWS, dataDir = { home }, runner = r, hasResource = { false })
    assertNotNull(f)
    val a = file(home, "a b.txt")
    // the script is part of this build's resources; extracting it needs nothing else
    assertTrue(f.share(listOf(a)))
    val c = r.command!!
    assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File"), c.take(6))
    assertTrue(c[6].endsWith("share-windows.ps1") && File(c[6]).isFile, "the script was put on disk: ${c[6]}")
    assertEquals(listOf("share", a.absolutePath), c.drop(7))
  }

  @Test fun theHelperIsGivenTheRealFilesAndOnlyThose() {
    val d = dir(); val r = Recorder("ok")
    val h = HelperShare({ listOf("/x/helper") }, r)
    assertTrue(h.share(listOf(file(d, "a.txt"), File(d, "gone.txt"), file(d, "b.txt"))))
    assertEquals(listOf("/x/helper", "share", File(d, "a.txt").absolutePath, File(d, "b.txt").absolutePath), r.command)
    assertTrue(r.timeout >= 10 * 60_000L, "the sheet has minutes, not seconds")
  }

  @Test fun noFilesMeansNoSheet() {
    val r = Recorder("ok")
    assertFalse(HelperShare({ listOf("h") }, r).share(listOf(File("/nope/zzz"))))
    assertNull(r.command, "the helper was not even started")
  }

  @Test fun aDismissedSheetIsNotAFailureButAnErrorOrSilenceIs() {
    val d = dir(); val a = file(d, "a.txt")
    assertTrue(HelperShare({ listOf("h") }, Recorder("ok")).share(listOf(a)))
    assertTrue(HelperShare({ listOf("h") }, Recorder("cancelled")).share(listOf(a)))
    assertFalse(HelperShare({ listOf("h") }, Recorder("error:Win32Exception")).share(listOf(a)))
    assertFalse(HelperShare({ listOf("h") }, Recorder(null)).share(listOf(a)), "the helper did not answer")
  }

  @Test fun theWindowsCommandLineStaysUnderItsLimit() {
    val d = dir()
    val files = (1..200).map { file(d, "f".repeat(150) + "$it.txt") }
    val r = Recorder("ok")
    assertTrue(HelperShare({ listOf("h") }, r, argBudget = Share.WINDOWS_ARG_BUDGET).share(files))
    val paths = r.command!!.drop(2)
    assertTrue(paths.isNotEmpty() && paths.size < files.size, "some were left off rather than the whole command failing: ${paths.size}")
    assertTrue(paths.sumOf { it.length + 3 } <= Share.WINDOWS_ARG_BUDGET)
    assertEquals(files.take(paths.size).map { it.absolutePath }, paths, "the first ones, in order")
  }

  @Test fun shareIsFalseOnlyWhenTheHelperFails() {
    val d = dir(); val a = file(d, "a.txt")
    Share.facility = HelperShare({ listOf("h") }, Recorder("error:x"))
    assertFalse(Share.share(listOf(a)))
    Share.facility = HelperShare({ listOf("h") }, Recorder("ok"))
    assertTrue(Share.share(listOf(a)))
  }

  @Test fun theHelpersAreInTheBuild() {
    val cl = Share::class.java.classLoader
    assertNotNull(cl.getResource("share-windows.ps1"), "the Windows script ships in the resources")
    assertNotNull(cl.getResource("share-apple.swift"), "its Swift source ships next to the other helpers")
    val ps = String(cl.getResourceAsStream("share-windows.ps1")!!.readBytes())
    for (needle in listOf("DataTransferManager", "ShowShareUIForWindow", "SetStorageItems", "'cancelled'", "'ok'", "error:nosheet", "GetForegroundWindow", "add_Deactivate")) assertTrue(needle in ps, needle)
    // 1.0.1 matrix, Windows 10: the anchor form deactivates within 0.1 s with no pane (foreground window 0), so an early deactivation is not
    // a pane and "no window in front" counts as no pane.
    for (needle in listOf("[IntPtr]::Zero", "ElapsedMilliseconds -gt 1500")) assertTrue(needle in ps, "nosheet logic: $needle")
  }

  @Test fun aPaneThatNeverCameIsAFailureAndTheMessageSendsThePersonToShowInFolder() {
    val a = file(dir(), "a.txt")
    assertFalse(HelperShare({ listOf("h") }, Recorder("error:nosheet")).share(listOf(a)))
    Strings.use(java.util.Locale.ENGLISH)
    assertEquals("Sharing isn't working on this computer. Use Show in folder instead.", t("share_failed"))
    assertEquals("Show in folder", t("action_show_folder"), "the message names a menu entry that exists")
  }
}
