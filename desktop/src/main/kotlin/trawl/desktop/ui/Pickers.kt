package trawl.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import trawl.desktop.Os
import trawl.desktop.Platform
import trawl.desktop.t

/** The system's own file dialogs (native FileDialog on macOS, Swing chooser elsewhere). Blocking: call from a click handler. */
object Pickers {
  fun folder(start: File? = null, title: String = t("settings_choose_dialog")): File? {
    val c = JFileChooser(start?.takeIf { it.isDirectory }).apply {
      fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
      dialogTitle = title
      isAcceptAllFileFilterUsed = false
    }
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
  }

  /** Multi-select file picker (native NSOpenPanel via FileDialog on macOS; JFileChooser elsewhere). Returns real files only. */
  fun files(start: File? = null, title: String = t("settings_choose_files_dialog")): List<File> {
    if (Platform.os == Os.MAC) {
      val picked = runCatching {
        val dlg = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
          isMultipleMode = true
          start?.takeIf { it.isDirectory }?.let { directory = it.absolutePath }
        }
        try {
          dlg.isVisible = true
          dlg.files?.filter { it.isFile } ?: emptyList()
        } finally {
          dlg.dispose()
        }
      }.getOrNull()
      if (picked != null) return picked
    }
    val c = JFileChooser(start?.takeIf { it.isDirectory }).apply {
      fileSelectionMode = JFileChooser.FILES_ONLY
      isMultiSelectionEnabled = true
      dialogTitle = title
    }
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
      c.selectedFiles.orEmpty().filter { it.isFile }
    } else {
      emptyList()
    }
  }

  fun modelsZip(start: File? = null): File? {
    val c = JFileChooser(start?.takeIf { it.isDirectory }).apply {
      fileSelectionMode = JFileChooser.FILES_ONLY
      dialogTitle = t("settings_choose_models_dialog")
      fileFilter = FileNameExtensionFilter("trawl-models (.zip)", "zip")
    }
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
  }
}
