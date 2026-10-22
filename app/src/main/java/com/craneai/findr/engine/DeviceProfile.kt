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
) {
  /** The class this phone falls in, which picks its [Tuning] ([Tuning.forDevice]). */
  val deviceClass: DeviceClass get() = DeviceClass(RamClass.of(ramMb), SocTier.of(bigMaxMhz), Oem.of(manufacturer, brand))

  /** Budget phone: 4 GB or less. Keeps memory headroom (the GPU text path is never used) and paces harder. */
  val lowRam: Boolean get() = ramMb <= 4_500

  /** Which model stack this phone gets ([MemoryProfile.choose]); [override] is the debug setting, null in normal use. */
  fun memoryProfile(override: String? = null): MemoryProfile = MemoryProfile.choose(ramMb, lowRamDevice, memoryClassMb, override)

  fun toJson(): JSONObject = JSONObject()
    .put("model", model).put("soc", "$socManufacturer $socModel").put("ram_mb", ramMb)
    .put("big_cores", bigCores).put("big_max_mhz", bigMaxMhz).put("little_cores", littleCores)
    .put("little_max_mhz", littleMaxMhz).put("opencl", hasOpenCl).put("npu_model", npuModelSuffix ?: JSONObject.NULL)
    .put("low_ram_device", lowRamDevice).put("memory_class_mb", memoryClassMb).put("class", deviceClass.toString())

  companion object {
    /** Chips with an NPU build of EmbeddingGemma 2 on Hugging Face (litert-community), by Build.SOC_MODEL. */
    private val NPU_BUILDS = mapOf(
      "SM8550" to "Qualcomm_SM8550", "SM8650" to "Qualcomm_SM8650", "SM8750" to "Qualcomm_SM8750",
      "SM8850" to "Qualcomm_SM8850", "QCS8275" to "Qualcomm_QCS8275", "MT6991" to "MediaTek_MT6991",
      "MT6993" to "MediaTek_MT6993",
    )

    fun read(context: Context): DeviceProfile {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
      // Core clusters from cpufreq: the fastest max frequency is the "big" cluster.
      val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
      }
      val top = freqs.maxOrNull() ?: 0
      val big = freqs.count { it == top }
      val little = freqs.filter { it != top }
      val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
      val socMaker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else ""
      val openCl = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so").any { File(it).exists() }
      return DeviceProfile(
        model = Build.MODEL,
        socManufacturer = socMaker,
        socModel = socModel,
        ramMb = mem.totalMem / (1024 * 1024),
        bigCores = big,
        bigMaxMhz = top / 1000,
        littleCores = little.size,
        littleMaxMhz = (little.maxOrNull() ?: 0) / 1000,
        hasOpenCl = openCl,
        npuModelSuffix = NPU_BUILDS.entries.firstOrNull { socModel.uppercase().startsWith(it.key) }?.value,
        lowRamDevice = am.isLowRamDevice,
        memoryClassMb = am.memoryClass,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
      )
    }
  }
}
