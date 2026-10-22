package trawl.desktop.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import trawl.desktop.t

/** The system's own file dialogs (Swing's chooser follows the look of the desktop). Blocking: call from a click handler. */
object Pickers {
  fun folder(start: File? = null, title: String = t("settings_choose_dialog")): File? {
    val c = JFileChooser(start?.takeIf { it.isDirectory }).apply {
      fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
      dialogTitle = title
      isAcceptAllFileFilterUsed = false
    }
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
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
