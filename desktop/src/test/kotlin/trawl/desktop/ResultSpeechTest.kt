package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.MatchReason
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import trawl.desktop.shell.fakeFile
import trawl.desktop.ui.ResultSpeech
import trawl.desktop.ui.kindLabel
import trawl.desktop.ui.reasonLabel

/** What a screen reader says for a result (audit row H2): the wording, without a window. */
class ResultSpeechTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private fun row(name: String, kind: FileKind, reason: MatchReason? = null, page: Int? = null, startMs: Long? = null, sensitive: Boolean = false, snippet: String? = null) =
    ResultRow(fakeFile(1, name, kind).let { if (sensitive) it.copy(sensitiveFlags = 1) else it }, snippet, reason, page, strong = true, startMs = startMs)

  @Test fun partsAreJoinedWithAPauseAndBlanksAreDropped() {
    assertEquals("one. two. three", ResultSpeech.join("one", null, "  ", "two.", "three"))
  }

  @Test fun aDocumentRowSaysNameKindWhereAndWhyItMatched() {
    val r = row("invoice.pdf", FileKind.PDF, MatchReason.BOTH, page = 3, snippet = "total due «4417»")
    val said = ResultSpeech.doc(r, "invoice.pdf", "Today  ·  Documents", r.snippet, hidden = false)
    val kind = kindLabel(FileKind.PDF)
    assertEquals("invoice.pdf. $kind. Today  ·  Documents. Page 3, Matched by ${reasonLabel(MatchReason.BOTH)}. Matched text: total due «4417»", said)
  }

  @Test fun aRecordingSaysWhereInItTheMatchIs() {
    val said = ResultSpeech.doc(row("call.m4a", FileKind.AUDIO, startMs = 83_000), "call.m4a", "Today", null, hidden = false)
    assertEquals(true, "at 1:23" in said, said)
  }

  @Test fun aVideoSaysTheMomentAndHowLongItIs() {
    val r = row("clip.mp4", FileKind.VIDEO, startMs = 83_000).let { it.copy(file = it.file.copy(durationMs = 252_000)) }
    val said = ResultSpeech.doc(r, "clip.mp4", "Today", null, hidden = false)
    assertEquals(true, "at 1:23" in said && "Length 4:12" in said, said)
    val noLength = ResultSpeech.doc(row("b.mp4", FileKind.VIDEO), "b.mp4", "Today", null, hidden = false)
    assertFalse("Length" in noLength, noLength)
  }

  @Test fun aHiddenPictureSaysHiddenAndNothingOfItsContent() {
    val r = row("secret.jpg", FileKind.IMAGE, sensitive = true, snippet = "words read from the picture")
    val doc = ResultSpeech.doc(r, "Hidden picture", "Today", r.snippet, hidden = true)
    assertFalse("words read" in doc, doc)
    assertFalse("secret.jpg" in doc, doc)
    val tile = ResultSpeech.tile(r, "Hidden picture", hidden = true)
    assertEquals("Hidden picture. Sensitive", tile)
  }

  @Test fun aPictureTileSaysItsNameAndKind() {
    assertEquals("beach.jpg. ${kindLabel(FileKind.IMAGE)}", ResultSpeech.tile(row("beach.jpg", FileKind.IMAGE), "beach.jpg", hidden = false))
  }
}
