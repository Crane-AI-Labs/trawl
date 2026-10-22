package com.craneai.findr.engine

import java.io.InputStream
import java.text.Normalizer

/**
 * OpenCLIP's SimpleTokenizer in plain Kotlin: the BPE tokenizer of the LAION CLIP-ViT-B/32 text tower, which turns a query into the
 * `input_ids` int64 [1, 77] the ONNX text tower takes. The vectors only mean something if the ids are exactly the ones the model was
 * trained on, so this is checked token for token against open_clip itself (scripts/build_clip_tokenizer.py writes the golden file,
 * ClipTokenizerTest compares). No Android types, so unit tests run it as is.
 *
 * What it matches: open_clip 3.3.0 `get_tokenizer("hf-hub:laion/CLIP-ViT-B-32-laion2B-s34B-b79K")`, which that repo's config resolves to
 * `SimpleTokenizer(context_length=77, clean="lower")`. The special tokens are open_clip's `<start_of_text>` and `<end_of_text>` (OpenAI's
 * original spelled them `<|startoftext|>`; here that is ordinary punctuation, as it is in open_clip).
 *
 * The cleaning is ftfy.fix_text, then html.unescape twice, then whitespace collapse and lower-casing. ftfy cannot be ported whole, so this
 * does the parts that change what a person types (curly quotes to straight, full-width forms, Latin ligatures, line breaks, control
 * characters, C1 controls read as Windows-1252, NFC, ANSI escapes, `&name;` and `&#n;` references) and skips the rest:
 *  - Mojibake repair (`cafÃ©` back to `café`) needs ftfy's encoding detector and badness model. Text that is already wrong stays wrong.
 *  - HTML entities beyond a table of ~140 names (Latin-1, common punctuation and arrows). Python knows 2,231; a query with `&alpha;` is
 *    left as typed here and decoded there.
 * Both only matter for pasted web text, not for typed queries.
 *
 * Heap: the merge table is two int arrays of 48,894 entries (sorted pair keys and their ranks, ~390 KB) plus a word cache capped at
 * [CACHE_LIMIT] entries (a few hundred KB when full). Loading briefly holds a string-to-id map (several MB) and then drops it. Encoding a
 * query allocates the output and one small string and array per word.
 *
 * Ids follow open_clip's vocab: 256 byte symbols, the same 256 ending a word (`</w>`), the 48,894 merges in order, then the two specials.
 * A merge's result id is therefore 512 + its rank, so no result table is stored.
 */
class ClipTokenizer private constructor(merges: Iterator<String>) {
  /** [merges] are the lines `"a b"` of open_clip's merge list (see [ASSET]), in order, header line excluded. */
  constructor(merges: List<String>) : this(merges.iterator())

  private val pairKeys: IntArray   // (left id shl 16) or right id, ascending
  private val pairRanks: IntArray  // rank of the pair at the same index in pairKeys
  private val byteIds = IntArray(256)   // id of the symbol for a raw byte, in the middle of a word
  private val cache = HashMap<String, IntArray>(CACHE_LIMIT * 2)

  init {
    val ids = HashMap<String, Int>(N_MERGES * 2)
    for (i in 0 until 256) {
      val ch = symbolChar(i)
      byteIds[BYTE_ORDER[i]] = i
      ids[ch] = i
      ids[ch + END] = 256 + i
    }
    val packed = LongArray(N_MERGES)
    var n = 0
    while (merges.hasNext()) {
      val line = merges.next()
      if (line.isEmpty()) continue
      require(n < N_MERGES) { "more than $N_MERGES merges" }
      val sp = line.indexOf(' ')
      require(sp > 0 && sp < line.length - 1) { "bad merge line $n: '$line'" }
      val left = line.substring(0, sp)
      val right = line.substring(sp + 1)
      val a = requireNotNull(ids[left]) { "merge $n uses a symbol that is not defined yet: '$line'" }
      val b = requireNotNull(ids[right]) { "merge $n uses a symbol that is not defined yet: '$line'" }
      // open_clip would let a repeated string take the later id; the real list never repeats one, so refuse rather than diverge silently
      require(ids.put(left + right, 512 + n) == null) { "merge $n repeats a vocab string: '$line'" }
      packed[n] = (((a shl 16) or b).toLong() shl 32) or n.toLong()
      n++
    }
    require(n == N_MERGES) { "expected $N_MERGES merges, got $n" }
    packed.sort()
    pairKeys = IntArray(N_MERGES) { (packed[it] ushr 32).toInt() }
    pairRanks = IntArray(N_MERGES) { packed[it].toInt() }
    for (i in 1 until N_MERGES) require(pairKeys[i] != pairKeys[i - 1]) { "a merge pair is listed twice" }
  }

