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
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * The Luganda/Swahili -> English keyword table ([LocalKeywords]; internal/research/languages-keywords-1.0.6.md): a Luganda or Swahili
 * query word also finds the English file that says it, through the app's own store and [Searcher]; an English query is not touched.
 */
class LocalKeywordsTest {
  private val tableText = """
    # a comment line
    lg	lisiiti	receipt
    lg	ddwaliro	hospital
    lg	eddwaliro	hospital
    lg	ebifaananyi	pictures photos
    lg	baba	energetic
    lg	fiizi	fees
    lg	essomero	school
    lg	abasawo	doctors
    sw	risiti	receipt
    sw	hospitali	hospital hospitals
    sw	baba	father
    sw	ada	fees
    sw	shule	school schools
    sw	ng'ombe	cow cattle
    sw	ombe	pray
    sw	shambani	farm
    lg	bad	not_a_word!
  """.trimIndent()

  private fun table(gzip: Boolean): LocalKeywords {
    val raw = tableText.toByteArray()
    val bytes = if (!gzip) raw else ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
    return LocalKeywords.parse(ByteArrayInputStream(bytes))
  }

  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private var nextId = 1L
  private var loads = 0
  private val lazyTable = lazy { loads++; table(gzip = true) }

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-kw").toFile()
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

