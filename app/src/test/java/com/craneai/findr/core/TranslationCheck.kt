package com.craneai.findr.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The rules a translation file must follow so the app does not crash or show broken text, written once for the JVM tests
 * (TranslationsTest). `scripts/i18n_import.py` applies the same rules to a file before it enters res/. Reads Android
 * `<resources>` files: `<string>` and `<plurals>`, with `<xliff:g>` spans.
 */
object TranslationCheck {
  const val XLIFF = "urn:oasis:names:tc:xliff:document:1.2"

  /** The plural keywords of CLDR; a language uses only some of them. */
  val QUANTITIES = setOf("zero", "one", "two", "few", "many", "other")

  /** One sentence: its text as written (escapes untouched), and what its `<xliff:g id="app_name">` spans hold. */
  data class Piece(val text: String, val appNameSlots: List<String>, val wholeQuoted: Boolean)

  data class Entry(val name: String, val plural: Boolean, val translatable: Boolean, val piece: Piece?, val items: Map<String, Piece>)

  /** Matches `%1$s`, `%2$d`, `%d`, `%s`, `%,d`, `%02d`, `%.1f`, `%%`, `%n`. */
  private val PLACEHOLDER = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

  fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.toList()

  /** The positional index of each argument placeholder (an unnumbered one takes the next free index), without `%%` and `%n`. */
  fun argumentIndexes(text: String): Set<Int> {
    var next = 1
    val out = sortedSetOf<Int>()
    for (m in PLACEHOLDER.findAll(text)) {
      if (m.value == "%%" || m.value == "%n") continue
      out += m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt() ?: next++
    }
    return out
  }

  private fun piece(e: Element): Piece {
    val slots = mutableListOf<String>()
    val gs = e.getElementsByTagNameNS(XLIFF, "g")
    for (i in 0 until gs.length) (gs.item(i) as Element).let { g -> if (g.getAttribute("id") == "app_name") slots += g.textContent }
    val t = e.textContent
    return Piece(t, slots, t.length >= 2 && t.startsWith("\"") && t.endsWith("\""))
  }

  fun parse(file: File): Map<String, Entry> {
    val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val doc = f.newDocumentBuilder().parse(file)
    val out = LinkedHashMap<String, Entry>()
    val kids = doc.documentElement.childNodes
    for (i in 0 until kids.length) {
      val e = kids.item(i) as? Element ?: continue
      val name = e.getAttribute("name")
      val translatable = e.getAttribute("translatable") != "false"
      when (e.tagName) {
        "string" -> out[name] = Entry(name, false, translatable, piece(e), emptyMap())
        "plurals" -> {
          val items = LinkedHashMap<String, Piece>()
          val its = e.getElementsByTagName("item")
          for (j in 0 until its.length) (its.item(j) as Element).let { items[it.getAttribute("quantity")] = piece(it) }
          out[name] = Entry(name, true, translatable, null, items)
        }
      }
    }
    return out
  }

  /** All `strings*.xml` in a values folder, merged. */
  fun parseFolder(dir: File): Map<String, Entry> {
    val out = LinkedHashMap<String, Entry>()
    dir.listFiles { f -> f.isFile && f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().sortedBy { it.name }
      .forEach { out.putAll(parse(it)) }
    return out
  }

  /** `values-fr` -> "fr", `values-b+nyn` -> "nyn", `values-fr-rCA` -> "fr-CA"; null for `values`, `values-night`, `values-sw600dp`, ... */
  fun languageOfFolder(folder: String): String? {
    val q = folder.removePrefix("values-").takeIf { folder.startsWith("values-") } ?: return null
    Regex("""b\+([A-Za-z]{2,3}(?:\+[A-Za-z0-9]+)*)""").matchEntire(q)?.let { return it.groupValues[1].replace('+', '-') }
    Regex("""([a-z]{2})(?:-r([A-Z]{2}))?""").matchEntire(q)?.let { return if (it.groupValues[2].isEmpty()) it.groupValues[1] else "${it.groupValues[1]}-${it.groupValues[2]}" }
    return null
  }

  /** Language folders under [res]: tag to folder. */
  fun languageFolders(res: File): Map<String, File> =
    res.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d -> languageOfFolder(d.name)?.let { it to d } }.sortedBy { it.first }.toMap(LinkedHashMap())

