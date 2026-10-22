package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FusionTest {
  private val text = Modality.TEXT.ordinal
  private val image = Modality.IMAGE.ordinal

  @Test fun soundLabelVectorsAreCentredApartFromTranscripts() {
    // Tags eval (ii): 30 transcript cosines around 0.50 and 30 sound-line cosines around 0.20. Centred apart, a sound row
    // that is as good for its group as the best transcript row is as high on the scale; mixed into one group it would sink.
    val sound = Modality.SOUND.ordinal
    val scores = FloatArray(60) { if (it < 30) 0.50f + 0.01f * (it % 5) else 0.20f + 0.01f * (it % 5) }
    val modality = IntArray(60) { if (it < 30) text else sound }
    val z = Fusion.meaning(scores, modality, BooleanArray(60) { true })
    assertEquals(z[4], z[34], 1e-4)
    val together = Fusion.meaning(scores, IntArray(60) { text }, BooleanArray(60) { true })
    assertTrue(together[34] < -0.5 && z[34] > -0.5)
    assertEquals(Modality.SOUND, Modality.of("sound"))
    assertEquals("sound", Modality.SOUND.db)
  }

  @Test fun termsKeepContentWordsInOrderWithoutRepeats() {
    assertEquals(listOf("trx50", "receipt"), Fusion.terms("Find my TRX50 receipt photos"))
    assertEquals(listOf("img", "2026"), Fusion.terms("IMG_2026"))
    assertEquals(listOf("mama", "birthday"), Fusion.terms("the Mama birthday, mama!"))
    assertEquals(emptyList<String>(), Fusion.terms("a of the"))
    assertEquals(emptyList<String>(), Fusion.terms("x ?"))   // single letters are not words
  }

  @Test fun lookupQueriesAreCodesAndLongNumbers() {
    assertTrue(Fusion.isLookup(listOf("trx50")))
    assertTrue(Fusion.isLookup(listOf("4815162342")))
    assertTrue(Fusion.isLookup(listOf("2026")))
    assertFalse(Fusion.isLookup(listOf("123")))
    assertFalse(Fusion.isLookup(listOf("trx50", "receipt")))
    assertFalse(Fusion.isLookup(emptyList()))
  }

  @Test fun kindHintsAreSoftBoostsFromKindWords() {
    assertEquals(setOf(FileKind.SCREENSHOT), Fusion.kindHints("screenshot of a chat message"))
    assertEquals(setOf(FileKind.IMAGE, FileKind.PDF), Fusion.kindHints("photos in my pdf"))
    assertEquals(setOf(FileKind.DOCUMENT, FileKind.PDF), Fusion.kindHints("the document"))
    assertEquals(setOf(FileKind.IMAGE), Fusion.kindHints("funny meme"))
    assertEquals(emptySet<FileKind>(), Fusion.kindHints("tax return"))
  }

  @Test fun scoreCombinesMeaningWordsAndHint() {
    assertEquals(3.0 + 2.0, Fusion.score(3.0, 1.0, lookup = false, hinted = false), 1e-9)
    assertEquals(0.5 * 3.0 + 4.0, Fusion.score(3.0, 1.0, lookup = true, hinted = false), 1e-9)
    assertEquals(2.0 * Math.pow(0.5, 1.5), Fusion.score(null, 0.5, lookup = false, hinted = false), 1e-9)
    assertEquals(1.0, Fusion.score(-4.0, 0.0, lookup = false, hinted = true), 1e-9)   // a negative z never subtracts
    // one common word cannot beat a strong meaning match; all the words of an exact lookup usually do
    assertTrue(Fusion.score(6.0, 0.0, false, false) > Fusion.score(0.0, 0.5, false, false))
    assertTrue(Fusion.score(2.5, 1.0, true, false) > Fusion.score(6.0, 0.0, true, false))
  }

  @Test fun pendingDoublesTheWordTermOnlyWithNoMeaningScoreOfItsOwn() {
    // A file still waiting to be understood (pending), with no meaning vector of its own: its word term is doubled,
    // so a strong but partial word match (a document, say) is not routinely buried by a weak but already-embedded
    // meaning-only match (a screenshot whose quick-look or OCR vector landed first) -- the research notes, item 2.
    val coverage = 0.5
    val plainWordsTerm = Fusion.WORD_WEIGHT * Math.pow(coverage, 1.5)
    assertEquals(plainWordsTerm, Fusion.score(null, coverage, lookup = false, hinted = false, pending = false), 1e-9)
    assertEquals(2 * plainWordsTerm, Fusion.score(null, coverage, lookup = false, hinted = false, pending = true), 1e-9)
    // The one case that must stay exactly as it was before this existed: a file that already has its own meaning
    // score is never touched by `pending`, whatever it is set to -- the bonus is gone the moment the file is understood.
    assertEquals(Fusion.score(3.0, coverage, false, false, pending = false), Fusion.score(3.0, coverage, false, false, pending = true), 1e-9)
    assertEquals(Fusion.score(0.0, coverage, false, false, pending = false), Fusion.score(0.0, coverage, false, false, pending = true), 1e-9)
  }

  @Test fun strongCountCutsAtHalfTheTopScore() {
    assertEquals(3, Fusion.strongCount(listOf(10.0, 6.0, 5.0, 4.9, 1.0)))
    assertEquals(0, Fusion.strongCount(emptyList()))
    assertEquals(2, Fusion.strongCount(listOf(0.0, 0.0)))   // no positive top to compare to: all strong
    assertEquals(1, Fusion.strongCount(listOf(8.0, 3.0)))
  }

  @Test fun meaningPutsModalitiesOnOneFooting() {
    // Same spread, but images sit 0.2 lower, as text-to-image cosines do.
    val spread = DoubleArray(100) { (it - 50) * 0.002 }
    val scores = FloatArray(200) { if (it < 100) (0.30 + spread[it]).toFloat() else (0.10 + spread[it - 100]).toFloat() }
    val modality = IntArray(200) { if (it < 100) text else image }
    val z = Fusion.meaning(scores, modality, BooleanArray(200) { true })
    for (i in 0 until 100) assertEquals(z[i], z[100 + i], 1e-4)
    assertEquals(0.0, z.average(), 1e-6)
  }

  @Test fun laterVectorsAreScoredButDoNotMoveTheScale() {
    val spread = FloatArray(100) { 0.3f + (it - 50) * 0.002f }
    val base = Fusion.meaning(spread, IntArray(100) { text }, BooleanArray(100) { true })
    val scores = spread + FloatArray(1000) { -0.5f }   // a book's pages, all far below
    val withBook = Fusion.meaning(scores, IntArray(1100) { text }, BooleanArray(1100) { it < 100 })
    for (i in 0 until 100) assertEquals(base[i], withBook[i], 1e-9)
    assertTrue(withBook[500] < -10)   // the pages are still scored, just not counted in the scale
  }

  @Test fun staleVectorsAreScoredButDoNotMoveTheScaleWhileCurrentOnesCanStandInForIt() {
    // 100 current photos and 1000 stale ones far below them: the stale ones are scored but the scale is the current ones' own.
    val spread = FloatArray(100) { 0.3f + (it - 50) * 0.002f }
    val base = Fusion.meaning(spread, IntArray(100) { image }, BooleanArray(100) { true })
    val scores = spread + FloatArray(1000) { -0.5f }
    val z = Fusion.meaning(scores, IntArray(1100) { image }, BooleanArray(1100) { true }, BooleanArray(1100) { it >= 100 })
    for (i in 0 until 100) assertEquals(base[i], z[i], 1e-9)
    assertTrue(z[500] < -10)
  }

  @Test fun rightAfterAnUpgradeTheStaleRowsOfAModalityWithNoCurrentOnesSetItsScale() {
    // Every photo is stale: with nothing current to centre on, a flat deviation floor would blow every z up. They set the scale.
    val spread = FloatArray(100) { 0.3f + (it - 50) * 0.002f }
    val allStale = Fusion.meaning(spread, IntArray(100) { image }, BooleanArray(100) { true }, BooleanArray(100) { true })
    val plain = Fusion.meaning(spread, IntArray(100) { image }, BooleanArray(100) { true })
    for (i in 0 until 100) assertEquals(plain[i], allStale[i], 1e-9)
    // Fewer than a group's worth of current photos is not enough to stand on either.
    val few = Fusion.meaning(spread, IntArray(100) { image }, BooleanArray(100) { true }, BooleanArray(100) { it >= 5 })
    for (i in 0 until 100) assertEquals(plain[i], few[i], 1e-9)
  }

  @Test fun smallModalityGroupsUseTheOverallAverage() {
    // 3 image vectors among 100 text ones: too few to have their own average.
    val scores = FloatArray(103) { if (it < 100) 0.30f + (it % 10) * 0.01f else 0.10f }
    val modality = IntArray(103) { if (it < 100) text else image }
    val z = Fusion.meaning(scores, modality, BooleanArray(103) { true })
    // centred on the overall mean, the low image vectors land well below the text ones instead of at the middle
    assertTrue(z[100] < -3)
  }

  @Test fun aFlatLandscapeHasNothingStandingOut() {
    val z = Fusion.meaning(FloatArray(500) { 0.25f }, IntArray(500) { text }, BooleanArray(500) { true })
    assertTrue(z.all { it == 0.0 })   // the deviation floor keeps a flat score list from inflating noise
    val noisy = Fusion.meaning(FloatArray(500) { 0.25f + (it % 7) * 0.0001f }, IntArray(500) { text }, BooleanArray(500) { true })
    assertTrue(noisy.all { it < 2.0 })
  }

  @Test fun aPlantedMatchStandsFarPastTheGate() {
    // 1000 cosines with sd 0.036 (random 768-d vectors); one planted at 0.5 is far past the gate.
    val random = java.util.Random(9)
    val scores = FloatArray(1001) { if (it == 1000) 0.5f else (random.nextGaussian() * 0.036).toFloat() }
    val z = Fusion.meaning(scores, IntArray(1001) { text }, BooleanArray(1001) { true })
    assertTrue(z[1000] > 10)
    assertTrue(sqrt(z.take(1000).sumOf { it * it } / 1000) < 1.5)
  }

  @Test fun phraseQueryNeedsTwoTermsAndQuotesThemInOrder() {
    assertEquals(null, Fusion.phraseQuery(emptyList(), false))
    assertEquals(null, Fusion.phraseQuery(listOf("budget"), true))
    assertEquals("\"budget review\"", Fusion.phraseQuery(listOf("budget", "review"), false))
    assertEquals("\"budget review\" *", Fusion.phraseQuery(listOf("budget", "review"), true))   // FTS5 spells a prefix phrase this way
    assertEquals("\"a1 b2 c3\"", Fusion.phraseQuery(Fusion.terms("A1, b2 - c3"), false))
  }

  @Test fun thePhraseBonusIsTheWordWeightAndAddsToTheScore() {
    assertEquals(2.0, Fusion.PHRASE_BONUS, 0.0)
    assertEquals(Fusion.score(3.0, 1.0, lookup = false, hinted = true) + 2.0, Fusion.score(3.0, 1.0, lookup = false, hinted = true, phrase = true), 1e-9)
    assertEquals(Fusion.score(null, 0.0, lookup = false, hinted = false) + 2.0, Fusion.score(null, 0.0, lookup = false, hinted = false, phrase = true), 1e-9)
    // the bonus does not double in lookup mode: it is fixed, not a weight
    assertEquals(Fusion.score(0.0, 1.0, lookup = true, hinted = false) + 2.0, Fusion.score(0.0, 1.0, lookup = true, hinted = false, phrase = true), 1e-9)
  }
}
