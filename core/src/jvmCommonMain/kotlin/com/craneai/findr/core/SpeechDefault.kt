package com.craneai.findr.core

/**
 * D26: whether speech to text starts on, from the language the person chose at first run. The speech model (Parakeet) reads
 * English only. For Luganda, Swahili and other speech the audio match alone finds voice notes better today
 * (the research notes: Luganda r@1 0.37 audio-only vs 0.30 with the transcripts gated), while for English the
 * transcripts double it (0.40 → 0.81 on the A06's own index). So it starts on only for English. Applied once, when onboarding
 * finishes; afterwards Settings → Add-ons is the person's own choice. It never turns speech on that was off (the lite
 * profile keeps it off).
 */
object SpeechDefault {
  fun atFirstRun(appLanguageTag: String, current: Boolean): Boolean =
    current && appLanguageTag.substringBefore('-').equals("en", ignoreCase = true)
}