  /** Heap held for the life of the object, not counting the word cache. */
  val sizeBytes: Int get() = (pairKeys.size + pairRanks.size + byteIds.size) * 4

  /** [text] as open_clip's tokenizer returns it: exactly [CONTEXT] ids, [SOT], the BPE ids, [EOT], zeros; too long is cut and ends in [EOT]. */
  fun encode(text: String): LongArray {
    val out = LongArray(CONTEXT)
    out[0] = SOT
    var n = 1
    val s = clean(text)
    var i = 0
    // open_clip keeps 75 content ids when the text is longer (77 minus SOT and the forced EOT), so stop reading there
    while (i < s.length && n < CONTEXT - 1) {
      val c = s[i]
      if (c == '<' && s.startsWith(SPECIAL_START, i)) {
        out[n++] = SOT
        i += SPECIAL_START.length
        continue
      }
      if (c == '<' && s.startsWith(SPECIAL_END, i)) {
        out[n++] = EOT
        i += SPECIAL_END.length
        continue
      }
      val cp = s.codePointAt(i)
      var end = i + Character.charCount(cp)
      val contraction = if (c == '\'') contractionLength(s, i) else 0
      when {
        contraction > 0 -> end = i + contraction
        isPySpace(cp) -> { i = end; continue }
        isLetter(cp) -> while (end < s.length) {
          val q = s.codePointAt(end)
          if (!isLetter(q)) break
          end += Character.charCount(q)
        }
        isNumber(cp) -> {}   // one digit at a time, so "4815" is four words
        else -> while (end < s.length) {   // a run of symbols and punctuation, greedy: it swallows a following ' and < too
          val q = s.codePointAt(end)
          if (isPySpace(q) || isLetter(q) || isNumber(q)) break
          end += Character.charCount(q)
        }
      }
      for (id in wordIds(s.substring(i, end))) {
        if (n >= CONTEXT - 1) break
        out[n++] = id.toLong()
      }
      i = end
    }
    out[n] = EOT
    return out
  }

  /** Length of the contraction ('s 't 're 've 'm 'll 'd) starting at [i], or 0. open_clip's IGNORECASE regex also lets U+017F (long s) stand for s. */
  private fun contractionLength(s: String, i: Int): Int {
    if (i + 1 >= s.length) return 0
    val a = s[i + 1]
    if (a == 's' || a == 'ſ' || a == 't' || a == 'm' || a == 'd') return 2
    if (i + 2 >= s.length) return 0
    val b = s[i + 2]
    return if ((a == 'r' && b == 'e') || (a == 'v' && b == 'e') || (a == 'l' && b == 'l')) 3 else 0
  }

