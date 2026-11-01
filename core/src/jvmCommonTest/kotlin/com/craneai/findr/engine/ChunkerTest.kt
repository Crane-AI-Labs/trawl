package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkerTest {
  /** The rule as it was before the scan was bounded (kept here to prove the bounded one cuts text in the same places). */
  private fun reference(raw: String): List<String> {
    val text = raw.replace(Regex("\\s+"), " ").trim()
    val out = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      var end = minOf(text.length, start + Chunker.CHUNK_CHARS)
      if (end < text.length) {
        val space = text.lastIndexOf(' ', end).takeIf { it > start + Chunker.CHUNK_CHARS / 2 }
        if (space != null) end = space
      }
      out += text.substring(start, end)
      if (end >= text.length) break
      start = maxOf(end - Chunker.OVERLAP_CHARS, start + 1)
    }
    return out
  }

  @Test fun cutsWhereItAlwaysCut() {
    val words = listOf("a", "kampala", "school", "fees", "receipt", "x".repeat(70), "y".repeat(300), "term", "uganda")
    val text = (0 until 4000).joinToString(" ") { words[(it * 7 + it / 3) % words.size] } + "\n\n" + "z".repeat(1500) + " tail words here"
    assertEquals(reference(text), Chunker.chunks(text))
    // A long word with a space only just inside the window, just outside it, and exactly on its edge.
    for (gap in listOf(150, 199, 200, 201, 202, 399, 400, 401)) {
      val t = "q".repeat(gap) + " " + "w".repeat(900)
      assertEquals("gap $gap", reference(t), Chunker.chunks(t))
    }
  }

  @Test fun everyChunkFitsAndNothingIsLost() {
    val text = "word ".repeat(5_000)
    val chunks = Chunker.chunks(text)
    assertTrue(chunks.all { it.length <= Chunker.CHUNK_CHARS })
    assertEquals("word", chunks.first().substringBefore(' '))
    assertTrue(chunks.last().endsWith("word"))
  }

  /**
   * A text with no space in it at all (a minified blob, base64 on one line, a corrupt file read as text). The old scan looked
   * back to the start of the text for a space at every chunk, so the work grew with the square of the length and a 2 million
   * character file (the most the extractor hands over) pinned a core for minutes. 8 million characters here would take the
   * old code well over a minute; the bounded scan does it in a fraction of a second.
   */
  @Test fun aTextWithNoSpacesIsChunkedInLinearTime() {
    val text = "A".repeat(8_000_000)
    val t0 = System.nanoTime()
    val chunks = Chunker.chunks(text)
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertTrue("took $ms ms", ms < 10_000)
    assertTrue(chunks.size > 20_000)
    assertTrue(chunks.all { it.length == Chunker.CHUNK_CHARS || it === chunks.last() })
  }

  @Test fun tickRunsOncePerChunkAndCanStopTheWalk() {
    var calls = 0
    val chunks = Chunker.chunks("word ".repeat(1_000)) { calls++ }
    assertEquals(chunks.size, calls)
    var stopAt = 0
    try {
      Chunker.chunks("A".repeat(1_000_000)) { if (++stopAt == 5) throw IllegalStateException("stop") }
      fail("the tick's exception must reach the caller")
    } catch (e: IllegalStateException) {
      assertEquals(5, stopAt)
    }
  }
}
