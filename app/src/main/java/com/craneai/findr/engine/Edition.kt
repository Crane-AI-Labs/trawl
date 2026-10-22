package com.craneai.findr.engine

import com.craneai.findr.BuildConfig
import com.craneai.findr.core.FindrSettings

/**
 * Which edition of the app this is, and what that edition does. One code base, two product flavors (app/build.gradle.kts):
 *  - full: Trawl, with the models (meaning search, quick look, speech, privacy blur);
 *  - go: Trawl Go, for 32-bit, low-memory Android Go phones. It finds files by their names and the words in them (document text,
 *    and OCR of photos and screenshots) and does nothing else: LiteRT-LM, ONNX Runtime and sherpa-onnx have no armeabi-v7a build
 *    we can ship, and a phone with 1 GB free could not hold their models. Its model code does not exist (src/go has stubs).
 *
 * Everything in the shared code that differs by edition asks here, so the full edition's behaviour is the same code path it
 * always was, and the Go rules are all in one place (and unit-tested: EditionTest).
 */
object Edition {
  /** True in Trawl Go. A compile-time constant, so R8 removes the other edition's branches. */
  const val GO: Boolean = BuildConfig.EDITION_GO

  /** Search by meaning (EmbeddingGemma). Never in Go: the "meaning is pending" state is permanent and said honestly. */
  const val MEANING: Boolean = !GO

  /** The optional model packs and everything that delivers them: the models download, Settings add-ons, speech, quick look, blur. */
  const val MODELS: Boolean = !GO

  /** "Find similar" needs a picture vector. */
  const val FIND_SIMILAR: Boolean = !GO

  /** Search by voice would need the speech model; Go has none. (Typing into the search box is unchanged.) */
  const val SPEECH: Boolean = !GO

  /** Quick look (TinyCLIP on ONNX Runtime). */
  const val QUICK_LOOK: Boolean = !GO

  /** Blur of private-looking previews needs the scorer (ONNX Runtime) which has no 32-bit build in our slim AAR. */
  const val BLUR_PREVIEWS: Boolean = !GO

  /**
   * The longest side an image is decoded to before text recognition. 2048 px costs a 12.6 MB bitmap and about 0.3-0.5 s on the A06;
   * on a 128 MB Java heap with ML Kit beside it that is too much, so Go reads at 1280 px (5.9 MB for a 4:3 picture), enough for
   * screenshots, documents and receipts photographed at normal size.
   */
  fun ocrMaxSide(): Int = if (GO) GO_OCR_MAX_SIDE else FileProcessor.OCR_MAX_SIDE

  const val GO_OCR_MAX_SIDE = 1280

  /** Scanned PDF pages read per PDF in Go (full: [FileProcessor.OCR_MAX_PAGES] in the slow pass), and their render width (full: 1600 px). */
  const val GO_OCR_PDF_PAGES = 12
  const val GO_PDF_PAGE_WIDTH = 1100
}

/**
 * The settings this edition can honour. In Go every model-backed switch is off whatever was saved (there is nothing to turn on),
 * so no code path asks for speech, quick look, scoring or deeper sound search. Full: returned unchanged.
 */
fun FindrSettings.forEdition(): FindrSettings =
  if (!Edition.GO) this
  else copy(speechToText = false, quickLook = false, blurPrivatePreviews = false, privateScoring = false, indexAudio = false)
