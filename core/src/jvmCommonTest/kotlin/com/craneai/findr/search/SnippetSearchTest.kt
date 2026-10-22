package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SearchResults
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A result's snippet never starts or ends inside a word, through the real store and searcher. Most of the index here is built the way
 * an older one was: a file's chunks cut by character count, so a continuation chunk can start in the middle of a word (the bug seen
 * on the desktop: "h's notice in writing" for "one month's notice in writing").
 */
class SnippetSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(3)
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-snippets").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
    // other files on other topics, so the file under test is one result among several as in a real index
    repeat(8) { save("filler$it.txt", listOf("nothing to do with it, filler number $it"), 0, 20 + it) }
  }

  @After fun tearDown() { vectors.close(); store.close(); dir.deleteRecursively() }

  private fun unit(raw: FloatArray): FloatArray { val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(raw.size) { raw[it] / n } }
  private fun noise() = unit(FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() })
  private fun topic(k: Int) = FloatArray(Fp16.DIM) { if (it == k) 1f else 0f }

  /** The cutter of an older index: a piece starts exactly 60 characters before the end of the one before it. */
  private fun legacyChunks(text: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      var end = minOf(text.length, start + 400)
      if (end < text.length) { val space = text.lastIndexOf(' ', end).takeIf { it > start + 200 }; if (space != null) end = space }
      out += text.substring(start, end)
      if (end >= text.length) break
      start = maxOf(end - 60, start + 1)
    }
    return out
  }

  private fun leaseWith(pad: Int) = "Lease agreement. " + "The tenant keeps the flat clean and pays rent monthly. ".repeat(4) + "x".repeat(pad) + " " +
    "Either party may end this agreement with one month's notice in writing. No sub-letting without the landlord's consent. Keys are returned on the last day."

  /** A lease whose second legacy piece starts inside "month's". */
  private fun lease(): String =
    (0..80).map(::leaseWith).first { legacyChunks(it).size >= 2 && legacyChunks(it)[1].startsWith("h's notice") }

  private fun addFile(name: String, kind: FileKind = FileKind.DOCUMENT): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, kind, null, 1000, 1L, dateMillis = 1L)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  /** [pieces] saved as one chunk segment each; the piece at [hot] has the topic vector [k], the others are noise. */
  private fun save(name: String, pieces: List<String>, hot: Int, k: Int): Long {
    val file = addFile(name)
    val vecs = pieces.indices.map { if (it == hot) topic(k) else noise() }
    val ids = store.saveSegments(file, pieces.mapIndexed { i, p -> Segment(SegmentKind.CHUNK, Modality.TEXT, text = p, vector = vecs[i]) }, FileState.UNDERSTOOD)
    ids.forEachIndexed { i, id -> vectors.add(id, file, FileKind.DOCUMENT, Modality.TEXT, false, vecs[i]) }
    return file
  }

  private fun snippetOf(r: SearchResults, file: Long) = r.hits.first { it.file.id == file }.snippet

  private fun plain(snippet: String) = snippet.removePrefix("…").removeSuffix("…").replace("«", "").replace("»", "")

  /** The shown words begin and end on a word of [text]. */
  private fun assertWholeWords(text: String, snippet: String) {
    val p = plain(snippet)
    val at = text.indexOf(p)
    assertTrue("'$snippet' is a stretch of the text", at >= 0)
    val before = if (at == 0) ' ' else text[at - 1]
    val after = if (at + p.length >= text.length) ' ' else text[at + p.length]
    assertFalse("'$snippet' starts inside a word", (before.isLetterOrDigit() || before == '\'') && p.first().isLetterOrDigit())
    assertFalse("'$snippet' ends inside a word", (after.isLetterOrDigit() || after == '\'') && p.last().isLetterOrDigit())
  }

  // ---- layer 1: the display ------------------------------------------------------------------------------------------

  @Test fun theLeaseSnippetByMeaningStartsAtAWholeWord() {
    val text = lease()
    val pieces = legacyChunks(text)
    assertTrue(pieces[1].startsWith("h's notice"))
    val file = save("lease-agreement.txt", pieces, hot = 1, k = 7)
    val r = searcher.search("termination clause", topic(7), KindFilter.ALL, 50)
    assertEquals("…" + pieces[1].substringAfter(' '), snippetOf(r, file))
  }

  @Test fun everySnippetOfTheLeaseIsWholeWordsWhateverIsAsked() {
    val text = lease()
    val pieces = legacyChunks(text)
    val file = save("lease-agreement.txt", pieces, hot = 1, k = 7)
    var checked = 0
    for (q in listOf("notice", "month", "months notice", "writing", "sub-letting", "landlord", "consent keys", "last day", "agreement", "tenant keeps", "rent monthly")) {
      for (vec in listOf(null, topic(7))) {
        val hit = searcher.search(q, vec, KindFilter.ALL, 50).hits.firstOrNull { it.file.id == file } ?: continue
        val s = hit.snippet ?: continue
        assertWholeWords(text, s)
        checked++
      }
    }
    assertTrue("$checked snippets checked", checked >= 10)
  }

  @Test fun theMatchedWordsStayMarkedWhereTheyWere() {
    val text = lease()
    val pieces = legacyChunks(text)
    val file = save("lease-agreement.txt", pieces, hot = 1, k = 7)
    val s = snippetOf(searcher.search("landlord consent", null, KindFilter.ALL, 50), file)
    assertNotNull(s)
    assertEquals(s, setOf("landlord's", "consent"), Regex("«([^»]+)»").findAll(s!!).map { it.groupValues[1].lowercase() }.toSet().let { found ->
      if ("landlord" in found) found - "landlord" + "landlord's" else found
    })
    assertWholeWords(text, s)
  }

  @Test fun aWordIndexExcerptNeverStartsOnTheSOfMonths() {
    val text = "Please note that the notice period is one month's notice in writing to the landlord, and the deposit is returned afterwards."
    val file = save("terms.txt", listOf(text), 0, 13)
    for (q in listOf("notice writing", "month", "notice", "writing landlord", "deposit afterwards")) {
      val s = snippetOf(searcher.search(q, null, KindFilter.ALL, 50), file) ?: continue
      assertWholeWords(text, s)
      assertFalse(s, s.removePrefix("…").startsWith("s notice"))
    }
  }

  @Test fun aChunkThatHappensToStartCleanIsNotTrimmed() {
    // an older index whose second piece begins exactly at a word: nothing is cut and no ellipsis is added
    val text = (0..80).map(::leaseWith).first { t ->
      val p = legacyChunks(t)
      p.size >= 2 && t.indexOf(p[1]).let { it > 0 && t[it - 1] == ' ' } && p[1].first().isLetter()
    }
    val pieces = legacyChunks(text)
    val file = save("clean.txt", pieces, hot = 1, k = 5)
    val s = snippetOf(searcher.search("termination clause", topic(5), KindFilter.ALL, 50), file)
    assertEquals(pieces[1], s)
  }

  @Test fun theFirstPieceKeepsItsStart() {
    val text = lease()
    val pieces = legacyChunks(text)
    val file = save("lease-agreement.txt", pieces, hot = 0, k = 7)
    val s = snippetOf(searcher.search("rental income", topic(7), KindFilter.ALL, 50), file)
    assertTrue(s, s!!.startsWith("Lease agreement."))
  }

  @Test fun aPieceFromAnotherPageIsNotMistakenForAContinuation() {
    // two unrelated pieces (as pages are): the second starts with a lower-case word that is simply its first word
    val file = save("pages.txt", listOf("Page one is about rent and keys and the flat that is let by the month " + "x ".repeat(40), "tenants pay on the first day of the month " + "y ".repeat(40)), hot = 1, k = 4)
    val s = snippetOf(searcher.search("settlement schedule", topic(4), KindFilter.ALL, 50), file)!!
    assertTrue(s, s.startsWith("tenants pay on the first day"))
  }

  @Test fun noSpaceTextIsShownAsItIs() {
    val cjk = "我们在这里学习语言和文字".repeat(30)
    val pieces = legacyChunks(cjk)
    val file = save("中文.txt", pieces, hot = 1, k = 3)
    val s = snippetOf(searcher.search("study of speech", topic(3), KindFilter.ALL, 50), file)!!
    assertFalse(s, s.startsWith("…"))
  }
}
