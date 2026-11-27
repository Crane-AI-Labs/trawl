package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {
  @Test fun helioG85TwoPlusSix() {
    val freqs = List(6) { 1_800_000 } + List(2) { 2_000_000 }
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 2, perfCores = 2, littleCores = 6, bigMaxMhz = 2_000, littleMaxMhz = 1_800),
      DeviceProfile.classifyCores(freqs),
    )
  }

  @Test fun helioG99TwoPlusSix() {
    val freqs = List(6) { 2_000_000 } + List(2) { 2_200_000 }
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 2, perfCores = 2, littleCores = 6, bigMaxMhz = 2_200, littleMaxMhz = 2_000),
      DeviceProfile.classifyCores(freqs),
    )
  }

  @Test fun snapdragon8Gen3OnePlusThreePlusTwoPlusTwo() {
    // Galaxy S24 Ultra: 2x 2.27 GHz A520 + 2x 2.96 GHz A720 + 3x 3.15 GHz A720 + 1x 3.40 GHz X4
    val freqs = listOf(2_270_000, 2_270_000, 2_960_000, 2_960_000, 3_150_000, 3_150_000, 3_150_000, 3_400_000)
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 6, perfCores = 6, littleCores = 2, bigMaxMhz = 3_400, littleMaxMhz = 2_270),
      DeviceProfile.classifyCores(freqs),
    )
  }

  @Test fun flagshipOnePlusThreePlusFour() {
    // 4x 1.80 GHz little + 3x 2.42 GHz mid + 1x 2.84 GHz prime
    val freqs = List(4) { 1_800_000 } + List(3) { 2_420_000 } + listOf(2_840_000)
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 4, perfCores = 4, littleCores = 4, bigMaxMhz = 2_840, littleMaxMhz = 1_800),
      DeviceProfile.classifyCores(freqs),
    )
  }

  @Test fun balancedFourPlusFour() {
    val freqs = List(4) { 2_000_000 } + List(4) { 2_400_000 }
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 4, perfCores = 4, littleCores = 4, bigMaxMhz = 2_400, littleMaxMhz = 2_000),
      DeviceProfile.classifyCores(freqs),
    )
  }

  @Test fun uniformFourCoreHasNoLittleCluster() {
    val freqs = List(4) { 2_000_000 }
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 4, perfCores = 4, littleCores = 0, bigMaxMhz = 2_000, littleMaxMhz = 0),
      DeviceProfile.classifyCores(freqs),
    )
  }

  @Test fun emptyOrUnreadableCpufreqReturnsZeros() {
    val zero = DeviceProfile.CoreLayout(bigCores = 0, perfCores = 0, littleCores = 0, bigMaxMhz = 0, littleMaxMhz = 0)
    assertEquals(zero, DeviceProfile.classifyCores(emptyList()))
    assertEquals(zero, DeviceProfile.classifyCores(listOf(0, -1, 0)))
  }

  @Test fun efficiencyClusterToleranceGroupsNearEqualFreqsAndIgnoresOrder() {
    // Efficiency cores with <=5% variance stay grouped together; core ordering does not matter.
    val freqs = listOf(3_000_000, 2_600_000, 2_600_000, 2_600_000, 1_850_000, 1_800_000, 1_800_000, 1_800_000)
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 4, perfCores = 4, littleCores = 4, bigMaxMhz = 3_000, littleMaxMhz = 1_850),
      DeviceProfile.classifyCores(freqs),
    )
    // All cores within 5% of min -> treated as one uniform cluster.
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 4, perfCores = 4, littleCores = 0, bigMaxMhz = 2_080, littleMaxMhz = 0),
      DeviceProfile.classifyCores(listOf(2_000_000, 2_000_000, 2_050_000, 2_080_000)),
    )
  }

  @Test fun perfCoresDefaultsToBigCoresAndAppearsInJson() {
    val legacy = DeviceProfile(
      model = "S24U", socManufacturer = "Qualcomm", socModel = "SM8650", ramMb = 12_000,
      bigCores = 6, bigMaxMhz = 3_400, littleCores = 2, littleMaxMhz = 2_270,
      hasOpenCl = true, npuModelSuffix = "Qualcomm_SM8650",
    )
    assertEquals(6, legacy.perfCores)
    val json = legacy.toJson()
    assertEquals(6, json.getInt("big_cores"))
    assertEquals(6, json.getInt("perf_cores"))
    assertEquals(2, json.getInt("little_cores"))
  }

  @Test fun cpuCapacitySeparatesMatchedClockBigLittleClusters() {
    // Matched 2.0 GHz clocks on big + little cores: cpu_capacity (1024 vs 348, like Helio G99 A76 vs A55) still finds 2+6.
    val freqs = List(6) { 2_000_000 } + List(2) { 2_000_000 }
    val caps = List(6) { 348 } + List(2) { 1_024 }
    assertEquals(
      DeviceProfile.CoreLayout(bigCores = 2, perfCores = 2, littleCores = 6, bigMaxMhz = 2_000, littleMaxMhz = 2_000),
      DeviceProfile.classifyCores(freqs, caps),
    )
  }
}
