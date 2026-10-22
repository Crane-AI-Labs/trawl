package trawl.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File

/** What "Copy" managed: the files themselves (paste into a mail, a chat, a folder), only their paths as text, or nothing. */
enum class CopyOutcome { FILES, PATHS, FAILED }

/** The two things the clipboard is asked to hold; a fake in tests, the AWT clipboard in the app. Each returns false rather than throwing. */
interface ClipboardPort {
  fun putFiles(files: List<File>): Boolean
  fun putText(text: String): Boolean
}

/**
 * Copy for a result (B3). The files go on the clipboard as files, in the form each system's file manager and chat apps paste from; where
 * that is not possible (no desktop, a clipboard that refuses a file list, a file that has gone) the paths go on as text, and the caller
 * says so: Copy never fails silently and never pretends a path was a file.
 */
object Clip {
  /** The most files one Copy takes, so a "select all" of a big list stays sane (the phone's cap is the same). */
  const val LIMIT = 200

  fun copyFiles(files: List<File>, port: ClipboardPort = AwtClipboard, os: Os = Platform.os): CopyOutcome {
    val some = files.take(LIMIT)
    if (some.isEmpty()) return CopyOutcome.FAILED
    // A file that is no longer there cannot be pasted as a file; the paths still say where it was.
    if (some.all { it.exists() } && runCatching { port.putFiles(some) }.getOrDefault(false)) return CopyOutcome.FILES
    return if (runCatching { port.putText(pathsText(some)) }.getOrDefault(false)) CopyOutcome.PATHS else CopyOutcome.FAILED
  }

  fun copyText(text: String, port: ClipboardPort = AwtClipboard): Boolean = runCatching { port.putText(text) }.getOrDefault(false)

  /** One path per line, as the person would paste them. */
  fun pathsText(files: List<File>): String = files.joinToString("\n") { it.path }

  /** The text the GNOME and Cinnamon file managers (and the apps that follow them) paste a copied file from: the word "copy", then one URI per line. */
  fun gnomeCopiedText(files: List<File>): String = "copy\n" + files.joinToString("\n") { "file://" + it.toURI().rawPath }
}

/** The system clipboard through AWT. Files go as a Java file list, which AWT hands to the system in its own form (CF_HDROP, a file URL on the pasteboard, text/uri-list). */
object AwtClipboard : ClipboardPort {
  /** The flavor GNOME's file manager looks for, mapped to its own target name so X11 offers it as `x-special/gnome-copied-files`. */
  val gnomeFlavor: DataFlavor by lazy {
    DataFlavor("x-special/gnome-copied-files;class=java.io.InputStream").also {
      runCatching { (SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap).addUnencodedNativeForFlavor(it, "x-special/gnome-copied-files") }
    }
  }

  private class Files(val files: List<File>, val withGnome: Boolean) : Transferable {
    private val flavors: Array<DataFlavor> = buildList {
      add(DataFlavor.javaFileListFlavor)
      if (withGnome) add(gnomeFlavor)
      add(DataFlavor.stringFlavor)
    }.toTypedArray()

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors
    override fun isDataFlavorSupported(f: DataFlavor): Boolean = flavors.any { it.equals(f) }
    override fun getTransferData(f: DataFlavor): Any = when {
      f.equals(DataFlavor.javaFileListFlavor) -> files
      withGnome && f.equals(gnomeFlavor) -> ByteArrayInputStream(Clip.gnomeCopiedText(files).toByteArray(Charsets.UTF_8))
      f.equals(DataFlavor.stringFlavor) -> Clip.pathsText(files)
      else -> throw UnsupportedFlavorException(f)
    }
  }

  override fun putFiles(files: List<File>): Boolean {
    if (java.awt.GraphicsEnvironment.isHeadless()) return false
    val linux = Platform.os == Os.LINUX
    Toolkit.getDefaultToolkit().systemClipboard.setContents(Files(files, withGnome = linux), null)
    return true
  }

  override fun putText(text: String): Boolean {
    if (java.awt.GraphicsEnvironment.isHeadless()) return false
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    return true
  }
}