  /** The BPE ids of one regex word (cached; the cache is dropped whole when full, which keeps it small and needs no bookkeeping). */
  private fun wordIds(word: String): IntArray {
    synchronized(cache) { cache[word]?.let { return it } }
    val bytes = word.toByteArray(Charsets.UTF_8)
    val sym = IntArray(bytes.size) { byteIds[bytes[it].toInt() and 0xFF] }
    sym[sym.size - 1] += 256   // the last symbol of a word carries </w>
    var len = sym.size
    while (len > 1) {
      var best = Int.MAX_VALUE
      var left = 0
      var right = 0
      for (j in 0 until len - 1) {
        val r = rankOf(sym[j], sym[j + 1])
        if (r >= 0 && r < best) { best = r; left = sym[j]; right = sym[j + 1] }
      }
      if (best == Int.MAX_VALUE) break
      var w = 0
      var j = 0
      while (j < len) {   // merge every non-overlapping occurrence, left to right
        if (j < len - 1 && sym[j] == left && sym[j + 1] == right) { sym[w++] = 512 + best; j += 2 } else sym[w++] = sym[j++]
      }
      len = w
    }
    val ids = sym.copyOf(len)
    synchronized(cache) {
      if (cache.size >= CACHE_LIMIT) cache.clear()
      cache[word] = ids
    }
    return ids
  }