  /** Problems in [translated] measured against [english]. [appName] is the literal name that must not be written in a translation. */
  fun problems(english: Map<String, Entry>, translated: Map<String, Entry>, appName: String): List<String> {
    val out = mutableListOf<String>()
    for ((name, t) in translated) {
      val en = english[name]
      if (en == null) { out += "$name: not in the English strings (a typo, or an old key)"; continue }
      if (!en.translatable) { out += "$name: marked translatable=\"false\" in English, so it must not be translated"; continue }
      if (en.plural != t.plural) { out += "$name: English is ${kind(en)}, the translation is ${kind(t)}"; continue }
      if (!t.translatable) out += "$name: do not mark translations translatable=\"false\""
      if (!en.plural) {
        out += pieceProblems(name, en.piece!!, t.piece!!, appName, exact = true, allowed = null)
      } else {
        for (q in t.items.keys) if (q !in QUANTITIES) out += "$name: \"$q\" is not a plural keyword (zero, one, two, few, many, other)"
        val enOther = en.items["other"]
        val trOther = t.items["other"]
        if (trOther == null) { out += "$name: plural has no \"other\" item"; continue }
        val allowed = en.items.values.flatMap { placeholders(it.text) }.toSet()
        for ((q, item) in t.items) {
          if (q !in QUANTITIES) continue
          out += pieceProblems("$name[$q]", enOther!!, item, appName, exact = q == "other", allowed = allowed)
        }
      }
    }
    return out
  }

  private fun kind(e: Entry) = if (e.plural) "<plurals>" else "<string>"

  private fun pieceProblems(label: String, en: Piece, tr: Piece, appName: String, exact: Boolean, allowed: Set<String>?): List<String> {
    val out = mutableListOf<String>()
    val enP = placeholders(en.text).sorted()
    val trP = placeholders(tr.text).sorted()
    if (exact && enP != trP) out += "$label: placeholders ${trP} do not match English ${enP}"
    if (!exact && allowed != null) {
      val extra = trP.filter { it !in allowed }
      if (extra.isNotEmpty()) out += "$label: uses $extra, which English does not"
    }
    if (exact) {
      if (en.appNameSlots.toSet() != tr.appNameSlots.toSet()) {
        out += "$label: the app name span (<xliff:g id=\"app_name\">) holds ${tr.appNameSlots} but English has ${en.appNameSlots}; keep it as it is"
      }
    } else {
      val extra = tr.appNameSlots.filter { it !in en.appNameSlots }
      if (extra.isNotEmpty()) out += "$label: the app name span holds $extra, English has ${en.appNameSlots}"
    }
    if (appName.isNotEmpty() && tr.text.contains(appName)) out += "$label: writes the app name \"$appName\"; use the <xliff:g id=\"app_name\"> placeholder"
    if (!tr.wholeQuoted) {
      // A quote is escaped only by an odd number of backslashes: in ma\\'ana the backslash escapes itself and the quote is bare.
      Regex("""(?<!\\)(?:\\\\)*'""").find(tr.text)?.let { out += "$label: unescaped apostrophe at ${it.range.first} (write \\')" }
      Regex("""(?<!\\)(?:\\\\)*"""").find(tr.text)?.let { out += "$label: unescaped double quote at ${it.range.first} (write \\\")" }
    }
    if (Regex("""^[@?]""").containsMatchIn(tr.text) && !tr.text.startsWith("@@")) out += "$label: starts with @ or ? (write \\@ or \\?)"
    return out
  }
}
