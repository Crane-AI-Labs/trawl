package com.craneai.findr.engine

import com.craneai.findr.core.LearningSpeed

/**
 * The per-phone settings that decide how hard indexing leans on the phone: threads, how much of the time the side lanes run, how
 * warm the battery may get, the on-battery per-run caps. [DEFAULT] is exactly what every phone ran before profiles existed, so a
 * phone with no profile behaves as it always did. [forDevice] picks a phone's profile from its hardware (see the device-profile
 * study); a profiling build can also override single settings from a key=value file ([parse]) to try one without a rebuild.
 *
 * What is NOT here on purpose: anything that changes what is indexed or how results are ranked, and the battery policy of
 * [IndexPolicy] (charger first, 30% floor), which every phone shares.
 */
data class Tuning(
  /** EmbeddingGemma's CPU threads while Trawl is in front or the screen is on; null is LiteRT-LM's default (4). */
  val textThreadsOn: Int? = null,
  /** The same once the screen has been off for [Embedder.SCREEN_OFF_SETTLE_MS]. */
  val textThreadsOff: Int = Embedder.SCREEN_OFF_TEXT_THREADS,
  /** Quick-look image tower ONNX threads (TinyCLIP). */
  val clipImageThreads: Int = 2,
  /** Privacy scorer ONNX threads. */
  val privacyThreads: Int = 2,
  /** Speech (Parakeet) threads. */
  val asrThreads: Int = 2,
  /** Share of the time the quick-look lane runs while EmbeddingGemma embeds (1.0 = flat out). */
  val quickDuty: Double = QuickLookPass.BUSY_DUTY,
  /** The same for the privacy lane. */
  val privacyDuty: Double = PrivacyPass.BUSY_DUTY,
  /** Battery temperature at which the worker rests [warmRestMs] between files. */
  val warmC: Double = IndexWorker.WARM_C,
  /** Battery temperature at which the worker stops and cools down for [coolDownMs]. */
  val hotStopC: Double = IndexWorker.HOT_STOP_C,
  val warmRestMs: Long = IndexWorker.WARM_REST_MS,
  val coolDownMs: Long = IndexWorker.COOL_DOWN_MS,
  /** Try the GPU for the vision encoder first (false: the CPU, for a GPU that is slower than the cores). */
  val visionGpu: Boolean = true,
  /** Threads of the vision encoder when it runs on the CPU (visionGpu false, or after a GPU failure); null is LiteRT-LM's default. */
  val visionCpuThreads: Int? = null,
  /**
   * Keep the models loaded through a polite wait even on a memory-tight phone (true), or give them back after
   * [PolitePolicy.RELEASE_AFTER_MS] there (false, the long-standing rule). A system low-memory signal still releases them.
   */
  val keepModelsWhenPolite: Boolean = false,
  /**
   * ONNX Runtime memory options for the quick-look and privacy sessions (native-mem study): [ortArena] is the CPU arena allocator
   * (true: ORT's default, which keeps freed blocks), [ortPattern] the memory-pattern planner (true: the default), [ortInitDevice]
   * puts weights straight into plain allocations instead of the arena (false: the default).
   */
  val ortArena: Boolean = true,
  val ortPattern: Boolean = true,
  val ortInitDevice: Boolean = false,
  /**
   * Longest speech segment Parakeet is given, in seconds (native-mem study): its ONNX arena grows with the longest input it has seen
   * and never shrinks, so a shorter cap is a lower memory ceiling at a small WER cost (Mac, pooled: 15 s 12.8%, 10 s 14.9%).
   */
  val asrMaxSpeechS: Int = 15,
  /** On battery, full-quality new files per worker run, and the encode time they may take. */
  val newFilesPerRun: Int = IndexPolicy.NEW_FILES_PER_RUN,
  val newEncodeMsPerRun: Long = IndexPolicy.NEW_ENCODE_MS_PER_RUN,
  /**
   * Videos and recordings' share of the bulk pass's busy time while photos and documents wait ([MediaShare]); 0 is the old order
   * (every photo first). Measured: internal/research/learn-control.md.
   */
  val mediaShare: Double = MEDIA_SHARE,
  /** Share of each quick-look batch that is videos ([QuickMix]); 0 puts them after every photo. */
  val quickVideoShare: Double = QUICK_VIDEO_SHARE,
  /** Where in a video quick look takes its frame, at most (ms; capped at a quarter of the length). 0 is the first frame, often a black fade-in. */
  val quickVideoSeekMs: Long = QUICK_VIDEO_SEEK_MS,
  /** On battery, short videos and recordings that arrived since the backlog was counted get full quality too, within the per-run caps. */
  val newMediaOnBattery: Boolean = NEW_MEDIA_ON_BATTERY,
  /** "Short" for the line above, by size: about 40 s of phone video, about 10 minutes of voice recording. */
  val newVideoMaxMb: Int = 40,
  val newAudioMaxMb: Int = 10,
  /**
   * Polite mode also on the lock screen (the screen is on but the phone is locked: a glance at the clock or a notification). True is
   * the long-standing rule; false waits only for an unlocked phone in another app ([LearningSpeed.FAST]).
   */
  val politeOnLockScreen: Boolean = true,
  /**
   * Pictures per minute for the minutes of a long video past [FileProcessor.DEEP_WINDOWS] (the first half hour). Default 1: one picture
   * (GPU embedding plus OCR) from the middle of each later minute, so a long video is not blind in its second half. 0 is the old rule
   * (speech and sound labels only past half an hour); 2..6 read that many evenly spaced frames; the audio vector stays off past
   * half an hour. Measured on a tablet over 5 h of video: +5% total time, no memory or heat change (internal/research/long-video.md).
   */
  val deepFramesPerMin: Int = 1,
) {
  companion object {
    const val MEDIA_SHARE = 0.2
    const val QUICK_VIDEO_SHARE = 0.2
    const val QUICK_VIDEO_SEEK_MS = 1_500L
    const val NEW_MEDIA_ON_BATTERY = true

    val DEFAULT = Tuning()

    @Volatile private var installed: Tuning = DEFAULT

    /** The phone's own tuning (class profile plus knob-file overrides), before the person's [LearningSpeed] is laid over it. */
    @Volatile private var deviceBase: Tuning = DEFAULT

    /** The tuning in force: set once per process by [install]. */
    val current: Tuning get() = installed

    /** Chosen at engine start from the phone; [overrides] come from a profiling build's knob file, empty in a shipped build. */
    fun install(device: DeviceProfile, overrides: Map<String, String> = emptyMap(), speed: LearningSpeed = LearningSpeed.BALANCED, roomy: Boolean = false) {
      deviceBase = apply(forDevice(device), overrides)
      installed = LearningProfile.tuning(deviceBase, speed, roomy)
    }

    /** The person changed "how hard Trawl works": the new speed over the same phone tuning. Thread counts apply when models next load. */
    fun useSpeed(speed: LearningSpeed, roomy: Boolean) {
      installed = LearningProfile.tuning(deviceBase, speed, roomy)
    }

    /** The profile for a phone, by its [DeviceClass]. */
    fun forDevice(device: DeviceProfile): Tuning = forClass(device.deviceClass)

    /**
     * Every class is on [DEFAULT]: the device-profile study measured each knob on four phones (Infinix Helio G99, TECNO KN4 MT6769,
     * TECNO KN3 Unisoc UMS9230E, Samsung tablet MT8781; Mali-G57 MC2 and G52 MC2) and none paid for itself.
     *  - Vision on the GPU is the fastest or equal everywhere it was tried (Infinix +27%, tablet +40%, Unisoc equal) and the CPU
     *    variant that is 10% faster on the Mali-G52 phone draws twice the current and runs 5 °C hotter.
     *  - Text threads (4, 6, 8), the quick-look and privacy duty cycles, and the vision CPU threads changed no per-file time: the
     *    main lane is the GPU image embed, ~95% of a photo.
     *  - Temperatures stayed under [IndexWorker.WARM_C] in every window, so the thresholds never engaged.
     *  - Giving the models back after [PolitePolicy.RELEASE_AFTER_MS] on a 4 GB phone beat keeping them: 290 MB more free memory
     *    when the next app starts, launches about 8% faster, and keeping saved no reload, because the run hands off after a longer
     *    wait and starts again from cold (KN4 cycle windows).
     * What does differ by phone is whether the screen-off hours are usable (the TECNO KN4 ran 13% of them, the Infinix
     * 100%): that is not a tuning value but something the app measures ([ScreenOffMeter]) and tells the person about. A class that
     * a measurement moves off [DEFAULT] is added here, with a test.
     */
    fun forClass(@Suppress("UNUSED_PARAMETER") c: DeviceClass): Tuning = DEFAULT

    /** key=value lines (blank lines and # comments skipped) → a map; a line that is not key=value is ignored. */
    fun parse(text: String): Map<String, String> = text.lineSequence().map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
      .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    /** [base] with the named settings replaced; an unknown key or a value that does not parse changes nothing. */
    fun apply(base: Tuning, o: Map<String, String>): Tuning {
      fun int(k: String) = o[k]?.toIntOrNull()
      fun dbl(k: String) = o[k]?.toDoubleOrNull()
      fun lng(k: String) = o[k]?.toLongOrNull()
      return base.copy(
        textThreadsOn = when (val v = o["textThreadsOn"]) {
          null -> base.textThreadsOn
          "default" -> null
          else -> v.toIntOrNull()?.takeIf { it in 1..16 } ?: base.textThreadsOn
        },
        textThreadsOff = int("textThreadsOff")?.takeIf { it in 1..16 } ?: base.textThreadsOff,
        clipImageThreads = int("clipImageThreads")?.takeIf { it in 1..8 } ?: base.clipImageThreads,
        privacyThreads = int("privacyThreads")?.takeIf { it in 1..8 } ?: base.privacyThreads,
        asrThreads = int("asrThreads")?.takeIf { it in 1..8 } ?: base.asrThreads,
        quickDuty = dbl("quickDuty")?.takeIf { it in 0.05..1.0 } ?: base.quickDuty,
        privacyDuty = dbl("privacyDuty")?.takeIf { it in 0.05..1.0 } ?: base.privacyDuty,
        warmC = dbl("warmC")?.takeIf { it in 30.0..50.0 } ?: base.warmC,
        hotStopC = dbl("hotStopC")?.takeIf { it in 35.0..55.0 } ?: base.hotStopC,
        warmRestMs = lng("warmRestMs")?.takeIf { it in 0..300_000 } ?: base.warmRestMs,
        coolDownMs = lng("coolDownMs")?.takeIf { it in 0..600_000 } ?: base.coolDownMs,
        visionGpu = o["visionGpu"]?.let { it == "true" } ?: base.visionGpu,
        visionCpuThreads = when (val v = o["visionCpuThreads"]) {
          null -> base.visionCpuThreads
          "default" -> null
          else -> v.toIntOrNull()?.takeIf { it in 1..16 } ?: base.visionCpuThreads
        },
        keepModelsWhenPolite = o["keepModelsWhenPolite"]?.let { it == "true" } ?: base.keepModelsWhenPolite,
        ortArena = o["ortArena"]?.let { it == "true" } ?: base.ortArena,
        ortPattern = o["ortPattern"]?.let { it == "true" } ?: base.ortPattern,
        ortInitDevice = o["ortInitDevice"]?.let { it == "true" } ?: base.ortInitDevice,
        asrMaxSpeechS = int("asrMaxSpeechS")?.takeIf { it in 5..28 } ?: base.asrMaxSpeechS,
        newFilesPerRun = int("newFilesPerRun")?.takeIf { it in 0..500 } ?: base.newFilesPerRun,
        newEncodeMsPerRun = lng("newEncodeMsPerRun")?.takeIf { it in 0..3_600_000 } ?: base.newEncodeMsPerRun,
        mediaShare = dbl("mediaShare")?.takeIf { it in 0.0..0.9 } ?: base.mediaShare,
        quickVideoShare = dbl("quickVideoShare")?.takeIf { it in 0.0..0.9 } ?: base.quickVideoShare,
        quickVideoSeekMs = lng("quickVideoSeekMs")?.takeIf { it in 0..60_000 } ?: base.quickVideoSeekMs,
        newMediaOnBattery = o["newMediaOnBattery"]?.let { it == "true" } ?: base.newMediaOnBattery,
        newVideoMaxMb = int("newVideoMaxMb")?.takeIf { it in 0..2_000 } ?: base.newVideoMaxMb,
        newAudioMaxMb = int("newAudioMaxMb")?.takeIf { it in 0..2_000 } ?: base.newAudioMaxMb,
        politeOnLockScreen = o["politeOnLockScreen"]?.let { it == "true" } ?: base.politeOnLockScreen,
        deepFramesPerMin = int("deepFramesPerMin")?.takeIf { it in 0..6 } ?: base.deepFramesPerMin,
      )
    }
  }
}
