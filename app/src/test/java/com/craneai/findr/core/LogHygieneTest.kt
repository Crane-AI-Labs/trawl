package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The log check (the research notes, "Logs"): a release build must never write file names, folders, paths,
 * URIs, queries, extracted text, transcripts or exception messages to logcat. Reads every Kotlin file under
 * src/main and fails on:
 *  1. a direct `android.util.Log` call, `println`, `printStackTrace` or `System.out/err` (everything goes through
 *     [FindrLog], usually imported as `Log`);
 *  2. a private value in a log line without `redact(...)`: inside `Log.i/w/e(...)`, `FindrLog.i/w/e(...)` and the
 *     injected `log(...)` loggers, every string template (`$x`, `${...}`) is checked;
 *  3. a private value in the ongoing notification (`foregroundInfo(...)`), which can show on the lock screen.
 * A line that logs something that only looks private (our own model file's name, a codec's name) can say so with a
 * `// log-safe: <why>` comment inside the call. Debug-only code (src/debug) is not checked.
 *
 * It is a pattern check, not data-flow analysis: a private value built into a variable first and logged later is not
 * caught, so review those by hand (the perf marks in the search view models are the known case, and redact at the source).
 */
class LogHygieneTest {

  @Test fun mainSourcesFollowTheLogRules() {
    val root = File("src/main/java")
    assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
    // The shared engine moved to :core; its sources are checked by the same rules.
    val roots = listOf(root) + (File("../core/src").listFiles { f -> f.name.endsWith("Main") }.orEmpty().map { File(it, "kotlin") })
    val offenders = roots.asSequence().flatMap { it.walk() }.filter { it.isFile && it.extension == "kt" }.flatMap { f ->
      LogScan.scan(f.readText(), f.path).asSequence()
    }.toList()
    assertTrue(
      "Private data can reach logcat or a notification. Wrap it in redact(...) (core/FindrLog) or log a count instead:\n" +
        offenders.joinToString("\n"),
      offenders.isEmpty(),
    )
  }

  // ---- the scanner catches what it should, and only that --------------------------------------------------------

  private fun flagged(code: String) = LogScan.scan(code, "Fixture.kt").map { it.why }

  @Test fun rawLoggingIsCaught() {
    assertEquals(1, flagged("import android.util.Log\nfun f() {}").size)
    assertEquals(1, flagged("fun f() { android.util.Log.i(\"T\", \"x\") }").size)
    assertEquals(1, flagged("fun f(e: Exception) { e.printStackTrace() }").size)
    assertEquals(1, flagged("fun f() { println(\"x\") }").size)
    assertEquals(0, flagged("import com.craneai.findr.core.FindrLog as Log\nfun f() { Log.i(\"T\", \"x\") }").size)
  }

  @Test fun privateValuesInLogLinesAreCaught() {
    assertEquals(1, flagged("fun f() { Log.i(TAG, \"understood \${file.name} in 2 s\") }").size)
    assertEquals(1, flagged("fun f() { Log.w(TAG, \"\$name: no text\") }").size)
    assertEquals(1, flagged("fun f() { Log.w(TAG, \"failed (\${t.message})\") }").size)
    assertEquals(1, flagged("fun f() { Log.i(TAG, \"search \\\"\$q\\\"\") }").size)
    assertEquals(1, flagged("fun f() { log(\"install failed: \$t\") }").size)
    assertEquals(1, flagged("fun f() { FindrLog.i(TAG, \"top \${hits.firstOrNull()?.file?.name}\") }").size)
    assertEquals(1, flagged("fun f() { Log.i(TAG, \"x \" +\n  \"at \${file.relativePath}\") }").size)
  }

  @Test fun countsTimingsAndRedactedValuesPass() {
    assertEquals(0, flagged("fun f() { Log.i(TAG, \"understood \${redact(file.name)} in \${secs} s: \${segments.size} pieces\") }").size)
    assertEquals(0, flagged("fun f() { Log.w(TAG, \"no text (\${t.javaClass.simpleName})\") }").size)
    assertEquals(0, flagged("fun f() { Log.w(TAG, \"no text (\${Log.brief(t)})\") }").size)
    assertEquals(0, flagged("fun f() { Log.i(TAG, \"name length \${file.name.length}\") }").size)
    assertEquals(0, flagged("fun f() { Log.i(TAG, \"loaded \${file.name}\")   // log-safe: our model file\n}").size)
    // Not a log call: a UI string may hold a name.
    assertEquals(0, flagged("fun f() { val title = \"Open \${file.name}\" }").size)
    // Comments and strings that only mention logging are not calls.
    assertEquals(0, flagged("// Log.i(TAG, \"\${file.name}\")\nfun f() { val s = \"Log.i(\" }").size)
  }

