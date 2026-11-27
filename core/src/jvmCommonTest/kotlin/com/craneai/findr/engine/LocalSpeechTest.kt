package com.craneai.findr.engine

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SpeechSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech add-on's rules (LocalSpeech), without a model: which segments a speech pack reads again (only the ones the gate drops, never
 * an English window, never a sung stretch), that it reads them one at a time, which of its readings are kept, and that a window it did
 * not touch is exactly the gate's judgement, so English is unchanged by construction.
 */
class LocalSpeechTest {
  private val common = listOf(
    "a", "about", "and", "at", "call", "can", "come", "for", "i", "is", "it", "me", "meet", "please", "station", "the", "this", "to",
    "tomorrow", "we", "will", "you",
  ).sorted()
  private val gate = SpeechLanguageGate(lazyOf(null), lazyOf(EnglishWords((common.joinToString("\n") + "\n").toByteArray())))

  private fun seg(start: Long, text: String, sung: Boolean = false, local: SpeechSegment? = null) =
    SpeechSegment(start, start + 4_000, text, sung = sung, local = local)

  private val english = "please call me tomorrow at the station"   // 7 common words
  private val junk = "bulungi nnyo kale tuli wamu"                   // Parakeet's "English" for Luganda: no common words
  private val shortEnglish = "is the"                               // two common words prove nothing

