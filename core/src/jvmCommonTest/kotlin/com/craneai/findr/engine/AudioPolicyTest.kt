package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision table for sound files: speech pack (yes/no) x length (short/long) x "Deeper sound search" (off/on). */
class AudioPolicyTest {
  private val short = 60_000L                          // a one-minute voice note
  private val edge = AudioPolicy.SHORT_MS              // exactly five minutes is still short
  private val long = AudioPolicy.SHORT_MS + 1          // a song or a podcast

  private fun audio(ms: Long?, speechToText: Boolean, pack: Boolean, deeper: Boolean) =
    AudioPolicy.plan(FileKind.AUDIO, ms, speechToText, pack, deeper)

  // ---- speech works: words and sound labels; the vector only comes with the deeper switch (or the language gate, in FileProcessor)

  @Test fun withSpeechShortNoDeeperReadsWordsOnly() =
    assertEquals(AudioPolicy.Plan(speech = true, vector = false), audio(short, true, pack = true, deeper = false))

  @Test fun withSpeechLongNoDeeperReadsWordsOnly() =
    assertEquals(AudioPolicy.Plan(speech = true, vector = false), audio(long, true, pack = true, deeper = false))

  @Test fun withSpeechShortDeeperReadsWordsAndVector() =
    assertEquals(AudioPolicy.Plan(speech = true, vector = true), audio(short, true, pack = true, deeper = true))

  @Test fun withSpeechLongDeeperReadsWordsAndVector() =
    assertEquals(AudioPolicy.Plan(speech = true, vector = true), audio(long, true, pack = true, deeper = true))

  // ---- no speech pack: short recordings get the vector by default, long ones wait for the deeper switch

  @Test fun noPackShortNoDeeperGetsTheVector() {
    val p = audio(short, true, pack = false, deeper = false)
    assertTrue(p.vector)
    assertFalse(p.nothing)
  }

  @Test fun noPackFiveMinutesExactlyIsStillShort() = assertTrue(audio(edge, true, pack = false, deeper = false).vector)

  @Test fun noPackLongNoDeeperIsOnlyNoted() {
    // Asked for speech, none installed: no vector. FileProcessor notes the missing models so the file is read once they arrive.
    assertEquals(AudioPolicy.Plan(speech = true, vector = false), audio(long, true, pack = false, deeper = false))
  }

  @Test fun noPackLongDeeperGetsTheVector() = assertTrue(audio(long, true, pack = false, deeper = true).vector)

  @Test fun noPackShortDeeperGetsTheVector() = assertTrue(audio(short, true, pack = false, deeper = true).vector)

  @Test fun anUnknownLengthCountsAsShort() = assertTrue(audio(null, true, pack = false, deeper = false).vector)

  // ---- speech to text switched off in Settings behaves like a missing pack, even when the pack is installed

  @Test fun speechSwitchedOffShortGetsTheVector() {
    val p = audio(short, false, pack = true, deeper = false)
    assertEquals(AudioPolicy.Plan(speech = false, vector = true), p)
  }

  @Test fun speechSwitchedOffLongDoesNothingUntilDeeper() {
    assertTrue(audio(long, false, pack = true, deeper = false).nothing)
    assertTrue(audio(long, false, pack = false, deeper = false).nothing)
    assertEquals(AudioPolicy.Plan(speech = false, vector = true), audio(long, false, pack = true, deeper = true))
  }

  // ---- the whole table in one place: every combination, so a change to the rule shows up as one diff

  @Test fun theWholeTable() {
    data class Row(val pack: Boolean, val ms: Long, val deeper: Boolean, val vector: Boolean)
    val rows = listOf(
      Row(pack = true, ms = short, deeper = false, vector = false),
      Row(pack = true, ms = short, deeper = true, vector = true),
      Row(pack = true, ms = long, deeper = false, vector = false),
      Row(pack = true, ms = long, deeper = true, vector = true),
      Row(pack = false, ms = short, deeper = false, vector = true),
      Row(pack = false, ms = short, deeper = true, vector = true),
      Row(pack = false, ms = long, deeper = false, vector = false),
      Row(pack = false, ms = long, deeper = true, vector = true),
    )
    for (r in rows) assertEquals("$r", r.vector, audio(r.ms, true, r.pack, r.deeper).vector)
  }

  // ---- videos are unchanged: the vector only comes with the deeper switch, whatever the speech pack or the length

  @Test fun videosGetTheVectorOnlyWithTheDeeperSwitch() {
    for (pack in listOf(true, false)) for (ms in listOf(short, long, null)) {
      assertFalse(AudioPolicy.plan(FileKind.VIDEO, ms, true, pack, deeper = false).vector)
      assertTrue(AudioPolicy.plan(FileKind.VIDEO, ms, true, pack, deeper = true).vector)
    }
  }

  @Test fun otherKindsNeverGetAnAudioVector() {
    for (k in listOf(FileKind.IMAGE, FileKind.DOCUMENT, FileKind.PDF, FileKind.OTHER))
      assertFalse(AudioPolicy.plan(k, short, false, false, deeper = true).vector)
  }
}
