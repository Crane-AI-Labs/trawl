package com.craneai.findr.diag

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.craneai.findr.core.FindrLog as Log
import java.io.File

/** A FileProvider of its own for the one folder reports are shared from, so the app's other provider is not widened. */
class ReportFileProvider : FileProvider()

/** What an email hand-off consists of, with no Android types so the JVM tests can check it. */
data class EmailPlan(val to: String, val subject: String, val body: String, val attachmentName: String, val mime: String)

/** A WhatsApp hand-off: the wa.me link with the short text, and the file to offer as a second step. */
data class WhatsAppPlan(val url: String, val text: String, val attachmentName: String)

object SendPlan {
  fun email(report: BuiltReport, to: String = SupportContact.EMAIL): EmailPlan = EmailPlan(
    to = to,
    subject = report.subject,
    body = SendPlans.mailBody(report.summary, hasAttachment = true),
    attachmentName = SendPlans.ATTACHMENT_NAME,
    mime = "text/plain",
  )

  /** The address-only fallback for a phone whose mail app takes no attachment: the (clipped) report rides in the body. */
  fun mailtoFallback(report: BuiltReport, to: String = SupportContact.EMAIL): String =
    SendPlans.mailtoUri(to, report.subject, report.text)

  /** Null while no WhatsApp number is configured: the option is not offered at all. */
  fun whatsApp(report: BuiltReport, number: String = SupportContact.WHATSAPP): WhatsAppPlan? {
    val url = SendPlans.whatsAppUrl(number, report.summary) ?: return null
    return WhatsAppPlan(url, SendPlans.whatsAppText(report.summary), SendPlans.ATTACHMENT_NAME)
  }
}

enum class SendResult { STARTED, NO_APP }

/**
 * Hands a report to the person's own apps. Findr never connects: it only starts an email or WhatsApp screen, filled in,
 * and the person presses Send there. Nothing is sent until they do.
 */
object ReportSender {
  const val FOLDER = "shared-reports"
  const val WHATSAPP_PACKAGE = "com.whatsapp"

  fun whatsAppConfigured(): Boolean = SendPlans.isE164(SupportContact.WHATSAPP)

  fun whatsAppInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE) != null }.getOrDefault(false)

  /** Writes the report to the share folder in the app's cache and returns a content:// URI another app may read once. */
  fun shareFile(context: Context, report: BuiltReport): Uri {
    val dir = File(context.cacheDir, FOLDER).apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, SendPlans.ATTACHMENT_NAME)
    file.writeText(report.text)
    return FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
  }

  /** Removes the shared copy (it lives in the cache, but "Delete everything" should not leave a report anywhere). */
  fun cleanup(context: Context) {
    runCatching { File(context.cacheDir, FOLDER).deleteRecursively() }
  }

  /** Email: the summary in the body and the whole report attached. Falls back to a plain mailto: when no app takes an attachment. */
  fun email(context: Context, report: BuiltReport): SendResult {
    val plan = SendPlan.email(report)
    val attempt = runCatching {
      val uri = shareFile(context, report)
      // ACTION_SENDTO with a mailto: address lists only mail apps, and they take the attachment as EXTRA_STREAM. (An
      // ACTION_SEND intent with a mailto selector looked right but made the system chooser say "No apps can perform
      // this action" on the Infinix with Gmail installed, found in the phone check.)
      val send = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${plan.to}")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(plan.to))
        putExtra(Intent.EXTRA_SUBJECT, plan.subject)
        putExtra(Intent.EXTRA_TEXT, plan.body)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(plan.attachmentName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    if (attempt.isSuccess) return SendResult.STARTED
    Log.w("FindrDiag", "email with attachment did not start (${Log.brief(attempt.exceptionOrNull() ?: ActivityNotFoundException())})")
    return runCatching {
      val view = Intent(Intent.ACTION_SENDTO, Uri.parse(SendPlan.mailtoFallback(report))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(view)
      SendResult.STARTED
    }.getOrDefault(SendResult.NO_APP)
  }

  /** WhatsApp, step one: the chat with the short summary typed in. NO_APP while no number is set. */
  fun whatsAppSummary(context: Context, report: BuiltReport): SendResult {
    val plan = SendPlan.whatsApp(report) ?: return SendResult.NO_APP
    return runCatching {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(plan.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      SendResult.STARTED
    }.getOrDefault(SendResult.NO_APP)
  }

  /** WhatsApp, step two: the whole report as a file, straight to WhatsApp, when it is installed. */
  fun whatsAppFile(context: Context, report: BuiltReport): SendResult {
    if (SendPlan.whatsApp(report) == null || !whatsAppInstalled(context)) return SendResult.NO_APP
    return runCatching {
      val uri = shareFile(context, report)
      val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(WHATSAPP_PACKAGE)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(SendPlans.ATTACHMENT_NAME, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(send)
      SendResult.STARTED
    }.getOrDefault(SendResult.NO_APP)
  }
}
