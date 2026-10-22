package com.craneai.findr.harness

/**
 * A small JSON reader for the benchmark harness. Android's org.json is stubbed in JVM unit tests, so the harness
 * brings its own: objects become Map, arrays List, numbers Double, plus String, Boolean and null.
 */
object MiniJson {
  fun parse(text: String): Any? = Parser(text).run { val v = value(); ws(); check(i == s.length) { "trailing JSON at $i" }; v }

  private class Parser(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? {
      ws()
      return when (val c = s[i]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> str()
        't' -> { expect("true"); true }
        'f' -> { expect("false"); false }
        'n' -> { expect("null"); null }
        else -> if (c == '-' || c.isDigit()) num() else error("bad JSON char '$c' at $i")
      }
    }
    fun expect(w: String) { check(s.startsWith(w, i)) { "expected $w at $i" }; i += w.length }
    fun obj(): Map<String, Any?> {
      val m = LinkedHashMap<String, Any?>()
      i++; ws()
      if (s[i] == '}') { i++; return m }
      while (true) {
        ws(); val k = str(); ws(); check(s[i] == ':'); i++
        m[k] = value(); ws()
        if (s[i] == ',') { i++; continue }
        check(s[i] == '}') { "expected } at $i" }; i++; return m
      }
    }
    fun arr(): List<Any?> {
      val l = ArrayList<Any?>()
      i++; ws()
      if (s[i] == ']') { i++; return l }
      while (true) {
        l += value(); ws()
        if (s[i] == ',') { i++; continue }
        check(s[i] == ']') { "expected ] at $i" }; i++; return l
      }
    }
    fun num(): Double {
      val st = i
      while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
      return s.substring(st, i).toDouble()
    }
    fun str(): String {
      check(s[i] == '"') { "expected string at $i" }
      i++
      val sb = StringBuilder()
      while (true) {
        val c = s[i++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> when (val e = s[i++]) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000c')
            '/' -> sb.append('/')
            '\\' -> sb.append('\\')
            '"' -> sb.append('"')
            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
            else -> error("bad escape \\$e")
          }
          else -> sb.append(c)
        }
      }
    }
  }

  /** JSON string literal with escapes (UTF-16 surrogates pass through as they are). */
  fun quote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when {
      c == '"' -> sb.append("\\\"")
      c == '\\' -> sb.append("\\\\")
      c == '\n' -> sb.append("\\n")
      c == '\r' -> sb.append("\\r")
      c == '\t' -> sb.append("\\t")
      c < ' ' -> sb.append(String.format("\\u%04x", c.code))
      else -> sb.append(c)
    }
    return sb.append('"').toString()
  }
}