  @Test fun anEnglishWindowIsNeverRouted() {
    val first = listOf(seg(0, english), seg(4_000, "we will meet you at the station tomorrow"))
    assertEquals(emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = false, englishToo = false).route(first))
  }

  @Test fun aNotEnglishWindowRoutesTheSegmentsTheGateDropsAndKeepsTheSureEnglishOnes() {
    val first = listOf(seg(0, junk), seg(4_000, english), seg(8_000, shortEnglish), seg(12_000, junk), seg(16_000, junk), seg(20_000, ""))
    // segment 1 is sure English (7 common words); the blank one (Parakeet heard nothing) is read again too
    assertEquals(setOf(0, 2, 3, 4, 5), LocalSpeech.Router(gate, wholeNote = false, englishToo = false).route(first))
  }

  @Test fun sungStretchesAreNeverRouted() {
    val first = listOf(seg(0, junk), seg(4_000, junk, sung = true), seg(8_000, junk))
    assertEquals(setOf(0, 2), LocalSpeech.Router(gate, wholeNote = false, englishToo = false).route(first))
    assertEquals("only sung words: nothing to read", emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = false, englishToo = false).route(listOf(seg(0, junk, sung = true))))
  }

  @Test fun noWordsAtAllIsNotRouted() {
    assertEquals(emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = false, englishToo = false).route(emptyList()))
    assertEquals(emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = false, englishToo = false).route(listOf(seg(0, ""), seg(4_000, ""))))
  }

  @Test fun readAgainReadsThePickedSegmentsOneAtATimeInOrder() = runBlocking {
    val first = listOf(seg(0, junk), seg(4_000, english), seg(8_000, ""), seg(12_000, junk))
    val log = ArrayList<String>()
    var inFlight = 0
    var maxInFlight = 0
    val reading = object : LocalSpeech.Reading {
      override fun route(first: List<SpeechSegment>) = setOf(0, 2, 3)
      override suspend fun beforeEachRead() { log += "wait" }
    }
    val out = LocalSpeech.readAgain(first, reading) { i, s ->
      inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
      log += "read $i"
      inFlight--
      if (i == 2) null else SpeechSegment(s.startMs, s.endMs, "nze njagala okulya emmere")
    }
    assertEquals(listOf("wait", "read 0", "wait", "read 2", "wait", "read 3"), log)
    assertEquals("one segment at a time", 1, maxInFlight)
    assertEquals("the blank segment nothing read is dropped", listOf(0L, 4_000L, 12_000L), out.map { it.startMs })
    assertEquals("nze njagala okulya emmere", out[0].local?.text)
    assertNull("not picked: no second reading", out[1].local)
    assertEquals(junk, out[0].text)   // Parakeet's text stays, so the gate judges the window as it always did
  }

  @Test fun readAgainWithNothingPickedOnlyDropsTheBlanks() = runBlocking {
    val first = listOf(seg(0, english), seg(4_000, ""))
    var reads = 0
    val out = LocalSpeech.readAgain(first, LocalSpeech.Router(gate, wholeNote = false, englishToo = false)) { _, _ -> reads++; null }
    assertEquals(0, reads)
    assertEquals(listOf(english), out.map { it.text })
  }

  @Test fun acceptKeepsSpeechAndDropsJunk() {
    fun r(text: String, ms: Long = 4_000) = SpeechSegment(0, ms, text)
    assertTrue(LocalSpeech.accept(r("nze njagala okulya emmere")))
    assertFalse("one word", LocalSpeech.accept(r("eee")))
    assertFalse("two words in 10 s is not a speaking pace", LocalSpeech.accept(r("eee aaa", 10_000)))
    assertFalse("a few words over and over", LocalSpeech.accept(r("la la la la la la")))
  }

  @Test fun keepWithoutAnySecondReadingIsExactlyTheGate() {
    for (t in listOf(
      AudioTranscript(listOf(seg(0, english), seg(4_000, english)), emptyList(), 8.0),
      AudioTranscript(listOf(seg(0, junk), seg(4_000, english)), emptyList(), 8.0),
      AudioTranscript(listOf(seg(0, junk), seg(4_000, junk, sung = true)), emptyList(), 8.0),
    )) {
      val ours = LocalSpeech.keep(t, gate, wholeNote = false)
      val theirs = gate.keep(t)
      assertNull(ours.localKept)
      assertEquals(theirs.verdict.english, ours.verdict.english)
      assertEquals(theirs.verdict.share, ours.verdict.share, 0.0)
      assertEquals(theirs.speech.map { Triple(it.startMs, it.text, it.marks) }, ours.speech.map { Triple(it.startMs, it.text, it.marks) })
    }
  }

  @Test fun keepPutsThePacksAcceptedReadingsInPlaceOfTheJunk() {
    val t = AudioTranscript(listOf(
      seg(0, junk, local = SpeechSegment(0, 4_000, "nze njagala okulya emmere")),
      seg(4_000, english),
      seg(8_000, junk, local = SpeechSegment(8_000, 12_000, "eee")),   // rejected: one word
    ), emptyList(), 12.0)
    val kept = LocalSpeech.keep(t, gate, wholeNote = false)
    assertFalse(kept.verdict.english)
    assertEquals(1, kept.localKept)
    val text = kept.speech.joinToString(" | ") { it.text }
    assertTrue(text, text.contains("nze njagala okulya emmere"))
    assertTrue("the sure English segment stays", text.contains(english))
    assertFalse("the junk is not indexed", text.contains("bulungi"))
    assertFalse("a rejected reading is not indexed", text.contains("eee"))
  }

  @Test fun keepLeavesSungStretchesToTheGatesOwnRule() {
    val sungEnglish = "we will come for you tomorrow at the station"
    val t = AudioTranscript(listOf(
      seg(0, junk, local = SpeechSegment(0, 4_000, "nze njagala okulya emmere")),
      seg(4_000, sungEnglish, sung = true),
    ), emptyList(), 8.0)
    val kept = LocalSpeech.keep(t, gate, wholeNote = false)
    val text = kept.speech.joinToString(" | ") { it.text }
    assertTrue(text.contains("nze njagala okulya emmere"))
    assertTrue("an English chorus keeps its words", text.contains(sungEnglish))
    assertFalse(kept.verdict.english)
  }

  // ---- whole-note reading (LocalSpeech.WHOLE_NOTE, being measured) ----

  @Test fun wholeNoteRoutesEverySpokenSegmentOfANotEnglishWindowAndNoneOfAnEnglishOne() {
    val notEnglish = listOf(seg(0, junk), seg(4_000, english), seg(8_000, ""), seg(12_000, junk, sung = true))
    assertEquals(setOf(0, 1, 2), LocalSpeech.Router(gate, wholeNote = true).route(notEnglish))
    val englishWindow = listOf(seg(0, english), seg(4_000, "we will meet you at the station tomorrow"))
    assertEquals(emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = true).route(englishWindow))
  }

  @Test fun wholeNoteKeepsEveryReadingAndDropsTheEnglishModelsWords() {
    val t = AudioTranscript(listOf(
      seg(0, junk, local = SpeechSegment(0, 4_000, "nze njagala okulya emmere")),
      seg(4_000, english, local = SpeechSegment(4_000, 8_000, "kale tugende")),
      seg(8_000, junk, local = SpeechSegment(8_000, 12_000, "eee")),   // one word: kept in whole-note mode
    ), emptyList(), 12.0)
    val kept = LocalSpeech.keep(t, gate, wholeNote = true, english = LocalSpeech.ENGLISH_NONE)
    val text = kept.speech.joinToString(" | ") { it.text }
    assertEquals(3, kept.localKept)
    assertTrue(text, text.contains("nze njagala okulya emmere") && text.contains("kale tugende") && text.contains("eee"))
    assertFalse("the English model's words are dropped", text.contains("station") || text.contains("bulungi"))
  }

  @Test fun wholeNoteLeavesAWindowNothingReadExactlyTheGate() {
    val t = AudioTranscript(listOf(seg(0, english), seg(4_000, english)), emptyList(), 8.0)
    val ours = LocalSpeech.keep(t, gate, wholeNote = true)
    assertNull(ours.localKept)
    assertEquals(gate.keep(t).speech.map { it.text }, ours.speech.map { it.text })
  }

  // ---- English-judged windows too (LocalSpeech.ENGLISH_TOO, being measured) ----

  @Test fun englishTooRoutesAnEnglishWindowButNotAnEmptyOne() {
    val englishWindow = listOf(seg(0, english), seg(4_000, ""))
    assertEquals(setOf(0, 1), LocalSpeech.Router(gate, wholeNote = true, englishToo = true).route(englishWindow))
    assertEquals(emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = true, englishToo = true).route(listOf(seg(0, ""))))
    assertEquals(emptySet<Int>(), LocalSpeech.Router(gate, wholeNote = true).route(englishWindow))
  }

  @Test fun anEnglishWindowKeepsItsWordsAndGainsThePacksOnlyWhenTheyLookLocal() {
    val luganda = SpeechSegment(0, 4_000, "ekibatu ky omuntu kirimu emmwanyi nga zengedde era nga mmyufu")
    val junk = SpeechSegment(0, 4_000, "kalabba tugenderera omusajja")
    for ((local, gains) in listOf(luganda to true, junk to false)) {
      val t = AudioTranscript(listOf(seg(0, english, local = local)), emptyList(), 4.0)
      val kept = LocalSpeech.keep(t, gate, wholeNote = true)
      val text = kept.speech.joinToString(" | ") { it.text }
      assertTrue("the English words always stay", text.contains(english))
      assertEquals(local.text, gains, text.contains(local.text))
      assertTrue(kept.verdict.english)
    }
    assertTrue(LocalSpeech.looksLocal("ekibatu ky omuntu kirimu emmwanyi nga zengedde era nga mmyufu"))
    assertFalse(LocalSpeech.looksLocal("please call me tomorrow at the station"))
  }

  /** A note the gate misjudged as not English (FLEURS): its sure English segments and English runs stay beside the pack's words. */
  @Test fun wholeNoteKeepsTheEnglishTheSegmentRuleKeepsOrAllOfIt() {
    val run = "we will meet you at the station tomorrow please"   // a 9-word English run inside a junk segment
    val t = AudioTranscript(listOf(
      seg(0, junk, local = SpeechSegment(0, 4_000, "nze njagala okulya emmere")),
      seg(4_000, english, local = SpeechSegment(4_000, 8_000, "kale tugende")),
      seg(8_000, "$junk $run bulungi", local = SpeechSegment(8_000, 12_000, "kyaweefunigal pot maigas")),
    ), emptyList(), 12.0)
    val sure = LocalSpeech.keep(t, gate, wholeNote = true, english = LocalSpeech.ENGLISH_SURE).speech.joinToString(" | ") { it.text }
    assertTrue(sure, sure.contains(english) && sure.contains(run) && sure.contains("nze njagala okulya emmere") && sure.contains("kyaweefunigal"))
    assertFalse("junk outside the runs is not kept", sure.contains("bulungi"))
    val all = LocalSpeech.keep(t, gate, wholeNote = true, english = LocalSpeech.ENGLISH_ALL).speech.joinToString(" | ") { it.text }
    assertTrue(all, all.contains("bulungi") && all.contains(english))
  }

  /**
   * The shipped default (whole note, sure English kept): an English note the gate misjudges as not English (FLEURS fleurs_en_1810: its text
   * became "kyaweefunigalrngx pot maigas ..." when the English words were dropped) keeps every sure English segment beside the pack's words.
   */
  @Test fun byDefaultAMisjudgedEnglishNoteKeepsItsEnglishWords() {
    assertTrue(LocalSpeech.WHOLE_NOTE)
    assertEquals(LocalSpeech.ENGLISH_SURE, LocalSpeech.WHOLE_ENGLISH)
    val first = listOf(seg(0, english), seg(4_000, "$junk $junk"), seg(8_000, "we will meet you at the station tomorrow"))
    assertEquals("every spoken segment of the not-English window goes to the pack", setOf(0, 1, 2), LocalSpeech.Router(gate).route(first))
    val t = AudioTranscript(listOf(
      seg(0, english, local = SpeechSegment(0, 4_000, "kyaweefunigalrngx pot maigas")),
      seg(4_000, "$junk $junk", local = SpeechSegment(4_000, 8_000, "simon tong kale")),
      seg(8_000, "we will meet you at the station tomorrow", local = SpeechSegment(8_000, 12_000, "wiil miit yu")),
    ), emptyList(), 12.0)
    val kept = LocalSpeech.keep(t, gate)
    assertFalse(kept.verdict.english)
    val text = kept.speech.joinToString(" | ") { it.text }
    assertTrue(text, text.contains(english) && text.contains("we will meet you at the station tomorrow"))
    assertTrue("the pack's words are there too", text.contains("simon tong kale"))
  }
}