  private fun names(searcher: Searcher, q: String) = searcher.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name }

  @Test fun parsesGzippedAndPlainAlikeAndSkipsBadLines() {
    for (gz in listOf(true, false)) {
      val t = table(gz)
      assertEquals(16, t.size)   // the comment and the line whose English is not a word are skipped
      assertEquals(listOf(listOf("receipt")), t.english(listOf(listOf("lisiiti"))))
    }
  }

  @Test fun theQueryLanguageIsTheTableThatKnowsMoreOfItsWords() {
    val t = table(gzip = false)
    // Swahili: "hospitali" and "baba" are Swahili words, so "baba" is "father" and not Luganda's "energetic"
    assertEquals(listOf(listOf("hospital", "hospitals"), listOf("father")), t.english(listOf(listOf("hospitali"), listOf("baba"))))
    // Luganda: "ddwaliro" decides it
    assertEquals(listOf(listOf("hospital"), listOf("energetic")), t.english(listOf(listOf("ddwaliro"), listOf("baba"))))
    // A tie uses both, Luganda's first
    assertEquals(listOf(listOf("energetic", "father")), t.english(listOf(listOf("baba"))))
    // Nothing known: empty lists, one per word
    assertEquals(listOf(emptyList<String>(), emptyList()), t.english(listOf(listOf("kampala"), listOf("2026"))))
  }

  @Test fun anyNounClassFormOfTheWordFindsItsEntry() {
    val t = table(gzip = false)
    // "ekifaananyi" (a picture) is not in the table; its plural "ebifaananyi" is
    assertEquals(listOf(listOf("pictures", "photos")), t.english(listOf(Fusion.nounClassForms("ekifaananyi"))))
  }

  @Test fun aLugandaQueryFindsTheEnglishFileThatSaysIt() {
    doc("Nsambya receipt.txt", "Nsambya Hospital receipt, outpatient consultation UGX 50,000")
    doc("ebyafaayo.txt", "ebyafaayo bya Buganda ne Kabaka")
    val without = Searcher(store, vectors)
    val with = Searcher(store, vectors, keywords = lazyTable)
    assertEquals(emptyList<String>(), names(without, "lisiiti ya ddwaliro"))
    assertEquals(listOf("Nsambya receipt.txt"), names(with, "lisiiti ya ddwaliro"))
    assertEquals(listOf("Nsambya receipt.txt"), names(with, "risiti ya hospitali"))   // Swahili
  }

  @Test fun anEnglishWordCountsAsTheLocalWordItStandsForNotAsAnExtraWord() {
    doc("a.txt", "fees for the school term one")
    doc("b.txt", "fiizi z'essomero")
    doc("c.txt", "fees for term one")
    val with = Searcher(store, vectors, keywords = lazyTable)
    val hits = with.search("fiizi za essomero", null, KindFilter.ALL, 50).hits.associate { it.file.name to it.score }
    // a and b hold both words, one file in English and one in Luganda: the same coverage, the same score. c holds one of the two words:
    // half, as it would be for a query of two English words, not more because "fees" came from the table.
    assertEquals(hits.getValue("a.txt"), hits.getValue("b.txt"), 1e-9)
    assertEquals(Fusion.score(null, 0.5, lookup = false, hinted = false), hits.getValue("c.txt"), 1e-9)
  }

  @Test fun anEnglishQueryNeverReadsTheTableAndRanksAsBefore() {
    doc("Nsambya receipt.txt", "Nsambya Hospital receipt, outpatient consultation UGX 50,000")
    doc("fees.txt", "school fees for term one at the hospital school")
    val without = Searcher(store, vectors)
    val with = Searcher(store, vectors, keywords = lazyTable)
    for (q in listOf("hospital receipt", "school fees", "father", "photos of the receipt", "baba")) {
      assertEquals(q, without.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name to it.score },
        with.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name to it.score })
    }
    assertFalse(lazyTable.isInitialized())
    assertEquals(0, loads)
  }

  @Test fun theGlossIsTheQueryWordByWordInEnglish() {
    val t = table(gzip = false)
    // grammar words ("ya") and single letters ("e") go, known words become English, names stay as typed (lower case)
    assertEquals("receipt hospital nsambya", t.gloss("lisiiti ya ddwaliro e Nsambya"))
    // a picture word becomes "photo"; a word in another noun-class form finds its entry ("omusawo", a doctor: "abasawo" is listed)
    assertEquals("photo receipt", t.gloss("ekifaananyi kya lisiiti"))
    assertEquals("doctors hospital", t.gloss("omusawo w'eddwaliro"))
    assertEquals("fees school schools 2026", t.gloss("ada ya shule 2026"))
    // nothing the table knows: no gloss
    assertEquals(null, t.gloss("ssente za Kampala"))
  }

  @Test fun onlyALugandaOrSwahiliQueryIsEmbeddedWithItsGloss() {
    assertEquals("lisiiti ya ddwaliro receipt hospital", LocalKeywords.embedText("lisiiti ya ddwaliro", lazyTable))
    assertEquals("ssente za Kampala", LocalKeywords.embedText("ssente za Kampala", lazyTable))   // no known word: as typed
    assertEquals("lisiiti ya ddwaliro", LocalKeywords.embedText("lisiiti ya ddwaliro", null))      // no table: as typed
    val fresh = lazy { loads++; table(gzip = false) }
    for (q in listOf("hospital receipt", "school fees 2026", "baba", "photos of the wedding")) assertEquals(q, LocalKeywords.embedText(q, fresh))
    assertFalse(fresh.isInitialized())
  }

  @Test fun aSwahiliApostropheWordIsOneWordHoweverTheKeyboardTypesTheApostrophe() {
    val t = table(gzip = false)
    // plain, curly, opening-curly and backtick apostrophes: all "ng'ombe" (cow), never "ng" + "ombe" ("ombe" alone is "pray")
    for (a in listOf("'", "\u2019", "\u2018", "`")) {
      assertEquals(a, listOf("picha", "za", "ng'ombe", "shambani"), t.words("Picha za ng${a}ombe shambani"))
      assertEquals(a, listOf("ng'ombe", "shambani"), t.terms("picha za ng${a}ombe shambani"))
      assertEquals(a, "photo cow cattle farm", t.gloss("picha za ng${a}ombe shambani"))
    }
    // Luganda elision is two words: a word the table does not hold whole splits exactly as Fusion.words splits it
    for (q in listOf("fiizi z'essomero", "omusawo w\u2019eddwaliro", "n`abantu ba Kampala", "ng'ombe", "don't stop", "mother's day 2026",
      "'quoted' words", "ng''ombe", "a'b", "ng'", "'ombe", "IMG_2026_receipt", "ekifaananyi\u02BCkya")) {
      val plain = LocalKeywords(emptyMap(), emptyMap())
      assertEquals(q, Fusion.words(q), plain.words(q))
      assertEquals(q, Fusion.terms(q), plain.terms(q))
      if ("ng" !in q) assertEquals(q, Fusion.words(q), t.words(q))
    }
    assertEquals(listOf("doctors", "hospital"), t.gloss("omusawo w\u2019eddwaliro")?.split(" "))
  }

  @Test fun aSwahiliApostropheQueryFindsTheEnglishFileAndTheSwahiliOneAndEnglishIsUntouched() {
    doc("cows.txt", "two cows on the farm near Mbarara")
    doc("ngombe.txt", "ng'ombe wawili shambani")
    doc("prayer.txt", "prayer meeting on Sunday")
    doc("mothers.txt", "mother's day lunch, don't forget")
    val without = Searcher(store, vectors)
    val with = Searcher(store, vectors, keywords = lazyTable)
    for (a in listOf("'", "\u2019", "`")) {
      val hits = with.search("picha za ng${a}ombe shambani", null, KindFilter.ALL, 50).hits.associate { it.file.name to it.score }
      // the English file holds both words (cow, farm) and the Swahili one both too (the phrase "ng ombe", "shambani"): the same coverage
      assertEquals(a, setOf("cows.txt", "ngombe.txt"), hits.keys)
      assertEquals(a, hits.getValue("cows.txt"), hits.getValue("ngombe.txt"), 1e-9)
    }
    // without the table the Swahili file is still found as before (the index splits "ng'ombe" the same way), the English one is not
    assertEquals(listOf("ngombe.txt"), names(without, "picha za ng'ombe shambani"))
    // English queries with apostrophes: the same results, and the table is never read
    val fresh = lazy { loads++; table(gzip = false) }
    val withFresh = Searcher(store, vectors, keywords = fresh)
    for (q in listOf("mother's day", "don\u2019t forget", "farmer`s cows")) {
      assertEquals(q, without.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name to it.score },
        withFresh.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name to it.score })
      assertEquals(q, q, LocalKeywords.embedText(q, fresh))
    }
    assertFalse(fresh.isInitialized())
  }

  @Test fun noTableOrAnUnreadableOneIsTheOldSearch() {
    doc("Nsambya receipt.txt", "Nsambya Hospital receipt")
    val broken = Searcher(store, vectors, keywords = lazy<LocalKeywords?> { null })
    assertEquals(emptyList<String>(), names(broken, "lisiiti ya ddwaliro"))
  }
}
