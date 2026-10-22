package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "no hard-coded text" check (docs: the translation notes). Every sentence the user reads is a string resource, so it
 * can be translated; the screens never carry English in Kotlin. Reads every Kotlin file under src/main and fails on a string
 * literal with letters in a user-visible position:
 *  - `Text("...")`, `text = "..."`, `contentDescription = "..."`, `label = "..."`, `title = "..."` and the other named
 *    arguments listed in [HardcodedText.NAMED];
 *  - notification and dialog calls: `setContentTitle(`, `setContentText(`, `NotificationChannel(id, "name", ...)`,
 *    `Toast.makeText(ctx, "...", ...)`, `showSnackbar("...")`, `Icon(x, "description")`.
 * Not checked on purpose: log lines, `diag/` (problem-report text for developers), `ui/settings/LicenceNotices.kt` (legal
 * notices stay English), `ui/fake/` (sample data). A literal that only looks like text (a debug label, a unit symbol) can say
 * so with `// i18n-ok: <why>` on its line (or on the line above it).
 *
 * It is a pattern check, not data-flow analysis: text built in a variable first and shown later is not caught, so review
 * those by hand. Android lint's HardcodedText (layout XML) is switched on in app/build.gradle.kts.
 */
class HardcodedTextTest {

  @Test fun mainSourcesHaveNoHardCodedUserText() {
    val root = File("src/main/java")
    assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
    // The shared engine moved to :core; its sources are checked by the same rules.
    val roots = listOf(root) + (File("../core/src").listFiles { f -> f.name.endsWith("Main") }.orEmpty().map { File(it, "kotlin") })
    val offenders = roots.asSequence().flatMap { it.walk() }.filter { it.isFile && it.extension == "kt" }.flatMap { f ->
      HardcodedText.scan(f.readText(), f.invariantSeparatorsPath).asSequence()
    }.toList()
    assertTrue(
      "User-visible text in Kotlin. Move it to res/values/strings*.xml (app name via the xliff placeholder, see ui/common/AppName.kt), " +
        "or add `// i18n-ok: <why>` if it is not text a person reads:\n" + offenders.joinToString("\n"),
      offenders.isEmpty(),
    )
  }

  // ---- the scanner catches what it should, and only that --------------------------------------------------------

  private fun flagged(code: String, path: String = "app/src/main/java/com/craneai/findr/ui/Fixture.kt") =
    HardcodedText.scan(code, path).map { it.line }.distinct()

  @Test fun literalsInVisiblePositionsAreCaught() {
    assertEquals(listOf(1), flagged("fun f() { Text(\"Hello there\") }"))
    assertEquals(listOf(1), flagged("fun f() { Text(text = \"Hello\", modifier = m) }"))
    assertEquals(listOf(1), flagged("fun f() { Text(if (on) \"On\" else \"Off\") }"))
    assertEquals(listOf(1), flagged("fun f() { Icon(Icons.Close, \"Close\") }"))
    assertEquals(listOf(1), flagged("fun g() = Modifier.semantics { contentDescription = \"Close\" }"))
    assertEquals(listOf(2), flagged("fun f() {\n  Chip(label = \"Photos\")\n}"))
    assertEquals(listOf(1), flagged("fun f() { AlertDialog(title = \"Remove?\") }"))
    assertEquals(listOf(1), flagged("fun f() { b.setContentTitle(\"Reading\") }"))
    assertEquals(listOf(1), flagged("fun f() { b.setContentText(\"Reading a photo\") }"))
    assertEquals(listOf(1), flagged("fun f() { NotificationChannel(ID, \"Learning your phone\", IMPORTANCE_LOW) }"))
    assertEquals(listOf(1), flagged("fun f() { Toast.makeText(this, \"Copied\", Toast.LENGTH_SHORT).show() }"))
    assertEquals(listOf(1), flagged("fun f() { scope.launch { snackbar.showSnackbar(\"Saved\") } }"))
    // Text with a template still has words in it.
    assertEquals(listOf(1), flagged("fun f() { Text(\"Open \${file.name}\") }"))
    // Every piece of a long sentence is reported.
    assertEquals(listOf(1, 2), flagged("fun f() { Text(\"A long sentence \" +\n \"that goes on\") }"))
    // Raw strings too.
    assertEquals(listOf(1), flagged("fun f() { Text(\"\"\"Hello\"\"\") }"))
  }

