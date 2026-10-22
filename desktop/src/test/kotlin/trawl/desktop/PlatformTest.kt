package trawl.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformTest {
  @Test fun detectsTheOs() {
    assertEquals(Os.WINDOWS, Platform.detect("Windows Server 2022"))
    assertEquals(Os.WINDOWS, Platform.detect("Windows 11"))
    assertEquals(Os.MAC, Platform.detect("Mac OS X"))
    assertEquals(Os.LINUX, Platform.detect("Linux"))
  }

  @Test fun memoryParsersReadEachOsOutput() {
    assertEquals(15932L, Platform.parseMeminfoMb("MemTotal:       16314716 kB\nMemFree:         1000 kB\n"))
    assertEquals(0L, Platform.parseMeminfoMb("MemFree: 1000 kB"))
    assertEquals(0L, Platform.parseMeminfoMb(""))
    assertEquals(16384L, Platform.parseBytesMb("17179869184\n"))             // sysctl -n hw.memsize
    assertEquals(8191L, Platform.parseBytesMb("8589410304\r\n"))              // PowerShell on Windows
    for (bad in listOf(null, "", "unknown", "Get-CimInstance : error", "-5")) assertEquals(0L, Platform.parseBytesMb(bad), "'$bad'")
  }

  /** This machine's own answer, on whichever OS runs the tests: a real size, from the bean or the OS fallback. */
  @Test fun thisMachineReportsItsMemory() {
    assertTrue(Platform.readTotalRamMb(Platform.os) > 256, "got ${Platform.readTotalRamMb(Platform.os)} MB")
    assertEquals(Platform.totalRamMb(), Platform.totalRamMb())
  }

  @Test fun theInstallerRuntimeIncludesTheModuleThatHoldsTheMemoryBean() {
    val gradle = File("build.gradle.kts").readText()
    val modules = Regex("""modules\(([^)]*)\)""").find(gradle)?.groupValues?.get(1) ?: ""
    assertTrue("\"jdk.management\"" in modules, "the jlinked runtime lacks com.sun.management, so total RAM read 0: $modules")
  }

  @Test fun dataDirPerOsAndOverride() {
    assertEquals(File("C:\\Users\\a\\AppData\\Local", "Trawl"), Platform.dataDir(Os.WINDOWS, mapOf("LOCALAPPDATA" to "C:\\Users\\a\\AppData\\Local"), "C:\\Users\\a"))
    assertEquals(File("/h/.local/share", "trawl"), Platform.dataDir(Os.LINUX, emptyMap(), "/h"))
    assertEquals(File("/x/data", "trawl"), Platform.dataDir(Os.LINUX, mapOf("XDG_DATA_HOME" to "/x/data"), "/h"))
    assertEquals(File("/h/Library/Application Support/Trawl"), Platform.dataDir(Os.MAC, emptyMap(), "/h"))
    assertEquals(File("/tmp/t"), Platform.dataDir(Os.LINUX, mapOf("TRAWL_HOME" to "/tmp/t"), "/h"))
  }

  @Test fun linuxUserDirsAreHonoured() {
    val dirs = "XDG_PICTURES_DIR=\"\$HOME/Bilder\"\nXDG_DOCUMENTS_DIR=\"\$HOME/Dokumente\"\nXDG_DOWNLOAD_DIR=\"\$HOME\"\n"
    val f = Platform.userFolders(Os.LINUX, File("/h"), dirs)
    assertEquals(File("/h/Bilder"), f.pictures)
    assertEquals(File("/h/Dokumente"), f.documents)
    assertEquals(File("/h/Downloads"), f.downloads)   // "$HOME" itself means "unset"
    assertEquals(File("/h/Pictures"), Platform.userFolders(Os.WINDOWS, File("/h"), null).pictures)
  }

  @Test fun videosAndMusicAreAmongTheFirstRunFolders() {
    val dirs = "XDG_VIDEOS_DIR=\"\$HOME/Filme\"\nXDG_MUSIC_DIR=\"\$HOME/Musik\"\n"
    val de = Platform.userFolders(Os.LINUX, File("/h"), dirs)
    assertEquals(File("/h/Filme"), de.videos)
    assertEquals(File("/h/Musik"), de.music)
    val plain = Platform.userFolders(Os.LINUX, File("/h"), null)
    assertEquals(listOf("Pictures", "Documents", "Downloads", "Videos", "Music").map { File("/h", it) }, plain.all)
    assertEquals(File("/h/Movies"), Platform.userFolders(Os.MAC, File("/h"), null).videos)
    assertEquals(File("/h/Videos"), Platform.userFolders(Os.WINDOWS, File("/h"), null).videos)
    assertEquals(3, Platform.UserFolders(File("a"), File("b"), File("c")).all.size, "a list built with three folders still has three")
  }

  @Test fun theQuickCommandIsTheLauncherThatReallyExists() {
    assertEquals("/opt/trawl/bin/Trawl --quick", Platform.quickCommand("/opt/trawl/bin/Trawl", "/opt/trawl/lib/runtime/bin/java"))
    assertEquals("/usr/local/trawl/bin/Trawl --quick", Platform.quickCommand(null, "/usr/local/trawl/bin/Trawl"))
    assertEquals("/opt/trawl/bin/Trawl --quick", Platform.quickCommand(null, "/usr/lib/jvm/java-21/bin/java"), "a development run is told the installed place")
    assertEquals("/opt/trawl/bin/Trawl --quick", Platform.quickCommand("", null))
  }

  @Test fun openAndShowCommands() {
    assertEquals(listOf("xdg-open", "/a b/c.pdf"), Platform.openCommand("/a b/c.pdf", Os.LINUX))
    assertEquals(listOf("open", "/a"), Platform.openCommand("/a", Os.MAC))
    assertEquals(listOf("rundll32.exe", "url.dll,FileProtocolHandler", "C:\\a.pdf"), Platform.openCommand("C:\\a.pdf", Os.WINDOWS))
    assertEquals(listOf("explorer.exe", "/select,C:\\a.pdf"), Platform.showCommand("C:\\a.pdf", Os.WINDOWS))
    assertEquals(listOf("open", "-R", "/a"), Platform.showCommand("/a", Os.MAC))
    if (File.separatorChar == '/') {   // the Linux command is built from a Linux path; it is never run on Windows
      val linux = Platform.showCommand("/a b/c.pdf", Os.LINUX)
      assertEquals("array:string:file:///a%20b/c.pdf", linux.first { it.startsWith("array:string:") })
      assertEquals(listOf("xdg-open", "/a b"), Platform.showFallbackCommand("/a b/c.pdf", Os.LINUX))
    }
  }
}
