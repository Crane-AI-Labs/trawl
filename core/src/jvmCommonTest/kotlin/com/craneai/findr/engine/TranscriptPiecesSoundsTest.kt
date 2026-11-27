package com.craneai.findr.engine

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SoundTags
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptPiecesSoundsTest {
  private fun line(vararg windows: List<String>) =
    TranscriptPieces.sounds(AudioTranscript(emptyList(), windows.mapIndexed { i, l -> SoundTags(i * 10_000L, (i + 1) * 10_000L, l) }, 20.0))!!.text

  /** The exact lines the bench measured (video-context-levers.md, arm 1c), from real sung recordings' labels. */
  @Test fun soundsSayManWomanAndChildrenSinging() {
    assertEquals(
      "Sounds: Male singing (man singing), Lullaby, Female singing (woman singing), Mantra",
      line(listOf("Male singing", "Lullaby", "Female singing", "Mantra")),
    )
    assertEquals("Sounds: Mantra, Lullaby, Male singing (man singing)", line(listOf("Mantra", "Lullaby"), listOf("Male singing")))
    assertEquals("Sounds: Child singing (children singing), Babbling (baby babbling), Music", line(listOf("Child singing", "Babbling", "Music")))
  }

  @Test fun otherLabelsStayAsTheyAre() {
    assertEquals("Sounds: Singing, Choir, Music", line(listOf("Singing", "Choir", "Music")))
    assertEquals("Sounds: Dog, Bark, Speech", line(listOf("Dog", "Bark"), listOf("Dog", "Speech")))
  }
}
