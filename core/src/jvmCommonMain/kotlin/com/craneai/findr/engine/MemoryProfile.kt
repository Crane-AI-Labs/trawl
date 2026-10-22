package com.craneai.findr.engine

/**
 * How much of Findr's model stack a phone can hold at once (the research notes).
 *
 *  - [STANDARD]: phones with 3 GB or more. EmbeddingGemma reads text on the CPU and pictures on the GPU (70 vision tokens),
 *    quick look runs as a first pass beside it, speech to text is on: today's app, unchanged.
 *  - [LITE]: 2 GB phones, Android Go among them (the itel and Tecno phones many Ugandans use). EmbeddingGemma loads its text
 *    model only (no vision or audio encoder, so no GPU), pictures are read by the quick-look model on the CPU, and the text
 *    read from a picture (OCR) is embedded by EmbeddingGemma's text model as its own modality, which keeps screenshots and memes
 *    findable by meaning. The quick-look pass runs only beside the words pass (before EmbeddingGemma loads), so photos are
 *    searchable by meaning within minutes there too; speech to text starts off.
 */
enum class MemoryProfile {
  STANDARD, LITE;

  companion object {
    /**
     * Phones that report less than this much RAM get [LITE]. A "2 GB" phone reports ~1.8-1.95 GB once the kernel and modem
     * have taken theirs, a "3 GB" one ~2.7-2.9 GB (the Galaxy A06, sold as 4 GB, reports 3,633 MiB), so 2,600 MiB splits the
     * two with room on both sides and leaves every 3 GB phone on [STANDARD].
     */
    const val LITE_BELOW_MB = 2_600L

    /** A Java heap class this small marks a low-end phone too, but only counts when the RAM total can't be read. */
    const val LOW_MEMORY_CLASS_MB = 128

    /** Debug override values (a debug build's setting; release builds always choose automatically). */
    const val OVERRIDE_LITE = "lite"
    const val OVERRIDE_STANDARD = "standard"

    /**
     * The profile for a phone. [isLowRamDevice] is ActivityManager.isLowRamDevice() (ro.config.low_ram: Android Go), [totalRamMb]
     * is MemoryInfo.totalMem in MiB (0 when unknown), [memoryClassMb] is ActivityManager.getMemoryClass(). [override] forces
     * one ("lite" or "standard"; anything else is automatic).
     */
    fun choose(totalRamMb: Long, isLowRamDevice: Boolean, memoryClassMb: Int, override: String? = null): MemoryProfile = when {
      override == OVERRIDE_LITE -> LITE
      override == OVERRIDE_STANDARD -> STANDARD
      isLowRamDevice -> LITE
      totalRamMb > 0 -> if (totalRamMb < LITE_BELOW_MB) LITE else STANDARD
      memoryClassMb in 1..LOW_MEMORY_CLASS_MB -> LITE
      else -> STANDARD
    }

    /**
     * The lean first download: a phone that would get [STANDARD] but holds only the text-only model (the core bundle) runs the lite
     * stack, which is the one that model fits, until a vision-capable model is installed (the next start then runs [STANDARD]).
     * Never overrides a forced profile, and never touches a phone that already holds a standard model or no model at all.
     */
    fun adopt(chosen: MemoryProfile, override: String?, hasStandardModel: Boolean, hasTextOnlyModel: Boolean): MemoryProfile =
      if (override != OVERRIDE_LITE && override != OVERRIDE_STANDARD && chosen == STANDARD && !hasStandardModel && hasTextOnlyModel) LITE else chosen
  }
}

/**
 * Whether a phone is short of memory for the heavy stages, which is a different question from which model stack it gets
 * ([MemoryProfile]): the Galaxy A06 and the TECNO Spark 50 report 3.6-3.7 GB, keep the full STANDARD stack (EmbeddingGemma with
 * vision on the GPU, quick look, speech), and have only about 1.8 GB free for an app. With the stages resident together (the
 * embedding engine ~0.9 GB with its GPU buffers, the speech model ~0.25 GB, the process itself ~0.35 GB) a photo and video backlog
 * peaks at 1.3-1.4 GB, a Luganda recording with its audio encoder at 1.7-1.9 GB, and the system's low-memory killer ends the app
 * near 2 GB (the research notes). On a tight phone the indexer therefore keeps one heavy stage loaded at a time:
 * the speech model leaves before the audio encoder loads and after the last video or recording of a stretch, and the audio encoder
 * leaves when the stretch is over. Nothing about the models or their output changes, only how long each one stays in memory
 * (each reloads in 5-10 s, against a minute or more for the file it served).
 */
object MemoryBudget {
  /** Total RAM up to this (MiB) is tight: covers the "4 GB" phones (3.6-3.9 GB reported) and not the 6 GB ones (~5.5 GB). */
  const val TIGHT_TOTAL_MB = 4_600L

  /** Free RAM below this (MiB) at the start of a run is tight too: a 6 GB phone with a dozen apps open has little more than a 4 GB one. */
  const val TIGHT_AVAILABLE_MB = 1_500L

  /** [totalRamMb] is MemoryInfo.totalMem in MiB (0 when unknown), [availableMb] is MemoryInfo.availMem in MiB (0 when unknown). */
  fun tight(totalRamMb: Long, availableMb: Long): Boolean =
    (totalRamMb in 1..TIGHT_TOTAL_MB) || (availableMb in 1 until TIGHT_AVAILABLE_MB)

  /**
   * Whether the models stay loaded when a run ends, between passes, and while the app sits idle. A phone with room keeps them: the
   * next run, or a search a minute after the last one, then starts at once instead of paying a 1.6-14 s reload (the first GPU
   * load on the S24 took 14 s). A tight phone gives them back, as before: it needs that memory for the app in front.
   */
  fun keepResident(tight: Boolean): Boolean = !tight

  /**
   * An onTrimMemory level that makes an idle app (no run going) give its models back even on a roomy phone: RUNNING_LOW (10) and
   * RUNNING_CRITICAL (15), and the cached-process levels where the system is killing (MODERATE 60, COMPLETE 80). Not BACKGROUND
   * (40): that only says the app has moved into the cached list, which happens every time the person leaves it. Not UI_HIDDEN (20).
   */
  fun releaseWhenIdle(trimLevel: Int): Boolean = trimLevel == 10 || trimLevel == 15 || trimLevel >= 60
}
