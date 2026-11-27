package com.craneai.findr.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import org.json.JSONObject

/**
 * What this phone is, as far as the embedder cares: chip, core layout, memory, GPU driver, NPU. Different phones need
 * different setups (the build notes: on the A06 the GPU halves image time but nearly OOMs on text; a Snapdragon 8 Gen 3 has
 * an NPU build of the model). The profile is read once; the calibration result (what actually ran fastest here) is
 * stored next to it, so each phone keeps its own best setup.
 */
data class DeviceProfile(
  val model: String,
  val socManufacturer: String,
  val socModel: String,
  val ramMb: Long,
  val bigCores: Int,
  val bigMaxMhz: Int,
  val littleCores: Int,
  val littleMaxMhz: Int,
  val hasOpenCl: Boolean,
  val npuModelSuffix: String?,   // e.g. "Qualcomm_SM8650": Google publishes NPU builds for these chips
  /** ActivityManager.isLowRamDevice(): Android Go (ro.config.low_ram). */
  val lowRamDevice: Boolean = false,
  /** ActivityManager.getMemoryClass(): the Java heap limit in MB, small on low-end phones. */
  val memoryClassMb: Int = 0,
  /** Build.MANUFACTURER and Build.BRAND, for [Oem]; empty when unknown. */
  val manufacturer: String = "",
  val brand: String = "",
  /** Performance cores (all clusters above the lowest efficiency cluster); defaults to [bigCores] for legacy callers. */
  val perfCores: Int = bigCores,
) {
  /** The class this phone falls in, which picks its [Tuning] ([Tuning.forDevice]). */
  val deviceClass: DeviceClass get() = DeviceClass(RamClass.of(ramMb), SocTier.of(bigMaxMhz), Oem.of(manufacturer, brand))

  /** Budget phone: 4 GB or less. Keeps memory headroom (the GPU text path is never used) and paces harder. */
  val lowRam: Boolean get() = ramMb <= MemoryBudget.TIGHT_TOTAL_MB

  /** Which model stack this phone gets ([MemoryProfile.choose]); [override] is the debug setting, null in normal use. */
  fun memoryProfile(override: String? = null): MemoryProfile = MemoryProfile.choose(ramMb, lowRamDevice, memoryClassMb, override)

  fun toJson(): JSONObject = JSONObject()
    .put("model", model).put("soc", "$socManufacturer $socModel").put("ram_mb", ramMb)
    .put("big_cores", bigCores).put("perf_cores", perfCores).put("big_max_mhz", bigMaxMhz).put("little_cores", littleCores)
    .put("little_max_mhz", littleMaxMhz).put("opencl", hasOpenCl).put("npu_model", npuModelSuffix ?: JSONObject.NULL)
    .put("low_ram_device", lowRamDevice).put("memory_class_mb", memoryClassMb).put("class", deviceClass.toString())

  /** Core topology classified from per-CPU maximum frequencies ([classifyCores]). */
  data class CoreLayout(
    val bigCores: Int,
    val perfCores: Int,
    val littleCores: Int,
    val bigMaxMhz: Int,
    val littleMaxMhz: Int,
  )

  companion object {
    /** Chips with an NPU build of EmbeddingGemma 2 on Hugging Face (litert-community), by Build.SOC_MODEL. */
    private val NPU_BUILDS = mapOf(
      "SM8550" to "Qualcomm_SM8550", "SM8650" to "Qualcomm_SM8650", "SM8750" to "Qualcomm_SM8750",
      "SM8850" to "Qualcomm_SM8850", "QCS8275" to "Qualcomm_QCS8275", "MT6991" to "MediaTek_MT6991",
      "MT6993" to "MediaTek_MT6993",
    )

    /**
     * Classifies per-CPU `cpuinfo_max_freq` readings (in kHz) and optional kernel `cpu_capacity` values into performance
     * vs efficiency cores.
     *
     * Multi-cluster chips (1+3+4, 1+3+2+2 such as Snapdragon 8 Gen 3) have several performance clusters above a single
     * efficiency tier. When per-CPU `cpu_capacity` is available and heterogeneous (>20% spread, e.g., Helio G99 A76=1024
     * vs A55=348 even at close clock speeds), capacity separates the efficiency cluster from performance cores directly;
     * otherwise a >5% frequency gap separates them. Uniform clusters report all cores as performance cores and 0 little cores.
     */
    fun classifyCores(freqsKhz: List<Int>, capacities: List<Int> = emptyList()): CoreLayout {
      val valid = freqsKhz.filter { it > 0 }
      if (valid.isEmpty()) return CoreLayout(0, 0, 0, 0, 0)
      val maxKhz = valid.max()
      if (capacities.size == freqsKhz.size) {
        val pairs = freqsKhz.zip(capacities).filter { (f, c) -> f > 0 && c > 0 }
        if (pairs.size == valid.size) {
          val maxCap = pairs.maxOf { it.second }
          val minCap = pairs.minOf { it.second }
          val capCutoff = (minCap * 1.20).toLong()
          if (maxCap.toLong() > capCutoff) {
            val little = pairs.filter { it.second.toLong() <= capCutoff }
            val perf = pairs.filter { it.second.toLong() > capCutoff }
            return CoreLayout(
              bigCores = perf.size,
              perfCores = perf.size,
              littleCores = little.size,
              bigMaxMhz = maxKhz / 1000,
              littleMaxMhz = (little.maxOfOrNull { it.first } ?: 0) / 1000,
            )
          }
        }
      }
      val minKhz = valid.min()
      val cutoffKhz = (minKhz * 1.05).toLong()
      if (maxKhz.toLong() <= cutoffKhz) {
        return CoreLayout(
          bigCores = valid.size,
          perfCores = valid.size,
          littleCores = 0,
          bigMaxMhz = maxKhz / 1000,
          littleMaxMhz = 0,
        )
      }
      val little = valid.filter { it.toLong() <= cutoffKhz }
      val perf = valid.filter { it.toLong() > cutoffKhz }
      return CoreLayout(
        bigCores = perf.size,
        perfCores = perf.size,
        littleCores = little.size,
        bigMaxMhz = maxKhz / 1000,
        littleMaxMhz = (little.maxOrNull() ?: 0) / 1000,
      )
    }

    fun read(context: Context): DeviceProfile {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
      val nCpus = Runtime.getRuntime().availableProcessors()
      val freqs = (0 until nCpus).mapNotNull { cpu ->
        runCatching {
          File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
        }.getOrNull()
      }
      val caps = if (freqs.size == nCpus) {
        (0 until nCpus).mapNotNull { cpu ->
          runCatching {
            File("/sys/devices/system/cpu/cpu$cpu/cpu_capacity").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
          }.getOrNull()
        }
      } else {
        emptyList()
      }
      val layout = classifyCores(freqs, if (caps.size == freqs.size) caps else emptyList())
      val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
      val socMaker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
      val openCl = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so").any { File(it).exists() }
      return DeviceProfile(
        model = Build.MODEL,
        socManufacturer = socMaker,
        socModel = socModel,
        ramMb = mem.totalMem / (1024 * 1024),
        bigCores = layout.bigCores,
        bigMaxMhz = layout.bigMaxMhz,
        littleCores = layout.littleCores,
        littleMaxMhz = layout.littleMaxMhz,
        hasOpenCl = openCl,
        npuModelSuffix = NPU_BUILDS.entries.firstOrNull { socModel.uppercase().startsWith(it.key) }?.value,
        lowRamDevice = am.isLowRamDevice,
        memoryClassMb = am.memoryClass,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
        perfCores = layout.perfCores,
      )
    }
  }
}
