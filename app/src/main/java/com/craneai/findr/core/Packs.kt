package com.craneai.findr.core

import com.craneai.findr.R

/** "142 MB", as a resource so the unit can be written in the reader's language. */
val PackDef.sizeCopy: PackCopy get() = PackCopy(R.string.pack_size_mb, listOf(sizeMb.toInt()))

/**
 * The packs, and every number the copy quotes, in one place. Source of each number: the research notes
 * (speech: the phone's own index on the Galaxy A06, 89 queries; Quick look: D2 reversed, the CPU-contention test).
 * Update a number here and every screen follows.
 */
object PackCatalog {
  object Facts {
    /** Speech search, phone index: share of speech queries with the right file first, without and with the pack. */
    const val SPEECH_RIGHT_FIRST_BEFORE_PCT = 40
    const val SPEECH_RIGHT_FIRST_AFTER_PCT = 81
    /** Extra phone seconds per minute of video while reading (A06, speech stage). */
    const val SPEECH_EXTRA_SECONDS_PER_VIDEO_MINUTE = 13
    /** The required files of `findr-speech-models`: equals the sum over `AsrModelPack.files` (a test keeps them equal). */
    const val SPEECH_SIZE_BYTES = 142_772_368L
    /** TinyCLIP-61M/32, both towers (image dynamic int8, text weight-only int8): equals the sum over `QuickLookPack.files` (a test keeps them equal). */
    const val QUICK_LOOK_SIZE_BYTES = 117_164_024L
    /**
     * Photos made searchable by meaning per hour on the Galaxy A06, end to end (1,000 new 12 MP photos, the release app
     * started on the charger, `d2-bench.sh ready` with READY_APK, D10): with quick look (TinyCLIP) 95% in 438 s, 950 photos
     * in 7.3 min, ~7,800 an hour; without it 95% at ~87 min (the words pass, then EmbeddingGemma at ~5 s a photo; measured
     * on a debug build, but OCR and EmbeddingGemma are native code).
     */
    const val QUICK_LOOK_PHOTOS_PER_HOUR_BEFORE = 660
    const val QUICK_LOOK_PHOTOS_PER_HOUR_AFTER = 7_800
    /** How much quick look slows EmbeddingGemma's own reading of a photo while both run (A06, 50% duty, A/B/A: +5.5%). */
    const val QUICK_LOOK_SLOWDOWN_PCT = 5
    /** Marqo/nsfw-image-detection-384 (Apache-2.0) plus its licence: equals the sum over `PrivatePack.files` (a test keeps them equal). */
    const val PRIVATE_SIZE_BYTES = 22_501_653L
    /**
     * How much the scoring pass slows EmbeddingGemma's own reading of a photo while both run, as shipped: 20% duty, niced (A06,
     * measured A/B/A, mean +4.8%, median +2.7%; 60% duty was +15.7%: the research notes). A test holds it at or under the 5% budget.
     */
    const val PRIVATE_SLOWDOWN_PCT = 5
  }

  const val SPEECH = PackIds.SPEECH
  const val QUICK_LOOK = PackIds.QUICK_LOOK
  const val PRIVACY = PackIds.PRIVACY

  val speech = PackDef(
    id = SPEECH,
    nameRes = R.string.pack_speech_name,
    benefit = PackCopy(R.string.pack_speech_benefit, listOf(Facts.SPEECH_RIGHT_FIRST_AFTER_PCT, Facts.SPEECH_RIGHT_FIRST_BEFORE_PCT)),
    cost = PackCopy(
      R.plurals.pack_speech_cost,
      listOf(Facts.SPEECH_EXTRA_SECONDS_PER_VIDEO_MINUTE),
      quantity = Facts.SPEECH_EXTRA_SECONDS_PER_VIDEO_MINUTE,
    ),
    sizeBytes = Facts.SPEECH_SIZE_BYTES,
    archiveName = "trawl-speech-models.zip",
    available = true,
    enabledIn = { it.speechToText },
    withEnabled = { s, on -> s.copy(speechToText = on) },
    byDefault = true,
  )

  /** D2, D10: a small image-text model (TinyCLIP) reads every photo on the CPU first (engine/QuickLookPass.kt); EmbeddingGemma fills in behind it. */
  val quickLook = PackDef(
    id = QUICK_LOOK,
    nameRes = R.string.pack_quicklook_name,
    benefit = PackCopy(
      R.plurals.pack_quicklook_benefit,
      listOf(PackCopy.Grouped(Facts.QUICK_LOOK_PHOTOS_PER_HOUR_AFTER), Facts.QUICK_LOOK_PHOTOS_PER_HOUR_BEFORE),
      quantity = Facts.QUICK_LOOK_PHOTOS_PER_HOUR_AFTER,
    ),
    cost = PackCopy(R.string.pack_quicklook_cost, listOf(Facts.QUICK_LOOK_SLOWDOWN_PCT)),
    sizeBytes = Facts.QUICK_LOOK_SIZE_BYTES,
    archiveName = "trawl-quicklook-models.zip",
    available = true,
    enabledIn = { it.quickLook },
    withEnabled = { s, on -> s.copy(quickLook = on) },
    byDefault = true,
  )

  /** A small classifier marks photos that look private; previews of those are blurred (core/PrivateLook.kt). Blur only: nothing is hidden or reordered. */
  val privacy = PackDef(
    id = PRIVACY,
    nameRes = R.string.pack_privacy_name,
    benefit = PackCopy(R.string.pack_privacy_benefit),
    cost = PackCopy(R.string.pack_privacy_cost, listOf(Facts.PRIVATE_SLOWDOWN_PCT)),
    sizeBytes = Facts.PRIVATE_SIZE_BYTES,
    archiveName = "trawl-private-models.zip",
    available = true,
    enabledIn = { it.privateScoring },
    withEnabled = { s, on -> s.copy(privateScoring = on) },
    recommendedFor = PackCopy(R.string.pack_privacy_recommended),
    byDefault = true,
  )

  val all: List<PackDef> = listOf(speech, quickLook, privacy)

  fun byId(id: String): PackDef? = all.firstOrNull { it.id == id }
}