  @Test fun resourcesFormatsAndNonUiCodePass() {
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(stringResource(R.string.a)) }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(stringResource(R.string.a, \"arg\")) }"))   // an argument, not the sentence
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(\"\$count\") }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(\"\${a}  ·  \${b}\") }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(\"%d:%02d\".format(m, s)) }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(\"…\") }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(text = x, modifier = Modifier.testTag(\"Hello World\")) }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { val text = \"Hello\" }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { if (title == \"Hello\") {} }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Log.i(TAG, \"Reading a photo\") }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { error(\"Not here\") }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { animateFloat(label = \"pulseAlpha\") }"))   // an identifier-like debug label
    assertEquals(emptyList<Int>(), flagged("fun f() { NotificationChannel(\"indexing\", name, IMPORTANCE_LOW) }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT) }"))
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(buildString { append(\"x\") }) }"))
    // Comments and strings that only mention these calls are not calls.
    assertEquals(emptyList<Int>(), flagged("// Text(\"Hello\")\n/* label = \"Hello\" */\nfun f() { val s = \"Text(\\\"Hello\\\")\" }"))
  }

  @Test fun theEscapeCommentAndTheAllowlistWork() {
    assertEquals(emptyList<Int>(), flagged("fun f() { Text(\"MB\")   // i18n-ok: a unit symbol\n}"))
    assertEquals(emptyList<Int>(), flagged("// i18n-ok: sample data\nfun f() { Text(\"Hello\") }"))
    assertEquals(listOf(1), flagged("fun f() { Text(\"Hello\")   // i18n-ok:\n}"))   // a reason is required
    val code = "fun f() { Text(\"Hello\") }"
    assertEquals(emptyList<Int>(), flagged(code, "app/src/main/java/com/craneai/findr/ui/settings/LicenceNotices.kt"))
    assertEquals(emptyList<Int>(), flagged(code, "app/src/main/java/com/craneai/findr/diag/ReportBuilder.kt"))
    assertEquals(emptyList<Int>(), flagged(code, "app/src/main/java/com/craneai/findr/ui/fake/FakeData.kt"))
    assertEquals(listOf(1), flagged(code, "app/src/main/java/com/craneai/findr/ui/search/SearchScreen.kt"))
  }
}

/** A small Kotlin lexer, just enough to tell code from strings and comments, and a few positional rules on top. */
object HardcodedText {
  data class Offender(val path: String, val line: Int, val why: String, val code: String) {
    override fun toString() = "  $path:$line  $why\n      $code"
  }

  /** Files and folders whose text is not translated on purpose. */
  private val ALLOWED = listOf("/ui/settings/LicenceNotices.kt", "/diag/", "/ui/fake/")

  /** Named arguments whose value a person reads. */
  val NAMED = listOf(
    "text", "contentDescription", "label", "title", "subtitle", "placeholder", "supportingText", "description", "message",
    "hint", "stateDescription", "onClickLabel", "onLongClickLabel", "shortLabel", "longLabel", "headline",
  )

  /** Calls whose arguments, from [minArg] (0-based) on, a person reads. */
  private class CallRule(val name: Regex, val minArg: Int)
  private val CALLS = listOf(
    CallRule(Regex("""(?<![\w])Text\("""), 0),
    CallRule(Regex("""(?<![\w])(?:Icon|Image)\("""), 1),
    CallRule(Regex("""\.?(?<![\w])(?:setContentTitle|setContentText|setSubText|setTicker|setShortLabel|setLongLabel)\("""), 0),
    CallRule(Regex("""(?<![\w])showSnackbar\("""), 0),
    CallRule(Regex("""(?<![\w])NotificationChannel\("""), 1),
    CallRule(Regex("""(?<![\w])Toast\.makeText\("""), 1),
  )
  private val NAMED_PREFIX = Regex("""(?<![\w.])(?<!val\s)(?<!var\s)(?:${NAMED.joinToString("|")})\s*=\s*$""")
  private val ESCAPE = Regex("""//\s*i18n-ok:\s*\S""")
  private val FORMAT_SPEC = Regex("""%[\d$.,#+\- ]*[a-zA-Z%]""")
  private val IDENTIFIER_LIKE = Regex("""[a-z][A-Za-z0-9_]*""")

  private class Lit(val start: Int, val end: Int, val text: String)
  private class Lexed(val code: CharArray, val lits: List<Lit>)

