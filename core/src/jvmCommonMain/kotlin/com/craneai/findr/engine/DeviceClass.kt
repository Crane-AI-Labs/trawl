package com.craneai.findr.engine

/**
 * Which kind of phone this is, for choosing its [Tuning]: how much memory it has, how fast its cores are, and whose Android it runs.
 * Three small axes instead of a model list, so a phone nobody measured still lands in the class of the phones that are like it.
 * The classes are the ones the device-profile study found to behave differently (internal/research/device-profiles.md).
 */
enum class RamClass {
  /** 4.6 GB or less ([MemoryBudget.TIGHT_TOTAL_MB]): the "4 GB" phones, ~1.8 GB free for an app. */
  TIGHT,

  /** Up to 9 GB: the "6 and 8 GB" phones. */
  MID,

  /** More than 9 GB. */
  ROOMY,
  ;

  companion object {
    const val MID_UP_TO_MB = 9_200L

    fun of(ramMb: Long): RamClass = when {
      ramMb <= 0 -> MID   // unknown: the middle, not the most cautious
      ramMb <= MemoryBudget.TIGHT_TOTAL_MB -> TIGHT
      ramMb <= MID_UP_TO_MB -> MID
      else -> ROOMY
    }
  }
}

enum class SocTier {
  /** Fastest core at 2.0 GHz or under (Helio G85, Unisoc T606/UMS9230E class: Cortex-A75/A55). */
  ENTRY,

  /** Up to 2.6 GHz (Helio G99, Dimensity 700-7xxx class: Cortex-A76/A78). */
  MID,

  /** Above that (Snapdragon 8 Gen 3, Dimensity 9xxx class). */
  HIGH,
  ;

  companion object {
    fun of(bigMaxMhz: Int): SocTier = when {
      bigMaxMhz <= 0 -> MID
      bigMaxMhz <= 2_050 -> ENTRY
      bigMaxMhz <= 2_600 -> MID
      else -> HIGH
    }
  }
}

enum class Oem {
  /** TECNO, Infinix, itel (HiOS/XOS "Hiber" freezes an app a few seconds after the screen goes off). */
  TRANSSION,
  SAMSUNG,
  XIAOMI,
  OTHER,
  ;

  companion object {
    private val TRANSSION_NAMES = setOf("tecno", "infinix", "itel", "transsion")
    private val XIAOMI_NAMES = setOf("xiaomi", "redmi", "poco")

    /** By Build.MANUFACTURER and Build.BRAND ("INFINIX MOBILITY LIMITED", "TECNO", "itel", "samsung", "Xiaomi"). */
    fun of(manufacturer: String?, brand: String?): Oem {
      val names = listOf(manufacturer, brand).map { it?.trim()?.lowercase().orEmpty() }
      return when {
        names.any { n -> TRANSSION_NAMES.any { n.startsWith(it) } } -> TRANSSION
        names.any { it.startsWith("samsung") } -> SAMSUNG
        names.any { n -> XIAOMI_NAMES.any { n.startsWith(it) } } -> XIAOMI
        else -> OTHER
      }
    }
  }
}

data class DeviceClass(val ram: RamClass, val soc: SocTier, val oem: Oem) {
  override fun toString() = "$ram/$soc/$oem"
}
