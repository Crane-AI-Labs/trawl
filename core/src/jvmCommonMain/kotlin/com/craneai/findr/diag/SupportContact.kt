package com.craneai.findr.diag

import java.net.URLEncoder

/**
 * Where a problem report goes. Trawl has no internet permission and never connects: the person's own email app (and,
 * later, WhatsApp) sends it, after they have read it. These two constants are the only addresses in the app.
 */
object SupportContact {
  /** The mailbox that reads problem reports (Crane AI Labs' public support address). */
  const val EMAIL = "info@craneailabs.com"

  /**
   * The WhatsApp number reports go to, in E.164 with the leading plus ("+256..."). Empty for now, and while it is empty
   * the "Send on WhatsApp" option is not shown at all.
   */
  const val WHATSAPP = ""
}

/**
 * The text of each hand-off, as pure functions the JVM tests can check. `ReportSender` turns these into intents.
 * Email takes a summary in the body and the whole report as an attachment; a long body is cut, never the file.
 */
object SendPlans {
  /** What fits in a mailto: body on the weakest mail apps; the full report rides as the attachment. */
  const val MAIL_BODY_MAX = 1_800
  /** A wa.me link that fits in every browser and in WhatsApp's own limit with room to spare. */
  const val WHATSAPP_TEXT_MAX = 600
  const val ATTACHMENT_NAME = "trawl-report.txt"

  fun subject(appVersion: String, deviceModel: String): String = "Trawl problem report ($appVersion, $deviceModel)"

  /** The email body: [summary] first, then (with an attachment) a line saying the file holds the rest. */
  fun mailBody(summary: String, hasAttachment: Boolean): String {
    val tail = if (hasAttachment) "\n\nThe full report is attached ($ATTACHMENT_NAME). It holds no file names, searches or text from files." else ""
    return clip(summary, MAIL_BODY_MAX - tail.length) + tail
  }

  /** `mailto:` link with the subject and body percent-encoded. Used when no email app takes an attachment. */
  fun mailtoUri(to: String, subject: String, body: String): String =
    "mailto:$to?subject=${pct(subject)}&body=${pct(clip(body, MAIL_BODY_MAX))}"

  /** The short text for a WhatsApp message. */
  fun whatsAppText(summary: String): String = clip(summary, WHATSAPP_TEXT_MAX)

  /** `https://wa.me/<digits>?text=...`, or null while [number] is empty or not E.164. */
  fun whatsAppUrl(number: String, text: String): String? {
    if (!isE164(number)) return null
    return "https://wa.me/${number.removePrefix("+")}?text=${pct(whatsAppText(text))}"
  }

  /** "+" and 8 to 15 digits, not starting with 0. */
  fun isE164(number: String): Boolean = Regex("""\+[1-9][0-9]{7,14}""").matches(number)

  /** Cuts [s] to [max] characters at a line end where it can, and says so. */
  fun clip(s: String, max: Int): String {
    if (s.length <= max) return s
    val note = "\n[cut: the attached report has the rest]"
    val keep = (max - note.length).coerceAtLeast(0)
    val cut = s.take(keep)
    val atLine = cut.lastIndexOf('\n').takeIf { it > keep / 2 } ?: cut.length
    return cut.take(atLine) + note
  }

  /** Percent-encoding for a URI query value (spaces as %20, never +). */
  private fun pct(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
