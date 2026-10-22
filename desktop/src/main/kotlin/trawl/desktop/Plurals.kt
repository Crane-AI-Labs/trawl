package trawl.desktop

/**
 * The plural category of a whole number, per language, from the CLDR rules for the languages the app ships. A string that depends on a
 * count has one key per category (`key_one`, `key_other`, and for a language that needs them `key_zero`, `key_two`, `key_few`,
 * `key_many`); `other` is the one every language must have. A language this table does not know uses English's rule, so a translator
 * who needs more forms adds the language here and the extra keys to the language's file; no Kotlin elsewhere changes.
 */
object Plurals {
  fun category(language: String, n: Long): String {
    val a = Math.abs(n)
    return when (language) {
      "yo" -> "other"                                                        // Yoruba has one form
      "fr", "pt", "am", "zu" -> if (a == 0L || a == 1L) "one" else "other"   // 0 and 1 share the singular
      "ar" -> when {
        a == 0L -> "zero"
        a == 1L -> "one"
        a == 2L -> "two"
        a % 100 in 3..10 -> "few"
        a % 100 in 11..99 -> "many"
        else -> "other"
      }
      // English, Luganda, Swahili, Hausa, Somali, Kinyarwanda, Runyankore and Acholi: exactly 1 is singular.
      else -> if (a == 1L) "one" else "other"
    }
  }

  /** The English noun form for [n], for text that is always English (the diagnostics report). */
  fun english(n: Long, one: String, other: String): String = if (category("en", n) == "one") one else other
}
