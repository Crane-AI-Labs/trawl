package trawl.desktop

/**
 * A language the window can be shown in. [nativeName] is the language in its own words, which is how the picker lists it (a person
 * who reads only Luganda must still find Luganda). [preview] marks a translation that has not reached the quality bar yet; the
 * picker says so. [tag] is the language code the strings file is named for: `strings_<tag>.properties`.
 */
data class AppLanguage(val tag: String, val nativeName: String, val englishName: String, val preview: Boolean = false)

/** The languages the phone app ships (app/.../ui/language/AppLanguages.kt), in the phone's picker order. English is the base file. */
object Languages {
  val shipped: List<AppLanguage> = listOf(
    AppLanguage("en", "English", "English"),
    AppLanguage("fr", "Français", "French"),
    AppLanguage("pt", "Português", "Portuguese"),
    AppLanguage("ar", "العربية", "Arabic"),
    AppLanguage("sw", "Kiswahili", "Swahili"),
    AppLanguage("lg", "Luganda", "Luganda"),
    AppLanguage("rw", "Ikinyarwanda", "Kinyarwanda"),
    AppLanguage("nyn", "Runyankore", "Runyankole", preview = true),
    AppLanguage("ach", "Leb Acoli", "Acholi", preview = true),
    AppLanguage("am", "አማርኛ", "Amharic"),
    AppLanguage("ha", "Hausa", "Hausa"),
    AppLanguage("yo", "Yorùbá", "Yoruba"),
    AppLanguage("so", "Soomaali", "Somali"),
    AppLanguage("zu", "isiZulu", "isiZulu"),
  )

  fun byTag(tag: String?): AppLanguage? = tag?.let { t -> shipped.firstOrNull { it.tag.equals(t, ignoreCase = true) } }
}