  /** Rank of the merge (left, right), or -1. */
  private fun rankOf(left: Int, right: Int): Int {
    val key = (left shl 16) or right
    var lo = 0
    var hi = pairKeys.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val k = pairKeys[mid]
      if (k == key) return pairRanks[mid]
      if (k < key) lo = mid + 1 else hi = mid - 1
    }
    return -1
  }

  companion object {
    const val ASSET = "clip_bpe_merges.txt.gz"
    const val CONTEXT = 77
    const val SOT = 49406L
    const val EOT = 49407L

    /** 49152 - 256 - 2: the merges open_clip keeps from its 262,144-line file. */
    private const val N_MERGES = 48894
    private const val CACHE_LIMIT = 2048
    private const val END = "</w>"
    private const val SPECIAL_START = "<start_of_text>"
    private const val SPECIAL_END = "<end_of_text>"
    private const val N_PRINTABLE = 188

    /** Reads the merge list built by scripts/build_clip_tokenizer.py: gzipped (the source file) or plain (as the APK packages it). */
    fun fromGzip(stream: InputStream): ClipTokenizer =
      BundledAssets.ungzipIfNeeded(stream).bufferedReader(Charsets.UTF_8).useLines { ClipTokenizer(it.iterator()) }

    /** The 188 printable bytes open_clip's bytes_to_unicode keeps as themselves, then the other 68 in byte order: the order the vocab lists symbols. */
    private val BYTE_ORDER: IntArray = run {
      val printable = (33..126) + (161..172) + (174..255)
      (printable + (0..255).filter { it !in printable }).toIntArray()
    }

    /** The character bytes_to_unicode gives the byte at vocab position [i]: itself when printable, else U+0100 and up. */
    private fun symbolChar(i: Int): String = String(Character.toChars(if (i < N_PRINTABLE) BYTE_ORDER[i] else 256 + (i - N_PRINTABLE)))

    // ---- cleaning: ftfy.fix_text, html.unescape twice, whitespace collapse, lower case (open_clip's clean="lower") ----

    private fun clean(text: String): String {
      return pyLower(collapse(pyUnescape(pyUnescape(ftfy(text)))))
    }

    /**
     * Python's str.lower: the simple lower-case mapping, plus the two special cases (U+0130 becomes i and a combining dot; capital sigma
     * becomes the final form after a cased letter when no cased letter follows). String.lowercase gets the sigma rule wrong when a digit
     * or mark follows, so it is spelled out here.
     */
    private fun pyLower(s: String): String {
      val sb = StringBuilder(s.length + 4)
      var i = 0
      while (i < s.length) {
        val cp = s.codePointAt(i)
        when {
          cp == 0x130 -> sb.append("i\u0307")
          cp == 0x3a3 -> sb.append(if (isFinalSigma(s, i)) '\u03c2' else '\u03c3')
          else -> sb.appendCodePoint(Character.toLowerCase(cp))
        }
        i += Character.charCount(cp)
      }
      return sb.toString()
    }

    private fun isFinalSigma(s: String, at: Int): Boolean {
      var before = false
      var i = at
      while (i > 0) {   // a cased letter before, skipping case-ignorable marks and apostrophes
        val cp = s.codePointBefore(i)
        i -= Character.charCount(cp)
        if (isCaseIgnorable(cp)) continue
        before = isCased(cp)
        break
      }
      if (!before) return false
      i = at + 1
      while (i < s.length) {   // and none after
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        if (isCaseIgnorable(cp)) continue
        return !isCased(cp)
      }
      return true
    }

    private fun isCased(cp: Int): Boolean = Character.isLowerCase(cp) || Character.isUpperCase(cp) || Character.isTitleCase(cp)

    /** Unicode Case_Ignorable: marks, format characters, modifier letters and symbols, and the word-internal punctuation (' . : middle dot ...). */
    private fun isCaseIgnorable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
      Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.MODIFIER_LETTER, Character.MODIFIER_SYMBOL -> true
      else -> cp == 0x27 || cp == 0x2e || cp == 0x3a || cp == 0xb7 || cp == 0x387 || cp == 0x55f || cp == 0x5f4 || cp == 0x2018 || cp == 0x2019 ||
        cp == 0x2024 || cp == 0x2027 || cp == 0xfe13 || cp == 0xfe52 || cp == 0xfe55 || cp == 0xff07 || cp == 0xff0e || cp == 0xff1a
    }

    /** Python's str.isspace, which is what str.split() and strip() break on. */
    private fun isPySpace(c: Int): Boolean = (c in 9..13) || (c in 0x1c..0x20) || c == 0x85 || c == 0xa0 || c == 0x1680 ||
      (c in 0x2000..0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000

    /** " ".join(text.split()) */
    private fun collapse(s: String): String {
      val sb = StringBuilder(s.length)
      var i = 0
      var pending = false
      while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        if (isPySpace(cp)) { pending = sb.isNotEmpty(); continue }
        if (pending) { sb.append(' '); pending = false }
        sb.appendCodePoint(cp)
      }
      return sb.toString()
    }

    /** The regex's \p{L}: Lu Ll Lt Lm Lo. */
    private fun isLetter(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
      Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER -> true
      else -> false
    }

    /** The regex's \p{N}: Nd Nl No. */
    private fun isNumber(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
      Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
      else -> false
    }

    // ---- the ftfy steps that apply to typed or pasted text ----

    /** U+0080..U+009F as Windows-1252 reads them (the five undefined ones stay as they are, ftfy's "sloppy" variant). */
    private const val CP1252_C1 = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f" +
      "\u0090‘’“”•–—˜™š›œ\u009džŸ"

    private val LIGATURES = mapOf(
      'Ĳ' to "IJ", 'ĳ' to "ij", 'ŉ' to "ʼn", 'Ǳ' to "DZ", 'ǲ' to "Dz", 'ǳ' to "dz", 'Ǆ' to "DŽ", 'ǅ' to "Dž", 'ǆ' to "dž",
      'Ǉ' to "LJ", 'ǈ' to "Lj", 'ǉ' to "lj", 'Ǌ' to "NJ", 'ǋ' to "Nj", 'ǌ' to "nj",
      'ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl", 'ﬅ' to "ſt", 'ﬆ' to "st",
    )

    /** U+FF01..U+FFEF where NFKC changes the character (full-width ASCII, half-width katakana and hangul), as ftfy builds its width map. */
    private val WIDTH: Map<Char, String> by lazy {
      val m = HashMap<Char, String>()
      m['　'] = " "
      for (c in 0xFF01 until 0xFFF0) {
        val s = c.toChar().toString()
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC)
        if (n != s) m[c.toChar()] = n
      }
      m
    }

    private val ANSI = Regex("\u001b\\[[\\p{Nd};]*[a-zA-Z]")
    private val FTFY_ENTITY = Regex("&#?[0-9A-Za-z]{1,24};")

    /** ftfy.fix_text with fix_encoding left out: line by line, because ftfy stops decoding HTML for good once a line contains `<`. */
    private fun ftfy(text: String): String {
      val out = StringBuilder(text.length)
      var unescape = true
      var pos = 0
      while (pos < text.length) {
        var brk = text.indexOf('\n', pos) + 1
        if (brk == 0) brk = text.length
        val seg = text.substring(pos, brk)
        if (unescape && seg.indexOf('<') >= 0) unescape = false
        out.append(fixSegment(seg, unescape))
        pos = brk
      }
      return out.toString()
    }

    private fun fixSegment(seg: String, unescape: Boolean): String {
      if (seg.all { (it in ' '..'~' && (it != '&' || !unescape)) || it == '\n' || it == '\t' }) return seg   // plain ASCII: no step changes it
      var text = seg
      while (true) {
        val before = text
        if (unescape) text = FTFY_ENTITY.replace(text) { ftfyEntity(it.value) }
        text = fixC1(text)
        text = mapChars(text) { LIGATURES[it] }
        text = mapChars(text) { WIDTH[it] }
        text = mapChars(text) { if (it in 'ʼ'..'ʼ' || it in '‘'..'‛') "'" else if (it in '“'..'‟') "\"" else null }
        text = text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', '\n').replace(' ', '\n').replace('\u0085', '\n')
        text = fixSurrogates(text)
        text = ANSI.replace(text, "")
        text = mapChars(text) { if (isFtfyControl(it.code)) "" else null }
        text = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (text == before) return text
      }
    }

    private inline fun mapChars(s: String, f: (Char) -> String?): String {
      var sb: StringBuilder? = null
      for (i in s.indices) {
        val r = f(s[i])
        if (r != null && sb == null) sb = StringBuilder(s.length + 8).append(s, 0, i)
        if (sb != null) sb.append(r ?: s[i])
      }
      return sb?.toString() ?: s
    }

    private fun fixC1(s: String): String = mapChars(s) { if (it in '\u0080'..'\u009f') CP1252_C1[it.code - 0x80].toString() else null }

    private fun fixSurrogates(s: String): String {
      var sb: StringBuilder? = null
      var i = 0
      while (i < s.length) {
        val c = s[i]
        val paired = Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])
        if (paired) {
          sb?.append(c)?.append(s[i + 1])
          i += 2
          continue
        }
        if (Character.isSurrogate(c)) {
          if (sb == null) sb = StringBuilder(s.length).append(s, 0, i)
          sb.append('�')
        } else sb?.append(c)
        i++
      }
      return sb?.toString() ?: s
    }

    /** ftfy.remove_control_chars: C0 controls except tab, LF, FF, CR; DEL; the deprecated Arabic controls; BOM; interlinear annotation. */
    private fun isFtfyControl(c: Int): Boolean = (c in 0x00..0x08) || c == 0x0b || (c in 0x0e..0x1f) || c == 0x7f ||
      (c in 0x206a..0x206f) || c == 0xfeff || (c in 0xfff9..0xfffc)

    // ---- HTML entities: ftfy's `&name;` pass, then Python's html.unescape ----

    private val LATIN1 = ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute micro para " +
      "middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml " +
      "Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave " +
      "aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde " +
      "ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml").split(' ')

    /** HTML5 names (as written before the `;`) this port decodes. U+00A0.. for LATIN1 in order, then the rest; the upper-case spellings AMP LT
     *  GT QUOT COPY REG TRADE are real HTML5 entities of their own. */
    private val ENTITIES: Map<String, String> by lazy {
      val m = HashMap<String, String>()
      LATIN1.forEachIndexed { i, n -> m[n] = (0xA0 + i).toChar().toString() }
      val more = mapOf(
        "amp" to "&", "AMP" to "&", "lt" to "<", "LT" to "<", "gt" to ">", "GT" to ">", "quot" to "\"", "QUOT" to "\"", "COPY" to "©",
        "REG" to "®", "apos" to "'", "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "bdquo" to "„", "bull" to "•", "euro" to "€",
        "trade" to "™", "TRADE" to "™", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "hearts" to "♥", "checkmark" to "✓", "check" to "✓", "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
        "lsaquo" to "‹", "rsaquo" to "›", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "‌",
        "zwj" to "‍", "infin" to "∞", "ne" to "≠", "le" to "≤", "ge" to "≥", "minus" to "−", "star" to "☆",
        "sum" to "∑", "pi" to "π",
      )
      m.putAll(more)
      m
    }

    /** Names HTML5 also accepts without the closing `;`. */
    private val LEGACY: Set<String> by lazy { (LATIN1 + listOf("amp", "lt", "gt", "quot", "AMP", "LT", "GT", "QUOT", "COPY", "REG")).toHashSet() }

    /** HTML5 names whose upper-case spelling is itself an entity, so ftfy does not add an upper-cased variant for the lower-case one. */
    private val REAL_UPPER = setOf("COPY", "REG", "ETH", "THORN", "AMP", "LT", "GT", "QUOT", "TRADE")

    /** One `&...;` match of ftfy's pass: the entity, its upper-cased-name variant (`&NTILDE;` is N with tilde), or the text unchanged. */
    private fun ftfyEntity(m: String): String {
      val name = m.substring(1, m.length - 1)
      ENTITIES[name]?.let { return it }
      if (name.startsWith("#")) {
        val u = pyUnescape(m)
        return if (u.indexOf(';') >= 0) m else u   // html.unescape decoded only part of it: leave the whole reference
      }
      if (name == name.uppercase() && name !in REAL_UPPER) {   // ftfy also decodes &NTILDE; for every lower-case name whose upper-case spelling is not real
        val base = ENTITIES[name.lowercase()]
        if (base != null) return base.uppercase()
      }
      return m
    }

    private val CHARREF = Regex("&(#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\\t\\n\\u000c <&#;]{1,32};?)")

    /** Python's html.unescape, over the entity table above. */
    private fun pyUnescape(s: String): String {
      if (s.indexOf('&') < 0) return s
      return CHARREF.replace(s) { m ->
        val ref = m.groupValues[1]
        if (ref[0] == '#') numericRef(ref) else namedRef(ref) ?: m.value
      }
    }

    private fun namedRef(ref: String): String? {
      val semi = ref.endsWith(";")
      val name = if (semi) ref.dropLast(1) else ref
      val whole = if (semi) ENTITIES[name] else if (name in LEGACY) ENTITIES[name] else null
      if (whole != null) return whole
      for (x in ref.length - 1 downTo 2) {   // longest legacy name that prefixes it: "&notit;" is "¬it;"
        val p = ref.substring(0, x)
        if (p in LEGACY) return ENTITIES.getValue(p) + ref.substring(x)
      }
      return null
    }

    private fun numericRef(ref: String): String {
      val hex = ref[1] == 'x' || ref[1] == 'X'
      val digits = ref.substring(if (hex) 2 else 1).trimEnd(';').trimStart('0')
      val num = if (digits.length > (if (hex) 8 else 10)) Long.MAX_VALUE else if (digits.isEmpty()) 0L else digits.toLong(if (hex) 16 else 10)
      if (num == 0L) return "�"
      if (num == 0x0dL) return "\r"
      if (num in 0x80..0x9f) return CP1252_C1[(num - 0x80).toInt()].toString()
      if (num in 0xd800..0xdfff || num > 0x10ffff) return "�"
      val c = num.toInt()
      val invalid = (c in 1..8) || (c in 0xe..0x1f) || (c in 0x7f..0x9f) || (c in 0xfdd0..0xfdef) || c == 0xb || (c and 0xfffe) == 0xfffe
      return if (invalid) "" else String(Character.toChars(c))
    }
  }
}
