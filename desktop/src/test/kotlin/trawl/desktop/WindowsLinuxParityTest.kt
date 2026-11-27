package trawl.desktop

import java.awt.Canvas
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import trawl.desktop.shell.MacNative
import trawl.desktop.ui.FileListTransferable

class WindowsLinuxParityTest {
  @Test fun osNotifierBuildsSafeArgvCommandsForMacAndLinuxAndDefersWindowsToTrayToast() {
    val mac = OsNotifier.notifyCommand(Os.MAC, "Large folder", "2,400 files in dump")
    assertNotNull(mac)
    assertEquals("osascript", mac.first())
    assertTrue("--" in mac, "macOS osascript must separate argv with -- to prevent injection")
    assertEquals(listOf("2,400 files in dump", "Large folder"), mac.takeLast(2))

    val linux = OsNotifier.notifyCommand(Os.LINUX, "--weird-title", "2,400 files in dump")
    assertNotNull(linux)
    assertEquals(listOf("notify-send", "--app-name=Trawl", "--", "--weird-title", "2,400 files in dump"), linux)

    // Windows returns null so Shell falls back to Compose Desktop native TrayState Windows Toast (zero PowerShell flash)
    assertNull(OsNotifier.notifyCommand(Os.WINDOWS, "Large folder", "2,400 files"))
  }

  @Test fun fileDragTransferableOmitsStringFlavorOnWindowsAndMacButKeepsItOnLinux() {
    val files = listOf(File("/tmp/report.pdf"), File("/tmp/photo.png"))

    val win = FileListTransferable(files, os = Os.WINDOWS)
    assertTrue(win.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
    assertFalse(
      win.isDataFlavorSupported(DataFlavor.stringFlavor),
      "Windows drag must omit stringFlavor so WhatsApp/Slack/Chrome attach the file via CF_HDROP instead of pasting path text",
    )
    assertEquals(files, win.getTransferData(DataFlavor.javaFileListFlavor))

    val mac = FileListTransferable(files, os = Os.MAC)
    assertTrue(mac.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
    assertFalse(mac.isDataFlavorSupported(DataFlavor.stringFlavor))

    val linux = FileListTransferable(files, os = Os.LINUX)
    assertTrue(linux.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
    assertTrue(linux.isDataFlavorSupported(DataFlavor.stringFlavor))
    assertEquals(files.joinToString("\n") { it.absolutePath }, linux.getTransferData(DataFlavor.stringFlavor))
  }

  @Test fun findComposeCanvasInLocatesNestedSkikoCanvasAcrossAllPlatforms() {
    val root = Container()
    val panel = JPanel()
    val canvas = Canvas().apply {
      isVisible = true
      isFocusable = true
      isEnabled = true
    }
    panel.add(canvas)
    root.add(panel)

    assertSame(canvas, MacNative.findComposeCanvasIn(root))
  }
}
