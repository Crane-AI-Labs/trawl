package trawl.desktop

import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties

/**
 * Every word the window shows comes from here, by key, from `trawl/strings*.properties` on the classpath. English is the base
 * file; a translation is one more file named for the language (`strings_lg.properties`), looked up first, with English filling
 * any key it lacks. Nothing in the UI code holds a sentence, so a translator never opens Kotlin. Placeholders are the phone's
 * (`%1$s`, `%2$d`), so a phone translation can be reused as is.
 */
object Strings {
  private const val BASE = "trawl/strings"

  private fun load(name: String): Properties? =
    Strings::class.java.classLoader.getResourceAsStream(name)?.use { Properties().apply { load(InputStreamReader(it, Charsets.UTF_8)) } }

  private val english: Properties by lazy { load("$BASE.properties") ?: Properties() }

  @Volatile private var local: Properties? = null
  @Volatile private var localeTag = "en"

  /** Switches the language the keys are looked up in first; an unknown language leaves English. */
  fun use(locale: Locale) {
    localeTag = locale.language
    local = if (locale.language == "en") null else load("${BASE}_${locale.language}.properties")
  }

  val language: String get() = localeTag

  /** The text for [key] with [args] filled in. A key nobody wrote shows as itself, in brackets, so it is seen and not silent. */
  fun get(key: String, vararg args: Any?): String {
    val raw = local?.getProperty(key) ?: english.getProperty(key) ?: return "[$key]"
    return if (args.isEmpty()) raw else String.format(Locale.ROOT, raw, *args)
  }

  /**
   * The text for [key] in the form [count] needs. Looked up as `key_<category>`, then `key_other`, first in the language's file and
   * then in English, where English's own category is used. A translation may give only `_other` (a language whose noun does not
   * change with the number) and it is used for every count. With no [args] the count itself is the one argument, written with the
   * thousands separator.
   */
  fun plural(key: String, count: Long, vararg args: Any?): String {
    fun pick(p: Properties?, lang: String): String? =
      p?.getProperty("${key}_${Plurals.category(lang, count)}") ?: p?.getProperty("${key}_other")
    val raw = pick(local, localeTag) ?: pick(english, "en") ?: return "[$key]"
    val shown = if (args.isEmpty()) arrayOf<Any?>(java.text.NumberFormat.getIntegerInstance().format(count)) else arrayOf(*args)
    return String.format(Locale.ROOT, raw, *shown)
  }

  fun has(key: String): Boolean = english.getProperty(key) != null

  /**
   * True when [key] (or its counted forms, `key_one`, `key_other`) reads in the language in use: always in English, and in another language only
   * when its file has the key. A caller that would otherwise show English words inside a translated screen (an age, "2 min. ago") checks this
   * and shows something every language can format instead.
   */
  fun readsInLanguage(key: String): Boolean =
    localeTag == "en" || local?.stringPropertyNames()?.any { it == key || it.startsWith("${key}_") } == true

  /** Every key of the base file, for the test that checks the code asks for no key that is missing. */
  fun keys(): Set<String> = english.stringPropertyNames()
}

/** Shorthand used by the screens: `t("search_hint")`, `t("meter_sub_progress", done, total)`. */
fun t(key: String, vararg args: Any?): String = Strings.get(key, *args)

/** Counted text: `tn("count_photos", n)` picks `count_photos_one` or `_other` (or the language's other forms) for n; see [Plurals]. */
fun tn(key: String, count: Int, vararg args: Any?): String = Strings.plural(key, count.toLong(), *args)
