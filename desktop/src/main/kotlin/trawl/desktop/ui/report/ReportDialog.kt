package trawl.desktop.ui.report

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import trawl.desktop.ProblemReport
import trawl.desktop.ProblemReports
import trawl.desktop.Reporter
import trawl.desktop.t
import trawl.desktop.ui.Notice
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunButton
import trawl.desktop.ui.SunOutlinedButton
import trawl.desktop.ui.SunTextButton
import trawl.desktop.ui.SunType

/**
 * "Report a problem": the whole report on screen to read, an optional note, and two ways out: copy it or save it as a file.
 * Nothing is sent. This is also where the old "Copy diagnostics" lives now: the report starts with the same diagnostics text.
 */
@Composable
fun ReportDialog(reporter: Reporter, onClose: () -> Unit, openLogFolder: () -> Unit = {}, openMail: (String) -> Boolean = trawl.desktop.Platform::openUri, copy: (String) -> Boolean = ::copyToClipboard, build: suspend (String) -> ProblemReport = { n -> withContext(Dispatchers.IO) { reporter.build(n) } }) {
  var note by remember { mutableStateOf("") }
  var report by remember { mutableStateOf<ProblemReport?>(null) }
  var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
  val scope = rememberCoroutineScope()

  // Built off the UI thread (it reads the report files), and again whenever the note changes.
  androidx.compose.runtime.LaunchedEffect(note) {
    report = build(note)
  }

  AlertDialog(
    onDismissRequest = onClose,
    containerColor = MaterialTheme.colorScheme.background,
    modifier = Modifier.widthIn(max = 720.dp),
    title = { Text(t("report_title"), style = SunType.Heading) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
        Text(t("report_hint"), style = SunType.Body)
        OutlinedTextField(
          value = note, onValueChange = { note = it.take(ProblemReports.NOTE_MAX) }, label = { Text(t("report_note_label")) },
          minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        Text(t("report_note_hint"), style = SunType.Caption)
        SelectionContainer {
          Text(
            report?.text ?: t("addons_adding"), style = SunType.Caption,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).border(Sun.Border, MaterialTheme.colorScheme.outline).verticalScroll(rememberScrollState()).padding(Sun.S8),
          )
        }
        Text(t("report_send_to", ProblemReports.EMAIL), style = SunType.Caption)
        Text(t("report_kept"), style = SunType.Caption)
        result?.let { (text, good) -> Notice(text, bad = !good) }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
        SunOutlinedButton(onClick = openLogFolder) { Text(t("report_logs")) }
        // F10: copies the whole report first (the link can only carry the short summary), then opens the person's own mail app.
        SunOutlinedButton(onClick = {
          val r = report ?: return@SunOutlinedButton
          val copied = copy(r.text)
          scope.launch {
            val opened = withContext(Dispatchers.IO) { openMail(ProblemReports.mailUri(r.summary)) }
            result = when {
              opened && copied -> t("report_mail_opened") to true
              opened -> t("report_mail_opened_not_copied") to false
              else -> t("report_mail_failed", ProblemReports.EMAIL) to false
            }
          }
        }, enabled = report != null) { Text(t("report_mail")) }
        SunOutlinedButton(onClick = {
          val r = report ?: return@SunOutlinedButton
          scope.launch {
            val chosen = withContext(Dispatchers.IO) { chooseSaveFile() } ?: return@launch
            val ok = withContext(Dispatchers.IO) { reporter.save(r, chosen) }
            result = (if (ok) t("report_saved") else t("report_save_failed")) to ok
          }
        }, enabled = report != null) { Text(t("report_save")) }
        SunButton(onClick = {
          val r = report ?: return@SunButton
          val ok = copy(r.text)
          result = (if (ok) t("report_copied") else t("report_copy_failed")) to ok
        }, enabled = report != null) { Text(t("report_copy")) }
      }
    },
    dismissButton = { SunTextButton(onClick = onClose) { Text(t("report_close")) } },
  )
}

/** The text on the system clipboard. False where there is no clipboard to reach (a computer with no screen). */
fun copyToClipboard(text: String): Boolean = runCatching {
  java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
}.isSuccess

/** The system's Save dialog, suggesting a dated name in Downloads. Null when cancelled. Blocking. */
private fun chooseSaveFile(): File? {
  val dialog = FileDialog(null as Frame?, t("report_save"), FileDialog.SAVE).apply {
    file = ProblemReports.fileName()
    directory = trawl.desktop.Platform.userFolders().downloads.takeIf { it.isDirectory }?.absolutePath
    isVisible = true
  }
  val name = dialog.file ?: return null
  return File(dialog.directory ?: return null, name)
}
