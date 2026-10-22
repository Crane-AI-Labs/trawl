package trawl.desktop

import com.craneai.findr.ui.common.formatBytes

/** E21: what "Forget everything" just let go of: how many files Trawl had learned about and how much space that index took. */
data class Forgot(val files: Int, val bytes: Long)

object ForgetNote {
  /** "Forgot 12.4 MB: what Trawl had learned about 3,210 files. ..." in the person's words; nothing in it names a file. */
  fun line(f: Forgot): String =
    if (f.files <= 0 && f.bytes <= 0) t("settings_forgot_nothing")
    else t("settings_forgot", formatBytes(f.bytes), tn("count_files", f.files))
}
