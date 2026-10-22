package trawl.desktop.ui

import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.common.formatDuration
import trawl.desktop.ResultRow
import trawl.desktop.t

/**
 * What a screen reader says for one result (audit row H2). A sighted person reads a row as a picture: the icon tells the kind, the badge row
 * the page and why it matched, the highlight the match. None of that is text, so each of those becomes a short sentence here, joined with
 * full stops so the reader pauses between them. A hidden (blurred) picture says "hidden" and nothing of its name, exactly as it looks.
 *
 * Pure strings in, one string out, so the wording is tested without a window.
 */
object ResultSpeech {
  /** Joins the non-blank [parts] into one spoken run, a pause between each. */
  fun join(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(". ") { it!!.trim().trimEnd('.') }

  /** The badges as words: the kind where it is not obvious, "sensitive", the page, the time in a recording, and why it matched. */
  fun badges(row: ResultRow): List<String> = buildList {
    if (row.file.kind == FileKind.PDF || row.file.kind == FileKind.VIDEO || row.file.kind == FileKind.AUDIO) add(kindLabel(row.file.kind))
    if (row.file.sensitive) add(t("sensitive_badge"))
    row.page?.let { add(t("speak_page", it)) }   // "p. 3" read aloud is a letter and a number
    row.startMs?.let { add(t("badge_at_time", formatDuration(it))) }
    // A16: a video or recording also says how long it is, read as a sentence ("4:12" alone is just digits).
    if (row.file.kind == FileKind.VIDEO || row.file.kind == FileKind.AUDIO) row.file.durationMs?.takeIf { it > 0 }?.let { add(t("speak_length", formatDuration(it))) }
    reasonLabel(row.reason)?.let { add(t("speak_match_reason", it)) }
  }

  /** A document, PDF, recording or video row: name, kind, age and folder, the badges, and the matched text. */
  fun doc(row: ResultRow, name: String, subtitle: String, snippet: String?, hidden: Boolean): String {
    val kind = kindLabel(row.file.kind)
    val extra = badges(row).filter { it != kind }
    return join(name, kind, subtitle, extra.joinToString(", "), if (hidden || snippet == null) null else t("speak_matched_text", snippet))
  }

  /** A picture tile: its name (or "hidden"), what kind of picture, and the sensitive mark. */
  fun tile(row: ResultRow, name: String, hidden: Boolean): String =
    join(name, if (hidden) null else kindLabel(row.file.kind), if (row.file.sensitive) t("sensitive_badge") else null)
}
