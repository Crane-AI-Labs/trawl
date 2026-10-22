package com.craneai.findr.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLanguageGateTest {
  private val words = File("src/main/assets/${SpeechLanguageGate.ASSET}").inputStream().use { EnglishWords.fromGzip(it) }
  private val common = File("src/main/assets/${SpeechLanguageGate.COMMON_ASSET}").inputStream().use { EnglishWords.fromGzip(it) }
  private val gate = SpeechLanguageGate(lazyOf(words), lazyOf(common))
  private fun kept(text: String) = gate.judge(text).english

  @Test fun englishSpeechPasses() {
    assertTrue(kept("Its long jaws were studded with more than seventy razor sharp teeth along with an extra set in the roof of its mouth."))
    assertTrue(kept("Hello, can you hear me? I'm at the market, I'll call you when I'm done, it's nearly nine."))
    assertTrue(kept("Soon after the outbreak of hostilities, Britain initiated a naval blockade of Germany."))
  }

  /** Parakeet's output on Luganda and Swahili clips: real-looking letters, few common words. */
  @Test fun lugandaAndSwahiliJunkIsRejected() {
    val junk = listOf(
      "Ekku Kamoldavva, Kitiwa, Tishin Shina Holy de me They won' get of dom Nala shall it to sing or it's aay to Ool",
      "in Bazacho in Pamu Zetoodo Amanu Aoji, Agasuamamo quota dinner Menu againun is our gloom cama Eegz Talicona Eichonchaso",
      "no y aku Multine Baggano Pocononia Eblla, Italin nonji Aza oulated HO companyiram",
      "Ao G atamako Aayengo Aakoukouka Emirun Di Menji K beja O Ku befana Niza",
    )
    junk.forEach { assertFalse(it, kept(it)) }
    assertFalse(kept("Embazaako empanvu zeetooloddwa amannyo amooji agasukka mu nsanvu nga kwotadde n'amannyo ag'ennyong"))
    assertFalse(kept("taya zake kubwa zilikuwa na zaidi ya meno sabini kali kama wembe pamoja na seti ya ziada kwenye paa la mdomo wake"))
  }

  /** The short-transcript exemption of version 1 is gone: a handful of junk words proves nothing, and let most Luganda clips of a corpus through. */
  @Test fun shortJunkIsRejectedAndShortEnglishIsKept() {
    assertFalse(kept("zzzz qqqq xxxx vvvv"))
    assertFalse(kept("Bwanji sana mukwano"))
    assertFalse(kept("okay zzzz"))
    assertTrue(kept("okay"))
    assertTrue(kept("okay thanks a lot"))
    assertTrue(kept("one two three four xxxxx"))      // 0.8 exactly passes
    assertFalse(kept("one two three yyyy xxxxx"))     // 0.6
    assertEquals(4, gate.judge("one two three four").wordCount)
  }

  @Test fun noWordsMeansNothingToJudge() {
    for (t in listOf("", "   ", "12 34 !!!")) {
      val v = gate.judge(t)
      assertTrue(t, v.english)
      assertEquals(0, v.wordCount)
      assertEquals("", v.partial)
    }
  }

  /** A note that switches language keeps the English stretches of 7 or more common words and loses the rest. */
  @Test fun codeSwitchedNotesKeepTheirLongEnglishRuns() {
    val text = "Nze nja kujja ku katale enkya naye weebale nnyo please send the money by mobile today for the school fees mukwano gwange tugende"
    val v = gate.judge(text)
    assertFalse(v.english)
    assertEquals("please send the money by mobile today for the school fees", v.partial)
    // two runs are joined with a space, a run of 6 is not worth keeping
    val two = gate.judge("webale nnyo I will see you at the market tomorrow at nine nga tugenda ssaawa nga mukwano I need it for the school fees and the bus home")
    assertFalse(two.english)
    assertEquals("i will see you at the market tomorrow at nine i need it for the school fees and the bus home", two.partial)
    assertEquals("", gate.judge("Nze nja kujja ku katale enkya please send the money by mobile mukwano gwange tugende").partial)
    // mostly English passes whole
    assertTrue(kept("Hello mukwano, I will see you at the market tomorrow around nine, okay, webale nnyo for the lift and the matatu home"))
  }

  @Test fun curlyApostrophesAndPunctuationAreHandled() {
    assertTrue(kept("It’s fine, we’re all here; don’t worry about the time."))
    assertEquals(listOf("don't", "go"), SpeechLanguageGate.tokens("DON’T go!!"))
  }

  @Test fun missingWordListKeepsTheTranscript() {
    val v = SpeechLanguageGate(lazyOf(null)).judge("Ekku Kamoldavva Kitiwa Tishin Shina Holy de me")
    assertTrue(v.english)
    assertEquals("", v.partial)
  }

  @Test fun detailLinesSayWhatHappened() {
    val v = gate.judge("Ekku Kamoldavva, Kitiwa, Tishin Shina Holy de me They won' get of dom Nala shall it to sing")
    assertFalse(v.english)
    val dropped = SpeechLanguageGate.note(v, 0, audioVectors = true)
    assertTrue(dropped, dropped.startsWith("${SpeechLanguageGate.NOTE_NOT_ENGLISH} ("))
    assertTrue(dropped, dropped.endsWith("common words): audio vector"))
    assertTrue(SpeechLanguageGate.note(v, 0, audioVectors = false).endsWith("transcript dropped"))
    assertEquals("3 ${SpeechLanguageGate.NOTE_ENGLISH}", SpeechLanguageGate.note(SpeechLanguageGate.Verdict(0.95, 20, true), 3, audioVectors = true))
    // both verdict notes carry the marker that IndexStore.requeueSpeechJudgedAgain looks for
    val kept = SpeechLanguageGate.note(SpeechLanguageGate.Verdict(0.4, 20, false, "a b c d e f g"), 2, audioVectors = true)
    assertEquals("speech not English (40% common words): kept its English parts (2 pieces), audio vector", kept)
  }

  @Test fun partialRunsBecomeTextPieces() {
    val text = (1..120).joinToString(" ") { "word$it".replace(Regex("[0-9]"), "x") }
    val pieces = TranscriptPieces.ofText(text, maxChars = 100)
    assertTrue(pieces.size > 1)
    assertTrue(pieces.all { it.text.length <= 100 && it.startMs == null && it.endMs == null && it.marks == null })
    assertEquals(text, pieces.joinToString(" ") { it.text })
    assertTrue(TranscriptPieces.ofText("").isEmpty())
  }

  /** The Mac eval's twin (the experiment code) wrote this file: every line must come out the same on the phone's code. */
  @Test fun agreesWithThePythonTwinOnTheStudyTranscripts() {
    val lines = checkNotNull(javaClass.getResourceAsStream("/speech_gate_golden.tsv")).bufferedReader().readLines().filter { !it.startsWith("#") && it.isNotBlank() }
    assertEquals(480, lines.size)
    var whole = 0
    var partial = 0
    for (line in lines) {
      val f = line.split('\t', limit = 6)
      val v = gate.judge(f[5])
      assertEquals(line, f[1].toInt(), v.wordCount)
      assertEquals(line, f[2].toDouble(), v.share, 0.0006)
      assertEquals(line, f[3] == "1", v.english)
      assertEquals(line, f[4], v.partial)
      if (v.english) whole++
      if (v.partial.isNotEmpty()) partial++
    }
    assertTrue("whole $whole", whole in 100..250)
    assertTrue("partial $partial", partial > 20)
  }
}
