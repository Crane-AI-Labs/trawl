package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which phones get the lite profile (2 GB, Android Go) and which keep today's stack (3 GB and up). */
class MemoryProfileTest {
  private fun choose(ramMb: Long, go: Boolean = false, heapMb: Int = 256, override: String? = null) =
    MemoryProfile.choose(ramMb, go, heapMb, override)

  @Test fun twoGigabytePhonesAreLite() {
    assertEquals(MemoryProfile.LITE, choose(1_850))   // a "2 GB" phone after the kernel's share
    assertEquals(MemoryProfile.LITE, choose(1_950, heapMb = 192))
    assertEquals(MemoryProfile.LITE, choose(2_599))
  }

  @Test fun threeGigabytesAndUpStayStandard() {
    assertEquals(MemoryProfile.STANDARD, choose(2_600))
    assertEquals(MemoryProfile.STANDARD, choose(2_750, heapMb = 128))   // a "3 GB" phone, even with a small Java heap
    assertEquals(MemoryProfile.STANDARD, choose(3_633))                 // the Galaxy A06 (sold as 4 GB)
    assertEquals(MemoryProfile.STANDARD, choose(7_700))                 // the Infinix X6885 (8 GB)
  }

  @Test fun androidGoIsLiteWhateverItsRam() {
    assertEquals(MemoryProfile.LITE, choose(2_900, go = true))
  }

  @Test fun theMemoryClassCountsOnlyWhenRamIsUnknown() {
    assertEquals(MemoryProfile.LITE, choose(0, heapMb = 96))
    assertEquals(MemoryProfile.LITE, choose(0, heapMb = 128))
    assertEquals(MemoryProfile.STANDARD, choose(0, heapMb = 256))
    assertEquals(MemoryProfile.STANDARD, choose(0, heapMb = 0))   // nothing known: today's app
  }

  @Test fun theDebugOverrideWinsBothWays() {
    assertEquals(MemoryProfile.LITE, choose(7_700, override = "lite"))
    assertEquals(MemoryProfile.STANDARD, choose(1_850, go = true, override = "standard"))
    assertEquals(MemoryProfile.LITE, choose(1_850, override = "auto"))
    assertEquals(MemoryProfile.STANDARD, choose(3_633, override = "something else"))
  }

  @Test fun findrsOwnPiecesPassTheLiteCutWhole() {
    // Documents, OCR text and transcripts are cut into pieces of at most 400 chars (Chunker, TranscriptPieces), embedded with a
    // prefix; none of them may lose text to the lite profile's cut, which is only for long one-off inputs.
    val docPrefix = "title: none | text: ".length
    assertTrue(Chunker.CHUNK_CHARS + docPrefix <= Embedder.LITE_MAX_INPUT_CHARS)
    assertTrue(TranscriptPieces.MAX_CHARS + docPrefix <= Embedder.LITE_MAX_INPUT_CHARS)
    assertTrue(Embedder.QUERY_PREFIX.length + 400 <= Embedder.LITE_MAX_INPUT_CHARS)
    assertTrue(Chunker.chunks("word ".repeat(2_000)).all { it.length + docPrefix <= Embedder.LITE_MAX_INPUT_CHARS })
    assertTrue(Embedder.LITE_MAX_INPUT_CHARS < 512)   // a token covers at least a character: inside the 512-token bucket
  }

  @Test fun staleCarryOverOnlyBetweenEmbeddingGemmaImageSpaces() {
    val stock = "embeddinggemma-2-740m.litertlm:vt70"
    val tuned = "findr-eg2-ft-740m.litertlm:vt70"
    val lite = Embedder.LITE_FINGERPRINT_PREFIX + "laion-b32-int8:58e57819:squash224-bicubic"
    val lite2 = Embedder.LITE_FINGERPRINT_PREFIX + "tinyclip-61m-32-wo8:238c09ec:squash224-bicubic"
    assertEquals(Embedder.REEMBED_STALE, Embedder.keepStaleOnChange(stock, tuned))   // standard: main's rule, unchanged
    assertEquals(Embedder.REEMBED_STALE, Embedder.keepStaleOnChange(null, stock))
    assertFalse(Embedder.keepStaleOnChange(stock, lite))    // into lite: read everything again
    assertFalse(Embedder.keepStaleOnChange(lite, stock))    // out of lite: lite pictures hold no EG vectors to keep
    assertFalse(Embedder.keepStaleOnChange(lite, lite2))    // a new quick-look model in lite
  }

  @Test fun aStandardPhoneHoldingOnlyTheCoreModelRunsTheLiteStackUntilAVisionModelArrives() {
    val a06 = choose(3_633)
    assertEquals(MemoryProfile.STANDARD, a06)
    assertEquals(MemoryProfile.LITE, MemoryProfile.adopt(a06, null, hasStandardModel = false, hasTextOnlyModel = true))
    assertEquals(MemoryProfile.LITE, MemoryProfile.adopt(a06, "auto", hasStandardModel = false, hasTextOnlyModel = true))
    // After the vision bundle: both files on disk, the standard stack runs (the next start).
    assertEquals(MemoryProfile.STANDARD, MemoryProfile.adopt(a06, null, hasStandardModel = true, hasTextOnlyModel = true))
    // No model at all, or a standard model alone: nothing to adopt, today's behaviour.
    assertEquals(MemoryProfile.STANDARD, MemoryProfile.adopt(a06, null, hasStandardModel = false, hasTextOnlyModel = false))
    assertEquals(MemoryProfile.STANDARD, MemoryProfile.adopt(a06, null, hasStandardModel = true, hasTextOnlyModel = false))
    // A forced profile wins in both directions; a lite phone stays lite.
    assertEquals(MemoryProfile.STANDARD, MemoryProfile.adopt(choose(3_633, override = "standard"), "standard", hasStandardModel = false, hasTextOnlyModel = true))
    assertEquals(MemoryProfile.LITE, MemoryProfile.adopt(choose(1_850), null, hasStandardModel = false, hasTextOnlyModel = true))
  }

  @Test fun standardModelOrderIsUnchangedAndLitePrefersTheTextOnlyBundle() {
    assertEquals(Embedder.MODEL_NAMES, Embedder.modelNames(MemoryProfile.STANDARD))
    assertFalse(Embedder.TEXT_ONLY_NAME in Embedder.modelNames(MemoryProfile.STANDARD))
    val lite = Embedder.modelNames(MemoryProfile.LITE)
    val firstStock = lite.indexOfFirst { it.startsWith("embeddinggemma-2-") }
    assertEquals(Embedder.TEXT_ONLY_NAME, lite[firstStock])
    assertTrue(lite.containsAll(Embedder.MODEL_NAMES))   // a multimodal bundle still works, text only
  }
}