  fun scan(src: String, path: String): List<Offender> {
    if (ALLOWED.any { path.contains(it) }) return emptyList()
    val lexed = lex(src)
    val code = String(lexed.code)
    val lines = src.lines()
    val lineStarts = ArrayList<Int>().also { l -> l += 0; src.forEachIndexed { i, c -> if (c == '\n') l += i + 1 } }
    fun lineOf(i: Int) = lineStarts.indexOfLast { it <= i } + 1
    fun waived(vararg at: Int): Boolean = at.any { n ->
      val line = lineOf(n)
      ESCAPE.containsMatchIn(lines.getOrElse(line - 1) { "" }) ||
        lines.getOrElse(line - 2) { "" }.let { it.trim().startsWith("//") && ESCAPE.containsMatchIn(it) }
    }
    val visible = lexed.lits.filter { l ->
      FORMAT_SPEC.replace(l.text, "").any { it.isLetter() }
    }
    val out = LinkedHashMap<Int, Offender>()   // by literal start: one report per literal
    fun flag(l: Lit, why: String, callAt: Int) {
      if (waived(l.start, callAt)) return
      out.getOrPut(l.start) { Offender(path, lineOf(l.start), why, lines[lineOf(l.start) - 1].trim()) }
    }

    // Named arguments: `text = "..."`.
    for (l in visible) {
      val before = code.substring(maxOf(0, l.start - 80), l.start)
      val m = NAMED_PREFIX.find(before) ?: continue
      val name = m.value.substringBefore('=').trim()
      if (name == "label" && IDENTIFIER_LIKE.matches(l.text)) continue   // animation debug labels: "pulseAlpha"
      flag(l, "$name = \"...\" shows English", l.start)
    }
    // Calls: `Text("...")`, `setContentTitle("...")`, ...
    for (rule in CALLS) rule.name.findAll(code).forEach { m ->
      val open = m.range.last
      val close = matchBracket(code, open) ?: return@forEach
      for (l in visible) {
        if (l.start <= open || l.end > close) continue
        val (depth, arg) = position(code, open, l.start)
        if (depth != 0 || arg < rule.minArg) continue
        flag(l, "${m.value.trimStart('.').trimEnd('(')}(\"...\") shows English", open)
      }
    }
    return out.values.sortedBy { it.line }
  }

  /** Bracket depth below the call's own parenthesis, and the 0-based argument index, of the position [at] inside the call at [open]. */
  private fun position(code: String, open: Int, at: Int): Pair<Int, Int> {
    var depth = 0
    var arg = 0
    for (i in open + 1 until at) when (code[i]) {
      '(', '[', '{' -> depth++
      ')', ']', '}' -> depth--
      ',' -> if (depth == 0) arg++
    }
    return depth to arg
  }

  private fun matchBracket(code: String, open: Int): Int? {
    var depth = 0
    for (i in open until code.length) when (code[i]) {
      '(', '[', '{' -> depth++
      ')', ']', '}' -> if (--depth == 0) return i
    }
    return null
  }

  /**
   * Blanks comments and string contents (so brackets and calls inside them are invisible) and lists the string literals with
   * the text of each, template parts (`$x`, `${...}`) left out. A literal inside a template is listed on its own.
   */
  private fun lex(src: String): Lexed {
    val n = src.length
    val code = src.toCharArray()
    val lits = ArrayList<Lit>()
    var i = 0
    fun at(s: String) = src.startsWith(s, i)
    fun blank(from: Int, to: Int) { for (j in from until minOf(to, n)) if (code[j] != '\n') code[j] = ' ' }

    lateinit var string: () -> Unit
    // Code until the end of input, or (inside a `${`) until its closing brace.
    fun codeRun(inTemplate: Boolean) {
      var depth = 0
      while (i < n) {
        when {
          at("//") -> { val end = src.indexOf('\n', i).let { if (it < 0) n else it }; blank(i, end); i = end }
          at("/*") -> {
            val start = i
            var nest = 0
            while (i < n) {
              if (at("/*")) { nest++; i += 2 } else if (at("*/")) { nest--; i += 2; if (nest == 0) break } else i++
            }
            blank(start, i)
          }
          at("\"") -> string()
          at("'") -> { i++; while (i < n && !at("'")) i += if (at("\\")) 2 else 1; i++ }
          at("{") -> { depth++; i++ }
          at("}") -> { if (inTemplate && depth == 0) { i++; return }; depth--; i++ }
          else -> i++
        }
      }
    }
    string = {
      val start = i
      val raw = at("\"\"\"")
      i += if (raw) 3 else 1
      val text = StringBuilder()
      while (i < n) {
        when {
          raw && at("\"\"\"") -> { i += 3; while (i < n && src[i] == '"') i++; break }
          !raw && at("\\") -> i += 2
          !raw && at("\"") -> { i++; break }
          at("\${") -> { i += 2; codeRun(inTemplate = true) }
          at("$") && i + 1 < n && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
            i++
            while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
          }
          else -> { text.append(src[i]); i++ }
        }
      }
      // The quotes stay, so `"` still marks where a literal is; everything between is blanked.
      blank(start + 1, i - 1)
      lits += Lit(start, i, text.toString())
    }
    codeRun(inTemplate = false)
    return Lexed(code, lits)
  }
}
