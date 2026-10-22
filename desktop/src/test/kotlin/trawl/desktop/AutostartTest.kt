package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Start Trawl when I sign in": the entry each system gets, and that switching it off removes exactly what switching it on made. */
class AutostartTest {
  private fun tmp() = Files.createTempDirectory("trawl-autostart").toFile().canonicalFile
  private fun fakeLauncher(dir: File, name: String = "Trawl") = File(dir, name).apply { writeText("#!/bin/sh\n"); setExecutable(true) }

  @Test fun theMacEntryRunsTheLauncherAtSignIn() {
    val p = Autostart.macPlist("/Applications/Trawl.app/Contents/MacOS/Trawl")
    assertTrue("<key>RunAtLoad</key><true/>" in p)
    assertTrue("<string>/Applications/Trawl.app/Contents/MacOS/Trawl</string>" in p)
    assertTrue("<string>${Autostart.LABEL}</string>" in p)
    assertTrue("A&amp;B" in Autostart.macPlist("/Users/a/A&B/Trawl"), "XML characters in a path are escaped")
  }

  @Test fun theLinuxEntryQuotesAPathWithSpaces() {
    val plain = Autostart.linuxDesktopEntry("/opt/trawl/bin/trawl")
    assertTrue("Exec=/opt/trawl/bin/trawl\n" in plain)
    assertTrue("Type=Application" in plain && "Terminal=false" in plain)
    assertTrue("Exec=\"/home/a b/trawl\"\n" in Autostart.linuxDesktopEntry("/home/a b/trawl"))
  }

  @Test fun theWindowsCommandsTouchOnlyTheCurrentUsersRunKey() {
    val add = Autostart.windowsAdd("C:\\Users\\a\\AppData\\Local\\Trawl\\Trawl.exe")
    assertEquals(listOf("reg", "add"), add.take(2))
    assertEquals("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", add[2])
    assertEquals("\"C:\\Users\\a\\AppData\\Local\\Trawl\\Trawl.exe\"", add[add.indexOf("/d") + 1], "quoted, so a folder with spaces still starts")
    assertEquals(listOf("reg", "delete"), Autostart.windowsRemove().take(2))
    assertTrue(Autostart.windowsRemove().none { it.startsWith("HKLM") })
  }

  @Test fun entriesGoInThePersonsOwnFolders() {
    val home = File("/home/ann")
    assertEquals(File("/home/ann/Library/LaunchAgents/${Autostart.LABEL}.plist"), Autostart.entryFile(Os.MAC, home, emptyMap()))
    assertEquals(File("/home/ann/.config/autostart/trawl.desktop"), Autostart.entryFile(Os.LINUX, home, emptyMap()))
    assertEquals(File("/x/cfg/autostart/trawl.desktop"), Autostart.entryFile(Os.LINUX, home, mapOf("XDG_CONFIG_HOME" to "/x/cfg")))
    assertNull(Autostart.entryFile(Os.WINDOWS, home, emptyMap()))
  }

  @Test fun onThenOffOnLinuxAndMac() {
    for (os in listOf(Os.LINUX, Os.MAC)) {
      val home = tmp(); val l = fakeLauncher(home)
      assertFalse(Autostart.isOn(os, home, emptyMap()), "off by default")
      assertTrue(Autostart.apply(true, l, os, home, emptyMap()))
      assertTrue(Autostart.isOn(os, home, emptyMap()))
      val entry = assertNotNull(Autostart.entryFile(os, home, emptyMap()))
      assertTrue(l.absolutePath in entry.readText(), "$os: the entry names the launcher")
      assertTrue(entry.parentFile.listFiles().none { it.name.endsWith(".part") }, "written atomically, no temp file left")
      assertTrue(Autostart.apply(false, l, os, home, emptyMap()))
      assertFalse(Autostart.isOn(os, home, emptyMap()))
      assertTrue(Autostart.apply(false, l, os, home, emptyMap()), "switching off when already off is fine")
      home.deleteRecursively()
    }
  }

  @Test fun windowsRunsTheCommandsAndReadsTheKeyBack() {
    val ran = mutableListOf<List<String>>()
    var present = false
    val run: (List<String>) -> Int = { cmd ->
      ran += cmd
      when (cmd[1]) { "add" -> { present = true; 0 }; "delete" -> { present = false; 0 }; else -> if (present) 0 else 1 }
    }
    val home = tmp(); val l = fakeLauncher(home, "Trawl.exe")
    assertFalse(Autostart.isOn(Os.WINDOWS, home, emptyMap(), run))
    assertTrue(Autostart.apply(true, l, Os.WINDOWS, home, emptyMap(), run))
    assertTrue(Autostart.isOn(Os.WINDOWS, home, emptyMap(), run))
    assertTrue(Autostart.apply(false, l, Os.WINDOWS, home, emptyMap(), run))
    assertFalse(Autostart.isOn(Os.WINDOWS, home, emptyMap(), run))
    home.deleteRecursively()
  }

  @Test fun aFailedCommandIsReportedNotHidden() {
    val home = tmp(); val l = fakeLauncher(home, "Trawl.exe")
    assertFalse(Autostart.apply(true, l, Os.WINDOWS, home, emptyMap()) { 1 })
    assertFalse(Autostart.apply(true, null, Os.LINUX, home, emptyMap()), "no launcher, nothing to register")
    home.deleteRecursively()
  }

  @Test fun aPlainJavaRunHasNoLauncherToRegister() {
    val home = tmp()
    assertNull(Autostart.launcher("/usr/lib/jvm/java-21/bin/java"))
    assertNull(Autostart.launcher("C:\\jdk\\bin\\javaw.exe"))
    assertNull(Autostart.launcher(null))
    assertNull(Autostart.launcher(File(home, "missing").path), "a path that does not exist")
    val l = fakeLauncher(home)
    assertEquals(l, Autostart.launcher(l.path))
    home.deleteRecursively()
  }
}
