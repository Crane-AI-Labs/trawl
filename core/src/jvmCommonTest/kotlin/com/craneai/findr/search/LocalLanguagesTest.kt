package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * The other local languages of the keyword table ([LocalLanguage]: Runyankole, Kinyarwanda, Acholi;
 * internal/research/languages-keywords-more.md): a query is read as one of them by its grammar words and what the table knows, its words
 * also find the English file that says them, and Luganda, Swahili and English queries are searched exactly as before.
 */
class LocalLanguagesTest {
  private val tableText = """
    lg	lisiiti	receipt
    lg	ddwaliro	hospital
    lg	omu	one
    lg	engiri	warthog
    sw	risiti	receipt
    sw	hospitali	hospital
    nyn	engiri	gospel
    nyn	ishomero	school
    nyn	sente	money
    kin	ubukwe	wedding
    kin	ibitaro	hospital
    kin	inka	cow cattle
    ach	cal	picture
    ach	ot	house
    ach	yat	medicine
    teo	aisomo	education
    xx	ignored	never
  """.trimIndent()

  private fun table() = LocalKeywords.parse(ByteArrayInputStream(tableText.toByteArray()))

  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private var nextId = 1L
  private var loads = 0
  private val lazyTable = lazy { loads++; table() }

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-ll").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
  }

  @After fun tearDown() { vectors.close(); store.close(); dir.deleteRecursively() }

  private fun doc(name: String, text: String) {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, FileKind.DOCUMENT, null, 1000, 1)))
    val f = store.knownMediaStoreIds("external_primary").getValue(id)
    store.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.NONE, text = text)), FileState.SEARCHABLE)
  }

  private fun hits(s: Searcher, q: String) = s.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name to it.score }

  @Test fun everyGrammarWordIsLowerCaseLettersAndNoneIsALugandaOrSwahiliOnlyListChange() {
    for (l in LocalLanguage.entries) {
      assertTrue(l.name, l.grammar.size >= 30)
      for (w in l.grammar) assertTrue("$l $w", w.length >= 2 && w.all { it in 'a'..'z' })
      assertEquals(l, LocalLanguage.of(l.code))
    }
    assertNull(LocalLanguage.of("lgg"))   // Lugbara: no licence-clean source, so no list and no table
    assertNull(LocalLanguage.of("teo"))   // Ateso: measured, did not help, left out (its table lines are skipped)
  }

  @Test fun theLanguageIsTheOneWhoseGrammarWordsAndTableKnowMoreOfTheQuery() {
    val t = table()
    // Kinyarwanda: "ya", "mu" are Luganda/Swahili grammar words too, but the Kinyarwanda table knows "ubukwe" and "ibitaro"
    assertEquals(LocalLanguage.KIN, t.languageOf("ubukwe bwo mu ibitaro"))
    // Runyankole: "omu" and "aha" are its grammar words; "engiri" is the gospel, not Luganda's warthog
    assertEquals(LocalLanguage.NYN, t.languageOf("okushoma engiri omu ishomero aha Sunday"))
    // Acholi: "pa" is also a Swahili grammar word; the Acholi table knows "cal", "ot", "yat"
    assertEquals(LocalLanguage.ACH, t.languageOf("cal pa ot yat"))
    // Luganda/Swahili: null, as before (a tie goes to them)
    assertNull(t.languageOf("lisiiti ya ddwaliro"))
    assertNull(t.languageOf("risiti ya hospitali"))
    // English: no local grammar word at all
    for (q in listOf("hospital receipt", "photos of the wedding", "school fees 2026", "cal of the house")) assertNull(q, t.languageOf(q))
    // a grammar word the table has nothing to add to never routes a query ("ryo" is Kinyarwanda; nothing else here is)
    assertNull(t.languageOf("okinawa goju ryo dojo"))
  }

  @Test fun theGlossComesFromTheQuerysLanguageWithoutItsGrammarWords() {
    val t = table()
    assertEquals("gospel school sunday", t.gloss("engiri omu ishomero aha Sunday"))
    assertEquals("wedding hospital", t.gloss("ubukwe bwo mu ibitaro"))
    assertEquals("picture house medicine", t.gloss("cal pa ot yat"))
    assertEquals("receipt hospital nsambya", t.gloss("lisiiti ya ddwaliro e Nsambya"))   // Luganda as before
    assertNull(t.gloss("hospital receipt"))
    assertEquals("ubukwe bwo mu ibitaro wedding hospital", LocalKeywords.embedText("ubukwe bwo mu ibitaro", lazyTable))
  }

  @Test fun aQueryInAnotherLocalLanguageFindsTheEnglishFile() {
    doc("wedding at the hospital.txt", "wedding photos at the hospital chapel")
    doc("other.txt", "the market price of maize")
    val without = Searcher(store, vectors)
    val with = Searcher(store, vectors, keywords = lazyTable)
    assertEquals(emptyList<String>(), hits(without, "ubukwe bwo mu ibitaro").map { it.first })
    assertEquals(listOf("wedding at the hospital.txt"), hits(with, "ubukwe bwo mu ibitaro").map { it.first })
  }

  @Test fun englishLugandaAndSwahiliQueriesRankAsWithTheLugandaSwahiliTableAlone() {
    doc("Nsambya receipt.txt", "Nsambya Hospital receipt, outpatient consultation UGX 50,000")
    doc("fees.txt", "school fees for term one at the hospital school")
    doc("cal.txt", "a cal of the house")
    val lgswOnly = lazy<LocalKeywords?> { LocalKeywords.parse(ByteArrayInputStream(tableText.lines().filter { it.startsWith("lg\t") || it.startsWith("sw\t") }.joinToString("\n").toByteArray())) }
    val old = Searcher(store, vectors, keywords = lgswOnly)
    val fresh = lazy { loads++; table() }
    val new = Searcher(store, vectors, keywords = fresh)
    for (q in listOf("hospital receipt", "school fees", "cal of the house", "photos of the receipt", "baba")) {
      assertEquals(q, hits(old, q), hits(new, q))
      assertEquals(q, q, LocalKeywords.embedText(q, fresh))
    }
    assertFalse(fresh.isInitialized())
    for (q in listOf("lisiiti ya ddwaliro", "risiti ya hospitali", "fiizi z'essomero")) assertEquals(q, hits(old, q), hits(new, q))
  }

  @Test fun noTableIsTheOldSearch() {
    doc("wedding at the hospital.txt", "wedding photos at the hospital chapel")
    val broken = Searcher(store, vectors, keywords = lazy<LocalKeywords?> { null })
    assertEquals(hits(Searcher(store, vectors), "ubukwe bwo mu ibitaro"), hits(broken, "ubukwe bwo mu ibitaro"))
  }
}