  @Test fun privateValuesInTheNotificationAreCaught() {
    assertEquals(1, flagged("fun f() { setForeground(foregroundInfo(file.name)) }").size)
    assertEquals(0, flagged("fun f() { setForeground(foregroundInfo(notificationLine(file.kind))) }").size)
  }
}

/** A small Kotlin lexer, just enough to tell code from strings and comments and to find string templates. */
object LogScan {
  data class Offender(val path: String, val line: Int, val why: String, val code: String) {
    override fun toString() = "  $path:$line  $why\n      $code"
  }

  private enum class K { CODE, STR, COMMENT }
  private class Template(val start: Int, val end: Int, val expr: String)

  /** Members whose value names or holds user data. Flagged as the last part of `a.b.c`, unless followed by a count. */
  private val PRIVATE_MEMBERS = setOf(
    "name", "displayName", "path", "relativePath", "absolutePath", "canonicalPath", "parent", "parentFile", "uri", "query",
    "text", "body", "transcript", "snippet", "words", "title", "folder", "folders", "excludedFolders", "privateFolders",
    "currentFile", "message", "localizedMessage",
    "label", "problems",   // a model pack's source label is its path in Downloads; install problems quote paths
  )
  /** Variables that hold a private value whatever is done with them: `$name`, `${query.trim()}`. */
  private val PRIVATE_VARS = PRIVATE_MEMBERS - "words" + "q"   // `$words` is usually a count
  /**
   * Variables that are private when printed whole: `${file}` (a File prints its path), `$t` (an exception prints its
   * message). `${file.kind}` and `${now - t}` are fine.
   */
  private val PRIVATE_WHOLE = setOf("file", "f", "t", "e", "ex", "err", "error", "throwable", "cause", "source", "doc")
  /** A count or size of a private value is fine: `${file.name.length}`. */
  private val COUNTS = setOf("length", "size", "count", "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "hashCode", "lastIndex")

  private val RAW = listOf(
    Regex("""\bandroid\.util\.Log\b""") to "android.util.Log: use core/FindrLog (import com.craneai.findr.core.FindrLog as Log)",
    Regex("""\.printStackTrace\(""") to "printStackTrace: log through FindrLog, which leaves messages out of release logs",
    Regex("""(?<![\w.])println\(""") to "println goes to logcat: use FindrLog",
    Regex("""\bSystem\.(out|err)\b""") to "System.out/err goes to logcat: use FindrLog",
  )
  private val LOG_CALL = Regex("""(?<![\w.])(?:Log|FindrLog)\.(?:i|w|e)\(|(?<![\w.])log\(""")
  private val NOTIFICATION_CALL = Regex("""(?<![\w.])foregroundInfo\(""")
  private val CHAIN = Regex("""(?<![\w$])[A-Za-z_]\w*(?:\s*\??\.\s*[A-Za-z_]\w*)*""")
  private val STRING_LITERAL = Regex(""""(?:\\.|[^"\\])*"""")

  fun scan(src: String, path: String): List<Offender> {
    // FindrLog itself, and its platform sinks (FindrLog.android.kt is the one place that calls android.util.Log).
    if (Regex("""core/FindrLog(\.\w+)?\.kt$""").containsMatchIn(path.replace('\\', '/'))) return emptyList()
    val (kind, templates) = lex(src)
    val out = ArrayList<Offender>()
    fun lineOf(i: Int) = src.substring(0, i).count { it == '\n' } + 1
    fun lineText(i: Int) = src.lineSequence().drop(lineOf(i) - 1).first().trim()

    for ((re, why) in RAW) re.findAll(src).forEach { m ->
      if (kind[m.range.first] == K.CODE) out += Offender(path, lineOf(m.range.first), why, lineText(m.range.first))
    }
    for ((re, isNotification) in listOf(LOG_CALL to false, NOTIFICATION_CALL to true)) re.findAll(src).forEach { m ->
      if (kind[m.range.first] != K.CODE) return@forEach
      if (src.substring(maxOf(0, m.range.first - 4), m.range.first) == "fun ") return@forEach   // a declaration, not a call
      val open = m.range.last
      val close = matchParen(src, kind, open) ?: return@forEach
      val waived = (lineOf(open)..lineOf(close) + 0).any { n -> src.lineSequence().drop(n - 1).firstOrNull()?.contains("// log-safe:") == true }
      if (waived) return@forEach
      val bad = templates.filter { it.start > open && it.end <= close && isPrivate(it.expr) }.map { it.expr }.toMutableList()
      if (isNotification) {
        val argCode = (open + 1 until close).filter { kind[it] == K.CODE }.map { src[it] }.joinToString("")
        if (isPrivate(argCode)) bad += argCode.trim()
      }
      bad.distinct().forEach { expr ->
        val why = if (isNotification) "notification shows \"$expr\" (it can appear on the lock screen)" else "log line holds \"$expr\" without redact()"
        out += Offender(path, lineOf(open), why, lineText(open))
      }
    }
    return out
  }

  /** True when the expression reads a private value that isn't wrapped in redact(...) or reduced to a count. */
  fun isPrivate(expr: String): Boolean {
    // redact() and FindrLog.brief() print "<private>" for what they are given, unless detail logging is on.
    val e = stripCalls(stripCalls(expr, "redact("), "brief(").replace(STRING_LITERAL, "\"\"")
    if (e.trim() in PRIVATE_WHOLE) return true
    return CHAIN.findAll(e).any { m ->
      val parts = m.value.split('.').map { it.trim().trimEnd('?') }.filter { it.isNotEmpty() }
      if (parts.last() in COUNTS) return@any false
      parts[0] in PRIVATE_VARS || parts.drop(1).any { it in PRIVATE_MEMBERS }
    }
  }

  /** Removes every `name(...)` call (balanced parentheses) from [expr]. */
  private fun stripCalls(expr: String, call: String): String {
    var s = expr
    while (true) {
      val at = Regex("""(?<![\w])""" + Regex.escape(call)).find(s)?.range?.first ?: return s
      var depth = 0
      var j = at + call.length - 1
      while (j < s.length) {
        if (s[j] == '(') depth++ else if (s[j] == ')' && --depth == 0) break
        j++
      }
      s = s.substring(0, at) + " " + if (j < s.length) s.substring(j + 1) else ""
    }
  }

  private fun matchParen(src: String, kind: Array<K>, open: Int): Int? {
    var depth = 0
    for (i in open until src.length) {
      if (kind[i] != K.CODE) continue
      when (src[i]) {
        '(' -> depth++
        ')' -> if (--depth == 0) return i
      }
    }
    return null
  }

  private fun lex(src: String): Pair<Array<K>, List<Template>> {
    val kind = Array(src.length) { K.CODE }
    val templates = ArrayList<Template>()
    var i = 0
    fun at(s: String) = src.startsWith(s, i)
    fun mark(n: Int, k: K) { for (j in i until minOf(i + n, src.length)) kind[j] = k; i += n }

    lateinit var string: (Boolean) -> Unit
    // Code until the end of input, or (inside a `${`) until its closing brace.
    fun code(inTemplate: Boolean) {
      var depth = 0
      while (i < src.length) {
        when {
          at("//") -> { val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }; mark(end - i, K.COMMENT) }
          at("/*") -> {
            var nest = 0
            while (i < src.length) {
              if (at("/*")) { nest++; mark(2, K.COMMENT) } else if (at("*/")) { nest--; mark(2, K.COMMENT); if (nest == 0) break } else mark(1, K.COMMENT)
            }
          }
          at("\"\"\"") -> { mark(3, K.STR); string(true) }
          at("\"") -> { mark(1, K.STR); string(false) }
          at("'") -> { mark(1, K.STR); while (i < src.length && !at("'")) mark(if (at("\\")) 2 else 1, K.STR); mark(1, K.STR) }
          at("{") -> { depth++; i++ }
          at("}") -> { if (inTemplate && depth == 0) { mark(1, K.STR); return }; depth--; i++ }
          else -> i++
        }
      }
    }
    string = { raw ->
      while (i < src.length) {
        when {
          raw && at("\"\"\"") -> { while (at("\"\"\"\"")) mark(1, K.STR); mark(3, K.STR); break }
          !raw && at("\\") -> mark(2, K.STR)
          !raw && at("\"") -> { mark(1, K.STR); break }
          at("\${") -> {
            mark(2, K.STR)
            val start = i
            code(inTemplate = true)
            templates += Template(start, i - 1, src.substring(start, i - 1))
          }
          at("$") && i + 1 < src.length && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
            mark(1, K.STR)
            val start = i
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) mark(1, K.STR)
            templates += Template(start, i, src.substring(start, i))
          }
          else -> mark(1, K.STR)
        }
      }
    }
    code(inTemplate = false)
    return kind to templates
  }
}
