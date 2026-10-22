package com.craneai.findr.ui.language

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * A language the app's screens can be shown in. [nativeName] is the language in its own words, which is how the picker lists it
 * (a person who reads only Luganda must still find Luganda). [preview] marks a translation that has not reached our quality bar
 * yet; the picker says so. [tag] is the BCP 47 tag: res/values-<qualifier> and res/xml/locales_config.xml use the same one.
 */
data class AppLanguage(val tag: String, val nativeName: String, val englishName: String, val preview: Boolean = false) {
  /** The resource folder qualifier: "values-lg" for two letters, "values-b+nyn" for three. */
  val valuesQualifier: String get() = if (tag.length == 2) tag else "b+" + tag.replace('-', '+')
}

object AppLanguages {
  /**
   * Every language the app ships, in picker order: English first, then the maintainers' chosen order. Each one here has a full
   * res/values-<qualifier>/strings.xml and a line in res/xml/locales_config.xml (LanguagesTest keeps the three in step).
   * All but English are machine translations (Gemini 3.8 Flash, back-translation checked: the translation notes),
   * awaiting native review. Acholi and Runyankore scored lower and carry the Preview tag.
   */
  val shipped: List<AppLanguage> by lazy { planned.filter { it.tag !in NOT_READY } }

  /**
   * Translated but held back (the lead, 2026-10-15): Ateso (4.35 of 5, 66 strings flagged) and Lugbara (3.52, 216 of 412
   * flagged) need a native rewrite before they go in the picker or locales_config. Their files stay out of the app.
   */
  val NOT_READY: Set<String> = setOf("teo", "lgg")

  /** Every language asked for, in the picker's order (the maintainers' order); [shipped] is these minus [NOT_READY]. */
  val planned: List<AppLanguage> = listOf(
    AppLanguage("en", "English", "English"),
    AppLanguage("fr", "Français", "French"),
    AppLanguage("pt", "Português", "Portuguese"),
    AppLanguage("ar", "العربية", "Arabic"),
    AppLanguage("sw", "Kiswahili", "Swahili"),
    AppLanguage("lg", "Luganda", "Luganda"),
    AppLanguage("rw", "Ikinyarwanda", "Kinyarwanda"),
    AppLanguage("nyn", "Runyankore", "Runyankole", preview = true),
    AppLanguage("ach", "Leb Acoli", "Acholi", preview = true),
    AppLanguage("teo", "Ateso", "Ateso", preview = true),
    AppLanguage("lgg", "Lugbarati", "Lugbara", preview = true),
    AppLanguage("am", "አማርኛ", "Amharic"),
    AppLanguage("ha", "Hausa", "Hausa"),
    AppLanguage("yo", "Yorùbá", "Yoruba"),
    AppLanguage("so", "Soomaali", "Somali"),
    AppLanguage("zu", "isiZulu", "isiZulu"),
  )

  fun byTag(tag: String?): AppLanguage? = tag?.let { t -> shipped.firstOrNull { it.tag.equals(t, ignoreCase = true) } }

  /** The phone's own language when the app ships it (the picker's first choice), else English. */
  fun systemDefault(systemLocales: LocaleList = LocaleList.getDefault()): AppLanguage {
    for (i in 0 until systemLocales.size()) {
      val lang = systemLocales[i].language
      byTag(lang)?.let { return it }
    }
    return shipped.first()
  }
}

/**
 * The per-app language. Android 13 and later keep it themselves (LocaleManager), so the choice also shows in the phone's
 * Settings, Apps, Language, and the phone can change it too. Before 13 it is kept here and applied to every context the app
 * creates ([wrap], from FindrApp and the activities). An empty tag means "follow the phone".
 */
object AppLocale {
  private const val PREFS = "findr_locale"
  private const val KEY = "tag"

  /** The chosen language tag, or null when the app follows the phone's language. */
  fun current(context: Context): String? =
    if (Build.VERSION.SDK_INT >= 33) {
      context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
    } else {
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotEmpty() }
    }

  /** What the screens are shown in now: the chosen language, else the phone's when shipped, else English. */
  fun effective(context: Context): AppLanguage =
    AppLanguages.byTag(current(context)?.let { Locale.forLanguageTag(it).language }) ?: AppLanguages.systemDefault(systemLocales(context))

  /**
   * Sets the app's language ([tag] null = follow the phone). On Android 13+ the system recreates the visible activities itself;
   * before 13 the caller recreates its activity ([needsRecreate] says so).
   */
  fun set(context: Context, tag: String?) {
    if (Build.VERSION.SDK_INT >= 33) {
      context.getSystemService(LocaleManager::class.java)?.applicationLocales =
        if (tag.isNullOrEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    } else {
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, tag.orEmpty()) }
    }
  }

  val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < 33

  /** Before Android 13: a context whose resources speak the chosen language. From 13 the system does this; [base] is returned. */
  fun wrap(base: Context): Context {
    if (Build.VERSION.SDK_INT >= 33) return base
    val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotEmpty() } ?: return base
    val config = Configuration(base.resources.configuration)
    val locales = LocaleList.forLanguageTags(tag)
    config.setLocales(locales)
    LocaleList.setDefault(locales)
    return base.createConfigurationContext(config)
  }

  /** The phone's languages, not the app's. */
  private fun systemLocales(context: Context): LocaleList =
    if (Build.VERSION.SDK_INT >= 33) {
      context.getSystemService(LocaleManager::class.java)?.systemLocales ?: LocaleList.getDefault()
    } else {
      android.content.res.Resources.getSystem().configuration.locales
    }
}
