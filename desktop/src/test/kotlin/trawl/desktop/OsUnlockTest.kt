package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import trawl.desktop.privacy.HelperRunner
import trawl.desktop.privacy.MacTouchId
import trawl.desktop.privacy.NoOsUnlock
import trawl.desktop.privacy.OsAvailability
import trawl.desktop.privacy.OsOutcome
import trawl.desktop.privacy.OsPrompt
import trawl.desktop.privacy.OsUnlocks
import trawl.desktop.privacy.WindowsHello

/** The one-line protocol of the native helpers, read through a fake runner (no helper, and no prompt, is ever run here). */
class OsUnlockTest {
  private fun runner(answer: String?, seen: MutableList<List<String>> = ArrayList()) = HelperRunner { cmd, _ -> seen += cmd; answer }
  private val mac = File("/x/touchid-apple")
  private val ps1 = File("/x/hello-windows.ps1")

  @Test fun `touch id check reads biometry and password-only Macs`() {
    assertEquals(OsAvailability.Available(OsPrompt.TOUCH_ID), MacTouchId(mac, runner("available:biometry")).availability())
    assertEquals(OsAvailability.Available(OsPrompt.MAC_PASSWORD), MacTouchId(mac, runner("available:password")).availability())
    assertIs<OsAvailability.Unavailable>(MacTouchId(mac, runner("unavailable:-1")).availability())
    assertIs<OsAvailability.Unavailable>(MacTouchId(mac, runner(null)).availability())
    assertIs<OsAvailability.Unavailable>(MacTouchId(mac, runner("available:something new")).availability())
  }

  @Test fun `touch id check never asks for a prompt, auth passes the reason`() {
    val seen = ArrayList<List<String>>()
    MacTouchId(mac, runner("available:biometry", seen)).availability()
    assertEquals(listOf("/x/touchid-apple", "check"), seen.single())
    seen.clear()
    MacTouchId(mac, runner("ok", seen)).authenticate("Unlock Trawl")
    assertEquals(listOf("/x/touchid-apple", "auth", "Unlock Trawl"), seen.single())
  }

  @Test fun `outcomes map one to one and anything odd is an error, never a yes`() {
    val answers = mapOf(
      "ok" to OsOutcome.OK, "denied" to OsOutcome.DENIED, "cancelled" to OsOutcome.CANCELLED, "unavailable:x" to OsOutcome.UNAVAILABLE,
      "error:5" to OsOutcome.ERROR, "OK" to OsOutcome.ERROR, "okay" to OsOutcome.ERROR, "" to OsOutcome.ERROR,
    )
    for ((line, want) in answers) assertEquals(want, MacTouchId(mac, runner(line)).authenticate("r"), "for '$line'")
    assertEquals(OsOutcome.ERROR, MacTouchId(mac, runner(null)).authenticate("r"), "a helper that did not answer is not a yes")
  }

  @Test fun `windows hello is run through powershell with the script and the verb`() {
    val seen = ArrayList<List<String>>()
    assertEquals(OsAvailability.Available(OsPrompt.WINDOWS_HELLO), WindowsHello(ps1, runner("available", seen)).availability())
    assertEquals("powershell.exe", seen.single().first())
    assertTrue("-File" in seen.single() && "/x/hello-windows.ps1" in seen.single() && seen.single().last() == "check")
    assertEquals(OsAvailability.Unavailable("DeviceNotPresent"), WindowsHello(ps1, runner("unavailable:DeviceNotPresent")).availability())
    assertEquals(OsOutcome.OK, WindowsHello(ps1, runner("ok")).authenticate("Unlock Trawl"))
  }

  @Test fun `an unavailable reason is kept short`() {
    val long = "unavailable:" + "x".repeat(500)
    assertEquals(60, (MacTouchId(mac, runner(long)).availability() as OsAvailability.Unavailable).why.length)
  }

  @Test fun `linux has no system prompt and says so`() {
    val os = OsUnlocks.choose(Files.createTempDirectory("helpers").toFile(), Os.LINUX)
    assertIs<NoOsUnlock>(os)
    assertIs<OsAvailability.Unavailable>(os.availability())
    assertEquals(OsOutcome.UNAVAILABLE, os.authenticate("r"))
  }

  @Test fun `windows extracts its script next to the index and uses it`() {
    val dir = Files.createTempDirectory("helpers").toFile()
    val os = OsUnlocks.choose(dir, Os.WINDOWS, runner("available"))
    assertIs<WindowsHello>(os)
    val script = File(dir, "helpers/hello-windows.ps1")
    assertTrue(script.isFile && script.length() > 100)
    assertTrue(script.readText().contains("UserConsentVerifier"))
  }
}
