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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Luganda and Swahili queries (internal/research/languages-text-1.0.6.md): their grammar words are not search words, and a Luganda word
 * also finds its other noun-class forms. English queries are left as they were.
 */
class LocalLanguageSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-local").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun tearDown() { vectors.close(); store.close(); dir.deleteRecursively() }

  /** A document with words only (its meaning not learned yet), so the word side alone decides. */
  private fun doc(name: String, text: String) {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, FileKind.DOCUMENT, null, 1000, 1)))
    val f = store.knownMediaStoreIds("external_primary").getValue(id)
    store.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.NONE, text = text)), FileState.SEARCHABLE)
  }

  private fun names(q: String) = searcher.search(q, null, KindFilter.ALL, 50).hits.map { it.file.name }

  @Test fun grammarWordsAreNotSearchWords() {
    assertEquals(listOf("ssente", "nasaasaanya", "wallet", "essimu", "august"), Fusion.terms("ssente mmeka ze nasaasaanya ku wallet y’essimu mu august"))
    assertEquals(listOf("kahawa", "shamba"), Fusion.terms("picha ya kahawa kwenye shamba langu"))   // "picha" (photo) is a kind word
  }

  @Test fun pictureAndMessageWordsAreKindHintsAsInEnglish() {
    assertEquals(setOf(FileKind.IMAGE), Fusion.kindHints("picha ya harusi"))
    assertEquals(setOf(FileKind.IMAGE), Fusion.kindHints("ebifaananyi by'embaga"))
    assertEquals(listOf("harusi"), Fusion.terms("picha ya harusi"))         // "picha" is "photo": not a search word, as "photo" is not
    assertEquals(setOf(FileKind.SCREENSHOT), Fusion.kindHints("ujumbe wa pesa"))
    assertEquals(listOf("ujumbe", "pesa"), Fusion.terms("ujumbe wa pesa"))   // "message" stays a search word, as in English
  }

  @Test fun englishQueriesKeepEveryWordTheyHadBefore() {
    assertEquals(listOf("we", "ate", "by", "lake", "la"), Fusion.terms("what we ate by the lake in LA"))
    assertEquals(listOf("pale", "wake", "ago", "era", "bali"), Fusion.terms("pale wake ago era bali"))
    assertFalse(Fusion.isLocal("music bills from last month"))
    assertFalse(Fusion.isLocal("photos we took by the lake in LA"))
    assertTrue(Fusion.isLocal("ebifaananyi by'embaga mu kampala"))
    assertTrue(Fusion.isLocal("picha ya harusi"))
  }

  @Test fun aLugandaWordAlsoNamesItsOtherNounClassForms() {
    val forms = Fusion.nounClassForms("ekifaananyi")
    assertEquals("ekifaananyi", forms.first())
    assertTrue(forms.toString(), forms.containsAll(listOf("ebifaananyi", "kifaananyi", "bifaananyi")))
    assertTrue(Fusion.nounClassForms("abantu").contains("omuntu"))
    assertTrue(Fusion.nounClassForms("omuggo").contains("emiggo"))
    assertTrue(Fusion.nounClassForms("okukola").contains("kukola"))
    assertEquals(listOf("ente"), Fusion.nounClassForms("ente"))     // no class prefix
    assertEquals(listOf("muti"), Fusion.nounClassForms("muti"))
    assertEquals(listOf("kiro"), Fusion.nounClassForms("kiro"))     // a stem of two letters is too short to stand for a word
  }

  @Test fun aSwahiliFileNoLongerMatchesOnGrammarWordsAlone() {
    doc("habari.txt", "habari ya leo na wa kwenye mji wangu langu na ya")
    doc("shamba la kahawa.txt", "picha za kahawa shamba Bukoba")
    // Before: the news file held "ya", "kwenye", "langu" (3 of 6 words) and qualified on words; now it holds none of the content words.
    assertEquals(listOf("shamba la kahawa.txt"), names("picha ya kahawa kwenye shamba langu"))
  }

  @Test fun aLugandaQueryFindsThePluralItDidNotType() {
    doc("embaga.txt", "ebifaananyi by'abagole e Mukono")
    doc("other.txt", "lipoota y'essomero")
    // "ekifaananyi" (a picture) and "omugole" (a bride) are written "ebifaananyi" and "abagole" (plural) in the file: no word in common as typed
    assertEquals(listOf("embaga.txt"), names("ekifaananyi ky'omugole"))
  }

  @Test fun anEnglishQueryIsNotWidenedToOtherForms() {
    doc("basics.txt", "the basic rules of the club")
    doc("songs.txt", "music for the wedding")
    assertEquals(listOf("songs.txt"), names("music for the wedding"))
    assertEquals(listOf("basics.txt"), names("basic rules"))
  }
}
